package com.chronos.pocketos

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.runtime.InstalledShortcut
import com.chronos.pocketos.runtime.RuntimeBackend
import java.io.File

/**
 * Stands in for the embedded Wine/Box64 runtime in JVM tests. It records what PocketOS asks for and lets tests
 * simulate what the real runtime process does (write the session result, create shortcuts).
 */
class FakeRuntimeBackend(private val context: Context) : RuntimeBackend {
    var installed = false
    var installSucceeds = true
    var container: Int? = null
    var drives: Map<Char, String> = emptyMap()
    val appliedProfiles = mutableListOf<RuntimeProfile>()
    val shortcutList = mutableListOf<InstalledShortcut>()
    var lastLaunch: Intent? = null

    override fun systemFilesInstalled() = installed
    override fun installSystemFiles(progress: (Int) -> Unit): Boolean {
        listOf(10, 50, 100).forEach(progress)
        installed = installSucceeds
        return installSucceeds
    }
    override fun containerId() = container
    override fun createContainer(name: String, drives: Map<Char, String>, profile: RuntimeProfile): Int? {
        this.drives = drives; container = 1; return 1
    }
    override fun ensureDrives(containerId: Int, drives: Map<Char, String>) { this.drives = drives }
    override fun applyProfile(containerId: Int, profile: RuntimeProfile) { appliedProfiles += profile }
    override fun launchIntent(containerId: Int, unixPath: String?, shortcutPath: String?, sessionId: String): Intent =
        Intent().setComponent(ComponentName(context.packageName, "com.winlator.XServerDisplayActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("container_id", containerId)
            .apply { if (shortcutPath != null) putExtra("shortcut_path", shortcutPath) else if (unixPath != null) putExtra("exec_path", unixPath) }
            .putExtra("pocketos_hosted", true).putExtra("pocketos_session_id", sessionId)
            .also { lastLaunch = it }
    override fun shortcuts(containerId: Int): List<InstalledShortcut> = shortcutList.toList()
    override fun advancedSettingsIntent(): Intent =
        Intent().setComponent(ComponentName(context.packageName, "com.winlator.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    override fun sessionResultFile() = File(context.filesDir, "pocketos_runtime/last_session.json")
    override fun installedSize() = if (installed) 1234L else 0L

    fun makeReady() { installed = true; container = 1 }

    /** What the runtime process writes when the program PocketOS started exits. */
    fun finishSession(exitStatus: Int = 0) {
        val id = lastLaunch?.getStringExtra("pocketos_session_id")
        sessionResultFile().apply { parentFile!!.mkdirs() }
            .writeText("""{"sessionId":"$id","exitStatus":$exitStatus,"endedAt":${System.currentTimeMillis()}}""")
    }
}
