# Phase 6 — Built-in Windows runtime (no separate Winlator app)

Supersedes the handoff design in [PHASE5_RUNTIME.md](PHASE5_RUNTIME.md). PocketOS now installs and runs `.exe` and
`.msi` files itself.

## What changed

| Before (0.1.0) | Now (0.2.0) |
|---|---|
| PocketOS only examined files and copied them to `Download/PocketOS` | PocketOS keeps its own copy and starts it |
| User had to install Winlator, create a container, find the file on D: | One tap: **Run** (programs) or **Install** (installers) |
| Self-test: copy sample → run it in Winlator by hand → come back | Self-test runs inside PocketOS and is verified automatically |
| Recovery fixes had to be applied by hand in Winlator | PocketOS applies resolution and graphics settings itself on every start |
| Installed programs were invisible to PocketOS | Programs an installer creates are added to the library automatically |

## Architecture

```
:app (PocketOS, package com.chron.os)                 :winruntime (derived from Winlator 11, LGPL-2.1)
 ├─ library/ImportManager  — import, Run/Install,       ├─ xenvironment/RootFSInstaller  (unpacks rootfs.tzst)
 │   session results, installed-program discovery       ├─ container/ContainerManager    (Windows environment)
 ├─ runtime/RuntimeManager — state, setup, launches     ├─ XServerDisplayActivity        (X server + Wine, own process)
 └─ runtime/EmbeddedWinlatorBackend — drives :winruntime└─ assets: Linux userland, Wine 10.10, Box64 0.4.4,
                                                            Turnip/Vortek/VirGL/Gladio drivers, DXVK/VKD3D/WineD3D
```

- **Separate process.** Every runtime activity and service runs in `:winruntime`. A crashing Windows program cannot
  take the PocketOS desktop down; when a program exits, the runtime writes `files/pocketos_runtime/last_session.json`,
  finishes and ends its process, and PocketOS picks up the result in `MainActivity.onResume`.
- **Drives.** `D:` = PocketOS's programs folder (`files/pc-apps`), `E:` = PocketOS Home, `C:` = Windows, `Z:` = runtime root.
- **Installed programs.** Before an installer starts, PocketOS records the existing shortcuts. Afterwards it scans the
  container's Desktop and Start Menu (`.desktop` files written by Wine's menu builder, plus `.lnk` files) and adds new
  ones, ignoring uninstallers, manuals and web links. If an installer creates no shortcut, new `.exe` files in
  Program Files are offered instead (max. 5, skipping uninstallers, updaters, redistributables).
- **Settings.** `RuntimeProfile` is written into the container before each start: resolution, graphics driver
  (automatic = Turnip on Adreno, Vortek otherwise; compatibility = VirGL), DirectX wrapper (DXVK, or WineD3D).

## Why the package id is `com.chron.os`

Winlator's prebuilt Linux userland contains the absolute path `/data/data/com.winlator/files` in 257 places
(ELF interpreters, `ld.so.cache`, scripts, Mesa/Box64 configs). `com.chron.os` has exactly the same length, so
`tools/runtime/retarget_assets.py` replaces it byte-for-byte without changing any offsets, then re-packs the archives.
The script verifies that no old path remains. Changing the application id would break the runtime.

## Why targetSdk 28

The runtime starts Box64/Wine executables from the app's private storage. Since Android 10 that is only permitted for
apps targeting API 28 or lower (Winlator, Termux and similar apps do the same). PocketOS is distributed as an APK, not
through Google Play, which requires a newer target. Consequences: Android shows an "built for an older version" notice
on some phones; lint checks for the old target are disabled on purpose.

## Verification done (2026-10-12)

| Check | Result |
|---|---|
| Retargeted archives vs. originals: file list, sizes, modes, owners, symlink targets | identical (md5 of `tar -tv`) |
| Old path left in any archive or compiled native library | none |
| Box64 0.4.4 from `/data/data/com.chron.os/files/rootfs` (ARM64 via qemu-user) | starts, reports version |
| PocketOS self-test `pocketos-sample.exe` from `D:` via Box64 + Wine 10.10 | wrote `POCKETOS_FIXTURE_OK <nonce>` |
| NSIS installer `sample_setup_nsis.exe /S` | exit 0, program installed in `C:\Program Files\Sample Notes` |
| Installed `hello_console_x64.exe` | printed `POCKETOS_FIXTURE_OK hello_console` |
| MSI package via `msiexec /i … /qn` | exit 0 |
| Installer with shortcuts (`sample_setup_shortcuts.exe`) | Wine created `.lnk` and `.desktop` files on Desktop and Start Menu |
| `EmbeddedRuntimeIntegrationTest` (real backend + real assets, Robolectric) | unpacks 412 MB, creates the environment with Wine's DLLs, maps drives, applies settings, finds shortcuts |
| Debug and release APK | build; package `com.chron.os`, arm64-v8a, runtime in `:winruntime` |

Run the integration test with
`./gradlew :app:testDebugUnitTest -Ppocketos.runtimeIntegration=true --tests '*EmbeddedRuntimeIntegrationTest*'`.

## Not verified yet (needs an ARM64 phone)

The host checks above run the same Wine/Box64 binaries under ARM64 emulation, but not Android itself. Still to be
confirmed on a phone: the Java X server and graphics drivers (Turnip/Vortek), audio, touch input, first-start time,
and the return to PocketOS after a program exits. Use the checklist in `HARDWARE_TEST_CHECKLIST.md`.

## Licensing

`winruntime/` is a modified copy of [winlator-app](https://github.com/brunodev85/winlator-app) (commit 3981d86),
licensed LGPL-2.1 (see `winruntime/LICENSE`). Its full source, including our changes (`winruntime/MODIFICATIONS.md`),
is in this repository, which satisfies the LGPL source requirement for the distributed APK. The bundled components
(Wine, Box64, Mesa, glibc, PulseAudio, DXVK, VKD3D) keep their own licenses.
