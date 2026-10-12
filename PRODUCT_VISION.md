# PocketOS — Product Vision and Technical Direction
# Rename this file to PRODUCT_VISION.md when adding it to the repository.

## 1. Product Summary

PocketOS is a mobile-first, Android-hosted desktop environment that gives an Android phone a familiar PC-style workspace. It has its own desktop UI/UX, app launcher, taskbar, window system, file manager, settings, and input controls. On compatible devices, connecting an external monitor opens PocketOS Desktop Mode on that display.

PocketOS also aims to run selected Windows applications (.exe) through an integrated compatibility runtime. The goal is to make this easier for everyday users than traditional emulator tools by automating setup, selecting sensible defaults, explaining failures clearly, and hiding unnecessary technical configuration.

PocketOS is an application/environment running on Android for the initial release. It is NOT a replacement for Android, does not boot as a standalone OS in the MVP, and must not claim to run every Windows application or work with every Android phone.

## 2. Product Principles

1. User-friendly by default: common tasks should not require terminal commands or knowledge of Wine, containers, drivers, or CPU translation.
2. Honest compatibility: clearly show whether an app is supported, untested, or known to have limitations. Never promise universal .exe support.
3. Hardware-aware: adapt UI scale, resolution, performance settings, and runtime profiles to device capabilities where possible.
4. Touch-first, desktop-ready: support touch on the phone and keyboard/mouse interaction on compatible desktop sessions.
5. Safe by design: treat imported executables as untrusted. Do not silently execute files or download unknown dependencies.
6. Modular architecture: desktop UI, input handling, display mode, device detection, and Windows compatibility must be separate modules.
7. Incremental delivery: every development phase must build, run, and have a clear acceptance test before moving forward.
8. Mobile-development friendly: repository instructions and tasks should be explicit, scoped, testable, and suitable for an AI coding agent.

## 3. Intended User Experience

### Phone Mode
- Touch-friendly home/desktop screen.
- App launcher, shortcuts, taskbar or dock, notification/status area, and settings.
- File manager for files the user has permission to access.
- Built-in utilities such as a text editor, terminal placeholder only if clearly labeled, calculator, and system information.
- Touch-friendly window and app switching.

### Desktop Mode
- On supported external displays, show a dedicated PocketOS desktop UI on the monitor.
- Desktop wallpaper, taskbar, Start-style app launcher, app windows, minimize/maximize/close controls, snapping, and Alt+Tab-like switching where supported.
- Keyboard and mouse input where Android and the device permit it.
- Phone can optionally act as a touchpad/companion controller. This must not be assumed to work on every device.
- Handle display connect/disconnect gracefully without losing user data.

### Smart Cursor
- Provide a phone touchpad surface that moves a pointer in the PocketOS desktop session.
- Tap/click, double-tap, two-finger scrolling, drag gestures, adjustable sensitivity, and optional left/right-click controls.
- Detect physical mouse and keyboard connections where possible and avoid showing redundant touch controls.
- Gamepad mapping is an optional later feature, not an MVP blocker.
- Clearly distinguish pointer events inside PocketOS-managed windows from input injection into arbitrary Android apps. The latter is not universally available to ordinary apps.

### Windows Application Workflow
- Let the user import an .exe or installer using Android's file picker.
- Inspect metadata safely; do not execute during inspection.
- Detect available architecture and likely requirements when technically possible.
- Offer automatic setup using a compatible runtime profile.
- Create an app entry with icon/name when obtainable, launch button, configuration summary, and logs.
- Provide advanced settings only behind an optional section.
- Explain that some apps may not run due to CPU architecture, graphics/API requirements, anti-cheat, drivers, DRM, licensing, or hardware limits.
- Use an existing, legally usable compatibility engine rather than attempting to recreate Windows or Wine from scratch.

## 4. Hardware and Compatibility Reality

The initial target is Android phones, especially ARM64 devices, but support must be validated through a device test matrix. A phone's ability to run PocketOS does not guarantee that it supports wired external video output. Some devices require supported USB-C DisplayPort Alt Mode, a compatible dock, or another supported display pathway.

The app can adapt to available hardware; it cannot create missing hardware capabilities. Performance and compatibility depend on RAM, CPU, GPU, graphics drivers, Android version, storage, and the Windows application itself.

## 5. Proposed Architecture

Keep these modules separate and expose clear interfaces between them:

1. Android Host Shell
   - App lifecycle, permissions, file picker, storage access, device information, connection events.
2. PocketOS UI
   - Phone home, desktop shell, taskbar, launcher, window manager, settings, file manager, app library.
3. Display Session Manager
   - Detect supported external displays, create a desktop presentation/session, adapt resolution and density, handle disconnects.
4. Input Manager
   - Touchpad gestures, pointer state, keyboard shortcuts, physical input detection where available, input preference settings.
5. Device Capability Manager
   - Record Android version, CPU architecture, RAM class, display properties, supported features, and safe performance presets.
6. Windows Runtime Adapter
   - Interface to a compatible runtime. Keep implementation details behind the adapter; do not fake functionality if no runtime is integrated.
7. Installer and App Library
   - Imported files, app metadata, install state, launch configuration, user-facing errors, logs, and uninstall/cleanup.
8. Diagnostics and Safety
   - Runtime logs, permission explanations, safe failure handling, storage cleanup, and no silent execution of untrusted files.

## 6. Suggested Technology Direction

- Android application: Kotlin and Android SDK.
- UI: choose a maintainable Android-native UI approach, such as Jetpack Compose, unless the repository already has a justified alternative.
- Persistence: local structured storage for settings and app metadata; do not store secrets unnecessarily.
- Windows compatibility: evaluate a suitable existing Wine-based solution and its licensing, distribution, architecture, graphics, and maintenance requirements before integration.
- External display: use supported Android presentation/display APIs and test on actual hardware.
- Testing: unit tests for state and configuration logic, UI tests for core flows, and a real-device compatibility matrix.

Do not add a large dependency or runtime until its license, distribution method, size, security, and Android compatibility are understood.

## 7. Non-Goals for the MVP

- Replacing or modifying the Android operating system.
- Root access as a requirement.
- Guaranteeing every Windows application or game works.
- Reimplementing Windows APIs, Wine, CPU translation, or GPU drivers from scratch.
- Installing arbitrary Windows kernel drivers.
- Circumventing anti-cheat, DRM, licensing, or app security restrictions.
- Injecting input globally into unrelated Android applications without a supported and user-authorized mechanism.
- Claiming external-display support on devices that do not expose a usable display output path.

## 8. Definition of a Successful MVP

The MVP is successful when:
- The Android app launches reliably.
- The user can navigate a coherent PocketOS interface.
- Built-in apps and desktop windows work within the environment.
- The file manager uses supported Android storage APIs and respects permissions.
- A compatible external display opens a distinct PocketOS desktop session.
- Smart Cursor works within PocketOS-managed desktop content.
- Keyboard and mouse input work where supported.
- The app reports hardware capabilities and explains unavailable features.
- Windows runtime integration is either demonstrably functional for a documented test app or clearly marked as a later phase. Never use a fake UI to imply .exe execution already works.
- Build, test, and setup instructions are documented.
