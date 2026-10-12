# PocketOS test APKs (0.1.0)

Built from this commit's source by `./gradlew :app:assembleDebug :app:assembleRelease`. Both are signed with the
Android **debug key** — for personal testing only, not for public distribution.

| File | Use |
|---|---|
| `PocketOS-0.1.0-debug.apk` | **Install this one first.** Not minified, easiest to debug. |
| `PocketOS-0.1.0-release.apk` | Minified (R8) build. Not yet tried on any device — please test it too and report differences. |

Both use the same key, so one can be installed over the other.

## Install
- **On the phone:** download the APK from GitHub (open the file → "View raw"/download), open it, and allow
  "Install unknown apps" for your browser or file manager when asked.
- **With a computer:** enable Developer options → USB debugging, then `adb install -r apk/PocketOS-0.1.0-debug.apk`.
- If Android says the app conflicts with an existing package: `adb uninstall com.chronos.pocketos` (or uninstall PocketOS
  from Settings) and install again.

## Trying Windows programs
PocketOS examines `.exe`/`.msi` files and prepares them; it does **not** run them itself. To run them you need:
1. A **64-bit ARM** phone (PC Apps → Runtime tells you whether yours qualifies).
2. **Winlator** installed separately from https://github.com/brunodev85/winlator/releases (large download, ~150 MB).
3. In PocketOS: PC Apps → Runtime → choose the shared folder (`Download/PocketOS`), then run the built-in sample test.
4. PC Apps → Library → Add program → pick your `.exe`. In Winlator, open it from the **D:** drive
   (`D:\PocketOS\Apps\<name>\`). Then press "It worked" or "It didn't work" in PocketOS.

Many programs will not work; results vary by phone. Please note your phone model, Android version and results using
`docs/HARDWARE_TEST_CHECKLIST.md`.

## SHA-256
```
e83c22af33cd6e761c6455db0886a25e665455bdd21c9ad6218b44502d61233d  apk/PocketOS-0.1.0-debug.apk
768054288c9db60900b2f3f0ec82aa551bc28e70b3753b294535ea54179a4750  apk/PocketOS-0.1.0-release.apk
```
