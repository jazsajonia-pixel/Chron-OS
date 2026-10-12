package com.chronos.pocketos.runtime

import android.content.Context
import android.view.KeyEvent
import android.view.View
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.runtime.InstalledShortcut

/**
 * The part of the Windows runtime that touches the engine (Wine, Box64 and PocketOS's display server). The real
 * implementation is [EmbeddedWindowsBackend]; unit tests use a fake so they run on any machine.
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
     * Starts [unixPath] (a .exe, .msi, .bat or .lnk) or the shortcut at [shortcutPath] (a .desktop file an installer
     * created), or the Windows desktop when both are null. The program is drawn by the returned session into a view
     * that PocketOS shows in one of its own windows. Call on the main thread.
     */
    fun startSession(containerId: Int, unixPath: String?, shortcutPath: String?, events: SessionEvents): RuntimeSession

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

/** A running Windows program. Its display can be shown in any number of views over time, one at a time. */
interface RuntimeSession {
    /** Creates the view that shows the program and passes touch, mouse and keyboard input to it. */
    fun createView(context: Context): View

    /** Sends a key to the program. Returns false if Windows has no equivalent key. */
    fun sendKey(event: KeyEvent): Boolean

    /** Types text into the program, e.g. from the Smart Cursor's text box. */
    fun typeText(text: CharSequence)

    /** Shows the phone's on-screen keyboard for the program's view. */
    fun showSoftKeyboard()

    /** Ends the program and everything it started. */
    fun stop()
}

/** Result of a session PocketOS started. */
data class SessionResult(val sessionId: String?, val exitStatus: Int?, val endedAt: Long)

fun Context.defaultRuntimeBackend(): RuntimeBackend = EmbeddedWindowsBackend(this)
