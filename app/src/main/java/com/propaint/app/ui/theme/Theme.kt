package com.propaint.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6CB4EE),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE2E2E2),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFCACACA),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE2E2E2),
    outline = Color(0xFF444444),
)

@Composable
fun ProPaintTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
