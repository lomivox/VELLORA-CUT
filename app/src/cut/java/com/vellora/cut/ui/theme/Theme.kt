package com.vellora.cut.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CyanPrimary = Color(0xFF00C8C8)
val CyanVariant = Color(0xFF00D4D4)
val BackgroundDark = Color(0xFF0A0A0A)
val SurfaceDark = Color(0xFF161616)
val SurfaceVariant = Color(0xFF1A1A1A)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF888888)

private val VelloraDarkColors = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Color.Black,
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
