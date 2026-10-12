package com.chronos.pocketos.core

import com.chronos.pocketos.core.runtime.InstalledProgramDiscovery
import com.chronos.pocketos.core.runtime.InstalledShortcut
import com.chronos.pocketos.core.runtime.RuntimeCatalog
import com.chronos.pocketos.core.runtime.RuntimeLogTranslator
import com.chronos.pocketos.core.runtime.RuntimePaths
import com.chronos.pocketos.core.runtime.RuntimeState
import com.chronos.pocketos.core.runtime.RuntimeStateRules
import com.chronos.pocketos.core.runtime.SampleResult
import com.chronos.pocketos.core.runtime.SampleVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTest {
    private val w = RuntimeCatalog.BUILTIN

    @Test fun stateRespectsAbiAndInstallation() {
        assertTrue(RuntimeStateRules.initial(w, listOf("x86_64"), true, true) is RuntimeState.Unsupported)
        assertEquals(RuntimeState.NotInstalled, RuntimeStateRules.initial(w, listOf("arm64-v8a"), false, false))
        // System files without the Windows environment still need setup.
        assertEquals(RuntimeState.NotInstalled, RuntimeStateRules.initial(w, listOf("arm64-v8a"), true, false))
        assertEquals(RuntimeState.Ready, RuntimeStateRules.initial(w, listOf("arm64-v8a", "armeabi-v7a"), true, true))
    }

    @Test fun programsFolderMapsToDDrive() {
        assertEquals("D:\\PocketOS Sample\\pocketos-sample.exe", RuntimePaths.windowsPath(w.programsDrive, "PocketOS Sample/pocketos-sample.exe"))
        assertEquals("D:\\Apps\\Notes\\setup.exe", RuntimePaths.windowsPath('D', "/Apps/Notes/setup.exe"))
        assertEquals('E', w.homeDrive)
    }

    @Test fun installedProgramDiscoveryIgnoresKnownShortcutsAndNoise() {
        val before = setOf("/c/Desktop/Old.desktop")
        val after = listOf(
            InstalledShortcut("Old", "/c/Desktop/Old.desktop"),
            InstalledShortcut("Sample Notes", "/c/Start Menu/Programs/Sample Notes/Sample Notes.lnk"),
            InstalledShortcut("Sample Notes", "/c/Desktop/Sample Notes.desktop", "/icons/notes.png"),
            InstalledShortcut("Uninstall Sample Notes", "/c/Start Menu/Programs/Sample Notes/Uninstall Sample Notes.lnk"),
            InstalledShortcut("Sample Notes Help", "/c/Start Menu/Programs/Sample Notes/Sample Notes Help.lnk"),
            InstalledShortcut("Visit our Website", "/c/Start Menu/Programs/Sample Notes/Visit our Website.lnk"),
            InstalledShortcut("Paint Tool", "/c/Desktop/Paint Tool.desktop"),
        )
        val found = InstalledProgramDiscovery.newPrograms(before, after)
        assertEquals(listOf("Paint Tool", "Sample Notes"), found.map { it.name })
        // The .desktop entry (with icon) wins over the raw .lnk.
        assertEquals("/icons/notes.png", found.single { it.name == "Sample Notes" }.iconPath)
    }

    @Test fun installersWithoutShortcutsFallBackToNewProgramFiles() {
        val pf = "/c/.wine/drive_c/Program Files"
        val before = setOf("$pf/Old/old.exe")
        val after = listOf(
            InstalledShortcut("old", "$pf/Old/old.exe"),
            InstalledShortcut("hello_console_x64", "$pf/Sample Notes/hello_console_x64.exe"),
            InstalledShortcut("uninstall", "$pf/Sample Notes/uninstall.exe"),
            InstalledShortcut("unins000", "$pf/Game/unins000.exe"),
            InstalledShortcut("vc_redist.x64", "$pf/Game/redist/vc_redist.x64.exe"),
        )
        assertEquals(listOf("hello_console_x64"), InstalledProgramDiscovery.newPrograms(before, after).map { it.name })
        // As soon as an installer made a shortcut, plain files are not offered.
        val withShortcut = after + InstalledShortcut("Sample Notes", "/c/Desktop/Sample Notes.desktop")
        assertEquals(listOf("Sample Notes"), InstalledProgramDiscovery.newPrograms(before, withShortcut).map { it.name })
        // Large installs are capped.
        val many = (1..9).map { InstalledShortcut("tool$it", "$pf/Suite/tool$it.exe") }
        assertEquals(InstalledProgramDiscovery.MAX_EXECUTABLES, InstalledProgramDiscovery.newPrograms(emptySet(), many).size)
    }

    @Test fun sampleVerificationNeedsExactNonce() {
        val n = SampleVerification.newNonce(java.util.Random(1))
        assertEquals(16, n.length)
        assertNotEquals(n, SampleVerification.newNonce(java.util.Random(2)))
        assertEquals(SampleResult.NOT_RUN_YET, SampleVerification.check(null, n))
        assertEquals(SampleResult.VERIFIED, SampleVerification.check("POCKETOS_FIXTURE_OK $n\r\n", n))
        assertEquals(SampleResult.STALE_OR_FOREIGN, SampleVerification.check("POCKETOS_FIXTURE_OK 0000\r\n", n))
        assertEquals(SampleResult.STALE_OR_FOREIGN, SampleVerification.check("hello", n))
    }

    @Test fun translatesKnownWineErrorsAndIgnoresNoise() {
        val log = """
            0024:err:module:import_dll Library MSVCP140.dll (which is needed by L"C:\\app\\tool.exe") not found
            0024:err:module:loader_init Importing dlls for L"C:\\app\\tool.exe" failed, status c0000135
            wine: Bad EXE format for Z:\home\user\notes.txt.
            0030:fixme:ntdll:NtQuerySystemInformation info_class SYSTEM_PERFORMANCE_INFORMATION
        """.trimIndent()
        val errs = RuntimeLogTranslator.translate(log)
        assertEquals(2, errs.size)
        assertTrue(errs[0].title.contains("MSVCP140.dll"))
        assertTrue(errs[1].title.contains("not a program"))
        assertTrue(RuntimeLogTranslator.translate("0030:fixme:ntdll:something harmless").isEmpty())
    }

    private fun res(name: String) = javaClass.getResource("/runtime-logs/$name")!!.readText()

    /** Logs captured from real Wine 10.0 runs on the build host (see tools/pe-fixtures/README.md). */
    @Test fun translatesRealCapturedWineLogs() {
        val missing = RuntimeLogTranslator.translate(res("wine10_missing_dll.log"))
        assertEquals(1, missing.size)
        assertTrue(missing[0].title.contains("pocketos_missing.dll"))
        val bad = RuntimeLogTranslator.translate(res("wine10_not_a_program.log"))
        assertEquals(1, bad.size)
        assertTrue(bad[0].title.contains("not a program"))
    }

    @Test fun realSampleMarkerVerifies() {
        assertEquals(SampleResult.VERIFIED, SampleVerification.check(res("wine10_sample_marker.txt"), "deadbeefcafe0001"))
        assertEquals(SampleResult.STALE_OR_FOREIGN, SampleVerification.check(res("wine10_sample_marker.txt"), "0000000000000000"))
    }
}
