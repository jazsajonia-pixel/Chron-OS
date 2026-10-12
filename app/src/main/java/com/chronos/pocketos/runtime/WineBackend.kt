package com.chronos.pocketos.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.runtime.InstalledShortcut
import com.chronos.pocketos.engine.WineEngine
import com.chronos.pocketos.engine.WineHost
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs Windows programs with PocketOS's own engine ([WineEngine]): every program is a Wine process, and its windows
 * are drawn by [WineHost] directly on the PocketOS desktop, next to PocketOS's own windows.
 */
class WineBackend(context: Context) : RuntimeBackend {
    private val app = context.applicationContext
    val engine = WineEngine(app)
    val host: WineHost = WineHost.get(app)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var dpi = 120
    private val prefs = app.getSharedPreferences("pocketos_engine", Context.MODE_PRIVATE)

    override fun unsupportedReason(): String? =
        if (engine.pageSize != 4096L) "This phone's system uses ${engine.pageSize / 1024} KB memory pages; the Windows engine needs 4 KB pages." else null

    override fun systemFilesInstalled(): Boolean = engine.isInstalled()

    override fun installSystemFiles(progress: (Int) -> Unit): Boolean = engine.install(progress)

    override fun containerId(): Int? = if (engine.prefixReady()) CONTAINER_ID else null

    override fun createContainer(name: String, drives: Map<Char, String>, profile: RuntimeProfile): Int? {
        engine.loadDriver(host)
        return if (engine.preparePrefix(drives) { }) CONTAINER_ID else null
    }

    override fun ensureDrives(containerId: Int, drives: Map<Char, String>) = engine.ensureDrives(drives)

    override fun applyProfile(containerId: Int, profile: RuntimeProfile) {
        dpi = profile.dpi(app.resources.displayMetrics.densityDpi)
        main.post { host.setDpi(dpi) }
    }

    override fun startSession(containerId: Int, unixPath: String?, shortcutPath: String?, events: SessionEvents, profile: RuntimeProfile): RuntimeSession {
        val session = WineSession()
        thread(name = "wine-session", isDaemon = true) {
            val code = try {
                main.post { events.onStatus("Starting Windows\u2026") }
                engine.loadDriver(host)
                if (!host.isDesktopReady) {
                    engine.startDesktop()
                    waitForDesktop(60)
                }
                val path = unixPath ?: shortcutPath
                if (path != null && path.endsWith(".exe", ignoreCase = true)) applyWindowsVersion(File(path).name, profile.windowsVersion.wineName)
                val args = when {
                    path == null -> listOf("winefile.exe")
                    // An .exe runs in the started process itself, so its exit is the program's exit.
                    path.endsWith(".exe", ignoreCase = true) -> listOf(path)
                    // Shortcuts, .msi and .bat files are opened the way Windows would, waiting until they finish.
                    else -> listOf("start.exe", "/wait", "/unix", path)
                }
                val p = engine.start(args, engine.logFile("program.log"))
                session.process = p
                main.post { events.onReady() }
                p.waitFor()
                p.exitValue()
            } catch (t: Throwable) {
                android.util.Log.e("PocketOS-Engine", "session failed", t)
                main.post { events.onStatus("Could not start: ${t.message ?: t.javaClass.simpleName}") }
                null
            }
            main.post { events.onEnded(if (session.stopped) null else code) }
        }
        return session
    }

    /** Writes the per-program Windows version only when it changes (each change costs a short Wine start). */
    private fun applyWindowsVersion(exe: String, version: String?) {
        val key = "winver:" + exe.lowercase()
        val current = prefs.getString(key, null)
        if (current == version) return
        engine.setWindowsVersion(exe, version)
        prefs.edit().putString(key, version).apply()
    }

    private fun waitForDesktop(seconds: Long) {
        if (host.isDesktopReady) return
        val latch = CountDownLatch(1)
        val l = object : WineHost.Listener {
            override fun onDesktopReady() { latch.countDown() }
            override fun onWindowsChanged(visibleTopLevelWindows: Int) {}
            override fun onWindowActivated(hwnd: Int) {}
        }
        host.addListener(l)
        latch.await(seconds, TimeUnit.SECONDS)
        host.removeListener(l)
    }

    private inner class WineSession : RuntimeSession {
        @Volatile var process: Process? = null
        @Volatile var stopped = false
        override fun sendKey(event: KeyEvent): Boolean = host.sendKey(event)
        override fun typeText(text: CharSequence) = host.typeText(text)
        override fun showSoftKeyboard() = host.showSoftKeyboard()
        override fun stop() {
            stopped = true
            process?.destroy()
        }
    }

    override fun stopAll() {
        thread(name = "wine-stop", isDaemon = true) {
            engine.shutdown()
            host.reset()
        }
    }

    override fun shortcuts(containerId: Int): List<InstalledShortcut> {
        val c = File(engine.prefix, "drive_c")
        val roots = listOf(
            "users/pocketos/AppData/Roaming/Microsoft/Windows/Start Menu/Programs",
            "ProgramData/Microsoft/Windows/Start Menu/Programs",
            "users/pocketos/Desktop",
            "users/Public/Desktop",
        ).map { File(c, it) }
        return roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().maxDepth(4).filter { it.isFile && it.name.endsWith(".lnk", ignoreCase = true) }
                .map { InstalledShortcut(it.name.removeSuffix(".lnk").removeSuffix(".LNK"), it.path) }.toList()
        }.distinctBy { it.name.lowercase() }
    }

    override fun installedSize(): Long = engine.installedBytes()

    companion object {
        const val CONTAINER_ID = 1
        const val CONTAINER_NAME = "PocketOS"
    }
}
