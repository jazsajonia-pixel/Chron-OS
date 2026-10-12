package com.chronos.pocketos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.chronos.pocketos.core.settings.ThemeMode
import com.chronos.pocketos.core.settings.Wallpaper

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5AC8FA), onPrimary = Color(0xFF00243A),
    secondary = Color(0xFFFFB547), onSecondary = Color(0xFF3A2400),
    background = Color(0xFF0E1424), onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF172036), onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF222D47), onSurfaceVariant = Color(0xFFC0C8DA),
    surfaceContainer = Color(0xFF1B2540), outline = Color(0xFF55617A),
    error = Color(0xFFFF8A80), onError = Color(0xFF3A0000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00658F), onPrimary = Color.White,
    secondary = Color(0xFF8A5300), onSecondary = Color.White,
    background = Color(0xFFF4F6FB), onBackground = Color(0xFF151B28),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF151B28),
    surfaceVariant = Color(0xFFE3E8F2), onSurfaceVariant = Color(0xFF424A5C),
    surfaceContainer = Color(0xFFEDF1F8), outline = Color(0xFF737C8F),
    error = Color(0xFFB3261E), onError = Color.White,
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun PocketOsTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDark(mode)) DarkColors else LightColors, content = content)
}

fun wallpaperBrush(w: Wallpaper, dark: Boolean): Brush = when (w) {
    Wallpaper.AURORA -> if (dark) Brush.linearGradient(listOf(Color(0xFF0B1630), Color(0xFF173F6B), Color(0xFF1C7A8C)))
    else Brush.linearGradient(listOf(Color(0xFFBFE3FF), Color(0xFF8EC5FC), Color(0xFFE0C3FC)))
    Wallpaper.DUSK -> if (dark) Brush.linearGradient(listOf(Color(0xFF1A1033), Color(0xFF4A1F4F), Color(0xFF9C4A3C)))
    else Brush.linearGradient(listOf(Color(0xFFFFE1C6), Color(0xFFF7B7A3), Color(0xFFD4A5E8)))
    Wallpaper.FOREST -> if (dark) Brush.linearGradient(listOf(Color(0xFF07201A), Color(0xFF145A45), Color(0xFF3D7B3A)))
    else Brush.linearGradient(listOf(Color(0xFFD7F5E3), Color(0xFFA8E6CF), Color(0xFFDCEDC1)))
    Wallpaper.SLATE -> if (dark) Brush.linearGradient(listOf(Color(0xFF111418), Color(0xFF262B33)))
    else Brush.linearGradient(listOf(Color(0xFFE9ECF1), Color(0xFFCFD5DE)))
}
