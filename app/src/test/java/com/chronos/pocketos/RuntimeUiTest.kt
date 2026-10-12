package com.chronos.pocketos

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronos.pocketos.core.runtime.RuntimeState
import com.chronos.pocketos.core.runtime.SampleResult
import com.chronos.pocketos.runtime.RuntimeManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
class RuntimeUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestPocketOsApp>()
    private val fake get() = app.fakeRuntime

    private fun setAbis(vararg abis: String) {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf(*abis))
        rule.runOnIdle { app.capabilities.recheck("test"); app.runtime.refresh() }
    }

    private fun ready() {
        fake.makeReady(); setAbis("arm64-v8a")
        assertEquals(RuntimeState.Ready, app.runtime.state.value)
    }

    private fun openRuntimeTab() {
        rule.runOnIdle { app.shell.openApp("winapps") }
        rule.onNodeWithTag("tab_runtime").performClick()
        rule.waitForIdle()
    }

    private fun awaitRuntime(pred: (RuntimeState) -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!pred(app.runtime.state.value)) {
            check(System.currentTimeMillis() < end) { "Timed out; state=${app.runtime.state.value}" }
            Thread.sleep(20); org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        rule.waitForIdle()
    }

    @Test fun nonArmDeviceIsExplainedHonestly() {
        setAbis("x86_64")
        openRuntimeTab()
        assertTrue(app.runtime.state.value is RuntimeState.Unsupported)
        rule.onNodeWithTag("runtime_status").assertTextContains("64-bit ARM", substring = true)
        assertEquals(0, rule.onAllNodesWithTag("runtime_install").fetchSemanticsNodes().size)
    }

    @Test fun oneTapSetupUnpacksSystemFilesAndCreatesEnvironment() {
        setAbis("arm64-v8a", "armeabi-v7a")
        openRuntimeTab()
        assertEquals(RuntimeState.NotInstalled, app.runtime.state.value)
        rule.onNodeWithTag("runtime_status").assertTextContains("Not set up", substring = true)
        rule.onNodeWithTag("runtime_install").performClick()
        awaitRuntime { it == RuntimeState.Ready }
        assertTrue(fake.installed)
        // Drive D: is PocketOS's programs folder, drive E: the PocketOS Home folder.
        assertEquals(app.runtime.programsDir.path, fake.drives['D'])
        assertEquals(File(app.filesDir, "home").path, fake.drives['E'])
        rule.onNodeWithTag("runtime_status").assertTextContains("Ready")
        rule.onNodeWithTag("runtime_desktop").assertIsDisplayed()
    }

    @Test fun failedSetupIsReportedAndCanBeRetried() {
        setAbis("arm64-v8a")
        fake.installSucceeds = false
        openRuntimeTab()
        rule.onNodeWithTag("runtime_install").performClick()
        awaitRuntime { it is RuntimeState.Failed }
        rule.onNodeWithTag("runtime_status").assertTextContains("Setup failed", substring = true)
        fake.installSucceeds = true
        rule.onNodeWithTag("runtime_install").performClick()
        awaitRuntime { it == RuntimeState.Ready }
    }

    @Test fun selfTestNeedsRuntimeFirst() {
        setAbis("arm64-v8a")
        openRuntimeTab()
        rule.onNodeWithTag("sample_run").performScrollTo().assertIsNotEnabled()
    }

    @Test fun selfTestRunsTheSampleInsidePocketOsAndVerifiesTheMarker() {
        ready(); openRuntimeTab()
        rule.onNodeWithTag("sample_run").performScrollTo().performClick()
        rule.waitForIdle()
        // The sample runs in a PocketOS window, not in another screen.
        assertEquals(null, shadowOf(app).nextStartedActivity)
        assertEquals("winprogram", app.shell.desktop.value.focused?.appId)
        val exe = File(fake.lastLaunch!!.unixPath!!)
        assertEquals(File(app.runtime.programsDir, "PocketOS Sample/pocketos-sample.exe"), exe)
        val bundled = app.assets.open("sample/pocketos-sample.exe").use { it.readBytes() }
        assertArrayEquals(bundled, exe.readBytes())
        val nonce = File(exe.parentFile, "pocketos_nonce.txt").readText().trim()
        assertEquals(16, nonce.length)

        // Simulate the runtime: write exactly what the real sample wrote under Wine 10 (tools/pe-fixtures/README.md),
        // then the session result the runtime process writes on exit.
        File(exe.parentFile, "pocketos_sample_ok.txt").writeText("POCKETOS_FIXTURE_OK $nonce\r\n")
        rule.runOnIdle { fake.finishSession(0) }
        rule.waitForIdle()
        assertTrue(app.shell.ui.value.message!!.contains("Self-test passed"))
        assertEquals(SampleResult.VERIFIED, app.runtime.sample.value.result)
        rule.onNodeWithTag("sample_result").performScrollTo().assertTextContains("Passed", substring = true)
        // Persisted across restarts.
        val again = RuntimeManager(app, fake, kotlinx.coroutines.MainScope(), { listOf("arm64-v8a") }, File(app.filesDir, "home"))
        assertEquals(SampleResult.VERIFIED, again.sample.value.result)

        // Running again invalidates the old marker.
        rule.onNodeWithTag("sample_run").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(!File(exe.parentFile, "pocketos_sample_ok.txt").exists())
        assertEquals(SampleResult.NOT_RUN_YET, app.runtime.checkSample())
    }

    @Test fun staleMarkerIsNotAcceptedAsProof() {
        rule.runOnIdle { app.runtime.stageSample() }
        File(app.runtime.programsDir, "PocketOS Sample/pocketos_sample_ok.txt").writeText("POCKETOS_FIXTURE_OK 0123456789abcdef\r\n")
        assertEquals(SampleResult.STALE_OR_FOREIGN, app.runtime.checkSample())
    }

    @Test fun windowsDesktopOpensInAPocketOsWindowAndThereAreNoRuntimeScreens() {
        ready(); openRuntimeTab()
        rule.onNodeWithTag("runtime_desktop").performClick()
        rule.waitForIdle()
        assertEquals(null, shadowOf(app).nextStartedActivity)
        val launch = fake.lastLaunch!!
        assertEquals(null, launch.unixPath); assertEquals(null, launch.shortcutPath)
        assertEquals("Windows desktop", app.shell.desktop.value.focused?.title)
        // The runtime's own settings screens are gone.
        assertEquals(0, rule.onAllNodesWithTag("runtime_advanced").fetchSemanticsNodes().size)
        rule.runOnIdle { app.runtime.stopSession() }
    }

    @Test fun logExplainerTranslatesRealWineOutput() {
        openRuntimeTab()
        val log = "012c:err:module:import_dll Library pocketos_missing.dll (which is needed by L\"Z:\\\\tmp\\\\wtest\\\\needs_missing_dll.exe\") not found"
        rule.onNodeWithTag("log_input").performScrollTo().performTextInput(log)
        rule.onNodeWithTag("log_error").performScrollTo().assertIsDisplayed().assertTextContains("pocketos_missing.dll", substring = true)
    }
}
