package com.chronos.pocketos.ui.apps

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.chronos.pocketos.core.runtime.RuntimeAvailability
import com.chronos.pocketos.core.runtime.RuntimeLogTranslator
import com.chronos.pocketos.core.runtime.RuntimePath
import com.chronos.pocketos.core.runtime.SampleResult
import com.chronos.pocketos.ui.components.KeyValue
import com.chronos.pocketos.ui.components.SectionTitle

/** Human description of where a staged file is inside the runtime. */
fun RuntimePath.describe(): String = when (this) {
    is RuntimePath.Mapped -> windowsPath
    is RuntimePath.NeedsDrive -> "Add $androidPath as a drive in the runtime's container settings"
}

@Composable
fun RuntimeSection() {
    val app = LocalPocketOs.current
    val rm = app.runtime
    val context = LocalContext.current
    val availability by rm.availability.collectAsState()
    val store by rm.store.collectAsState()
    val sample by rm.sample.collectAsState()
    var log by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(rm::setWorkspace) }

    SectionTitle("Compatibility runtime")
    Text(
        "PocketOS does not include a Windows runtime. It works with ${rm.runtime.name} (${rm.runtime.license}, open source), " +
            "a separate app you install yourself. Many Windows programs will not run; results depend on the program and your phone.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val statusText = when (val a = availability) {
        is RuntimeAvailability.Installed -> "${rm.runtime.name} ${a.versionName ?: ""} is installed"
        RuntimeAvailability.NotInstalled -> "${rm.runtime.name} is not installed"
        is RuntimeAvailability.DeviceIncompatible -> a.reason
    }
    Text(statusText, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp).testTag("runtime_status"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (availability) {
            is RuntimeAvailability.Installed -> Button(onClick = {
                rm.launchIntent()?.let { runCatching { context.startActivity(it) }.onFailure { app.shell.showMessage("Could not open ${rm.runtime.name}") } }
                    ?: app.shell.showMessage("${rm.runtime.name} cannot be opened from here")
            }, modifier = Modifier.testTag("runtime_open")) { Text("Open ${rm.runtime.name}") }
            RuntimeAvailability.NotInstalled -> Button(onClick = {
                try { context.startActivity(rm.downloadPageIntent()) } catch (_: ActivityNotFoundException) { app.shell.showMessage("No browser available") }
            }, modifier = Modifier.testTag("runtime_get")) { Text("Get ${rm.runtime.name}") }
            is RuntimeAvailability.DeviceIncompatible -> {}
        }
        OutlinedButton(onClick = { rm.refresh() }, modifier = Modifier.testTag("runtime_refresh")) { Text("Check again") }
    }

    SectionTitle("Shared folder")
    Text(
        "Programs are copied to a folder both apps can see. Choose or create Download/PocketOS: ${rm.runtime.name} shows the " +
            "Download folder as drive ${rm.runtime.downloadsDrive}:.",
        style = MaterialTheme.typography.bodySmall,
    )
    KeyValue("Folder", store?.let { "${it.label} (${it.documentId})" } ?: "Not chosen")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val hint = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
            runCatching { picker.launch(hint) }.onFailure { app.shell.showMessage("No folder picker is available on this device") }
        }, modifier = Modifier.testTag("runtime_choose_folder")) { Text(if (store == null) "Choose folder" else "Change folder") }
        if (store != null) OutlinedButton(onClick = { rm.clearWorkspace() }) { Text("Forget folder") }
    }

    SectionTitle("Test with the sample program")
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "The sample is a tiny Windows program made for PocketOS. When it really runs, it leaves a note that PocketOS reads back. " +
                    "Nothing runs until you start it yourself inside ${rm.runtime.name}.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { runCatching { rm.stageSample() }.onFailure { app.shell.showMessage(it.message ?: "Could not copy the sample") } },
                    enabled = store != null, modifier = Modifier.testTag("sample_stage"),
                ) { Text(if (sample.staged) "Copy again" else "1. Copy sample") }
                OutlinedButton(onClick = { rm.checkSample() }, enabled = sample.staged, modifier = Modifier.testTag("sample_check")) { Text("3. Check result") }
            }
            sample.path?.let { Text("2. In ${rm.runtime.name}, open a container and run: ${it.describe()}", modifier = Modifier.padding(top = 6.dp).testTag("sample_path")) }
            val resultText = when (sample.result) {
                SampleResult.VERIFIED -> "Verified: the sample ran through ${rm.runtime.name}" + (sample.runtimeVersion?.let { " $it" } ?: "") + " on this phone."
                SampleResult.NOT_RUN_YET -> "Not run yet. No result note was found."
                SampleResult.STALE_OR_FOREIGN -> "The note found is from an older copy. Copy the sample again and rerun it."
                null -> null
            }
            resultText?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp).testTag("sample_result")) }
        }
    }

    SectionTitle("Explain a runtime error")
    OutlinedTextField(log, { log = it }, label = { Text("Paste log text from the runtime") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("log_input"))
    val errors = remember(log) { RuntimeLogTranslator.translate(log) }
    if (log.isNotBlank() && errors.isEmpty()) Text("No known problem found in this text.", style = MaterialTheme.typography.bodySmall)
    errors.forEach { e ->
        Column(Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) {}.testTag("log_error")) {
            Text(e.title, style = MaterialTheme.typography.titleSmall)
            Text(e.advice, style = MaterialTheme.typography.bodySmall)
        }
    }
}
