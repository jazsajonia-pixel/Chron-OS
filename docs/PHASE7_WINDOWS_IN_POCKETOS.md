# Phase 7 — Windows programs in a PocketOS window (0.3.0)

Goal from the owner: no Winlator screens, menus or name; programs open inside a PocketOS window, controlled by the
Smart Cursor and keyboard; the phone never switches to another screen.

## What changed

| 0.2.0 | 0.3.0 |
|---|---|
| Run/Install opened Winlator's full-screen display activity in a separate `:winruntime` process | The program opens in a **"Windows program" window** on the PocketOS desktop |
| Touch, back-gesture menu, keyboard and exit came from Winlator's UI | PocketOS input: Smart Cursor, touch, mouse, hardware keyboard, the window's **Keyboard** and **Stop** buttons |
| "Advanced runtime settings" opened Winlator's settings app | Removed; PocketOS applies the settings itself (resolution, graphics, DirectX) |
| Result was passed back through a file when the activity finished | `RuntimeManager` gets the exit directly; the window closes and the library shows the result |
| Package `com.winlator`, library `libwinlator.so` | Package `com.chronos.pocketos.windows`, library `libpocketos_runtime.so` |

## How it works

```
PocketOS desktop (Compose)                         Windows runtime (winruntime/, in the same process)
 ShellController ── "Windows program" window ──▶  WindowsProgramApp ── AndroidView(DisplayView)
 RuntimeManager.launch / stopSession ──────────▶  EmbeddedWindowsBackend ── WindowsSession
   ActiveSession(title, status, ready)              ├─ XServer (Java X11 server) + DisplayHost (EGL thread, GLRenderer)
   onSessionEnded ──▶ ImportManager result          ├─ Wine + Box64 (Linux programs from the runtime image)
 PocketOsApp.routeKeyToWindows / Smart Cursor ──▶  └─ DisplayView: pointer / keys / on-screen keyboard → X events
```

- **Window.** `AppIds.WINDOWS_PROGRAM` ("winprogram"): opened when a session starts (title = program name, "Self-test"
  or "Windows desktop"), not in the launcher, not restored after a restart. A loading screen covers it until the
  program's first window is shown. Minimising or moving the window keeps the program running; the display view is
  recreated and the GL context survives (`DisplayHost` uses a 1×1 surface while nothing is shown).
- **Ending.** Closing the window or Stop (with confirmation) stops the session; when the program exits by itself the
  window closes. The runtime's own child processes are ended so nothing keeps running.
- **Input.** Pointer events in the window (Smart Cursor taps/drags/scroll, direct touch, mouse) are mapped to the
  Windows screen. Keys go to the program only while its window is focused and not minimised, and never while the
  launcher/switcher is open or text is typed in the companion keyboard field; Meta, Back, Home and Alt+Tab stay with
  PocketOS. The Keyboard button opens Android's on-screen keyboard for the program.
- **One at a time.** Starting a second program while one runs says "… is still running. Close its window first."
  (Next step: one PocketOS window per Windows program window.)

## Verification (2026-10-12, PC, no phone)

| Check | Result |
|---|---|
| Unit/UI tests (Robolectric) | 70 core + 72 app tests pass, including new `WindowsProgramWindowTest` (window opens in place, no activity started, status/loading, key routing, Keyboard button, close stops, one at a time, not restored) |
| `EmbeddedRuntimeIntegrationTest` (real runtime archives, real setup) | passes |
| `DisplayPipelineTest` (opt-in): `click_test.exe` with Box64 + Wine 10.10 under qemu-aarch64 against PocketOS's X server, input through `DisplayView` | window appeared and was drawn (its background colour present in the window's pixels); typed "pocket" and a tap on its button reached the program, which wrote `clicked:pocket` and exited; window removed |
| AddressSanitizer on the display server's native code during that test | found and fixed an overlapping `memcpy` (see `winruntime/MODIFICATIONS.md`); clean afterwards |
| Merged manifest | no runtime activities, no `:winruntime` process |

Not verified: GPU drawing on a real phone (the PC test checks the CPU-side frame memory), audio, performance, and the
look of the window on a real screen. See `docs/HARDWARE_TEST_CHECKLIST.md`.
