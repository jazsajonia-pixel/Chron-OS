package com.chronos.pocketos.ui.apps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.core.library.AppStatus
import com.chronos.pocketos.core.library.GraphicsChoice
import com.chronos.pocketos.core.library.LibraryEntry
import com.chronos.pocketos.core.library.Recovery
import com.chronos.pocketos.core.library.RuntimeProfile
import com.chronos.pocketos.core.pe.FileKind
import com.chronos.pocketos.core.pe.PeReport
import com.chronos.pocketos.core.runtime.RuntimeState
import com.chronos.pocketos.library.ImportState
import com.chronos.pocketos.ui.components.KeyValue
import com.chronos.pocketos.ui.components.SectionTitle
import com.chronos.pocketos.ui.components.formatBytes
import java.text.DateFormat
import java.util.Date

@Composable
fun StatusChip(status: AppStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        AppStatus.READY -> Color(0xFF15803D); AppStatus.NEEDS_SETUP -> Color(0xFFB45309)
        AppStatus.UNSUPPORTED -> Color(0xFF6B7280); AppStatus.FAILED -> Color(0xFFB91C1C)
    }
    Text(
        status.label, color = Color.White, style = MaterialTheme.typography.labelMedium,
        modifier = modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp).testTag("status_${status.name}"),
    )
}

@Composable
fun LibrarySection(onShowRuntime: () -> Unit) {
    val app = LocalPocketOs.current
    val lib by app.library.state.collectAsState()
    val import by app.imports.state.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { app.imports.begin(it) } }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Your PC programs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Button(onClick = {
            runCatching { picker.launch(arrayOf("application/x-msdownload", "application/vnd.microsoft.portable-executable", "application/x-msi", "application/octet-stream", "*/*")) }
                .onFailure { app.shell.showMessage("No file picker is available on this device") }
        }, enabled = import is ImportState.Idle || import is ImportState.Done || import is ImportState.Failed, modifier = Modifier.testTag("lib_add")) { Text("Add program…") }
    }
    Text(
        "Add a .exe or .msi file. PocketOS checks it first without running it, keeps a private copy, and runs or installs it with the built-in Windows runtime.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ImportWizard(import, onShowRuntime)

    if (lib.entries.isEmpty() && import is ImportState.Idle) {
        Text("No programs yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp).testTag("lib_empty"))
    }
    lib.entries.sortedBy { it.displayName.lowercase() }.forEach { e ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selected = if (selected == e.id) null else e.id }.testTag("lib_entry")) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EntryIcon(e)
                    Column(Modifier.weight(1f)) {
                        Text(e.displayName, style = MaterialTheme.typography.titleSmall)
                        e.installedFromId?.let { from -> lib.entries.firstOrNull { it.id == from }?.let { Text("Installed by ${it.displayName}", style = MaterialTheme.typography.labelSmall) } }
                    }
                    if (e.status == AppStatus.READY) StartButton(e, compact = true)
                    StatusChip(e.status)
                }
                Text(e.statusDetail, style = MaterialTheme.typography.bodySmall)
                if (selected == e.id) EntryDetails(e, onShowRuntime) { selected = null }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportWizard(state: ImportState, onShowRuntime: () -> Unit) {
    val app = LocalPocketOs.current
    val runtimeState by app.runtime.state.collectAsState()
    when (state) {
        ImportState.Idle -> {}
        is ImportState.Reading -> WizardCard("Step 1 of 3 · Reading ${state.fileName}") {
            Progress(state.bytes, state.total)
            Text("Copying a private copy so it can be checked safely. Nothing is run.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = app.imports::cancel, modifier = Modifier.testTag("wiz_cancel")) { Text("Cancel") }
        }
        is ImportState.Inspected -> {
            val c = state.candidate
            val decision by app.capabilities.decision.collectAsState()
            var name by remember(c.sha256) { mutableStateOf(c.report.suggestedName ?: c.fileName.substringBeforeLast('.')) }
            var profile by remember(c.sha256) { mutableStateOf(RuntimeProfile.safeDefault(decision.profile)) }
            var advanced by remember { mutableStateOf(false) }
            WizardCard("Step 2 of 3 · What PocketOS found") {
                Findings(c.fileName, c.sizeBytes, c.report)
                state.duplicateOf?.let { Text("This exact file is already in your library as \"$it\".", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val blocked = state.blocked
                if (blocked != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        StatusChip(blocked.status); Text("  ${blocked.detail}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("wiz_blocked"))
                    }
                    blocked.nextSteps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = app.imports::cancel, modifier = Modifier.testTag("wiz_cancel")) { Text("Close") }
                        TextButton(onClick = { app.imports.confirm(name, profile, keepUnsupported = true) }, modifier = Modifier.testTag("wiz_keep")) { Text("Keep in library as unsupported") }
                    }
                } else {
                    SectionTitle("Step 3 of 3 · Add to library")
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("wiz_name"))
                    Text("Suggested runtime settings: ${profile.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    Text("Chosen for your ${decision.profile.label} profile. PocketOS applies them each time it starts the program.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("wiz_advanced")) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") }
                    if (advanced) {
                        Text("Resolution", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(800 to 600, 1024 to 768, 1280 to 720, 1920 to 1080).forEach { (w, h) ->
                                FilterChip(profile.width == w && profile.height == h, { profile = profile.copy(width = w, height = h) }, { Text("$w×$h") }, modifier = Modifier.testTag("wiz_res_$w"))
                            }
                        }
                        Text("Graphics", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GraphicsChoice.entries.forEach { g -> FilterChip(profile.graphics == g, { profile = profile.copy(graphics = g) }, { Text(g.label) }) }
                        }
                        if (profile.width > decision.settings.runtimeMaxWidth) {
                            Text("Higher than recommended for this phone; it may be slow.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (runtimeState !is RuntimeState.Ready) {
                        Text("The Windows runtime is not set up yet. You can add the program now and set up the runtime afterwards.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { app.imports.confirm(name, profile) }, modifier = Modifier.testTag("wiz_confirm")) { Text("Add to library") }
                        OutlinedButton(onClick = app.imports::cancel, modifier = Modifier.testTag("wiz_cancel")) { Text("Cancel") }
                    }
                }
            }
        }
        is ImportState.Copying -> WizardCard("Copying ${state.name} into PocketOS") {
            Progress(state.bytes, state.total)
            OutlinedButton(onClick = app.imports::cancel, modifier = Modifier.testTag("wiz_cancel")) { Text("Cancel") }
        }
        is ImportState.Done -> {
            val e = app.library.get(state.entryId)
            WizardCard("Added ${e?.displayName ?: "program"}") {
                if (e != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) { StatusChip(e.status); Text("  ${e.statusDetail}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("wiz_done_detail")) }
                    app.imports.assess(e).nextSteps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (e?.status == AppStatus.READY) {
                        StartButton(e, onStarted = app.imports::dismiss)
                    } else if (e?.status == AppStatus.NEEDS_SETUP) {
                        Button(onClick = onShowRuntime) { Text("Go to setup") }
                    }
                    OutlinedButton(onClick = app.imports::dismiss, modifier = Modifier.testTag("wiz_close")) { Text("Close") }
                }
            }
        }
        is ImportState.Failed -> WizardCard("Something went wrong") {
            Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("wiz_failed"))
            state.nextSteps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = app.imports::dismiss, modifier = Modifier.testTag("wiz_close")) { Text("Close") }
        }
    }
}

@Composable
private fun WizardCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("wizard")) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
            content()
        }
    }
}

@Composable
private fun Progress(bytes: Long, total: Long?) {
    if (total != null && total > 0) LinearProgressIndicator(progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(formatBytes(bytes) + (total?.let { " of ${formatBytes(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Findings(fileName: String, size: Long, r: PeReport) {
    Column(Modifier.semantics(mergeDescendants = true) {}.testTag("wiz_findings")) {
        Text("The file was examined, not run.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KeyValue("File", "$fileName · ${formatBytes(size)}")
        val type = when (r.kind) {
            FileKind.WINDOWS_PROGRAM -> if (r.isInstaller) "Installer (${r.installer.label})" else "Windows ${r.subsystem.label}"
            FileKind.MSI_PACKAGE -> "Windows Installer package (.msi)"
            FileKind.WINDOWS_LIBRARY -> "Windows library (DLL)"
            FileKind.DOS_PROGRAM -> "DOS program"; FileKind.WIN16_PROGRAM -> "16-bit Windows program"
            FileKind.ARCHIVE -> "ZIP archive"; FileKind.SCRIPT -> "Script"; FileKind.NOT_WINDOWS -> "Not a Windows program"; FileKind.DAMAGED -> "Damaged or unreadable"
        }
        KeyValue("Type", type)
        if (r.kind == FileKind.WINDOWS_PROGRAM || r.kind == FileKind.WINDOWS_LIBRARY) KeyValue("Made for", r.machine.label)
        r.version.productName?.let { KeyValue("Product", it) }
        r.version.companyName?.let { KeyValue("Publisher (unverified)", it) }
        (r.version.productVersion ?: r.version.fileVersion)?.let { KeyValue("Version", it) }
        r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        Text("Whether it works can only be known by trying it.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EntryDetails(e: LibraryEntry, onShowRuntime: () -> Unit, onRemoved: () -> Unit) {
    val app = LocalPocketOs.current
    val runtimeState by app.runtime.state.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val restage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { app.imports.stageFromUri(e.id, it) } }
    val a = app.imports.assess(e)
    Column(Modifier.padding(top = 8.dp).testTag("entry_details")) {
        a.nextSteps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        e.stagedPath?.let { rel -> KeyValue("In Windows", app.runtime.windowsPathFor(rel)) }
        KeyValue("Settings", e.profile.label + (if (e.knownGood == e.profile) " (worked before)" else ""))
        if (e.shortcutPath == null) KeyValue("File", "${e.sourceFileName} · ${formatBytes(e.sizeBytes)} · SHA-256 ${e.sha256.take(12)}…")
        e.lastRunAt?.let { KeyValue("Last started", DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) + (e.lastExitCode?.let { c -> " · exit code $c" } ?: "")) }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (e.status != AppStatus.UNSUPPORTED) {
                if (runtimeState is RuntimeState.Ready) StartButton(e)
                else OutlinedButton(onClick = onShowRuntime, modifier = Modifier.testTag("entry_setup_runtime")) { Text("Set up runtime") }
                if (e.stagedPath == null && e.shortcutPath == null) {
                    OutlinedButton(onClick = { runCatching { restage.launch(arrayOf("*/*")) } }, modifier = Modifier.testTag("entry_stage")) { Text("Copy file again…") }
                }
                if (e.isInstaller && e.installerRan && runtimeState is RuntimeState.Ready) {
                    OutlinedButton(onClick = {
                        val found = app.imports.findInstalledPrograms(e.id)
                        app.shell.showMessage(if (found.isEmpty()) "No new programs found." else "Added: ${found.joinToString { it.displayName }}")
                    }, modifier = Modifier.testTag("entry_find_installed")) { Text("Find installed programs") }
                }
                if (e.lastRunAt != null || e.userResult != null) {
                    OutlinedButton(onClick = { app.imports.reportWorked(e.id) }, modifier = Modifier.testTag("entry_worked")) { Text("It worked") }
                    OutlinedButton(onClick = { app.imports.reportFailed(e.id) }, modifier = Modifier.testTag("entry_failed")) { Text("It didn't work") }
                }
                if (e.status == AppStatus.FAILED && e.recoveryStep < Recovery.MAX_STEPS) {
                    Button(onClick = { app.imports.applyNextRecovery(e.id) }, modifier = Modifier.testTag("entry_next_fix")) { Text("Try the next fix (${e.recoveryStep + 1} of ${Recovery.MAX_STEPS})") }
                }
            }
            TextButton(onClick = { showLog = !showLog }, modifier = Modifier.testTag("entry_log")) { Text(if (showLog) "Hide log" else "Log") }
            TextButton(onClick = { confirmRemove = true }, modifier = Modifier.testTag("entry_remove")) { Text("Remove") }
        }
        if (showLog) {
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            e.log.takeLast(30).forEach { Text("${fmt.format(Date(it.at))}  ${it.text}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("entry_log_line")) }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${e.displayName}?") },
            text = { Text(if (e.shortcutPath != null) "This removes the shortcut from PocketOS. The program stays installed in Windows until you run its uninstaller." else "This removes it from PocketOS. Programs it installed stay in your library.") },
            confirmButton = {
                Row {
                    TextButton(onClick = { confirmRemove = false; app.shell.showMessage(app.imports.remove(e.id, deleteFiles = false)); onRemoved() }, modifier = Modifier.testTag("remove_keep_files")) { Text("Remove only") }
                    if (e.stagedPath != null) TextButton(onClick = { confirmRemove = false; app.shell.showMessage(app.imports.remove(e.id, deleteFiles = true)); onRemoved() }, modifier = Modifier.testTag("remove_delete_files")) { Text("Remove and delete PocketOS's copy") }
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}


/** Run (programs) or Install (installers) inside the built-in runtime. */
@Composable
internal fun StartButton(e: LibraryEntry, compact: Boolean = false, onStarted: () -> Unit = {}) {
    val app = LocalPocketOs.current
    val context = LocalContext.current
    val label = if (e.isInstaller) (if (e.installerRan) "Install again" else "Install") else "Run"
    val onClick = {
        val problem = app.imports.start(e.id)
        if (problem == null) onStarted() else app.shell.showMessage(problem)
    }
    if (compact) androidx.compose.material3.FilledTonalButton(onClick = onClick, modifier = Modifier.padding(end = 6.dp).testTag("entry_quick_start")) { Text(label) }
    else Button(onClick = onClick, modifier = Modifier.testTag("entry_start")) { Text(label) }
}

/** Icon the installer created for this program, if any. */
@Composable
private fun EntryIcon(e: LibraryEntry) {
    val path = e.iconPath ?: return
    val bmp = remember(path) { runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull() } ?: return
    androidx.compose.foundation.Image(
        bmp.asImageBitmap(), contentDescription = null,
        modifier = Modifier.padding(end = 8.dp).size(32.dp),
    )
}
