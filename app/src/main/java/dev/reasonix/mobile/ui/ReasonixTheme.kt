package dev.reasonix.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

private fun buildLightColorScheme(accent: Color): ColorScheme {
    return lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = lerp(accent, Color.White, 0.82f),
        onPrimaryContainer = Color(0xFF1F2430),
        secondary = lerp(accent, Color(0xFF6B7AA5), 0.72f),
        tertiary = Color(0xFF4FB989),
        background = Color(0xFFF5F7FD),
        surface = Color.White.copy(alpha = 0.92f),
        surfaceVariant = lerp(accent, Color.White, 0.88f),
        onBackground = Color(0xFF151821),
        onSurface = Color(0xFF171B24),
        onSurfaceVariant = Color(0xFF6A738A),
        outline = lerp(accent, Color(0xFFD7DDEA), 0.78f),
        error = Color(0xFFD95955)
    )
}

private fun buildDarkColorScheme(accent: Color): ColorScheme {
    return darkColorScheme(
        primary = lerp(accent, Color.White, 0.08f),
        onPrimary = Color(0xFF11141B),
        primaryContainer = lerp(accent, Color.Black, 0.56f),
        onPrimaryContainer = Color(0xFFF5F7FF),
        secondary = lerp(accent, Color(0xFFAAB3CC), 0.62f),
        tertiary = Color(0xFF62D5A0),
        background = Color(0xFF090B12),
        surface = Color(0xFF151A24).copy(alpha = 0.96f),
        surfaceVariant = Color(0xFF1C2230),
        onBackground = Color(0xFFF3F6FF),
        onSurface = Color(0xFFF3F6FF),
        onSurfaceVariant = Color(0xFF9EA8C4),
        outline = lerp(accent, Color(0xFF394256), 0.76f),
        error = Color(0xFFFF7B74)
    )
}

private val ReasonixShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

@Composable
fun ReasonixTheme(
    content: @Composable () -> Unit
) {
    val uiController = rememberReasonixUiController()
    val isDark = when (uiController.themeMode) {
        ReasonixThemeMode.SYSTEM -> isSystemInDarkTheme()
        ReasonixThemeMode.LIGHT -> false
        ReasonixThemeMode.DARK -> true
    }
    val accent = uiController.accentPreset.color
    val hazeState = remember { HazeState() }
    val colorScheme = if (isDark) buildDarkColorScheme(accent) else buildLightColorScheme(accent)
    CompositionLocalProvider(
        LocalReasonixUiController provides uiController,
        LocalReasonixHazeState provides hazeState
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            shapes = ReasonixShapes,
            content = content
        )
    }
}
