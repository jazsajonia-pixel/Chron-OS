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
open class PocketOsApp : Application() {
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
    lateinit var diagnostics: com.chronos.pocketos.diag.DiagnosticsCenter
        private set
    lateinit var library: com.chronos.pocketos.library.LibraryRepository
        private set
    lateinit var imports: com.chronos.pocketos.library.ImportManager
        private set

    /** Per-window transient state (e.g. unsaved editor text) that must survive moving between displays. */
    val windowState = mutableMapOf<Int, Any>()

    /** True while the companion screen's own text box has focus (keys then stay on the phone). */
    @Volatile var companionTextFocused = false

    /** Overridden by tests with a fake; the real backend drives the embedded Wine/Box64 runtime. */
    open fun createRuntimeBackend(): com.chronos.pocketos.runtime.RuntimeBackend =
        com.chronos.pocketos.runtime.EmbeddedWindowsBackend(this)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        diagnostics = com.chronos.pocketos.diag.DiagnosticsCenter(this)
        diagnostics.installCrashHandler()
        // Crash-loop protection: after repeated early crashes, start with an empty desktop instead of the saved session.
        shell = ShellController(if (diagnostics.safeMode) com.chronos.pocketos.core.window.DesktopState() else ShellController.decodeSession(settings.loadSession()))
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
        runtime = com.chronos.pocketos.runtime.RuntimeManager(this, createRuntimeBackend(), appScope, { capabilities.capabilities.value.abis }, home.root)
        library = com.chronos.pocketos.library.LibraryRepository(File(filesDir, "library.json"))
        imports = com.chronos.pocketos.library.ImportManager(this, library, runtime, appScope, {
            com.chronos.pocketos.core.library.RuntimeProfile.safeDefault(capabilities.decision.value.profile)
        })
        imports.cleanupTemp() // crash recovery: remove half-finished imports
        appScope.launch { runtime.state.collect { if (it !is com.chronos.pocketos.core.runtime.RuntimeState.Installing) { imports.refreshAll(); diagnostics.add("runtime", it.toString()) } } }
        com.chronos.pocketos.ui.apps.FileOpenHooks.onWindowsExecutable = { _, uri, name ->
            imports.begin(uri, name)
            shell.openApp(com.chronos.pocketos.shell.AppIds.WINDOWS_APPS)
        }
        appScope.launch {
            var lastChoice = settings.settings.value.profileChoice
            settings.settings.collect { if (it.profileChoice != lastChoice) { lastChoice = it.profileChoice; capabilities.recheck("Profile changed") } }
        }
        appScope.launch { displays.displays.collect { capabilities.recheck("Displays changed") } }
        appScope.launch { capabilities.decision.collect {
            shell.maxRecommendedWindows = it.settings.maxRecommendedWindows
            diagnostics.add("profile", "${it.profile.label} (${capabilities.lastRecheckReason}): ${it.reasons.joinToString(" ")}")
        } }
        appScope.launch { displays.state.collect { s -> s.lastEvent?.let { diagnostics.add("display", it) } } }
        appScope.launch { input.summary.collect { diagnostics.add("input", "Keyboards ${it.keyboards.size}, mice ${it.mice.size}, controllers ${it.gamepads.size}") } }
        connectWindowsPrograms()
        seedHome()
    }

    /**
     * A running Windows program lives in a PocketOS window: the window opens when the program starts, closing it ends
     * the program, and when the program ends by itself the window closes and PocketOS reports what happened.
     */
    private fun connectWindowsPrograms() {
        val programApp = com.chronos.pocketos.shell.AppIds.WINDOWS_PROGRAM
        appScope.launch {
            var shownSession: String? = null
            runtime.active.collect { s ->
                if (s != null && s.sessionId != shownSession) {
                    shownSession = s.sessionId
                    shell.showWindowsProgram(s.title)
                    diagnostics.add("runtime", "Started ${s.title}")
                }
                if (s == null) shownSession = null
            }
        }
        shell.onWindowClosed = { w -> if (w.appId == programApp) runtime.stopSession() }
        runtime.onSessionEnded = { s ->
            shell.closeApp(programApp)
            diagnostics.add("runtime", "Ended ${s.title}")
            imports.onReturnFromRuntime()?.let(shell::showMessage)
        }
        cursor.keyInterceptor = { routeKeyToWindows(it) }
    }

    /** The running Windows program, if its window is the focused, visible window. */
    fun focusedWindowsProgram(): com.chronos.pocketos.runtime.RuntimeSession? {
        val s = runtime.active.value ?: return null
        val w = shell.desktop.value.focused ?: return null
        if (w.appId != com.chronos.pocketos.shell.AppIds.WINDOWS_PROGRAM || w.mode == com.chronos.pocketos.core.window.WindowMode.MINIMIZED) return null
        if (shell.ui.value.launcherOpen || shell.ui.value.switcherOpen) return null
        return s.handle
    }

    /**
     * Gives a key to the focused Windows program. PocketOS keeps the Windows/Meta key chords and Alt+Tab (to reach
     * its own launcher and windows) and the Back key; everything else goes to the program.
     */
    fun routeKeyToWindows(event: android.view.KeyEvent): Boolean {
        if (companionTextFocused) return false
        val target = focusedWindowsProgram() ?: return false
        val code = event.keyCode
        val pocketKey = event.isMetaPressed || code == android.view.KeyEvent.KEYCODE_META_LEFT ||
            code == android.view.KeyEvent.KEYCODE_META_RIGHT || code == android.view.KeyEvent.KEYCODE_BACK ||
            code == android.view.KeyEvent.KEYCODE_HOME || (event.isAltPressed && code == android.view.KeyEvent.KEYCODE_TAB)
        if (pocketKey) return false
        return target.sendKey(event)
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
        if (::diagnostics.isInitialized) diagnostics.add("memory", "onTrimMemory level $level")
    }

    fun saveSession() {
        settings.saveSession(shell.encodeSession())
        if (::diagnostics.isInitialized) diagnostics.save()
    }
}

val LocalPocketOs = staticCompositionLocalOf<PocketOsApp> { error("PocketOsApp not provided") }
