package com.chronos.pocketos.core

import com.chronos.pocketos.core.window.DesktopState
import com.chronos.pocketos.core.window.SnapSide
import com.chronos.pocketos.core.window.WinRect
import com.chronos.pocketos.core.window.WindowManager
import com.chronos.pocketos.core.window.WindowMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowManagerTest {
    private val wide = DesktopState(area = WinRect(0, 0, 1280, 700))

    @Test fun openFocusesNewWindowOnTop() {
        var s = WindowManager.open(wide, "files", "Files")
        s = WindowManager.open(s, "editor", "Editor")
        assertEquals(listOf(1, 2), s.zOrder)
        assertEquals(2, s.focusedId)
        assertEquals(WindowMode.NORMAL, s.windows[2]!!.mode)
    }

    @Test fun singleInstanceReusesWindow() {
        var s = WindowManager.open(wide, "settings", "Settings", singleInstance = true)
        s = WindowManager.open(s, "files", "Files")
        s = WindowManager.open(s, "settings", "Settings", singleInstance = true)
        assertEquals(2, s.windows.size)
        assertEquals(1, s.focusedId)
        assertEquals(listOf(2, 1), s.zOrder)
    }

    @Test fun compactAreaOpensMaximized() {
        val s = WindowManager.open(DesktopState(area = WinRect(0, 0, 400, 700)), "files", "Files")
        assertEquals(WindowMode.MAXIMIZED, s.windows[1]!!.mode)
        assertEquals(WinRect(0, 0, 400, 700), s.windows[1]!!.bounds)
    }

    @Test fun minimizeMovesFocusToNextVisible() {
        var s = WindowManager.open(wide, "a", "A")
        s = WindowManager.open(s, "b", "B")
        s = WindowManager.minimize(s, 2)
        assertEquals(1, s.focusedId)
        assertEquals(WindowMode.MINIMIZED, s.windows[2]!!.mode)
        assertEquals(listOf(1), s.visibleWindows.map { it.id })
    }

    @Test fun taskbarToggleRestoresThenMinimizes() {
        var s = WindowManager.open(wide, "a", "A")
        s = WindowManager.minimize(s, 1)
        assertNull(s.focusedId)
        s = WindowManager.taskbarToggle(s, 1)
        assertEquals(WindowMode.NORMAL, s.windows[1]!!.mode)
        assertEquals(1, s.focusedId)
        s = WindowManager.taskbarToggle(s, 1)
        assertEquals(WindowMode.MINIMIZED, s.windows[1]!!.mode)
    }

    @Test fun maximizeAndRestoreKeepsOriginalBounds() {
        var s = WindowManager.open(wide, "a", "A")
        val original = s.windows[1]!!.bounds
        s = WindowManager.toggleMaximize(s, 1)
        assertEquals(wide.area, s.windows[1]!!.bounds)
        s = WindowManager.toggleMaximize(s, 1)
        assertEquals(original, s.windows[1]!!.bounds)
        assertEquals(WindowMode.NORMAL, s.windows[1]!!.mode)
    }

    @Test fun minimizedMaximizedWindowRestoresMaximized() {
        var s = WindowManager.open(wide, "a", "A")
        s = WindowManager.toggleMaximize(s, 1)
        s = WindowManager.minimize(s, 1)
        s = WindowManager.focus(s, 1)
        assertEquals(WindowMode.MAXIMIZED, s.windows[1]!!.mode)
    }

    @Test fun closeRemovesAndRefocuses() {
        var s = WindowManager.open(wide, "a", "A")
        s = WindowManager.open(s, "b", "B")
        s = WindowManager.close(s, 2)
        assertEquals(setOf(1), s.windows.keys)
        assertEquals(1, s.focusedId)
        s = WindowManager.close(s, 1)
        assertNull(s.focusedId)
        assertTrue(s.zOrder.isEmpty())
    }

    @Test fun resizeRespectsMinimumAndArea() {
        var s = WindowManager.open(wide, "a", "A")
        s = WindowManager.resizeBy(s, 1, -5000, -5000)
        assertEquals(WindowManager.MIN_WIDTH, s.windows[1]!!.bounds.width)
        assertEquals(WindowManager.MIN_HEIGHT, s.windows[1]!!.bounds.height)
        s = WindowManager.resizeBy(s, 1, 50_000, 50_000)
        val b = s.windows[1]!!.bounds
        assertTrue(b.right <= wide.area.right && b.bottom <= wide.area.bottom)
    }

    @Test fun moveKeepsTitleBarReachable() {
        var s = WindowManager.open(wide, "a", "A")
        s = WindowManager.moveBy(s, 1, 10_000, 10_000)
        val b = s.windows[1]!!.bounds
        assertTrue(b.x <= wide.area.right - 64)
        assertTrue(b.y <= wide.area.bottom - 40)
        s = WindowManager.moveBy(s, 1, -100_000, -100_000)
        assertTrue(s.windows[1]!!.bounds.y >= 0)
    }

    @Test fun snapUsesHalfArea() {
        var s = WindowManager.open(wide, "a", "A")
        s = WindowManager.snap(s, 1, SnapSide.RIGHT)
        assertEquals(WinRect(640, 0, 640, 700), s.windows[1]!!.bounds)
    }

    @Test fun cycleFocusWalksAllWindows() {
        var s = WindowManager.open(wide, "a", "A")
        s = WindowManager.open(s, "b", "B")
        s = WindowManager.open(s, "c", "C")
        val seen = mutableListOf<Int?>()
        repeat(3) { s = WindowManager.cycleFocus(s); seen += s.focusedId }
        assertEquals(setOf(1, 2, 3), seen.toSet())
    }

    @Test fun resizeAreaRefitsWindows() {
        var s = WindowManager.open(wide, "a", "A")
        s = WindowManager.moveBy(s, 1, 600, 0)
        s = WindowManager.open(s, "b", "B")
        s = WindowManager.toggleMaximize(s, 2)
        val small = WinRect(0, 0, 500, 400)
        s = WindowManager.resizeArea(s, small)
        s.windows.values.forEach { w ->
            assertTrue("window ${w.id} must fit", w.bounds.x >= 0 && w.bounds.right <= 500 && w.bounds.bottom <= 400)
        }
        assertEquals(small, s.windows[2]!!.bounds)
    }

    @Test fun hitTestReturnsTopmost() {
        var s = WindowManager.open(wide, "a", "A")
        s = WindowManager.open(s, "b", "B")
        val b = s.windows[2]!!.bounds
        assertEquals(2, WindowManager.topWindowAt(s, b.x + 5f, b.y + 5f)?.id)
        assertNull(WindowManager.topWindowAt(s, 1279f, 699f).takeIf { s.windows.values.none { w -> w.bounds.contains(1279f, 699f) } })
    }
}
