package com.chronos.pocketos.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.chronos.pocketos.core.library.AppStatus
import com.chronos.pocketos.core.library.Assessment
import com.chronos.pocketos.core.library.Compatibility
import com.chronos.pocketos.core.library.LibraryEntry
import com.chronos.pocketos.core.library.Recovery
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.library.SetupEnvironment
import com.chronos.pocketos.core.library.safeFolderName
import com.chronos.pocketos.core.pe.FileByteSource
import com.chronos.pocketos.core.pe.PeInspector
import com.chronos.pocketos.core.pe.PeReport
import com.chronos.pocketos.core.pe.FileKind
import com.chronos.pocketos.core.pe.InstallerType
import com.chronos.pocketos.core.pe.Machine
import com.chronos.pocketos.core.runtime.InstalledProgramDiscovery
import com.chronos.pocketos.core.runtime.InstalledShortcut
import com.chronos.pocketos.core.runtime.RuntimeState
import com.chronos.pocketos.core.runtime.SampleResult
import com.chronos.pocketos.core.runtime.SessionPurpose
import com.chronos.pocketos.runtime.RuntimeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class ImportCandidate(val temp: File, val fileName: String, val sizeBytes: Long, val sha256: String, val report: PeReport)

sealed interface ImportState {
    data object Idle : ImportState
    data class Reading(val fileName: String, val bytes: Long, val total: Long?) : ImportState
    data class Inspected(val candidate: ImportCandidate, val blocked: Assessment?, val duplicateOf: String?) : ImportState
    data class Copying(val name: String, val bytes: Long, val total: Long) : ImportState
    data class Done(val entryId: String) : ImportState
    data class Failed(val message: String, val nextSteps: List<String>) : ImportState
}

/**
 * Imports untrusted Windows files. Steps: copy to private cache while hashing → inspect bytes (never executed) →
 * user confirms → copy into PocketOS's programs folder (drive D: of the built-in runtime) → library entry.
 * Every step can be cancelled; temp files are removed. Entries are then run or installed inside PocketOS.
 */
class ImportManager(
    private val context: Context,
    private val library: LibraryRepository,
    private val runtime: RuntimeManager,
    private val scope: CoroutineScope,
    private val defaultProfile: () -> RuntimeProfile,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var job: Job? = null
    private val importDir = File(context.cacheDir, "imports")

    fun environment(): SetupEnvironment {
        val s = runtime.state.value
        return SetupEnvironment(runtimeReady = s is RuntimeState.Ready, runtimeCompatibleDevice = s !is RuntimeState.Unsupported)
    }

    fun assess(e: LibraryEntry): Assessment = Compatibility.assess(e, environment())

    fun begin(uri: Uri, nameHint: String? = null) {
        cancel()
        job = scope.launch {
            try {
                val name = nameHint ?: displayName(uri) ?: "program.exe"
                val total = runCatching { querySize(uri) }.getOrNull()
                _state.value = ImportState.Reading(name, 0, total)
                importDir.mkdirs()
                val temp = File(importDir, UUID.randomUUID().toString() + ".bin")
                val md = MessageDigest.getInstance("SHA-256")
                val size = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { out -> copyWithProgress(input, out::write, md) { n -> _state.value = ImportState.Reading(name, n, total) } }
                    } ?: error("The file could not be opened")
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                val report = withContext(Dispatchers.IO) { FileByteSource(temp).use { PeInspector.inspect(it, name) } }
                val dup = library.state.value.entries.firstOrNull { it.sha256 == sha }?.displayName
                _state.value = ImportState.Inspected(ImportCandidate(temp, name, size, sha, report), Compatibility.importable(report), dup)
            } catch (e: CancellationException) {
                cleanupTemp(); _state.value = ImportState.Idle; throw e
            } catch (e: Exception) {
                cleanupTemp()
                _state.value = ImportState.Failed("Could not read the file: ${e.message ?: e.javaClass.simpleName}", listOf("Check that the file is still available and try again."))
            }
        }
    }

    /** Adds the inspected file to the library. Unsupported files are recorded as Unsupported only if [keepUnsupported]. */
    fun confirm(displayName: String, profile: RuntimeProfile, keepUnsupported: Boolean = false) {
        val st = _state.value as? ImportState.Inspected ?: return
        val c = st.candidate
        val now = clock()
        val blocked = st.blocked
        if (blocked != null && !keepUnsupported) { cancel(); return }
        val name = displayName.trim().ifEmpty { c.report.suggestedName ?: c.fileName.substringBeforeLast('.') }
        var entry = LibraryEntry(
            id = UUID.randomUUID().toString(), displayName = name, sourceFileName = c.fileName, sha256 = c.sha256, sizeBytes = c.sizeBytes,
            kind = c.report.kind, machine = c.report.machine, installer = c.report.installer,
            status = blocked?.status ?: AppStatus.NEEDS_SETUP, statusDetail = blocked?.detail ?: "", profile = profile, createdAt = now, updatedAt = now,
        ).withLog(now, "Imported ${c.fileName} (${c.sizeBytes} bytes, SHA-256 ${c.sha256.take(12)}…). Inspected without running it.")
        if (blocked != null) {
            library.upsert(entry.withLog(now, "Unsupported: ${blocked.detail}"))
            cleanupTemp(); _state.value = ImportState.Done(entry.id); return
        }
        job = scope.launch {
            try {
                entry = stage(entry, c.temp)
                entry = refreshed(entry)
                library.upsert(entry)
                _state.value = ImportState.Done(entry.id)
            } catch (e: CancellationException) {
                _state.value = ImportState.Idle; throw e
            } catch (e: Exception) {
                val failed = entry.copy(status = AppStatus.FAILED, statusDetail = "Copying failed: ${e.message}").withLog(clock(), "Copy failed: ${e.message}")
                library.upsert(failed)
                _state.value = ImportState.Failed("Copying into PocketOS failed: ${e.message}", listOf("Check that the phone has free space, then import the file again."))
            } finally {
                cleanupTemp()
            }
        }
    }

    /** Copies an entry's file into PocketOS again (e.g. after its copy was deleted). Requires the same source file. */
    fun stageFromUri(entryId: String, uri: Uri) {
        val e = library.get(entryId) ?: return
        job = scope.launch {
            try {
                importDir.mkdirs()
                val temp = File(importDir, UUID.randomUUID().toString() + ".bin")
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { i -> temp.outputStream().use { o -> i.copyTo(o) } } }
                val sha = withContext(Dispatchers.IO) { FileByteSource(temp).use { PeInspector.sha256(it) } }
                if (sha != e.sha256) error("this is a different file than the one imported")
                library.upsert(refreshed(stage(e, temp)))
                _state.value = ImportState.Done(e.id)
            } catch (ex: CancellationException) { throw ex } catch (ex: Exception) {
                library.modify(e.id) { it.withLog(clock(), "Copy failed: ${ex.message}") }
                _state.value = ImportState.Failed("Copy failed: ${ex.message}", emptyList())
            } finally { cleanupTemp() }
        }
    }

    private suspend fun stage(e: LibraryEntry, temp: File): LibraryEntry {
        val store = runtime.programs
        val rel = "Apps/${safeFolderName(e.displayName)}/${safeFolderName(e.sourceFileName)}"
        _state.value = ImportState.Copying(e.displayName, 0, temp.length())
        withContext(Dispatchers.IO) {
            store.write(rel, object : InputStream() {
                val inner = temp.inputStream(); var n = 0L
                override fun read(): Int = inner.read().also { if (it >= 0) n++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (job?.isActive == false) throw CancellationException("Cancelled")
                    return inner.read(b, off, len).also { if (it > 0) { n += it; _state.value = ImportState.Copying(e.displayName, n, temp.length()) } }
                }
                override fun close() = inner.close()
            })
        }
        return e.copy(stagedPath = rel).withLog(clock(), "Copied into PocketOS (in Windows: ${runtime.windowsPathFor(rel)})")
    }

    fun refreshed(e: LibraryEntry): LibraryEntry {
        val a = assess(e)
        return if (a.status == e.status && a.detail == e.statusDetail) e else e.copy(status = a.status, statusDetail = a.detail, updatedAt = clock())
    }

    /** Re-evaluates all entries (after runtime/folder changes). */
    fun refreshAll() = library.update { s -> s.copy(entries = s.entries.map(::refreshed)) }

    fun reportWorked(id: String) = library.modify(id) { refreshed(Recovery.markWorked(it, clock())) }
    fun reportFailed(id: String) = library.modify(id) { refreshed(Recovery.markFailed(it, clock())) }
    /** Applies the next bounded recovery step; returns false if none are left. */
    fun applyNextRecovery(id: String): Boolean {
        val e = library.get(id) ?: return false
        val next = Recovery.applyNext(e, clock()) ?: return false
        library.upsert(refreshed(next)); return true
    }

    /** Removes the entry and, if asked, the copy PocketOS keeps. Programs installed inside Windows stay installed. */
    fun remove(id: String, deleteFiles: Boolean): String {
        val e = library.get(id) ?: return "Already removed"
        var msg = "Removed ${e.displayName} from the library."
        if (deleteFiles && e.stagedPath != null) {
            val folder = e.stagedPath!!.substringBeforeLast('/')
            msg += if (runCatching { runtime.programs.delete(folder) }.getOrDefault(false)) " Its copy was deleted." else " Its copy could not be deleted."
        }
        if (e.isInstaller && e.installerRan) msg += " Programs it installed stay in your library until you remove them."
        if (e.shortcutPath != null) msg += " It is still installed in Windows; run its uninstaller from the Windows desktop to remove it completely."
        library.remove(id)
        return msg
    }

    // ---- Starting programs inside the built-in runtime ----

    /**
     * Starts the entry inside PocketOS: the program opens in a PocketOS window. Returns a message for the user when
     * it cannot start (or another program is still running), otherwise null.
     */
    fun start(id: String): String? {
        val e = library.get(id) ?: return "This entry no longer exists."
        if (!runtime.isReady) return "Set up the Windows runtime first (PC Apps \u2192 Runtime)."
        if (e.status == AppStatus.UNSUPPORTED) return e.statusDetail
        val purpose = if (e.isInstaller) SessionPurpose.INSTALL else SessionPurpose.RUN
        // .desktop shortcuts carry their own start command; .lnk and .exe files are started directly.
        val lnk = e.shortcutPath?.takeIf { it.endsWith(".lnk", ignoreCase = true) || it.endsWith(".exe", ignoreCase = true) }
        val desktop = e.shortcutPath?.takeIf { lnk == null }
        if (e.stagedPath != null && !File(runtime.programsDir, e.stagedPath!!).isFile) {
            return "PocketOS's copy of ${e.sourceFileName} is missing. Import the file again."
        }
        when (val r = runtime.launch(purpose, e.id, e.stagedPath, desktop, e.profile, e.displayName, execPath = lnk)) {
            is RuntimeManager.LaunchResult.Busy -> return "${r.running.title} is still running. Close its window first."
            is RuntimeManager.LaunchResult.NotReady -> return r.message
            is RuntimeManager.LaunchResult.Started -> Unit
        }
        val now = clock()
        library.modify(id) {
            it.copy(lastRunAt = now, installerRan = it.installerRan || purpose == SessionPurpose.INSTALL)
                .withLog(now, (if (purpose == SessionPurpose.INSTALL) "Started the installer" else "Started") + " with ${it.profile.label}")
        }
        return null
    }

    /**
     * Called when a Windows program ends (and when PocketOS starts). If a program PocketOS started has exited, records the
     * result; after an installer, adds the programs it installed to the library. Returns a message for the user.
     */
    fun onReturnFromRuntime(): String? {
        val finished = runtime.consumeFinishedSession()
        if (finished == null) {
            // The sample writes its marker immediately, so it can be verified even if the session is still open.
            if (runtime.pendingSession()?.purpose == SessionPurpose.SAMPLE && runtime.checkSample() == SampleResult.VERIFIED) {
                return "Self-test passed: the sample Windows program ran on this phone."
            }
            return null
        }
        val p = finished.pending
        val code = finished.result?.exitStatus
        val now = clock()
        return when (p.purpose) {
            SessionPurpose.SAMPLE -> when (runtime.checkSample()) {
                SampleResult.VERIFIED -> "Self-test passed: the sample Windows program ran on this phone."
                else -> "The self-test program did not confirm that it ran. Open the Runtime tab for details."
            }
            SessionPurpose.DESKTOP -> null
            SessionPurpose.RUN -> {
                val e = p.entryId?.let(library::get) ?: return null
                library.modify(e.id) { refreshed(it.copy(lastExitCode = code).withLog(now, "Exited" + (code?.let { c -> " with code $c" } ?: ""))) }
                "${e.displayName} closed. Did it work? Tell PocketOS in PC Apps."
            }
            SessionPurpose.INSTALL -> {
                val e = p.entryId?.let(library::get) ?: return null
                val added = addInstalledPrograms(e, p.shortcutsBefore)
                library.modify(e.id) { refreshed(it.copy(lastExitCode = code).withLog(now, "Installer exited" + (code?.let { c -> " with code $c" } ?: "") + "; found ${added.size} new program(s)")) }
                if (added.isEmpty()) "The installer closed. No new program shortcuts were found; tap \u201cFind installed programs\u201d if it installed something."
                else "Installed and added to your library: ${added.joinToString { it.displayName }}."
            }
        }
    }

    /** Looks for shortcuts that are not in the library yet and adds them as programs installed by [installerId]. */
    fun findInstalledPrograms(installerId: String): List<LibraryEntry> {
        val e = library.get(installerId) ?: return emptyList()
        val known = library.state.value.entries.mapNotNull { it.shortcutPath }.toSet()
        return addInstalledPrograms(e, known)
    }

    private fun addInstalledPrograms(installer: LibraryEntry, before: Set<String>): List<LibraryEntry> {
        val known = library.state.value.entries.mapNotNull { it.shortcutPath }.toSet()
        val found = InstalledProgramDiscovery.newPrograms(before + known, runCatching { runtime.shortcuts() }.getOrDefault(emptyList()))
        val now = clock()
        val added = found.map { sc -> installedEntry(installer, sc, now) }
        added.forEach { library.upsert(refreshed(it)) }
        return added
    }

    private fun installedEntry(installer: LibraryEntry, sc: InstalledShortcut, now: Long) = LibraryEntry(
        id = UUID.randomUUID().toString(), displayName = sc.name, sourceFileName = File(sc.path).name, sha256 = "", sizeBytes = 0,
        kind = FileKind.WINDOWS_PROGRAM, machine = Machine.UNKNOWN, installer = InstallerType.NONE,
        status = AppStatus.NEEDS_SETUP, statusDetail = "", shortcutPath = sc.path, installedFromId = installer.id, iconPath = sc.iconPath,
        profile = installer.knownGood ?: installer.profile, createdAt = now, updatedAt = now,
    ).withLog(now, "Installed by ${installer.displayName}")

    fun cancel() {
        job?.cancel(); job = null
        cleanupTemp()
        _state.value = ImportState.Idle
    }

    fun dismiss() { if (_state.value is ImportState.Done || _state.value is ImportState.Failed) _state.value = ImportState.Idle }

    /** Deletes leftover temp files (also called at startup for crash recovery). Returns bytes freed. */
    fun cleanupTemp(): Long {
        val files = importDir.listFiles().orEmpty()
        val freed = files.sumOf { it.length() }
        files.forEach { it.delete() }
        return freed
    }

    private suspend fun copyWithProgress(input: InputStream, write: (ByteArray, Int, Int) -> Unit, md: MessageDigest, progress: (Long) -> Unit): Long {
        val buf = ByteArray(256 * 1024); var total = 0L; var lastReport = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf); if (n < 0) break
            write(buf, 0, n); md.update(buf, 0, n); total += n
            if (total - lastReport >= 1 shl 20) { progress(total); lastReport = total }
        }
        progress(total); return total
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun querySize(uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
}
