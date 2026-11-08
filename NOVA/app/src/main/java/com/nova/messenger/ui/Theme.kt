package com.nova.messenger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Violet = Color(0xFF8B5CF6)
val Cyan = Color(0xFF22D3EE)
val Ink = Color(0xFF07080D)
val Graphite = Color(0xFF12141C)
val Navy = Color(0xFF0B1230)
val CardDark = Color(0xFF1A1D29)
val Gold = Color(0xFFFFC857)
val NovaGradient = Brush.linearGradient(listOf(Violet, Cyan))

private val Dark = darkColorScheme(
    primary = Violet, secondary = Cyan, background = Ink, surface = Graphite,
    surfaceVariant = CardDark, onBackground = Color(0xFFEDEEF5), onSurface = Color(0xFFEDEEF5),
    onSurfaceVariant = Color(0xFF9AA0B8), primaryContainer = Color(0xFF2A2250), outline = Color(0xFF3A3F55),
)
private val Light = lightColorScheme(
    primary = Color(0xFF6D3FE0), secondary = Color(0xFF0891B2), background = Color(0xFFF5F6FB),
    surface = Color.White, surfaceVariant = Color(0xFFE9EBF5), onBackground = Color(0xFF14161F),
    onSurface = Color(0xFF14161F), onSurfaceVariant = Color(0xFF5B6076), primaryContainer = Color(0xFFE4DAFF),
)

@Composable
fun NovaTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(), content = content)
}
