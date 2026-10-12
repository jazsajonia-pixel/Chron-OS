package com.chronos.pocketos.display

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.PocketOsApp
import com.chronos.pocketos.core.display.DesktopScale
import com.chronos.pocketos.ui.apps.AppContent
import com.chronos.pocketos.ui.desktop.Desktop
import com.chronos.pocketos.ui.theme.PocketOsTheme

/**
 * Shows the PocketOS desktop on an external display using Android's Presentation API.
 * The desktop state is owned by [PocketOsApp.shell], so dismissing this window never loses windows or files.
 */
class DesktopPresentation(private val activity: ComponentActivity, display: Display) : Presentation(activity, display) {
    private val pocket = activity.application as PocketOsApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metrics = context.resources.displayMetrics
        val view = ComposeView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        window?.decorView?.let { d ->
            d.setViewTreeLifecycleOwner(activity)
            d.setViewTreeSavedStateRegistryOwner(activity)
            d.setViewTreeViewModelStoreOwner(activity)
        }
        view.setViewTreeLifecycleOwner(activity)
        view.setViewTreeSavedStateRegistryOwner(activity)
        view.setViewTreeViewModelStoreOwner(activity)
        view.setContent {
            val settings by pocket.settings.settings.collectAsState()
            val profileScale = pocket.uiScaleForExternal(settings)
            val density = DesktopScale.densityFor(metrics.widthPixels, metrics.densityDpi, profileScale)
            CompositionLocalProvider(
                LocalPocketOs provides pocket,
                LocalActivityResultRegistryOwner provides activity,
                LocalDensity provides Density(density, LocalDensity.current.fontScale),
            ) {
                PocketOsTheme(settings.theme) {
                    Desktop(
                        controller = pocket.shell,
                        settings = settings,
                        cursor = pocket.cursor,
                        targetId = "external",
                        targetPriority = 10,
                    ) { w -> AppContent(w) }
                }
            }
        }
        setContentView(view)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = pocket.keys.handle(event) || super.dispatchKeyEvent(event)
}
