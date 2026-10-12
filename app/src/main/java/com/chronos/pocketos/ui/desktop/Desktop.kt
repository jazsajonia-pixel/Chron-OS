package com.chronos.pocketos.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.runtime.DisposableEffect
import com.chronos.pocketos.input.DesktopTarget
import com.chronos.pocketos.input.SmartCursor
import com.chronos.pocketos.ui.input.CursorOverlay
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronos.pocketos.core.settings.ShellSettings
import com.chronos.pocketos.core.window.AppWindow
import com.chronos.pocketos.core.window.SnapSide
import com.chronos.pocketos.core.window.WinRect
import com.chronos.pocketos.core.window.WindowMode
import com.chronos.pocketos.shell.AppSpec
import com.chronos.pocketos.shell.BuiltInApps
import com.chronos.pocketos.shell.ShellController
import com.chronos.pocketos.ui.theme.isDark
import com.chronos.pocketos.ui.theme.wallpaperBrush
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val TaskbarHeight: Dp = 52.dp

/**
 * The PocketOS desktop: wallpaper, icons, windows, taskbar and launcher.
 * [appContent] renders the body of a window; it is supplied by the host so the desktop stays app-agnostic.
 * [statusExtras] lets the host add indicators (input/display) to the taskbar status area.
 */
@Composable
fun Desktop(
    controller: ShellController,
    settings: ShellSettings,
    modifier: Modifier = Modifier,
    cursor: SmartCursor? = null,
    targetId: String = "phone",
    targetPriority: Int = 0,
    onExit: (() -> Unit)? = null,
    statusExtras: @Composable () -> Unit = {},
    overlay: @Composable () -> Unit = {},
    appContent: @Composable (AppWindow) -> Unit,
) {
    val desktop by controller.desktop.collectAsState()
    val ui by controller.ui.collectAsState()
    val dark = isDark(settings.theme)
    val view = LocalView.current

    if (cursor != null) {
        DisposableEffect(cursor, targetId, view) {
            cursor.register(DesktopTarget(targetId, view.rootView, 0f, 0f, view.width.toFloat(), view.height.toFloat(), targetPriority))
            onDispose { cursor.unregister(targetId) }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(wallpaperBrush(settings.wallpaper, dark))
            .onGloballyPositioned { c ->
                val p = c.positionInWindow()
                cursor?.updateBounds(targetId, p.x, p.y, c.size.width.toFloat(), c.size.height.toFloat())
            }
            .pointerInput(cursor) {
                // Detect a physical mouse (system pointer) so the Smart Cursor can step aside.
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (cursor != null && !cursor.dispatching && e.changes.any { it.type == PointerType.Mouse }) cursor.markPhysicalMouseUsed()
                    }
                }
            }
            .testTag("desktop"),
    ) {
        val areaW = maxWidth.value.toInt()
        val areaH = (maxHeight - TaskbarHeight).value.toInt().coerceAtLeast(120)
        LaunchedEffect(areaW, areaH) { controller.setArea(WinRect(0, 0, areaW, areaH)) }

        // Wallpaper layer: tap dismisses menus; long-press opens the desktop context menu.
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = TaskbarHeight)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controller.dismissContextMenu(); controller.closeLauncher() },
                        onLongPress = { p -> controller.showContextMenu(p.x / density, p.y / density) },
                    )
                }
                .pointerInput(Unit) {
                    // Secondary (right) mouse button opens the desktop menu.
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                                val p = e.changes.first().position
                                e.changes.forEach { it.consume() }
                                controller.showContextMenu(p.x / density, p.y / density)
                            }
                        }
                    }
                }
                .testTag("wallpaper"),
        )
        if (settings.showDesktopIcons) DesktopIcons(onOpen = { controller.openApp(it.id) })

        // Windows, back to front.
        desktop.visibleWindows.forEach { w ->
            androidx.compose.runtime.key(w.id) {
                WindowFrame(
                    window = w,
                    focused = desktop.focusedId == w.id,
                    controller = controller,
                    reduceMotion = settings.reduceMotion,
                ) { appContent(w) }
            }
        }

        ui.contextMenuAt?.let { (x, y) ->
            DesktopContextMenu(
                x = x, y = y, maxW = areaW, maxH = areaH,
                onAction = { action ->
                    controller.dismissContextMenu()
                    when (action) {
                        "settings" -> controller.openApp("settings")
                        "files" -> controller.openApp("files")
                        "editor" -> controller.openApp("editor")
                    }
                },
            )
        }

        if (ui.launcherOpen) {
            Launcher(
                apps = BuiltInApps.launcherApps(),
                compact = areaW < 600,
                onOpen = { controller.openApp(it.id) },
                onDismiss = controller::closeLauncher,
                onExit = onExit,
            )
        }

        if (ui.switcherOpen) WindowSwitcher(desktop.orderedWindows.reversed(), desktop.focusedId)

        Taskbar(
            controller = controller,
            windows = desktop.orderedWindows.sortedBy { it.id },
            focusedId = desktop.focusedId,
            launcherOpen = ui.launcherOpen,
            use24h = settings.use24HourClock,
            statusExtras = statusExtras,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        overlay()

        if (cursor != null && settings.showSmartCursor) {
            val active by cursor.active.collectAsState()
            val activeTarget by cursor.activeTargetId.collectAsState()
            val ps by cursor.state.collectAsState()
            if (active && activeTarget == targetId) CursorOverlay(ps.x, ps.y, settings.cursorSizeScale, ps.pressed)
        }

        ui.message?.let { msg ->
            LaunchedEffect(msg) { delay(3500); controller.clearMessage() }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TaskbarHeight + 12.dp).widthIn(max = 480.dp),
            ) {
                Text(msg, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

@Composable
private fun DesktopIcons(onOpen: (AppSpec) -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BuiltInApps.launcherApps().take(5).forEach { app ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(84.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = "Open ${app.title}") { onOpen(app) }
                    .padding(6.dp)
                    .testTag("icon_${app.id}"),
            ) {
                AppGlyph(app, 44.dp)
                Text(
                    app.title, color = Color.White, fontSize = 12.sp, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium.copy(
                        shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 4f),
                    ),
                )
            }
        }
    }
}

@Composable
fun AppGlyph(app: AppSpec, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(app.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(app.glyph, color = Color.White, fontSize = (size.value * 0.48f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WindowFrame(
    window: AppWindow,
    focused: Boolean,
    controller: ShellController,
    reduceMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    val b = window.bounds
    val currentWindow by rememberUpdatedState(window)
    val maximized = window.mode == WindowMode.MAXIMIZED
    val shape = if (maximized) RoundedCornerShape(0.dp) else RoundedCornerShape(12.dp)
    Column(
        Modifier
            .offset(b.x.dp, b.y.dp)
            .size(b.width.dp, b.height.dp)
            .then(if (reduceMotion || maximized) Modifier else Modifier.shadow(if (focused) 18.dp else 6.dp, shape))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
            .pointerInput(window.id) {
                // Observe presses in the Initial pass without consuming, so app content still receives them.
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press) controller.focus(currentWindow.id)
                    }
                }
            }
            .semantics { contentDescription = "${window.title} window" }
            .testTag("window_${window.appId}"),
    ) {
        // Title bar
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainer)
                .pointerInput(window.id) {
                    detectDragGestures(onDragStart = { controller.focus(currentWindow.id) }) { change, drag ->
                        change.consume()
                        controller.moveBy(currentWindow.id, (drag.x / density).toInt(), (drag.y / density).toInt())
                    }
                }
                .pointerInput(window.id) { detectTapGestures(onDoubleTap = { controller.toggleMaximize(currentWindow.id) }, onTap = { controller.focus(currentWindow.id) }) }
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BuiltInApps.find(window.appId)?.let { AppGlyph(it, 22.dp) }
            Spacer(Modifier.width(8.dp))
            Text(
                window.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
            )
            if (!maximized && b.width >= 420) {
                TitleButton("◧", "Snap left") { controller.snap(window.id, SnapSide.LEFT) }
                TitleButton("◨", "Snap right") { controller.snap(window.id, SnapSide.RIGHT) }
            }
            TitleButton("–", "Minimize", tag = "min_${window.appId}") { controller.minimize(window.id) }
            TitleButton(if (maximized) "❐" else "□", if (maximized) "Restore" else "Maximize", tag = "max_${window.appId}") { controller.toggleMaximize(window.id) }
            TitleButton("✕", "Close", danger = true, tag = "close_${window.appId}") { controller.close(window.id) }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            content()
            if (window.mode == WindowMode.NORMAL) {
                // Resize handle (bottom-right); 32dp target.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                        .pointerInput(window.id) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                controller.resizeBy(currentWindow.id, (drag.x / density).toInt(), (drag.y / density).toInt())
                            }
                        }
                        .semantics { contentDescription = "Resize window" }
                        .testTag("resize_${window.appId}"),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Text("◢", color = MaterialTheme.colorScheme.outline, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}

@Composable
private fun TitleButton(glyph: String, label: String, danger: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 44.dp, height = 44.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 16.sp, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Taskbar(
    controller: ShellController,
    windows: List<AppWindow>,
    focusedId: Int?,
    launcherOpen: Boolean,
    use24h: Boolean,
    statusExtras: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(TaskbarHeight).testTag("taskbar"),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (launcherOpen) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable(role = Role.Button, onClickLabel = "Open launcher") { controller.toggleLauncher() }
                    .semantics { contentDescription = "Launcher" }
                    .testTag("start_button"),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text("P", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 15.sp)
                }
            }
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                windows.forEach { w ->
                    val active = w.id == focusedId && w.mode != WindowMode.MINIMIZED
                    Row(
                        Modifier
                            .height(44.dp)
                            .widthIn(min = 48.dp, max = 180.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .clickable(role = Role.Button, onClickLabel = "Switch to ${w.title}") { controller.taskbarToggle(w.id) }
                            .padding(horizontal = 8.dp)
                            .testTag("task_${w.appId}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BuiltInApps.find(w.appId)?.let { AppGlyph(it, 24.dp) }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            w.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                            color = if (w.mode == WindowMode.MINIMIZED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            statusExtras()
            Clock(use24h)
        }
    }
}

@Composable
private fun Clock(use24h: Boolean) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(15_000) }
    }
    val pattern = if (use24h) "HH:mm" else "h:mm a"
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 8.dp).testTag("clock")) {
        Text(SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(now)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Launcher(
    apps: List<AppSpec>,
    compact: Boolean,
    onOpen: (AppSpec) -> Unit,
    onDismiss: () -> Unit,
    onExit: (() -> Unit)?,
) {
    var query by remember { mutableStateOf("") }
    val filtered = apps.filter { query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true) }
    Box(Modifier.fillMaxSize().padding(bottom = TaskbarHeight), contentAlignment = Alignment.BottomStart) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .testTag("launcher_scrim"),
        )
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(start = if (compact) 0.dp else 8.dp)
                .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(460.dp))
                .fillMaxHeight(if (compact) 0.75f else 0.7f)
                .testTag("launcher"),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("PocketOS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search apps") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { filtered.firstOrNull()?.let(onOpen) }),
                    modifier = Modifier.fillMaxWidth().testTag("launcher_search"),
                )
                Spacer(Modifier.height(12.dp))
                if (filtered.isEmpty()) {
                    Text("No apps match \"$query\".", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(96.dp), modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { app ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button, onClickLabel = "Open ${app.title}") { onOpen(app) }
                                .padding(10.dp)
                                .testTag("launch_${app.id}"),
                        ) {
                            AppGlyph(app, 48.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(app.title, fontSize = 13.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
                if (onExit != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onExit, modifier = Modifier.testTag("exit_pocketos")) { Text("Exit PocketOS") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopContextMenu(x: Float, y: Float, maxW: Int, maxH: Int, onAction: (String) -> Unit) {
    val w = 200; val h = 156
    val px = x.coerceAtMost((maxW - w).toFloat()).coerceAtLeast(0f)
    val py = y.coerceAtMost((maxH - h).toFloat()).coerceAtLeast(0f)
    Surface(
        shape = RoundedCornerShape(10.dp), tonalElevation = 8.dp, shadowElevation = 8.dp,
        modifier = Modifier.offset(px.dp, py.dp).width(w.dp).testTag("context_menu"),
    ) {
        Column {
            listOf("files" to "Open Files", "editor" to "New text document", "settings" to "Personalize").forEach { (id, label) ->
                Text(
                    label,
                    modifier = Modifier.fillMaxWidth().clickable { onAction(id) }.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun WindowSwitcher(windows: List<AppWindow>, focusedId: Int?) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 8.dp, modifier = Modifier.testTag("switcher")) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (windows.isEmpty()) Text("No open windows")
                windows.forEach { w ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (w.id == focusedId) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                            .padding(10.dp).width(96.dp),
                    ) {
                        BuiltInApps.find(w.appId)?.let { AppGlyph(it, 40.dp) }
                        Text(w.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusChip(text: String, description: String, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) { Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
