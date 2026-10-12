package com.chronos.pocketos.runtime

import android.content.Context
import android.content.Intent
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.runtime.InstalledShortcut

/**
 * The part of the Windows runtime that touches the embedded engine. The real implementation is
 * [EmbeddedWinlatorBackend]; unit tests use a fake so they run on any machine.
 */
interface RuntimeBackend {
    /** True when the Linux userland, Wine and Box64 are unpacked and current. */
    fun systemFilesInstalled(): Boolean

    /** Unpacks the system files. Blocking; call from a background thread. */
    fun installSystemFiles(progress: (Int) -> Unit): Boolean

    /** Id of PocketOS's Windows environment ("container"), or null if it does not exist yet. */
    fun containerId(): Int?

    /** Creates PocketOS's Windows environment with the given drive mapping. Blocking. */
    fun createContainer(name: String, drives: Map<Char, String>, profile: RuntimeProfile): Int?

    /** Makes sure the container maps these drives (e.g. after an update moved a folder). */
    fun ensureDrives(containerId: Int, drives: Map<Char, String>)

    /** Writes resolution and graphics settings into the container before a start. */
    fun applyProfile(containerId: Int, profile: RuntimeProfile)

    /**
     * Intent that opens the runtime's display and starts [unixPath] (a .exe, .msi or .lnk), or the Windows desktop
     * when both paths are null. [shortcutPath] is a .desktop shortcut created by an installer.
     */
    fun launchIntent(containerId: Int, unixPath: String?, shortcutPath: String?, sessionId: String): Intent

    /** Every program shortcut that installers created inside the container. */
    fun shortcuts(containerId: Int): List<InstalledShortcut>

    /** The engine's own settings screens (containers, Box64 presets, drivers, input controls). */
    fun advancedSettingsIntent(): Intent

    /** Folder where the runtime writes the result of a session that PocketOS started. */
    fun sessionResultFile(): java.io.File

    /** Bytes the runtime's system files and Windows environment use. */
    fun installedSize(): Long
}

/** Session result written by the runtime process when a program started from PocketOS exits. */
data class SessionResult(val sessionId: String?, val exitStatus: Int?, val endedAt: Long)

internal fun parseSessionResult(text: String?): SessionResult? = runCatching {
    val o = org.json.JSONObject(text ?: return null)
    SessionResult(
        sessionId = o.optString("sessionId").takeIf { it.isNotEmpty() },
        exitStatus = if (o.has("exitStatus")) o.getInt("exitStatus") else null,
        endedAt = o.optLong("endedAt"),
    )
}.getOrNull()

fun Context.defaultRuntimeBackend(): RuntimeBackend = EmbeddedWinlatorBackend(this)
