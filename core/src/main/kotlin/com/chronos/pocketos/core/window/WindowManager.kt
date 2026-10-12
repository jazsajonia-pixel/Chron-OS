package com.chronos.pocketos.core.window

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** Simple integer rectangle in desktop pixels (device-independent units chosen by the host). */
@Serializable
data class WinRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    fun contains(px: Float, py: Float): Boolean = px >= x && px < right && py >= y && py < bottom
}

enum class WindowMode { NORMAL, MINIMIZED, MAXIMIZED }

enum class SnapSide { LEFT, RIGHT }

@Serializable
data class AppWindow(
    val id: Int,
    val appId: String,
    val title: String,
    val bounds: WinRect,
    val mode: WindowMode = WindowMode.NORMAL,
    /** Bounds to restore to after maximize/snap. */
    val restoreBounds: WinRect? = null,
    /** Optional argument passed to the app, e.g. a file path for the editor. */
    val argument: String? = null,
)

/**
 * Immutable desktop window state. [zOrder] lists window ids from back to front.
 * All operations are pure; the host stores the latest state in a StateFlow.
 */
@Serializable
data class DesktopState(
    val windows: Map<Int, AppWindow> = emptyMap(),
    val zOrder: List<Int> = emptyList(),
    val focusedId: Int? = null,
    val nextId: Int = 1,
    /** Usable desktop area (excludes the taskbar). */
    val area: WinRect = WinRect(0, 0, 1280, 720),
) {
    val orderedWindows: List<AppWindow> get() = zOrder.mapNotNull { windows[it] }
    val visibleWindows: List<AppWindow> get() = orderedWindows.filter { it.mode != WindowMode.MINIMIZED }
    val focused: AppWindow? get() = focusedId?.let { windows[it] }
}

/** Pure reducer for window operations. */
object WindowManager {
    const val MIN_WIDTH = 220
    const val MIN_HEIGHT = 160
    private const val CASCADE = 32

    fun open(
        state: DesktopState,
        appId: String,
        title: String,
        preferredWidth: Int = 640,
        preferredHeight: Int = 440,
        argument: String? = null,
        singleInstance: Boolean = false,
    ): DesktopState {
        if (singleInstance) {
            val existing = state.windows.values.firstOrNull { it.appId == appId }
            if (existing != null) {
                val updated = if (argument != null) existing.copy(argument = argument) else existing
                return focus(state.copy(windows = state.windows + (existing.id to updated)), existing.id)
            }
        }
        val area = state.area
        val w = clamp(preferredWidth, min(MIN_WIDTH, area.width), area.width)
        val h = clamp(preferredHeight, min(MIN_HEIGHT, area.height), area.height)
        val offset = (state.windows.size % 6) * CASCADE
        val x = area.x + clamp(offset + (area.width - w) / 8, 0, max(0, area.width - w))
        val y = area.y + clamp(offset + (area.height - h) / 10, 0, max(0, area.height - h))
        val id = state.nextId
        // On small screens (phones in portrait) windows open maximized for usability.
        val compact = area.width < 600
        val window = AppWindow(
            id = id, appId = appId, title = title,
            bounds = if (compact) area else WinRect(x, y, w, h),
            mode = if (compact) WindowMode.MAXIMIZED else WindowMode.NORMAL,
            restoreBounds = if (compact) WinRect(x, y, w, h) else null,
            argument = argument,
        )
        return state.copy(
            windows = state.windows + (id to window),
            zOrder = state.zOrder + id,
            focusedId = id,
            nextId = id + 1,
        )
    }

    fun close(state: DesktopState, id: Int): DesktopState {
        if (id !in state.windows) return state
        val z = state.zOrder - id
        val newFocus = if (state.focusedId == id) topVisible(state.windows - id, z) else state.focusedId
        return state.copy(windows = state.windows - id, zOrder = z, focusedId = newFocus)
    }

    fun focus(state: DesktopState, id: Int): DesktopState {
        val w = state.windows[id] ?: return state
        val restored = if (w.mode == WindowMode.MINIMIZED) {
            w.copy(mode = if (w.restoreBounds != null && w.bounds == state.area) WindowMode.MAXIMIZED else WindowMode.NORMAL)
        } else w
        return state.copy(
            windows = state.windows + (id to restored),
            zOrder = state.zOrder - id + id,
            focusedId = id,
        )
    }

    fun minimize(state: DesktopState, id: Int): DesktopState {
        val w = state.windows[id] ?: return state
        val windows = state.windows + (id to w.copy(mode = WindowMode.MINIMIZED))
        val newFocus = if (state.focusedId == id) topVisible(windows, state.zOrder) else state.focusedId
        return state.copy(windows = windows, focusedId = newFocus)
    }

    /** Taskbar click: focus if hidden/unfocused, minimize if already focused. */
    fun taskbarToggle(state: DesktopState, id: Int): DesktopState {
        val w = state.windows[id] ?: return state
        return if (state.focusedId == id && w.mode != WindowMode.MINIMIZED) minimize(state, id) else focus(state, id)
    }

    fun toggleMaximize(state: DesktopState, id: Int): DesktopState {
        val w = state.windows[id] ?: return state
        val updated = if (w.mode == WindowMode.MAXIMIZED) {
            val restore = w.restoreBounds?.let { fit(it, state.area) } ?: defaultBounds(state.area)
            w.copy(mode = WindowMode.NORMAL, bounds = restore, restoreBounds = null)
        } else {
            w.copy(mode = WindowMode.MAXIMIZED, restoreBounds = w.bounds, bounds = state.area)
        }
        return focus(state.copy(windows = state.windows + (id to updated)), id)
    }

    fun snap(state: DesktopState, id: Int, side: SnapSide): DesktopState {
        val w = state.windows[id] ?: return state
        val a = state.area
        val half = a.width / 2
        val target = when (side) {
            SnapSide.LEFT -> WinRect(a.x, a.y, half, a.height)
            SnapSide.RIGHT -> WinRect(a.x + half, a.y, a.width - half, a.height)
        }
        val updated = w.copy(mode = WindowMode.NORMAL, bounds = target, restoreBounds = w.restoreBounds ?: w.bounds)
        return focus(state.copy(windows = state.windows + (id to updated)), id)
    }

    /** Move by a delta; keeps at least part of the title bar reachable inside the desktop area. */
    fun moveBy(state: DesktopState, id: Int, dx: Int, dy: Int): DesktopState {
        val w = state.windows[id] ?: return state
        if (w.mode != WindowMode.NORMAL) return state
        val a = state.area
        val keep = 64
        val nx = clamp(w.bounds.x + dx, a.x - w.bounds.width + keep, a.right - keep)
        val ny = clamp(w.bounds.y + dy, a.y, a.bottom - 40)
        return state.copy(windows = state.windows + (id to w.copy(bounds = w.bounds.copy(x = nx, y = ny))))
    }

    /** Resize from the bottom-right corner, respecting minimum size and desktop bounds. */
    fun resizeBy(state: DesktopState, id: Int, dw: Int, dh: Int): DesktopState {
        val w = state.windows[id] ?: return state
        if (w.mode != WindowMode.NORMAL) return state
        val a = state.area
        val nw = clamp(w.bounds.width + dw, MIN_WIDTH, max(MIN_WIDTH, a.right - w.bounds.x))
        val nh = clamp(w.bounds.height + dh, MIN_HEIGHT, max(MIN_HEIGHT, a.bottom - w.bounds.y))
        return state.copy(windows = state.windows + (id to w.copy(bounds = w.bounds.copy(width = nw, height = nh))))
    }

    /** Alt+Tab style cycling through windows (including minimized ones), most recent first. */
    fun cycleFocus(state: DesktopState, reverse: Boolean = false): DesktopState {
        if (state.zOrder.size < 2 && state.focusedId != null) return state
        if (state.zOrder.isEmpty()) return state
        val target = if (reverse) state.zOrder.first() else {
            val candidates = state.zOrder.filter { it != state.focusedId }
            candidates.lastOrNull() ?: return state
        }
        return if (reverse) focus(state, target) else {
            // Send the current window to the back so repeated presses walk the stack.
            val current = state.focusedId
            val base = if (current != null) state.copy(zOrder = listOf(current) + (state.zOrder - current)) else state
            focus(base, target)
        }
    }

    /** Adapt to a new desktop area (rotation, external display, taskbar changes). */
    fun resizeArea(state: DesktopState, area: WinRect): DesktopState {
        if (area == state.area) return state
        val windows = state.windows.mapValues { (_, w) ->
            when (w.mode) {
                WindowMode.MAXIMIZED -> w.copy(bounds = area)
                else -> w.copy(bounds = fit(w.bounds, area), restoreBounds = w.restoreBounds?.let { fit(it, area) })
            }
        }
        return state.copy(area = area, windows = windows)
    }

    fun topWindowAt(state: DesktopState, x: Float, y: Float): AppWindow? =
        state.visibleWindows.lastOrNull { it.bounds.contains(x, y) }

    private fun topVisible(windows: Map<Int, AppWindow>, z: List<Int>): Int? =
        z.lastOrNull { windows[it]?.mode != WindowMode.MINIMIZED }

    private fun defaultBounds(a: WinRect) =
        WinRect(a.x + a.width / 8, a.y + a.height / 10, a.width * 3 / 4, a.height * 3 / 4)

    fun fit(r: WinRect, a: WinRect): WinRect {
        val w = clamp(r.width, min(MIN_WIDTH, a.width), a.width)
        val h = clamp(r.height, min(MIN_HEIGHT, a.height), a.height)
        val x = clamp(r.x, a.x, a.right - w)
        val y = clamp(r.y, a.y, a.bottom - h)
        return WinRect(x, y, w, h)
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (hi < lo) lo else v.coerceIn(lo, hi)
}
