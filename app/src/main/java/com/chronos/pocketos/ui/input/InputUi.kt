package com.chronos.pocketos.ui.input

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.core.input.TouchpadDecision
import com.chronos.pocketos.core.input.TouchpadPolicy
import com.chronos.pocketos.ui.desktop.StatusChip
import com.chronos.pocketos.ui.desktop.TaskbarHeight

/** Taskbar indicators on the phone desktop: physical input status and the touchpad toggle. */
@Composable
fun PhoneInputExtras() {
    val app = LocalPocketOs.current
    val input by app.input.summary.collectAsState()
    val settings by app.settings.settings.collectAsState()
    val ui by app.shell.ui.collectAsState()
    if (input.hasKeyboard) StatusChip("⌨", "Physical keyboard connected: ${input.keyboards.joinToString()}")
    if (input.hasMouse) StatusChip("🖱", "Physical mouse connected: ${input.mice.joinToString()}")
    val decision = TouchpadPolicy.decide(settings.touchpadVisibility, input)
    if (settings.showSmartCursor && decision == TouchpadDecision.SHOW) {
        Row(Modifier.testTag("touchpad_toggle")) {
            StatusChip(if (ui.touchpadOpen) "Touchpad ✓" else "Touchpad", "Show or hide the on-screen touchpad") {
                app.shell.setTouchpadOpen(!ui.touchpadOpen)
            }
        }
    }
}

/**
 * Floating touchpad shown in a separate popup window on the phone screen, so its own touches never collide with
 * the synthetic events PocketOS delivers to the desktop underneath.
 */
@Composable
fun FloatingTouchpad() {
    val app = LocalPocketOs.current
    val ui by app.shell.ui.collectAsState()
    val settings by app.settings.settings.collectAsState()
    if (!ui.touchpadOpen || !settings.showSmartCursor) return
    Popup(
        alignment = Alignment.BottomEnd,
        offset = androidx.compose.ui.unit.IntOffset(0, 0),
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp), tonalElevation = 8.dp, shadowElevation = 8.dp,
            modifier = Modifier.padding(end = 8.dp, bottom = TaskbarHeight + 8.dp).size(width = 300.dp, height = 250.dp).testTag("floating_touchpad"),
        ) {
            Column(Modifier.padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Smart Cursor", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    TextButton(onClick = { app.shell.setTouchpadOpen(false); app.cursor.hide() }) { Text("Close") }
                }
                TouchpadSurface(app.cursor, settings.touchpadClickButtons, Modifier.weight(1f))
            }
        }
    }
}
