package com.chronos.pocketos.ui.apps

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.core.display.ExternalDisplayPolicy
import com.chronos.pocketos.core.display.ExternalDisplayStatus
import com.chronos.pocketos.core.display.SessionPhase
import com.chronos.pocketos.ui.components.KeyValue
import com.chronos.pocketos.ui.components.SectionTitle
import com.chronos.pocketos.ui.components.SliderRow
import com.chronos.pocketos.ui.components.SwitchRow

@Composable
fun DisplaySettingsSection() {
    val app = LocalPocketOs.current
    val s by app.settings.settings.collectAsState()
    val state by app.displays.state.collectAsState()
    val displays by app.displays.displays.collectAsState()
    SectionTitle("External display")
    Text(ExternalDisplayPolicy.explain(state.status), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("display_status"))
    Row(Modifier.padding(vertical = 6.dp)) {
        if (state.phase == SessionPhase.ACTIVE) {
            OutlinedButton(onClick = { app.displays.stopSession() }) { Text("Show desktop on phone") }
        } else if (state.status is ExternalDisplayStatus.Available) {
            Button(onClick = { app.displays.startSession() }, modifier = Modifier.testTag("display_start")) { Text("Show desktop on monitor") }
        }
        OutlinedButton(onClick = { app.displays.refresh("Manual re-check") }, modifier = Modifier.padding(start = 8.dp)) { Text("Check again") }
    }
    SwitchRow("Start automatically", "Open the desktop on a compatible monitor as soon as it connects", s.autoStartDesktopOnExternalDisplay) { v ->
        app.settings.update { it.copy(autoStartDesktopOnExternalDisplay = v) }
    }
    SliderRow("Desktop size on monitor", s.uiScaleOverride ?: 1f, 0.75f..1.6f, String.format("%.0f%%", (s.uiScaleOverride ?: 1f) * 100)) { v ->
        app.settings.update { it.copy(uiScaleOverride = v) }
    }
    displays.forEach { d ->
        KeyValue(
            if (d.isDefault) "Phone screen" else "Display ${d.id}",
            "${d.name} · ${d.widthPx}×${d.heightPx} · ${d.densityDpi} dpi · ${d.refreshRate.toInt()} Hz" +
                (if (!d.isDefault) " · " + listOfNotNull(if (d.isPresentation) "presentation" else "no presentation", if (d.isPrivate) "private" else null).joinToString() else ""),
        )
    }
    KeyValue("Apps on secondary displays", if (app.displays.supportsActivitiesOnSecondaryDisplays) "Supported by this device" else "Not reported")
    Text(
        "PocketOS uses Android's Presentation API. Video output depends on your phone's hardware: not every USB-C port " +
            "carries video. If the phone only mirrors its screen, the monitor is not exposed to apps and PocketOS cannot show a separate desktop.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
