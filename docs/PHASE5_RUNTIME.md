# Phase 5 — Windows runtime feasibility and integration decision

Date: 2026-10-12. Status: **integration implemented as a handoff; real execution on Android NOT verified (no ARM64 device).**

## Question
Can PocketOS legally and safely run real Windows `.exe` programs on Android, and can that be proven here?

## Candidates reviewed

| Option | License | Android ABI | Size | Health | Notes |
|---|---|---|---|---|---|
| Winlator (Wine + Box64 + X server, Android app) | LGPL-2.1 ([repo](https://github.com/brunodev85/winlator), [app source](https://github.com/brunodev85/winlator-app)) | arm64-v8a only (`abiFilters 'arm64-v8a'` in `app/build.gradle`) | v11.2 APK 157,549,075 bytes (GitHub release v11.2.0, 2026-08-19) | Active: last push 2026-09-22 (main repo), 2026-09-25 (app repo) | `targetSdkVersion 28`; exports only `com.winlator.MainActivity` (launcher). `XServerDisplayActivity` is `exported="false"`, so no third-party app can ask it to run a specific `.exe`. Container default drives: `D:` = public Download folder, `E:` = its private storage (`Container.DEFAULT_DRIVES`, `AppUtils.DIRECTORY_DOWNLOADS`). |
| Bundle Wine + Box64 inside PocketOS | Wine LGPL-2.1+ ([Debian manpage](https://manpages.debian.org/unstable/wine64/wine64-stable.1.en.html)); Box64 MIT ([license](https://github.com/ptitSeb/box64/blob/main/LICENSE)) | Needs arm64 build of Wine userland + glibc rootfs + Box64 + graphics (Turnip/VirGL/Zink) | 150 MB+ | Wine/Box64 healthy | Legally possible (LGPL relinking/source-offer duties, MIT notice), but it means building and maintaining a full Wine-on-Android stack (what Winlator is), and none of it can be run or tested without an ARM64 device. |
| Proprietary launchers | Closed / unclear redistribution | — | — | — | Rejected: cannot audit licensing or behaviour. |

## Decision
**PocketOS does not ship a Windows runtime. It integrates with an installed Winlator by handoff.**

Why:
1. **Legally clean.** PocketOS bundles no Wine/Box64 code, so it has no LGPL redistribution duties. Winlator is installed separately by the user from its official releases.
2. **Technically honest.** Winlator offers no public intent to run a specific file. PocketOS therefore copies the program into a folder Winlator can see. The user then starts it inside Winlator. PocketOS never claims a program ran unless it can read back proof.
3. **Verifiable.** The bundled sample (`pocketos-sample.exe`, 17 KB, public domain, built from `tools/pe-fixtures/hello_gui.c`) writes `POCKETOS_FIXTURE_OK <nonce>` next to itself. The nonce is a fresh random value from PocketOS for each staging, so a stale or copied note is rejected.
4. **Permission-free storage.** Staging uses a Storage Access Framework folder the user picks (recommended: `Download/PocketOS`, which Winlator sees as `D:\PocketOS`). No storage permissions are requested.

## What is implemented
- `:core/runtime`: `RuntimeCatalog`, `RuntimeAvailabilityRules` (needs arm64-v8a), `RuntimePaths` (SAF document id → `D:\...` or "add as a drive"), `SampleVerification`, `RuntimeLogTranslator`.
- `:app/runtime`: `RuntimeManager` (package detection via a `<queries>` entry for `com.winlator`; launch through `getLaunchIntentForPackage`; download page link; workspace folder; sample staging and verification, persisted), `SafStagingStore` / `FileStagingStore`.
- PC Apps → Runtime: status, Get/Open Winlator, choose shared folder, 3-step sample test, "Explain a runtime error".
- Nothing is executed during import or staging (tested: no activity starts while staging).

## Evidence actually obtained
- PE fixtures built with MinGW-w64 and run on the **x86-64 Linux build host** with Ubuntu `wine64` 10.0~repack-12ubuntu1:
  console x64/x86 printed the marker, the GUI sample wrote `POCKETOS_FIXTURE_OK deadbeefcafe0001\r\n`, and the missing-DLL
  and not-a-program runs produced the logs saved in `core/src/test/resources/runtime-logs/`. The translator is tested against those real logs.
- Robolectric tests cover detection, launch intent, staging bytes, nonce verification, stale-marker rejection, persistence, and path mapping.

## Blocker (external, documented)
- **No ARM64 Android device or emulator is available** (x86-64 host, no `/dev/kvm`; Winlator is arm64-only). Running the sample
  through Winlator on Android, and therefore any "this program works on your phone" claim, **has not been verified**.
- Not verified on device either: SAF writes into `Download/PocketOS` being visible to Winlator (it targets SDK 28 with legacy storage), and Winlator's working directory and permissions on current Android versions.

## How to verify on a real phone (arm64, Android 8+)
1. Install Winlator 11.2 from its GitHub releases; open it once and let it finish setup; create a container.
2. In PocketOS → PC Apps → Runtime: Choose folder → create/select `Download/PocketOS`.
3. Tap "1. Copy sample". In Winlator, start the container, open `D:\PocketOS\PocketOS Sample\pocketos-sample.exe`.
4. Return to PocketOS → "3. Check result". Expected: "Verified: the sample ran through Winlator 11.2 on this phone."
Record model, Android version, Winlator version and result in `docs/HARDWARE_TEST_CHECKLIST.md`.

## User-facing limitations (shown in the app)
- A separate runtime app is required; many Windows programs will not run; 16-bit/DOS programs are unsupported;
  performance and compatibility depend on the program, the phone's GPU driver and memory.
