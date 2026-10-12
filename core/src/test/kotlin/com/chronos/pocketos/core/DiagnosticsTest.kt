package com.chronos.pocketos.core

import com.chronos.pocketos.core.capability.Capability
import com.chronos.pocketos.core.capability.DeviceCapabilities
import com.chronos.pocketos.core.capability.PerformanceProfile
import com.chronos.pocketos.core.capability.ProfileDecision
import com.chronos.pocketos.core.diagnostics.CompatibilityReport
import com.chronos.pocketos.core.diagnostics.CrashLoopGuard
import com.chronos.pocketos.core.diagnostics.EventLog
import com.chronos.pocketos.core.diagnostics.LogSanitizer
import com.chronos.pocketos.core.library.AppStatus
import com.chronos.pocketos.core.library.LibraryEntry
import com.chronos.pocketos.core.pe.FileKind
import com.chronos.pocketos.core.pe.InstallerType
import com.chronos.pocketos.core.pe.Machine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test fun sanitizerRemovesPersonalData() {
        val raw = """
            user jane.doe@example.com opened content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fsecret%2Fsetup.exe
            path /storage/emulated/0/Download/PocketOS/x.exe and /data/user/0/com.chronos.pocketos/files/home
            wine: C:\users\jane\AppData\Local and /home/jane/.wine
            Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789ABCDEF
            api_key=sk_live_123 token: xyz
            sha256 3057c0318312… kept
            key AAAAB3NzaC1yc2EAAAADAQABAAABAQC7v1b2mX0q9KfT4s
        """.trimIndent()
        val c = LogSanitizer.clean(raw)
        listOf("jane", "example.com", "secret%2F", "/storage/emulated/0", "/data/user/0", "sk_live_123", "xyz", "abcdefghijklmnopqrstuvwxyz0123456789", "AAAAB3NzaC1yc2E").forEach {
            assertFalse("leaked '$it' in:\n$c", c.contains(it))
        }
        assertTrue(c.contains("<email>")); assertTrue(c.contains("<storage>/Download/PocketOS/x.exe")); assertTrue(c.contains("<app-data>/files/home"))
        assertTrue(c.contains("C:\\users\\<user>")); assertTrue(c.contains("3057c0318312"))
        assertTrue(c.contains("setup.exe"))
    }

    @Test fun eventLogIsBoundedSanitizedAndRoundTrips() {
        var t = 0L
        val log = EventLog(capacity = 3) { t++ }
        repeat(5) { log.add("display", "event $it by a@b.co") }
        val s = log.snapshot()
        assertEquals(3, s.size); assertEquals("event 2 by <email>", s.first().text)
        val enc = s.map(EventLog::encode)
        assertEquals(s, enc.mapNotNull(EventLog::decode))
        assertEquals(null, EventLog.decode("garbage"))
    }

    @Test fun crashLoopGuard() {
        val now = 10_000_000L
        assertFalse(CrashLoopGuard.shouldStartSafe(emptyList(), now))
        assertFalse(CrashLoopGuard.shouldStartSafe(listOf(now - 1000), now))
        assertTrue(CrashLoopGuard.shouldStartSafe(listOf(now - 5000, now - 1000), now))
        assertFalse("old crashes expire", CrashLoopGuard.shouldStartSafe(listOf(now - 9_000_000, now - 8_000_000), now))
        assertEquals(listOf(now - 1), CrashLoopGuard.prune(listOf(now - 9_000_000, now - 1), now))
    }

    @Test fun reportHasNeededFactsAndNoPrivateData() {
        val gb = 1L shl 30
        val caps = DeviceCapabilities("Acme", "P1", "14", 34, listOf("arm64-v8a"), Capability.Known(8), Capability.Known(8 * gb - 100), Capability.Unknown(""),
            Capability.Known(256), false, false, Capability.Known(1), Capability.Known(false), Capability.Known(0), null, null, false, Capability.Known("1.3"), Capability.Known("3.2"))
        val e = LibraryEntry("1", "Notes for jane@example.com", "setup.exe", "ab".repeat(32), 10, FileKind.WINDOWS_PROGRAM, Machine.X64, InstallerType.NSIS,
            AppStatus.FAILED, "", stagedPath = "Apps/x/setup.exe", createdAt = 0, updatedAt = 0)
        val log = "0024:err:module:import_dll Library MSVCP140.dll (which is needed by L\"C:\\\\users\\\\jane\\\\setup.exe\") not found"
        val r = CompatibilityReport.build("0.9.0", caps, ProfileDecision(PerformanceProfile.BALANCED, true, listOf("x")), "Winlator", "11.2", e, log)
        listOf("model: Acme P1", "API 34", "memory: 8 GB", "version: 11.2", "status: Failed", "MSVCP140.dll", "Balanced (auto)", "not guaranteed").forEach {
            assertTrue("missing '$it' in\n$r", r.contains(it))
        }
        assertFalse(r.contains("jane")); assertFalse(r.contains("example.com"))
    }
}
