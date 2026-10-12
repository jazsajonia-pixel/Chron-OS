package com.chronos.pocketos

import android.content.Context
import android.view.KeyEvent
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.runtime.InstalledShortcut
import com.chronos.pocketos.runtime.RuntimeBackend
import com.chronos.pocketos.runtime.RuntimeSession
import com.chronos.pocketos.runtime.SessionEvents

/**
 * Stands in for PocketOS's Windows engine (Wine + FEX) in JVM tests. It records what PocketOS asks for and lets tests
 * simulate what the real runtime does (show the program, end it, create shortcuts).
 */
class FakeRuntimeBackend(private val context: Context) : RuntimeBackend {
    var installed = false
    var installSucceeds = true
    var container: Int? = null
    var drives: Map<Char, String> = emptyMap()
    val appliedProfiles = mutableListOf<RuntimeProfile>()
    val shortcutList = mutableListOf<InstalledShortcut>()
    var lastLaunch: Launch? = null
    val launches = mutableListOf<Launch>()

    data class Launch(val containerId: Int, val unixPath: String?, val shortcutPath: String?, val session: FakeSession)

    class FakeSession(private val events: SessionEvents) : RuntimeSession {
        var stopped = false
        var ended = false
        val keys = mutableListOf<KeyEvent>()
        val typed = StringBuilder()
        var keyboardShown = 0

        override fun sendKey(event: KeyEvent): Boolean { keys += event; return true }
        override fun typeText(text: CharSequence) { typed.append(text) }
        override fun showSoftKeyboard() { keyboardShown++ }
        override fun stop() { stopped = true; end(null) }

        fun ready() = events.onReady()
        fun status(text: String) = events.onStatus(text)
        fun end(exitStatus: Int?) { if (!ended) { ended = true; events.onEnded(exitStatus) } }
    }

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
    var stoppedAll = 0
    override fun stopAll() { stoppedAll++ }
    override fun startSession(containerId: Int, unixPath: String?, shortcutPath: String?, events: SessionEvents, profile: RuntimeProfile): RuntimeSession {
        val session = FakeSession(events)
        val launch = Launch(containerId, unixPath, shortcutPath, session)
        lastLaunch = launch
        launches += launch
        return session
    }
    override fun shortcuts(containerId: Int): List<InstalledShortcut> = shortcutList.toList()
    override fun installedSize() = if (installed) 1234L else 0L

    fun makeReady() { installed = true; container = 1 }

    /** The program PocketOS started exits by itself. */
    fun finishSession(exitStatus: Int = 0) {
        lastLaunch?.session?.end(exitStatus)
    }
}
