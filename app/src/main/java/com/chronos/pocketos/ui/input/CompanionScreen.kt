package com.chronos.pocketos.ui.input

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.core.display.ExternalDisplayStatus
import com.chronos.pocketos.core.input.ShellCommand

/** Phone screen while the desktop is on an external display: touchpad plus companion controls. */
@Composable
fun CompanionScreen(modifier: Modifier = Modifier) {
    val app = LocalPocketOs.current
    val state by app.displays.state.collectAsState()
    val settings by app.settings.settings.collectAsState()
    val input by app.input.summary.collectAsState()
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val display = (state.status as? ExternalDisplayStatus.Available)?.display

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp).testTag("companion")) {
        Text("Desktop is on ${display?.name ?: "the external display"}", style = MaterialTheme.typography.titleMedium)
        Text(
            buildString {
                display?.let { append("${it.widthPx}×${it.heightPx} · ") }
                append(if (input.hasMouse) "Mouse connected" else "Use this screen as a touchpad")
                if (input.hasKeyboard) append(" · Keyboard connected")
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = { app.keys.execute(ShellCommand.OPEN_LAUNCHER) }, modifier = Modifier.testTag("companion_launcher")) { Text("Launcher") }
            FilledTonalButton(onClick = { app.keys.execute(ShellCommand.NEXT_WINDOW); app.shell.hideSwitcher() }) { Text("Next window") }
            FilledTonalButton(onClick = { typing = !typing }, modifier = Modifier.testTag("companion_keyboard")) { Text(if (typing) "Hide keyboard" else "Keyboard") }
            OutlinedButton(onClick = { app.displays.stopSession() }, modifier = Modifier.testTag("companion_stop")) { Text("Show desktop on phone") }
        }
        if (typing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("Type, then Send") }, modifier = Modifier.weight(1f).onFocusChanged { app.companionTextFocused = it.isFocused }.testTag("companion_text"))
                Button(onClick = { app.cursor.typeText(text); text = "" }, modifier = Modifier.testTag("companion_send")) { Text("Send") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { app.cursor.pressKey(KeyEvent.KEYCODE_DEL) }) { Text("⌫") }
                OutlinedButton(onClick = { app.cursor.pressKey(KeyEvent.KEYCODE_ENTER) }) { Text("Enter") }
                OutlinedButton(onClick = { app.cursor.pressKey(KeyEvent.KEYCODE_TAB) }) { Text("Tab") }
                OutlinedButton(onClick = { app.cursor.pressKey(KeyEvent.KEYCODE_ESCAPE) }) { Text("Esc") }
            }
        }
        TouchpadSurface(
            app.cursor, settings.touchpadClickButtons, Modifier.weight(1f).padding(top = 8.dp),
            hint = "Touchpad · one finger moves · tap clicks · two fingers scroll",
        )
    }
}
