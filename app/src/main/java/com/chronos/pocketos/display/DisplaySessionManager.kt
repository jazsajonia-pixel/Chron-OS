package com.chronos.pocketos.display

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.chronos.pocketos.core.display.DisplayInfo
import com.chronos.pocketos.core.display.DisplayModeInfo
import com.chronos.pocketos.core.display.DisplaySessionReducer
import com.chronos.pocketos.core.display.DisplaySessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches displays through the public DisplayManager API and maintains [DisplaySessionState].
 * Rendering (Presentation) is done by the activity, which observes [state].
 */
class DisplaySessionManager(private val context: Context, private val autoStart: () -> Boolean) : DisplayManager.DisplayListener {
    private val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val _state = MutableStateFlow(DisplaySessionState())
    val state: StateFlow<DisplaySessionState> = _state.asStateFlow()
    private val _displays = MutableStateFlow<List<DisplayInfo>>(emptyList())
    val displays: StateFlow<List<DisplayInfo>> = _displays.asStateFlow()
    val eventLog = ArrayDeque<String>()
    private var listening = false

    val supportsActivitiesOnSecondaryDisplays: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)

    fun startListening() {
        if (listening) return
        listening = true
        dm.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        refresh("Started monitoring displays")
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        dm.unregisterDisplayListener(this)
    }

    fun refresh(reason: String) {
        val list = dm.displays.map { it.toInfo() }
        _displays.value = list
        val next = DisplaySessionReducer.onDisplaysChanged(_state.value, list, autoStart())
        log("$reason → ${next.phase}${next.displayId?.let { " (display $it)" } ?: ""}")
        _state.value = next
    }

    fun startSession() { _state.value = DisplaySessionReducer.start(_state.value); log("User started external desktop") }
    fun stopSession() { _state.value = DisplaySessionReducer.stop(_state.value); log("User returned desktop to phone") }

    /** Presentation failed to attach (e.g. InvalidDisplayException). Fall back safely to the phone. */
    fun reportFailure(message: String) {
        log("Presentation failed: $message")
        _state.value = DisplaySessionReducer.stop(_state.value).copy(lastEvent = "Could not show the desktop on this display: $message")
    }

    fun display(id: Int): Display? = dm.getDisplay(id)

    override fun onDisplayAdded(displayId: Int) = refresh("Display $displayId added")
    override fun onDisplayRemoved(displayId: Int) = refresh("Display $displayId removed")
    override fun onDisplayChanged(displayId: Int) = refresh("Display $displayId changed")

    private fun log(s: String) { eventLog.addLast(s); while (eventLog.size > 40) eventLog.removeFirst() }

    companion object {
        @Suppress("DEPRECATION")
        fun Display.toInfo(): DisplayInfo {
            val metrics = android.util.DisplayMetrics().also { getRealMetrics(it) }
            return DisplayInfo(
                id = displayId,
                name = name ?: "Display $displayId",
                widthPx = metrics.widthPixels,
                heightPx = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                refreshRate = runCatching { refreshRate }.getOrDefault(0f),
                isDefault = displayId == Display.DEFAULT_DISPLAY,
                isPresentation = (flags and Display.FLAG_PRESENTATION) != 0,
                isPrivate = (flags and Display.FLAG_PRIVATE) != 0,
                isOn = state == Display.STATE_ON || state == Display.STATE_UNKNOWN,
                modes = runCatching { supportedModes.map { m -> DisplayModeInfo(m.modeId, m.physicalWidth, m.physicalHeight, m.refreshRate) } }
                    .getOrDefault(emptyList()),
                // getMode() throws if the active mode is missing from the supported list; report it as unknown.
                currentModeId = runCatching { mode.modeId }.getOrNull(),
            )
        }
    }
}
