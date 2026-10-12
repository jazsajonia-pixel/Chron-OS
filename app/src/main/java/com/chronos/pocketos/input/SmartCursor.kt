package com.chronos.pocketos.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.chronos.pocketos.core.input.GestureConfig
import com.chronos.pocketos.core.input.InputAction
import com.chronos.pocketos.core.input.MouseButton
import com.chronos.pocketos.core.input.PointerController
import com.chronos.pocketos.core.input.PointerDispatch
import com.chronos.pocketos.core.input.PointerState
import com.chronos.pocketos.core.input.TouchPoint
import com.chronos.pocketos.core.input.TouchpadGestureInterpreter
import com.chronos.pocketos.core.settings.ShellSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A desktop surface that can receive Smart Cursor input. Events are dispatched only into this PocketOS-owned
 * window ([rootView]); PocketOS never injects input into other apps.
 */
class DesktopTarget(
    val id: String,
    val rootView: View,
    /** Top-left of the desktop content inside [rootView]'s window, in px. */
    var originX: Float,
    var originY: Float,
    var width: Float,
    var height: Float,
    val priority: Int,
)

/**
 * Smart Cursor: converts touchpad gestures into pointer movement and synthetic events delivered to the active
 * PocketOS desktop surface (phone screen or external display).
 */
class SmartCursor {
    private val interpreter = TouchpadGestureInterpreter()
    private val pointer = PointerController()
    private val targets = linkedMapOf<String, DesktopTarget>()

    private val _state = MutableStateFlow(PointerState())
    val state: StateFlow<PointerState> = _state.asStateFlow()

    /** Cursor is shown after the touchpad is used; hidden again when a physical mouse moves. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _activeTargetId = MutableStateFlow<String?>(null)
    val activeTargetId: StateFlow<String?> = _activeTargetId.asStateFlow()

    /** True while PocketOS is delivering its own synthetic events (so they aren't mistaken for a physical mouse). */
    @Volatile var dispatching = false
        private set

    private var leftButtonHeld = false

    /** Log of dispatches for diagnostics and tests (bounded). */
    val recentDispatches = ArrayDeque<PointerDispatch>()

    fun configure(s: ShellSettings) {
        interpreter.config = GestureConfig(speed = s.cursorSpeed, tapToClick = s.tapToClick, naturalScroll = s.naturalScroll)
    }

    fun register(target: DesktopTarget) {
        targets[target.id] = target
        refreshTarget()
    }

    fun unregister(id: String) {
        targets.remove(id)
        refreshTarget()
    }

    /** Top-left of [id]'s desktop inside its window, in px (where cursor coordinates start). */
    fun origin(id: String): Pair<Float, Float>? = targets[id]?.let { it.originX to it.originY }

    fun updateBounds(id: String, originX: Float, originY: Float, w: Float, h: Float) {
        val t = targets[id] ?: return
        t.originX = originX; t.originY = originY; t.width = w; t.height = h
        if (_activeTargetId.value == id) { pointer.setBounds(w, h); publish() }
    }

    private fun refreshTarget() {
        val best = targets.values.maxByOrNull { it.priority }
        if (best?.id != _activeTargetId.value) {
            _activeTargetId.value = best?.id
            interpreter.reset(); leftButtonHeld = false
            best?.let { pointer.setBounds(it.width, it.height); pointer.warpTo(it.width / 2, it.height / 2) }
            publish()
        }
    }

    fun onTouchpad(timeMs: Long, pointers: List<TouchPoint>) = handle(interpreter.onTouch(timeMs, pointers))
    fun tick(timeMs: Long) = handle(interpreter.tick(timeMs))

    fun click(button: MouseButton) = handle(listOf(InputAction.Click(button)))

    /** On-screen left button held: subsequent movement drags. */
    fun setLeftButtonHeld(held: Boolean) {
        if (held == leftButtonHeld) return
        leftButtonHeld = held
        handle(listOf(if (held) InputAction.DragStart else InputAction.DragEnd))
    }

    /** Moves the cursor to a position inside the active desktop (px). Used by tests and keyboard accessibility. */
    fun warpTo(x: Float, y: Float) { pointer.warpTo(x, y); publish() }

    /** Gets keys first (the running Windows program takes them while its window is focused). */
    var keyInterceptor: ((android.view.KeyEvent) -> Boolean)? = null

    /** Forwards a key event to the active desktop surface (used when the phone window has focus). */
    fun forwardKey(event: android.view.KeyEvent): Boolean {
        if (keyInterceptor?.invoke(event) == true) return true
        val t = targets[_activeTargetId.value ?: return false] ?: return false
        return t.rootView.dispatchKeyEvent(event)
    }

    /** Types text into the focused field of the active desktop using virtual key events. */
    fun typeText(text: String) {
        val map = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
        map.getEvents(text.toCharArray())?.forEach { forwardKey(it) }
    }

    fun pressKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        forwardKey(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0))
        forwardKey(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, code, 0))
    }

    fun markPhysicalMouseUsed() { if (!dispatching) _active.value = false }
    fun show() { _active.value = true }
    fun hide() { _active.value = false }

    fun handle(actions: List<InputAction>) {
        if (actions.isEmpty()) return
        _active.value = true
        for (a in actions) {
            val effective = if (leftButtonHeld && a is InputAction.Move) InputAction.DragMove(a.dx, a.dy) else a
            val d = pointer.apply(effective) ?: continue
            deliver(d)
        }
        publish()
    }

    private fun publish() { _state.value = pointer.state }

    private fun deliver(d: PointerDispatch) {
        recentDispatches.addLast(d); while (recentDispatches.size > 50) recentDispatches.removeFirst()
        val t = targets[_activeTargetId.value ?: return] ?: return
        val view = t.rootView
        dispatching = true
        try {
            when (d) {
                is PointerDispatch.Tap -> if (d.button == MouseButton.LEFT) {
                    val now = SystemClock.uptimeMillis()
                    touch(view, now, now, MotionEvent.ACTION_DOWN, t.originX + d.x, t.originY + d.y)
                    touch(view, now, now + 1, MotionEvent.ACTION_UP, t.originX + d.x, t.originY + d.y)
                } else {
                    secondaryClick(view, t.originX + d.x, t.originY + d.y)
                }
                is PointerDispatch.Down -> { dragDownTime = SystemClock.uptimeMillis(); touch(view, dragDownTime, dragDownTime, MotionEvent.ACTION_DOWN, t.originX + d.x, t.originY + d.y) }
                is PointerDispatch.MoveTo -> touch(view, dragDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, t.originX + d.x, t.originY + d.y)
                is PointerDispatch.Up -> touch(view, dragDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, t.originX + d.x, t.originY + d.y)
                is PointerDispatch.Scroll -> scroll(view, t.originX + d.x, t.originY + d.y, d.dx, d.dy)
            }
        } finally {
            dispatching = false
        }
    }

    private var dragDownTime = 0L

    private fun touch(view: View, downTime: Long, time: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, time, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun mouseEvent(downTime: Long, time: Long, action: Int, x: Float, y: Float, buttons: Int, vscroll: Float = 0f, hscroll: Float = 0f): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
            setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
            setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll)
        })
        return MotionEvent.obtain(downTime, time, action, 1, props, coords, 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    private fun secondaryClick(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to MotionEvent.BUTTON_SECONDARY, MotionEvent.ACTION_UP to 0).forEach { (action, buttons) ->
            val e = mouseEvent(now, now, action, x, y, buttons)
            view.dispatchTouchEvent(e); e.recycle()
        }
    }

    private fun scroll(view: View, x: Float, y: Float, dx: Float, dy: Float) {
        val vc = ViewConfiguration.get(view.context)
        val vFactor = vc.scaledVerticalScrollFactor.takeIf { it > 0f } ?: 64f
        val hFactor = vc.scaledHorizontalScrollFactor.takeIf { it > 0f } ?: 64f
        val now = SystemClock.uptimeMillis()
        // Wheel "down" is a negative AXIS_VSCROLL value.
        val e = mouseEvent(now, now, MotionEvent.ACTION_SCROLL, x, y, 0, vscroll = -dy / vFactor, hscroll = dx / hFactor)
        view.dispatchGenericMotionEvent(e); e.recycle()
    }
}
