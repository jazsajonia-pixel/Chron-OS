package com.chronos.pocketos.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.core.window.AppWindow
import com.chronos.pocketos.ui.components.ConfirmDialog

/**
 * The window a Windows program runs in. The program's own windows, menus and dialogs are drawn inside it; tapping,
 * the Smart Cursor, a mouse and the keyboard all control it directly. Closing this window ends the program.
 */
@Composable
fun WindowsProgramApp(window: AppWindow) {
    val app = LocalPocketOs.current
    val active by app.runtime.active.collectAsState()
    var confirmStop by remember { mutableStateOf(false) }
    val session = active

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                when {
                    session == null -> "Not running"
                    !session.ready -> session.status.ifEmpty { "Starting…" }
                    else -> "Running"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("windows-status"),
            )
            if (session != null) {
                TextButton(onClick = { session.handle.showSoftKeyboard() }, modifier = Modifier.testTag("windows-keyboard")) {
                    Text("Keyboard", fontSize = 12.sp)
                }
                TextButton(onClick = { confirmStop = true }, modifier = Modifier.testTag("windows-stop")) {
                    Text("Stop", fontSize = 12.sp)
                }
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (session == null) {
                Text("No Windows program is running.", color = Color.White.copy(alpha = 0.8f))
            }
            else {
                key(session.sessionId) {
                    AndroidView(
                        factory = { ctx -> session.handle.createView(ctx) },
                        modifier = Modifier.fillMaxSize().testTag("windows-display"),
                    )
                }
                if (!session.ready) {
                    Column(
                        Modifier.fillMaxSize().background(Color.Black),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, strokeWidth = 3.dp)
                        Spacer(Modifier.height(14.dp))
                        Text(session.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(session.status.ifEmpty { "Starting…" }, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("The first start can take a minute.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (confirmStop && session != null) {
        ConfirmDialog(
            title = "Stop ${session.title}?",
            message = "Unsaved work in the program will be lost.",
            confirm = "Stop",
            onDismiss = { confirmStop = false },
            onConfirm = { confirmStop = false; app.runtime.stopSession() },
        )
    }
}
