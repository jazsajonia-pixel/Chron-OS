package com.chronos.pocketos.ui.apps

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.PocketOsApp
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.runtime.RuntimeLogTranslator
import com.chronos.pocketos.core.runtime.RuntimeState
import com.chronos.pocketos.core.runtime.SampleResult
import com.chronos.pocketos.ui.components.KeyValue
import com.chronos.pocketos.ui.components.SectionTitle

/** Reports why a Windows program could not start (it opens in its own PocketOS window when it does). */
internal fun PocketOsApp.reportLaunch(result: com.chronos.pocketos.runtime.RuntimeManager.LaunchResult) {
    when (result) {
        is com.chronos.pocketos.runtime.RuntimeManager.LaunchResult.Busy -> {
            shell.showMessage("${result.running.title} is still running. Close its window first.")
            shell.showWindowsProgram(result.running.title)
        }
        is com.chronos.pocketos.runtime.RuntimeManager.LaunchResult.NotReady -> shell.showMessage(result.message)
        is com.chronos.pocketos.runtime.RuntimeManager.LaunchResult.Started -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuntimeSection() {
    val app = LocalPocketOs.current
    val rm = app.runtime
    val context = LocalContext.current
    val state by rm.state.collectAsState()
    val sample by rm.sample.collectAsState()
    val decision by app.capabilities.decision.collectAsState()
    val profile = RuntimeProfile.safeDefault(decision.profile)
    var log by remember { mutableStateOf("") }

    SectionTitle("Windows runtime")
    Text(
        "PocketOS runs Windows programs itself, with a built-in runtime (${rm.runtime.engine}; ${rm.runtime.license}). " +
            "No other app is needed. Many programs work, but not all: results depend on the program and your phone.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val statusText = when (val s = state) {
        RuntimeState.Ready -> "Ready"
        RuntimeState.NotInstalled -> "Not set up yet"
        is RuntimeState.Installing -> "${s.stage}… ${s.percent}%"
        is RuntimeState.Failed -> "Setup failed: ${s.message}"
        is RuntimeState.Unsupported -> s.reason
    }
    Text(statusText, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp).testTag("runtime_status"))
    (state as? RuntimeState.Installing)?.let {
        LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("runtime_progress"))
        Text("You can keep using PocketOS while this runs. It only happens once.", style = MaterialTheme.typography.bodySmall)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            RuntimeState.NotInstalled, is RuntimeState.Failed -> Button(
                onClick = { rm.install(profile) { ok -> app.shell.showMessage(if (ok) "The Windows runtime is ready." else "Setting up the Windows runtime failed.") } },
                modifier = Modifier.testTag("runtime_install"),
            ) { Text(if (state is RuntimeState.Failed) "Try again" else "Set up (about ${rm.runtime.installedBytes / (1024 * 1024)} MB)") }
            RuntimeState.Ready -> {
                Button(onClick = { app.reportLaunch(rm.launchDesktop(profile)) }, modifier = Modifier.testTag("runtime_desktop")) { Text("Open Windows desktop") }
                OutlinedButton(onClick = { rm.install(profile) }, modifier = Modifier.testTag("runtime_repair")) { Text("Repair") }
            }
            else -> {}
        }
    }
    if (state is RuntimeState.Ready) {
        KeyValue("Drive ${rm.runtime.programsDrive}:", "Programs you imported into PocketOS")
        KeyValue("Drive ${rm.runtime.homeDrive}:", "Your PocketOS Home folder (open and save files from Windows programs)")
        KeyValue("Drive C:", "Windows itself; installers put programs here")
    }

    SectionTitle("Self-test")
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Runs a tiny Windows program made for PocketOS. When it really runs it leaves a note with a fresh code, " +
                    "which PocketOS reads back. This proves Windows programs start on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { app.reportLaunch(rm.launchSample(profile)) },
                    enabled = state is RuntimeState.Ready, modifier = Modifier.testTag("sample_run"),
                ) { Text(if (sample.staged) "Run self-test again" else "Run self-test") }
                OutlinedButton(onClick = { rm.checkSample() }, enabled = sample.staged, modifier = Modifier.testTag("sample_check")) { Text("Check result") }
            }
            val resultText = when (sample.result) {
                SampleResult.VERIFIED -> "Passed: the sample ran in ${rm.runtime.name}" + (sample.runtimeVersion?.let { " ($it)" } ?: "") + " on this phone."
                SampleResult.NOT_RUN_YET -> "No result yet. If the self-test window is still open, wait a moment, then check again."
                SampleResult.STALE_OR_FOREIGN -> "The note found is from an older run. Run the self-test again."
                null -> null
            }
            resultText?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp).testTag("sample_result")) }
        }
    }

    SectionTitle("Using Windows programs")
    listOf(
        "Tap a program in Library, then Run (or Install for installers). It opens in a PocketOS window on your desktop; you can move, resize, maximize and minimize it like any other window.",
        "Tap or click inside the window to click in the program. Use the Smart Cursor or a mouse for right-click and scrolling, and \"Keyboard\" on the window's bar to type.",
        "Closing the window (or \"Stop\") ends the program. Programs that an installer added appear in your library automatically.",
    ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }

    SectionTitle("Explain a runtime error")
    OutlinedTextField(log, { log = it }, label = { Text("Paste error text from a program") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("log_input"))
    val errors = remember(log) { RuntimeLogTranslator.translate(log) }
    if (log.isNotBlank() && errors.isEmpty()) Text("No known problem found in this text.", style = MaterialTheme.typography.bodySmall)
    errors.forEach { e ->
        Column(Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) {}.testTag("log_error")) {
            Text(e.title, style = MaterialTheme.typography.titleSmall)
            Text(e.advice, style = MaterialTheme.typography.bodySmall)
        }
    }
}
