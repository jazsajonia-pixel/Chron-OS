# Chron OS — PocketOS

PocketOS is an **Android app** that turns a phone into a PC-like desktop: windows, a taskbar, a launcher, a file manager,
settings, a Smart Cursor with touchpad gestures, physical mouse/keyboard support, and a desktop on an external monitor
when the phone supports it.

> PocketOS is **not a standalone or bootable operating system**. It runs inside Android like any other app. Your phone,
> its apps and its data keep working normally.

Status: version 0.1.0, development build. Verified with automated tests only — see [Testing status](#testing-status).

## Features

| Area | What it does | Honest caveat |
|---|---|---|
| Desktop shell | Windows (move, resize, snap, minimise, maximise), taskbar, launcher, wallpapers, themes, session restore | — |
| Apps | Files (private PocketOS Home + import from Android's picker), Text Editor, Calculator, Settings, System Info, PC Apps, Diagnostics | Files only sees PocketOS Home and files you pick; it does not browse all phone storage |
| Smart Cursor | On-screen touchpad: move, tap, double-tap, two-finger tap (right click), two-finger scroll, tap-and-drag; speed/size settings | Works inside PocketOS only — it does not control other Android apps |
| Mouse & keyboard | USB/Bluetooth mouse and keyboard, shortcuts (Ctrl+Esc, Alt+Tab, Ctrl+Alt+Arrows, Alt+F4, …) | Some phones intercept shortcuts such as Alt+Tab |
| External monitor | Desktop on a monitor via Android's Presentation API; phone becomes touchpad/keyboard; desktop size control; safe fallback on unplug | **Many phones have no video output** or only mirror the screen. Settings → Display shows what your phone reports |
| Hardware-aware setup | Reads CPU ABIs, memory, GPU API levels, thermal and battery state; picks Battery saver / Balanced / Performance automatically (overridable) | Unknown values are shown as "Unknown", never guessed |
| PC Apps (.exe/.msi) | Examines files **without running them** (type, 32/64-bit, installer type, version info), stores them in a library with honest statuses, copies them to a shared folder for a separate runtime, bounded step-by-step fixes, known-good settings | PocketOS does **not** contain a Windows runtime. Running programs needs the free third-party app Winlator (64-bit ARM phones only). **Many Windows programs will not work.** |
| Diagnostics | Sanitized event log, crash notice and safe start, storage cleanup, compatibility report with preview before sharing | Nothing is uploaded automatically |

## Install

Requirements: Android 8.0 (API 26) or newer.

### Build from source
```bash
# JDK 17 and the Android SDK (platform 35, build-tools 35) are required.
export ANDROID_HOME=$HOME/android-sdk          # or your SDK path; alternatively set sdk.dir in local.properties
./gradlew :app:assembleDebug                   # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test :app:testDebugUnitTest    # run all automated tests
./gradlew :app:assembleRelease                 # → app/build/outputs/apk/release/app-release.apk (minified)
```
The release build is signed with the **debug key** so it can be installed for testing. Replace `signingConfig` in
`app/build.gradle.kts` with your own keystore before any public distribution.

### Install on a phone
1. On the phone: Settings → About phone → tap "Build number" 7 times → Developer options → enable **USB debugging**.
2. Connect by USB and accept the prompt, then:
   ```bash
   adb devices                                             # the phone must be listed as "device"
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.chronos.pocketos/.MainActivity
   ```
   Alternatively copy the APK to the phone and open it (allow "Install unknown apps" for your file manager).

## Using PocketOS

- First launch shows a 5-step welcome tour (reopen it from Settings → "Show welcome tour").
- Launcher: tap the launcher button at the left of the taskbar (or press Ctrl+Esc).
- Touchpad: tap "Touchpad" in the taskbar. Move one finger to move the pointer; tap to click; two-finger tap to right-click;
  drag two fingers to scroll; tap, then touch again and move to drag (for example a window title bar).
- External monitor: connect a monitor through a USB-C video adapter/dock or supported wireless display. If the phone
  offers the monitor to apps, the desktop moves there and the phone shows a touchpad and keyboard. Settings → Display
  explains what was detected.
- PC programs: PC Apps → Library → "Add program". PocketOS examines the file and explains what it found. To run it,
  install Winlator (see PC Apps → Runtime), choose the shared folder (Download/PocketOS), then open the program from
  Winlator's D: drive. Report "It worked" / "It didn't work" so PocketOS can remember settings or suggest the next fix.

## Privacy

- No Android permissions are requested (the manifest declares none; Android adds one internal, non-user-facing
  permission for AndroidX receivers).
- No ads, no analytics, no network use. Files are read only when you pick them.
- The diagnostics log removes e-mail addresses, keys/tokens, user names in paths, storage roots and document URIs.
  A compatibility report is created only when you press "Preview report", and shared only when you press "Share…".

## Known limitations

- Android app, not an operating system; it cannot replace Android or boot on its own.
- External display depends on the phone's hardware and Android build. Many phones (including many mid-range models)
  have no DisplayPort Alt Mode. Some only mirror. Behaviour when pressing Home while the monitor desktop is shown varies.
- Smart Cursor and shortcuts work inside PocketOS only (no input injection into other apps, by design).
- PocketOS never executes Windows files itself. The supported runtime (Winlator) is a separate app that the user installs;
  it requires a 64-bit ARM phone. Compatibility of any given program is not guaranteed and is reported by the user.
- PocketOS cannot see inside the runtime's container (installed programs and their files there).
- Not yet tested on a physical phone, emulator, monitor, mouse or keyboard — see below.

## Testing status

All results come from a Linux build machine without a phone, emulator (no KVM) or monitor:

- `:core:test` — 68 JVM unit tests (window manager, input gestures, display policy, capabilities, runtime rules,
  PE inspector incl. 300-case corruption fuzzing, library, diagnostics).
- `:app:testDebugUnitTest` — 64 Robolectric UI tests (shell, phone portrait, input, external display via shadow displays,
  capabilities, runtime handoff, library/wizard, quality/diagnostics, onboarding).
- Real Windows fixture programs (built from source with mingw-w64, NSIS and wixl) were executed with **Wine 10 on the
  x86-64 build host** to prove the fixtures behave as described. This is **not** evidence that they run on Android.
- `lintDebug`: 0 errors.

Manual test steps for real hardware: [docs/HARDWARE_TEST_CHECKLIST.md](docs/HARDWARE_TEST_CHECKLIST.md).

## Troubleshooting

| Problem | What to try |
|---|---|
| `adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A build with another signing key is installed: `adb uninstall com.chronos.pocketos`, then install again |
| Monitor shows a mirror of the phone, not the desktop | Your phone mirrors only. Settings → Display shows whether a "presentation" display was offered. Try a different adapter or the phone's desktop/"external display" setting if it has one |
| Monitor is detected but nothing appears | Settings → Display → "Check again". Check Diagnostics → Event log for the reason |
| Pointer too fast/small | Settings → Input → Pointer speed / Pointer size |
| Alt+Tab does nothing | The phone intercepts it; use Ctrl+Alt+Right/Left |
| PC Apps says "Needs setup" | Follow the listed steps: install Winlator, choose the shared folder, then copy the program |
| A program fails in Winlator | Press "It didn't work", apply the suggested fix (up to 3), or paste the Winlator log into "Explain a runtime error" |
| PocketOS crashed | Reopen it. After two quick crashes it starts with an empty desktop (safe start). Diagnostics shows the crash notice |
| Storage is full | Diagnostics → Storage → "Clear temporary files" / "Remove leftover folders" |

## Project layout

```
core/   Pure Kotlin/JVM logic (window manager, input, display policy, capabilities, runtime rules, PE inspector, library, diagnostics)
app/    Android app (Jetpack Compose UI, Android integrations)
docs/   Audit, development log, runtime feasibility, hardware test checklist
tools/pe-fixtures/  Sources and build script for the Windows test fixtures
```
Product documents: PRODUCT_VISION.md, DEVELOPMENT_PHASES.md, AI_AGENT_RULES.md. Licences: THIRD_PARTY_NOTICES.md.
