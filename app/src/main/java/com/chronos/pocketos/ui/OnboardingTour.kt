package com.chronos.pocketos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class TourPage(val title: String, val body: String)

private val pages = listOf(
    TourPage("Welcome to PocketOS",
        "PocketOS is an Android app that gives your phone a desktop with windows, a taskbar and a launcher. " +
            "It is not a separate operating system: your phone, its apps and its data keep working normally, and you can leave at any time."),
    TourPage("Pointer and keyboard",
        "Use the on-screen touchpad: move one finger to move the pointer, tap to click, two-finger tap to right-click, " +
            "drag with two fingers to scroll. A Bluetooth or USB mouse and keyboard also work, including shortcuts such as Alt+Tab."),
    TourPage("External monitor",
        "If your phone supports video output (for example USB-C DisplayPort or screen casting), PocketOS can show the desktop on the monitor " +
            "while the phone becomes a touchpad. Many phones do not support video output; Settings → Display shows what yours reports."),
    TourPage("PC programs",
        "PocketOS can examine .exe and .msi files and prepare them for a separate, free compatibility app (Winlator, 64-bit ARM phones only). " +
            "PocketOS never runs these files itself. Many programs will not work, and results differ between phones."),
    TourPage("Your privacy",
        "PocketOS asks for no permissions, shows no ads and contains no analytics. Nothing is uploaded. " +
            "Diagnostics stay on this phone unless you choose to share a report, which you can preview first."),
)

/** First-run tour. Keyboard: → or Enter for next, ← for back, Esc to skip. */
@Composable
fun OnboardingTour(onFinish: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val last = index == pages.lastIndex
    fun next() { if (last) onFinish() else index++ }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { } } // block the desktop behind without merging semantics
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight, Key.Enter, Key.NumPadEnter -> { next(); true }
                    Key.DirectionLeft -> { if (index > 0) index--; true }
                    Key.Escape -> { onFinish(); true }
                    else -> false
                }
            }
            .testTag("onboarding"),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.widthIn(max = 520.dp).padding(20.dp).semantics { paneTitle = "Welcome tour" }) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text("Step ${index + 1} of ${pages.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("onb_step"))
                Text(pages[index].title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp).semantics { heading() }.testTag("onb_title"))
                Text(pages[index].body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp).testTag("onb_body"))
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.Center) {
                    pages.indices.forEach { i ->
                        Box(Modifier.padding(3.dp).size(8.dp).background(
                            if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!last) TextButton(onClick = onFinish, modifier = Modifier.testTag("onb_skip")) { Text("Skip") }
                    Spacer(Modifier.weight(1f))
                    if (index > 0) TextButton(onClick = { index-- }, modifier = Modifier.testTag("onb_back")) { Text("Back") }
                    Button(onClick = ::next, modifier = Modifier.testTag("onb_next")) { Text(if (last) "Get started" else "Next") }
                }
            }
        }
    }
}
