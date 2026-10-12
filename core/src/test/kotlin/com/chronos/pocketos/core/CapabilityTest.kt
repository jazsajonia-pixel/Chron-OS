package com.chronos.pocketos.core

import com.chronos.pocketos.core.capability.Capability
import com.chronos.pocketos.core.capability.DeviceCapabilities
import com.chronos.pocketos.core.capability.DisplayRecommendation
import com.chronos.pocketos.core.capability.PerformanceProfile
import com.chronos.pocketos.core.capability.ProfileSelector
import com.chronos.pocketos.core.capability.Thermal
import com.chronos.pocketos.core.display.DisplayInfo
import com.chronos.pocketos.core.display.DisplayModeInfo
import com.chronos.pocketos.core.settings.ProfileChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityTest {
    private val gb = 1024L * 1024 * 1024
    private fun caps(
        ram: Capability<Long> = Capability.Known(6 * gb), cores: Int = 8, lowRam: Boolean = false,
        powerSave: Capability<Boolean> = Capability.Known(false), thermal: Capability<Int> = Capability.Known(Thermal.NONE),
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
    ) = DeviceCapabilities(
        "Acme", "P1", "14", 34, abis, Capability.Known(cores), ram, Capability.Unknown("n/a"), Capability.Known(256),
        lowRam, false, Capability.Known(10 * gb), powerSave, thermal, null, null, false, Capability.Unknown("x"), Capability.Known("3.2"),
    )

    @Test fun deterministicAutoSelection() {
        assertEquals(PerformanceProfile.BALANCED, ProfileSelector.select(caps(), ProfileChoice.AUTO).profile)
        assertEquals(PerformanceProfile.PERFORMANCE, ProfileSelector.select(caps(ram = Capability.Known(12 * gb)), ProfileChoice.AUTO).profile)
        assertEquals(PerformanceProfile.BALANCED, ProfileSelector.select(caps(ram = Capability.Known(12 * gb), cores = 4), ProfileChoice.AUTO).profile)
        assertEquals(PerformanceProfile.BATTERY_SAVER, ProfileSelector.select(caps(ram = Capability.Known(2 * gb)), ProfileChoice.AUTO).profile)
        assertEquals(PerformanceProfile.BATTERY_SAVER, ProfileSelector.select(caps(lowRam = true), ProfileChoice.AUTO).profile)
        assertEquals(PerformanceProfile.BATTERY_SAVER, ProfileSelector.select(caps(powerSave = Capability.Known(true)), ProfileChoice.AUTO).profile)
        repeat(3) { assertEquals(ProfileSelector.select(caps(), ProfileChoice.AUTO), ProfileSelector.select(caps(), ProfileChoice.AUTO)) }
    }

    @Test fun unknownRamFallsBackToBalancedWithReason() {
        val d = ProfileSelector.select(caps(ram = Capability.Unknown("blocked")), ProfileChoice.AUTO)
        assertEquals(PerformanceProfile.BALANCED, d.profile)
        assertTrue(d.reasons.single().contains("could not be read"))
    }

    @Test fun overheatingForcesBatterySaverInAutoAndWarnsOnManual() {
        val hot = caps(ram = Capability.Known(12 * gb), thermal = Capability.Known(Thermal.SEVERE))
        assertEquals(PerformanceProfile.BATTERY_SAVER, ProfileSelector.select(hot, ProfileChoice.AUTO).profile)
        val manual = ProfileSelector.select(hot, ProfileChoice.PERFORMANCE)
        assertEquals(PerformanceProfile.PERFORMANCE, manual.profile)
        assertFalse(manual.automatic)
        assertTrue(manual.warnings.any { it.contains("hot") })
    }

    @Test fun manualPerformanceOnLowMemoryWarns() {
        val d = ProfileSelector.select(caps(ram = Capability.Known(3 * gb)), ProfileChoice.PERFORMANCE)
        assertTrue(d.warnings.any { it.contains("battery") })
    }

    private val monitor = DisplayInfo(
        2, "HDMI", 3840, 2160, 160, 60f, false, true, false, true,
        modes = listOf(
            DisplayModeInfo(1, 3840, 2160, 60f), DisplayModeInfo(2, 2560, 1440, 120f), DisplayModeInfo(3, 2560, 1440, 60f),
            DisplayModeInfo(4, 1920, 1080, 60f), DisplayModeInfo(5, 1920, 1080, 30f), DisplayModeInfo(6, 1280, 720, 60f),
        ),
    )

    @Test fun recommendsOnlyReportedModes() {
        assertEquals(4, DisplayRecommendation.recommendMode(monitor, PerformanceProfile.BALANCED)!!.id)
        assertEquals(2, DisplayRecommendation.recommendMode(monitor, PerformanceProfile.PERFORMANCE)!!.id)
        assertEquals(4, DisplayRecommendation.recommendMode(monitor, PerformanceProfile.BATTERY_SAVER)!!.id)
        assertNull(DisplayRecommendation.recommendMode(monitor.copy(modes = emptyList()), PerformanceProfile.BALANCED))
        val only4k = monitor.copy(modes = listOf(DisplayModeInfo(9, 3840, 2160, 60f)))
        assertEquals(9, DisplayRecommendation.recommendMode(only4k, PerformanceProfile.BALANCED)!!.id)
        DisplayRecommendation.recommendMode(monitor, PerformanceProfile.BALANCED).let { m -> assertTrue(monitor.modes.contains(m)) }
    }

    @Test fun uiScaleRecommendation() {
        assertEquals(1.15f, DisplayRecommendation.recommendUiScale(monitor), 0.001f)
        assertEquals(1.0f, DisplayRecommendation.recommendUiScale(monitor.copy(widthPx = 1920)), 0.001f)
        assertEquals(0.9f, DisplayRecommendation.recommendUiScale(monitor.copy(widthPx = 1024)), 0.001f)
    }
}
