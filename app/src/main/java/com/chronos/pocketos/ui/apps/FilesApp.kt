package com.chronos.pocketos.ui.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.core.files.FileEntry
import com.chronos.pocketos.shell.AppIds
import com.chronos.pocketos.ui.components.ConfirmDialog
import com.chronos.pocketos.ui.components.TextPromptDialog
import com.chronos.pocketos.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val textExtensions = setOf("txt", "md", "log", "json", "csv", "ini", "xml", "cfg", "conf", "yaml", "yml", "kt", "java", "py", "js", "html", "css", "sh", "bat")
private val windowsExtensions = setOf("exe", "msi")

fun isTextName(name: String) = name.substringAfterLast('.', "").lowercase() in textExtensions || !name.contains('.')
fun isWindowsName(name: String) = name.substringAfterLast('.', "").lowercase() in windowsExtensions

private sealed interface Location {
    data object Home : Location
    data class Tree(val root: Uri, val label: String) : Location
}

private data class Row1(val name: String, val isDir: Boolean, val size: Long, val modified: Long, val home: FileEntry? = null, val doc: DocumentFile? = null)

/** Callback interface so other phases (e.g. the Windows installer) can receive files from the file manager. */
object FileOpenHooks {
    var onWindowsExecutable: ((context: Context, uri: Uri, name: String) -> Unit)? = null
}

@Composable
fun FilesApp() {
    val app = LocalPocketOs.current
    val context = LocalContext.current
    val shell = app.shell
    val scope = rememberCoroutineScope()

    var location by remember { mutableStateOf<Location>(Location.Home) }
    var homePath by remember { mutableStateOf("") }
    var docStack by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var rows by remember { mutableStateOf<List<Row1>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var trees by remember { mutableStateOf(app.settings.grantedTrees()) }
    var prompt by remember { mutableStateOf<String?>(null) } // "folder" | "file" | "rename"
    var target by remember { mutableStateOf<Row1?>(null) }
    var confirmDelete by remember { mutableStateOf<Row1?>(null) }
    var menuFor by remember { mutableStateOf<Row1?>(null) }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.onFailure {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            trees = trees + uri.toString()
            app.settings.setGrantedTrees(trees)
            val doc = DocumentFile.fromTreeUri(context, uri)
            location = Location.Tree(uri, doc?.name ?: "Folder")
            docStack = listOfNotNull(doc)
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { copyIntoHome(context, uri, app.home.resolve(homePath)) } }
            result.onSuccess { shell.showMessage("Copied \"$it\" to PocketOS Home") }.onFailure { error = "Could not copy file: ${it.message}" }
            refresh++
        }
    }

    LaunchedEffect(location, homePath, docStack, refresh) {
        error = null
        rows = withContext(Dispatchers.IO) {
            runCatching {
                when (val loc = location) {
                    Location.Home -> app.home.list(homePath).map { Row1(it.name, it.isDirectory, it.sizeBytes, it.lastModified, home = it) }
                    is Location.Tree -> (docStack.lastOrNull()?.listFiles() ?: emptyArray())
                        .map { Row1(it.name ?: "?", it.isDirectory, it.length(), it.lastModified(), doc = it) }
                        .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                }
            }.getOrElse { e ->
                error = if (e is SecurityException) "PocketOS no longer has permission for this folder. Remove it and add it again." else (e.message ?: "Could not open folder")
                emptyList()
            }
        }
    }

    fun openRow(r: Row1) {
        if (r.isDir) {
            if (r.home != null) homePath = r.home.relativePath else if (r.doc != null) docStack = docStack + r.doc
            return
        }
        when {
            isWindowsName(r.name) -> {
                val uri = r.doc?.uri ?: FileProvider.getUriForFile(context, "${context.packageName}.files", app.home.resolve(r.home!!.relativePath))
                val hook = FileOpenHooks.onWindowsExecutable
                if (hook != null) hook(context, uri, r.name) else shell.showMessage("Windows programs are handled by the Windows Apps tool.")
            }
            isTextName(r.name) && r.size <= 2L * 1024 * 1024 -> {
                val arg = if (r.home != null) "home:${r.home.relativePath}" else "uri:${r.doc!!.uri}"
                shell.openApp(AppIds.EDITOR, arg)
            }
            else -> {
                val uri = r.doc?.uri ?: FileProvider.getUriForFile(context, "${context.packageName}.files", app.home.resolve(r.home!!.relativePath))
                val ext = r.name.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(Intent.createChooser(intent, "Open ${r.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: ActivityNotFoundException) { shell.showMessage("No Android app can open this file type.") }
            }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val narrow = maxWidth < 480.dp
    val sidebar: @Composable () -> Unit = {
            SidebarItem("Home", location == Location.Home, tag = "files_home") { location = Location.Home; homePath = "" }
            trees.forEach { t ->
                val uri = Uri.parse(t)
                val label = remember(t) { runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull() ?: "Folder" }
                SidebarItem(label, (location as? Location.Tree)?.root == uri) {
                    location = Location.Tree(uri, label); docStack = listOfNotNull(DocumentFile.fromTreeUri(context, uri))
                }
            }
            TextButton(onClick = { treePicker.launch(null) }, modifier = Modifier.testTag("files_add_folder")) { Text("+ Add folder") }
            (location as? Location.Tree)?.let { loc ->
                TextButton(onClick = {
                    runCatching { context.contentResolver.releasePersistableUriPermission(loc.root, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    trees = trees - loc.root.toString(); app.settings.setGrantedTrees(trees)
                    location = Location.Home; homePath = ""
                }) { Text("Remove access", color = MaterialTheme.colorScheme.error) }
            }
    }
    val main: @Composable (Modifier) -> Unit = { mod -> Column(mod) {
            // Toolbar
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val canUp = when (location) { Location.Home -> homePath.isNotEmpty(); is Location.Tree -> docStack.size > 1 }
                TextButton(enabled = canUp, onClick = {
                    if (location == Location.Home) homePath = homePath.substringBeforeLast('/', "") else docStack = docStack.dropLast(1)
                }, modifier = Modifier.testTag("files_up")) { Text("↑ Up") }
                if (location == Location.Home) {
                    TextButton(onClick = { prompt = "folder" }, modifier = Modifier.testTag("files_new_folder")) { Text("New folder") }
                    TextButton(onClick = { prompt = "file" }, modifier = Modifier.testTag("files_new_file")) { Text("New text file") }
                    TextButton(onClick = { importPicker.launch(arrayOf("*/*")) }) { Text("Import from device") }
                }
                TextButton(onClick = { refresh++ }) { Text("Refresh") }
            }
            val crumb = when (val loc = location) {
                Location.Home -> "Home" + if (homePath.isEmpty()) "" else " / " + homePath.replace("/", " / ")
                is Location.Tree -> docStack.joinToString(" / ") { it.name ?: "?" }.ifEmpty { loc.label }
            }
            Text(crumb, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp).testTag("files_path"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            HorizontalDivider(Modifier.padding(top = 4.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            if (rows.isEmpty() && error == null) {
                Text("This folder is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize().testTag("files_list")) {
                items(rows, key = { it.name + it.isDir }) { r ->
                    Box {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { openRow(r) }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("file_${r.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (r.isDir) "📁" else if (isWindowsName(r.name)) "⧉" else "📄", modifier = Modifier.width(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (if (r.isDir) "Folder" else formatBytes(r.size)) + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.modified)),
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { menuFor = r }, modifier = Modifier.testTag("file_menu_${r.name}")) { Text("⋯") }
                        }
                        DropdownMenu(expanded = menuFor == r, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text("Open") }, onClick = { menuFor = null; openRow(r) })
                            if (r.home != null || r.doc?.canWrite() == true) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; target = r; prompt = "rename" })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuFor = null; confirmDelete = r }, modifier = Modifier.testTag("file_delete"))
                            }
                        }
                    }
                }
            }
        } }
    if (narrow) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp)) { sidebar() }
            main(Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(150.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer).padding(6.dp)) { sidebar() }
            main(Modifier.weight(1f).fillMaxHeight())
        }
    }
    }

    when (prompt) {
        "folder" -> TextPromptDialog("New folder", "New folder", "Create", { prompt = null }) { name ->
            prompt = null
            runCatching { app.home.createFolder(homePath, name) }.onFailure { error = it.message }; refresh++
        }
        "file" -> TextPromptDialog("New text file", "Untitled.txt", "Create", { prompt = null }) { name ->
            prompt = null
            runCatching {
                com.chronos.pocketos.core.files.SandboxFileSystem.validateName(name)
                val rel = if (homePath.isEmpty()) name else "$homePath/$name"
                if (app.home.resolve(rel).exists()) throw IllegalArgumentException("\"$name\" already exists")
                app.home.writeText(rel, "")
                shell.openApp(AppIds.EDITOR, "home:$rel")
            }.onFailure { error = it.message }; refresh++
        }
        "rename" -> target?.let { t ->
            TextPromptDialog("Rename", t.name, "Rename", { prompt = null }) { name ->
                prompt = null
                runCatching {
                    if (t.home != null) app.home.rename(t.home.relativePath, name)
                    else if (t.doc?.renameTo(name) != true) throw IllegalStateException("Rename was not allowed")
                }.onFailure { error = it.message }; refresh++
            }
        }
    }
    confirmDelete?.let { r ->
        ConfirmDialog("Delete \"${r.name}\"?", if (r.isDir) "The folder and everything inside it will be permanently deleted." else "The file will be permanently deleted.", "Delete", { confirmDelete = null }) {
            confirmDelete = null
            runCatching {
                if (r.home != null) app.home.delete(r.home.relativePath) else if (r.doc?.delete() != true) throw IllegalStateException("Delete was not allowed")
            }.onFailure { error = it.message }; refresh++
        }
    }
}

@Composable
private fun SidebarItem(label: String, selected: Boolean, tag: String? = null, onClick: () -> Unit) {
    Text(
        label, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(min = 72.dp)
            .heightIn(min = 44.dp)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/** Copies a user-picked document into a Home folder without executing or parsing it. */
fun copyIntoHome(context: Context, uri: Uri, dir: File): String {
    val name = DocumentFile.fromSingleUri(context, uri)?.name?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "imported"
    var target = File(dir, name); var n = 1
    while (target.exists()) { target = File(dir, name.substringBeforeLast('.', name) + " ($n)" + (if (name.contains('.')) "." + name.substringAfterLast('.') else "")); n++ }
    context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
    return target.name
}
