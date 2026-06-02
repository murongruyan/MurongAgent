package dev.reasonix.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFF663A6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE4EF),
    onPrimaryContainer = Color(0xFF4D1633),
    secondary = Color(0xFF8C93B0),
    tertiary = Color(0xFF4CAF50),
    background = Color(0xFFF7F7F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFFFF0F6),
    onBackground = Color(0xFF121212),
    onSurface = Color(0xFF121212),
    onSurfaceVariant = Color(0xFF8C93B0),
    outline = Color(0xFFE8D6DF),
    error = Color(0xFFE94634)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFF663A6),
    onPrimary = Color(0xFF2B0D1B),
    primaryContainer = Color(0xFF5B1C3B),
    onPrimaryContainer = Color(0xFFFFD8E8),
    secondary = Color(0xFF9BA3C3),
    tertiary = Color(0xFF66BB6A),
    background = Color(0xFF000000),
    surface = Color(0xFF242424),
    surfaceVariant = Color(0xFF2E2E2E),
    onBackground = Color(0xFFE6FFFFFF),
    onSurface = Color(0xFFE6FFFFFF),
    onSurfaceVariant = Color(0xFF9197B0),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFFF6B5F)
)

private val ReasonixShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ReasonixTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = ReasonixShapes,
        content = content
    )
}
