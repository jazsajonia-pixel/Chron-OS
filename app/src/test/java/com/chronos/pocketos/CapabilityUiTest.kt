package com.chronos.pocketos

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.view.Display
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronos.pocketos.core.capability.Capability
import com.chronos.pocketos.core.capability.PerformanceProfile
import com.chronos.pocketos.core.capability.Thermal
import com.chronos.pocketos.core.settings.ProfileChoice
import com.chronos.pocketos.data.SettingsRepository
import com.chronos.pocketos.display.DesktopPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
class CapabilityUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<PocketOsApp>()
    private val gb = 1024L * 1024 * 1024

    private fun setRam(total: Long, low: Boolean = false) {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = total; availMem = total / 2; lowMemory = low })
    }

    private fun recheck() { rule.runOnIdle { app.capabilities.recheck("test") }; rule.waitForIdle() }

    @Test fun reportUsesRealApiValuesAndUnknownStates() {
        setRam(6 * gb); recheck()
        val c = app.capabilities.capabilities.value
        assertEquals(6 * gb, c.totalRamBytes.valueOrNull())
        assertTrue(c.cpuCores is Capability.Known)
        assertEquals(android.os.Build.VERSION.SDK_INT, c.apiLevel)
        assertTrue(c.thermalStatus is Capability.Known)
        rule.runOnIdle { app.shell.openApp("sysinfo") }
        rule.onNodeWithTag("sysinfo_profile").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Balanced:", substring = true).assertIsDisplayed()
    }

    @Test fun batterySaverSelectsLightProfileAndDisablesEffects() {
        setRam(12 * gb)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        recheck()
        val d = app.capabilities.decision.value
        assertEquals(PerformanceProfile.BATTERY_SAVER, d.profile)
        assertTrue(d.reasons.single().contains("Battery Saver"))
        assertTrue(!d.settings.windowShadows && !d.settings.animations)
        rule.runOnIdle { app.shell.openApp("settings") }
        rule.onNodeWithTag("profile_explanation").performScrollTo().assertIsDisplayed()
    }

    @Test fun lowRamAndOverheatingAreConservative() {
        setRam(12 * gb)
        shadowOf(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).setIsLowRamDevice(true)
        recheck()
        assertEquals(PerformanceProfile.BATTERY_SAVER, app.capabilities.decision.value.profile)
        shadowOf(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).setIsLowRamDevice(false)
        shadowOf(app.getSystemService(PowerManager::class.java)).setCurrentThermalStatus(Thermal.SEVERE)
        recheck()
        assertEquals(PerformanceProfile.BATTERY_SAVER, app.capabilities.decision.value.profile)
        assertTrue(app.capabilities.decision.value.warnings.any { it.contains("hot") })
    }

    @Test fun manualOverrideAppliesAndPersists() {
        setRam(2 * gb); recheck()
        assertEquals(PerformanceProfile.BATTERY_SAVER, app.capabilities.decision.value.profile)
        rule.runOnIdle { app.shell.openApp("settings") }
        rule.onNodeWithTag("profile_choice_Performance").performScrollTo().performClick()
        rule.waitForIdle()
        val d = app.capabilities.decision.value
        assertEquals(PerformanceProfile.PERFORMANCE, d.profile)
        assertTrue(!d.automatic && d.warnings.isNotEmpty())
        assertEquals(ProfileChoice.PERFORMANCE, SettingsRepository(app).settings.value.profileChoice)
    }

    @Test fun exceedingRecommendedWindowsShowsHintButStillOpens() {
        setRam(2 * gb); recheck()
        assertEquals(4, app.shell.maxRecommendedWindows)
        rule.runOnIdle { repeat(5) { app.shell.openApp("editor") } }
        rule.waitForIdle()
        assertEquals(5, app.shell.desktop.value.windows.size)
        assertTrue(app.shell.ui.value.message!!.contains("5 windows"))
    }

    @Test fun trimMemoryTriggersRecheck() {
        rule.runOnIdle { app.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) }
        assertTrue(app.capabilities.lastRecheckReason.contains("free memory"))
    }

    @Test fun recommendedModeIsRequestedOnlyWhenEnabledAndOnlyFromReportedModes() {
        setRam(6 * gb)
        val id = ShadowDisplayManager.addDisplay("w3840dp-h2160dp-mdpi")
        val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java)
        shadowOf(dm.getDisplay(id)).setFlags(Display.FLAG_PRESENTATION)
        // Display.Mode's constructor is hidden in the SDK stubs; the Robolectric runtime has it.
        val ctor = Display.Mode::class.java.getDeclaredConstructor(Int::class.java, Int::class.java, Int::class.java, Float::class.java)
        val modes = arrayOf(ctor.newInstance(1, 3840, 2160, 60f), ctor.newInstance(2, 1920, 1080, 60f), ctor.newInstance(3, 1280, 720, 60f))
        ShadowDisplayManager.setSupportedModes(id, *modes)
        rule.runOnIdle { app.displays.refresh("test") }
        rule.waitForIdle()
        val p = ShadowDialog.getShownDialogs().filterIsInstance<DesktopPresentation>().last { it.isShowing }
        assertEquals(0, p.window!!.attributes.preferredDisplayModeId)
        assertEquals(1.15f, app.uiScaleForExternal(app.settings.settings.value), 0.001f)
        rule.runOnIdle { app.settings.update { it.copy(useRecommendedDisplayMode = true) } }
        rule.waitForIdle()
        assertEquals(2, p.window!!.attributes.preferredDisplayModeId)
    }
}
