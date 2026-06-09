package dev.reasonix.mobile.ui

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.WindowManager
import android.graphics.Color as AndroidColor
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.zIndex
import org.json.JSONObject
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ReasonixThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class ReasonixThemeStyle {
    CLASSIC,
    GLASS
}

enum class ReasonixBackgroundMode {
    GRADIENT,
    SOLID,
    WALLPAPER,
    CUSTOM_IMAGE
}

private const val ENABLE_REASONIX_BACK_DEBUG_REPORTS = false

data class ReasonixAccentPreset(
    val label: String,
    val color: Color
)

private const val UI_PREFS_NAME = "reasonix_ui"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_THEME_STYLE = "theme_style"
private const val KEY_THEME_COLOR_HEX = "theme_color_hex"
private const val KEY_BACKGROUND_MODE = "background_mode"
private const val KEY_BACKGROUND_COLOR_HEX = "background_color_hex"
private const val KEY_SURFACE_COLOR_HEX = "surface_color_hex"
private const val KEY_CHROME_COLOR_HEX = "chrome_color_hex"
private const val KEY_MUTED_TEXT_COLOR_HEX = "muted_text_color_hex"
private const val KEY_CUSTOM_BACKGROUND_URI = "custom_background_uri"
private const val KEY_BACKGROUND_BLUR_RADIUS = "background_blur_radius"
private const val KEY_FONT_SCALE = "font_scale"
private const val KEY_UI_SCALE = "ui_scale"

private val AccentPresets = listOf(
    ReasonixAccentPreset("樱粉", Color(0xFFF663A6)),
    ReasonixAccentPreset("晴蓝", Color(0xFF5B8CFF)),
    ReasonixAccentPreset("薄荷", Color(0xFF34C8A3)),
    ReasonixAccentPreset("琥珀", Color(0xFFFFB347)),
    ReasonixAccentPreset("紫雾", Color(0xFF9B7BFF))
)

val ReasonixGlassBottomBarScrollPadding = 112.dp
val ReasonixClassicBottomBarScrollPadding = 156.dp

@Stable
class ReasonixUiController(private val context: Context) {
    private val prefs = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(
        runCatching {
            ReasonixThemeMode.valueOf(
                prefs.getString(KEY_THEME_MODE, ReasonixThemeMode.SYSTEM.name)
                    ?: ReasonixThemeMode.SYSTEM.name
            )
        }.getOrDefault(ReasonixThemeMode.SYSTEM)
    )
        private set

    var themeStyle by mutableStateOf(
        runCatching {
            ReasonixThemeStyle.valueOf(
                prefs.getString(KEY_THEME_STYLE, ReasonixThemeStyle.GLASS.name)
                    ?: ReasonixThemeStyle.GLASS.name
            )
        }.getOrDefault(ReasonixThemeStyle.GLASS)
    )
        private set

    var themeColorHex by mutableStateOf(
        normalizeReasonixColorHex(
            prefs.getString(KEY_THEME_COLOR_HEX, null),
            AccentPresets.first().color
        )
    )
        private set

    var backgroundMode by mutableStateOf(
        runCatching {
            ReasonixBackgroundMode.valueOf(
                prefs.getString(KEY_BACKGROUND_MODE, ReasonixBackgroundMode.GRADIENT.name)
                    ?: ReasonixBackgroundMode.GRADIENT.name
            )
        }.getOrDefault(ReasonixBackgroundMode.GRADIENT)
    )
        private set

    var backgroundColorHex by mutableStateOf(
        normalizeReasonixColorHex(
            prefs.getString(KEY_BACKGROUND_COLOR_HEX, null),
            Color(0xFFF5F7FD)
        )
    )
        private set

    var surfaceColorHex by mutableStateOf(
        normalizeReasonixColorHex(
            prefs.getString(KEY_SURFACE_COLOR_HEX, null),
            Color.White
        )
    )
        private set

    var chromeColorHex by mutableStateOf(
        normalizeReasonixColorHex(
            prefs.getString(KEY_CHROME_COLOR_HEX, null),
            Color.White
        )
    )
        private set

    var mutedTextColorHex by mutableStateOf(
        normalizeReasonixColorHex(
            prefs.getString(KEY_MUTED_TEXT_COLOR_HEX, null),
            Color(0xFF6A738A)
        )
    )
        private set

    var customBackgroundUri by mutableStateOf(
        prefs.getString(KEY_CUSTOM_BACKGROUND_URI, "").orEmpty()
    )
        private set

    var backgroundBlurRadius by mutableIntStateOf(
        prefs.getInt(KEY_BACKGROUND_BLUR_RADIUS, 18).coerceIn(0, 60)
    )
        private set

    var fontScale by mutableStateOf(
        prefs.getFloat(KEY_FONT_SCALE, 1.0f).coerceIn(0.85f, 1.30f)
    )
        private set

    var uiScale by mutableStateOf(
        prefs.getFloat(KEY_UI_SCALE, 1.0f).coerceIn(0.85f, 1.20f)
    )
        private set

    val accentIndex: Int
        get() {
            val currentColor = accentColor.toArgb()
            return AccentPresets.indexOfFirst { it.color.toArgb() == currentColor }
        }

    val accentPreset: ReasonixAccentPreset
        get() = AccentPresets.getOrNull(accentIndex)
            ?: ReasonixAccentPreset("自定义", accentColor)

    val accentColor: Color
        get() = parseReasonixColor(themeColorHex, AccentPresets.first().color)

    val backgroundColor: Color
        get() = parseReasonixColor(
            backgroundColorHex,
            if (themeMode == ReasonixThemeMode.DARK) Color(0xFF090B12) else Color(0xFFF5F7FD)
        )

    fun updateThemeMode(value: ReasonixThemeMode) {
        themeMode = value
        prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
    }

    fun updateThemeStyle(value: ReasonixThemeStyle) {
        themeStyle = value
        prefs.edit().putString(KEY_THEME_STYLE, value.name).apply()
    }

    fun updateAccentIndex(value: Int) {
        val safeValue = value.coerceIn(0, AccentPresets.lastIndex)
        updateThemeColorHex(AccentPresets[safeValue].color.toReasonixHex())
    }

    fun updateThemeColorHex(value: String) {
        themeColorHex = normalizeReasonixColorHex(value, accentColor)
        prefs.edit().putString(KEY_THEME_COLOR_HEX, themeColorHex).apply()
    }

    fun updateBackgroundMode(value: ReasonixBackgroundMode) {
        backgroundMode = value
        prefs.edit().putString(KEY_BACKGROUND_MODE, value.name).apply()
    }

    fun updateBackgroundColorHex(value: String) {
        backgroundColorHex = normalizeReasonixColorHex(value, backgroundColor)
        prefs.edit().putString(KEY_BACKGROUND_COLOR_HEX, backgroundColorHex).apply()
    }

    fun updateCustomBackgroundUri(value: String) {
        customBackgroundUri = value.trim()
        prefs.edit().putString(KEY_CUSTOM_BACKGROUND_URI, customBackgroundUri).apply()
    }

    fun updateSurfaceColorHex(value: String) {
        surfaceColorHex = normalizeReasonixColorHex(value, Color.White)
        prefs.edit().putString(KEY_SURFACE_COLOR_HEX, surfaceColorHex).apply()
    }

    fun updateChromeColorHex(value: String) {
        chromeColorHex = normalizeReasonixColorHex(value, Color.White)
        prefs.edit().putString(KEY_CHROME_COLOR_HEX, chromeColorHex).apply()
    }

    fun updateMutedTextColorHex(value: String) {
        mutedTextColorHex = normalizeReasonixColorHex(value, Color(0xFF6A738A))
        prefs.edit().putString(KEY_MUTED_TEXT_COLOR_HEX, mutedTextColorHex).apply()
    }

    fun clearCustomBackgroundUri() {
        customBackgroundUri = ""
        prefs.edit().remove(KEY_CUSTOM_BACKGROUND_URI).apply()
        if (backgroundMode == ReasonixBackgroundMode.CUSTOM_IMAGE) {
            updateBackgroundMode(ReasonixBackgroundMode.GRADIENT)
        }
    }

    fun updateBackgroundBlurRadius(value: Int) {
        backgroundBlurRadius = value.coerceIn(0, 60)
        prefs.edit().putInt(KEY_BACKGROUND_BLUR_RADIUS, backgroundBlurRadius).apply()
    }

    fun updateFontScale(value: Float) {
        fontScale = value.coerceIn(0.85f, 1.30f)
        prefs.edit().putFloat(KEY_FONT_SCALE, fontScale).apply()
    }

    fun updateUiScale(value: Float) {
        uiScale = value.coerceIn(0.85f, 1.20f)
        prefs.edit().putFloat(KEY_UI_SCALE, uiScale).apply()
    }

    fun accentPresets(): List<ReasonixAccentPreset> = AccentPresets
}

internal fun defaultReasonixBackgroundColor(darkMode: Boolean): Color {
    return if (darkMode) Color(0xFF090B12) else Color(0xFFF5F7FD)
}

internal fun defaultReasonixSurfaceColor(darkMode: Boolean): Color {
    return if (darkMode) Color(0xFF151A24) else Color.White
}

internal fun defaultReasonixChromeColor(darkMode: Boolean): Color {
    return if (darkMode) Color(0xFF121924) else Color.White
}

internal fun defaultReasonixMutedTextColor(darkMode: Boolean): Color {
    return if (darkMode) Color(0xFF9EA8C4) else Color(0xFF6A738A)
}

internal fun normalizeReasonixColorHex(raw: String?, fallback: Color): String {
    val value = raw?.trim().orEmpty().ifBlank { fallback.toReasonixHex() }
    val normalized = if (value.startsWith("#")) value else "#$value"
    return when (normalized.length) {
        7, 9 -> normalized.uppercase()
        else -> fallback.toReasonixHex()
    }
}

internal fun parseReasonixColor(hex: String, fallback: Color): Color {
    return runCatching { Color(AndroidColor.parseColor(hex)) }.getOrDefault(fallback)
}

internal fun Color.toReasonixHex(): String {
    return String.format("#%08X", toArgb())
}

internal fun reasonixIsDarkColor(color: Color): Boolean {
    val luminance = (0.299f * color.red) + (0.587f * color.green) + (0.114f * color.blue)
    return luminance < 0.5f
}

private fun Modifier.reasonixBackgroundBlur(radiusDp: Int): Modifier {
    if (radiusDp <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return this
    }
    val radiusPx = radiusDp * Resources.getSystem().displayMetrics.density
    return graphicsLayer {
        renderEffect = AndroidRenderEffect
            .createBlurEffect(radiusPx, radiusPx, Shader.TileMode.DECAL)
            .asComposeRenderEffect()
    }
}

val LocalReasonixUiController = compositionLocalOf<ReasonixUiController> {
    error("ReasonixUiController not provided")
}

val LocalReasonixHazeState = compositionLocalOf<HazeState?> { null }

@Stable
data class ReasonixSurfaceTokens(
    val accent: Color,
    val cardGlassColor: Color,
    val cardContainerColor: Color,
    val cardBlurRadius: Int,
    val bottomBarGlassColor: Color,
    val bottomBarContainerColor: Color,
    val bottomBarBlurRadius: Int,
    val popupGlassColor: Color,
    val popupContainerColor: Color,
    val popupBlurRadius: Int,
    val secondaryPageGlassColor: Color,
    val secondaryPageContainerColor: Color,
    val secondaryPageBlurRadius: Int
)

@Composable
fun rememberReasonixUiController(): ReasonixUiController {
    val context = LocalContext.current.applicationContext
    return remember(context) { ReasonixUiController(context) }
}

@Composable
@ReadOnlyComposable
fun rememberReasonixAccentColor(): Color {
    return LocalReasonixUiController.current.accentColor
}

@Composable
@ReadOnlyComposable
fun rememberReasonixBottomBarScrollPadding(): Dp {
    val ui = LocalReasonixUiController.current
    return if (ui.themeStyle == ReasonixThemeStyle.GLASS) {
        ReasonixGlassBottomBarScrollPadding
    } else {
        ReasonixClassicBottomBarScrollPadding
    }
}

@Composable
@ReadOnlyComposable
fun rememberReasonixSurfaceColor(): Color {
    val ui = LocalReasonixUiController.current
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    return parseReasonixColor(ui.surfaceColorHex, defaultReasonixSurfaceColor(darkMode))
}

@Composable
@ReadOnlyComposable
fun rememberReasonixChromeColor(): Color {
    val ui = LocalReasonixUiController.current
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    return parseReasonixColor(ui.chromeColorHex, defaultReasonixChromeColor(darkMode))
}

@Composable
@ReadOnlyComposable
fun rememberReasonixMutedTextColor(): Color {
    val ui = LocalReasonixUiController.current
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    return parseReasonixColor(ui.mutedTextColorHex, defaultReasonixMutedTextColor(darkMode))
}

@Composable
fun rememberReasonixSurfaceTokens(): ReasonixSurfaceTokens {
    val ui = LocalReasonixUiController.current
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    val accent = rememberReasonixAccentColor()
    val surfaceSeed = rememberReasonixSurfaceColor()
    val chromeSeed = rememberReasonixChromeColor()
    val imageBackgroundMode = ui.backgroundMode == ReasonixBackgroundMode.WALLPAPER ||
        ui.backgroundMode == ReasonixBackgroundMode.CUSTOM_IMAGE
    val sharedBlurRadius = if (imageBackgroundMode) {
        ui.backgroundBlurRadius.coerceIn(0, 42)
    } else {
        0
    }
    val popupBlurRadius = sharedBlurRadius
    val secondaryPageBlurRadius = 0
    val cardGlassColor = rememberReasonixGlassColor(
        baseColor = surfaceSeed,
        blurRadius = sharedBlurRadius,
        darkMode = darkMode
    )
    val bottomBarBase = when (ui.backgroundMode) {
        ReasonixBackgroundMode.SOLID -> Color(
            ColorUtils.blendARGB(
                ui.backgroundColor.toArgb(),
                accent.toArgb(),
                if (darkMode) 0.14f else 0.18f
            )
        )
        else -> surfaceSeed
    }
    val bottomBarGlassColor = rememberReasonixGlassColor(
        baseColor = bottomBarBase,
        blurRadius = sharedBlurRadius,
        darkMode = darkMode
    )
    val popupGlassColor = rememberReasonixGlassColor(
        baseColor = surfaceSeed,
        blurRadius = popupBlurRadius,
        darkMode = darkMode
    )
    val secondaryPageGlassColor = rememberReasonixGlassColor(
        baseColor = surfaceSeed,
        blurRadius = secondaryPageBlurRadius,
        darkMode = darkMode
    )
    val secondaryPageBase = when (ui.backgroundMode) {
        ReasonixBackgroundMode.WALLPAPER,
        ReasonixBackgroundMode.CUSTOM_IMAGE -> surfaceSeed
        ReasonixBackgroundMode.SOLID -> ui.backgroundColor
        ReasonixBackgroundMode.GRADIENT -> chromeSeed
    }

    return ReasonixSurfaceTokens(
        accent = accent,
        cardGlassColor = cardGlassColor,
        cardContainerColor = reasonixGlassContainerColor(
            glassColor = cardGlassColor,
            blurRadius = sharedBlurRadius,
            darkMode = darkMode
        ),
        cardBlurRadius = sharedBlurRadius,
        bottomBarGlassColor = bottomBarGlassColor,
        bottomBarContainerColor = reasonixGlassContainerColor(
            glassColor = bottomBarGlassColor,
            blurRadius = sharedBlurRadius,
            darkMode = darkMode
        ),
        bottomBarBlurRadius = sharedBlurRadius,
        popupGlassColor = popupGlassColor,
        popupContainerColor = reasonixGlassContainerColor(
            glassColor = popupGlassColor,
            blurRadius = popupBlurRadius,
            darkMode = darkMode
        ),
        popupBlurRadius = popupBlurRadius,
        secondaryPageGlassColor = secondaryPageGlassColor,
        secondaryPageContainerColor = if (imageBackgroundMode) {
            if (secondaryPageBlurRadius > 0) {
                reasonixGlassContainerColor(
                    glassColor = secondaryPageGlassColor,
                    blurRadius = secondaryPageBlurRadius,
                    darkMode = darkMode
                )
            } else {
                Color.Transparent
            }
        } else {
            if (ui.themeStyle == ReasonixThemeStyle.GLASS) {
                Color.Transparent
            } else {
                secondaryPageBase.copy(alpha = 1f)
            }
        },
        secondaryPageBlurRadius = secondaryPageBlurRadius
    )
}

@Composable
fun rememberOpaqueReasonixSecondaryPageColor(): Color {
    val ui = LocalReasonixUiController.current
    val chromeSeed = rememberReasonixChromeColor()
    val surfaceSeed = rememberReasonixSurfaceColor()
    return when (ui.backgroundMode) {
        ReasonixBackgroundMode.WALLPAPER,
        ReasonixBackgroundMode.CUSTOM_IMAGE -> surfaceSeed.copy(alpha = 1f)
        ReasonixBackgroundMode.SOLID -> ui.backgroundColor.copy(alpha = 1f)
        ReasonixBackgroundMode.GRADIENT -> chromeSeed.copy(alpha = 1f)
    }
}

@Composable
fun ReasonixBackgroundLayer(
    modifier: Modifier = Modifier,
    darkMode: Boolean
) {
    val context = LocalContext.current
    val ui = LocalReasonixUiController.current
    val hazeState = LocalReasonixHazeState.current
    val bitmapState = produceState<Bitmap?>(initialValue = null, ui.backgroundMode, ui.customBackgroundUri) {
        value = withContext(Dispatchers.IO) {
            when (ui.backgroundMode) {
                ReasonixBackgroundMode.WALLPAPER -> loadWallpaperBitmap(context)
                ReasonixBackgroundMode.CUSTOM_IMAGE -> loadBitmapFromUri(context, ui.customBackgroundUri)
                else -> null
            }
        }
    }
    val backgroundModifier = modifier
        .fillMaxSize()
        .reasonixGlassSource(hazeState)
        .then(
            if (ui.backgroundBlurRadius > 0 &&
                ui.backgroundMode in setOf(
                    ReasonixBackgroundMode.WALLPAPER,
                    ReasonixBackgroundMode.CUSTOM_IMAGE
                )
            ) {
                Modifier.reasonixBackgroundBlur(ui.backgroundBlurRadius)
            } else {
                Modifier
            }
        )

    when (ui.backgroundMode) {
        ReasonixBackgroundMode.WALLPAPER,
        ReasonixBackgroundMode.CUSTOM_IMAGE -> {
            val bitmap = bitmapState.value
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = backgroundModifier,
                    contentScale = ContentScale.Crop
                )
            } else {
                ReasonixGradientBackground(
                    modifier = modifier.fillMaxSize().reasonixGlassSource(hazeState),
                    darkMode = darkMode,
                    baseColor = ui.backgroundColor,
                    accent = ui.accentColor
                )
            }
        }

        ReasonixBackgroundMode.SOLID -> {
            Box(modifier = backgroundModifier.background(ui.backgroundColor))
        }

        ReasonixBackgroundMode.GRADIENT -> {
            ReasonixGradientBackground(
                modifier = modifier.fillMaxSize().reasonixGlassSource(hazeState),
                darkMode = darkMode,
                baseColor = ui.backgroundColor,
                accent = ui.accentColor
            )
        }
    }
}

@Composable
fun ReasonixGradientBackground(
    modifier: Modifier = Modifier,
    darkMode: Boolean,
    baseColor: Color = if (darkMode) Color(0xFF090B12) else Color(0xFFF5F7FD),
    accent: Color = rememberReasonixAccentColor()
) {
    val lightAccent = accent.copy(alpha = if (darkMode) 0.15f else 0.25f)
    val secondaryLight = if (darkMode) Color(0xFF3B2D4A) else Color(0xFFE8E0F8)
    Box(
        modifier = modifier
            .background(baseColor)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        lightAccent,
                        baseColor.copy(alpha = 0.8f),
                        secondaryLight.copy(alpha = if (darkMode) 0.2f else 0.4f),
                        Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = 2000f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        lightAccent.copy(alpha = if (darkMode) 0.15f else 0.2f),
                        baseColor.copy(alpha = 0.5f),
                        Color.Transparent
                    ),
                    center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    radius = 1800f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        secondaryLight.copy(alpha = if (darkMode) 0.15f else 0.3f),
                        Color.Transparent
                    ),
                    center = Offset(Float.POSITIVE_INFINITY, 0f),
                    radius = 1500f
                )
            )
    )
}

private fun loadWallpaperBitmap(context: Context): Bitmap? {
    return runCatching {
        WallpaperManager.getInstance(context).drawable?.toBitmap()
    }.getOrNull()
}

private fun loadBitmapFromUri(context: Context, uriValue: String): Bitmap? {
    if (uriValue.isBlank()) return null
    return runCatching {
        context.contentResolver.openInputStream(android.net.Uri.parse(uriValue))?.use(BitmapFactory::decodeStream)
    }.getOrNull()
}

fun Modifier.reasonixGlassSource(hazeState: HazeState?): Modifier {
    return if (hazeState == null) this else hazeSource(state = hazeState)
}

fun Modifier.reasonixBackdropGlass(
    surfaceColor: Color,
    enabled: Boolean,
    hazeState: HazeState? = null,
    blurRadius: Int = 24,
    minTintAlpha: Float = 0.12f,
    maxTintAlpha: Float = 0.26f
): Modifier {
    if (!enabled || hazeState == null) return this
    val blurRadiusDp = with(Resources.getSystem().displayMetrics) {
        val px = 12f + ((blurRadius.coerceIn(0, 60) / 60f) * 8f)
        (px / density).dp
    }
    return hazeEffect(
        state = hazeState,
        style = HazeStyle(
            blurRadius = blurRadiusDp,
            backgroundColor = Color.Transparent,
            tint = HazeTint(surfaceColor.copy(alpha = surfaceColor.alpha.coerceIn(minTintAlpha, maxTintAlpha)))
        )
    )
}

private fun reasonixOpaqueGlassColor(
    seed: Color,
    accent: Color,
    darkMode: Boolean,
    accentBlendRatio: Float,
    alpha: Float
): Color {
    val blendedSeed = Color(
        ColorUtils.blendARGB(
            seed.toArgb(),
            accent.toArgb(),
            accentBlendRatio
        )
    )
    val baseBlendTarget = if (darkMode) {
        Color(0xFF0C1018)
    } else {
        Color(0xFFFDFEFF)
    }
    return Color(
        ColorUtils.blendARGB(
            blendedSeed.toArgb(),
            baseBlendTarget.toArgb(),
            if (darkMode) 0.20f else 0.26f
        )
    ).copy(alpha = alpha)
}

@Composable
@ReadOnlyComposable
private fun rememberReasonixGlassColor(
    baseColor: Color,
    blurRadius: Int,
    darkMode: Boolean = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
): Color {
    val isGlassStyle = LocalReasonixUiController.current.themeStyle == ReasonixThemeStyle.GLASS
    val effectiveBlurRadius = if (isGlassStyle && blurRadius <= 0) 24 else blurRadius
    if (effectiveBlurRadius <= 0) {
        return baseColor
    }

    val baseArgb = baseColor.toArgb()
    val baseOpaqueArgb = when {
        AndroidColor.alpha(baseArgb) == 0 &&
            AndroidColor.red(baseArgb) == 0 &&
            AndroidColor.green(baseArgb) == 0 &&
            AndroidColor.blue(baseArgb) == 0 -> {
            if (darkMode) {
                AndroidColor.rgb(28, 28, 32)
            } else {
                AndroidColor.rgb(255, 255, 255)
            }
        }

        else -> ColorUtils.setAlphaComponent(baseArgb, 255)
    }

    val requestedAlpha = baseColor.alpha
    val fallbackAlpha = if (isGlassStyle) {
        if (darkMode) 0.45f else 0.55f
    } else {
        if (darkMode) 0.72f else 0.66f
    }
    val resolvedAlpha = when {
        requestedAlpha <= 0f -> fallbackAlpha
        isGlassStyle -> requestedAlpha.coerceIn(0.25f, 0.70f)
        else -> requestedAlpha.coerceIn(0.60f, 0.90f)
    }

    return Color(baseOpaqueArgb).copy(alpha = resolvedAlpha)
}

private fun reasonixGlassTone(
    seed: Color,
    accent: Color,
    darkMode: Boolean,
    accentBlendRatio: Float,
    alpha: Float
): Color {
    return reasonixOpaqueGlassColor(
        seed = seed,
        accent = accent,
        darkMode = darkMode,
        accentBlendRatio = accentBlendRatio,
        alpha = alpha
    )
}

private fun reasonixGlassContainerColor(
    glassColor: Color,
    blurRadius: Int,
    darkMode: Boolean
): Color {
    return if (blurRadius > 0) {
        glassColor
    } else {
        glassColor.copy(alpha = if (darkMode) 0.94f else 0.98f)
    }
}

@Composable
fun ReasonixGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    surfaceColorOverride: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = rememberReasonixSurfaceTokens()
    val ui = LocalReasonixUiController.current
    val hazeState = LocalReasonixHazeState.current
    val isGlassStyle = ui.themeStyle == ReasonixThemeStyle.GLASS
    val effectiveBlurRadius = if (isGlassStyle && tokens.cardBlurRadius <= 0) {
        24
    } else {
        tokens.cardBlurRadius
    }
    val surfaceColor = surfaceColorOverride ?: tokens.cardContainerColor
    val borderColor = tokens.accent.copy(alpha = 0.18f)
    Surface(
        modifier = modifier
            .clip(shape)
            .reasonixBackdropGlass(
                surfaceColor = tokens.cardGlassColor,
                enabled = effectiveBlurRadius > 0,
                hazeState = hazeState,
                blurRadius = effectiveBlurRadius
            )
            .clip(shape),
        shape = shape,
        color = surfaceColor,
        border = if (effectiveBlurRadius > 0) null else BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun ReasonixInfoCard(
    title: String,
    modifier: Modifier = Modifier,
    titleVisible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val accent = rememberReasonixAccentColor()
    val ui = LocalReasonixUiController.current
    val isGlassStyle = ui.themeStyle == ReasonixThemeStyle.GLASS
    ReasonixGlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(if (isGlassStyle) 24.dp else 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            if (isGlassStyle) 20.dp else 16.dp
        )
    ) {
        if (titleVisible) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        content()
    }
}

@Composable
fun ReasonixSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val ui = LocalReasonixUiController.current
    val isGlassStyle = ui.themeStyle == ReasonixThemeStyle.GLASS
    ReasonixGlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(if (isGlassStyle) 24.dp else 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            if (isGlassStyle) 18.dp else 14.dp
        )
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

@Composable
fun ReasonixOutlinedActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val ui = LocalReasonixUiController.current
    val accent = rememberReasonixAccentColor()
    if (ui.themeStyle == ReasonixThemeStyle.GLASS) {
        val containerColor = if (enabled) {
            accent.copy(alpha = 0.15f)
        } else {
            accent.copy(alpha = 0.06f)
        }
        val borderColor = if (enabled) {
            accent.copy(alpha = 0.36f)
        } else {
            accent.copy(alpha = 0.15f)
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = RoundedCornerShape(18.dp),
            color = containerColor,
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = if (enabled) accent else accent.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, if (enabled) accent else accent.copy(alpha = 0.36f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = accent,
                disabledContentColor = accent.copy(alpha = 0.45f),
                containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            )
        ) {
            Text(text = text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ReasonixTagButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = rememberReasonixAccentColor()
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.36f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = accent,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ReasonixAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            ReasonixPopupSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                shape = RoundedCornerShape(30.dp),
                forceOpaque = true
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    title?.invoke()
                    text?.invoke()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dismissButton?.let {
                            Box(modifier = Modifier.padding(end = 8.dp)) {
                                it()
                            }
                        }
                        confirmButton()
                    }
                }
            }
        }
    }
}

@Composable
fun ReasonixDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        ReasonixDisableDialogDimEffect()
        content()
    }
}

@Composable
private fun ReasonixDisableDialogDimEffect() {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val originalDimAmount = window?.attributes?.dimAmount ?: 0f
        val hadDimBehind = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0
        window?.setDimAmount(0f)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        onDispose {
            if (hadDimBehind) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window?.setDimAmount(originalDimAmount)
            } else {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
    }
}

@Composable
fun ReasonixLargeDialogScaffold(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    topPadding: androidx.compose.ui.unit.Dp = 20.dp,
    horizontalPadding: androidx.compose.ui.unit.Dp = 8.dp,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    ReasonixDialog(onDismissRequest = onDismissRequest) {
        ReasonixSecondaryPageSurface(
            modifier = modifier
                .fillMaxSize()
                .padding(top = topPadding, start = horizontalPadding, end = horizontalPadding),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            forceOpaque = true,
            contentPadding = contentPadding,
            content = content
        )
    }
}

data class ReasonixBottomBarItem(
    val label: String,
    val icon: ImageVector
)

@Composable
fun ReasonixSecondaryPageSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(34.dp),
    forceOpaque: Boolean = false,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = rememberReasonixSurfaceTokens()
    val opaqueColor = rememberOpaqueReasonixSecondaryPageColor()
    val hazeState = LocalReasonixHazeState.current
    val surfaceModifier = if (tokens.secondaryPageBlurRadius > 0) {
        modifier
            .clip(shape)
            .reasonixBackdropGlass(
                surfaceColor = tokens.secondaryPageGlassColor,
                enabled = true,
                hazeState = hazeState,
                blurRadius = tokens.secondaryPageBlurRadius,
                minTintAlpha = 0.006f,
                maxTintAlpha = 0.024f
            )
            .clip(shape)
    } else {
        modifier.clip(shape)
    }
    Surface(
        modifier = surfaceModifier,
        shape = shape,
        color = if (forceOpaque) opaqueColor else tokens.secondaryPageContainerColor,
        border = null,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun ReasonixPrimaryPageSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(34.dp),
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = rememberReasonixSurfaceTokens()
    val ui = LocalReasonixUiController.current
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    val containerColor = if (ui.themeStyle == ReasonixThemeStyle.GLASS) {
        Color.Transparent
    } else {
        tokens.secondaryPageContainerColor.copy(alpha = if (darkMode) 0.98f else 0.94f)
    }
    Surface(
        modifier = modifier.clip(shape),
        shape = shape,
        color = containerColor,
        border = null,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            content = content
        )
    }
}

@Composable
private fun ReasonixBottomBarSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(40.dp),
    glassTintColorOverride: Color? = null,
    glassMinTintAlpha: Float? = null,
    glassMaxTintAlpha: Float? = null,
    glassShadowElevation: Dp? = null,
    content: @Composable () -> Unit
) {
    val tokens = rememberReasonixSurfaceTokens()
    val ui = LocalReasonixUiController.current
    val hazeState = LocalReasonixHazeState.current
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    val isGlassStyle = ui.themeStyle == ReasonixThemeStyle.GLASS
    val effectiveBlurRadius = when {
        isGlassStyle && tokens.bottomBarBlurRadius <= 0 -> 34
        isGlassStyle -> tokens.bottomBarBlurRadius.coerceIn(28, 38)
        else -> tokens.bottomBarBlurRadius
    }
    val glassTintColor = glassTintColorOverride ?: if (isGlassStyle) {
        tokens.accent.copy(alpha = if (darkMode) 0.020f else 0.012f)
    } else {
        tokens.bottomBarGlassColor
    }
    val resolvedMinTintAlpha = glassMinTintAlpha ?: if (isGlassStyle) 0.008f else 0.18f
    val resolvedMaxTintAlpha = glassMaxTintAlpha ?: if (isGlassStyle) 0.045f else 0.42f
    val resolvedShadowElevation = glassShadowElevation ?: if (isGlassStyle) 2.dp else 0.dp
    val containerColor = if (isGlassStyle) Color.Transparent else tokens.bottomBarContainerColor
    val outlineColor = Color(
        ColorUtils.setAlphaComponent(
            tokens.accent.toArgb(),
            if (isGlassStyle) 56 else 46
        )
    )
    LaunchedEffect(
        ui.themeStyle,
        ui.backgroundMode,
        tokens.bottomBarBlurRadius,
        glassTintColor,
        containerColor
    ) {
        // #region debug-point E:bottom-bar-snapshot
        reportChatEntryBackAnimationChromeDebug(
            hypothesisId = "E",
            location = "ReasonixUiChrome.kt:bottomBarSurface",
            msg = "[DEBUG] bottom bar surface snapshot",
            data = JSONObject()
                .put("themeStyle", ui.themeStyle.name)
                .put("backgroundMode", ui.backgroundMode.name)
                .put("isGlassStyle", isGlassStyle)
                .put("bottomBarBlurRadius", tokens.bottomBarBlurRadius)
                .put("effectiveBlurRadius", effectiveBlurRadius)
                .put("glassTintArgb", glassTintColor.toArgb())
                .put("containerArgb", containerColor.toArgb())
                .put("bottomBarGlassArgb", tokens.bottomBarGlassColor.toArgb())
                .put("bottomBarContainerArgb", tokens.bottomBarContainerColor.toArgb())
        )
        // #endregion
    }
    Surface(
        modifier = modifier
            .clip(shape)
            .reasonixBackdropGlass(
                surfaceColor = glassTintColor,
                enabled = effectiveBlurRadius > 0,
                hazeState = hazeState,
                blurRadius = effectiveBlurRadius,
                minTintAlpha = resolvedMinTintAlpha,
                maxTintAlpha = resolvedMaxTintAlpha
            )
            .clip(shape),
        shape = shape,
        color = containerColor,
        border = if (!isGlassStyle && effectiveBlurRadius <= 0) {
            BorderStroke(1.dp, outlineColor)
        } else null,
        shadowElevation = resolvedShadowElevation
    ) {
        content()
    }
}

// #region debug-point E:chrome-debug-reporter
private fun reportChatEntryBackAnimationChromeDebug(
    hypothesisId: String,
    location: String,
    msg: String,
    data: JSONObject
) {
    if (!ENABLE_REASONIX_BACK_DEBUG_REPORTS) return
    Thread {
        runCatching {
            val connection = (URL("http://192.168.2.3:7777/event").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1200
                readTimeout = 1200
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject()
                .put("sessionId", "chat-entry-back-animation")
                .put("runId", "pre-fix")
                .put("hypothesisId", hypothesisId)
                .put("location", location)
                .put("msg", msg)
                .put("data", data)
                .put("ts", System.currentTimeMillis())
                .toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            runCatching { connection.inputStream.use { input -> while (input.read() != -1) {} } }
            connection.disconnect()
        }
    }.start()
}
// #endregion

@Composable
fun ReasonixPopupSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(30.dp),
    forceOpaque: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = rememberReasonixSurfaceTokens()
    val opaqueColor = rememberOpaqueReasonixSecondaryPageColor()
    val ui = LocalReasonixUiController.current
    val hazeState = LocalReasonixHazeState.current
    val isGlassStyle = ui.themeStyle == ReasonixThemeStyle.GLASS
    val effectiveBlurRadius = if (isGlassStyle && tokens.popupBlurRadius <= 0) {
        24
    } else {
        tokens.popupBlurRadius
    }
    Surface(
        modifier = modifier
            .clip(shape)
            .reasonixBackdropGlass(
                surfaceColor = tokens.popupGlassColor,
                enabled = effectiveBlurRadius > 0,
                hazeState = hazeState,
                blurRadius = effectiveBlurRadius,
                minTintAlpha = 0.012f,
                maxTintAlpha = 0.060f
            )
            .clip(shape),
        shape = shape,
        color = if (forceOpaque) opaqueColor else tokens.popupContainerColor,
        border = if (effectiveBlurRadius > 0) null else {
            BorderStroke(1.dp, tokens.accent.copy(alpha = 0.16f))
        },
        shadowElevation = 0.dp
    ) {
        Column(content = content)
    }
}

@Composable
fun ReasonixFloatingBottomBar(
    items: List<ReasonixBottomBarItem>,
    selectedIndex: Int,
    visualIndex: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = rememberReasonixAccentColor()
    val ui = LocalReasonixUiController.current
    val hazeState = LocalReasonixHazeState.current
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    val isGlassStyle = ui.themeStyle == ReasonixThemeStyle.GLASS
    var draggingVisualIndex by remember(items.size) { mutableStateOf<Float?>(null) }
    var bottomBarHeld by remember(items.size) { mutableStateOf(false) }
    var dragStartVisualIndex by remember(items.size) { mutableStateOf<Float?>(null) }
    var dragStartPositionX by remember(items.size) { mutableStateOf<Float?>(null) }
    val targetVisualIndex = (draggingVisualIndex ?: visualIndex)
        .coerceIn(0f, items.lastIndex.coerceAtLeast(0).toFloat())
    val heldProgress by animateFloatAsState(
        targetValue = if (bottomBarHeld) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (bottomBarHeld) 180 else 220,
            easing = FastOutSlowInEasing
        ),
        label = "reasonixBottomBarHoldProgress"
    )
    val bottomBarShellTintColor = if (isGlassStyle) {
        if (darkMode) {
            accent.copy(alpha = 0.12f)
        } else {
            Color(
                AndroidColor.argb(
                    150,
                    ((accent.red * 255f) * 0.16f + 255f * 0.84f).toInt().coerceIn(0, 255),
                    ((accent.green * 255f) * 0.16f + 255f * 0.84f).toInt().coerceIn(0, 255),
                    ((accent.blue * 255f) * 0.16f + 255f * 0.84f).toInt().coerceIn(0, 255)
                )
            )
        }
    } else {
        accent.copy(alpha = 0.08f)
    }
    ReasonixBottomBarSurface(
        modifier = modifier
            .navigationBarsPadding()
            .offset(y = (-16).dp)
            .padding(horizontal = 26.dp, vertical = 4.dp)
            .widthIn(max = 520.dp)
            .zIndex(5f),
        shape = RoundedCornerShape(40.dp),
        glassTintColorOverride = if (isGlassStyle) {
            bottomBarShellTintColor.copy(alpha = if (darkMode) 0.014f else 0.010f)
        } else {
            bottomBarShellTintColor.copy(alpha = if (darkMode) 0.10f else 0.16f)
        },
        glassMinTintAlpha = if (isGlassStyle) 0.003f else 0.10f,
        glassMaxTintAlpha = if (isGlassStyle) 0.014f else 0.16f,
        glassShadowElevation = if (isGlassStyle) 0.dp else 12.dp
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val slotWidth = maxWidth / items.size.coerceAtLeast(1)
            val localDensity = LocalDensity.current
            val transitionFraction = abs((targetVisualIndex % 1f + 1f) % 1f)
            val dropletStretch = (1f - abs(transitionFraction - 0.5f) / 0.5f)
                .coerceIn(0f, 1f)
            val indicatorWidthScale = 0.86f + (0.06f * dropletStretch) + (0.06f * heldProgress)
            val indicatorWidth = slotWidth * indicatorWidthScale
            val slotWidthPx = with(localDensity) { slotWidth.toPx() }
            val indicatorWidthPx = with(localDensity) { indicatorWidth.toPx() }
            val indicatorCenterAdjustmentPx = with(localDensity) { 2.dp.toPx() }
            val indicatorHeight = lerp(64.dp, 98.dp, heldProgress)
            val indicatorColor = if (isGlassStyle) {
                if (darkMode) {
                    accent.copy(alpha = 0.28f)
                } else {
                    Color(
                        AndroidColor.argb(
                            235,
                            ((accent.red * 255f) * 0.58f + 255f * 0.42f).toInt().coerceIn(0, 255),
                            ((accent.green * 255f) * 0.58f + 255f * 0.42f).toInt().coerceIn(0, 255),
                            ((accent.blue * 255f) * 0.58f + 255f * 0.42f).toInt().coerceIn(0, 255)
                        )
                    )
                }
            } else {
                Color(
                    AndroidColor.argb(
                        if (darkMode) 170 else 220,
                        ((accent.red * 255f) * 0.74f + 255f * 0.26f).toInt().coerceIn(0, 255),
                        ((accent.green * 255f) * 0.74f + 255f * 0.26f).toInt().coerceIn(0, 255),
                        ((accent.blue * 255f) * 0.74f + 255f * 0.26f).toInt().coerceIn(0, 255)
                    )
                )
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .height(68.dp)
                    .pointerInput(items.size, selectedIndex) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                bottomBarHeld = true
                                dragStartPositionX = offset.x
                                dragStartVisualIndex = visualIndex
                                draggingVisualIndex = visualIndex
                            },
                            onDragEnd = {
                                val settledIndex = draggingVisualIndex
                                    ?.roundToInt()
                                    ?.coerceIn(0, items.lastIndex)
                                draggingVisualIndex = null
                                dragStartVisualIndex = null
                                dragStartPositionX = null
                                bottomBarHeld = false
                                if (settledIndex != null && settledIndex != selectedIndex) {
                                    onSelect(settledIndex)
                                }
                            },
                            onDragCancel = {
                                draggingVisualIndex = null
                                dragStartVisualIndex = null
                                dragStartPositionX = null
                                bottomBarHeld = false
                            }
                        ) { change, _ ->
                            change.consume()
                            val slotWidthPx = size.width / items.size.coerceAtLeast(1).toFloat()
                            val startX = dragStartPositionX ?: change.position.x
                            val startIndex = dragStartVisualIndex ?: visualIndex
                            val deltaSlots = if (slotWidthPx > 0f) {
                                (change.position.x - startX) / slotWidthPx
                            } else {
                                0f
                            }
                            draggingVisualIndex = (startIndex + deltaSlots)
                                .coerceIn(0f, items.lastIndex.toFloat())
                        }
                    }
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            translationX =
                                (slotWidthPx * targetVisualIndex) +
                                    ((slotWidthPx - indicatorWidthPx) / 2f) -
                                    indicatorCenterAdjustmentPx
                        }
                        .width(indicatorWidth)
                        .height(indicatorHeight)
                        .clip(RoundedCornerShape(32.dp))
                        .reasonixBackdropGlass(
                            surfaceColor = indicatorColor.copy(alpha = if (darkMode) 0.26f else 0.22f),
                            enabled = isGlassStyle,
                            hazeState = hazeState,
                            blurRadius = if (isGlassStyle) 34 else 0,
                            minTintAlpha = if (isGlassStyle) {
                                if (darkMode) 0.12f else 0.10f
                            } else {
                                0.12f
                            },
                            maxTintAlpha = if (isGlassStyle) {
                                if (darkMode) 0.26f else 0.22f
                            } else {
                                if (darkMode) 0.28f else 0.24f
                            }
                        )
                        .clip(RoundedCornerShape(32.dp)),
                    shape = RoundedCornerShape(32.dp),
                    color = indicatorColor.copy(alpha = if (darkMode) 0.26f else 0.34f),
                    border = null,
                    shadowElevation = 0.dp
                ) {
                    Spacer(modifier = Modifier.fillMaxSize())
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { index, item ->
                        val selectionProgress by animateFloatAsState(
                            targetValue = (1f - abs(targetVisualIndex - index).coerceIn(0f, 1f)),
                            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                            label = "reasonixBottomBarItemProgress$index"
                        )
                        val interactionSource = remember { MutableInteractionSource() }
                        val glassContentColor = if (darkMode) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        val itemColor = glassContentColor.copy(alpha = 0.72f + (0.24f * selectionProgress))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(32.dp))
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null
                                ) { onSelect(index) }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Transparent
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    modifier = Modifier
                                        .padding(7.dp)
                                        .size(18.dp),
                                    tint = itemColor
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = itemColor.copy(alpha = 0.92f),
                                fontWeight = if (selectionProgress > 0.55f) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Medium
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReasonixSecondaryPageFrame(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val bottomBarScrollPadding = rememberReasonixBottomBarScrollPadding()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = bottomBarScrollPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ReasonixInfoCard(title = title, titleVisible = false) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        content()
    }
}
