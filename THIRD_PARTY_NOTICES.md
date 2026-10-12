# Third-party notices

## Bundled in the APK
| Component | Licence |
|---|---|
| AndroidX (core, activity, lifecycle, documentfile, Compose UI/Foundation/Material 3, graphics-path native lib, startup, profileinstaller) | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| `assets/sample/pocketos-sample.exe` — PocketOS's own test program (source: `tools/pe-fixtures/hello_gui.c`) | Project code; built with mingw-w64 GCC, which links mingw-w64 CRT startup code (ZPL-2.1 / permissive) and libgcc (GCC Runtime Library Exception). Review before commercial distribution. |

## Test-only (not in the APK)
JUnit 4 (EPL-1.0), Robolectric (MIT), AndroidX Test (Apache-2.0). Fixtures were built with mingw-w64, NSIS (zlib/libpng
licence) and msitools/wixl (LGPL-2.1+ tools; the produced MSI is project data) and exercised with Wine (LGPL-2.1+) on the
build host only.

## Bundled Windows engine (since 0.4.0)
PocketOS 0.4.0 no longer contains any Winlator code, files or prebuilt archives. The engine is built from upstream
sources by `engine/wine/build.sh`.

| Component | Licence | Notes |
|---|---|---|
| Wine 11.19 — https://www.winehq.org (source: https://dl.winehq.org/wine/source/11.x/wine-11.19.tar.xz) | LGPL-2.1-or-later | Built for Android (arm64 Unix side; i386, ARM64EC and ARM64 Windows side) in `assets/engine`. Modified only by `engine/wine/patches/` (Java class name and socket name of the Android display driver). `WineHost.java` is adapted from Wine's `dlls/wineandroid.drv/WineActivity.java` and is LGPL-2.1-or-later. Source for the exact build: the Wine tarball above plus the patches in this repository. |
| FEX 2610 (ARM64EC/WoW64 emulator DLLs `libarm64ecfex.dll`, `libwow64fex.dll`) — https://github.com/FEX-Emu/FEX | MIT | Unmodified binaries from the FEX Ubuntu PPA (`fex-emu-wine` package) |
| FreeType 2.13.3 — https://freetype.org | FreeType License (FTL) | Font rendering for Wine, built for Android. Portions of this software are copyright © The FreeType Project (www.freetype.org). All rights reserved. |

PocketOS uses no Microsoft code or branding. "Windows" is used only descriptively for the file format.
