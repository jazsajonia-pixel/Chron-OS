# PocketOS Development Log

Persistent log for resuming work. Newest phase at the bottom. Every "PASS" below refers to a command that was actually run
in the agent sandbox (Ubuntu x86_64, JDK 17, Gradle 8.11.1, Android SDK 35, **no emulator, no physical device**).

## Resume point

See the last entry's "Next" line.

---

## Phase 0 — Repository audit (2026-10-12)

- Repo `jazsajonia-pixel/Chron-OS` contained only planning docs; no code. Work happens on branch `pocketos-dev`.
- Installed JDK 17, Android cmdline-tools, platform 35, build-tools 35, Gradle 8.11.1; mingw-w64 for PE fixtures.
- No `/dev/kvm` → no emulator. Verification strategy: JVM unit tests + Robolectric (native graphics mode) + manual checklists.
- Researched display/input APIs and runtime licensing → `docs/PHASE0_AUDIT.md`.
- Changed: `docs/PHASE0_AUDIT.md`, Gradle scaffold (`settings.gradle.kts`, `build.gradle.kts`, `gradle/`, `gradlew*`, `gradle.properties`, `.gitignore`).
- Original planning docs were left untouched.

## Phase 1 — Functional shell (2026-10-12)

Implemented
- `:core` `WindowManager` (open/close/focus/minimize/maximize/snap/move/resize/cycle/area refit), `ShellSettings` + tolerant codec,
  `Calculator`, `SandboxFileSystem` (path-escape protection).
- `:app` Compose desktop: wallpaper, desktop icons, long-press context menu, window frames (drag, double-tap maximize, snap, resize
  handle, 44dp controls), taskbar with Start button / window buttons / clock, searchable launcher with "Exit PocketOS",
  toast messages; back button closes menus then minimizes focused window.
- Built-in apps: Files (PocketOS Home + Storage Access Framework folders, new folder/file, rename, delete with confirm, import copy,
  open with Android), Text Editor (Home or SAF documents, save/save-as, unsaved marker, 2 MB limit), Calculator, System Info, Settings
  (theme, wallpaper, icons, 24h clock, reduce motion, reset). Settings persist via SharedPreferences JSON; desktop session persists on stop.
- Phone portrait: windows open maximized; Files sidebar collapses into a top row below 480dp.

Build/test (actual)
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (debug APK 9.2 MB).
- `./gradlew :core:test` → 18/18 pass. `./gradlew :app:testDebugUnitTest` → 9/9 pass (Robolectric, 1280×800 landscape and 411×891 portrait).

Issue found and fixed
- Robolectric's legacy graphics mode cannot hit-test non-uniform rounded shapes (Path containment), making launcher taps fall through
  in tests. Verified with a minimal reproduction; fixed by `graphicsMode=NATIVE` in `robolectric.properties`. Also changed window focus to
  observe the Initial pointer pass without consuming, so app content always receives presses.

Limitations
- Not run on a physical device or emulator. Touch behaviour verified only through Robolectric input injection.

Next: Phase 2 — input abstraction and Smart Cursor.

## Phase 2 — Input system and Smart Cursor (2026-10-12)

Implemented
- `:core/input`: `TouchpadGestureInterpreter` (move with acceleration, tap/double-tap click, tap-and-drag, long-press drag,
  two-finger tap = right click, two-finger scroll with natural/traditional direction), `PointerController` (clamped cursor →
  dispatch requests), `ShortcutMap` (14 default chords avoiding Meta), `PhysicalInputSummary` (excludes virtual/built-in devices),
  `TouchpadPolicy` (AUTO hides touchpad when a mouse is connected).
- `:app/input`: `SmartCursor` delivers synthetic `MotionEvent`s (finger taps, drag sequences, mouse-source right click and
  ACTION_SCROLL) **only into PocketOS's own window** registered as a `DesktopTarget`; `PhysicalInputMonitor` (InputManager listener);
  `KeyShortcutHandler` (called from `Activity.dispatchKeyEvent`), Alt-hold window switcher, Escape closes menus.
- UI: floating touchpad in a separate Popup window (no touch-stream collisions), optional left/right buttons (hold left = drag),
  cursor overlay, taskbar indicators for keyboard/mouse, right-click on wallpaper, physical mouse hides Smart Cursor.
- Settings: speed, size, tap-to-click, natural scroll, click buttons, touchpad Auto/Always/Never, device list, shortcut toggles,
  limitations text.

Bugs found by tests and fixed
- Gestures starting with two fingers were treated as cursor movement (now scroll/right-click).

Build/test (actual)
- `./gradlew :app:assembleDebug` → SUCCESS. `:core:test` → 30/30. `:app:testDebugUnitTest` → 20/20 (11 new input tests).

Limitations
- Physical mouse/keyboard verified only with Robolectric-injected events; no real Bluetooth/USB device tested.
- Some OEMs intercept Alt+Tab/Meta; documented fallbacks.

Next: Phase 3 — external monitor desktop mode.

## Phase 3 — External monitor desktop mode (2026-10-12)

Implemented
- `:core/display`: `DisplayInfo`, `ExternalDisplayPolicy` (picks the largest usable presentation display; explains
  "none connected", private, mirror-only, off, too small), `DesktopScale` (keeps the monitor desktop 1024–1920 dp wide,
  user multiplier), `DisplaySessionReducer` (auto-start, user stop, move between displays, disconnect).
- `:app/display`: `DisplaySessionManager` (public `DisplayManager.DisplayListener`, event log, reports
  `FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS`), `DesktopPresentation` (Android `Presentation` hosting the same `Desktop`
  composable with its own density; key events routed to shortcuts).
- Phone becomes a companion: full-size touchpad, Launcher / Next window / Keyboard (send text, Backspace, Enter, Tab, Esc) /
  "Show desktop on phone". Physical-keyboard keys are forwarded to the monitor desktop when the phone window has focus.
- Failure path: `InvalidDisplayException` or a vanished display → session stops, desktop stays on phone, message shown.
- Settings → External display: status explanation, start/stop, re-check, auto-start, desktop size, per-display details.
- `docs/HARDWARE_TEST_CHECKLIST.md` for real-device verification.

Bugs found by tests and fixed
- Test-only race: editor's async load overwrote a buffer written by the test; test now waits for load.

Build/test (actual)
- `:app:assembleDebug` SUCCESS. `:core:test` 36/36. `:app:testDebugUnitTest` 28/28 (8 new external-display tests using
  Robolectric `ShadowDisplayManager`, with FLAG_PRESENTATION set via `ShadowDisplay`). Two consecutive clean `--rerun-tasks` runs.

Limitations / not verified
- No physical monitor, DisplayPort Alt Mode phone, dock, or wireless display was available. All display behaviour is
  verified only against Robolectric's simulated display. Presentation behaviour while the activity is stopped, and IME
  placement, are device-dependent and listed in the checklist.

Next: Phase 4 — hardware-aware configuration.

## Phase 4 — Device capability detection and automatic profiles (2026-10-12)

Implemented
- `:core/capability`: `Capability<T>` (Known / Unknown(reason) / Unsupported(reason)), `DeviceCapabilities`,
  `ProfileSelector` (deterministic Auto rules: overheating ≥ SEVERE, Android Battery Saver, low-RAM flag, < 3 GB →
  Battery Saver; unknown RAM → Balanced; ≥ 8 GB + ≥ 8 cores + 64-bit + cool → Performance; else Balanced; manual choice
  always wins with warnings), `ProfileSettings` (shadows, animations, comfortable window count, Windows runtime resolution
  cap, refresh cap), `DisplayRecommendation` (mode chosen only from the monitor's reported list; null if none; UI scale hint).
- `:app/capability/CapabilityMonitor`: public APIs only (Build, ActivityManager.MemoryInfo / memoryClass / isLowRamDevice,
  StatFs, PowerManager power-save + thermal, PackageManager Vulkan feature, GLES config). Re-checks on Battery Saver
  broadcast, thermal listener, display changes, profile change, `onTrimMemory`, and a manual "Check again".
- Effects applied: window shadows, launcher fade animation, soft window-count hint (never blocks), automatic monitor UI scale,
  optional `preferredDisplayModeId` on the Presentation window (off by default).
- Settings → Performance (profile chips, plain-language reason, warnings, effective effects, reduce motion);
  Settings → External display (auto/override size, current vs recommended mode, opt-in switch);
  System Info rewritten on the capability model, states "Unknown"/"Not supported" instead of guessing; privacy note.

Bugs found by tests and fixed (real robustness issues)
- `PackageManager.getSystemAvailableFeatures()` returning null crashed startup → now Unknown.
- `PowerManager.addThermalStatusListener` throws "Listener failed to set" when the thermal service is unavailable → caught.
- `Display.getMode()/getRefreshRate()` throw if the active mode is missing from the supported list → reported as unknown.

Build/test (actual)
- `:app:assembleDebug` SUCCESS. `:core:test` 42/42. `:app:testDebugUnitTest` 35/35 (7 new, using ShadowPowerManager,
  ShadowActivityManager, ShadowDisplayManager.setSupportedModes).

Limitations
- "Accurate for tested devices" can only be checked on Robolectric's simulated device here; no physical phone tested.
- Thresholds are conservative heuristics, documented in code and shown to the user.

Next: Phase 5 — Windows runtime feasibility.
