package com.chronos.pocketos

import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronos.pocketos.core.library.AppStatus
import com.chronos.pocketos.core.library.GraphicsChoice
import com.chronos.pocketos.core.pe.InstallerType
import com.chronos.pocketos.library.ImportState
import com.chronos.pocketos.library.LibraryRepository
import com.chronos.pocketos.core.runtime.InstalledShortcut
import com.chronos.pocketos.ui.apps.FileOpenHooks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@Config(qualifiers = "w1280dp-h900dp-land-mdpi")
class LibraryUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = ApplicationProvider.getApplicationContext<TestPocketOsApp>()

    private fun fixture(name: String): File {
        val f = File(tmp.root, name)
        if (!f.exists()) f.writeBytes(javaClass.getResource("/pe/$name")!!.readBytes())
        return f
    }

    /** Built-in runtime set up (fake backend); returns PocketOS's programs folder (drive D:). */
    private fun readyEnvironment(): File {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        app.fakeRuntime.makeReady()
        rule.runOnIdle { app.capabilities.recheck("test"); app.runtime.refresh() }
        rule.waitForIdle()
        return app.runtime.programsDir
    }

    private fun open() { rule.runOnIdle { app.shell.openApp("winapps") }; rule.waitForIdle() }

    /** Background I/O resumes on the main looper, which must be pumped explicitly under Robolectric. */
    private fun awaitState(what: String, pred: (ImportState) -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!pred(app.imports.state.value)) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what; state=${app.imports.state.value}" }
            Thread.sleep(20); org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        rule.waitForIdle()
    }

    private fun importFile(name: String) {
        rule.runOnIdle { app.imports.begin(Uri.fromFile(fixture(name)), name) }
        awaitState("inspection") { it is ImportState.Inspected }
    }

    private fun waitDone() = awaitState("done") { it is ImportState.Done || it is ImportState.Failed }

    @Test fun emptyStateAndHonestIntro() {
        open()
        rule.onNodeWithTag("lib_empty").assertIsDisplayed()
        rule.onNodeWithTag("lib_add").assertIsDisplayed()
    }

    @Test fun importShowsFindingsWithoutRunningAndNeedsSetupWithoutRuntime() {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        rule.runOnIdle { app.capabilities.recheck("test"); app.runtime.refresh() }
        open()
        importFile("hello_console_x64.exe")
        assertNull("nothing may be launched during import", shadowOf(app).nextStartedActivity)
        rule.onNodeWithTag("wiz_findings").assertTextContains("Sample Notes", substring = true)
            .assertTextContains("64-bit x86 (x64)", substring = true).assertTextContains("not run", substring = true)
        rule.onNodeWithTag("wiz_confirm").performScrollTo().performClick()
        waitDone()
        val e = app.library.state.value.entries.single()
        assertEquals("Sample Notes", e.displayName)
        assertEquals(AppStatus.NEEDS_SETUP, e.status)
        // PocketOS always keeps its own copy, so the program can start as soon as the runtime is set up.
        assertArrayEquals(fixture("hello_console_x64.exe").readBytes(), File(app.runtime.programsDir, e.stagedPath!!).readBytes())
        assertTrue(app.imports.assess(e).nextSteps.any { it.contains("Set up the Windows runtime") })
        // Persisted
        assertEquals(1, LibraryRepository(File(app.filesDir, "library.json")).state.value.entries.size)
        // Temp copy removed
        assertTrue(File(app.cacheDir, "imports").listFiles().orEmpty().isEmpty())
    }

    @Test fun installerIsStagedAndReadyButNotClaimedCompatible() {
        val shared = readyEnvironment()
        open()
        importFile("sample_setup_nsis.exe")
        rule.onNodeWithTag("wiz_findings").assertTextContains("NSIS installer", substring = true)
        rule.onNodeWithTag("wiz_confirm").performScrollTo().performClick()
        waitDone()
        assertNull(shadowOf(app).nextStartedActivity)
        val e = app.library.state.value.entries.single()
        assertEquals(InstallerType.NSIS, e.installer)
        assertEquals(AppStatus.READY, e.status)
        assertTrue(e.statusDetail.contains("has not been tested"))
        val staged = File(shared, e.stagedPath!!)
        assertArrayEquals(fixture("sample_setup_nsis.exe").readBytes(), staged.readBytes())
        assertTrue(app.imports.assess(e).nextSteps.first().contains("Install"))
        rule.onNodeWithTag("wiz_done_detail").assertIsDisplayed()
    }

    @Test fun installerRunsInsidePocketOsAndInstalledProgramsAreAdded() {
        readyEnvironment(); open()
        importFile("sample_setup_nsis.exe")
        rule.onNodeWithTag("wiz_confirm").performScrollTo().performClick()
        waitDone()
        val installer = app.library.state.value.entries.single()
        rule.onNodeWithTag("entry_start").performScrollTo().assertTextContains("Install").performClick()
        rule.waitForIdle()
        // The installer runs inside a PocketOS window; no other screen is opened.
        assertNull(shadowOf(app).nextStartedActivity)
        val launch = app.fakeRuntime.lastLaunch!!
        assertEquals(File(app.runtime.programsDir, installer.stagedPath!!).path, launch.unixPath)
        assertEquals(installer.profile, app.fakeRuntime.appliedProfiles.last())
        assertEquals("winprogram", app.shell.desktop.value.focused?.appId)
        assertEquals(installer.displayName, app.shell.desktop.value.focused?.title)

        // The installer creates a program shortcut and an uninstaller shortcut, then exits.
        val root = "/data/data/com.chron.os/files/rootfs/home/xuser-1"
        app.fakeRuntime.shortcutList += InstalledShortcut("Sample Notes", "$root/.wine/drive_c/users/xuser/Desktop/Sample Notes.desktop")
        app.fakeRuntime.shortcutList += InstalledShortcut("Uninstall Sample Notes", "$root/.local/share/applications/wine/Programs/Sample Notes/Uninstall Sample Notes.desktop")
        rule.runOnIdle { app.fakeRuntime.finishSession(0) }
        rule.waitForIdle()
        // When the installer ends its window closes and PocketOS reports what was installed.
        val msg = app.shell.ui.value.message!!
        assertTrue(msg, msg.contains("Installed and added to your library: Sample Notes"))
        assertTrue(app.shell.desktop.value.windows.values.none { it.appId == "winprogram" })
        val entries = app.library.state.value.entries
        assertEquals(2, entries.size)
        val program = entries.single { it.id != installer.id }
        assertEquals(installer.id, program.installedFromId)
        assertEquals(AppStatus.READY, program.status)
        assertTrue(app.library.get(installer.id)!!.installerRan)

        // Starting the installed program uses its shortcut.
        val problem = rule.runOnIdle { app.imports.start(program.id) }
        assertNull(problem)
        assertEquals(program.shortcutPath, app.fakeRuntime.lastLaunch!!.shortcutPath)
        rule.runOnIdle { app.runtime.stopSession() }
        // Nothing new appears when looking again.
        assertTrue(rule.runOnIdle { app.imports.findInstalledPrograms(installer.id) }.isEmpty())
    }

    @Test fun programExitIsRecordedAndCannotStartWithoutRuntime() {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        rule.runOnIdle { app.capabilities.recheck("test"); app.runtime.refresh() }
        open()
        importFile("hello_gui_x64.exe"); rule.onNodeWithTag("wiz_confirm").performScrollTo().performClick(); waitDone()
        val id = app.library.state.value.entries.single().id
        val why = rule.runOnIdle { app.imports.start(id) }
        assertTrue(why!!.contains("Set up the Windows runtime"))
        assertNull(app.fakeRuntime.lastLaunch)
        readyEnvironment()
        assertNull(rule.runOnIdle { app.imports.start(id) })
        assertEquals(app.library.get(id)!!.displayName, app.runtime.active.value?.title)
        rule.runOnIdle { app.fakeRuntime.finishSession(3) }
        rule.waitForIdle()
        assertTrue(app.shell.ui.value.message!!.contains("Did it work?"))
        val e = app.library.get(id)!!
        assertEquals(3, e.lastExitCode)
        assertTrue(e.statusDetail.contains("exit code 3"))
        // Only one result per session.
        assertNull(rule.runOnIdle { app.imports.onReturnFromRuntime() })
    }

    @Test fun dllIsUnsupportedAndCancelLeavesNothing() {
        open()
        importFile("hello_x64.dll")
        rule.onNodeWithTag("wiz_blocked").assertTextContains("library", substring = true)
        rule.onNodeWithTag("wiz_cancel").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(app.library.state.value.entries.isEmpty())
        assertTrue(File(app.cacheDir, "imports").listFiles().orEmpty().isEmpty())
        importFile("hello_x64.dll")
        rule.onNodeWithTag("wiz_keep").performScrollTo().performClick()
        waitDone()
        assertEquals(AppStatus.UNSUPPORTED, app.library.state.value.entries.single().status)
    }

    @Test fun msiAndNonWindowsFiles() {
        readyEnvironment(); open()
        importFile("sample_setup.msi")
        rule.onNodeWithTag("wiz_findings").assertTextContains("Windows Installer package", substring = true)
        rule.runOnIdle { app.imports.cancel() }
        importFile("script.bat")
        rule.onNodeWithTag("wiz_blocked").assertTextContains("Script", substring = true)
    }

    @Test fun duplicateIsDetected() {
        open()
        importFile("hello_console_x64.exe"); rule.onNodeWithTag("wiz_confirm").performScrollTo().performClick(); waitDone()
        rule.runOnIdle { app.imports.dismiss() }
        importFile("hello_console_x64.exe")
        assertEquals("Sample Notes", (app.imports.state.value as ImportState.Inspected).duplicateOf)
        rule.onNodeWithText("already in your library", substring = true).assertIsDisplayed()
    }

    @Test fun failedReportDrivesBoundedRecoveryAndKnownGood() {
        readyEnvironment(); open()
        importFile("hello_gui_x64.exe"); rule.onNodeWithTag("wiz_confirm").performScrollTo().performClick(); waitDone()
        rule.runOnIdle { app.imports.dismiss() }
        val id = app.library.state.value.entries.single().id
        rule.runOnIdle { app.imports.start(id) }
        rule.runOnIdle { app.fakeRuntime.finishSession(0) }
        rule.onNodeWithTag("lib_entry").performClick()
        rule.onNodeWithTag("entry_failed").performScrollTo().performClick()
        assertEquals(AppStatus.FAILED, app.library.get(id)!!.status)
        rule.onNodeWithText("Next fix (1 of 3)", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("entry_next_fix").performScrollTo().performClick()
        var e = app.library.get(id)!!
        assertEquals(1024 to 768, e.profile.width to e.profile.height)
        assertEquals(AppStatus.READY, e.status)
        assertTrue(e.log.any { it.text.startsWith("Recovery 1/3") })
        // The fix is applied automatically on the next start.
        rule.runOnIdle { app.imports.start(id) }
        assertEquals(1024, app.fakeRuntime.appliedProfiles.last().width)
        rule.runOnIdle { app.runtime.stopSession() } // its window would cover the library
        repeat(2) { rule.runOnIdle { app.imports.reportFailed(id); app.imports.applyNextRecovery(id) } }
        e = app.library.get(id)!!
        assertEquals(GraphicsChoice.SAFER, e.profile.graphics); assertEquals(800, e.profile.width)
        rule.runOnIdle { app.imports.reportFailed(id) }
        assertFalse("no more automatic suggestions", rule.runOnIdle { app.imports.applyNextRecovery(id) })
        assertEquals(0, rule.onAllNodesWithTag("entry_next_fix").fetchSemanticsNodes().size)
        rule.onNodeWithTag("entry_worked").performScrollTo().performClick()
        e = app.library.get(id)!!
        assertEquals(e.profile, e.knownGood)
        assertTrue(e.statusDetail.contains("You reported it works"))
    }

    @Test fun removeDeletesCopiedFiles() {
        val shared = readyEnvironment(); open()
        importFile("hello_console_x64.exe"); rule.onNodeWithTag("wiz_confirm").performScrollTo().performClick(); waitDone()
        rule.runOnIdle { app.imports.dismiss() }
        val folder = File(shared, app.library.state.value.entries.single().stagedPath!!).parentFile!!
        assertTrue(folder.exists())
        rule.onNodeWithTag("lib_entry").performClick()
        rule.onNodeWithTag("entry_remove").performScrollTo().performClick()
        rule.onNodeWithTag("remove_delete_files").performClick()
        rule.waitForIdle()
        assertTrue(app.library.state.value.entries.isEmpty())
        assertFalse(folder.exists())
        assertTrue(app.shell.ui.value.message!!.contains("deleted"))
    }

    @Test fun filesAppHookStartsWizardWithoutExecuting() {
        val f = fixture("hello_console_x64.exe")
        rule.runOnIdle { FileOpenHooks.onWindowsExecutable!!.invoke(app, Uri.fromFile(f), f.name) }
        awaitState("inspection") { it is ImportState.Inspected }
        assertEquals("winapps", app.shell.desktop.value.focused?.appId)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun leftoverTempFilesAreCleanedAtStartup() {
        val dir = File(app.cacheDir, "imports").apply { mkdirs() }
        File(dir, "stale.bin").writeBytes(ByteArray(1000))
        assertEquals(1000L, app.imports.cleanupTemp())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
