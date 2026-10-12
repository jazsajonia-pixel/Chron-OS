package com.chronos.pocketos.ui.apps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.core.input.ShellCommand
import com.chronos.pocketos.core.input.ShortcutMap
import com.chronos.pocketos.core.settings.TouchpadVisibility
import com.chronos.pocketos.ui.components.ChoiceRow
import com.chronos.pocketos.ui.components.KeyValue
import com.chronos.pocketos.ui.components.SectionTitle
import com.chronos.pocketos.ui.components.SliderRow
import com.chronos.pocketos.ui.components.SwitchRow

@Composable
fun InputSettingsSection() {
    val app = LocalPocketOs.current
    val s by app.settings.settings.collectAsState()
    val input by app.input.summary.collectAsState()
    SectionTitle("Input and Smart Cursor")
    SwitchRow("Smart Cursor", "Show a pointer you control with the phone touchpad", s.showSmartCursor, tag = "toggle_cursor") { v -> app.settings.update { it.copy(showSmartCursor = v) } }
    SliderRow("Pointer speed", s.cursorSpeed, 0.25f..3f, String.format("%.1f×", s.cursorSpeed), tag = "slider_speed") { v -> app.settings.update { it.copy(cursorSpeed = v) } }
    SliderRow("Pointer size", s.cursorSizeScale, 0.75f..2.5f, String.format("%.1f×", s.cursorSizeScale)) { v -> app.settings.update { it.copy(cursorSizeScale = v) } }
    SwitchRow("Tap to click", null, s.tapToClick, tag = "toggle_tap") { v -> app.settings.update { it.copy(tapToClick = v) } }
    SwitchRow("Natural scrolling", "Content follows your fingers", s.naturalScroll) { v -> app.settings.update { it.copy(naturalScroll = v) } }
    SwitchRow("Click buttons under the touchpad", null, s.touchpadClickButtons) { v -> app.settings.update { it.copy(touchpadClickButtons = v) } }
    ChoiceRow("On-screen touchpad", TouchpadVisibility.entries, s.touchpadVisibility, {
        when (it) { TouchpadVisibility.AUTO -> "Auto"; TouchpadVisibility.ALWAYS -> "Always"; TouchpadVisibility.NEVER -> "Never" }
    }, tag = "touchpad_vis") { v -> app.settings.update { it.copy(touchpadVisibility = v) } }
    Text(
        "Auto hides the touchpad when a physical mouse is connected.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SectionTitle("Connected input devices")
    KeyValue("Keyboard", input.keyboards.joinToString().ifEmpty { "None detected" })
    KeyValue("Mouse / touchpad", input.mice.joinToString().ifEmpty { "None detected" })
    KeyValue("Game controller", input.gamepads.joinToString().ifEmpty { "None detected (optional)" })

    SectionTitle("Keyboard shortcuts")
    SwitchRow("Enable keyboard shortcuts", null, s.shortcutsEnabled, tag = "toggle_shortcuts") { v -> app.settings.update { it.copy(shortcutsEnabled = v) } }
    ShellCommand.entries.forEach { cmd ->
        val chords = ShortcutMap.chordsFor(cmd).joinToString("  or  ") { it.describe() }
        SwitchRow(cmd.label, chords, cmd.name !in s.disabledShortcuts, enabled = s.shortcutsEnabled) { on ->
            app.settings.update { it.copy(disabledShortcuts = if (on) it.disabledShortcuts - cmd.name else it.disabledShortcuts + cmd.name) }
        }
    }
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "Limitations: the Smart Cursor works only inside PocketOS. It cannot control other Android apps. " +
                "Some phones reserve Alt+Tab or Meta shortcuts for the system; use Ctrl+Alt+Arrow keys instead. " +
                "With a physical mouse, Android draws its own pointer and PocketOS hides the Smart Cursor.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
