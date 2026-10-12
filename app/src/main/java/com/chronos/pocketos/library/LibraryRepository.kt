package com.chronos.pocketos.library

import com.chronos.pocketos.core.library.LibraryCodec
import com.chronos.pocketos.core.library.LibraryEntry
import com.chronos.pocketos.core.library.LibraryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Persists the PC Apps library as JSON with atomic replace (write temp file, then rename). */
class LibraryRepository(private val file: File) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private fun load(): LibraryState = LibraryCodec.decode(runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull())

    @Synchronized
    fun update(transform: (LibraryState) -> LibraryState) {
        val next = transform(_state.value)
        _state.value = next
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(LibraryCodec.encode(next))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun upsert(e: LibraryEntry) = update { s -> s.copy(entries = s.entries.filterNot { it.id == e.id } + e) }
    fun remove(id: String) = update { s -> s.copy(entries = s.entries.filterNot { it.id == id }) }
    fun get(id: String): LibraryEntry? = state.value.entries.firstOrNull { it.id == id }
    fun modify(id: String, f: (LibraryEntry) -> LibraryEntry) = get(id)?.let { upsert(f(it)) }
}
