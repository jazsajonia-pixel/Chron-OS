package com.chronos.pocketos.shell

import com.chronos.pocketos.core.window.DesktopState
import com.chronos.pocketos.core.window.SnapSide
import com.chronos.pocketos.core.window.WinRect
import com.chronos.pocketos.core.window.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

data class ShellUi(
    val launcherOpen: Boolean = false,
    val switcherOpen: Boolean = false,
    /** Desktop context menu position in desktop dp, or null when hidden. */
    val contextMenuAt: Pair<Float, Float>? = null,
    val message: String? = null,
    val touchpadOpen: Boolean = false,
)

/**
 * Process-wide owner of the desktop session. UI surfaces (phone screen or external display)
 * render this state; they never own it, so a surface being destroyed cannot lose windows.
 */
class ShellController(initial: DesktopState = DesktopState()) {
    private val _desktop = MutableStateFlow(initial)
    val desktop: StateFlow<DesktopState> = _desktop.asStateFlow()

    private val _ui = MutableStateFlow(ShellUi())
    val ui: StateFlow<ShellUi> = _ui.asStateFlow()

    fun openApp(appId: String, argument: String? = null) {
        val spec = BuiltInApps.find(appId) ?: return
        _desktop.update {
            WindowManager.open(it, spec.id, spec.title, spec.defaultWidth, spec.defaultHeight, argument, spec.singleInstance)
        }
        _ui.update { it.copy(launcherOpen = false, contextMenuAt = null, switcherOpen = false) }
    }

    fun close(id: Int) = _desktop.update { WindowManager.close(it, id) }
    fun focus(id: Int) = _desktop.update { WindowManager.focus(it, id) }
    fun minimize(id: Int) = _desktop.update { WindowManager.minimize(it, id) }
    fun toggleMaximize(id: Int) = _desktop.update { WindowManager.toggleMaximize(it, id) }
    fun taskbarToggle(id: Int) = _desktop.update { WindowManager.taskbarToggle(it, id) }
    fun snap(id: Int, side: SnapSide) = _desktop.update { WindowManager.snap(it, id, side) }
    fun moveBy(id: Int, dx: Int, dy: Int) = _desktop.update { WindowManager.moveBy(it, id, dx, dy) }
    fun resizeBy(id: Int, dw: Int, dh: Int) = _desktop.update { WindowManager.resizeBy(it, id, dw, dh) }
    fun setArea(area: WinRect) = _desktop.update { WindowManager.resizeArea(it, area) }
    fun setTitle(id: Int, title: String) = _desktop.update { s ->
        val w = s.windows[id] ?: return@update s
        s.copy(windows = s.windows + (id to w.copy(title = title)))
    }

    fun cycleWindows(reverse: Boolean = false) {
        _desktop.update { WindowManager.cycleFocus(it, reverse) }
    }

    fun closeFocused() { _desktop.value.focusedId?.let(::close) }

    fun setTouchpadOpen(open: Boolean) = _ui.update { it.copy(touchpadOpen = open) }
    fun showSwitcher() = _ui.update { it.copy(switcherOpen = true) }
    fun hideSwitcher() = _ui.update { it.copy(switcherOpen = false) }

    /** Escape closes transient UI (menus, launcher, switcher). Returns true if something was closed. */
    fun handleEscape(): Boolean {
        val ui = _ui.value
        if (ui.contextMenuAt == null && !ui.launcherOpen && !ui.switcherOpen) return false
        _ui.update { it.copy(contextMenuAt = null, launcherOpen = false, switcherOpen = false) }
        return true
    }

    fun toggleLauncher() = _ui.update { it.copy(launcherOpen = !it.launcherOpen, contextMenuAt = null) }
    fun closeLauncher() = _ui.update { it.copy(launcherOpen = false) }
    fun showContextMenu(x: Float, y: Float) = _ui.update { it.copy(contextMenuAt = x to y, launcherOpen = false) }
    fun dismissContextMenu() = _ui.update { it.copy(contextMenuAt = null) }
    fun showMessage(text: String) = _ui.update { it.copy(message = text) }
    fun clearMessage() = _ui.update { it.copy(message = null) }

    /**
     * Back button policy: close transient UI first, then minimize the focused window.
     * Returns false when there is nothing for PocketOS to handle (Android may then leave the app).
     */
    fun handleBack(): Boolean {
        val ui = _ui.value
        if (ui.contextMenuAt != null) { dismissContextMenu(); return true }
        if (ui.launcherOpen) { closeLauncher(); return true }
        val focused = _desktop.value.focused ?: return false
        minimize(focused.id)
        return true
    }

    fun encodeSession(): String = json.encodeToString(DesktopState.serializer(), _desktop.value)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decodeSession(text: String?): DesktopState = if (text.isNullOrBlank()) DesktopState() else
            runCatching { json.decodeFromString(DesktopState.serializer(), text) }.getOrDefault(DesktopState())
                .let { s -> s.copy(windows = s.windows.filterValues { BuiltInApps.find(it.appId) != null }) }
                .let { s -> s.copy(zOrder = s.zOrder.filter { it in s.windows }, focusedId = s.focusedId?.takeIf { it in s.windows }) }
    }
}
