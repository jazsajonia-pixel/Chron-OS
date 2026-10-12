package com.chronos.pocketos.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import com.chronos.pocketos.core.input.InputDeviceInfo
import com.chronos.pocketos.core.input.PhysicalInputSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Watches connected input devices with the public InputManager API. No permissions required. */
class PhysicalInputMonitor(context: Context) : InputManager.InputDeviceListener {
    private val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val _summary = MutableStateFlow(PhysicalInputSummary())
    val summary: StateFlow<PhysicalInputSummary> = _summary.asStateFlow()

    init {
        im.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        val devices = im.inputDeviceIds.toList().mapNotNull { id -> im.getInputDevice(id) }.map { d -> d.toInfo() }
        _summary.value = PhysicalInputSummary.from(devices)
    }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    companion object {
        fun InputDevice.toInfo(): InputDeviceInfo = InputDeviceInfo(
            name = name ?: "Unknown",
            isVirtual = isVirtual,
            isExternal = if (Build.VERSION.SDK_INT >= 29) isExternal else null,
            hasMouseSource = supportsSource(InputDevice.SOURCE_MOUSE),
            hasTouchpadSource = supportsSource(InputDevice.SOURCE_TOUCHPAD),
            isAlphabeticKeyboard = keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC,
            hasGamepadSource = supportsSource(InputDevice.SOURCE_GAMEPAD) || supportsSource(InputDevice.SOURCE_JOYSTICK),
        )
    }
}
