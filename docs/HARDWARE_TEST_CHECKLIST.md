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
