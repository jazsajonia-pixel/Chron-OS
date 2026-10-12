# Phase 0 — Repository Audit and Technical Plan

Audit date: 2026-10-12. Branch: `pocketos-dev` (created from `main` @ `4500714`).

## 1. Repository state (verified)

| Item | Finding |
|---|---|
| Commits | `d7b934c Initial commit`, `4500714 Add files via upload` |
| Files | `PRODUCT_VISION.md`, `DEVELOPMENT_PHASES.md`, `AI_AGENT_RULES.md`, `AI_PROMPTS.md`, `README_START_HERE.md`, empty `README.md` |
| Source code | None. No Gradle, no Android project, no tests. |
| Classification | **Empty repository** (planning documents only). Nothing to preserve except the docs, which are kept unchanged. |
| Builds? | Not applicable before scaffolding — there was nothing to build. |

Decision: scaffold a new Android/Kotlin project following the recommended direction in PRODUCT_VISION.md §6.

## 2. Build environment (verified in the agent sandbox)

| Tool | Version |
|---|---|
| Host | Ubuntu 26.04 x86_64, 2 vCPU, 7 GB RAM, **no `/dev/kvm`** |
| JDK | OpenJDK 17.0.20.1 |
| Gradle | 8.11.1 (wrapper committed) |
| Android SDK | platform `android-35`, build-tools `35.0.0`, platform-tools |
| Cross compilers | `x86_64-w64-mingw32-gcc`, `i686-w64-mingw32-gcc` (used only to build PE test fixtures) |

Consequence: no hardware-accelerated emulator is possible here, and no physical Android device is attached.
Verification therefore relies on (a) JVM unit tests, (b) Robolectric tests that exercise real Android framework
code paths on the JVM, and (c) documented manual test checklists for physical hardware.

## 3. Architecture choices

| Choice | Decision | Trade-off |
|---|---|---|
| Language/UI | Kotlin + Jetpack Compose (Material 3) | Modern, maintainable; Compose adds ~2–4 MB to the APK. |
| Modules | `:core` (pure Kotlin/JVM) + `:app` (Android) | All state machines (windows, gestures, profiles, PE inspection, recovery, sanitising) are testable without Android. Slightly more Gradle config. |
| Persistence | SharedPreferences (settings JSON) + atomic JSON files in app-private storage (app library) | No database dependency; adequate for small metadata sets. |
| Serialization | `kotlinx.serialization` (Apache-2.0, JetBrains-maintained) | Small, no reflection. |
| External display | `DisplayManager` + `android.app.Presentation` | Presentation is the broadest supported API for showing distinct content on a secondary display without special permissions. It only works if the phone exposes a presentation-capable display (DisplayPort Alt Mode, compatible dock, or wireless display). |
| Smart Cursor | Touchpad gestures interpreted in `:core`; clicks delivered by dispatching synthetic `MotionEvent`s **into PocketOS's own view hierarchy only** | No `INJECT_EVENTS` permission, no accessibility-service injection into other apps. |
| Physical input | Compose pointer events (`PointerType.Mouse`), `onPreviewKeyEvent` for shortcuts, `InputManager` device listener for detection | Works when Android routes the device to the focused PocketOS window. |
| Windows runtime | Adapter interface in `:core`; see §5 | No runtime is faked. |

## 4. Android display and input APIs (researched)

- External displays are enumerated with `DisplayManager`; non-default display IDs are external, and
  `DISPLAY_CATEGORY_PRESENTATION` filters displays suitable for `Presentation`
  ([Android Developers – connected displays](https://developer.android.com/develop/adaptive-apps/guides/support-connected-displays),
  [Presentation reference](https://developer.android.com/reference/android/app/Presentation)).
- When an activity's display is disconnected the system moves it and may recreate it; PocketOS keeps session state
  outside the UI surface so a disconnect cannot lose data.
- `ActivityOptions.setLaunchDisplayId` is ignored on devices lacking `FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS`
  ([Microsoft Learn mirror of the API doc](https://learn.microsoft.com/en-us/dotnet/api/android.app.activityoptions.setlaunchdisplayid?view=net-android-35.0)),
  so PocketOS uses `Presentation` instead and reports that feature flag in diagnostics.
- Mouse events arrive with `SOURCE_MOUSE`; `requestPointerCapture()` is available from API 26 but is not needed for
  the MVP ([Android mouse input guide](https://developer.android.com/games/playgames/input-mouse)).

## 5. Windows compatibility runtime research

| Candidate | License | Notes |
|---|---|---|
| Wine | LGPL-2.1-or-later | Legally redistributable; modifications must be published. |
| Box64 / Box86 | MIT ([LICENSE](https://github.com/ptitSeb/box64/blob/main/LICENSE)) | x86_64 → ARM64 translator. |
| Winlator (brunodev85) | LGPL-2.1 per GitHub metadata (older tree had MIT) ([repo](https://github.com/brunodev85/winlator)) | Android app bundling Wine + Box64 + Mesa/DXVK/VKD3D. Active (last push 2026-09-22). Its manifest exports only the launcher `MainActivity`; there is no documented intent for opening an .exe from another app. |
| Termux-based stacks (Box64Droid etc.) | GPL-3.0 scripts | Require Termux and manual setup; not user-friendly. |

Embedding a Wine + Box64 + glibc + Mesa stack inside PocketOS is legally possible (with LGPL/MIT notices and
source offers), but adds roughly 150–300 MB and can only be validated on an ARM64 Android device with a working
GPU driver path. This sandbox has neither. Phase 5 therefore records this as an external verification blocker and
integrates the safe, legal path that can be built here (see `docs/PHASE5_RUNTIME.md`).

## 6. Risks

1. External display availability is device-specific (many phones have no video output over USB-C).
2. Synthetic events reach only PocketOS's own windows; Android apps and the Windows runtime are out of scope.
3. No device/emulator in this environment: UI behaviour is verified by Robolectric, not by a real screen.
4. Windows compatibility cannot be proven without ARM64 hardware.

## 7. Phase 1 implementation order

1. Gradle scaffold (`:core`, `:app`), theme, `MainActivity`.
2. `WindowManager` reducer in `:core` with tests.
3. Desktop composables: wallpaper, desktop icons, taskbar, launcher, window frames.
4. Built-in apps: File Manager, Text Editor, Settings, System Info, Calculator.
5. Settings persistence and back-button behaviour.
