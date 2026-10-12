package com.chronos.pocketos.core.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

enum class MouseButton { LEFT, RIGHT }

/** High-level pointer intents produced by the touchpad, independent of Android. */
sealed interface InputAction {
    data class Move(val dx: Float, val dy: Float) : InputAction
    data class Click(val button: MouseButton, val count: Int = 1) : InputAction
    data object DragStart : InputAction
    data class DragMove(val dx: Float, val dy: Float) : InputAction
    data object DragEnd : InputAction
    data class Scroll(val dx: Float, val dy: Float) : InputAction
}

data class TouchPoint(val id: Long, val x: Float, val y: Float)

data class GestureConfig(
    val speed: Float = 1f,
    val tapToClick: Boolean = true,
    val naturalScroll: Boolean = false,
    /** Movement (px) below which a touch is still considered a tap. */
    val touchSlop: Float = 12f,
    val tapTimeoutMs: Long = 220,
    val doubleTapWindowMs: Long = 300,
    val longPressMs: Long = 550,
    val scrollScale: Float = 1f,
)

/**
 * Pure touchpad gesture state machine.
 *
 * - One finger move → [InputAction.Move] (with mild acceleration).
 * - One finger tap → left click (if tap-to-click). A second tap within the double-tap window → click count 2.
 * - Tap, then touch again and move (tap-and-drag) or long-press then move → drag.
 * - Two finger tap → right click. Two finger move → scroll.
 *
 * Call [onTouch] with the full set of pointers currently down and [tick] periodically while touching.
 */
class TouchpadGestureInterpreter(var config: GestureConfig = GestureConfig()) {
    private enum class Mode { IDLE, PENDING, MOVING, DRAGGING, SCROLLING, MULTI_PENDING }

    private var mode = Mode.IDLE
    private var downTime = 0L
    private var maxPointers = 0
    private var startCentroid = 0f to 0f
    private var lastCentroid = 0f to 0f
    private var travelled = 0f
    private var lastTapUpTime = Long.MIN_VALUE / 2
    private var lastTapCount = 0
    private var dragCandidate = false
    private var lastPointerCount = 0

    val isDragging: Boolean get() = mode == Mode.DRAGGING

    fun reset() {
        mode = Mode.IDLE; maxPointers = 0; travelled = 0f; dragCandidate = false; lastPointerCount = 0
    }

    fun onTouch(timeMs: Long, pointers: List<TouchPoint>): List<InputAction> {
        val out = mutableListOf<InputAction>()
        val count = pointers.size
        if (count == 0) {
            out += finish(timeMs)
            lastPointerCount = 0
            return out
        }
        val c = centroid(pointers)
        if (mode == Mode.IDLE) {
            mode = if (count >= 2) Mode.MULTI_PENDING else Mode.PENDING
            downTime = timeMs
            maxPointers = count
            startCentroid = c; lastCentroid = c; travelled = 0f
            dragCandidate = timeMs - lastTapUpTime <= config.doubleTapWindowMs
            lastPointerCount = count
            return out
        }
        if (count != lastPointerCount) {
            // Finger count changed: re-anchor to avoid jumps.
            maxPointers = maxOf(maxPointers, count)
            lastCentroid = c
            lastPointerCount = count
            if (count >= 2 && mode == Mode.PENDING) mode = Mode.MULTI_PENDING
            if (count >= 2 && mode == Mode.MOVING) mode = Mode.MULTI_PENDING
            return out
        }
        val dx = c.first - lastCentroid.first
        val dy = c.second - lastCentroid.second
        lastCentroid = c
        travelled = hypot(c.first - startCentroid.first, c.second - startCentroid.second)

        when (mode) {
            Mode.PENDING -> if (travelled > config.touchSlop) {
                if (dragCandidate) { mode = Mode.DRAGGING; out += InputAction.DragStart; out += InputAction.DragMove(accel(dx), accel(dy)) }
                else { mode = Mode.MOVING; out += InputAction.Move(accel(dx), accel(dy)) }
            }
            Mode.MOVING -> out += InputAction.Move(accel(dx), accel(dy))
            Mode.DRAGGING -> out += InputAction.DragMove(accel(dx), accel(dy))
            Mode.MULTI_PENDING -> if (travelled > config.touchSlop && count >= 2) { mode = Mode.SCROLLING; out += scroll(dx, dy) }
            Mode.SCROLLING -> if (count >= 2) out += scroll(dx, dy)
            Mode.IDLE -> Unit
        }
        return out
    }

    /** Call periodically while a finger is down; detects long-press → drag start. */
    fun tick(timeMs: Long): List<InputAction> {
        if (mode == Mode.PENDING && maxPointers == 1 && travelled <= config.touchSlop && timeMs - downTime >= config.longPressMs) {
            mode = Mode.DRAGGING
            return listOf(InputAction.DragStart)
        }
        return emptyList()
    }

    private fun finish(timeMs: Long): List<InputAction> {
        val out = mutableListOf<InputAction>()
        val duration = timeMs - downTime
        when (mode) {
            Mode.PENDING -> if (duration <= config.tapTimeoutMs || dragCandidate) {
                if (config.tapToClick) {
                    val count = if (dragCandidate && lastTapCount >= 1) 2 else 1
                    out += InputAction.Click(MouseButton.LEFT, count)
                    lastTapCount = count
                    lastTapUpTime = if (count == 2) Long.MIN_VALUE / 2 else timeMs
                }
            }
            Mode.MULTI_PENDING -> if (maxPointers == 2 && duration <= config.tapTimeoutMs * 2) {
                out += InputAction.Click(MouseButton.RIGHT)
                lastTapUpTime = Long.MIN_VALUE / 2
            }
            Mode.DRAGGING -> out += InputAction.DragEnd
            else -> Unit
        }
        if (mode != Mode.PENDING) lastTapCount = 0
        mode = Mode.IDLE
        maxPointers = 0
        dragCandidate = false
        return out
    }

    /**
     * Positive [InputAction.Scroll.dy] means "scroll toward the end of the content" (like a mouse wheel turned down).
     * Traditional: fingers moving down scroll down. Natural: content follows the fingers.
     */
    private fun scroll(dx: Float, dy: Float): InputAction.Scroll {
        val sign = if (config.naturalScroll) -1f else 1f
        return InputAction.Scroll(sign * dx * config.scrollScale, sign * dy * config.scrollScale)
    }

    private fun accel(d: Float): Float {
        val factor = config.speed * (1f + min(abs(d) / 24f, 1.5f))
        return d * factor
    }

    private fun centroid(p: List<TouchPoint>): Pair<Float, Float> =
        p.sumOf { it.x.toDouble() }.toFloat() / p.size to p.sumOf { it.y.toDouble() }.toFloat() / p.size
}

data class PointerState(val x: Float = 0f, val y: Float = 0f, val pressed: Boolean = false)

/** Requests the host should deliver to PocketOS-managed content at the cursor position. */
sealed interface PointerDispatch {
    data class Tap(val x: Float, val y: Float, val button: MouseButton) : PointerDispatch
    data class Down(val x: Float, val y: Float) : PointerDispatch
    data class MoveTo(val x: Float, val y: Float) : PointerDispatch
    data class Up(val x: Float, val y: Float) : PointerDispatch
    data class Scroll(val x: Float, val y: Float, val dx: Float, val dy: Float) : PointerDispatch
}

/** Keeps the cursor inside the target surface and converts actions into dispatches. */
class PointerController(width: Float = 1f, height: Float = 1f) {
    var width = width; private set
    var height = height; private set
    var state = PointerState(width / 2, height / 2); private set

    fun setBounds(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val firstLayout = width <= 1f && height <= 1f
        width = w; height = h
        state = if (firstLayout) state.copy(x = w / 2, y = h / 2) else state.copy(x = state.x.coerceIn(0f, w - 1), y = state.y.coerceIn(0f, h - 1))
    }

    fun warpTo(x: Float, y: Float) { state = state.copy(x = x.coerceIn(0f, width - 1), y = y.coerceIn(0f, height - 1)) }

    fun apply(action: InputAction): PointerDispatch? = when (action) {
        is InputAction.Move -> { moveBy(action.dx, action.dy); null }
        is InputAction.Click -> PointerDispatch.Tap(state.x, state.y, action.button)
        InputAction.DragStart -> { state = state.copy(pressed = true); PointerDispatch.Down(state.x, state.y) }
        is InputAction.DragMove -> { moveBy(action.dx, action.dy); if (state.pressed) PointerDispatch.MoveTo(state.x, state.y) else null }
        InputAction.DragEnd -> { state = state.copy(pressed = false); PointerDispatch.Up(state.x, state.y) }
        is InputAction.Scroll -> PointerDispatch.Scroll(state.x, state.y, action.dx, action.dy)
    }

    private fun moveBy(dx: Float, dy: Float) {
        state = state.copy(x = (state.x + dx).coerceIn(0f, width - 1), y = (state.y + dy).coerceIn(0f, height - 1))
    }
}
