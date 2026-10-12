package com.chronos.pocketos.core

import com.chronos.pocketos.core.runtime.RuntimeAvailability
import com.chronos.pocketos.core.runtime.RuntimeAvailabilityRules
import com.chronos.pocketos.core.runtime.RuntimeCatalog
import com.chronos.pocketos.core.runtime.RuntimeLogTranslator
import com.chronos.pocketos.core.runtime.RuntimePath
import com.chronos.pocketos.core.runtime.RuntimePaths
import com.chronos.pocketos.core.runtime.SampleResult
import com.chronos.pocketos.core.runtime.SampleVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTest {
    private val w = RuntimeCatalog.WINLATOR

    @Test fun availabilityRespectsAbi() {
        assertTrue(RuntimeAvailabilityRules.evaluate(w, listOf("x86_64"), null) is RuntimeAvailability.DeviceIncompatible)
        assertEquals(RuntimeAvailability.NotInstalled, RuntimeAvailabilityRules.evaluate(w, listOf("arm64-v8a"), null))
        assertEquals(RuntimeAvailability.Installed("11.2", 30), RuntimeAvailabilityRules.evaluate(w, listOf("arm64-v8a", "armeabi-v7a"), "11.2" to 30L))
    }

    @Test fun downloadFolderMapsToDDrive() {
        assertEquals(
            RuntimePath.Mapped("D:\\PocketOS\\PocketOS Sample\\pocketos-sample.exe"),
            RuntimePaths.locate(w, "primary:Download/PocketOS", "PocketOS Sample/pocketos-sample.exe"),
        )
        assertEquals(RuntimePath.Mapped("D:\\Tools\\a.exe"), RuntimePaths.locate(w, "primary:Download", "Tools/a.exe"))
        assertEquals(
            RuntimePath.NeedsDrive("/storage/emulated/0/Documents/Apps/a.exe"),
            RuntimePaths.locate(w, "primary:Documents/Apps", "a.exe"),
        )
        assertEquals(RuntimePath.NeedsDrive("/storage/1234-ABCD/Download/a.exe"), RuntimePaths.locate(w, "1234-ABCD:Download", "a.exe"))
        // "Downloads2" must not be treated as the Download folder.
        assertTrue(RuntimePaths.locate(w, "primary:Downloads2", "a.exe") is RuntimePath.NeedsDrive)
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
