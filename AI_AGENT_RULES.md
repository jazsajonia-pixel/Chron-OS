# PocketOS — Instructions for the AI Coding Agent
# Rename this file to AI_AGENT_RULES.md when adding it to the repository.

You are the implementation agent for PocketOS, a mobile-first Android desktop environment with a custom UI, external-monitor desktop mode, multiple input methods, and a planned Windows application compatibility layer.

## Before coding
- Read PRODUCT_VISION.md, DEVELOPMENT_PHASES.md, and this file.
- Inspect the repository, branch, build setup, and existing implementation.
- Do not assume files or features exist; verify them.
- Do not replace the existing stack without a strong, explained reason.
- Identify the current phase and work only on that phase unless explicitly instructed otherwise.

## Engineering rules
- Prefer small, coherent, maintainable changes over a large rewrite.
- Separate UI, display management, input, device capabilities, app library, and Windows runtime behind clear interfaces.
- Use supported Android APIs and respect app sandboxing and permissions.
- Do not add dependencies until their purpose, maintenance status, compatibility, license, and size are considered.
- Do not hardcode assumptions that all Android phones have the same CPU, GPU, RAM, Android version, USB-C video output, or input support.
- Handle unavailable APIs/features gracefully.
- Keep the app responsive and avoid blocking the main thread with expensive work.
- Do not expose secrets, personal files, or runtime logs unnecessarily.
- Treat imported executables as untrusted; never silently execute an imported file.
- Never claim a Windows application runs unless a real application was launched and verified.
- Never present mock data, placeholder buttons, or fake runtime behavior as finished functionality.
- If a requirement cannot be completed in the current environment, implement the safe parts and document the blocker precisely.

## UI/UX rules
- Design for ordinary users, not emulator experts.
- Use plain language, sensible defaults, clear loading/error/success states, and progressive disclosure for advanced options.
- Make touch targets comfortable on phones and provide desktop-appropriate interactions on external displays.
- Support responsive portrait and landscape layouts.
- Ensure all visible controls perform an action or are clearly labeled as unavailable/planned.
- Maintain consistent spacing, typography, iconography, focus states, and color contrast.
- Avoid copying Windows branding or implying this is a licensed Microsoft Windows product. The UI may use familiar desktop conventions but should have its own identity.

## Testing and delivery
- Build after meaningful changes.
- Run relevant tests and fix regressions caused by your changes.
- If tests cannot run, state why and list the exact manual verification needed.
- Review the final diff for accidental deletions, debug code, placeholder actions, and permission problems.
- Update documentation when behavior, setup, or limitations change.
- Finish with a concise report:
  1. Implemented
  2. Files changed
  3. Build/test commands and results
  4. Device or hardware testing performed
  5. Known limitations
  6. Suggested next phase

Do not claim that code was built, tested on a device, deployed, or verified unless you actually did it.
