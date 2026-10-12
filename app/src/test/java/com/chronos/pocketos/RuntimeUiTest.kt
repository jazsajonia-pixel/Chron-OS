package com.chronos.pocketos

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
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
import com.chronos.pocketos.core.runtime.RuntimeAvailability
import com.chronos.pocketos.core.runtime.SampleResult
import com.chronos.pocketos.core.runtime.SampleVerification
import com.chronos.pocketos.runtime.FileStagingStore
import com.chronos.pocketos.runtime.RuntimeManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
class RuntimeUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = ApplicationProvider.getApplicationContext<PocketOsApp>()

    private fun setAbis(vararg abis: String) {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf(*abis))
        rule.runOnIdle { app.capabilities.recheck("test"); app.runtime.refresh() }
    }

    private fun installWinlator(version: String = "11.2") {
        val spm = shadowOf(app.packageManager)
        spm.installPackage(PackageInfo().apply {
            packageName = "com.winlator"; versionName = version
            applicationInfo = ApplicationInfo().apply { packageName = "com.winlator" }
        })
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage("com.winlator")
        spm.addResolveInfoForIntent(main, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "com.winlator"; name = "com.winlator.MainActivity"; applicationInfo = ApplicationInfo().apply { packageName = "com.winlator" } }
        })
        val info = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO).setPackage("com.winlator")
        spm.addResolveInfoForIntent(info, emptyList())
        rule.runOnIdle { app.runtime.refresh() }
    }

    private fun openPcApps() { rule.runOnIdle { app.shell.openApp("winapps") }; rule.waitForIdle() }

    private fun useTempWorkspace(docId: String = "primary:Download/PocketOS"): File {
        val dir = tmp.newFolder("PocketOS")
        rule.runOnIdle { app.runtime.useStore(FileStagingStore(dir, docId)) }
        return dir
    }

    @Test fun notInstalledOffersDownloadPage() {
        setAbis("arm64-v8a", "armeabi-v7a")
        openPcApps()
        assertEquals(RuntimeAvailability.NotInstalled, app.runtime.availability.value)
        rule.onNodeWithTag("runtime_status").assertTextContains("not installed", substring = true)
        rule.onNodeWithTag("runtime_get").performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://github.com/brunodev85/winlator/releases", started.dataString)
    }

    @Test fun nonArmDeviceIsExplainedHonestly() {
        setAbis("x86_64")
        openPcApps()
        assertTrue(app.runtime.availability.value is RuntimeAvailability.DeviceIncompatible)
        rule.onNodeWithTag("runtime_status").assertTextContains("64-bit ARM", substring = true)
        assertEquals(0, rule.onAllNodesWithTag("runtime_get").fetchSemanticsNodes().size)
    }

    @Test fun installedRuntimeIsDetectedAndOpened() {
        setAbis("arm64-v8a")
        installWinlator()
        openPcApps()
        assertEquals(RuntimeAvailability.Installed("11.2", 0), app.runtime.availability.value)
        rule.onNodeWithTag("runtime_status").assertTextContains("11.2", substring = true)
        rule.onNodeWithTag("runtime_open").performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals("com.winlator", started.component?.packageName ?: started.`package`)
    }

    @Test fun sampleNeedsFolderFirst() {
        openPcApps()
        rule.onNodeWithTag("sample_stage").performScrollTo().assertIsNotEnabled()
    }

    @Test fun stagingCopiesSampleAndNonceButRunsNothing() {
        setAbis("arm64-v8a"); installWinlator()
        val dir = useTempWorkspace()
        openPcApps()
        rule.onNodeWithTag("sample_stage").performScrollTo().performClick()
        rule.waitForIdle()
        assertNull("staging must not start any activity", shadowOf(app).nextStartedActivity)
        val exe = File(dir, "PocketOS Sample/pocketos-sample.exe")
        val bundled = app.assets.open("sample/pocketos-sample.exe").use { it.readBytes() }
        assertArrayEquals(bundled, exe.readBytes())
        assertEquals('M'.code.toByte(), exe.readBytes()[0]); assertEquals('Z'.code.toByte(), exe.readBytes()[1])
        val nonce = File(dir, "PocketOS Sample/pocketos_nonce.txt").readText().trim()
        assertEquals(16, nonce.length)
        rule.onNodeWithTag("sample_path").assertTextContains("D:\\PocketOS\\PocketOS Sample\\pocketos-sample.exe", substring = true)

        rule.onNodeWithTag("sample_check").performClick()
        rule.onNodeWithTag("sample_result").assertTextContains("Not run yet", substring = true)

        // Simulate the runtime executing the sample: write exactly what the real program wrote under Wine 10 on the host
        // (see tools/pe-fixtures/README.md). This tests PocketOS's verification, not the runtime.
        File(dir, "PocketOS Sample/pocketos_sample_ok.txt").writeText("POCKETOS_FIXTURE_OK $nonce\r\n")
        rule.onNodeWithTag("sample_check").performClick()
        rule.onNodeWithTag("sample_result").assertTextContains("Verified", substring = true)
        assertEquals(SampleResult.VERIFIED, app.runtime.sample.value.result)
        // Persisted across restarts.
        val again = RuntimeManager(app) { listOf("arm64-v8a") }
        assertEquals(SampleResult.VERIFIED, again.sample.value.result)

        // Re-staging invalidates the old result and the old marker.
        rule.onNodeWithTag("sample_stage").performClick()
        rule.waitForIdle()
        assertTrue(!File(dir, "PocketOS Sample/pocketos_sample_ok.txt").exists())
        assertEquals(SampleResult.NOT_RUN_YET, app.runtime.checkSample())
    }

    @Test fun staleMarkerIsNotAcceptedAsProof() {
        val dir = useTempWorkspace()
        rule.runOnIdle { app.runtime.stageSample() }
        File(dir, "PocketOS Sample/pocketos_sample_ok.txt").writeText("POCKETOS_FIXTURE_OK 0123456789abcdef\r\n")
        assertEquals(SampleResult.STALE_OR_FOREIGN, app.runtime.checkSample())
    }

    @Test fun folderOutsideDownloadExplainsDriveSetup() {
        useTempWorkspace("primary:Documents/Apps")
        val p = rule.runOnIdle { app.runtime.stageSample() }
        assertTrue(p.toString().contains("/storage/emulated/0/Documents/Apps/${SampleVerification.SAMPLE_FOLDER}"))
    }

    @Test fun logExplainerTranslatesRealWineOutput() {
        openPcApps()
        val log = "012c:err:module:import_dll Library pocketos_missing.dll (which is needed by L\"Z:\\\\tmp\\\\wtest\\\\needs_missing_dll.exe\") not found"
        rule.onNodeWithTag("log_input").performScrollTo().performTextInput(log)
        rule.onNodeWithTag("log_error").performScrollTo().assertIsDisplayed().assertTextContains("pocketos_missing.dll", substring = true)
    }
}
