# Chron OS — PocketOS

PocketOS is an **Android app** that turns a phone into a PC-like desktop: windows, a taskbar, a launcher, a file manager,
settings, a Smart Cursor with touchpad gestures, physical mouse/keyboard support, and a desktop on an external monitor
when the phone supports it.

> PocketOS is **not a standalone or bootable operating system**. It runs inside Android like any other app. Your phone,
> its apps and its data keep working normally.

Status: version 0.2.0, development build. Verified with automated tests and ARM64 emulation of the Windows runtime — see [Testing status](#testing-status).

## Features

| Area | What it does | Honest caveat |
|---|---|---|
| Desktop shell | Windows (move, resize, snap, minimise, maximise), taskbar, launcher, wallpapers, themes, session restore | — |
| Apps | Files (private PocketOS Home + import from Android's picker), Text Editor, Calculator, Settings, System Info, PC Apps, Diagnostics | Files only sees PocketOS Home and files you pick; it does not browse all phone storage |
| Smart Cursor | On-screen touchpad: move, tap, double-tap, two-finger tap (right click), two-finger scroll, tap-and-drag; speed/size settings | Works inside PocketOS only — it does not control other Android apps |
| Mouse & keyboard | USB/Bluetooth mouse and keyboard, shortcuts (Ctrl+Esc, Alt+Tab, Ctrl+Alt+Arrows, Alt+F4, …) | Some phones intercept shortcuts such as Alt+Tab |
| External monitor | Desktop on a monitor via Android's Presentation API; phone becomes touchpad/keyboard; desktop size control; safe fallback on unplug | **Many phones have no video output** or only mirror the screen. Settings → Display shows what your phone reports |
| Hardware-aware setup | Reads CPU ABIs, memory, GPU API levels, thermal and battery state; picks Battery saver / Balanced / Performance automatically (overridable) | Unknown values are shown as "Unknown", never guessed |
| PC Apps (.exe/.msi) | **Built-in Windows runtime** (Wine 10.10 + Box64 0.4.4): checks files first, then **runs programs and installers in a PocketOS window** on the desktop (no other screen opens); programs an installer creates appear in the library automatically; one-tap self-test; settings and up to 3 fixes applied automatically; known-good settings remembered | 64-bit ARM phones only. Needs about 520 MB once set up. **Not every Windows program works**, and results differ between phones |
| Diagnostics | Sanitized event log, crash notice and safe start, storage cleanup, compatibility report with preview before sharing | Nothing is uploaded automatically |

## Install

Requirements: Android 8.0 (API 26) or newer. Running Windows programs needs a 64-bit ARM (arm64-v8a) phone; the APK contains only arm64 code.

### Build from source
```bash
# JDK 17, the Android SDK (platform 35, build-tools 35), NDK 24.0.8215888 and CMake 3.22.1 are required.
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
   adb shell am start -n com.chron.os/com.chronos.pocketos.MainActivity
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
- PC programs: first, PC Apps → Runtime → "Set up" (one time, unpacks the built-in Windows runtime; no download).
  Then PC Apps → Library → "Add program", pick a `.exe` or `.msi`. PocketOS checks the file, explains what it found and
  keeps a copy. Tap **Install** for installers (the programs they install are added to the library) or **Run** for
  programs. The program opens in a normal PocketOS window on your desktop — move, resize or minimise it like any other
  window. The Smart Cursor (or a mouse) controls it, a keyboard types into it while its window is focused, and the
  window's Keyboard button shows the on-screen keyboard. Close the window (or press Stop) to end the program. The
  phone never switches to another screen. Afterwards report "It worked" / "It didn't work"; PocketOS remembers working settings
  or applies the next fix automatically. "Run self-test" in the Runtime tab proves that Windows programs start on
  your phone.

## Privacy

- No runtime permission prompts. The manifest declares network access (only Windows programs that go online use it),
  audio settings, vibration, wake lock and a foreground service so a running program keeps going with the screen off.
  No storage permission: Windows programs see only PocketOS's own folders (drives D: and E:).
- No ads, no analytics. PocketOS itself sends nothing anywhere. Files are read only when you pick them.
- The diagnostics log removes e-mail addresses, keys/tokens, user names in paths, storage roots and document URIs.
  A compatibility report is created only when you press "Preview report", and shared only when you press "Share…".

## Known limitations

- Android app, not an operating system; it cannot replace Android or boot on its own.
- External display depends on the phone's hardware and Android build. Many phones (including many mid-range models)
  have no DisplayPort Alt Mode. Some only mirror. Behaviour when pressing Home while the monitor desktop is shown varies.
- Smart Cursor and shortcuts work inside PocketOS only (no input injection into other apps, by design).
- The built-in Windows runtime requires a 64-bit ARM phone. Compatibility of any given program is not guaranteed;
  games and programs that need special drivers, anti-cheat or kernel components will not work.
- The app targets Android API 28 because the runtime starts its own Linux programs from app storage. Some Android
  versions show an "older Android version" notice when installing; it cannot be published on Google Play this way.
- The application id is `com.chron.os` (was `com.chronos.pocketos`); uninstall 0.1.0 first — see
  [docs/PHASE6_EMBEDDED_RUNTIME.md](docs/PHASE6_EMBEDDED_RUNTIME.md) for why it must not change.
- One Windows program (or installer) runs at a time; close its window before starting another.
- Not yet tested on a physical phone, emulator, monitor, mouse or keyboard — see below.

## Testing status

All results come from a Linux build machine without a phone, emulator (no KVM) or monitor:

- `:core:test` — 70 JVM unit tests (window manager, input gestures, display policy, capabilities, runtime rules,
  PE inspector incl. 300-case corruption fuzzing, library, diagnostics).
- `:app:testDebugUnitTest` — 66 Robolectric UI tests (+1 opt-in runtime integration test) (shell, phone portrait, input, external display via shadow displays,
  capabilities, built-in runtime, install/run flow, library/wizard, quality/diagnostics, onboarding).
- The bundled runtime itself (retargeted to `/data/data/com.chron.os`) was run under **ARM64 emulation (qemu-user)**:
  Box64 + Wine 10.10 ran the self-test program, an NSIS installer, the program it installed, and an MSI package.
  `EmbeddedRuntimeIntegrationTest` runs PocketOS's real runtime code against the real runtime files. Details:
  [docs/PHASE6_EMBEDDED_RUNTIME.md](docs/PHASE6_EMBEDDED_RUNTIME.md). The Android graphics/X server path still needs
  a real phone.
- `lintDebug`: 0 errors.

Manual test steps for real hardware: [docs/HARDWARE_TEST_CHECKLIST.md](docs/HARDWARE_TEST_CHECKLIST.md).

## Troubleshooting

| Problem | What to try |
|---|---|
| `adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A build with another signing key is installed: `adb uninstall com.chron.os`, then install again |
| Monitor shows a mirror of the phone, not the desktop | Your phone mirrors only. Settings → Display shows whether a "presentation" display was offered. Try a different adapter or the phone's desktop/"external display" setting if it has one |
| Monitor is detected but nothing appears | Settings → Display → "Check again". Check Diagnostics → Event log for the reason |
| Pointer too fast/small | Settings → Input → Pointer speed / Pointer size |
| Alt+Tab does nothing | The phone intercepts it; use Ctrl+Alt+Right/Left |
| PC Apps says "Needs setup" | PC Apps → Runtime → "Set up". On non-ARM64 phones Windows programs cannot run |
| A program does not start or looks wrong | Press "It didn't work", then "Try the next fix" (up to 3, applied automatically). Runtime menu → Logs, paste into "Explain a runtime error" |
| An installer finished but nothing appeared | Open the installer entry → "Find installed programs", or PC Apps → Runtime → "Open Windows desktop" |
| Runtime setup failed | Free at least 600 MB, then "Try again". "Repair" re-checks an existing setup |
| PocketOS crashed | Reopen it. After two quick crashes it starts with an empty desktop (safe start). Diagnostics shows the crash notice |
| Storage is full | Diagnostics → Storage → "Clear temporary files" / "Remove leftover folders" |

## Project layout

```
core/   Pure Kotlin/JVM logic (window manager, input, display policy, capabilities, runtime rules, PE inspector, library, diagnostics)
app/    Android app (Jetpack Compose UI, Android integrations)
winruntime/  Built-in Windows runtime (derived from Winlator, LGPL-2.1): display server, Wine/Box64 launcher, runtime files
docs/   Audit, development log, runtime feasibility, hardware test checklist
tools/pe-fixtures/  Sources and build script for the Windows test fixtures
tools/runtime/  Scripts used to adapt the runtime (path retargeting, library conversion)
```
Product documents: PRODUCT_VISION.md, DEVELOPMENT_PHASES.md, AI_AGENT_RULES.md. Licences: THIRD_PARTY_NOTICES.md.
