package com.chronos.pocketos.core

import com.chronos.pocketos.core.display.DesktopScale
import com.chronos.pocketos.core.display.DisplayInfo
import com.chronos.pocketos.core.display.DisplaySessionReducer
import com.chronos.pocketos.core.display.DisplaySessionState
import com.chronos.pocketos.core.display.ExternalDisplayPolicy
import com.chronos.pocketos.core.display.ExternalDisplayStatus
import com.chronos.pocketos.core.display.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTest {
    private val phone = DisplayInfo(0, "Built-in", 1080, 2400, 420, 120f, true, false, false, true)
    private val hdmi = DisplayInfo(2, "HDMI", 1920, 1080, 160, 60f, false, true, false, true)
    private val uhd = DisplayInfo(3, "4K", 3840, 2160, 320, 60f, false, true, false, true)
    private val rec = DisplayInfo(5, "Recorder", 1080, 1920, 320, 60f, false, false, true, true)

    @Test fun noExternalDisplay() {
        val s = ExternalDisplayPolicy.evaluate(listOf(phone))
        assertEquals(ExternalDisplayStatus.NoneConnected, s)
        assertTrue(ExternalDisplayPolicy.explain(s).contains("DisplayPort Alt Mode"))
    }

    @Test fun picksLargestUsablePresentationDisplay() {
        val s = ExternalDisplayPolicy.evaluate(listOf(phone, rec, hdmi, uhd))
        assertEquals(uhd, (s as ExternalDisplayStatus.Available).display)
    }

    @Test fun privateOrMirrorOnlyDisplaysAreExplained() {
        val s = ExternalDisplayPolicy.evaluate(listOf(phone, rec))
        assertTrue(s is ExternalDisplayStatus.Unusable)
        assertTrue(ExternalDisplayPolicy.explain(s).contains("another app"))
        val mirror = ExternalDisplayPolicy.evaluate(listOf(phone, hdmi.copy(isPresentation = false)))
        assertTrue(ExternalDisplayPolicy.explain(mirror).contains("mirroring"))
        val off = ExternalDisplayPolicy.evaluate(listOf(phone, hdmi.copy(isOn = false)))
        assertTrue(ExternalDisplayPolicy.explain(off).contains("switched off"))
    }

    @Test fun desktopScaleKeepsReadableWidth() {
        // 1080p at 160dpi → 1920dp: density 1.0
        assertEquals(1.0f, DesktopScale.densityFor(1920, 160), 0.001f)
        // 4K at 160dpi would be 3840dp → clamp to 1920dp → density 2.0
        assertEquals(2.0f, DesktopScale.densityFor(3840, 160), 0.001f)
        // 1280x720 at 320dpi = 640dp → raise to 1024dp... but never above 1px per dp below 1280 → density 1.25
        assertEquals(1.25f, DesktopScale.densityFor(1280, 320), 0.001f)
        // User scale enlarges UI
        assertEquals(1.5f, DesktopScale.densityFor(1920, 160, 1.5f), 0.001f)
        val w = 1920 / DesktopScale.densityFor(1920, 213)
        assertTrue(w in 1024f..1920f)
    }

    @Test fun sessionAutoStartsStopsAndHandlesDisconnect() {
        var s = DisplaySessionState()
        s = DisplaySessionReducer.onDisplaysChanged(s, listOf(phone, hdmi), autoStart = true)
        assertEquals(SessionPhase.ACTIVE, s.phase); assertEquals(2, s.displayId)
        s = DisplaySessionReducer.onDisplaysChanged(s, listOf(phone, hdmi.copy(widthPx = 1280, heightPx = 720)), true)
        assertEquals(SessionPhase.ACTIVE, s.phase)
        s = DisplaySessionReducer.onDisplaysChanged(s, listOf(phone), true)
        assertEquals(SessionPhase.IDLE, s.phase)
        assertTrue(s.lastEvent!!.contains("disconnected"))
        // Reconnect auto-starts again.
        s = DisplaySessionReducer.onDisplaysChanged(s, listOf(phone, hdmi), true)
        assertEquals(SessionPhase.ACTIVE, s.phase)
        // User stops: does not restart on display-changed events for the same display.
        s = DisplaySessionReducer.stop(s)
        s = DisplaySessionReducer.onDisplaysChanged(s, listOf(phone, hdmi), true)
        assertEquals(SessionPhase.STOPPED_BY_USER, s.phase)
        s = DisplaySessionReducer.start(s)
        assertEquals(SessionPhase.ACTIVE, s.phase)
    }

    @Test fun noAutoStartWhenDisabled() {
        val s = DisplaySessionReducer.onDisplaysChanged(DisplaySessionState(), listOf(phone, hdmi), autoStart = false)
        assertEquals(SessionPhase.IDLE, s.phase)
        assertEquals(SessionPhase.ACTIVE, DisplaySessionReducer.start(s).phase)
        assertEquals(SessionPhase.IDLE, DisplaySessionReducer.start(DisplaySessionState()).phase)
    }
}
