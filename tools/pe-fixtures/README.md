# PE test fixtures

Small public-domain Windows programs built with MinGW-w64 (`./build.sh`). They are used by `:core` tests
(PE inspection) and `pocketos-sample.exe` is bundled as the runtime verification sample.

| File | What it is |
|---|---|
| hello_console_x64.exe | x86-64 console program with a version resource ("Sample Notes" 1.2.3.4); prints `POCKETOS_FIXTURE_OK hello_console` |
| hello_console_x86.exe | 32-bit x86 console build of the same program |
| hello_gui_x64.exe / app assets `sample/pocketos-sample.exe` | GUI-subsystem sample: reads `pocketos_nonce.txt` next to itself and writes `pocketos_sample_ok.txt` containing `POCKETOS_FIXTURE_OK <nonce>` |
| hello_x64.dll | DLL (not runnable on its own) |
| needs_missing_dll_x64.exe | imports `pocketos_missing.dll`, which does not exist |
| sample_setup_nsis.exe | real NSIS 3 installer (`makensis`, `sample_installer.nsi`) that installs hello_console_x64.exe |
| sample_setup.msi | real Windows Installer package (`wixl`, `sample_installer.wxs`) |
| script.bat, archive.zip, linux.bin | tiny non-Windows inputs for rejection tests |

## Host-only execution evidence (2026-10-12)
Run on the x86-64 Ubuntu build machine with Ubuntu's `wine64` 10.0~repack-12ubuntu1, **not on Android**:

- `wine64 hello_console_x64.exe` → printed `POCKETOS_FIXTURE_OK hello_console`, exit 0
- `wine64 hello_console_x86.exe` → same output, exit 0
- `wine64 hello_gui_x64.exe` with nonce `deadbeefcafe0001` → wrote `POCKETOS_FIXTURE_OK deadbeefcafe0001\r\n`, exit 0
- `wine64 needs_missing_dll_x64.exe` → exit 53, log saved as `core/src/test/resources/runtime-logs/wine10_missing_dll.log`
- `wine64 notes.exe` (a text file renamed to .exe) → exit 1, log saved as `wine10_not_a_program.log`

- `wine64 sample_setup_nsis.exe /S` → exit 0; `C:\Program Files\Sample Notes\` then contained `hello_console_x64.exe` and `uninstall.exe`
- `wine64 msiexec /i sample_setup.msi /qn` → exit 0

This proves the fixtures are valid Windows programs and that the marker protocol works under Wine. It does **not**
prove anything about Winlator/Box64 on an Android phone; that must be checked on an ARM64 device.
