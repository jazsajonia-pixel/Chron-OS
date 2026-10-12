package com.chronos.pocketos.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.chronos.pocketos.PocketOsApp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Puts Wine's windows on the phone's PocketOS desktop: the [WineHost.DesktopLayer] sits above PocketOS's own views,
 * covering the desktop above the taskbar. Only Windows windows are drawn there; everything else stays touchable.
 * The layer steps aside while the launcher, the window switcher or a desktop menu is open.
 */
object WindowsLayer {
    private const val TARGET = "phone"
    private var listener: WineHost.Listener? = null

    fun attach(activity: ComponentActivity) {
        val pocket = activity.application as PocketOsApp
        val host = WineHost.get(activity)
        val layer = host.layer(activity.applicationContext)
        (layer.parent as? ViewGroup)?.removeView(layer)
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        content.addView(layer, FrameLayout.LayoutParams(0, 0))
        val cursorView = CursorView(activity)
        content.addView(cursorView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        listener?.let(host::removeListener)
        listener = object : WineHost.Listener {
            override fun onDesktopReady() {}
            override fun onWindowsChanged(visibleTopLevelWindows: Int) { cursorView.invalidate() }
            override fun onWindowActivated(hwnd: Int) { pocket.onWindowsWindowTapped() }
        }.also(host::addListener)

        activity.lifecycleScope.launch {
            combine(pocket.windowsArea, pocket.shell.ui, pocket.displays.state) { area, ui, display -> Triple(area, ui, display) }
                .collect { (area, ui, display) ->
                    if (area != null) {
                        val lp = layer.layoutParams as FrameLayout.LayoutParams
                        if (lp.leftMargin != area.left || lp.topMargin != area.top || lp.width != area.width() || lp.height != area.height()) {
                            lp.leftMargin = area.left; lp.topMargin = area.top; lp.width = area.width(); lp.height = area.height()
                            layer.layoutParams = lp
                        }
                    }
                    val onPhone = display.phase != com.chronos.pocketos.core.display.SessionPhase.ACTIVE
                    val covered = ui.launcherOpen || ui.switcherOpen || ui.contextMenuAt != null
                    layer.visibility = if (area != null && onPhone && !covered) View.VISIBLE else View.INVISIBLE
                }
        }
        // The Smart Cursor also hovers over Windows windows (menus and buttons react like with a mouse).
        activity.lifecycleScope.launch {
            pocket.cursor.state.collect { ps ->
                cursorView.invalidate()
                if (!pocket.cursor.active.value || pocket.cursor.activeTargetId.value != TARGET || ps.pressed) return@collect
                val (ox, oy) = pocket.cursor.origin(TARGET) ?: return@collect
                val lp = layer.layoutParams as FrameLayout.LayoutParams
                if (layer.visibility == View.VISIBLE) host.hover(ox + ps.x - lp.leftMargin, oy + ps.y - lp.topMargin)
            }
        }
        activity.lifecycleScope.launch { pocket.cursor.active.collect { cursorView.invalidate() } }
    }

    /**
     * Draws the Smart Cursor above Windows windows (PocketOS draws its own copy under them, at the same spot).
     * Never takes touches.
     */
    private class CursorView(context: Context) : View(context) {
        private val pocket = context.applicationContext as PocketOsApp
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
        private val path = Path()

        init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun dispatchTouchEvent(event: android.view.MotionEvent?) = false

        override fun onDraw(canvas: Canvas) {
            val cursor = pocket.cursor
            val settings = pocket.settings.settings.value
            if (!cursor.active.value || cursor.activeTargetId.value != TARGET || !settings.showSmartCursor) return
            if (WineHost.get(context).visibleTopLevelCount() == 0) return
            val (ox, oy) = cursor.origin(TARGET) ?: return
            val ps = cursor.state.value
            val s = 22f * resources.displayMetrics.density * settings.cursorSizeScale
            val x = ox + ps.x; val y = oy + ps.y
            path.reset()
            path.moveTo(x, y); path.lineTo(x, y + s); path.lineTo(x + s * 0.28f, y + s * 0.74f); path.lineTo(x + s * 0.48f, y + s)
            path.lineTo(x + s * 0.6f, y + s * 0.94f); path.lineTo(x + s * 0.42f, y + s * 0.68f); path.lineTo(x + s * 0.74f, y + s * 0.68f); path.close()
            fill.color = if (ps.pressed) 0xFF5AC8FA.toInt() else Color.WHITE
            stroke.strokeWidth = s * 0.07f
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
    }
}
