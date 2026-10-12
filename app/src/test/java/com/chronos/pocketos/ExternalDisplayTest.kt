package com.chronos.pocketos

import android.view.Display
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronos.pocketos.core.display.ExternalDisplayStatus
import com.chronos.pocketos.core.display.SessionPhase
import com.chronos.pocketos.core.input.InputAction
import com.chronos.pocketos.core.input.MouseButton
import com.chronos.pocketos.display.DesktopPresentation
import com.chronos.pocketos.ui.apps.EditorBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
class ExternalDisplayTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<PocketOsApp>()

    /** Simulates plugging in a monitor. Flags are applied through the display shadow, then a change event is sent. */
    private fun connect(flags: Int = Display.FLAG_PRESENTATION): Int {
        val q = "w1920dp-h1080dp-mdpi"
        val id = ShadowDisplayManager.addDisplay(q)
        val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java)
        org.robolectric.Shadows.shadowOf(dm.getDisplay(id)).apply { setFlags(flags); setName("HDMI monitor") }
        rule.runOnIdle { app.displays.refresh("test: display $id configured") }
        rule.waitForIdle()
        return id
    }

    private fun centerPx(tag: String): Offset {
        val r = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return Offset(r.center.x, r.center.y)
    }

    private fun presentation(): DesktopPresentation? = ShadowDialog.getShownDialogs().filterIsInstance<DesktopPresentation>().lastOrNull { it.isShowing }

    @Test fun noDisplayExplainsHardwareRequirement() {
        assertEquals(ExternalDisplayStatus.NoneConnected, app.displays.state.value.status)
        rule.onNodeWithTag("taskbar").assertIsDisplayed()
    }

    @Test fun connectingMonitorStartsSeparateDesktopAndPhoneBecomesTouchpad() {
        val id = connect()
        val s = app.displays.state.value
        assertEquals(SessionPhase.ACTIVE, s.phase)
        assertEquals(id, s.displayId)
        val p = presentation()
        assertNotNull("presentation shown", p)
        assertEquals(id, p!!.display.displayId)
        rule.onNodeWithTag("companion").assertIsDisplayed()
        rule.onNodeWithTag("touchpad_surface").assertIsDisplayed()
        assertEquals("external", app.cursor.activeTargetId.value)
        // Exactly one desktop is composed and it lives on the monitor.
        assertEquals(1, rule.onAllNodesWithTag("desktop").fetchSemanticsNodes().size)
        val r = rule.onNodeWithTag("desktop").fetchSemanticsNode().boundsInRoot
        assertEquals(1920f, r.width, 1f)
        assertEquals(1080f, r.height, 1f)
    }

    @Test fun smartCursorControlsExternalDesktop() {
        connect()
        val c = centerPx("icon_calculator")
        rule.runOnIdle { app.cursor.warpTo(c.x, c.y); app.cursor.handle(listOf(InputAction.Click(MouseButton.LEFT))) }
        rule.waitForIdle()
        assertEquals(listOf("calculator"), app.shell.desktop.value.windows.values.map { it.appId })
        rule.onNodeWithTag("companion_launcher").performClick()
        assertTrue(app.shell.ui.value.launcherOpen)
    }

    @Test fun disconnectReturnsDesktopToPhoneWithoutLosingWindows() {
        val id = connect()
        rule.runOnIdle { app.shell.openApp("editor") }
        rule.waitForIdle()
        val editorId = app.shell.desktop.value.focusedId!!
        rule.waitUntil(5_000) { (app.windowState[editorId] as? EditorBuffer)?.loaded == true }
        (app.windowState[editorId] as EditorBuffer).apply { text = "unsaved draft"; dirty = true }
        ShadowDisplayManager.removeDisplay(id)
        rule.waitForIdle()
        assertEquals(SessionPhase.IDLE, app.displays.state.value.phase)
        assertTrue(app.displays.state.value.lastEvent!!.contains("disconnected"))
        assertFalse(presentation()?.isShowing == true)
        rule.onNodeWithTag("taskbar").assertIsDisplayed()
        assertEquals("phone", app.cursor.activeTargetId.value)
        assertEquals(listOf("editor"), app.shell.desktop.value.windows.values.map { it.appId })
        assertEquals("unsaved draft", (app.windowState[editorId] as EditorBuffer).text)
        // Windows were refit to the phone's area.
        val area = app.shell.desktop.value.area
        app.shell.desktop.value.windows.values.forEach { assertTrue(it.bounds.right <= area.right) }
        // Reconnect starts again.
        connect()
        assertEquals(SessionPhase.ACTIVE, app.displays.state.value.phase)
    }

    @Test fun mirrorOnlyDisplayIsExplainedAndNotUsed() {
        connect(flags = 0)
        val s = app.displays.state.value
        assertEquals(SessionPhase.IDLE, s.phase)
        assertTrue(s.status is ExternalDisplayStatus.Unusable)
        assertTrue(presentation() == null)
        rule.onNodeWithTag("taskbar").assertIsDisplayed()
    }

    @Test fun userCanReturnDesktopToPhoneAndStartAgain() {
        connect()
        rule.onNodeWithTag("companion_stop").performClick()
        rule.waitForIdle()
        assertEquals(SessionPhase.STOPPED_BY_USER, app.displays.state.value.phase)
        assertFalse(presentation()?.isShowing == true)
        rule.onNodeWithTag("taskbar").assertIsDisplayed()
        rule.runOnIdle { app.displays.startSession() }
        rule.waitForIdle()
        assertTrue(presentation()?.isShowing == true)
    }

    @Test fun autoStartCanBeDisabled() {
        rule.runOnIdle { app.settings.update { it.copy(autoStartDesktopOnExternalDisplay = false) } }
        connect()
        assertEquals(SessionPhase.IDLE, app.displays.state.value.phase)
        assertTrue(app.displays.state.value.status is ExternalDisplayStatus.Available)
    }

    @Test fun companionKeyboardTypesIntoExternalEditor() {
        connect()
        rule.runOnIdle { app.shell.openApp("editor") }
        rule.waitForIdle()
        val c = centerPx("editor_text")
        rule.runOnIdle {
            app.cursor.warpTo(c.x, c.y)
            app.cursor.handle(listOf(InputAction.Click(MouseButton.LEFT)))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("companion_keyboard").performClick()
        rule.onNodeWithTag("companion_text").performTextInput("hi")
        rule.onNodeWithTag("companion_send").performClick()
        rule.waitForIdle()
        val id = app.shell.desktop.value.focusedId!!
        assertEquals("hi", (app.windowState[id] as EditorBuffer).text)
    }
}
