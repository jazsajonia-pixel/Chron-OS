package com.chronos.pocketos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.chronos.pocketos.ui.apps.AppContent
import com.chronos.pocketos.ui.desktop.Desktop
import com.chronos.pocketos.ui.theme.PocketOsTheme

class MainActivity : ComponentActivity() {
    private val pocket get() = application as PocketOsApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!pocket.shell.handleBack()) moveTaskToBack(true)
            }
        })
        setContent {
            val settings by pocket.settings.settings.collectAsState()
            CompositionLocalProvider(LocalPocketOs provides pocket) {
                PocketOsTheme(settings.theme) {
                    Desktop(
                        controller = pocket.shell,
                        settings = settings,
                        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                        onExit = { pocket.saveSession(); finishAndRemoveTask() },
                    ) { w -> AppContent(w) }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        pocket.saveSession()
    }
}
