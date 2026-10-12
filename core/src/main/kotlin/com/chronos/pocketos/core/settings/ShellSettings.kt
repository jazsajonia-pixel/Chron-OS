package com.chronos.pocketos.core.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Wallpaper { AURORA, DUSK, FOREST, SLATE }

/** Explicit user choice for the performance profile; AUTO lets PocketOS pick from device capabilities. */
enum class ProfileChoice { AUTO, BATTERY_SAVER, BALANCED, PERFORMANCE }

enum class TouchpadVisibility { AUTO, ALWAYS, NEVER }

@Serializable
data class ShellSettings(
    val version: Int = 1,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val wallpaper: Wallpaper = Wallpaper.AURORA,
    val showDesktopIcons: Boolean = true,
    val use24HourClock: Boolean = false,
    // Input
    val cursorSpeed: Float = 1.0f,
    val cursorSizeScale: Float = 1.0f,
    val tapToClick: Boolean = true,
    val naturalScroll: Boolean = false,
    val showSmartCursor: Boolean = true,
    val touchpadVisibility: TouchpadVisibility = TouchpadVisibility.AUTO,
    val shortcutsEnabled: Boolean = true,
    val disabledShortcuts: Set<String> = emptySet(),
    val touchpadClickButtons: Boolean = true,
    // Display & performance
    val profileChoice: ProfileChoice = ProfileChoice.AUTO,
    /** null = automatic UI scale. */
    val uiScaleOverride: Float? = null,
    val reduceMotion: Boolean = false,
    val autoStartDesktopOnExternalDisplay: Boolean = true,
    /** Ask the monitor for the recommended resolution (only modes it reports). Off = keep the system's choice. */
    val useRecommendedDisplayMode: Boolean = false,
    // Onboarding
    val onboardingCompleted: Boolean = false,
) {
    fun sanitized(): ShellSettings = copy(
        cursorSpeed = cursorSpeed.coerceIn(MIN_SPEED, MAX_SPEED),
        cursorSizeScale = cursorSizeScale.coerceIn(MIN_CURSOR_SCALE, MAX_CURSOR_SCALE),
        uiScaleOverride = uiScaleOverride?.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE),
    )

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 3.0f
        const val MIN_CURSOR_SCALE = 0.75f
        const val MAX_CURSOR_SCALE = 2.5f
        const val MIN_UI_SCALE = 0.75f
        const val MAX_UI_SCALE = 1.6f
    }
}

/** JSON codec tolerant of unknown/missing keys so older saved settings keep loading. */
object SettingsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    fun encode(settings: ShellSettings): String = json.encodeToString(ShellSettings.serializer(), settings)

    fun decode(text: String?): ShellSettings {
        if (text.isNullOrBlank()) return ShellSettings()
        return runCatching { json.decodeFromString(ShellSettings.serializer(), text).sanitized() }
            .getOrDefault(ShellSettings())
    }
}
