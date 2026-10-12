package com.chronos.pocketos.ui.apps

import androidx.compose.runtime.Composable
import com.chronos.pocketos.core.window.AppWindow
import com.chronos.pocketos.shell.AppIds

/** Maps a window's appId to its content. */
@Composable
fun AppContent(window: AppWindow) {
    when (window.appId) {
        AppIds.FILES -> FilesApp()
        AppIds.EDITOR -> TextEditorApp(window)
        AppIds.CALCULATOR -> CalculatorApp()
        AppIds.SETTINGS -> SettingsApp()
        AppIds.SYSINFO -> SystemInfoApp()
        AppIds.WINDOWS_APPS -> PcAppsApp(window)
        else -> UnknownApp(window)
    }
}
