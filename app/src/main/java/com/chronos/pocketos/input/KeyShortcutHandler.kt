package com.chronos.pocketos.input

import android.view.KeyEvent
import com.chronos.pocketos.core.input.Key
import com.chronos.pocketos.core.input.KeyChord
import com.chronos.pocketos.core.input.ShellCommand
import com.chronos.pocketos.core.input.ShortcutMap
import com.chronos.pocketos.core.settings.ShellSettings
import com.chronos.pocketos.core.window.SnapSide
import com.chronos.pocketos.shell.AppIds
import com.chronos.pocketos.shell.ShellController

/** Translates physical keyboard chords into PocketOS shell commands. Called from Activity/Presentation dispatchKeyEvent. */
class KeyShortcutHandler(
    private val shell: ShellController,
    private val settings: () -> ShellSettings,
    private val cursor: SmartCursor,
) {
    fun handle(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && (event.keyCode == KeyEvent.KEYCODE_ALT_LEFT || event.keyCode == KeyEvent.KEYCODE_ALT_RIGHT)) {
            if (shell.ui.value.switcherOpen) { shell.hideSwitcher(); return true }
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val key = map(event.keyCode)
        if (key == Key.ESCAPE && !event.isCtrlPressed && shell.handleEscape()) return true
        if (key == Key.OTHER) return false
        val chord = KeyChord(key, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed)
        val s = settings()
        val cmd = ShortcutMap.resolve(chord, s.shortcutsEnabled, s.disabledShortcuts) ?: return false
        execute(cmd)
        return true
    }

    fun execute(cmd: ShellCommand) {
        val focused = shell.desktop.value.focusedId
        when (cmd) {
            ShellCommand.OPEN_LAUNCHER -> shell.toggleLauncher()
            ShellCommand.NEXT_WINDOW -> { shell.cycleWindows(); shell.showSwitcher() }
            ShellCommand.PREVIOUS_WINDOW -> { shell.cycleWindows(reverse = true); shell.showSwitcher() }
            ShellCommand.CLOSE_WINDOW -> shell.closeFocused()
            ShellCommand.MINIMIZE_WINDOW -> focused?.let(shell::minimize)
            ShellCommand.MAXIMIZE_WINDOW -> focused?.let(shell::toggleMaximize)
            ShellCommand.SNAP_LEFT -> focused?.let { shell.snap(it, SnapSide.LEFT) }
            ShellCommand.SNAP_RIGHT -> focused?.let { shell.snap(it, SnapSide.RIGHT) }
            ShellCommand.OPEN_FILES -> shell.openApp(AppIds.FILES)
            ShellCommand.OPEN_SETTINGS -> shell.openApp(AppIds.SETTINGS)
            ShellCommand.OPEN_EDITOR -> shell.openApp(AppIds.EDITOR)
            ShellCommand.TOGGLE_CURSOR -> if (cursor.active.value) cursor.hide() else cursor.show()
        }
    }

    private fun map(code: Int): Key = when (code) {
        KeyEvent.KEYCODE_ESCAPE -> Key.ESCAPE
        KeyEvent.KEYCODE_TAB -> Key.TAB
        KeyEvent.KEYCODE_SPACE -> Key.SPACE
        KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> Key.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
        KeyEvent.KEYCODE_F4 -> Key.F4
        KeyEvent.KEYCODE_W -> Key.W
        KeyEvent.KEYCODE_E -> Key.E
        KeyEvent.KEYCODE_S -> Key.S
        KeyEvent.KEYCODE_T -> Key.T
        KeyEvent.KEYCODE_C -> Key.C
        else -> Key.OTHER
    }
}
