# PocketOS — Development Phases
# Rename this file to DEVELOPMENT_PHASES.md when adding it to the repository.

## Instructions for the AI coding agent

Read PRODUCT_VISION.md and this file before making changes. Inspect the existing repository before choosing architecture. Do not assume a framework, folder structure, or current implementation. Preserve working features. Work on one phase at a time, and do not start the next phase until the current phase builds and its acceptance criteria are met.

If the repository is empty, scaffold the project using the recommended Android/Kotlin direction. If the repository already contains a working app, adapt its architecture instead of rewriting it without a strong reason.

For each phase:
1. Inspect relevant files and summarize the current state.
2. Make the smallest coherent implementation that satisfies the phase.
3. Build and run available tests.
4. Fix errors introduced by the changes.
5. Update documentation and report exact files changed, commands/tests run, results, known limitations, and next steps.
6. Do not claim a feature works unless it is implemented and tested. Use clearly labeled placeholders only when a real integration is blocked.

## Phase 0 — Repository Audit and Technical Plan

### Objective
Understand the repo and define a realistic implementation plan before writing major code.

### Tasks
- Inspect all relevant source files, build configuration, dependencies, and existing tests.
- Identify whether the repo is empty, partially built, or already functional.
- Document current architecture and reusable components.
- Verify Android SDK/build tooling requirements.
- Investigate supported external-display APIs and input APIs for the target Android versions.
- Research the licensing, distribution constraints, architecture support, and packaging size of any proposed Windows compatibility runtime before integrating it.
- Create or update README.md with setup instructions and honest scope.

### Acceptance criteria
- A concise repository audit exists.
- Architecture choices and their trade-offs are documented.
- No existing working features were unnecessarily removed.
- The project builds, or any pre-existing build blocker is documented with evidence.

## Phase 1 — Functional PocketOS Shell

### Objective
Create a usable desktop-style environment inside the Android app, without requiring Windows compatibility yet.

### Tasks
- Build a coherent home/desktop screen with wallpaper/background, taskbar or dock, app launcher, status area, and settings entry.
- Add a window model and functional app-window surface with title bar, close, minimize, maximize/restore, and resizing where practical.
- Implement at least three real built-in tools, such as File Manager, Settings, and a simple Text Editor or System Info app.
- Implement app switching and sensible back-button behavior.
- Add dark/light theme or a theme foundation.
- Use accessible touch targets and responsive layouts.
- Persist basic user settings.

### Acceptance criteria
- App launches and can be navigated entirely by touch.
- Built-in apps actually work; no dead buttons.
- Window controls update real app state.
- UI adapts to portrait and landscape.
- Settings persist across app restarts.
- Build and available tests pass.

## Phase 2 — Input System and Smart Cursor

### Objective
Make PocketOS comfortable with touch, mouse, and keyboard.

### Tasks
- Create an input abstraction that separates pointer position, click, double-click, drag, scroll, keyboard shortcuts, and focus.
- Implement an on-screen touchpad area for moving the cursor inside PocketOS-managed desktop content.
- Add tap-to-click, double-tap, two-finger scroll, drag, sensitivity settings, and an optional visible cursor.
- Support physical mouse and keyboard input where the Android host and UI architecture allow it.
- Add configurable desktop shortcuts such as app switching and opening the launcher.
- Hide or reduce virtual controls when physical input is detected, if reliable detection is available.
- Make gamepad support optional and do not let it block the phase.

### Acceptance criteria
- Touch-only users can operate the shell.
- Touchpad gestures control the pointer within PocketOS-managed windows.
- Mouse and keyboard behavior works on supported devices.
- Input limitations are documented honestly.
- No unsupported global input injection is used or claimed.

## Phase 3 — External Monitor Desktop Mode

### Objective
Show a dedicated PocketOS desktop on an external display when the phone supports it.

### Tasks
- Detect external display connection and disconnection using supported Android APIs.
- Create a separate desktop presentation/session when possible.
- Scale desktop UI to the external display's dimensions and density.
- Keep the phone screen usable as a touchpad/companion controller where the architecture supports it.
- Handle display rotation, reconnects, display changes, app lifecycle, and disconnects.
- Provide a clear settings page showing whether external display is supported and why it may not be available.
- Never assume all USB-C ports support video output.

### Acceptance criteria
- The external desktop works on at least one tested compatible device/display combination.
- Disconnecting the monitor does not crash the app or lose saved data.
- Unsupported devices receive an understandable explanation.
- The phone UI and monitor UI behave appropriately for their separate roles.
- Include a manual test checklist for devices without physical test access.

## Phase 4 — Device Capability Detection and Automatic Profiles

### Objective
Choose safe, useful defaults for different Android devices.

### Tasks
- Detect available device information using supported APIs: Android version, CPU ABI, available memory class, display properties, and storage availability.
- Build a capability model with unknown/unsupported states instead of guessing.
- Define conservative performance profiles, such as Battery Saver, Balanced, and Performance.
- Adjust UI scale and effects based on display size and user settings.
- Recommend a supported resolution rather than forcing an unsupported mode.
- Re-check capabilities when display or memory conditions change.
- Explain choices in simple language and allow manual override.
- Avoid collecting or uploading device information unless required and clearly disclosed.

### Acceptance criteria
- Device capability report is accurate for tested devices.
- Profile selection is deterministic and testable.
- Unsupported features are disabled gracefully.
- Users can override non-critical settings.
- No unsupported hardware capability is falsely promised.

## Phase 5 — Windows Runtime Feasibility and Integration Spike

### Objective
Prove a real .exe can run through an existing compatibility runtime before building a large installer experience.

### Tasks
- Review the licensing and redistribution requirements of candidate runtimes.
- Verify Android ABI support, graphics path, storage integration, process lifecycle, package size, and maintenance health.
- Choose one supported approach and document why.
- Integrate the smallest safe proof of concept possible.
- Test one known-compatible, non-sensitive Windows sample application.
- Capture runtime logs and translate common errors into user-friendly messages.
- If a runtime cannot be integrated safely or legally, document the blocker and offer a viable alternative; do not fake success.

### Acceptance criteria
- A real, documented test application launches, or a clear evidence-based blocker is recorded.
- Runtime licensing and distribution decisions are documented.
- No arbitrary executable is silently run during import.
- Build and runtime limitations are known.

## Phase 6 — Installer Wizard and App Library

### Objective
Make Windows app installation understandable to non-technical users.

### Tasks
- Add a file picker for .exe and supported installer files.
- Treat files as untrusted and do not execute them during metadata inspection.
- Detect what can reliably be detected: file metadata, architecture hints, known installer types, and user-selected install location.
- Create an installation wizard with plain-language steps.
- Choose a safe default runtime profile and explain optional advanced settings.
- Track installed apps, icons, launch configuration, installation status, and logs.
- Support uninstall/cleanup of PocketOS-managed files and runtime data.
- Clearly show statuses such as Ready, Needs Setup, Unsupported, and Failed.
- Never claim compatibility based solely on file extension or metadata.

### Acceptance criteria
- The user can import a file, see a clear setup path, and understand the result.
- Failed installations provide useful next steps and logs.
- App entries persist and can be removed.
- Imported files are not automatically executed without an explicit user action.
- Unsupported apps are labeled honestly.

## Phase 7 — Compatibility Recovery and Quality

### Objective
Improve success rate and reliability without pretending every app can work.

### Tasks
- Save known-good settings per app.
- Add a controlled fallback sequence for common recoverable failures, such as reducing resolution or selecting a safer graphics profile when supported.
- Do not endlessly retry or change settings without user visibility.
- Add runtime diagnostics, crash recovery, storage cleanup, and safe cancellation.
- Test app lifecycle, low-memory conditions, display disconnects, and orientation changes.
- Create a compatibility report format with device details, app details, runtime profile, and error summary, with privacy safeguards.

### Acceptance criteria
- Recovery actions are bounded and explain what changed.
- Logs do not unnecessarily include private file contents or secrets.
- Common failure paths are tested.
- No guarantee of universal compatibility is made.

## Phase 8 — Polish, Accessibility, and Release Readiness

### Objective
Deliver a stable, user-friendly release.

### Tasks
- Polish onboarding, empty states, errors, loading states, settings, and visual consistency.
- Add cursor size/speed controls, keyboard accessibility, and readable scaling.
- Verify permissions and privacy disclosures.
- Test multiple Android versions, screen sizes, input setups, and external-display setups where available.
- Document known limitations, supported devices tested, compatibility caveats, and troubleshooting.
- Optimize startup time, memory use, storage footprint, and battery consumption.
- Add crash reporting only if it is privacy-conscious, disclosed, and appropriate.

### Acceptance criteria
- Clean build and tests pass.
- Critical flows have manual test steps.
- No misleading claims about .exe or external display support.
- README contains installation, usage, limitations, and troubleshooting.
- Release artifact is clearly identified as an Android app/environment, not a standalone OS.

## Recommended MVP boundary

The first usable MVP should finish Phases 0–3 with a stable shell, Smart Cursor, and external display support on tested hardware. Phase 5 is the earliest point where Windows compatibility should be considered proven. Do not spend months building an elaborate installer UI before proving that a real Windows application can launch on the intended Android devices.
