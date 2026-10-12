package com.chronos.pocketos.ui.apps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.chronos.pocketos.BuildConfig
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.core.settings.ThemeMode
import com.chronos.pocketos.core.settings.Wallpaper
import com.chronos.pocketos.ui.components.ChoiceRow
import com.chronos.pocketos.ui.components.ConfirmDialog
import com.chronos.pocketos.ui.components.SectionTitle
import com.chronos.pocketos.ui.components.SwitchRow

/** Extra settings sections contributed by other modules (input, display, performance, privacy). */
object SettingsSections {
    val sections = mutableListOf<Pair<String, @Composable () -> Unit>>()
}

@Composable
fun SettingsApp() {
    val app = LocalPocketOs.current
    val s by app.settings.settings.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings_root")) {
        SectionTitle("Appearance")
        ChoiceRow("Theme", ThemeMode.entries, s.theme, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, tag = "theme") { v ->
            app.settings.update { it.copy(theme = v) }
        }
        ChoiceRow("Wallpaper", Wallpaper.entries, s.wallpaper, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, tag = "wallpaper") { v ->
            app.settings.update { it.copy(wallpaper = v) }
        }
        SwitchRow("Desktop icons", "Show app shortcuts on the desktop", s.showDesktopIcons, tag = "toggle_icons") { v -> app.settings.update { it.copy(showDesktopIcons = v) } }
        SwitchRow("24-hour clock", null, s.use24HourClock, tag = "toggle_24h") { v -> app.settings.update { it.copy(use24HourClock = v) } }
        SwitchRow("Reduce motion and shadows", "Fewer visual effects; saves a little battery", s.reduceMotion) { v -> app.settings.update { it.copy(reduceMotion = v) } }

        SettingsSections.sections.forEach { (title, content) ->
            SectionTitle(title)
            content()
        }

        SectionTitle("About")
        Text("PocketOS ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "PocketOS is an Android app that provides a desktop-style workspace. It is not a standalone operating system " +
                "and is not affiliated with Microsoft Windows.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.padding(top = 12.dp).testTag("settings_reset")) { Text("Reset settings to defaults") }
    }
    if (confirmReset) ConfirmDialog("Reset settings?", "Appearance, input and display preferences return to their defaults. Your files are not affected.", "Reset", { confirmReset = false }) {
        confirmReset = false; app.settings.resetToDefaults()
    }
}
