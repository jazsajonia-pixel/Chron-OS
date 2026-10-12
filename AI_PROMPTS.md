# PocketOS — Copy/Paste Prompts for an AI Coding Agent
# Rename this file to AI_PROMPTS.md when adding it to the repository.

Use one prompt at a time. Do not ask the agent to implement every phase in one run.

---

## PROMPT 0 — Audit the Repository

Read PRODUCT_VISION.md, DEVELOPMENT_PHASES.md, and AI_AGENT_RULES.md. Inspect the entire relevant repository and build configuration. Do not make large code changes yet.

Report:
1. Current project stack and architecture.
2. What is implemented, partially implemented, missing, or only mocked.
3. Whether the current project builds and which tests run.
4. Reusable components and technical risks.
5. Recommended implementation order for Phase 1.
6. Any runtime licensing or Android external-display/input questions that need research before implementation.

If the repository is empty, propose the minimal Android/Kotlin scaffold. If it already has working code, preserve it. Update README only with verified facts. Do not claim anything is tested unless you ran the test.

---

## PROMPT 1 — Build the Functional Desktop Shell

Implement Phase 1 from DEVELOPMENT_PHASES.md only. Build a functional Android app shell with PocketOS identity, desktop/home screen, app launcher, taskbar/dock, working settings, file manager using supported Android storage APIs, and at least one additional useful built-in app.

Implement a maintainable window model with working open, close, minimize, maximize/restore, and app switching where practical. Make the layout responsive to portrait and landscape and persist basic settings.

Inspect the existing project first, preserve working features, and use the current stack if appropriate. Do not create dead buttons or pretend Windows .exe support exists. Build and run available tests, fix introduced failures, update README, and report exact files changed and test results.

---

## PROMPT 2 — Implement Smart Cursor and Input Abstraction

Implement Phase 2 only. First inspect the current UI and input handling. Add a clear input abstraction for pointer movement, click, double-click, drag, scroll, keyboard shortcuts, and focus.

Create a phone touchpad surface that controls the pointer inside PocketOS-managed desktop content. Add configurable pointer speed, tap-to-click, double-tap, two-finger scrolling, drag support, and a toggle for cursor visibility. Support physical mouse and keyboard input wherever the existing Android architecture and APIs permit it. Avoid redundant touch controls when physical input is detected reliably.

Do not claim global input injection into unrelated Android apps. Add tests for gesture/state logic, test the app build, document device-specific limitations, and report what was actually verified.

---

## PROMPT 3 — Add External Monitor Desktop Mode

Implement Phase 3 only. Inspect current Android lifecycle, display, and UI architecture before coding. Use supported Android display/presentation APIs to create a dedicated PocketOS desktop session on a compatible external display. Keep the phone interface separate and, where supported, let the phone act as a touchpad/companion controller.

Handle connect, disconnect, rotation, reconnect, activity lifecycle, display-size changes, and unavailable external-display support. Adapt desktop scaling to the actual display. Never assume every USB-C port supports video output and never claim support for hardware that was not tested.

Build and test the changes, add a manual verification checklist for real compatible hardware, document known limitations, and report whether the behavior was verified on a physical device or only through code review/emulation.

---

## PROMPT 4 — Automatic Device Profiles

Implement Phase 4 only. Create a testable device capability model using supported APIs for Android version, CPU ABI, memory class, display properties, and storage availability. Use unknown states rather than guessing.

Implement conservative Battery Saver, Balanced, and Performance profiles. Adapt UI scaling and effects without forcing unsupported display modes. Allow users to override non-critical choices. Re-check relevant capabilities when display or memory conditions change.

Do not collect or upload device data unnecessarily. Add unit tests for profile selection and fallback behavior. Document what can and cannot be detected, then build and report results.

---

## PROMPT 5 — Prove Windows Runtime Feasibility

Do not build a large installer UI yet. Investigate candidate existing Windows compatibility runtimes suitable for Android. Verify licensing, redistribution rights, Android ABI support, graphics path, process lifecycle, storage integration, package size, security, and maintenance status.

Recommend one approach with evidence and explain trade-offs. If repository access and the environment permit, implement a minimal proof of concept and launch one known-compatible, non-sensitive Windows sample application. Do not download or execute untrusted binaries automatically.

If no safe/legal/maintainable integration is feasible, do not fake it. Document the blocker and alternatives. Report exactly what was built and whether a real .exe was actually launched.

---

## PROMPT 6 — Build the Friendly Installer and App Library

Proceed only after a real compatibility runtime has been selected and a basic test application can launch. Implement Phase 6. Let users import supported .exe/installer files through Android's file picker, inspect metadata without executing the file, and follow a plain-language setup wizard.

Select a sensible default runtime profile only when supported by actual capability data. Track app name, icon when available, install state, launch configuration, and logs. Show honest states such as Ready, Needs Setup, Unsupported, and Failed. Put advanced options behind an optional section. Support cleanup/uninstall of PocketOS-managed data.

Never guarantee compatibility based on file extension alone. Never silently execute imports. Add tests, build, update documentation, and report verified functionality.

---

## PROMPT 7 — Improve Recovery and Compatibility

Implement Phase 7 only. Add bounded, transparent recovery options for known recoverable runtime failures. Examples may include reducing resolution or switching to a supported safer graphics profile, but only if the selected runtime actually supports these options.

Save known-good settings per app, avoid endless automatic retries, provide understandable error explanations, and collect diagnostic logs without private file contents or secrets. Test cancellation, low-memory conditions, app crashes, display disconnects, and cleanup.

Do not imply that recovery can fix every compatibility issue. Build, test, document, and report results.

---

## PROMPT 8 — Release Readiness

Implement Phase 8 only. Review all critical user journeys: first launch, navigation, built-in apps, file permissions, touchpad, physical input, external display, app installation, runtime failure, cleanup, and settings persistence.

Polish accessibility, touch targets, keyboard focus, scaling, loading/empty/error states, privacy disclosures, startup time, memory use, and battery consumption. Verify dependencies and licenses. Update README with setup, tested devices, known limitations, troubleshooting, and release instructions.

Run all available builds and tests. Do not claim physical-device or external-display testing unless performed. Report release blockers and remaining known issues.

---

## PROMPT — Fast Repository Execution Mode

Work quickly but safely on the single task I specify. First search the repository for the relevant implementation, call sites, configuration, and tests. Read only the files needed to understand the change, then make the smallest complete implementation.

Do not merely describe what should be done: edit the code. Do not rewrite unrelated files, add speculative dependencies, or implement fake behavior. Run the most relevant test/build commands, fix errors caused by your changes, and report the exact files changed, test results, and unresolved blockers.

If the task belongs to a development phase, follow that phase's acceptance criteria. If a requirement is ambiguous, inspect the project and choose the safest reasonable interpretation; ask only when a decision would materially change architecture or behavior.
