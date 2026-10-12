package com.chronos.pocketos.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronos.pocketos.LocalPocketOs
import com.chronos.pocketos.core.pe.FileKind
import com.chronos.pocketos.core.library.AppStatus
import com.chronos.pocketos.core.library.LibraryEntry

/** Windows programs that get a desktop icon: everything an installer installed, and imported programs. */
fun desktopPrograms(entries: List<LibraryEntry>): List<LibraryEntry> = entries
    .filter { it.kind == FileKind.WINDOWS_PROGRAM && !it.isInstaller && it.status != AppStatus.UNSUPPORTED }
    .sortedBy { it.createdAt }

/** Desktop icons of installed Windows programs. One tap opens the program, like on a PC. */
@Composable
fun ProgramDesktopIcons() {
    val app = LocalPocketOs.current
    val library by app.library.state.collectAsState()
    desktopPrograms(library.entries).forEach { e ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(84.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClickLabel = "Open ${e.displayName}") {
                    app.imports.start(e.id)?.let(app.shell::showMessage)
                }
                .padding(6.dp)
                .testTag("program_icon_${e.id}"),
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2563EB)),
                contentAlignment = Alignment.Center,
            ) {
                Text(e.displayName.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                e.displayName, color = Color.White, fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium.copy(
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 4f),
                ),
            )
        }
    }
}
