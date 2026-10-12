package com.chronos.pocketos.ui.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronos.pocketos.core.input.MouseButton
import com.chronos.pocketos.core.input.TouchPoint
import com.chronos.pocketos.input.SmartCursor
import kotlinx.coroutines.delay

/**
 * Phone touchpad surface. Moves the Smart Cursor inside the active PocketOS desktop only.
 * Gestures: move, tap = click, double-tap, tap-and-drag or long-press-drag, two-finger tap = right click, two-finger scroll.
 */
@Composable
fun TouchpadSurface(cursor: SmartCursor, showButtons: Boolean, modifier: Modifier = Modifier, hint: String? = null) {
    var touching by remember { mutableStateOf(false) }
    LaunchedEffect(touching) {
        while (touching) { cursor.tick(android.os.SystemClock.uptimeMillis()); delay(50) }
    }
    Column(modifier) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        while (true) {
                            val event = awaitPointerEvent()
                            val down = event.changes.filter { it.pressed }
                            event.changes.forEach { it.consume() }
                            val time = event.changes.maxOfOrNull { it.uptimeMillis } ?: android.os.SystemClock.uptimeMillis()
                            touching = down.isNotEmpty()
                            cursor.onTouchpad(time, down.map { TouchPoint(it.id.value, it.position.x, it.position.y) })
                            if (down.isEmpty()) break
                        }
                    }
                }
                .semantics { contentDescription = "Touchpad. Move one finger to move the cursor, tap to click, two fingers to scroll." }
                .testTag("touchpad_surface"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                hint ?: "Touchpad",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp,
            )
        }
        if (showButtons) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ClickButton("Left click", Modifier.weight(1f).testTag("touchpad_left"), onTap = { cursor.click(MouseButton.LEFT) }, onHold = cursor::setLeftButtonHeld)
                ClickButton("Right click", Modifier.weight(1f).testTag("touchpad_right"), onTap = { cursor.click(MouseButton.RIGHT) }, onHold = null)
            }
        }
    }
}

@Composable
private fun ClickButton(label: String, modifier: Modifier, onTap: () -> Unit, onHold: ((Boolean) -> Unit)?) {
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f))
            .pointerInput(onHold) {
                awaitEachGesture {
                    val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
                    down.consume()
                    val start = down.uptimeMillis
                    var held = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        c.consume()
                        if (!held && onHold != null && c.pressed && c.uptimeMillis - start > 250) { held = true; onHold(true) }
                        if (!c.pressed) break
                    }
                    if (held) onHold?.invoke(false) else onTap()
                }
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp) }
}

/** Arrow cursor drawn at the Smart Cursor position (px inside the desktop surface). Does not intercept input. */
@Composable
fun CursorOverlay(xPx: Float, yPx: Float, scale: Float, pressed: Boolean) {
    val density = LocalDensity.current
    val sizeDp = 22.dp * scale
    Canvas(
        Modifier
            .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
            .size(sizeDp)
            .testTag("smart_cursor"),
    ) {
        val s = size.height
        val path = Path().apply {
            moveTo(0f, 0f); lineTo(0f, s); lineTo(s * 0.28f, s * 0.74f); lineTo(s * 0.48f, s * 1.0f)
            lineTo(s * 0.6f, s * 0.94f); lineTo(s * 0.42f, s * 0.68f); lineTo(s * 0.74f, s * 0.68f); close()
        }
        drawPath(path, if (pressed) Color(0xFF5AC8FA) else Color.White)
        drawPath(path, Color.Black, style = Stroke(width = s * 0.07f))
    }
}
