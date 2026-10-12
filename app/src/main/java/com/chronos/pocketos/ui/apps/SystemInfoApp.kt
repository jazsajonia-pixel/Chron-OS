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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.core.capability.Capability
import com.chronos.pocketos.core.capability.Thermal
import com.chronos.pocketos.ui.components.KeyValue
import com.chronos.pocketos.ui.components.SectionTitle
import com.chronos.pocketos.ui.components.formatBytes

/** Human-readable value of a capability, saying "Unknown"/"Not supported" instead of guessing. */
fun <T> Capability<T>.show(format: (T) -> String = { it.toString() }): String = when (this) {
    is Capability.Known -> format(value)
    is Capability.Unknown -> "Unknown ($reason)"
    is Capability.Unsupported -> "Not supported ($reason)"
}

@Composable
fun SystemInfoApp() {
    val app = LocalPocketOs.current
    val c by app.capabilities.capabilities.collectAsState()
    val d by app.capabilities.decision.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("sysinfo_root")) {
        SectionTitle("Device")
        KeyValue("Model", "${c.manufacturer} ${c.model}")
        KeyValue("Android", "${c.androidRelease} (API ${c.apiLevel})")
        KeyValue("CPU architectures", c.abis.joinToString().ifEmpty { "Unknown" })
        KeyValue("CPU cores", c.cpuCores.show())
        KeyValue("Graphics", "OpenGL ES ${c.openGlEs.show()} · Vulkan ${c.vulkan.show()}")
        SectionTitle("Memory and storage")
        KeyValue("Total memory", c.totalRamBytes.show(::formatBytes))
        KeyValue("Available memory", c.availableRamBytes.show(::formatBytes))
        KeyValue("App memory limit", c.memoryClassMb.show { "$it MB" })
        KeyValue("Low-memory device", if (c.lowRamDevice) "Yes" else "No")
        KeyValue("Free app storage", c.freeStorageBytes.show(::formatBytes))
        SectionTitle("Power")
        KeyValue("Battery Saver", c.powerSaveMode.show { if (it) "On" else "Off" })
        KeyValue("Temperature", c.thermalStatus.show(Thermal::label))
        SectionTitle("Screens")
        c.phoneScreen?.let { KeyValue("Phone screen", "${it.widthPx}×${it.heightPx} px · ${it.densityDpi} dpi") }
        KeyValue("External desktop", c.externalDisplay?.let { "${it.name} · ${it.widthPx}×${it.heightPx}" } ?: "None available")
        KeyValue("Apps on secondary displays", if (c.activitiesOnSecondaryDisplays) "Reported by device" else "Not reported")
        SectionTitle("Profile")
        Text("${d.profile.label}: ${d.reasons.joinToString(" ")}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("sysinfo_profile"))
        KeyValue("Last check", app.capabilities.lastRecheckReason)
        OutlinedButton(onClick = { app.capabilities.recheck("Checked manually") }, modifier = Modifier.padding(top = 8.dp).testTag("sysinfo_recheck")) { Text("Check again") }
        Text(
            "This information is read on your phone using standard Android settings. PocketOS does not send it anywhere.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
