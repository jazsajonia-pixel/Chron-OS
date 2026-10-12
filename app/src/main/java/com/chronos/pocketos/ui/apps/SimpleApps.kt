package com.chronos.pocketos.ui.apps

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.core.files.SandboxFileSystem
import com.chronos.pocketos.core.tools.Calculator
import com.chronos.pocketos.core.window.AppWindow
import com.chronos.pocketos.ui.components.TextPromptDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Editor buffer kept in [com.chronos.pocketos.PocketOsApp.windowState] so it survives display changes. */
class EditorBuffer(var source: String?, var text: String = "", var loaded: Boolean = false, var dirty: Boolean = false)

@Composable
fun TextEditorApp(window: AppWindow) {
    val app = LocalPocketOs.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val buffer = remember(window.id) {
        (app.windowState.getOrPut(window.id) { EditorBuffer(window.argument) } as EditorBuffer)
    }
    var text by remember(window.id) { mutableStateOf(buffer.text) }
    var dirty by remember(window.id) { mutableStateOf(buffer.dirty) }
    var status by remember { mutableStateOf<String?>(null) }
    var saveAs by remember { mutableStateOf(false) }

    fun displayName(src: String?) = when {
        src == null -> "Untitled"
        src.startsWith("home:") -> src.removePrefix("home:").substringAfterLast('/')
        else -> Uri.parse(src.removePrefix("uri:")).lastPathSegment?.substringAfterLast('/') ?: "Document"
    }

    LaunchedEffect(window.id) {
        if (!buffer.loaded) {
            val src = buffer.source
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        src == null -> ""
                        src.startsWith("home:") -> app.home.readText(src.removePrefix("home:"))
                        else -> context.contentResolver.openInputStream(Uri.parse(src.removePrefix("uri:")))!!.use { s ->
                            val bytes = s.readNBytesCompat(SandboxFileSystem.MAX_TEXT_BYTES.toInt() + 1)
                            if (bytes.size > SandboxFileSystem.MAX_TEXT_BYTES) throw java.io.IOException("File is too large to open in the text editor")
                            String(bytes)
                        }
                    }
                }
            }
            loaded.onSuccess { buffer.text = it; text = it; buffer.loaded = true }.onFailure { status = "Could not open: ${it.message}" }
        }
        app.shell.setTitle(window.id, displayName(buffer.source) + (if (buffer.dirty) " •" else "") + " – Text Editor")
    }

    fun save(src: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (src.startsWith("home:")) app.home.writeText(src.removePrefix("home:"), text)
                    else context.contentResolver.openOutputStream(Uri.parse(src.removePrefix("uri:")), "wt")!!.use { it.write(text.toByteArray()) }
                }
            }
            r.onSuccess {
                buffer.source = src; buffer.dirty = false; dirty = false; status = "Saved"
                app.shell.setTitle(window.id, displayName(src) + " – Text Editor")
            }.onFailure { status = "Save failed: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { val s = buffer.source; if (s == null) saveAs = true else save(s) }, modifier = Modifier.testTag("editor_save")) { Text("Save") }
            TextButton(onClick = { saveAs = true }) { Text("Save as…") }
            Text(
                status ?: if (dirty) "Unsaved changes" else "${text.length} characters",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("editor_status"),
            )
        }
        BasicTextField(
            value = text,
            onValueChange = {
                text = it; buffer.text = it; status = null
                if (!dirty) { dirty = true; buffer.dirty = true; app.shell.setTitle(window.id, displayName(buffer.source) + " • – Text Editor") }
            },
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp).testTag("editor_text"),
        )
    }
    if (saveAs) {
        TextPromptDialog("Save in Home/Documents as", displayName(buffer.source).let { if (it == "Untitled") "Untitled.txt" else it }, "Save", { saveAs = false }) { name ->
            saveAs = false
            runCatching { SandboxFileSystem.validateName(name) }.onSuccess { save("home:Documents/$name") }.onFailure { status = it.message }
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@Composable
fun CalculatorApp() {
    var expr by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    val keys = listOf(
        listOf("C", "(", ")", "÷"),
        listOf("7", "8", "9", "×"),
        listOf("4", "5", "6", "−"),
        listOf("1", "2", "3", "+"),
        listOf("⌫", "0", ".", "="),
    )
    fun press(k: String) {
        when (k) {
            "C" -> { expr = ""; result = "" }
            "⌫" -> expr = expr.dropLast(1)
            "=" -> when (val r = Calculator.evaluate(expr)) {
                is Calculator.Result.Value -> { result = r.display(); expr = r.display() }
                is Calculator.Result.Error -> result = r.message
            }
            else -> expr += k
        }
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text(expr.ifEmpty { "0" }, fontSize = 28.sp, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.fillMaxWidth().testTag("calc_expr"))
        Text(result, fontSize = 16.sp, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().testTag("calc_result"))
        Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            keys.forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { k ->
                        val op = k in setOf("÷", "×", "−", "+", "=")
                        Box(
                            Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp))
                                .background(if (op) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { press(k) }.testTag("calc_$k"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(k, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = if (op) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnknownApp(window: AppWindow) {
    Box(Modifier.fillMaxSize().height(100.dp), contentAlignment = Alignment.Center) {
        Text("\"${window.appId}\" is not available in this version of PocketOS.")
    }
}
