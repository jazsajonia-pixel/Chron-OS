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
| Winlator (winlator-app, commit 3981d86) — https://github.com/brunodev85/winlator-app | LGPL-2.1 | **Bundled** as the `winruntime/` module with modifications (see `winruntime/MODIFICATIONS.md`); full source is in this repository |
| Wine 10.10 — https://www.winehq.org | LGPL-2.1+ | Bundled inside the runtime archives (prebuilt by Winlator) |
| Box64 0.4.4 — https://github.com/ptitSeb/box64 | MIT | Bundled inside the runtime archives |
| Mesa (Turnip, VirGL, Zink), virglrenderer, DXVK, VKD3D-Proton, glibc, PulseAudio, FluidSynth | MIT / LGPL / zlib (per project) | Bundled inside the runtime archives / native libraries, unmodified except for the install-path retargeting |

PocketOS uses no Microsoft code or branding. "Windows" is used only descriptively for the file format.
