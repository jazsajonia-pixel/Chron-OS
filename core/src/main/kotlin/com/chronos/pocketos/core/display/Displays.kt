package com.chronos.pocketos.core.display

import kotlin.math.roundToInt

/** Platform-neutral snapshot of an Android Display. */
data class DisplayInfo(
    val id: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRate: Float,
    val isDefault: Boolean,
    /** Display.FLAG_PRESENTATION: the system allows Presentation content on it. */
    val isPresentation: Boolean,
    /** Display.FLAG_PRIVATE: owned by another app (e.g. a virtual display); cannot be used. */
    val isPrivate: Boolean,
    val isOn: Boolean,
    /** Modes the display itself reports as supported (empty if unknown). */
    val modes: List<DisplayModeInfo> = emptyList(),
    val currentModeId: Int? = null,
) {
    val widthDp: Int get() = if (densityDpi > 0) (widthPx * 160f / densityDpi).roundToInt() else widthPx
    val heightDp: Int get() = if (densityDpi > 0) (heightPx * 160f / densityDpi).roundToInt() else heightPx
}

data class DisplayModeInfo(val id: Int, val widthPx: Int, val heightPx: Int, val refreshRate: Float) {
    val label: String get() = "${widthPx}×${heightPx} @ ${refreshRate.toInt()} Hz"
}

sealed interface ExternalDisplayStatus {
    /** A usable external display is available. */
    data class Available(val display: DisplayInfo) : ExternalDisplayStatus
    data object NoneConnected : ExternalDisplayStatus
    data class Unusable(val display: DisplayInfo, val reason: String) : ExternalDisplayStatus
}

object ExternalDisplayPolicy {
    fun evaluate(displays: List<DisplayInfo>): ExternalDisplayStatus {
        val external = displays.filter { !it.isDefault }
        if (external.isEmpty()) return ExternalDisplayStatus.NoneConnected
        val usable = external.filter { it.isPresentation && !it.isPrivate && it.isOn && it.widthPx >= 640 && it.heightPx >= 480 }
        if (usable.isNotEmpty()) return ExternalDisplayStatus.Available(usable.maxBy { it.widthPx.toLong() * it.heightPx })
        val d = external.first()
        val reason = when {
            d.isPrivate -> "\"${d.name}\" belongs to another app (for example screen recording or casting) and cannot show PocketOS."
            !d.isPresentation -> "\"${d.name}\" does not allow app content. It may be mirroring only."
            !d.isOn -> "\"${d.name}\" is connected but switched off or asleep."
            else -> "\"${d.name}\" is too small (${d.widthPx}×${d.heightPx}) for a desktop."
        }
        return ExternalDisplayStatus.Unusable(d, reason)
    }

    /** Plain-language explanation for the Settings page. */
    fun explain(status: ExternalDisplayStatus): String = when (status) {
        is ExternalDisplayStatus.Available -> "Connected: ${status.display.name} (${status.display.widthPx}×${status.display.heightPx}). PocketOS can show a separate desktop on it."
        ExternalDisplayStatus.NoneConnected ->
            "No external display detected. Video output needs a phone whose USB-C port supports DisplayPort Alt Mode (or a " +
                "compatible dock/adapter, or a wireless display the phone supports). Many phones charge and transfer data over " +
                "USB-C but cannot output video. Check your phone's specifications."
        is ExternalDisplayStatus.Unusable -> status.reason
    }
}

/** Chooses the density used to render the desktop on an external display. */
object DesktopScale {
    const val MIN_DESKTOP_DP = 1024
    const val MAX_DESKTOP_DP = 1920

    /**
     * Returns a Compose density (px per dp). Keeps the desktop between 1024 and 1920 dp wide so text stays readable on
     * large monitors and windows still fit on small ones. [userScale] (>1 = bigger UI) multiplies the result.
     */
    fun densityFor(widthPx: Int, densityDpi: Int, userScale: Float = 1f): Float {
        val base = if (densityDpi > 0) densityDpi / 160f else 1f
        val baseDp = widthPx / base
        val targetDp = baseDp.coerceIn(MIN_DESKTOP_DP.toFloat(), MAX_DESKTOP_DP.toFloat()).coerceAtMost(widthPx.toFloat())
        val density = widthPx / targetDp
        return (density * userScale.coerceIn(0.5f, 2f)).coerceAtLeast(0.5f)
    }
}

enum class SessionPhase { IDLE, ACTIVE, STOPPED_BY_USER }

data class DisplaySessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val displayId: Int? = null,
    val status: ExternalDisplayStatus = ExternalDisplayStatus.NoneConnected,
    val lastEvent: String? = null,
)

/**
 * Pure reducer for the external-desktop session.
 * - Auto-starts when a usable display appears (if enabled), unless the user stopped it for that display.
 * - Ends the session when the display disappears or becomes unusable; data lives in the shell, so nothing is lost.
 */
object DisplaySessionReducer {
    fun onDisplaysChanged(state: DisplaySessionState, displays: List<DisplayInfo>, autoStart: Boolean): DisplaySessionState {
        val status = ExternalDisplayPolicy.evaluate(displays)
        return when (status) {
            is ExternalDisplayStatus.Available -> when {
                state.phase == SessionPhase.ACTIVE && state.displayId == status.display.id -> state.copy(status = status)
                state.phase == SessionPhase.ACTIVE -> state.copy(displayId = status.display.id, status = status, lastEvent = "Moved desktop to ${status.display.name}")
                state.phase == SessionPhase.STOPPED_BY_USER && state.displayId == status.display.id -> state.copy(status = status)
                autoStart -> DisplaySessionState(SessionPhase.ACTIVE, status.display.id, status, "Started desktop on ${status.display.name}")
                else -> state.copy(phase = SessionPhase.IDLE, status = status)
            }
            else -> {
                val ended = state.phase == SessionPhase.ACTIVE
                DisplaySessionState(SessionPhase.IDLE, null, status, if (ended) "External display disconnected. Your windows moved back to the phone." else state.lastEvent)
            }
        }
    }

    fun start(state: DisplaySessionState): DisplaySessionState {
        val s = state.status as? ExternalDisplayStatus.Available ?: return state
        return state.copy(phase = SessionPhase.ACTIVE, displayId = s.display.id, lastEvent = "Started desktop on ${s.display.name}")
    }

    fun stop(state: DisplaySessionState): DisplaySessionState =
        if (state.phase != SessionPhase.ACTIVE) state else state.copy(phase = SessionPhase.STOPPED_BY_USER, lastEvent = "Desktop returned to the phone")
}
