package com.chronos.pocketos.core.files

import java.io.File
import java.io.IOException

data class FileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * File operations confined to a root directory (PocketOS's app-private "Home").
 * Every path is resolved canonically and rejected if it escapes the root.
 */
class SandboxFileSystem(root: File) {
    val root: File = root.canonicalFile.also { it.mkdirs() }

    fun resolve(relativePath: String): File {
        val f = File(root, relativePath.trimStart('/')).canonicalFile
        if (f != root && !f.path.startsWith(root.path + File.separator)) {
            throw SecurityException("Path is outside the PocketOS home folder")
        }
        return f
    }

    fun relativeOf(file: File): String = file.canonicalFile.relativeTo(root).invariantSeparatorsPath

    fun list(relativeDir: String = ""): List<FileEntry> {
        val dir = resolve(relativeDir)
        if (!dir.isDirectory) throw IOException("Not a folder: $relativeDir")
        return (dir.listFiles() ?: emptyArray())
            .map { FileEntry(it.name, relativeOf(it), it.isDirectory, if (it.isFile) it.length() else 0L, it.lastModified()) }
            .sortedWith(compareBy<FileEntry>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun createFolder(relativeDir: String, name: String): FileEntry {
        validateName(name)
        val f = File(resolve(relativeDir), name)
        resolve(relativeOf(f))
        if (f.exists()) throw IOException("\"$name\" already exists")
        if (!f.mkdirs()) throw IOException("Could not create \"$name\"")
        return FileEntry(f.name, relativeOf(f), true, 0, f.lastModified())
    }

    fun writeText(relativePath: String, text: String) {
        val f = resolve(relativePath)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    fun readText(relativePath: String, maxBytes: Long = MAX_TEXT_BYTES): String {
        val f = resolve(relativePath)
        if (f.length() > maxBytes) throw IOException("File is too large to open in the text editor")
        return f.readText()
    }

    fun rename(relativePath: String, newName: String): FileEntry {
        validateName(newName)
        val f = resolve(relativePath)
        if (f == root) throw IOException("Cannot rename the home folder")
        val target = File(f.parentFile, newName)
        resolve(relativeOf(target))
        if (target.exists()) throw IOException("\"$newName\" already exists")
        if (!f.renameTo(target)) throw IOException("Rename failed")
        return FileEntry(target.name, relativeOf(target), target.isDirectory, target.length(), target.lastModified())
    }

    fun delete(relativePath: String) {
        val f = resolve(relativePath)
        if (f == root) throw IOException("Cannot delete the home folder")
        if (!f.deleteRecursively()) throw IOException("Delete failed")
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val MAX_TEXT_BYTES = 2L * 1024 * 1024
        private val forbidden = Regex("[\\\\/:*?\"<>|\\u0000]")

        fun validateName(name: String) {
            if (name.isBlank() || name == "." || name == ".." || forbidden.containsMatchIn(name) || name.length > 128) {
                throw IllegalArgumentException("Choose a name without / \\ : * ? \" < > | characters")
            }
        }
    }
}
