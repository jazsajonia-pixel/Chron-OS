# PocketOS Windows engine (0.4.0)

PocketOS runs Windows programs with its own engine. There is no Winlator code, no Linux system image (rootfs),
no X server and no second app or screen.

## What it is
- **Wine 11.19 built for Android** (`engine/wine/build.sh`): the Unix side (`ntdll.so`, `win32u.so`, `wineandroid.so`,
  `wineserver`, the `wine` loader) is an Android arm64 build linked against Android's own libc (bionic). The Windows
  side contains ARM64, ARM64EC and 32-bit x86 DLLs.
- **FEX** (`libarm64ecfex.dll`, `libwow64fex.dll`) translates x64 and x86 program code to ARM inside Wine, the same way
  Windows on ARM does (ARM64EC and WoW64). PocketOS registers them under `HKLM\Software\Microsoft\Wow64`.
- **WineHost** (`app/.../engine/WineHost.java`) is PocketOS's side of Wine's Android display driver. Every Windows
  window becomes an Android view placed on the PocketOS desktop at the position Wine gives it, and Wine draws into
  it directly. The Windows desktop itself is invisible: PocketOS's desktop, icons and taskbar show around the windows.

## How a program runs
1. First start: PocketOS unpacks the engine from the APK (`assets/engine/*.zip`) and Wine creates the Windows
   environment (`files/wineprefix`, a few minutes on a phone). Drive D: is PocketOS's programs folder, E: is Home.
2. PocketOS starts Wine's desktop (`explorer.exe /desktop=Default,,android`). Every program then is its own Wine
   process (`wine <program.exe>`; shortcuts, `.msi` and `.bat` through `start.exe /wait /unix`), so PocketOS knows
   exactly when it ends. Several programs can run at once.
3. After an installer ends, PocketOS looks for new shortcuts in the Windows Start menu and desktop folders and adds
   them to the library and to the PocketOS desktop. One tap on such an icon opens the program.

## Input
- Touch, the Smart Cursor (including hover), a mouse and scrolling go to the Windows window under the pointer.
- Touches outside Windows windows reach PocketOS. While the launcher, the window switcher or a desktop menu is open,
  Windows windows step aside.
- The keyboard goes to the Windows window tapped last, until a PocketOS window is focused. Meta (Windows key)
  shortcuts, Back and Home always stay with PocketOS. The on-screen keyboard types through an input connection.

## Requirements and limits
- 64-bit ARM phone, Android 9 or later, kernel with 4 KB memory pages (checked at start).
- The app targets API 28 so that it may start the engine's programs from its private storage (Android forbids this
  for newer target levels). Distribute as an APK.
- Not tested on a phone yet at the time of writing: the engine was built and the PocketOS side was tested with
  automated tests, but the build PC cannot run Android programs. "Runtime → Engine log → Copy log" collects what
  is needed to fix problems.
- No .NET (Mono) or HTML (Gecko) engines are bundled. 3D acceleration is not wired up yet (Wine falls back to
  software drawing for GDI programs; OpenGL/Direct3D programs may fail).
