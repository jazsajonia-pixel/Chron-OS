package com.chronos.pocketos

import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.runtime.RuntimeState
import com.chronos.pocketos.core.runtime.SessionPurpose
import com.chronos.pocketos.core.window.WindowMode
import com.chronos.pocketos.runtime.RuntimeManager
import com.chronos.pocketos.shell.AppIds
import com.chronos.pocketos.shell.BuiltInApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** A Windows program runs in a PocketOS window: no other screen, PocketOS input, closing the window ends it. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
class WindowsProgramWindowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestPocketOsApp>()
    private val fake get() = app.fakeRuntime

    private fun ready() {
        fake.makeReady()
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        rule.runOnIdle { app.capabilities.recheck("test"); app.runtime.refresh() }
        assertEquals(RuntimeState.Ready, app.runtime.state.value)
    }

    private fun startNotepad(): RuntimeManager.LaunchResult = rule.runOnIdle {
        app.runtime.launch(SessionPurpose.RUN, null, "Apps/notepad/notepad.exe", null, RuntimeProfile(), "Notepad")
    }

    private fun programWindow() = app.shell.desktop.value.windows.values.singleOrNull { it.appId == AppIds.WINDOWS_PROGRAM }

    private fun key(code: Int, meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)

    @Test fun programOpensInAPocketOsWindowWithoutLeavingTheDesktop() {
        ready()
        assertTrue(startNotepad() is RuntimeManager.LaunchResult.Started)
        rule.waitForIdle()
        assertNull("no other screen is opened", shadowOf(app).nextStartedActivity)
        val w = programWindow()!!
        assertEquals("Notepad", w.title)
        assertEquals(w.id, app.shell.desktop.value.focusedId)
        assertTrue(fake.lastLaunch!!.unixPath!!.endsWith("pc-apps/Apps/notepad/notepad.exe"))
        // The program's display is a view inside the window; a loading screen covers it until the first window shows.
        rule.onNodeWithTag("windows-display").assertExists()
        assertEquals(1, fake.lastLaunch!!.session.views)
        rule.onNodeWithText("The first start can take a minute.").assertIsDisplayed()
        rule.runOnIdle { fake.lastLaunch!!.session.status("Preparing Windows files…") }
        rule.onNodeWithTag("windows-status").assertTextContains("Preparing Windows files", substring = true)
        rule.runOnIdle { fake.lastLaunch!!.session.ready() }
        rule.onNodeWithTag("windows-status").assertTextContains("Running")
        assertEquals(0, rule.onAllNodesWithText("The first start can take a minute.").fetchSemanticsNodes().size)
        // Not offered in the launcher and not restored after a restart.
        assertFalse(BuiltInApps.launcherApps().any { it.id == AppIds.WINDOWS_PROGRAM })
        val restored = com.chronos.pocketos.shell.ShellController.decodeSession(app.shell.encodeSession())
        assertTrue(restored.windows.values.none { it.appId == AppIds.WINDOWS_PROGRAM })
    }

    @Test fun keyboardGoesToTheFocusedProgramButPocketOsKeepsItsShortcuts() {
        ready(); startNotepad(); rule.waitForIdle()
        val session = fake.lastLaunch!!.session
        rule.runOnIdle { rule.activity.dispatchKeyEvent(key(KeyEvent.KEYCODE_A)) }
        rule.runOnIdle { rule.activity.dispatchKeyEvent(key(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)) }
        assertEquals(listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C), session.keys.map { it.keyCode })
        // The Windows/Meta key opens PocketOS's launcher instead.
        rule.runOnIdle { rule.activity.dispatchKeyEvent(key(KeyEvent.KEYCODE_META_LEFT)) }
        assertEquals(2, session.keys.size)
        // Smart Cursor typing (used with the companion keyboard) also reaches the program.
        rule.runOnIdle { app.cursor.forwardKey(key(KeyEvent.KEYCODE_B)) }
        assertEquals(KeyEvent.KEYCODE_B, session.keys.last().keyCode)
        // A minimized program gets no keys.
        rule.runOnIdle { app.shell.minimize(programWindow()!!.id) }
        rule.runOnIdle { rule.activity.dispatchKeyEvent(key(KeyEvent.KEYCODE_D)) }
        assertEquals(3, session.keys.size)
        // Restoring creates a new view for the same running program.
        rule.runOnIdle { app.shell.focus(programWindow()!!.id) }
        rule.waitForIdle()
        assertEquals(WindowMode.NORMAL, programWindow()!!.mode)
        assertEquals(2, session.views)
        assertFalse(session.stopped)
        // The window's Keyboard button opens the on-screen keyboard for the program.
        rule.onNodeWithTag("windows-keyboard").performClick()
        assertEquals(1, session.keyboardShown)
    }

    @Test fun closingTheWindowEndsTheProgramAndOneProgramRunsAtATime() {
        ready(); startNotepad(); rule.waitForIdle()
        val session = fake.lastLaunch!!.session
        val busy = startNotepad()
        assertTrue(busy is RuntimeManager.LaunchResult.Busy)
        assertEquals(1, fake.launches.size)
        rule.runOnIdle { app.shell.close(programWindow()!!.id) }
        rule.waitForIdle()
        assertTrue(session.stopped)
        assertNull(app.runtime.active.value)
        assertNull(programWindow())
        // Now another program can start.
        assertTrue(startNotepad() is RuntimeManager.LaunchResult.Started)
    }

    @Test fun stopButtonAsksFirstAndProgramExitClosesTheWindow() {
        ready(); startNotepad(); rule.waitForIdle()
        val first = fake.lastLaunch!!.session
        rule.onNodeWithTag("windows-stop").performClick()
        rule.onNodeWithText("Stop Notepad?").assertIsDisplayed()
        rule.runOnIdle { app.runtime.stopSession() }
        rule.waitForIdle()
        assertTrue(first.stopped)
        assertNull(programWindow())

        startNotepad(); rule.waitForIdle()
        val second = fake.lastLaunch!!.session
        rule.runOnIdle { second.ready(); second.end(0) }
        rule.waitForIdle()
        assertNull("the window closes when the program exits", programWindow())
    }
}
