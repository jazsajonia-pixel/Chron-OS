package com.chronos.pocketos

import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.engine.WineHost
import com.chronos.pocketos.runtime.RuntimeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Windows programs on the PocketOS desktop: no extra screen, no wrapper window, several at once, keyboard focus. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
class WindowsDesktopTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestPocketOsApp>()
    private val fake get() = app.fakeRuntime

    private fun ready() {
        fake.makeReady()
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        rule.runOnIdle { app.capabilities.recheck("test"); app.runtime.refresh() }
    }

    private fun start(title: String): FakeRuntimeBackend.Launch {
        val r = rule.runOnIdle { app.runtime.launch(com.chronos.pocketos.core.runtime.SessionPurpose.RUN, null, null, null, RuntimeProfile(), title, "/x/$title.exe") }
        assertTrue(r is RuntimeManager.LaunchResult.Started)
        return fake.lastLaunch!!
    }

    @Test fun windowsLayerSitsOverTheDesktopAndLetsTouchesThrough() {
        rule.waitForIdle()
        val content = rule.activity.findViewById<FrameLayout>(android.R.id.content)
        val layer = (0 until content.childCount).map(content::getChildAt).filterIsInstance<WineHost.DesktopLayer>().single()
        // It covers the desktop above the taskbar.
        val area = app.windowsArea.value
        assertNotNull(area)
        assertEquals(area!!.width(), layer.layoutParams.width)
        assertEquals(area.height(), layer.layoutParams.height)
        assertEquals(View.VISIBLE, layer.visibility)
        // With no Windows window under the finger, the touch goes to PocketOS.
        assertFalse(layer.hasWindowAt(10f, 10f))
        // The launcher is drawn by PocketOS, so Windows windows step aside while it is open.
        rule.runOnIdle { app.shell.toggleLauncher() }
        rule.waitForIdle()
        assertEquals(View.INVISIBLE, layer.visibility)
    }

    @Test fun severalProgramsRunAtOnceWithoutAnotherScreenOrWrapperWindow() {
        ready()
        val a = start("Code")
        val b = start("Notepad")
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(listOf("Code", "Notepad"), app.runtime.sessions.value.map { it.title })
        assertTrue(app.shell.desktop.value.windows.isEmpty())
        rule.runOnIdle { a.session.end(0) }
        assertEquals(listOf("Notepad"), app.runtime.sessions.value.map { it.title })
        rule.runOnIdle { app.runtime.stopAll() }
        assertTrue(b.session.stopped)
        assertTrue(app.runtime.sessions.value.isEmpty())
    }

    @Test fun keyboardGoesToTheTappedWindowsWindowUntilPocketOsTakesFocus() {
        ready()
        val launch = start("Code")
        val key = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        // Nothing tapped yet: keys stay with PocketOS.
        assertFalse(rule.runOnIdle { app.routeKeyToWindows(key) })
        rule.runOnIdle { app.onWindowsWindowTapped() }
        assertTrue(rule.runOnIdle { app.routeKeyToWindows(key) })
        assertEquals(1, launch.session.keys.size)
        // PocketOS shortcuts are never taken.
        assertFalse(rule.runOnIdle { app.routeKeyToWindows(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, 0, KeyEvent.META_META_ON)) })
        // Focusing a PocketOS window takes the keyboard back.
        rule.runOnIdle { app.shell.openApp("files"); app.shell.desktop.value.focusedId?.let(app.shell::focus) }
        assertFalse(rule.runOnIdle { app.routeKeyToWindows(key) })
        rule.runOnIdle { app.runtime.stopAll() }
    }
}
