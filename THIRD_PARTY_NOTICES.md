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

## Not bundled — user-installed separately
| Component | Licence | Notes |
|---|---|---|
| Winlator — https://github.com/brunodev85/winlator | LGPL-2.1 (per its repository) | PocketOS only detects it (`<queries>` for `com.winlator`) and launches its main screen when the user asks. No code is copied or bundled |
| Wine, Box64 (inside Winlator) | LGPL-2.1+, MIT | Shipped by Winlator, not by PocketOS |

PocketOS uses no Microsoft code or branding. "Windows" is used only descriptively for the file format.
