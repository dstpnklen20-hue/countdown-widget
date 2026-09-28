package com.claudecode.countdown.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import com.claudecode.countdown.ThemeManager

/** Maps the app's own theme presets (ThemeManager) onto a Material 3 color scheme. */
@Composable
fun TikTakTheme(palette: ThemeManager.Palette, content: @Composable () -> Unit) {
    val bg = Color(palette.bg)
    val surface = Color(palette.surface)
    val text = Color(palette.text)
    val accent = Color(palette.accent)
    val base = if (palette.isDark) darkColorScheme() else lightColorScheme()
    val container = Color(ColorUtils.blendARGB(palette.accent, palette.bg, 0.78f))
    val raised = Color(ColorUtils.blendARGB(palette.surface, palette.bg, 0.5f))
    val scheme = base.copy(
        primary = accent,
        onPrimary = Color(palette.onAccent),
        primaryContainer = container,
        onPrimaryContainer = text,
        secondary = accent,
        onSecondary = Color(palette.onAccent),
        secondaryContainer = container,
        onSecondaryContainer = text,
        tertiary = accent,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = surface,
        onSurfaceVariant = Color(palette.textSecondary),
        surfaceContainerLowest = bg,
        surfaceContainerLow = raised,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
        surfaceTint = accent,
        outline = Color(palette.textSecondary),
        outlineVariant = Color(ColorUtils.blendARGB(palette.text, palette.bg, 0.85f)),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
