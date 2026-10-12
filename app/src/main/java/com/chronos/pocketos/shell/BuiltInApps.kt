package com.chronos.pocketos.shell

import androidx.compose.ui.graphics.Color

/** Static description of a PocketOS app that can be opened in a window. */
data class AppSpec(
    val id: String,
    val title: String,
    val glyph: String,
    val color: Color,
    val description: String,
    val defaultWidth: Int = 640,
    val defaultHeight: Int = 440,
    val singleInstance: Boolean = true,
)

object AppIds {
    const val FILES = "files"
    const val EDITOR = "editor"
    const val SETTINGS = "settings"
    const val SYSINFO = "sysinfo"
    const val CALCULATOR = "calculator"
    const val TOUCHPAD = "touchpad"
    const val WINDOWS_APPS = "winapps"
    const val DIAGNOSTICS = "diagnostics"
}

object BuiltInApps {
    val all: List<AppSpec> = listOf(
        AppSpec(AppIds.FILES, "Files", "▤", Color(0xFF3B82F6), "Browse PocketOS Home and folders you allow", 720, 480),
        AppSpec(AppIds.EDITOR, "Text Editor", "✎", Color(0xFF10B981), "Write and edit plain-text files", 640, 460, singleInstance = false),
        AppSpec(AppIds.CALCULATOR, "Calculator", "±", Color(0xFFF59E0B), "Everyday arithmetic", 320, 460),
        AppSpec(AppIds.WINDOWS_APPS, "PC Apps", "▣", Color(0xFF0EA5E9), "Install supported Windows programs (.exe) through a compatibility runtime", 720, 540),
        AppSpec(AppIds.SYSINFO, "System Info", "ⓘ", Color(0xFF8B5CF6), "Device capabilities and profile", 560, 520),
        AppSpec(AppIds.DIAGNOSTICS, "Diagnostics", "✚", Color(0xFFEF4444), "Event log, storage cleanup and compatibility reports", 640, 540),
        AppSpec(AppIds.SETTINGS, "Settings", "⚙", Color(0xFF64748B), "Appearance, input, display, privacy", 640, 520),
    )

    fun launcherApps(): List<AppSpec> = all
    fun find(id: String): AppSpec? = all.firstOrNull { it.id == id }
}
