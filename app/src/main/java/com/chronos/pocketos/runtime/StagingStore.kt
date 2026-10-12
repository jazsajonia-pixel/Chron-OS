package com.chronos.pocketos.runtime

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

/** A folder shared with the runtime app, where PocketOS copies programs. Paths are '/'-separated and relative. */
interface StagingStore {
    /** ExternalStorageProvider-style id, e.g. "primary:Download/PocketOS", used to work out the runtime's drive path. */
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

/** Storage Access Framework implementation; works without storage permissions on every supported Android version. */
class SafStagingStore(private val context: Context, val treeUri: Uri) : StagingStore {
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri) ?: error("Folder is not accessible")
    override val documentId: String = DocumentsContract.getTreeDocumentId(treeUri)
    override val label: String = root.name ?: documentId

    private fun find(relative: String): DocumentFile? =
        relative.split('/').filter { it.isNotEmpty() }.fold(root as DocumentFile?) { d, part -> d?.findFile(part) }

    private fun dirFor(parts: List<String>): DocumentFile = parts.fold(root) { d, part ->
        d.findFile(part)?.takeIf { it.isDirectory } ?: d.createDirectory(part) ?: error("Could not create folder $part")
    }

    override fun write(relative: String, input: InputStream): Long {
        val parts = relative.split('/').filter { it.isNotEmpty() }
        val dir = dirFor(parts.dropLast(1))
        val name = parts.last()
        dir.findFile(name)?.delete()
        val file = dir.createFile("application/octet-stream", name) ?: error("Could not create $name")
        // Some providers append an extension; rename back to the exact name when possible.
        if (file.name != name) file.renameTo(name)
        return context.contentResolver.openOutputStream(file.uri, "wt")!!.use { out -> input.use { it.copyTo(out) } }
    }

    override fun readText(relative: String, maxBytes: Int): String? {
        val f = find(relative)?.takeIf { it.isFile } ?: return null
        return context.contentResolver.openInputStream(f.uri)?.use { s -> String(s.readNBytesCompat(maxBytes)) }
    }

    override fun exists(relative: String) = find(relative) != null
    override fun delete(relative: String) = find(relative)?.delete() ?: false
    override fun sizeOf(relative: String): Long {
        fun walk(d: DocumentFile): Long = if (d.isDirectory) d.listFiles().sumOf(::walk) else d.length()
        return find(relative)?.let(::walk) ?: 0L
    }
    override fun list(relative: String): List<String> =
        (if (relative.isEmpty()) root else find(relative))?.takeIf { it.isDirectory }?.listFiles()?.mapNotNull { it.name }.orEmpty()
}

/** Plain-file implementation, used by tests and for the app-private fallback. */
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
