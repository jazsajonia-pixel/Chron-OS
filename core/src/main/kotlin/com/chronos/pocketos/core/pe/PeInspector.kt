package com.chronos.pocketos.core.pe

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Random-access, read-only view of an untrusted file. */
interface ByteSource {
    val size: Long
    /** Reads up to [len] bytes at [offset]; returns fewer at end of file. */
    fun read(offset: Long, len: Int): ByteArray
}

class FileByteSource(private val file: File) : ByteSource, AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()
    override fun read(offset: Long, len: Int): ByteArray {
        if (offset < 0 || offset >= size || len <= 0) return ByteArray(0)
        val n = minOf(len.toLong(), size - offset).toInt()
        val b = ByteArray(n)
        raf.seek(offset); raf.readFully(b)
        return b
    }
    override fun close() = raf.close()
}

class ArrayByteSource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, len: Int): ByteArray {
        if (offset < 0 || offset >= size || len <= 0) return ByteArray(0)
        return bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + len).toInt())
    }
}

enum class FileKind { WINDOWS_PROGRAM, WINDOWS_LIBRARY, MSI_PACKAGE, DOS_PROGRAM, WIN16_PROGRAM, ARCHIVE, SCRIPT, NOT_WINDOWS, DAMAGED }
enum class Machine(val label: String) { X86("32-bit x86"), X64("64-bit x86 (x64)"), ARM64("64-bit ARM"), ARM("32-bit ARM"), OTHER("other"), UNKNOWN("unknown") }
enum class Subsystem(val label: String) { GUI("windowed app"), CONSOLE("command-line app"), OTHER("system component"), UNKNOWN("unknown") }
enum class InstallerType(val label: String) { NSIS("NSIS installer"), INNO("Inno Setup installer"), INSTALLSHIELD("InstallShield installer"), WIX_BURN("WiX bundle installer"), MSI("Windows Installer package"), SELF_EXTRACTING("self-extracting archive"), NONE("none detected") }

data class VersionInfo(
    val productName: String? = null,
    val companyName: String? = null,
    val fileDescription: String? = null,
    val fileVersion: String? = null,
    val productVersion: String? = null,
)

data class PeReport(
    val kind: FileKind,
    val sizeBytes: Long,
    val machine: Machine = Machine.UNKNOWN,
    val subsystem: Subsystem = Subsystem.UNKNOWN,
    val is64Bit: Boolean = false,
    val isDotNet: Boolean = false,
    val hasSignature: Boolean = false,
    val overlayBytes: Long = 0,
    val installer: InstallerType = InstallerType.NONE,
    val version: VersionInfo = VersionInfo(),
    val notes: List<String> = emptyList(),
) {
    /** Best human name from metadata, else null. */
    val suggestedName: String? get() = (version.productName ?: version.fileDescription)?.trim()?.takeIf { it.isNotEmpty() }
    val isInstaller: Boolean get() = installer != InstallerType.NONE
}

/**
 * Reads metadata from an untrusted file without executing it. All offsets are bounds-checked; malformed input yields
 * [FileKind.DAMAGED] instead of an exception. Results are hints, never a compatibility guarantee.
 */
object PeInspector {
    private const val MAX_RSRC_BYTES = 4 * 1024 * 1024
    private const val SCAN_WINDOW = 256 * 1024

    fun inspect(src: ByteSource, fileName: String = ""): PeReport = runCatching { inspectUnsafe(src, fileName) }
        .getOrElse { PeReport(FileKind.DAMAGED, src.size, notes = listOf("The file could not be read as a Windows program (${it.javaClass.simpleName}).")) }

    private fun u16(b: ByteArray, o: Int): Int = if (o < 0 || o + 2 > b.size) throw IndexOutOfBoundsException() else (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = if (o < 0 || o + 4 > b.size) throw IndexOutOfBoundsException()
        else (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)

    private fun inspectUnsafe(src: ByteSource, fileName: String): PeReport {
        val size = src.size
        val head = src.read(0, 4096)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (head.size >= 8 && head.copyOfRange(0, 8).contentEquals(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))) {
            return if (ext == "msi" || ext == "msp" || ext.isEmpty())
                PeReport(FileKind.MSI_PACKAGE, size, installer = InstallerType.MSI, notes = listOf("Windows Installer package. It is installed with the runtime's installer service (msiexec)."))
            else PeReport(FileKind.NOT_WINDOWS, size, notes = listOf("This is an Office-style compound document, not a program."))
        }
        if (head.size < 2 || head[0] != 'M'.code.toByte() || head[1] != 'Z'.code.toByte()) {
            return when {
                head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4 ->
                    PeReport(FileKind.ARCHIVE, size, notes = listOf("This is a ZIP archive. Extract it with Files first, then import the program inside."))
                head.size >= 4 && head[0].toInt() == 0x7f && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte() ->
                    PeReport(FileKind.NOT_WINDOWS, size, notes = listOf("This is a Linux/Android program, not a Windows program."))
                ext in setOf("bat", "cmd", "ps1", "vbs", "js") ->
                    PeReport(FileKind.SCRIPT, size, notes = listOf("Script files are not supported for import."))
                else -> PeReport(FileKind.NOT_WINDOWS, size, notes = listOf("This file is not a Windows program."))
            }
        }
        if (head.size < 0x40) return PeReport(FileKind.DAMAGED, size, notes = listOf("The file is too short to be a program."))
        val lfanew = u32(head, 0x3C)
        if (lfanew <= 0 || lfanew > size - 4 || lfanew > 16 * 1024 * 1024) return PeReport(FileKind.DOS_PROGRAM, size, notes = listOf("Very old DOS program. Not supported."))
        val hdr = src.read(lfanew, 4096)
        if (hdr.size >= 2 && hdr[0] == 'N'.code.toByte() && hdr[1] == 'E'.code.toByte()) return PeReport(FileKind.WIN16_PROGRAM, size, notes = listOf("16-bit Windows program. Not supported."))
        if (hdr.size < 24 || !(hdr[0] == 'P'.code.toByte() && hdr[1] == 'E'.code.toByte() && hdr[2].toInt() == 0 && hdr[3].toInt() == 0)) {
            return PeReport(FileKind.DOS_PROGRAM, size, notes = listOf("Very old DOS program. Not supported."))
        }
        val coff = 4
        val machine = when (u16(hdr, coff)) {
            0x14c -> Machine.X86; 0x8664 -> Machine.X64; 0xAA64 -> Machine.ARM64; 0x1c0, 0x1c4 -> Machine.ARM; else -> Machine.OTHER
        }
        val nSections = u16(hdr, coff + 2)
        val optSize = u16(hdr, coff + 16)
        val characteristics = u16(hdr, coff + 18)
        val opt = coff + 20
        val magic = u16(hdr, opt)
        val pe32plus = magic == 0x20b
        if (magic != 0x10b && magic != 0x20b) return PeReport(FileKind.DAMAGED, size, machine, notes = listOf("Program header is damaged."))
        val subsystem = when (u16(hdr, opt + 68)) { 2 -> Subsystem.GUI; 3 -> Subsystem.CONSOLE; 0 -> Subsystem.UNKNOWN; else -> Subsystem.OTHER }
        val ddBase = opt + if (pe32plus) 112 else 96
        val nDirs = u32(hdr, opt + if (pe32plus) 108 else 92).toInt().coerceIn(0, 16)
        fun dir(i: Int): Pair<Long, Long> = if (i < nDirs) u32(hdr, ddBase + i * 8) to u32(hdr, ddBase + i * 8 + 4) else 0L to 0L
        val (rsrcRva, rsrcSize) = dir(2)
        val (certOff, certSize) = dir(4)
        val (clrRva, _) = dir(14)

        data class Sec(val name: String, val va: Long, val vsize: Long, val raw: Long, val rawSize: Long)
        val secTable = src.read(lfanew + opt + optSize, nSections.coerceAtMost(96) * 40)
        val sections = (0 until nSections.coerceAtMost(96)).mapNotNull { i ->
            val o = i * 40
            if (o + 40 > secTable.size) null else Sec(
                String(secTable, o, 8, Charsets.ISO_8859_1).trimEnd('\u0000'),
                u32(secTable, o + 12), u32(secTable, o + 8), u32(secTable, o + 20), u32(secTable, o + 16),
            )
        }
        fun rvaToOff(rva: Long): Long? = sections.firstOrNull { rva >= it.va && rva < it.va + maxOf(it.vsize, it.rawSize) }?.let { it.raw + (rva - it.va) }

        val imageEnd = sections.maxOfOrNull { it.raw + it.rawSize } ?: 0L
        val hasCert = certOff > 0 && certSize > 0 && certOff + certSize <= size
        var overlay = (size - imageEnd).coerceAtLeast(0)
        if (hasCert && certOff >= imageEnd) overlay = (certOff - imageEnd).coerceAtLeast(0)

        val version = if (rsrcRva > 0 && rsrcSize > 0) rvaToOff(rsrcRva)?.let { off ->
            val rs = src.read(off, rsrcSize.coerceAtMost(MAX_RSRC_BYTES.toLong()).toInt())
            runCatching { readVersion(rs, rsrcRva) { rva -> rvaToOff(rva) }?.let { (dataOff, len) -> parseVersionInfo(src.read(dataOff, len.coerceAtMost(64 * 1024))) } }.getOrNull()
        } else null

        val installer = detectInstaller(src, sections.map { it.name }, imageEnd, overlay, version)
        val isDll = (characteristics and 0x2000) != 0
        val notes = buildList {
            if (isDll) add("This is a library (DLL) that other programs use. It cannot be started on its own.")
            if (machine == Machine.ARM64 || machine == Machine.ARM) add("Built for ARM Windows. Most compatibility runtimes expect x86/x64 programs; this type is untested.")
            if (clrRva > 0) add("Uses .NET. The runtime needs a compatible .NET or Mono setup.")
            if (hasCert) add("Has a digital signature (PocketOS does not verify it).")
            if (installer != InstallerType.NONE) add("Looks like a ${installer.label}. It must be run inside the runtime to install the program.")
        }
        return PeReport(
            kind = if (isDll) FileKind.WINDOWS_LIBRARY else FileKind.WINDOWS_PROGRAM,
            sizeBytes = size, machine = machine, subsystem = subsystem, is64Bit = pe32plus, isDotNet = clrRva > 0,
            hasSignature = hasCert, overlayBytes = overlay, installer = installer, version = version ?: VersionInfo(), notes = notes,
        )
    }

    private fun containsAscii(hay: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.ISO_8859_1)
        if (n.isEmpty() || hay.size < n.size) return false
        outer@ for (i in 0..hay.size - n.size) { for (j in n.indices) if (hay[i + j] != n[j]) continue@outer; return true }
        return false
    }
    private fun containsUtf16(hay: ByteArray, needle: String) = containsAscii(hay, needle.toCharArray().joinToString("") { "$it\u0000" })

    private fun detectInstaller(src: ByteSource, sectionNames: List<String>, imageEnd: Long, overlay: Long, v: VersionInfo?): InstallerType {
        if (".wixburn" in sectionNames) return InstallerType.WIX_BURN
        val overlayHead = if (overlay > 0) src.read(imageEnd, SCAN_WINDOW) else ByteArray(0)
        val body = src.read(0, SCAN_WINDOW * 2)
        if (containsAscii(overlayHead, "NullsoftInst") || containsAscii(body, "Nullsoft Install System")) return InstallerType.NSIS
        if (containsAscii(overlayHead, "Inno Setup Setup Data") || containsAscii(body, "Inno Setup Setup Data") || containsAscii(body, "JR.Inno.Setup")) return InstallerType.INNO
        if (containsAscii(body, "InstallShield") || containsUtf16(body, "InstallShield")) return InstallerType.INSTALLSHIELD
        val desc = listOfNotNull(v?.fileDescription, v?.productName).joinToString(" ").lowercase()
        if (overlay > 64 * 1024 && (containsAscii(overlayHead, "7z\u00BC\u00AF\u0027\u001C") || overlayHead.take(4).toByteArray().contentEquals(byteArrayOf(0x50, 0x4B, 3, 4)))) return InstallerType.SELF_EXTRACTING
        if (overlay > 0 && ("setup" in desc || "install" in desc)) return InstallerType.SELF_EXTRACTING
        return InstallerType.NONE
    }

    /** Walks the resource tree to RT_VERSION (16); returns (file offset, size) of its data. */
    private fun readVersion(rs: ByteArray, rsrcRva: Long, rvaToOff: (Long) -> Long?): Pair<Long, Int>? {
        fun firstEntry(dirOff: Int, wantId: Int?): Long? {
            val named = u16(rs, dirOff + 12); val ids = u16(rs, dirOff + 14)
            for (i in 0 until (named + ids).coerceAtMost(4096)) {
                val e = dirOff + 16 + i * 8
                val nameField = u32(rs, e)
                val isId = (nameField and 0x80000000L) == 0L
                if (wantId == null || (isId && nameField.toInt() == wantId)) return u32(rs, e + 4)
            }
            return null
        }
        val l1 = firstEntry(0, 16) ?: return null
        if ((l1 and 0x80000000L) == 0L) return null
        val l2 = firstEntry((l1 and 0x7fffffffL).toInt(), null) ?: return null
        if ((l2 and 0x80000000L) == 0L) return null
        val l3 = firstEntry((l2 and 0x7fffffffL).toInt(), null) ?: return null
        if ((l3 and 0x80000000L) != 0L) return null
        val dataRva = u32(rs, l3.toInt()); val dataSize = u32(rs, l3.toInt() + 4).toInt()
        val off = rvaToOff(dataRva) ?: return null
        if (dataSize <= 0) return null
        return off to dataSize
    }

    /** Parses VS_VERSIONINFO for the common StringFileInfo values. */
    internal fun parseVersionInfo(b: ByteArray): VersionInfo {
        val values = HashMap<String, String>()
        fun align(o: Int) = (o + 3) and 3.inv()
        fun readKey(o: Int): Pair<String, Int> {
            val sb = StringBuilder(); var p = o
            while (p + 1 < b.size) { val c = u16(b, p); p += 2; if (c == 0) break; sb.append(c.toChar()); if (sb.length > 64) break }
            return sb.toString() to p
        }
        fun block(o: Int, end: Int, depth: Int) {
            var p = o
            while (p + 6 <= end && depth < 6) {
                val len = u16(b, p); if (len < 6) return
                val valueLen = u16(b, p + 2); val type = u16(b, p + 4)
                val blockEnd = minOf(p + len, end)
                val (key, afterKey) = readKey(p + 6)
                var valueStart = align(afterKey)
                when {
                    depth == 0 -> { valueStart += valueLen; block(align(valueStart), blockEnd, 1) }
                    key == "StringFileInfo" -> block(valueStart, blockEnd, 2)
                    depth == 2 -> block(valueStart, blockEnd, 3) // string table, e.g. "040904B0"
                    depth == 3 && type == 1 && valueLen > 0 -> {
                        val chars = StringBuilder(); var q = valueStart
                        while (q + 1 < blockEnd && chars.length < valueLen) { val c = u16(b, q); q += 2; if (c == 0) break; chars.append(c.toChar()) }
                        values[key] = chars.toString().trim()
                    }
                }
                p = align(p + len)
            }
        }
        block(0, b.size, 0)
        fun v(k: String) = values[k]?.takeIf { it.isNotBlank() }?.take(120)
        return VersionInfo(v("ProductName"), v("CompanyName"), v("FileDescription"), v("FileVersion"), v("ProductVersion"))
    }

    fun sha256(src: ByteSource): String {
        val md = MessageDigest.getInstance("SHA-256")
        var off = 0L
        while (off < src.size) { val chunk = src.read(off, 1 shl 20); if (chunk.isEmpty()) break; md.update(chunk); off += chunk.size }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
