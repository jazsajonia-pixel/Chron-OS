package com.chronos.pocketos.runtime

import android.content.Context
import android.view.KeyEvent
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.runtime.InstalledShortcut

/**
 * The part of the Windows runtime that touches the engine (Wine and the FEX emulator). The real implementation is
 * [WineBackend]; unit tests use a fake so they run on any machine.
 */
interface RuntimeBackend {
    /** Why the engine cannot run on this phone at all, or null. */
    fun unsupportedReason(): String? = null

    /** True when the engine is unpacked and current. */
    fun systemFilesInstalled(): Boolean

    /** Unpacks the system files. Blocking; call from a background thread. */
    fun installSystemFiles(progress: (Int) -> Unit): Boolean

    /** Id of PocketOS's Windows environment ("container"), or null if it does not exist yet. */
    fun containerId(): Int?

    /** Creates PocketOS's Windows environment with the given drive mapping. Blocking. */
    fun createContainer(name: String, drives: Map<Char, String>, profile: RuntimeProfile): Int?

    /** Makes sure the container maps these drives (e.g. after an update moved a folder). */
    fun ensureDrives(containerId: Int, drives: Map<Char, String>)

    /** Applies a program's settings (scaling) before it starts. */
    fun applyProfile(containerId: Int, profile: RuntimeProfile)

    /**
     * Starts [unixPath] (a .exe, .msi, .bat or .lnk) or the shortcut at [shortcutPath] (a .desktop file an installer
     * created), or the Windows file manager when both are null. The program's windows appear on the PocketOS
     * desktop. Several programs can run at once. Call on the main thread.
     */
    fun startSession(containerId: Int, unixPath: String?, shortcutPath: String?, events: SessionEvents, profile: RuntimeProfile): RuntimeSession

    /** Ends every Windows program and the engine. */
    fun stopAll()

    /** Every program shortcut that installers created inside the container. */
    fun shortcuts(containerId: Int): List<InstalledShortcut>

    /** Bytes the runtime's system files and Windows environment use. */
    fun installedSize(): Long
}

/** What a running session reports back. All callbacks arrive on the main thread. */
interface SessionEvents {
    fun onStatus(message: String)
    fun onReady()
    /** Called once; [exitStatus] is null when unknown (e.g. the user stopped the program). */
    fun onEnded(exitStatus: Int?)
}

/** A running Windows program. */
interface RuntimeSession {
    /** Sends a key to the program. Returns false if Windows has no equivalent key. */
    fun sendKey(event: KeyEvent): Boolean

    /** Types text into the program, e.g. from the Smart Cursor's text box. */
    fun typeText(text: CharSequence)

    /** Shows the phone's on-screen keyboard for the focused Windows window. */
    fun showSoftKeyboard()

    /** Ends the program. */
    fun stop()
}

/** Result of a session PocketOS started. */
data class SessionResult(val sessionId: String?, val exitStatus: Int?, val endedAt: Long)

fun Context.defaultRuntimeBackend(): RuntimeBackend = WineBackend(this)
