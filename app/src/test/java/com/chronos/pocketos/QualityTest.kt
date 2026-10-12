package com.chronos.pocketos

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronos.pocketos.core.library.AppStatus
import com.chronos.pocketos.core.library.LibraryEntry
import com.chronos.pocketos.core.pe.FileKind
import com.chronos.pocketos.core.pe.InstallerType
import com.chronos.pocketos.core.pe.Machine
import com.chronos.pocketos.diag.DiagnosticsCenter
import com.chronos.pocketos.diag.StorageManager
import com.chronos.pocketos.runtime.FileStagingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h900dp-land-mdpi")
class QualityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = ApplicationProvider.getApplicationContext<PocketOsApp>()

    private fun entry(id: String, folder: String) = LibraryEntry(
        id, "Notes", "setup.exe", "ab".repeat(32), 100, FileKind.WINDOWS_PROGRAM, Machine.X64, InstallerType.NSIS,
        AppStatus.READY, "", stagedPath = "Apps/$folder/setup.exe", createdAt = 0, updatedAt = 0,
    )

    @Test fun diagnosticsShowsSanitizedEvents() {
        rule.runOnIdle { app.diagnostics.add("test", "opened /storage/emulated/0/Download/x.exe for jane@example.com") }
        rule.runOnIdle { app.shell.openApp("diagnostics") }
        rule.onNodeWithText("<storage>/Download/x.exe for <email>", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("jane@example.com", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun reportPreviewShareAndSave() {
        rule.runOnIdle { app.library.upsert(entry("e1", "Notes")) }
        rule.runOnIdle { app.shell.openApp("diagnostics") }
        rule.onNodeWithTag("diag_report_entry").performScrollTo().performClick()
        rule.onNodeWithTag("diag_preview").performScrollTo().performClick()
        rule.onNodeWithTag("diag_report_text").assertTextContains("[app]", substring = true).assertTextContains("status: Ready", substring = true)
            .assertTextContains("not guaranteed", substring = true)
        rule.onNodeWithTag("diag_share").performClick()
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertTrue(uri.toString().startsWith("content://com.chronos.pocketos.files/"))
        assertTrue(File(app.cacheDir, "reports").listFiles()!!.single().readText().contains("PocketOS compatibility report"))
        rule.onNodeWithTag("diag_preview").performScrollTo().performClick()
        rule.onNodeWithTag("diag_save").performClick()
        assertTrue(File(app.filesDir, "home/Documents").listFiles()!!.any { it.name.startsWith("pocketos-report-") })
    }

    @Test fun storageCleanupRemovesOnlyPocketOsTemporaryAndOrphanFiles() {
        val shared = tmp.newFolder("shared")
        File(shared, "Apps/Notes").mkdirs(); File(shared, "Apps/Notes/setup.exe").writeBytes(ByteArray(100))
        File(shared, "Apps/Old").mkdirs(); File(shared, "Apps/Old/old.exe").writeBytes(ByteArray(50))
        File(shared, "UserStuff.txt").writeText("keep me")
        File(app.cacheDir, "imports").mkdirs(); File(app.cacheDir, "imports/x.bin").writeBytes(ByteArray(1234))
        rule.runOnIdle { app.runtime.useStore(FileStagingStore(shared, "primary:Download/PocketOS")); app.library.upsert(entry("e1", "Notes")) }
        val sm = StorageManager(app)
        val u = sm.usage()
        assertEquals(listOf("Old"), u.orphanFolders)
        assertEquals(150L, u.sharedFolderBytes)
        assertEquals(1234L, u.tempImportBytes)
        assertEquals(1234L, sm.clearTemporary())
        assertEquals(1, sm.deleteOrphans())
        assertFalse(File(shared, "Apps/Old").exists())
        assertTrue(File(shared, "Apps/Notes/setup.exe").exists())
        assertTrue(File(shared, "UserStuff.txt").exists())
    }

    @Test fun crashRecordsAreSanitizedAndRepeatedEarlyCrashesTriggerSafeStart() {
        val d = DiagnosticsCenter(app)
        assertFalse(d.safeMode)
        d.recordCrash(IllegalStateException("secret path /storage/emulated/0/private/doc.txt"))
        val after1 = DiagnosticsCenter(app)
        assertNotNull(after1.previousCrash)
        assertTrue(after1.previousCrash!!.contains("IllegalStateException"))
        assertFalse("exception messages are not stored", after1.previousCrash!!.contains("private"))
        assertFalse(after1.safeMode)
        after1.recordCrash(RuntimeException("again"))
        val after2 = DiagnosticsCenter(app)
        assertTrue(after2.safeMode)
        assertNull("crash notice shown once", DiagnosticsCenter(app).previousCrash)
    }

    @Test fun activityRecreationKeepsWindowsAndEditorText() {
        rule.runOnIdle { app.shell.openApp("files"); app.shell.openApp("editor") }
        val before = app.shell.desktop.value.windows.keys
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals(before, app.shell.desktop.value.windows.keys)
        rule.onNodeWithTag("taskbar").assertIsDisplayed()
    }

    @Test fun orientationChangeRefitsWindows() {
        rule.runOnIdle { app.shell.openApp("settings"); app.shell.openApp("sysinfo") }
        RuntimeEnvironment.setQualifiers("w600dp-h1000dp-port-mdpi")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        val area = app.shell.desktop.value.area
        assertTrue("area follows portrait: $area", area.height > area.width)
        app.shell.desktop.value.windows.values.forEach { w ->
            assertTrue("window $w fits $area", w.bounds.x >= area.x && w.bounds.right <= area.right && w.bounds.width <= area.width)
        }
    }

    @Test fun lowMemoryIsHandledVisibly() {
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        shadowOf(am).setMemoryInfo(android.app.ActivityManager.MemoryInfo().apply { totalMem = 6L shl 30; availMem = 100L shl 20; lowMemory = true })
        rule.runOnIdle { app.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) }
        assertTrue(app.capabilities.decision.value.warnings.any { it.contains("low memory") })
        assertTrue(app.diagnostics.log.snapshot().any { it.area == "memory" })
    }
}
