# Changes to Winlator in this module

Source: https://github.com/brunodev85/winlator-app, commit `3981d86` (LGPL-2.1, see `LICENSE`).
Modified for PocketOS on 2026-10-12. All changes are listed here as required by LGPL-2.1 section 2(a).

1. **Library module.** `build.gradle` replaced by `build.gradle.kts` (`com.android.library`, namespace `com.winlator`,
   arm64-v8a only). `consumer-rules.pro` keeps all runtime classes.
2. **Resource ids are not constants in libraries.** `switch` statements over `R.id.*` were rewritten as `if/else`
   chains by `tools/runtime/switch_to_if.py` (ContainerDetailFragment, ContainerFileManagerFragment, ContainersFragment,
   ControlsEditorActivity, MainActivity, ShortcutsFragment, XServerDisplayActivity, TaskManagerDialog).
   Theme attributes now reference `androidx.appcompat.R.attr` / `com.google.android.material.R.attr`.
3. **Package path.** `/data/data/com.winlator/` → `/data/data/com.chron.os/` in `AppUtils.java`, `winlator.h`,
   `vortek.h`, `gladio.h` and in every asset archive (`tools/runtime/retarget_assets.py`, byte-for-byte, same length).
4. **Manifest.** No launcher activity; all components run in the `:winruntime` process; storage permissions removed
   (only app-private storage is used); FileProvider authority is `${applicationId}.winlator.FileProvider` with its own
   subclass `core/WinlatorFileProvider`; `core/RuntimeStartupProvider` initializes androidx.startup in `:winruntime`.
5. **Hosted sessions.** `XServerDisplayActivity`: new extras `pocketos_hosted` and `pocketos_session_id`. When hosted,
   exit writes `files/pocketos_runtime/last_session.json` (session id, exit status) and returns to PocketOS instead of
   restarting the app.
6. **Headless setup.** `RootFSInstaller.isInstalled()` and `installBlocking()`; `ContainerManager.createContainerBlocking()`;
   `SettingsFragment.resetPreferenceVersions(Context)`.
7. **Permissions.** `MainActivity.requestAppPermissions()` no longer requests storage access.
