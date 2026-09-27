package com.fplintbreak.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Same palette as frontend/src/index.css.
@Immutable
data class StatusColors(
    val red: Color,
    val redBg: Color,
    val amber: Color,
    val amberBg: Color,
    val green: Color,
    val greenBg: Color,
    val muted: Color,
    val border: Color,
)

private val LightStatus = StatusColors(
    red = Color(0xFFC62828), redBg = Color(0xFFFDECEA),
    amber = Color(0xFFA15C00), amberBg = Color(0xFFFFF4E0),
    green = Color(0xFF2E7D32), greenBg = Color(0xFFE8F5E9),
    muted = Color(0xFF676A73), border = Color(0xFFE3E1DA),
)

private val DarkStatus = StatusColors(
    red = Color(0xFFFF8A80), redBg = Color(0xFF3A1C1C),
    amber = Color(0xFFFFCC80), amberBg = Color(0xFF3A2C14),
    green = Color(0xFFA5D6A7), greenBg = Color(0xFF1B2E1D),
    muted = Color(0xFF9A9CA5), border = Color(0xFF2D2D35),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF37003C), // FPL purple
    onPrimary = Color.White,
    background = Color(0xFFF6F5F1),
    onBackground = Color(0xFF1C1D21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1D21),
    surfaceVariant = Color(0xFFF6F5F1),
    onSurfaceVariant = Color(0xFF676A73),
    outline = Color(0xFFE3E1DA),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF00FF87), // FPL green
    onPrimary = Color(0xFF121216),
    background = Color(0xFF121216),
    onBackground = Color(0xFFECECEF),
    surface = Color(0xFF1C1C22),
    onSurface = Color(0xFFECECEF),
    surfaceVariant = Color(0xFF121216),
    onSurfaceVariant = Color(0xFF9A9CA5),
    outline = Color(0xFF2D2D35),
)

@Composable
fun BreakTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
