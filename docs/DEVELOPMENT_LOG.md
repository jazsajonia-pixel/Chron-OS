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
