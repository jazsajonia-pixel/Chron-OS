package com.chronos.pocketos.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.chronos.pocketos.core.runtime.RuntimeAvailability
import com.chronos.pocketos.core.runtime.RuntimeAvailabilityRules
import com.chronos.pocketos.core.runtime.RuntimeCatalog
import com.chronos.pocketos.core.runtime.RuntimeInfo
import com.chronos.pocketos.core.runtime.RuntimePath
import com.chronos.pocketos.core.runtime.RuntimePaths
import com.chronos.pocketos.core.runtime.SampleResult
import com.chronos.pocketos.core.runtime.SampleVerification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connects PocketOS to an installed compatibility runtime app. PocketOS never executes Windows code itself: it stages
 * files in a shared folder the user chose, opens the runtime, and verifies results only through files it can read back.
 */
class RuntimeManager(private val context: Context, private val abis: () -> List<String>) {
    val runtime: RuntimeInfo = RuntimeCatalog.WINLATOR
    private val prefs = context.getSharedPreferences("pocketos_runtime", Context.MODE_PRIVATE)
    private val _store = MutableStateFlow<StagingStore?>(null)
    val store: StateFlow<StagingStore?> = _store.asStateFlow()
    private val _availability = MutableStateFlow<RuntimeAvailability>(RuntimeAvailability.NotInstalled)
    val availability: StateFlow<RuntimeAvailability> = _availability.asStateFlow()
    private val _sample = MutableStateFlow(SampleStatus())
    val sample: StateFlow<SampleStatus> = _sample.asStateFlow()

    data class SampleStatus(
        val staged: Boolean = false,
        val path: RuntimePath? = null,
        val result: SampleResult? = null,
        val verifiedAt: Long? = null,
        val runtimeVersion: String? = null,
    )

    init {
        prefs.getString(KEY_WORKSPACE, null)?.let { u -> runCatching { _store.value = SafStagingStore(context, Uri.parse(u)) } }
        _sample.value = SampleStatus(
            staged = prefs.getString(KEY_NONCE, null) != null,
            verifiedAt = prefs.getLong(KEY_VERIFIED_AT, 0L).takeIf { it > 0 },
            result = if (prefs.getLong(KEY_VERIFIED_AT, 0L) > 0) SampleResult.VERIFIED else null,
            runtimeVersion = prefs.getString(KEY_VERIFIED_VERSION, null),
        )
        refresh()
    }

    fun refresh() {
        val installed = runCatching {
            val pi = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(runtime.packageName, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(runtime.packageName, 0)
            pi.versionName to (if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong())
        }.getOrNull()
        _availability.value = RuntimeAvailabilityRules.evaluate(runtime, abis(), installed)
    }

    fun setWorkspace(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        _store.value = SafStagingStore(context, uri)
        prefs.edit().putString(KEY_WORKSPACE, uri.toString()).apply()
    }

    /** For tests and diagnostics only: use a plain-file folder. */
    fun useStore(store: StagingStore?) { _store.value = store }

    fun clearWorkspace() {
        _store.value = null
        prefs.edit().remove(KEY_WORKSPACE).apply()
    }

    fun launchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(runtime.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun downloadPageIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(runtime.homepage + "/releases")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun pathFor(relative: String): RuntimePath? = store.value?.let { RuntimePaths.locate(runtime, it.documentId, relative) }

    /** Copies the bundled sample program and a fresh nonce into the workspace. Does not run anything. */
    fun stageSample(): RuntimePath {
        val s = store.value ?: error("Choose a shared folder first")
        val nonce = SampleVerification.newNonce()
        val dir = SampleVerification.SAMPLE_FOLDER
        s.delete("$dir/${SampleVerification.MARKER_FILE}")
        context.assets.open("sample/${SampleVerification.SAMPLE_EXE}").use { s.write("$dir/${SampleVerification.SAMPLE_EXE}", it) }
        s.writeText("$dir/${SampleVerification.NONCE_FILE}", nonce + "\r\n")
        prefs.edit().putString(KEY_NONCE, nonce).remove(KEY_VERIFIED_AT).remove(KEY_VERIFIED_VERSION).apply()
        val path = RuntimePaths.locate(runtime, s.documentId, "$dir/${SampleVerification.SAMPLE_EXE}")
        _sample.value = SampleStatus(staged = true, path = path)
        return path
    }

    /** Reads the marker the sample writes when it really runs. */
    fun checkSample(): SampleResult {
        val s = store.value ?: return SampleResult.NOT_RUN_YET
        val nonce = prefs.getString(KEY_NONCE, null) ?: return SampleResult.NOT_RUN_YET
        val marker = s.readText("${SampleVerification.SAMPLE_FOLDER}/${SampleVerification.MARKER_FILE}", 512)
        val result = SampleVerification.check(marker, nonce)
        val version = (availability.value as? RuntimeAvailability.Installed)?.versionName
        if (result == SampleResult.VERIFIED) {
            prefs.edit().putLong(KEY_VERIFIED_AT, System.currentTimeMillis()).putString(KEY_VERIFIED_VERSION, version).apply()
        }
        _sample.value = _sample.value.copy(
            result = result,
            verifiedAt = if (result == SampleResult.VERIFIED) System.currentTimeMillis() else null,
            runtimeVersion = if (result == SampleResult.VERIFIED) version else null,
        )
        return result
    }

    companion object {
        private const val KEY_WORKSPACE = "workspace_tree"
        private const val KEY_NONCE = "sample_nonce"
        private const val KEY_VERIFIED_AT = "sample_verified_at"
        private const val KEY_VERIFIED_VERSION = "sample_verified_version"
    }
}
