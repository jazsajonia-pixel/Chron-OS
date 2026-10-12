# Physical-device test checklist

These checks **have not been performed** by the automated build environment (no phone, no emulator/KVM, no monitor,
no Bluetooth/USB peripherals). Run them on real hardware and record the results with device model and Android version.

## Phone only
- [ ] App installs (`adb install -r app-debug.apk`) and opens to the desktop.
- [ ] Open/close/minimize/maximize/drag/resize windows with touch; taskbar and launcher work.
- [ ] Files: create folder, rename, delete, open "Welcome.txt", import a document with "Add folder…".
- [ ] Rotate phone: desktop refits, no window is lost.
- [ ] Back button closes menus/launcher, then minimizes the focused window, then leaves PocketOS.

## Smart Cursor
- [ ] Taskbar "Touchpad" opens the floating touchpad; one-finger move, tap, double-tap, two-finger tap (right click),
      two-finger scroll, tap-and-drag a window.
- [ ] Settings: speed, size, natural scroll and tap-to-click take effect immediately.

## Physical mouse and keyboard (USB OTG or Bluetooth)
- [ ] Mouse connects → taskbar shows the mouse indicator; Smart Cursor hides; Auto touchpad hides.
- [ ] Left click, right click on wallpaper (menu), scroll wheel in Settings.
- [ ] Shortcuts: Ctrl+Esc, Alt+Tab (note if the phone intercepts it), Ctrl+Alt+Arrows, Alt+F4, Ctrl+Alt+E/S/T/C.
- [ ] Typing in Text Editor with the hardware keyboard.

## External monitor (USB-C DisplayPort Alt Mode, dock, or supported wireless display)
- [ ] Record whether the phone exposes the monitor to apps (Settings → External display lists it with "presentation")
      or only mirrors.
- [ ] Desktop appears on the monitor; phone shows the companion touchpad.
- [ ] Desktop size slider; text is readable at 1080p and 4K.
- [ ] Companion keyboard "Send" types into a focused Text Editor on the monitor.
- [ ] Unplug monitor: desktop returns to the phone, windows and unsaved editor text remain.
- [ ] Re-plug: desktop starts again (if "Start automatically" is on).
- [ ] Press Home on the phone while the monitor desktop is shown: record whether the monitor keeps showing PocketOS
      (behaviour of Presentation windows while the activity is stopped varies by device).
- [ ] Note where the on-screen keyboard appears when a text field on the monitor is focused.

## Windows runtime (ARM64 phone with Winlator installed)
- [ ] Follow docs/PHASE5_RUNTIME.md "How to verify on a real phone"; record whether the sample result says "Verified".
- [ ] Confirm Winlator sees files written by PocketOS in `Download/PocketOS` (D: drive).
- [ ] Paste a real Winlator log into "Explain a runtime error" and note whether the explanation was helpful.

## PC Apps library (Phase 6)
- [ ] PC Apps → Library → Add program → pick a real `.exe`: findings appear, nothing runs, no other app opens.
- [ ] Confirm without Winlator → "Needs setup" with steps. Install Winlator, choose folder → status becomes "Ready".
- [ ] File appears in `Download/PocketOS/Apps/<name>/` and in Winlator's D: drive.
- [ ] Pick a `.dll`, `.zip` or `.bat` → explained as unsupported; "Keep in library as unsupported" and "Cancel" both work.
- [ ] Cancel during copy of a large file → no partial file left in the shared folder.
- [ ] "It didn't work" → "Use suggestion 1"; after 3 suggestions no more are offered. "It worked" saves known-good settings.
- [ ] Remove → "Remove and delete copied files" deletes the folder; "Remove only" keeps it.
- [ ] Open a `.exe` from the Files app → the wizard opens.

## Diagnostics and quality (Phase 7)
- [ ] Diagnostics → Event log lists display/input/profile events; no personal paths or e-mail addresses appear.
- [ ] Preview report → Share… opens Android's share sheet; the shared text matches the preview. Save to Home works.
- [ ] Storage → Clear temporary files / Remove leftover folders change the shown sizes; other files in Download untouched.
- [ ] There is no crash button; if PocketOS ever crashes, reopen it and check the crash notice in Diagnostics.
- [ ] Rotate, split-screen, and return from background with windows open: nothing lost.
- [ ] Run with many apps in background (low memory): PocketOS shows "Android reports low memory right now" (Settings → Performance) and keeps working.

## Release readiness (Phase 8)
- [ ] Fresh install shows the welcome tour; Skip, Back, Next, Get started; arrow keys/Enter/Esc with a keyboard.
- [ ] TalkBack: launcher, taskbar buttons, window title buttons and tour pages are announced with meaningful labels.
- [ ] Settings → Privacy text is visible; Android Settings → Apps → PocketOS → Permissions shows "No permissions".
- [ ] Install `app-release.apk` (minified) and repeat the phone-only, Files and Settings checks (R8 build not run on a device yet).
- [ ] Record: device model, Android version, adapter/monitor used, and results for each section.
