# Changes to Winlator in this module

Source: https://github.com/brunodev85/winlator-app, commit `3981d86` (LGPL-2.1, see `LICENSE`).
Modified for PocketOS on 2026-10-12. All changes are listed here as required by LGPL-2.1 section 2(a).
The module keeps Winlator's runtime engine (display server, Wine/Box64 launcher, environment setup, audio, input
plumbing) and removes its app: PocketOS shows no Winlator screen, menu or name.

## 0.3.0 — runtime inside a PocketOS window
1. **Renamed.** Java package `com.winlator` → `com.chronos.pocketos.windows` (directory moved, Gradle namespace and
   `consumer-rules.pro` updated). Native library `libwinlator.so` → `libpocketos_runtime.so` (`cpp/winlator` →
   `cpp/pocketos_runtime`); JNI symbol names follow the new package. Notification channel `pocketos_windows_runtime`,
   GLX vendor string "PocketOS", FileProvider `core/RuntimeFileProvider` (`${applicationId}.windows.FileProvider`),
   log file `files/pocketos_runtime/logs.txt`.
2. **Removed (UI).** All activities and fragments (MainActivity, XServerDisplayActivity, ControlsEditorActivity,
   container/shortcut/settings/file-manager fragments, …), all `contentdialog` dialogs, TaskManagerDialog,
   PreloaderDialog, DownloadProgressDialog, Box64 preset / DX wrapper / graphics driver pickers, the on-screen
   controls editor and profiles (`inputcontrols` ControlElement, ControlsProfile, InputControlsManager,
   RangeScroller), widgets XServerView, TouchpadView, InputControlsView, FrameRating, LogView and the rest, and all
   resources except the cursor image, a notification icon, `xml/file_paths.xml` and `strings.xml` (name "PocketOS").
   `RuntimeStartupProvider`, UI parts of `GeneralComponents`, `AppUtils.setActivityTheme`, `HttpUtils.download` and
   `WineInstaller.generateWineprefix` were removed with them.
3. **New `WindowsSession`** (replaces XServerDisplayActivity). Runs in the PocketOS process; `start()` prepares the
   Windows environment and starts the display server and Wine (`wine explorer /desktop=nogui|shell,WxH
   winhandler.exe …`); `createView()` returns a view for the PocketOS window; `stop()` ends everything (environment,
   the runtime's own processes, display, foreground service). Reports status, "first window shown" and exit to a
   listener.
4. **New `renderer/DisplayHost`.** An EGL (OpenGL ES 3) thread owned by the session instead of a GLSurfaceView. The GL
   context survives when the PocketOS window is minimised or recreated (a 1×1 pbuffer is used while no view is
   shown); `GLRenderer` and the other renderer users take a `DisplayHost`.
5. **New `widget/DisplayView`.** A TextureView shown inside the PocketOS window. Touch, Smart Cursor and mouse events
   become X pointer events (with letterbox mapping), hover moves the pointer, scroll becomes wheel buttons; keys go to
   the X keyboard; an input connection lets the Android on-screen keyboard type (`typeText`, `sendKey`,
   `showSoftKeyboard`).
6. **Constructors.** XServer, WinHandler, Win32AppWorkarounds, GamepadHandler (no virtual gamepad) and MIDIHandler
   take a `WindowsSession` instead of the activity. Settings constants and environment helpers that lived in the
   removed dialogs moved to `core/RuntimeConfig` (Vortek's default version moved into `VortekRendererComponent`).
7. **Manifest.** Only the permissions, the foreground service (media playback) and the FileProvider. No activities and
   no separate `:winruntime` process.
8. **Bug fix.** `xinput_stream.c` `compactInputBuffer` copied overlapping memory with `memcpy`; now `memmove`. Found by
   AddressSanitizer in the display-pipeline test (`tools/runtime/host-display-test`); it corrupted X requests and
   stopped Wine's display driver from loading in child processes.

## 0.2.0 — embedded library (kept)
- **Library module** (`build.gradle.kts`, `com.android.library`, arm64-v8a only).
- **Resource ids are not constants in libraries:** `switch` over `R.id.*` rewritten (`tools/runtime/switch_to_if.py`);
  most of those files were later removed in 0.3.0.
- **Package path.** `/data/data/com.winlator/` → `/data/data/com.chron.os/` in `AppUtils.java`, the C headers and in
  every asset archive (`tools/runtime/retarget_assets.py`, byte-for-byte, same length).
- **Headless setup.** `RootFSInstaller.isInstalled()` / `installBlocking()`, `ContainerManager.createContainerBlocking()`,
  `RuntimeConfig.resetPreferenceVersions(Context)`.
- **No storage permissions**; only app-private storage is used.

The prebuilt Linux system image (Wine, Box64, libraries) is unchanged apart from the path retargeting. It still
contains the desktop file manager `wfm.exe` and its registry key `Software\Winlator\WFM` (not visible as a name in
PocketOS).
