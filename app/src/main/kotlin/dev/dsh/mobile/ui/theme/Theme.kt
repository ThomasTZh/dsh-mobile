package dev.dsh.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brand = Color(0xFF4D6BFE)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E7FF),
    onPrimaryContainer = Color(0xFF0B1B4F),
    secondary = Color(0xFF5B6478),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF0F7),
    outline = Color(0xFFC8CEDC),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FA3FF),
    onPrimary = Color(0xFF0A1A4D),
    primaryContainer = Color(0xFF253773),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFB9C1D6),
    background = Color(0xFF0F1117),
    surface = Color(0xFF171A22),
    surfaceVariant = Color(0xFF222634),
    outline = Color(0xFF3A4050),
    error = Color(0xFFFFB4AB),
)

@Composable
fun DshMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
