package com.chronos.pocketos.core

import com.chronos.pocketos.core.input.GestureConfig
import com.chronos.pocketos.core.input.InputAction
import com.chronos.pocketos.core.input.InputDeviceInfo
import com.chronos.pocketos.core.input.Key
import com.chronos.pocketos.core.input.KeyChord
import com.chronos.pocketos.core.input.MouseButton
import com.chronos.pocketos.core.input.PhysicalInputSummary
import com.chronos.pocketos.core.input.PointerController
import com.chronos.pocketos.core.input.PointerDispatch
import com.chronos.pocketos.core.input.ShellCommand
import com.chronos.pocketos.core.input.ShortcutMap
import com.chronos.pocketos.core.input.TouchPoint
import com.chronos.pocketos.core.input.TouchpadDecision
import com.chronos.pocketos.core.input.TouchpadGestureInterpreter
import com.chronos.pocketos.core.input.TouchpadPolicy
import com.chronos.pocketos.core.settings.TouchpadVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTest {
    private fun p(x: Float, y: Float, id: Long = 0) = TouchPoint(id, x, y)

    @Test fun singleTapIsLeftClick() {
        val g = TouchpadGestureInterpreter()
        assertTrue(g.onTouch(0, listOf(p(10f, 10f))).isEmpty())
        assertEquals(listOf(InputAction.Click(MouseButton.LEFT, 1)), g.onTouch(100, emptyList()))
    }

    @Test fun tapToClickDisabledProducesNoClick() {
        val g = TouchpadGestureInterpreter(GestureConfig(tapToClick = false))
        g.onTouch(0, listOf(p(10f, 10f)))
        assertTrue(g.onTouch(100, emptyList()).isEmpty())
    }

    @Test fun slowTouchIsNotATap() {
        val g = TouchpadGestureInterpreter()
        g.onTouch(0, listOf(p(10f, 10f)))
        assertTrue(g.onTouch(400, emptyList()).isEmpty())
    }

    @Test fun doubleTapGivesClickCountTwo() {
        val g = TouchpadGestureInterpreter()
        g.onTouch(0, listOf(p(10f, 10f))); g.onTouch(80, emptyList())
        g.onTouch(200, listOf(p(11f, 10f)))
        assertEquals(listOf(InputAction.Click(MouseButton.LEFT, 2)), g.onTouch(260, emptyList()))
        // A third tap later is a fresh single click.
        g.onTouch(1000, listOf(p(10f, 10f)))
        assertEquals(listOf(InputAction.Click(MouseButton.LEFT, 1)), g.onTouch(1080, emptyList()))
    }

    @Test fun movingFingerMovesCursorWithSpeed() {
        val slow = TouchpadGestureInterpreter(GestureConfig(speed = 1f))
        val fast = TouchpadGestureInterpreter(GestureConfig(speed = 2f))
        fun run(g: TouchpadGestureInterpreter): Float {
            g.onTouch(0, listOf(p(0f, 0f)))
            val a = g.onTouch(16, listOf(p(20f, 0f))) + g.onTouch(32, listOf(p(40f, 0f)))
            assertTrue(g.onTouch(48, emptyList()).isEmpty()) // movement is never a click
            return a.filterIsInstance<InputAction.Move>().sumOf { it.dx.toDouble() }.toFloat()
        }
        val s = run(slow); val f = run(fast)
        assertTrue(s > 0f)
        assertEquals(2f, f / s, 0.01f)
    }

    @Test fun tapAndDragProducesDragSequence() {
        val g = TouchpadGestureInterpreter()
        g.onTouch(0, listOf(p(0f, 0f))); g.onTouch(80, emptyList())
        g.onTouch(180, listOf(p(0f, 0f)))
        val moved = g.onTouch(200, listOf(p(30f, 0f)))
        assertEquals(InputAction.DragStart, moved.first())
        assertTrue(moved[1] is InputAction.DragMove)
        assertTrue(g.isDragging)
        assertEquals(listOf(InputAction.DragEnd), g.onTouch(300, emptyList()))
    }

    @Test fun longPressStartsDrag() {
        val g = TouchpadGestureInterpreter()
        g.onTouch(0, listOf(p(0f, 0f)))
        assertTrue(g.tick(300).isEmpty())
        assertEquals(listOf(InputAction.DragStart), g.tick(600))
        assertEquals(listOf(InputAction.DragEnd), g.onTouch(900, emptyList()))
    }

    @Test fun twoFingerTapIsRightClick() {
        val g = TouchpadGestureInterpreter()
        g.onTouch(0, listOf(p(0f, 0f, 0)))
        g.onTouch(10, listOf(p(0f, 0f, 0), p(40f, 0f, 1)))
        assertEquals(listOf(InputAction.Click(MouseButton.RIGHT)), g.onTouch(120, emptyList()))
    }

    @Test fun twoFingerMoveScrollsWithDirectionSetting() {
        fun firstScroll(natural: Boolean): InputAction.Scroll {
            val g = TouchpadGestureInterpreter(GestureConfig(naturalScroll = natural))
            g.onTouch(0, listOf(p(0f, 0f, 0), p(40f, 0f, 1)))
            g.onTouch(16, listOf(p(0f, 0f, 0), p(40f, 0f, 1)))
            val a = g.onTouch(32, listOf(p(0f, 30f, 0), p(40f, 30f, 1)))
            assertTrue(g.onTouch(48, emptyList()).isEmpty())
            return a.filterIsInstance<InputAction.Scroll>().single()
        }
        // Fingers moved down by 30px.
        assertTrue(firstScroll(false).dy > 0)
        assertTrue(firstScroll(true).dy < 0)
    }

    @Test fun pointerControllerClampsAndDispatches() {
        val c = PointerController(); c.setBounds(100f, 50f)
        assertEquals(50f, c.state.x); assertEquals(25f, c.state.y)
        c.apply(InputAction.Move(1000f, 1000f))
        assertEquals(99f, c.state.x); assertEquals(49f, c.state.y)
        assertEquals(PointerDispatch.Tap(99f, 49f, MouseButton.LEFT), c.apply(InputAction.Click(MouseButton.LEFT)))
        assertEquals(PointerDispatch.Down(99f, 49f), c.apply(InputAction.DragStart))
        assertEquals(PointerDispatch.MoveTo(89f, 49f), c.apply(InputAction.DragMove(-10f, 0f)))
        assertEquals(PointerDispatch.Up(89f, 49f), c.apply(InputAction.DragEnd))
        c.setBounds(50f, 20f)
        assertEquals(49f, c.state.x); assertEquals(19f, c.state.y)
    }

    @Test fun shortcutsResolveAndRespectSettings() {
        val alttab = KeyChord(Key.TAB, alt = true)
        assertEquals(ShellCommand.NEXT_WINDOW, ShortcutMap.resolve(alttab, true, emptySet()))
        assertNull(ShortcutMap.resolve(alttab, false, emptySet()))
        assertNull(ShortcutMap.resolve(alttab, true, setOf("NEXT_WINDOW")))
        assertNull(ShortcutMap.resolve(KeyChord(Key.TAB), true, emptySet()))
        assertEquals("Ctrl+Alt+Left", ShortcutMap.chordsFor(ShellCommand.SNAP_LEFT).single().describe())
    }

    @Test fun physicalInputDetectionExcludesBuiltInAndVirtual() {
        val devices = listOf(
            InputDeviceInfo("Virtual", true, false, false, false, true, false),
            InputDeviceInfo("touchscreen", false, false, false, false, false, false),
            InputDeviceInfo("gpio-keys", false, false, false, false, false, false),
            InputDeviceInfo("Logitech Mouse", false, true, true, false, false, false),
            InputDeviceInfo("BT Keyboard", false, true, false, false, true, false),
            InputDeviceInfo("Old API keyboard", false, null, false, false, true, false),
        )
        val s = PhysicalInputSummary.from(devices)
        assertEquals(listOf("Logitech Mouse"), s.mice)
        assertEquals(listOf("BT Keyboard", "Old API keyboard"), s.keyboards)
        assertEquals(TouchpadDecision.HIDE, TouchpadPolicy.decide(TouchpadVisibility.AUTO, s))
        assertEquals(TouchpadDecision.SHOW, TouchpadPolicy.decide(TouchpadVisibility.AUTO, PhysicalInputSummary()))
        assertEquals(TouchpadDecision.SHOW, TouchpadPolicy.decide(TouchpadVisibility.ALWAYS, s))
        assertEquals(TouchpadDecision.HIDE, TouchpadPolicy.decide(TouchpadVisibility.NEVER, PhysicalInputSummary()))
    }
}
