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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

private fun buildLightColorScheme(
    accent: Color,
    backgroundSeed: Color,
    surfaceSeed: Color,
    mutedTextSeed: Color
): ColorScheme {
    val background = lerp(backgroundSeed, Color(0xFFF5F7FD), 0.35f)
    val surface = lerp(surfaceSeed, Color.White, 0.14f)
    return lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = lerp(accent, Color.White, 0.82f),
        onPrimaryContainer = Color(0xFF1F2430),
        secondary = lerp(accent, Color(0xFF6B7AA5), 0.72f),
        tertiary = Color(0xFF4FB989),
        background = background,
        surface = surface.copy(alpha = 0.92f),
        surfaceVariant = lerp(surface, accent, 0.08f),
        onBackground = Color(0xFF151821),
        onSurface = Color(0xFF171B24),
        onSurfaceVariant = mutedTextSeed,
        outline = lerp(accent, Color(0xFFD7DDEA), 0.78f),
        error = Color(0xFFD95955)
    )
}

private fun buildDarkColorScheme(
    accent: Color,
    backgroundSeed: Color,
    surfaceSeed: Color,
    mutedTextSeed: Color
): ColorScheme {
    val background = lerp(backgroundSeed, Color(0xFF090B12), 0.78f)
    val surface = lerp(surfaceSeed, Color(0xFF151A24), 0.32f)
    return darkColorScheme(
        primary = lerp(accent, Color.White, 0.08f),
        onPrimary = Color(0xFF11141B),
        primaryContainer = lerp(accent, Color.Black, 0.56f),
        onPrimaryContainer = Color(0xFFF5F7FF),
        secondary = lerp(accent, Color(0xFFAAB3CC), 0.62f),
        tertiary = Color(0xFF62D5A0),
        background = background,
        surface = surface.copy(alpha = 0.96f),
        surfaceVariant = lerp(surface, accent, 0.06f),
        onBackground = Color(0xFFF3F6FF),
        onSurface = Color(0xFFF3F6FF),
        onSurfaceVariant = mutedTextSeed,
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
    val baseDensity = LocalDensity.current
    val isDark = when (uiController.themeMode) {
        ReasonixThemeMode.SYSTEM -> isSystemInDarkTheme()
        ReasonixThemeMode.LIGHT -> false
        ReasonixThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val accent = uiController.accentColor
    val backgroundSeed = parseReasonixColor(
        uiController.backgroundColorHex,
        defaultReasonixBackgroundColor(isDark)
    )
    val surfaceSeed = parseReasonixColor(
        uiController.surfaceColorHex,
        defaultReasonixSurfaceColor(isDark)
    )
    val mutedTextSeed = parseReasonixColor(
        uiController.mutedTextColorHex,
        defaultReasonixMutedTextColor(isDark)
    )
    val scaledDensity = remember(baseDensity, uiController.uiScale, uiController.fontScale) {
        Density(
            density = baseDensity.density * uiController.uiScale,
            fontScale = baseDensity.fontScale * uiController.fontScale
        )
    }
    val hazeState = remember { HazeState() }
    val colorScheme = if (isDark) {
        buildDarkColorScheme(accent, backgroundSeed, surfaceSeed, mutedTextSeed)
    } else {
        buildLightColorScheme(accent, backgroundSeed, surfaceSeed, mutedTextSeed)
    }
    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
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
