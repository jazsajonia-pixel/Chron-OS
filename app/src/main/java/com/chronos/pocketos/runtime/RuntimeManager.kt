package com.chronos.pocketos.runtime

import android.content.Context
import android.content.Intent
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
 * Windows), the PocketOS Home folder is drive E:, and programs start inside PocketOS (in a separate process so a
 * crashing program cannot take the desktop down). When the program exits the runtime writes a small result file,
 * which PocketOS reads when it comes back to the front.
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

    init {
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
        _state.value = RuntimeState.Installing(0, "Unpacking Windows system files")
        scope.launch(Dispatchers.IO) {
            val ok = installLock.withLock {
                runCatching {
                    if (!backend.systemFilesInstalled()) {
                        val unpacked = backend.installSystemFiles { p ->
                            _state.value = RuntimeState.Installing((p * 0.9).toInt(), "Unpacking Windows system files")
                        }
                        if (!unpacked) error("Could not unpack the system files. Check that at least ${runtime.installedBytes / (1024 * 1024)} MB are free.")
                    }
                    _state.value = RuntimeState.Installing(92, "Creating the Windows environment")
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
     * Prepares a start: applies [profile], records the session and returns the intent that opens the program.
     * Returns null if the runtime is not ready.
     */
    fun prepareLaunch(
        purpose: SessionPurpose,
        entryId: String?,
        relativePath: String?,
        shortcutPath: String?,
        profile: RuntimeProfile,
    ): Intent? {
        if (!isReady) return null
        val id = backend.containerId() ?: return null
        backend.ensureDrives(id, drives())
        backend.applyProfile(id, profile)
        val sessionId = UUID.randomUUID().toString()
        val before = if (purpose == SessionPurpose.INSTALL) backend.shortcuts(id).map { it.path }.toSet() else emptySet()
        savePending(PendingSession(sessionId, purpose, entryId, System.currentTimeMillis(), before))
        backend.sessionResultFile().delete()
        val unix = relativePath?.let { File(programsDir, it).path }
        return backend.launchIntent(id, unix, shortcutPath, sessionId)
    }

    /** Opens the plain Windows desktop (file manager) of the runtime. */
    fun prepareDesktop(profile: RuntimeProfile): Intent? = prepareLaunch(SessionPurpose.DESKTOP, null, null, null, profile)

    /** Returns the session that ended since the last call, or null while a program is still running. */
    fun consumeFinishedSession(): FinishedSession? {
        val pending = loadPending() ?: return null
        val file = backend.sessionResultFile()
        val result = parseSessionResult(file.takeIf { it.isFile }?.readText()) ?: return null
        if (result.sessionId != null && result.sessionId != pending.sessionId) return null
        file.delete()
        prefs.edit().remove(KEY_PENDING).apply()
        return FinishedSession(pending, result)
    }

    fun pendingSession(): PendingSession? = loadPending()

    fun shortcuts(): List<InstalledShortcut> = backend.containerId()?.let { backend.shortcuts(it) }.orEmpty()

    fun advancedSettingsIntent(): Intent = backend.advancedSettingsIntent()

    fun installedSize(): Long = backend.installedSize()

    private fun savePending(p: PendingSession) {
        val o = JSONObject().put("sessionId", p.sessionId).put("purpose", p.purpose.name).put("entryId", p.entryId)
            .put("startedAt", p.startedAt).put("before", JSONArray(p.shortcutsBefore.toList()))
        prefs.edit().putString(KEY_PENDING, o.toString()).apply()
    }

    private fun loadPending(): PendingSession? = runCatching {
        val o = JSONObject(prefs.getString(KEY_PENDING, null) ?: return null)
        val arr = o.optJSONArray("before") ?: JSONArray()
        PendingSession(
            o.getString("sessionId"), SessionPurpose.valueOf(o.getString("purpose")),
            o.optString("entryId").takeIf { it.isNotEmpty() && it != "null" }, o.optLong("startedAt"),
            (0 until arr.length()).map { arr.getString(it) }.toSet(),
        )
    }.getOrNull()

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

    /** Stages the sample and returns the intent that runs it inside the runtime. */
    fun prepareSampleRun(profile: RuntimeProfile): Intent? {
        if (!isReady) return null
        stageSample()
        return prepareLaunch(SessionPurpose.SAMPLE, null, sampleRel, null, profile)
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
        const val CONTAINER_NAME = EmbeddedWinlatorBackend.CONTAINER_NAME
        private const val KEY_NONCE = "sample_nonce"
        private const val KEY_VERIFIED_AT = "sample_verified_at"
        private const val KEY_VERIFIED_VERSION = "sample_verified_version"
        private const val KEY_PENDING = "pending_session"
    }
}
