# PocketOS — Repository Entry Point
# Rename this file to README.md when adding it to the repository.

## What is PocketOS?

PocketOS is a planned Android-hosted desktop environment with a custom, user-friendly UI, a Smart Cursor, external-monitor desktop mode on supported devices, and a future Windows application compatibility layer.

The aim is to make an Android phone feel like a lightweight PC without requiring users to manually configure a traditional emulator.

## Read these documents first

1. PRODUCT_VISION.md — product goals, user experience, architecture, scope, and technical limitations.
2. DEVELOPMENT_PHASES.md — staged development plan and acceptance criteria.
3. AI_AGENT_RULES.md — rules for coding agents working in this repository.
4. AI_PROMPTS.md — copy/paste prompts to execute one phase at a time.

These files are provided as .txt files initially so they can be reviewed and renamed to .md after being added to the repository.

## Important scope notes

- The first version is an Android app/environment, not a standalone operating system.
- External monitor support depends on the phone, Android version, and video-output hardware.
- Windows .exe compatibility must be proven with a real runtime and real test applications. The UI alone cannot run Windows executables.
- Not every Windows application or Android phone can be supported.
- Do not claim features are complete unless they are implemented and tested.

## Recommended first task

Run PROMPT 0 from AI_PROMPTS.md to audit the repository. Then implement Phase 1 and verify it before moving on.
