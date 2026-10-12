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
    lateinit var displays: com.chronos.pocketos.display.DisplaySessionManager
        private set
    lateinit var capabilities: com.chronos.pocketos.capability.CapabilityMonitor
        private set
    lateinit var runtime: com.chronos.pocketos.runtime.RuntimeManager
        private set

    /** Per-window transient state (e.g. unsaved editor text) that must survive moving between displays. */
    val windowState = mutableMapOf<Int, Any>()

    /** True while the companion screen's own text box has focus (keys then stay on the phone). */
    @Volatile var companionTextFocused = false

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        shell = ShellController(ShellController.decodeSession(settings.loadSession()))
        home = SandboxFileSystem(File(filesDir, "home"))
        input = PhysicalInputMonitor(this)
        keys = KeyShortcutHandler(shell, { settings.settings.value }, cursor)
        appScope.launch { settings.settings.collect { cursor.configure(it) } }
        displays = com.chronos.pocketos.display.DisplaySessionManager(this) { settings.settings.value.autoStartDesktopOnExternalDisplay }
        displays.startListening()
        capabilities = com.chronos.pocketos.capability.CapabilityMonitor(
            this, { displays.displays.value }, { settings.settings.value.profileChoice }, displays.supportsActivitiesOnSecondaryDisplays,
        )
        capabilities.startListening()
        runtime = com.chronos.pocketos.runtime.RuntimeManager(this) { capabilities.capabilities.value.abis }
        appScope.launch {
            var lastChoice = settings.settings.value.profileChoice
            settings.settings.collect { if (it.profileChoice != lastChoice) { lastChoice = it.profileChoice; capabilities.recheck("Profile changed") } }
        }
        appScope.launch { displays.displays.collect { capabilities.recheck("Displays changed") } }
        appScope.launch { capabilities.decision.collect { shell.maxRecommendedWindows = it.settings.maxRecommendedWindows } }
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

    /** UI scale multiplier for the external desktop (user override; Phase 4 adds profile-based defaults). */
    fun uiScaleForExternal(s: com.chronos.pocketos.core.settings.ShellSettings): Float =
        s.uiScaleOverride ?: (capabilities.capabilities.value.externalDisplay?.let {
            com.chronos.pocketos.core.capability.DisplayRecommendation.recommendUiScale(it)
        } ?: 1f)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::capabilities.isInitialized) capabilities.recheck("Android asked apps to free memory")
    }

    fun saveSession() = settings.saveSession(shell.encodeSession())
}

val LocalPocketOs = staticCompositionLocalOf<PocketOsApp> { error("PocketOsApp not provided") }
