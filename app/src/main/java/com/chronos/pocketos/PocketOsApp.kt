package com.chronos.pocketos

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import com.chronos.pocketos.core.files.SandboxFileSystem
import com.chronos.pocketos.data.SettingsRepository
import com.chronos.pocketos.shell.ShellController
import java.io.File
import com.chronos.pocketos.input.KeyShortcutHandler
import com.chronos.pocketos.input.PhysicalInputMonitor
import com.chronos.pocketos.input.SmartCursor
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Lightweight service locator; avoids a DI framework dependency. */
class PocketOsApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var shell: ShellController
        private set
    lateinit var home: SandboxFileSystem
        private set
    val cursor = SmartCursor()
    lateinit var input: PhysicalInputMonitor
        private set
    lateinit var keys: KeyShortcutHandler
        private set
    val appScope = MainScope()

    /** Per-window transient state (e.g. unsaved editor text) that must survive moving between displays. */
    val windowState = mutableMapOf<Int, Any>()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        shell = ShellController(ShellController.decodeSession(settings.loadSession()))
        home = SandboxFileSystem(File(filesDir, "home"))
        input = PhysicalInputMonitor(this)
        keys = KeyShortcutHandler(shell, { settings.settings.value }, cursor)
        appScope.launch { settings.settings.collect { cursor.configure(it) } }
        seedHome()
    }

    private fun seedHome() {
        val docs = File(home.root, "Documents")
        if (!docs.exists()) {
            docs.mkdirs()
            File(home.root, "Downloads").mkdirs()
            File(docs, "Welcome.txt").writeText(
                "Welcome to PocketOS.\n\nThis is your PocketOS Home folder. It lives in PocketOS's private app storage, " +
                    "so other apps cannot read it.\nUse Files > Add folder to let PocketOS see a folder on your device.\n",
            )
        }
    }

    fun saveSession() = settings.saveSession(shell.encodeSession())
}

val LocalPocketOs = staticCompositionLocalOf<PocketOsApp> { error("PocketOsApp not provided") }
