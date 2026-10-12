package com.chronos.pocketos.core

import com.chronos.pocketos.core.capability.PerformanceProfile
import com.chronos.pocketos.core.library.AppStatus
import com.chronos.pocketos.core.library.Compatibility
import com.chronos.pocketos.core.library.GraphicsChoice
import com.chronos.pocketos.core.library.LibraryCodec
import com.chronos.pocketos.core.library.LibraryEntry
import com.chronos.pocketos.core.library.LibraryState
import com.chronos.pocketos.core.library.Recovery
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.library.SetupEnvironment
import com.chronos.pocketos.core.library.UserResult
import com.chronos.pocketos.core.library.safeFolderName
import com.chronos.pocketos.core.pe.ArrayByteSource
import com.chronos.pocketos.core.pe.FileKind
import com.chronos.pocketos.core.pe.InstallerType
import com.chronos.pocketos.core.pe.Machine
import com.chronos.pocketos.core.pe.PeInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private fun entry(kind: FileKind = FileKind.WINDOWS_PROGRAM, staged: String? = "Apps/X/x.exe", installer: InstallerType = InstallerType.NONE) = LibraryEntry(
        id = "1", displayName = "X", sourceFileName = "x.exe", sha256 = "00", sizeBytes = 10, kind = kind, machine = Machine.X64,
        installer = installer, status = AppStatus.NEEDS_SETUP, statusDetail = "", stagedPath = staged, createdAt = 0, updatedAt = 0,
    )
    private val allSet = SetupEnvironment(runtimeReady = true, runtimeCompatibleDevice = true)

    @Test fun unsupportedKindsAreLabeledHonestly() {
        listOf(FileKind.WINDOWS_LIBRARY, FileKind.DOS_PROGRAM, FileKind.WIN16_PROGRAM, FileKind.ARCHIVE, FileKind.SCRIPT, FileKind.NOT_WINDOWS, FileKind.DAMAGED).forEach {
            val r = PeInspector.inspect(ArrayByteSource(ByteArray(0)), "x").copy(kind = it)
            assertEquals(AppStatus.UNSUPPORTED, Compatibility.importable(r)!!.status)
        }
        assertNull(Compatibility.importable(PeInspector.inspect(ArrayByteSource(ByteArray(0)), "x").copy(kind = FileKind.WINDOWS_PROGRAM)))
    }

    @Test fun needsSetupListsMissingSteps() {
        val a = Compatibility.assess(entry(staged = null), SetupEnvironment(false, true))
        assertEquals(AppStatus.NEEDS_SETUP, a.status)
        assertEquals(2, a.nextSteps.size)
        assertEquals(AppStatus.NEEDS_SETUP, Compatibility.assess(entry(), SetupEnvironment(true, false)).status)
        // Programs created by an installer have no copied file; their shortcut is enough.
        assertEquals(AppStatus.READY, Compatibility.assess(entry(staged = null).copy(shortcutPath = "/x/Notes.desktop"), allSet).status)
    }

    @Test fun readyNeverClaimsCompatibility() {
        val a = Compatibility.assess(entry(), allSet)
        assertEquals(AppStatus.READY, a.status)
        assertTrue(a.detail.contains("has not been tested"))
        val inst = Compatibility.assess(entry(installer = InstallerType.NSIS), allSet)
        assertTrue(inst.nextSteps.first().contains("Tap Install"))
        assertTrue(Compatibility.assess(entry(), allSet).nextSteps.first().contains("Tap Run"))
        assertTrue(Compatibility.assess(entry(installer = InstallerType.NSIS).copy(installerRan = true), allSet).nextSteps.first().contains("Install again"))
        val worked = Compatibility.assess(Recovery.markWorked(entry(), 5), allSet)
        assertTrue(worked.detail.contains("You reported it works"))
    }

    @Test fun recoveryIsBoundedVisibleAndSavesKnownGood() {
        var e = Recovery.markFailed(entry(), 1)
        assertEquals(AppStatus.FAILED, Compatibility.assess(e, allSet).status)
        assertTrue(Compatibility.assess(e, allSet).nextSteps.first().contains("1 of 3"))
        e = Recovery.applyNext(e, 2)!!
        assertEquals(1024, e.profile.width); assertEquals(768, e.profile.height)
        assertTrue(e.log.last().text.contains("1280×720") && e.log.last().text.contains("1024×768"))
        assertNull(e.userResult)
        e = Recovery.applyNext(Recovery.markFailed(e, 3), 4)!!
        assertEquals(GraphicsChoice.SAFER, e.profile.graphics)
        e = Recovery.applyNext(Recovery.markFailed(e, 5), 6)!!
        assertEquals(800, e.profile.width)
        e = Recovery.markFailed(e, 7)
        assertNull("no endless retries", Recovery.applyNext(e, 8))
        assertTrue(Compatibility.assess(e, allSet).nextSteps.first().contains("may not be compatible"))
        val good = Recovery.markWorked(e, 9)
        assertEquals(good.profile, good.knownGood)
        assertEquals(UserResult.WORKED, good.userResult)
    }

    @Test fun safeDefaultsFollowDeviceProfile() {
        assertEquals(RuntimeProfile(1024, 768), RuntimeProfile.safeDefault(PerformanceProfile.BATTERY_SAVER))
        assertEquals(RuntimeProfile(1280, 720), RuntimeProfile.safeDefault(PerformanceProfile.BALANCED))
        assertEquals(RuntimeProfile(1280, 720), RuntimeProfile.safeDefault(PerformanceProfile.PERFORMANCE))
    }

    @Test fun codecRoundTripAndTolerance() {
        val s = LibraryState(entries = listOf(entry().withLog(1, "hello")))
        assertEquals(s, LibraryCodec.decode(LibraryCodec.encode(s)))
        assertEquals(LibraryState(), LibraryCodec.decode("{broken"))
        assertEquals(LibraryState(), LibraryCodec.decode(null))
        var e = entry(); repeat(150) { e = e.withLog(it.toLong(), "l$it") }
        assertEquals(LibraryEntry.MAX_LOG, e.log.size)
    }

    @Test fun folderNamesAreSafe() {
        assertEquals("a_b_c", safeFolderName("a/b\\c"))
        assertEquals("App", safeFolderName("..."))
        assertEquals("Sample Notes", safeFolderName("Sample Notes"))
    }
}
