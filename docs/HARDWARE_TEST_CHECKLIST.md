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

## Built-in Windows runtime (ARM64 phone, PocketOS 0.3.0)
- [ ] Uninstall PocketOS 0.1.0 (`com.chronos.pocketos`) first; install 0.3.0 (`com.chron.os`, updates 0.2.0 in place). Note any "older Android version" notice.
- [ ] PC Apps → Runtime → "Set up": progress reaches 100 %, status "Ready". Record the time it took and free space before/after.
- [ ] "Run self-test": a "Self-test" window opens on the PocketOS desktop (the phone stays on PocketOS), the sample runs, the window closes and PocketOS shows "Passed". Record time to first start.
- [ ] "Open Windows desktop": a "Windows desktop" window opens with the file manager; drives D: (programs) and E: (PocketOS Home) are visible.
- [ ] Smart Cursor over the program window moves the Windows pointer; tap clicks; two-finger tap right-clicks; two-finger scroll scrolls.
- [ ] Touching the program window directly also clicks where you touch. A USB/Bluetooth mouse works over it.
- [ ] Hardware keyboard types into the program while its window is focused; Alt+Tab / Meta still control PocketOS.
- [ ] The window's Keyboard button shows the on-screen keyboard and typed text reaches the program.
- [ ] Move, resize, maximise and minimise the program window; restore it: the program is still running and redraws.
- [ ] Closing the window (or Stop → confirm) ends the program; starting another program then works.
- [ ] On an external monitor: the program window appears on the monitor desktop; the phone touchpad controls it.
- [ ] Press Home while a program runs, then reopen PocketOS: the program is still running (notification shown).
- [ ] Paste real error text from a program into "Explain a runtime error" and note whether the explanation was helpful.
- [ ] Record GPU (Adreno/Mali) and whether "Compatibility graphics" changes anything for a program that draws wrongly.

## PC Apps library
- [ ] PC Apps → Library → Add program → pick a real `.exe`: findings appear before anything runs.
- [ ] Before runtime setup → "Needs setup" with "Set up runtime" button. After setup → "Ready" with Run / Install.
- [ ] Install a real installer (e.g. 7-Zip, Notepad++): the installer window opens; after it finishes, PocketOS lists the installed program.
- [ ] Run that installed program from the library; save a file to E: and find it in PocketOS Files.
- [ ] Installer that makes no shortcut: "Find installed programs" offers its .exe from Program Files.
- [ ] Pick a `.dll`, `.zip` or `.bat` → explained as unsupported; "Keep in library as unsupported" and "Cancel" both work.
- [ ] Cancel during copy of a large file → no partial file left behind (Diagnostics → Storage shows no leftovers).
- [ ] "It didn't work" → "Try the next fix"; the next start uses the new settings; after 3 fixes no more are offered. "It worked" saves known-good settings.
- [ ] Remove → "Remove and delete PocketOS's copy" deletes the folder; "Remove only" keeps it.
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
- [ ] Settings → Privacy text is visible; Android Settings → Apps → PocketOS → Permissions shows no user-granted permissions (network/foreground service are install-time only).
- [ ] Install `app-release.apk` (minified) and repeat the phone-only, Files and Settings checks (R8 build not run on a device yet).
- [ ] Record: device model, Android version, adapter/monitor used, and results for each section.
