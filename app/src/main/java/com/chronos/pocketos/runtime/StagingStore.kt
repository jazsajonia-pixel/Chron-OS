package com.chronos.pocketos.runtime

import java.io.File
import java.io.InputStream

/** A folder where PocketOS keeps copies of imported programs. Paths are '/'-separated and relative. */
interface StagingStore {
    /** Stable identifier of the store (for logs). */
    val documentId: String
    val label: String
    fun write(relative: String, input: InputStream): Long
    fun writeText(relative: String, text: String) = write(relative, text.byteInputStream())
    fun readText(relative: String, maxBytes: Int = 64 * 1024): String?
    fun exists(relative: String): Boolean
    fun delete(relative: String): Boolean
    /** Total bytes used under [relative] (a folder or file). */
    fun sizeOf(relative: String): Long
    /** Names directly inside folder [relative] (empty if missing). */
    fun list(relative: String): List<String>
}

/** Plain-file store; PocketOS keeps imported programs in its private programs folder (drive D: of the runtime). */
class FileStagingStore(private val root: File, override val documentId: String, override val label: String = root.name) : StagingStore {
    private fun f(relative: String): File {
        val target = File(root, relative).canonicalFile
        require(target.path.startsWith(root.canonicalPath)) { "Path escapes the staging folder" }
        return target
    }
    override fun write(relative: String, input: InputStream): Long {
        val t = f(relative); t.parentFile?.mkdirs()
        return t.outputStream().use { out -> input.use { it.copyTo(out) } }
    }
    override fun readText(relative: String, maxBytes: Int): String? = f(relative).takeIf { it.isFile }?.inputStream()?.use { String(it.readNBytesCompat(maxBytes)) }
    override fun exists(relative: String) = f(relative).exists()
    override fun delete(relative: String) = f(relative).deleteRecursively()
    override fun sizeOf(relative: String) = f(relative).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    override fun list(relative: String): List<String> = f(relative).list()?.toList().orEmpty()
}

internal fun InputStream.readNBytesCompat(max: Int): ByteArray {
    val buf = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0
    while (total < max) {
        val n = read(chunk, 0, minOf(chunk.size, max - total))
        if (n < 0) break
        buf.write(chunk, 0, n); total += n
    }
    return buf.toByteArray()
}
