package com.chronos.pocketos.ui.apps

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.core.settings.ProfileChoice
import com.chronos.pocketos.ui.components.ChoiceRow
import com.chronos.pocketos.ui.components.KeyValue
import com.chronos.pocketos.ui.components.SectionTitle
import com.chronos.pocketos.ui.components.SwitchRow

@Composable
fun PerformanceSettingsSection() {
    val app = LocalPocketOs.current
    val s by app.settings.settings.collectAsState()
    val d by app.capabilities.decision.collectAsState()
    SectionTitle("Performance")
    ChoiceRow("Profile", ProfileChoice.entries, s.profileChoice, {
        when (it) {
            ProfileChoice.AUTO -> "Automatic"; ProfileChoice.BATTERY_SAVER -> "Battery Saver"
            ProfileChoice.BALANCED -> "Balanced"; ProfileChoice.PERFORMANCE -> "Performance"
        }
    }, tag = "profile_choice") { v -> app.settings.update { it.copy(profileChoice = v) } }
    Text(
        "Using ${d.profile.label}" + (if (d.automatic) " (chosen automatically)" else "") + ". " + d.reasons.joinToString(" "),
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("profile_explanation"),
    )
    d.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    val e = d.settings
    KeyValue("Window shadows", if (e.windowShadows && !s.reduceMotion) "On" else "Off")
    KeyValue("Animations", if (e.animations && !s.reduceMotion) "On" else "Off")
    KeyValue("Comfortable number of windows", "Up to ${e.maxRecommendedWindows}")
    SwitchRow("Reduce motion and effects", "Turns off animations and shadows regardless of profile", s.reduceMotion) { v ->
        app.settings.update { it.copy(reduceMotion = v) }
    }
}
