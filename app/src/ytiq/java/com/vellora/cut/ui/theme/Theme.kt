package com.vellora.cut.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// YTIQ look (navy + indigo). Same names as VELLORA CUT's theme (src/cut), different colors,
// so every screen picks up the new look without being edited.
val CyanPrimary = Color(0xFF6C7BFF)
val CyanVariant = Color(0xFF8794FF)
val BackgroundDark = Color(0xFF0D0E1A)
val SurfaceDark = Color(0xFF171926)
val SurfaceVariant = Color(0xFF262A3F)
val TextPrimary = Color(0xFFF2F3FA)
val TextSecondary = Color(0xFF9AA0B8)

private val VelloraDarkColors = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Color.White,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
)

@Composable
fun VelloraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VelloraDarkColors,
        content = content
    )
}
