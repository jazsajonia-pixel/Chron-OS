package com.chronos.pocketos

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronos.pocketos.engine.WineHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The window bookkeeping that Wine's display driver drives (the same calls wineandroid.so makes): Windows windows
 * become views on the PocketOS desktop at Wine's positions; only they take touches.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
class WineHostTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val WS_VISIBLE = 0x10000000
    private val SWP_NOZORDER = 0x04

    private fun idle() { ShadowLooper.idleMainLooper(); rule.waitForIdle() }

    @Test fun windowsArePlacedWhereWineSaysAndTouchesOutsideThemFallThrough() {
        val host = WineHost.get(rule.activity)
        val layer = host.layer(rule.activity)
        host.reset(); idle()
        var ready = false; var visible = -1
        host.addListener(object : WineHost.Listener {
            override fun onDesktopReady() { ready = true }
            override fun onWindowsChanged(visibleTopLevelWindows: Int) { visible = visibleTopLevelWindows }
            override fun onWindowActivated(hwnd: Int) {}
        })
        host.createDesktopView()
        host.createWindow(0x10, true, false, 0)
        host.createWindow(0x20, false, false, 0x10) // VS Code's main window
        host.createWindow(0x30, false, false, 0x10) // a dialog
        idle()
        assertTrue(ready)
        assertTrue(host.isDesktopReady)
        assertEquals(0, host.visibleTopLevelCount())

        // Wine shows the main window at 100,50 – 900,650 and the dialog in front of it.
        host.windowPosChanged(0x20, SWP_NOZORDER, 0, 0, WS_VISIBLE, 100, 50, 900, 650, 104, 80, 896, 646, 100, 50, 900, 650)
        host.windowPosChanged(0x30, 0, 0, 0x20, WS_VISIBLE, 300, 200, 600, 400, 304, 230, 596, 396, 300, 200, 600, 400)
        idle()
        assertEquals(2, host.visibleTopLevelCount())
        assertEquals(2, visible)
        assertEquals(2, layer.childCount)
        val main = layer.getChildAt(0)
        assertEquals(100, main.left); assertEquals(50, main.top); assertEquals(800, main.width); assertEquals(600, main.height)
        // The dialog was drawn last, so it is in front.
        assertEquals(300, layer.getChildAt(1).left)

        assertTrue(layer.hasWindowAt(150f, 60f))
        assertTrue(layer.hasWindowAt(400f, 300f))
        assertFalse("PocketOS desktop shows through", layer.hasWindowAt(20f, 20f))
        assertFalse(layer.hasWindowAt(950f, 300f))

        // Closing the dialog and hiding the main window leaves the desktop fully touchable again.
        host.destroyWindow(0x30)
        host.windowPosChanged(0x20, SWP_NOZORDER, 0, 0, 0, 100, 50, 900, 650, 104, 80, 896, 646, 100, 50, 900, 650)
        idle()
        assertEquals(0, host.visibleTopLevelCount())
        assertEquals(0, visible)
        assertEquals(0, layer.childCount)
        assertFalse(layer.hasWindowAt(150f, 60f))
        host.reset(); idle()
    }
}
