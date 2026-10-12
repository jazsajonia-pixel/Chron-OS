package com.chronos.pocketos.core

import com.chronos.pocketos.core.pe.ArrayByteSource
import com.chronos.pocketos.core.pe.FileKind
import com.chronos.pocketos.core.pe.InstallerType
import com.chronos.pocketos.core.pe.Machine
import com.chronos.pocketos.core.pe.PeInspector
import com.chronos.pocketos.core.pe.Subsystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses real binaries from tools/pe-fixtures (MinGW, makensis, wixl). */
class PeInspectorTest {
    private fun bytes(name: String) = javaClass.getResource("/pe/$name")!!.readBytes()
    private fun inspect(name: String) = PeInspector.inspect(ArrayByteSource(bytes(name)), name)

    @Test fun x64ConsoleWithVersionResource() {
        val r = inspect("hello_console_x64.exe")
        assertEquals(FileKind.WINDOWS_PROGRAM, r.kind)
        assertEquals(Machine.X64, r.machine)
        assertEquals(Subsystem.CONSOLE, r.subsystem)
        assertTrue(r.is64Bit)
        assertEquals(InstallerType.NONE, r.installer)
        assertEquals("Sample Notes", r.version.productName)
        assertEquals("PocketOS Test Fixtures", r.version.companyName)
        assertEquals("1.2.3.4", r.version.fileVersion)
        assertEquals("Sample Notes", r.suggestedName)
    }

    @Test fun x86ConsoleAndGui() {
        val a = inspect("hello_console_x86.exe")
        assertEquals(Machine.X86, a.machine); assertFalse(a.is64Bit); assertEquals(Subsystem.CONSOLE, a.subsystem)
        assertEquals(null, a.version.productName)
        val g = inspect("hello_gui_x64.exe")
        assertEquals(Subsystem.GUI, g.subsystem); assertEquals(FileKind.WINDOWS_PROGRAM, g.kind)
    }

    @Test fun dllIsNotAProgram() {
        val r = inspect("hello_x64.dll")
        assertEquals(FileKind.WINDOWS_LIBRARY, r.kind)
        assertTrue(r.notes.any { it.contains("library") })
    }

    @Test fun realNsisInstallerDetected() {
        val r = inspect("sample_setup_nsis.exe")
        assertEquals(FileKind.WINDOWS_PROGRAM, r.kind)
        assertEquals(InstallerType.NSIS, r.installer)
        assertTrue(r.overlayBytes > 1000)
        assertEquals(Machine.X86, r.machine) // NSIS stubs are 32-bit
    }

    @Test fun realMsiDetected() {
        val r = inspect("sample_setup.msi")
        assertEquals(FileKind.MSI_PACKAGE, r.kind)
        assertEquals(InstallerType.MSI, r.installer)
    }

    @Test fun nonWindowsInputs() {
        assertEquals(FileKind.SCRIPT, inspect("script.bat").kind)
        assertEquals(FileKind.ARCHIVE, inspect("archive.zip").kind)
        assertEquals(FileKind.NOT_WINDOWS, inspect("linux.bin").kind)
        assertEquals(FileKind.NOT_WINDOWS, PeInspector.inspect(ArrayByteSource("hello".toByteArray()), "notes.exe").kind)
    }

    @Test fun dosAndWin16() {
        val dos = ByteArray(128).also { it[0] = 'M'.code.toByte(); it[1] = 'Z'.code.toByte() }
        assertEquals(FileKind.DOS_PROGRAM, PeInspector.inspect(ArrayByteSource(dos), "old.exe").kind)
        val ne = ByteArray(256).also { it[0] = 'M'.code.toByte(); it[1] = 'Z'.code.toByte(); it[0x3C] = 0x80.toByte(); it[0x80] = 'N'.code.toByte(); it[0x81] = 'E'.code.toByte() }
        assertEquals(FileKind.WIN16_PROGRAM, PeInspector.inspect(ArrayByteSource(ne), "w16.exe").kind)
    }

    @Test fun truncatedAndCorruptedNeverThrow() {
        val good = bytes("hello_console_x64.exe")
        for (n in listOf(2, 63, 64, 200, 400, 1024, 2048)) {
            val k = PeInspector.inspect(ArrayByteSource(good.copyOf(n)), "t.exe").kind
            assertTrue("len $n -> $k", k in setOf(FileKind.DAMAGED, FileKind.DOS_PROGRAM, FileKind.WINDOWS_PROGRAM))
        }
        val rnd = java.util.Random(42)
        repeat(300) {
            val b = good.copyOf()
            repeat(20) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            PeInspector.inspect(ArrayByteSource(b), "fuzz.exe") // must not throw
        }
    }

    @Test fun sha256OfFixture() {
        val h = PeInspector.sha256(ArrayByteSource("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h)
    }
}
