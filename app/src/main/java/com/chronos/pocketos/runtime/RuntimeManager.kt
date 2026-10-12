package com.chronos.pocketos.runtime

import android.content.Context
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.runtime.InstalledShortcut
import com.chronos.pocketos.core.runtime.RuntimeCatalog
import com.chronos.pocketos.core.runtime.RuntimeInfo
import com.chronos.pocketos.core.runtime.RuntimePaths
import com.chronos.pocketos.core.runtime.RuntimeState
import com.chronos.pocketos.core.runtime.RuntimeStateRules
import com.chronos.pocketos.core.runtime.SampleResult
import com.chronos.pocketos.core.runtime.SampleVerification
import com.chronos.pocketos.core.runtime.SessionPurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * PocketOS's built-in Windows runtime. Programs are copied into PocketOS's private programs folder (drive D: inside
 * Windows), the PocketOS Home folder is drive E:, and programs open their windows right on the PocketOS desktop: the
 * phone never switches to another screen. Every program is its own process, so a crashing program cannot take the
 * desktop down. Several programs can run at the same time, like on a PC.
 */
class RuntimeManager(
    private val context: Context,
    private val backend: RuntimeBackend,
    private val scope: CoroutineScope,
    private val abis: () -> List<String>,
    /** PocketOS Home folder, shared with Windows programs as drive E:. */
    private val homeDir: File,
) {
    val runtime: RuntimeInfo = RuntimeCatalog.BUILTIN
    private val prefs = context.getSharedPreferences("pocketos_runtime", Context.MODE_PRIVATE)

    /** Folder (drive D:) with every program PocketOS imported. */
    val programsDir: File = File(context.filesDir, "pc-apps").apply { mkdirs() }
    val programs: StagingStore = FileStagingStore(programsDir, "pocketos-programs", "PocketOS programs")

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotInstalled)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()
    private val _sample = MutableStateFlow(SampleStatus())
    val sample: StateFlow<SampleStatus> = _sample.asStateFlow()
    private val installLock = Mutex()

    data class SampleStatus(
        val staged: Boolean = false,
        val result: SampleResult? = null,
        val verifiedAt: Long? = null,
        val runtimeVersion: String? = null,
    )

    /** A program PocketOS started and has not yet seen exit. */
    data class PendingSession(
        val sessionId: String,
        val purpose: SessionPurpose,
        val entryId: String?,
        val startedAt: Long,
        /** Shortcut paths that existed before an installer started; used to find what it installed. */
        val shortcutsBefore: Set<String>,
    )

    data class FinishedSession(val pending: PendingSession, val result: SessionResult?)

    /** A Windows program that is running now. */
    data class ActiveSession(
        val sessionId: String,
        val purpose: SessionPurpose,
        val entryId: String?,
        val title: String,
        val ready: Boolean,
        val status: String,
        val handle: RuntimeSession,
    )

    sealed interface LaunchResult {
        data class Started(val session: ActiveSession) : LaunchResult
        data class NotReady(val message: String) : LaunchResult
    }

    private val _sessions = MutableStateFlow<List<ActiveSession>>(emptyList())
    /** Programs that are running now, oldest first. */
    val sessions: StateFlow<List<ActiveSession>> = _sessions.asStateFlow()
    private val finished = ArrayDeque<FinishedSession>()

    /** Called on the main thread when a session ends (after its result can be read with [consumeFinishedSession]). */
    var onSessionEnded: ((ActiveSession) -> Unit)? = null

    init {
        // Programs that were running when PocketOS was last closed: report them once, without an exit code.
        loadPending().forEach { finished.addLast(FinishedSession(it, null)) }
        prefs.edit().remove(KEY_PENDING).apply()
        val verifiedAt = prefs.getLong(KEY_VERIFIED_AT, 0L).takeIf { it > 0 }
        _sample.value = SampleStatus(
            staged = prefs.getString(KEY_NONCE, null) != null,
            verifiedAt = verifiedAt,
            result = if (verifiedAt != null) SampleResult.VERIFIED else null,
            runtimeVersion = prefs.getString(KEY_VERIFIED_VERSION, null),
        )
        refresh()
    }

    fun refresh() {
        if (_state.value is RuntimeState.Installing) return
        backend.unsupportedReason()?.let { _state.value = RuntimeState.Unsupported(it); return }
        val installed = runCatching { backend.systemFilesInstalled() }.getOrDefault(false)
        val container = installed && runCatching { backend.containerId() }.getOrNull() != null
        _state.value = RuntimeStateRules.initial(runtime, abis(), installed, container)
    }

    val isReady: Boolean get() = state.value is RuntimeState.Ready

    private fun drives(): Map<Char, String> {
        homeDir.mkdirs()
        return mapOf(runtime.programsDrive to programsDir.path, runtime.homeDrive to homeDir.path)
    }

    /** Unpacks the system files and creates PocketOS's Windows environment. Safe to call again to repair. */
    fun install(defaultProfile: RuntimeProfile, onDone: (Boolean) -> Unit = {}) {
        if (state.value is RuntimeState.Unsupported || state.value is RuntimeState.Installing) return
        _state.value = RuntimeState.Installing(0, "Unpacking the Windows engine")
        scope.launch(Dispatchers.IO) {
            val ok = installLock.withLock {
                runCatching {
                    if (!backend.systemFilesInstalled()) {
                        val unpacked = backend.installSystemFiles { p ->
                            _state.value = RuntimeState.Installing((p * 0.6).toInt(), "Unpacking the Windows engine")
                        }
                        if (!unpacked) error("Could not unpack the Windows engine. Check that at least ${runtime.installedBytes / (1024 * 1024)} MB are free.")
                    }
                    _state.value = RuntimeState.Installing(62, "Creating the Windows environment (a few minutes the first time)")
                    val id = backend.containerId() ?: backend.createContainer(CONTAINER_NAME, drives(), defaultProfile)
                        ?: error("Could not create the Windows environment.")
                    backend.ensureDrives(id, drives())
                    true
                }.onFailure { _state.value = RuntimeState.Failed(it.message ?: "Setup failed") }.getOrDefault(false)
            }
            if (ok) _state.value = RuntimeState.Ready
            onDone(ok)
        }
    }

    fun windowsPathFor(relative: String): String = RuntimePaths.windowsPath(runtime.programsDrive, relative)

    /**
     * Starts a program: applies [profile], records the session and starts it. Its windows open on the PocketOS
     * desktop. Call on the main thread.
     */
    fun launch(
        purpose: SessionPurpose,
        entryId: String?,
        relativePath: String?,
        shortcutPath: String?,
        profile: RuntimeProfile,
        title: String,
        execPath: String? = null,
    ): LaunchResult {
        if (!isReady) return LaunchResult.NotReady("Set up the Windows runtime first (PC Apps \u2192 Runtime).")
        val id = backend.containerId() ?: return LaunchResult.NotReady("The Windows environment is missing. Repair the runtime in PC Apps \u2192 Runtime.")
        backend.ensureDrives(id, drives())
        backend.applyProfile(id, profile)
        val sessionId = UUID.randomUUID().toString()
        val before = if (purpose == SessionPurpose.INSTALL) backend.shortcuts(id).map { it.path }.toSet() else emptySet()
        val pending = PendingSession(sessionId, purpose, entryId, System.currentTimeMillis(), before)
        savePending(loadPending() + pending)
        val unix = execPath ?: relativePath?.let { File(programsDir, it).path }
        var published = false
        var endedEarly: Array<Int?>? = null
        val handle = backend.startSession(id, unix, shortcutPath, object : SessionEvents {
            override fun onStatus(message: String) = update(sessionId) { it.copy(status = message) }
            override fun onReady() = update(sessionId) { it.copy(ready = true, status = "") }
            override fun onEnded(exitStatus: Int?) {
                if (published) sessionEnded(pending, exitStatus) else endedEarly = arrayOf(exitStatus)
            }
        }, profile)
        val session = ActiveSession(sessionId, purpose, entryId, title, ready = false, status = "Starting\u2026", handle = handle)
        _sessions.value = _sessions.value + session
        published = true
        endedEarly?.let { sessionEnded(pending, it[0]) }
        return LaunchResult.Started(session)
    }

    private fun update(sessionId: String, f: (ActiveSession) -> ActiveSession) {
        _sessions.value = _sessions.value.map { if (it.sessionId == sessionId) f(it) else it }
    }

    private fun sessionEnded(pending: PendingSession, exitStatus: Int?) {
        val ended = _sessions.value.firstOrNull { it.sessionId == pending.sessionId }
        finished.addLast(FinishedSession(pending, SessionResult(pending.sessionId, exitStatus, System.currentTimeMillis())))
        savePending(loadPending().filter { it.sessionId != pending.sessionId })
        if (ended != null) {
            _sessions.value = _sessions.value.filter { it.sessionId != pending.sessionId }
            onSessionEnded?.invoke(ended)
        }
    }

    /** Ends one running program. */
    fun stopSession(sessionId: String) {
        _sessions.value.firstOrNull { it.sessionId == sessionId }?.handle?.stop()
    }

    /** Ends every Windows program (and anything they started in the background). */
    fun stopAll() {
        _sessions.value.forEach { it.handle.stop() }
        backend.stopAll()
    }

    /** Opens the Windows file manager. */
    fun launchDesktop(profile: RuntimeProfile): LaunchResult =
        launch(SessionPurpose.DESKTOP, null, null, null, profile, "Windows files")

    /**
     * Returns a session that ended since the last call (oldest first), or null. A session that was running when
     * PocketOS itself was closed is returned (without an exit code) after PocketOS starts again.
     */
    fun consumeFinishedSession(): FinishedSession? = finished.removeFirstOrNull()

    /** Programs PocketOS started that are still running. */
    fun pendingSessions(): List<PendingSession> = loadPending()

    fun shortcuts(): List<InstalledShortcut> = backend.containerId()?.let { backend.shortcuts(it) }.orEmpty()

    fun installedSize(): Long = backend.installedSize()

    /** The end of the engine's log files, for the user to copy into a bug report. */
    fun engineLog(maxChars: Int = 6000): String {
        val dir = File(context.filesDir, "engine-logs")
        return listOf("setup.log", "desktop.log", "program.log").mapNotNull { name ->
            File(dir, name).takeIf { it.isFile && it.length() > 0 }?.let { f ->
                val text = runCatching { f.readText() }.getOrDefault("")
                "== $name ==\n" + text.takeLast(maxChars / 3)
            }
        }.joinToString("\n")
    }

    private fun savePending(list: List<PendingSession>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("sessionId", p.sessionId).put("purpose", p.purpose.name).put("entryId", p.entryId)
                .put("startedAt", p.startedAt).put("before", JSONArray(p.shortcutsBefore.toList())))
        }
        prefs.edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    private fun loadPending(): List<PendingSession> = runCatching {
        val raw = prefs.getString(KEY_PENDING, null) ?: return emptyList()
        // Older versions stored a single object.
        val arr = if (raw.trimStart().startsWith("{")) JSONArray().put(JSONObject(raw)) else JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val before = o.optJSONArray("before") ?: JSONArray()
            PendingSession(
                o.getString("sessionId"), SessionPurpose.valueOf(o.getString("purpose")),
                o.optString("entryId").takeIf { it.isNotEmpty() && it != "null" }, o.optLong("startedAt"),
                (0 until before.length()).map { before.getString(it) }.toSet(),
            )
        }
    }.getOrDefault(emptyList())

    // ---- Self-test: a tiny bundled Windows program that writes a marker file with a fresh nonce ----

    private val sampleRel get() = "${SampleVerification.SAMPLE_FOLDER}/${SampleVerification.SAMPLE_EXE}"

    /** Copies the sample program and a fresh nonce into the programs folder. */
    fun stageSample(): String {
        val nonce = SampleVerification.newNonce()
        val dir = SampleVerification.SAMPLE_FOLDER
        programs.delete("$dir/${SampleVerification.MARKER_FILE}")
        context.assets.open("sample/${SampleVerification.SAMPLE_EXE}").use { programs.write(sampleRel, it) }
        programs.writeText("$dir/${SampleVerification.NONCE_FILE}", nonce + "\r\n")
        prefs.edit().putString(KEY_NONCE, nonce).remove(KEY_VERIFIED_AT).remove(KEY_VERIFIED_VERSION).apply()
        _sample.value = SampleStatus(staged = true)
        return windowsPathFor(sampleRel)
    }

    /** Stages the sample and runs it inside PocketOS. */
    fun launchSample(profile: RuntimeProfile): LaunchResult {
        if (!isReady) return LaunchResult.NotReady("Set up the Windows runtime first.")
        stageSample()
        return launch(SessionPurpose.SAMPLE, null, sampleRel, null, profile, "Self-test")
    }

    /** Reads the marker the sample writes when it really runs. */
    fun checkSample(): SampleResult {
        val nonce = prefs.getString(KEY_NONCE, null) ?: return SampleResult.NOT_RUN_YET
        val marker = programs.readText("${SampleVerification.SAMPLE_FOLDER}/${SampleVerification.MARKER_FILE}", 512)
        val result = SampleVerification.check(marker, nonce)
        val version = runtime.engine
        val now = System.currentTimeMillis()
        if (result == SampleResult.VERIFIED) prefs.edit().putLong(KEY_VERIFIED_AT, now).putString(KEY_VERIFIED_VERSION, version).apply()
        _sample.value = _sample.value.copy(
            result = result,
            verifiedAt = if (result == SampleResult.VERIFIED) now else null,
            runtimeVersion = if (result == SampleResult.VERIFIED) version else null,
        )
        return result
    }

    companion object {
        const val CONTAINER_NAME = WineBackend.CONTAINER_NAME
        private const val KEY_NONCE = "sample_nonce"
        private const val KEY_VERIFIED_AT = "sample_verified_at"
        private const val KEY_VERIFIED_VERSION = "sample_verified_version"
        private const val KEY_PENDING = "pending_sessions"
    }
}
