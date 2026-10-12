package com.chronos.pocketos

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.chronos.pocketos.core.display.SessionPhase
import com.chronos.pocketos.display.DesktopPresentation
import com.chronos.pocketos.ui.apps.AppContent
import com.chronos.pocketos.ui.desktop.Desktop
import com.chronos.pocketos.ui.input.CompanionScreen
import com.chronos.pocketos.ui.input.FloatingTouchpad
import com.chronos.pocketos.ui.input.PhoneInputExtras
import com.chronos.pocketos.ui.theme.PocketOsTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pocket get() = application as PocketOsApp
    private var presentation: DesktopPresentation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pocket.displays.state.value.phase == SessionPhase.ACTIVE) { moveTaskToBack(true); return }
                if (!pocket.shell.handleBack()) moveTaskToBack(true)
            }
        })
        lifecycleScope.launch {
            pocket.displays.state.collect { s -> syncPresentation(if (s.phase == SessionPhase.ACTIVE) s.displayId else null) }
        }
        setContent {
            val settings by pocket.settings.settings.collectAsState()
            val decision by pocket.capabilities.decision.collectAsState()
            val session by pocket.displays.state.collectAsState()
            LaunchedEffect(session.lastEvent) { session.lastEvent?.let { pocket.shell.showMessage(it) } }
            CompositionLocalProvider(LocalPocketOs provides pocket) {
                PocketOsTheme(settings.theme) {
                    if (session.phase == SessionPhase.ACTIVE) {
                        CompanionScreen(Modifier.fillMaxSize().safeDrawingPadding())
                    } else {
                        Desktop(
                            controller = pocket.shell,
                            settings = settings,
                            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                            cursor = pocket.cursor,
                            targetId = "phone",
                            targetPriority = 0,
                            onExit = { pocket.saveSession(); finishAndRemoveTask() },
                            statusExtras = { PhoneInputExtras() },
                            overlay = { FloatingTouchpad() },
                            effects = decision.settings,
                        ) { w -> AppContent(w) }
                        if (!settings.onboardingCompleted) {
                            com.chronos.pocketos.ui.OnboardingTour { pocket.settings.update { it.copy(onboardingCompleted = true) } }
                        }
                    }
                }
            }
        }
    }

    /** Shows or dismisses the external desktop. Failures fall back to the phone desktop with an explanation. */
    private fun syncPresentation(displayId: Int?) {
        val current = presentation
        if (displayId == null) {
            current?.dismiss(); presentation = null
            return
        }
        if (current != null && current.display.displayId == displayId && current.isShowing) return
        current?.dismiss(); presentation = null
        val display = pocket.displays.display(displayId)
        if (display == null) { pocket.displays.reportFailure("display $displayId is no longer available"); return }
        try {
            val p = DesktopPresentation(this, display)
            p.setOnDismissListener { if (presentation === p) presentation = null }
            p.show()
            presentation = p
        } catch (e: WindowManager.InvalidDisplayException) {
            pocket.displays.reportFailure(e.message ?: "the display rejected the window")
        } catch (e: RuntimeException) {
            pocket.displays.reportFailure(e.message ?: e.javaClass.simpleName)
        }
    }

    // Activity.dispatchKeyEvent is public platform API; lint flags only androidx.core's @RestrictTo override (false positive).
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (pocket.keys.handle(event)) return true
        // While the desktop is on the monitor, typing on a physical keyboard goes to the external desktop.
        if (presentation?.isShowing == true && !pocket.companionTextFocused) {
            if (pocket.cursor.forwardKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        pocket.saveSession()
    }

    override fun onDestroy() {
        presentation?.dismiss(); presentation = null
        super.onDestroy()
    }
}
