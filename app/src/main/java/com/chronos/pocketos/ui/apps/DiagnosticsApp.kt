package com.chronos.pocketos.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.diag.StorageManager
import com.chronos.pocketos.ui.components.KeyValue
import com.chronos.pocketos.ui.components.SectionTitle
import com.chronos.pocketos.ui.components.formatBytes
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsApp() {
    val app = LocalPocketOs.current
    val context = LocalContext.current
    val storage = remember { StorageManager(app) }
    var refresh by remember { mutableIntStateOf(0) }
    val usage = remember(refresh) { storage.usage() }
    val events = remember(refresh) { app.diagnostics.log.snapshot().takeLast(60).reversed() }
    val lib by app.library.state.collectAsState()
    var reportEntry by remember { mutableStateOf<String?>(null) }
    var reportLog by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("diag_root")) {
        SectionTitle("Health")
        app.diagnostics.previousCrash?.let {
            Text("PocketOS closed unexpectedly last time. The details below can help when reporting it.", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("diag_crash"))
        }
        if (app.diagnostics.safeMode) Text("Safe start: your last desktop layout was not restored because PocketOS crashed repeatedly.", modifier = Modifier.testTag("diag_safe"))
        if (app.diagnostics.previousCrash == null && !app.diagnostics.safeMode) Text("No problems recorded since the last start.", style = MaterialTheme.typography.bodyMedium)

        SectionTitle("Storage")
        KeyValue("PocketOS Home", formatBytes(usage.homeBytes))
        KeyValue("Temporary files", formatBytes(usage.tempImportBytes + usage.reportBytes))
        KeyValue("Imported programs", formatBytes(usage.programsBytes))
        KeyValue("Windows runtime (incl. installed programs)", if (usage.runtimeBytes > 0) formatBytes(usage.runtimeBytes) else "Not set up")
        if (usage.orphanFolders.isNotEmpty()) KeyValue("Leftover program folders", usage.orphanFolders.joinToString())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { val f = storage.clearTemporary(); app.shell.showMessage("Freed ${formatBytes(f)}"); refresh++ }, modifier = Modifier.testTag("diag_clear_temp")) { Text("Clear temporary files") }
            if (usage.orphanFolders.isNotEmpty()) OutlinedButton(onClick = { val n = storage.deleteOrphans(); app.shell.showMessage("Removed $n leftover folders"); refresh++ }, modifier = Modifier.testTag("diag_orphans")) { Text("Remove leftover folders") }
        }
        Text("To free the space of an installed program, run its uninstaller from the Windows desktop (PC Apps \u2192 Runtime).", style = MaterialTheme.typography.bodySmall)

        SectionTitle("Compatibility report")
        Text("Creates a text report about one attempt: device, runtime, program details and recognised errors. Personal details such as names in paths, e-mail addresses and keys are removed. You preview it before sharing.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(reportEntry == null, { reportEntry = null }, { Text("Device only") })
            lib.entries.forEach { e -> FilterChip(reportEntry == e.id, { reportEntry = e.id }, { Text(e.displayName) }, modifier = Modifier.testTag("diag_report_entry")) }
        }
        OutlinedTextField(reportLog, { reportLog = it }, label = { Text("Optional: paste runtime log text") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth().testTag("diag_report_log"))
        Button(onClick = { preview = storage.buildReport(reportEntry, reportLog.ifBlank { null }) }, modifier = Modifier.padding(top = 6.dp).testTag("diag_preview")) { Text("Preview report") }

        SectionTitle("Event log")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refresh++ }) { Text("Refresh") }
            OutlinedButton(onClick = { app.diagnostics.clear(); refresh++ }, modifier = Modifier.testTag("diag_clear_log")) { Text("Clear log") }
        }
        if (events.isEmpty()) Text("The log is empty.", modifier = Modifier.testTag("diag_log_empty"))
        Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.padding(8.dp)) {
                events.forEach { e -> Text("${fmt.format(Date(e.at))} [${e.area}] ${e.text}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("diag_event")) }
            }
        }
        Text("The log stays on this phone unless you share a report.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }

    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Report preview") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("diag_report_text")) } },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val rel = storage.saveToHome(text); app.shell.showMessage("Saved to Home/$rel"); preview = null
                    }, modifier = Modifier.testTag("diag_save")) { Text("Save to Home") }
                    TextButton(onClick = {
                        runCatching { context.startActivity(storage.shareIntent(text, reportEntry?.let { app.library.get(it)?.displayName })) }
                            .onFailure { app.shell.showMessage("No app can receive the report") }
                        preview = null
                    }, modifier = Modifier.testTag("diag_share")) { Text("Share…") }
                }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
        )
    }
}
