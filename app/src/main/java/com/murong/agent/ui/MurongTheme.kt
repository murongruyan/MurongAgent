package com.murong.agent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollFactory
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
    val background = lerp(backgroundSeed, Color(0xFF0A111B), 0.58f)
    val surface = lerp(surfaceSeed, Color(0xFF141D2A), 0.44f)
    val surfaceVariant = lerp(surface, accent, 0.11f)
    val primary = lerp(accent, Color(0xFFE4ECFF), 0.12f)
    return darkColorScheme(
        primary = primary,
        onPrimary = Color(0xFF08111B),
        primaryContainer = lerp(accent, Color(0xFF09131F), 0.68f),
        onPrimaryContainer = Color(0xFFF2F6FF),
        secondary = lerp(accent, Color(0xFFB7C0D9), 0.70f),
        tertiary = Color(0xFF63D7A5),
        background = background,
        surface = surface.copy(alpha = 0.98f),
        surfaceVariant = surfaceVariant,
        onBackground = Color(0xFFF3F7FF),
        onSurface = Color(0xFFF1F5FF),
        onSurfaceVariant = mutedTextSeed,
        outline = lerp(accent, Color(0xFF46516A), 0.78f),
        error = Color(0xFFFF847A)
    )
}

private val MurongShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

@Composable
fun MurongTheme(
    content: @Composable () -> Unit
) {
    val uiController = rememberMurongUiController()
    val baseDensity = LocalDensity.current
    val isDark = when (uiController.themeMode) {
        MurongThemeMode.SYSTEM -> isSystemInDarkTheme()
        MurongThemeMode.LIGHT -> false
        MurongThemeMode.DARK -> true
    }
    val accent = uiController.accentColor
    val backgroundSeed = parseMurongColor(
        uiController.backgroundColorHex,
        defaultMurongBackgroundColor(isDark)
    )
    val surfaceSeed = parseMurongColor(
        uiController.surfaceColorHex,
        defaultMurongSurfaceColor(isDark)
    )
    val mutedTextSeed = parseMurongColor(
        uiController.mutedTextColorHex,
        defaultMurongMutedTextColor(isDark)
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
        LocalMurongUiController provides uiController,
        LocalMurongHazeState provides hazeState,
        LocalOverscrollFactory provides null
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            shapes = MurongShapes,
            content = content
        )
    }
}
