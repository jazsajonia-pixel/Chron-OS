package com.chronos.pocketos

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronos.pocketos.core.input.InputAction
import com.chronos.pocketos.core.input.MouseButton
import com.chronos.pocketos.core.window.WindowMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class InputUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<PocketOsApp>()

    /** Center of a node in px, relative to the desktop root (mdpi: 1dp == 1px). */
    private fun centerOf(tag: String): Offset {
        val b = rule.onNodeWithTag(tag).getBoundsInRoot()
        return Offset((b.left.value + b.right.value) / 2, (b.top.value + b.bottom.value) / 2)
    }

    private fun cursorClickAt(o: Offset, button: MouseButton = MouseButton.LEFT) {
        rule.runOnIdle { app.cursor.warpTo(o.x, o.y); app.cursor.handle(listOf(InputAction.Click(button))) }
        rule.waitForIdle()
    }

    @Test fun smartCursorClickOpensDesktopIcon() {
        cursorClickAt(centerOf("icon_calculator"))
        assertEquals(listOf("calculator"), app.shell.desktop.value.windows.values.map { it.appId })
        rule.onNodeWithTag("smart_cursor").assertIsDisplayed()
    }

    @Test fun smartCursorOperatesWindowControlsAndApps() {
        cursorClickAt(centerOf("icon_calculator"))
        listOf("9", "+", "1", "=").forEach { cursorClickAt(centerOf("calc_$it")) }
        rule.onNodeWithTag("calc_expr").assertIsDisplayed()
        assertEquals("10", rule.onNodeWithTag("calc_expr").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text)
        cursorClickAt(centerOf("max_calculator"))
        assertEquals(WindowMode.MAXIMIZED, app.shell.desktop.value.focused!!.mode)
    }

    @Test fun smartCursorRightClickOpensDesktopMenu() {
        cursorClickAt(Offset(700f, 400f), MouseButton.RIGHT)
        rule.onNodeWithTag("context_menu").assertIsDisplayed()
    }

    @Test fun smartCursorDragMovesWindow() {
        cursorClickAt(centerOf("icon_sysinfo"))
        val before = app.shell.desktop.value.focused!!.bounds
        // Title bar: 20px below the window's top edge, away from buttons.
        rule.runOnIdle {
            app.cursor.warpTo(before.x + 60f, before.y + 20f)
            app.cursor.handle(listOf(InputAction.DragStart))
            repeat(10) { app.cursor.handle(listOf(InputAction.DragMove(10f, 5f))) }
            app.cursor.handle(listOf(InputAction.DragEnd))
        }
        rule.waitForIdle()
        val after = app.shell.desktop.value.focused!!.bounds
        assertTrue("window should move right: $before -> $after", after.x > before.x + 40)
        assertTrue(after.y > before.y)
    }

    @Test fun smartCursorScrollScrollsSettings() {
        rule.runOnIdle { app.shell.openApp("settings") }
        rule.waitForIdle()
        val tag = "toggle_icons"
        val before = rule.onNodeWithTag(tag).getBoundsInRoot().top
        val o = centerOf("settings_root")
        rule.runOnIdle { app.cursor.warpTo(o.x, o.y); repeat(5) { app.cursor.handle(listOf(InputAction.Scroll(0f, 80f))) } }
        rule.waitForIdle()
        val after = rule.onNodeWithTag(tag).getBoundsInRoot().top
        assertTrue("content should scroll up: $before -> $after", after < before)
    }

    @Test fun touchpadSurfaceMovesCursor() {
        rule.onNodeWithTag("touchpad_toggle").performClick()
        rule.onNodeWithTag("floating_touchpad").assertIsDisplayed()
        val start = app.cursor.state.value
        rule.onNodeWithTag("touchpad_surface").performTouchInput { swipe(Offset(20f, 40f), Offset(180f, 90f), 300) }
        rule.waitForIdle()
        val end = app.cursor.state.value
        assertTrue("cursor x should increase: $start -> $end", end.x > start.x + 50)
        assertTrue(end.y > start.y)
        assertTrue(app.cursor.active.value)
    }

    @Test fun touchpadTapClicksAtCursor() {
        rule.onNodeWithTag("touchpad_toggle").performClick()
        val icon = centerOf("icon_files")
        rule.runOnIdle { app.cursor.warpTo(icon.x, icon.y) }
        rule.onNodeWithTag("touchpad_surface").performTouchInput { down(center); advanceEventTime(60); up() }
        rule.waitForIdle()
        assertEquals(listOf("files"), app.shell.desktop.value.windows.values.map { it.appId })
    }

    @Test fun physicalMouseHidesSmartCursor() {
        rule.runOnIdle { app.cursor.show() }
        assertTrue(app.cursor.active.value)
        rule.onNodeWithTag("wallpaper").performMouseInput { moveTo(Offset(300f, 300f)); moveBy(Offset(10f, 10f)) }
        rule.waitForIdle()
        assertFalse(app.cursor.active.value)
    }

    private fun key(code: Int, meta: Int) {
        rule.runOnIdle {
            rule.activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))
            rule.activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, meta))
        }
        rule.waitForIdle()
    }

    @Test fun keyboardShortcutsDriveShell() {
        val ctrlAlt = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        key(KeyEvent.KEYCODE_E, ctrlAlt)
        assertEquals("files", app.shell.desktop.value.focused?.appId)
        key(KeyEvent.KEYCODE_S, ctrlAlt)
        assertEquals("settings", app.shell.desktop.value.focused?.appId)
        key(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        assertEquals("files", app.shell.desktop.value.focused?.appId)
        rule.onNodeWithTag("switcher").assertIsDisplayed()
        key(KeyEvent.KEYCODE_ALT_LEFT, 0)
        assertEquals(0, rule.onAllNodesWithTag("switcher").fetchSemanticsNodes().size)
        key(KeyEvent.KEYCODE_DPAD_LEFT, ctrlAlt)
        val area = app.shell.desktop.value.area
        assertEquals(0, app.shell.desktop.value.focused!!.bounds.x)
        assertEquals(area.width / 2, app.shell.desktop.value.focused!!.bounds.width)
        key(KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        rule.onNodeWithTag("launcher").assertIsDisplayed()
        key(KeyEvent.KEYCODE_ESCAPE, 0)
        assertEquals(0, rule.onAllNodesWithTag("launcher").fetchSemanticsNodes().size)
        key(KeyEvent.KEYCODE_F4, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        assertEquals(1, app.shell.desktop.value.windows.size)
    }

    @Test fun disabledShortcutsAreIgnored() {
        rule.runOnIdle { app.settings.update { it.copy(disabledShortcuts = setOf("OPEN_FILES")) } }
        key(KeyEvent.KEYCODE_E, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        assertTrue(app.shell.desktop.value.windows.isEmpty())
        rule.runOnIdle { app.settings.update { it.copy(shortcutsEnabled = false, disabledShortcuts = emptySet()) } }
        key(KeyEvent.KEYCODE_E, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        assertTrue(app.shell.desktop.value.windows.isEmpty())
    }

    @Test fun touchpadHiddenInAutoModeWhenMouseConnectedIsPolicyDriven() {
        // With no physical mouse in Robolectric, AUTO shows the touchpad toggle.
        rule.onNodeWithTag("touchpad_toggle").assertIsDisplayed()
        rule.runOnIdle { app.settings.update { it.copy(touchpadVisibility = com.chronos.pocketos.core.settings.TouchpadVisibility.NEVER) } }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("touchpad_toggle").fetchSemanticsNodes().size)
        assertNotEquals(null, app.input.summary.value)
    }
}
