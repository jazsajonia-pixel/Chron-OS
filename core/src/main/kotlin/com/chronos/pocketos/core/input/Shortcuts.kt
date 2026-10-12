package com.chronos.pocketos.core.input

enum class ShellCommand(val label: String) {
    OPEN_LAUNCHER("Open launcher"),
    NEXT_WINDOW("Switch to next window"),
    PREVIOUS_WINDOW("Switch to previous window"),
    CLOSE_WINDOW("Close window"),
    MINIMIZE_WINDOW("Minimize window"),
    MAXIMIZE_WINDOW("Maximize / restore window"),
    SNAP_LEFT("Snap window left"),
    SNAP_RIGHT("Snap window right"),
    OPEN_FILES("Open Files"),
    OPEN_SETTINGS("Open Settings"),
    OPEN_EDITOR("Open Text Editor"),
    TOGGLE_CURSOR("Show / hide Smart Cursor"),
}

/** Platform-neutral key identifiers (mapped from Android key codes by the host). */
enum class Key { ESCAPE, TAB, SPACE, LEFT, RIGHT, UP, DOWN, F4, W, E, S, T, C, OTHER }

data class KeyChord(val key: Key, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false, val meta: Boolean = false) {
    fun describe(): String = buildList {
        if (ctrl) add("Ctrl"); if (alt) add("Alt"); if (shift) add("Shift"); if (meta) add("Meta")
        add(key.name.lowercase().replaceFirstChar(Char::uppercase))
    }.joinToString("+")
}

object ShortcutMap {
    /**
     * Defaults avoid the Meta key because Android commonly reserves Meta shortcuts for the system.
     * Some devices also intercept Alt+Tab; Ctrl+Alt+Right/Left is a fallback for window switching.
     */
    val defaults: Map<KeyChord, ShellCommand> = mapOf(
        KeyChord(Key.ESCAPE, ctrl = true) to ShellCommand.OPEN_LAUNCHER,
        KeyChord(Key.SPACE, ctrl = true, alt = true) to ShellCommand.OPEN_LAUNCHER,
        KeyChord(Key.TAB, alt = true) to ShellCommand.NEXT_WINDOW,
        KeyChord(Key.TAB, alt = true, shift = true) to ShellCommand.PREVIOUS_WINDOW,
        KeyChord(Key.F4, alt = true) to ShellCommand.CLOSE_WINDOW,
        KeyChord(Key.W, ctrl = true, shift = true) to ShellCommand.CLOSE_WINDOW,
        KeyChord(Key.DOWN, ctrl = true, alt = true) to ShellCommand.MINIMIZE_WINDOW,
        KeyChord(Key.UP, ctrl = true, alt = true) to ShellCommand.MAXIMIZE_WINDOW,
        KeyChord(Key.LEFT, ctrl = true, alt = true) to ShellCommand.SNAP_LEFT,
        KeyChord(Key.RIGHT, ctrl = true, alt = true) to ShellCommand.SNAP_RIGHT,
        KeyChord(Key.E, ctrl = true, alt = true) to ShellCommand.OPEN_FILES,
        KeyChord(Key.S, ctrl = true, alt = true) to ShellCommand.OPEN_SETTINGS,
        KeyChord(Key.T, ctrl = true, alt = true) to ShellCommand.OPEN_EDITOR,
        KeyChord(Key.C, ctrl = true, alt = true) to ShellCommand.TOGGLE_CURSOR,
    )

    fun resolve(chord: KeyChord, enabled: Boolean, disabled: Set<String>): ShellCommand? {
        if (!enabled) return null
        val cmd = defaults[chord] ?: return null
        return if (cmd.name in disabled) null else cmd
    }

    fun chordsFor(cmd: ShellCommand): List<KeyChord> = defaults.filterValues { it == cmd }.keys.toList()
}

/** Minimal description of an Android InputDevice, so detection logic is testable on the JVM. */
data class InputDeviceInfo(
    val name: String,
    val isVirtual: Boolean,
    val isExternal: Boolean?,
    val hasMouseSource: Boolean,
    val hasTouchpadSource: Boolean,
    val isAlphabeticKeyboard: Boolean,
    val hasGamepadSource: Boolean,
)

data class PhysicalInputSummary(
    val mice: List<String> = emptyList(),
    val keyboards: List<String> = emptyList(),
    val gamepads: List<String> = emptyList(),
) {
    val hasMouse get() = mice.isNotEmpty()
    val hasKeyboard get() = keyboards.isNotEmpty()

    companion object {
        /**
         * Built-in phone devices (e.g. "gpio-keys", touch screens) are excluded. When Android cannot say whether a device is
         * external (API < 29), virtual devices are excluded and the rest are reported as "possibly external".
         */
        fun from(devices: List<InputDeviceInfo>): PhysicalInputSummary {
            val real = devices.filter { !it.isVirtual && it.isExternal != false }
            return PhysicalInputSummary(
                mice = real.filter { it.hasMouseSource || it.hasTouchpadSource }.map { it.name }.distinct(),
                keyboards = real.filter { it.isAlphabeticKeyboard }.map { it.name }.distinct(),
                gamepads = real.filter { it.hasGamepadSource }.map { it.name }.distinct(),
            )
        }
    }
}

enum class TouchpadDecision { SHOW, HIDE }

object TouchpadPolicy {
    /** AUTO hides the on-screen touchpad when a physical mouse is connected. */
    fun decide(visibility: com.chronos.pocketos.core.settings.TouchpadVisibility, input: PhysicalInputSummary): TouchpadDecision = when (visibility) {
        com.chronos.pocketos.core.settings.TouchpadVisibility.ALWAYS -> TouchpadDecision.SHOW
        com.chronos.pocketos.core.settings.TouchpadVisibility.NEVER -> TouchpadDecision.HIDE
        com.chronos.pocketos.core.settings.TouchpadVisibility.AUTO -> if (input.hasMouse) TouchpadDecision.HIDE else TouchpadDecision.SHOW
    }
}
