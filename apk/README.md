# PocketOS test APKs

## 0.3.0 — programs run in a PocketOS window (current)

`PocketOS-0.3.0-debug.apk` (in this folder, stored with Git LFS). Signed with the Android debug key — for personal
testing only. Needs a **64-bit ARM** phone. Installs over 0.2.0 (same package `com.chron.os`); uninstall 0.1.0 first.

### Running Windows programs
1. PC Apps → Runtime → **Set up** (one time, ~520 MB). Then **Run self-test**.
2. PC Apps → Library → **Add program** → pick a `.exe` or `.msi`.
3. Tap **Install** (installers) or **Run** (programs). The program opens in a window on the PocketOS desktop.
4. Use the Smart Cursor, touch or a mouse on it; type with a keyboard or the window's **Keyboard** button.
   Close the window (or **Stop**) to end it. One program at a time.
5. Then tap "It worked" or "It didn't work" (PocketOS applies the next fix automatically).

### SHA-256
```
24dbc3da44941510430fd93d75aa5238feedc6586736e8e437f73cb9756747fd  PocketOS-0.3.0-debug.apk
```

## 0.2.0 — built-in Windows runtime (previous, full-screen runtime)

The 0.2.0 APKs contain the Windows runtime (Wine + Box64) and are 170–185 MB, above GitHub's 100 MB limit for files
in a repository. They are attached to the **v0.2.0 release** on the GitHub Releases page once it is published; you can
also build them yourself with `./gradlew :app:assembleDebug :app:assembleRelease` (see the main README).

| File | Use |
|---|---|
| `PocketOS-0.2.0-debug.apk` | **Install this one first.** Not minified, easiest to debug. |
| `PocketOS-0.2.0-release.apk` | Minified (R8) build, same features. |

Both are signed with the Android **debug key** — for personal testing only. They need a **64-bit ARM** phone.

**Uninstall 0.1.0 first.** The package name changed from `com.chronos.pocketos` to `com.chron.os` (required by the
runtime, see `docs/PHASE6_EMBEDDED_RUNTIME.md`), so 0.2.0 installs as a separate app.

### Running Windows programs (no other app needed)
1. PC Apps → Runtime → **Set up** (one time, ~520 MB, takes a minute or two). Then **Run self-test**.
2. PC Apps → Library → **Add program** → pick a `.exe` or `.msi`.
3. Tap **Install** (installers) or **Run** (programs). Programs an installer creates appear in the library.
4. In the program: touch moves the mouse, tap clicks, back gesture opens the menu (keyboard, exit).
5. Back in PocketOS, tap "It worked" or "It didn't work" (PocketOS then applies the next fix automatically).

Not every Windows program works; results vary by phone. Please record results in `docs/HARDWARE_TEST_CHECKLIST.md`.

### SHA-256
```
466e689999fef19f520158cc92ccfb55593b788b85e0856c89da2bf1bee2e929  PocketOS-0.2.0-debug.apk
2e887b077f1d45d328a60c03aaac69691fab0d09e6529e32264630f2c05a4833  PocketOS-0.2.0-release.apk
```

## 0.1.0 (previous, Winlator handoff)

`PocketOS-0.1.0-debug.apk` and `PocketOS-0.1.0-release.apk` in this folder. 0.1.0 did not run programs itself; it
prepared them for the separate Winlator app.

```
e83c22af33cd6e761c6455db0886a25e665455bdd21c9ad6218b44502d61233d  apk/PocketOS-0.1.0-debug.apk
768054288c9db60900b2f3f0ec82aa551bc28e70b3753b294535ea54179a4750  apk/PocketOS-0.1.0-release.apk
```
