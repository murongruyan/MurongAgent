package dev.reasonix.mobile.ui

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    val topLeft = if (darkMode) accent.copy(alpha = 0.22f) else accent.copy(alpha = 0.24f)
    val topRight = if (darkMode) Color(0xFF21325C).copy(alpha = 0.28f) else Color(0xFFDCE6FF)
    val bottom = if (darkMode) Color(0xFF1D1430).copy(alpha = 0.24f) else Color(0xFFFFE3F0)
    Box(
        modifier = modifier
            .background(baseColor)
            .background(
                Brush.radialGradient(
                    colors = listOf(topLeft, Color.Transparent),
                    center = Offset.Zero,
                    radius = 1800f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(topRight, Color.Transparent),
                    center = Offset(Float.POSITIVE_INFINITY, 0f),
                    radius = 1600f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(bottom, Color.Transparent),
                    center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    radius = 2000f
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
    minTintAlpha: Float = 0.12f,
    maxTintAlpha: Float = 0.26f
): Modifier {
    if (!enabled || hazeState == null) return this
    return hazeEffect(
        state = hazeState,
        style = HazeStyle(
            blurRadius = 36.dp,
            backgroundColor = Color.Transparent,
            tint = HazeTint(surfaceColor.copy(alpha = surfaceColor.alpha.coerceIn(minTintAlpha, maxTintAlpha)))
        )
    )
}

@Composable
fun ReasonixGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    surfaceColorOverride: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val ui = LocalReasonixUiController.current
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    val accent = rememberReasonixAccentColor()
    val surfaceSeed = rememberReasonixSurfaceColor()
    val hazeState = LocalReasonixHazeState.current
    val surfaceColor = surfaceColorOverride ?: if (ui.themeStyle == ReasonixThemeStyle.GLASS) {
        surfaceSeed.copy(alpha = if (darkMode) 0.52f else 0.58f)
    } else {
        surfaceSeed.copy(alpha = 0.96f)
    }
    val borderColor = if (ui.themeStyle == ReasonixThemeStyle.GLASS) {
        accent.copy(alpha = if (darkMode) 0.22f else 0.18f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    }
    Surface(
        modifier = modifier.reasonixBackdropGlass(
            surfaceColor = surfaceColor,
            enabled = ui.themeStyle == ReasonixThemeStyle.GLASS,
            hazeState = hazeState
        ),
        shape = shape,
        color = surfaceColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = if (ui.themeStyle == ReasonixThemeStyle.GLASS) 0.dp else 2.dp,
        shadowElevation = if (ui.themeStyle == ReasonixThemeStyle.GLASS) 0.dp else 1.dp
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
    ReasonixGlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)
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
    ReasonixGlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
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
    val containerColor = if (ui.themeStyle == ReasonixThemeStyle.GLASS) {
        accent.copy(alpha = if (enabled) 0.16f else 0.08f)
    } else {
        Color.Transparent
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.42f else 0.18f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = accent,
            disabledContentColor = accent.copy(alpha = 0.45f),
            containerColor = containerColor,
            disabledContainerColor = containerColor.copy(alpha = 0.7f)
        )
    ) {
        Text(text = text, fontWeight = FontWeight.SemiBold)
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
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.38f))
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
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = RoundedCornerShape(28.dp),
        containerColor = if (LocalReasonixUiController.current.themeStyle == ReasonixThemeStyle.GLASS) {
            if (reasonixIsDarkColor(MaterialTheme.colorScheme.background)) {
                Color(0xFF151A24).copy(alpha = 0.90f)
            } else {
                Color.White.copy(alpha = 0.94f)
            }
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = 0.dp
    )
}

data class ReasonixBottomBarItem(
    val label: String,
    val icon: ImageVector
)

@Composable
fun ReasonixSecondaryPageSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(34.dp),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val ui = LocalReasonixUiController.current
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    val accent = rememberReasonixAccentColor()
    val chromeSeed = rememberReasonixChromeColor()
    val hazeState = LocalReasonixHazeState.current
    val surfaceColor = if (ui.themeStyle == ReasonixThemeStyle.GLASS) {
        chromeSeed.copy(alpha = if (darkMode) 0.76f else 0.84f)
    } else {
        chromeSeed.copy(alpha = 0.98f)
    }
    Surface(
        modifier = modifier.reasonixBackdropGlass(
            surfaceColor = surfaceColor,
            enabled = ui.themeStyle == ReasonixThemeStyle.GLASS,
            hazeState = hazeState,
            minTintAlpha = 0.05f,
            maxTintAlpha = 0.16f
        ),
        shape = shape,
        color = surfaceColor,
        border = if (ui.themeStyle == ReasonixThemeStyle.GLASS) {
            null
        } else {
            BorderStroke(1.dp, accent.copy(alpha = if (darkMode) 0.14f else 0.10f))
        },
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
    content: @Composable () -> Unit
) {
    val ui = LocalReasonixUiController.current
    val accent = rememberReasonixAccentColor()
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    val chromeSeed = rememberReasonixChromeColor()
    val hazeState = LocalReasonixHazeState.current
    val glassTint = if (darkMode) {
        ColorUtils.blendARGB(chromeSeed.toArgb(), accent.toArgb(), 0.18f).let { Color(it) }.copy(alpha = 0.08f)
    } else {
        ColorUtils.blendARGB(chromeSeed.toArgb(), accent.toArgb(), 0.12f).let { Color(it) }.copy(alpha = 0.10f)
    }
    Surface(
        modifier = modifier
            .clip(shape)
            .reasonixBackdropGlass(
                surfaceColor = glassTint,
                enabled = ui.themeStyle == ReasonixThemeStyle.GLASS,
                hazeState = hazeState,
                minTintAlpha = 0.002f,
                maxTintAlpha = 0.010f
            )
            .clip(shape),
        shape = shape,
        color = if (ui.themeStyle == ReasonixThemeStyle.GLASS) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = if (ui.themeStyle == ReasonixThemeStyle.GLASS) {
            null
        } else {
            BorderStroke(
                1.dp,
                accent.copy(alpha = if (darkMode) 0.20f else 0.14f)
            )
        },
        shadowElevation = if (ui.themeStyle == ReasonixThemeStyle.GLASS) 0.dp else 8.dp
    ) {
        content()
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
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    var draggingVisualIndex by remember(items.size) { mutableStateOf<Float?>(null) }
    var bottomBarHeld by remember(items.size) { mutableStateOf(false) }
    val targetVisualIndex = (draggingVisualIndex ?: visualIndex)
        .coerceIn(0f, items.lastIndex.coerceAtLeast(0).toFloat())
    val heldProgress by animateFloatAsState(
        targetValue = if (bottomBarHeld) 1f else 0f,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "reasonixBottomBarHoldProgress"
    )
    ReasonixBottomBarSurface(
        modifier = modifier
            .navigationBarsPadding()
            .widthIn(max = 520.dp)
            .zIndex(5f),
        shape = RoundedCornerShape(40.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val slotWidth = maxWidth / items.size.coerceAtLeast(1)
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .height(62.dp)
                    .pointerInput(items.size, selectedIndex) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                bottomBarHeld = true
                                val slotWidthPx = size.width / items.size.coerceAtLeast(1).toFloat()
                                draggingVisualIndex = (offset.x / slotWidthPx)
                                    .coerceIn(0f, items.lastIndex.toFloat())
                            },
                            onDragEnd = {
                                val settledIndex = draggingVisualIndex
                                    ?.roundToInt()
                                    ?.coerceIn(0, items.lastIndex)
                                draggingVisualIndex = null
                                bottomBarHeld = false
                                if (settledIndex != null && settledIndex != selectedIndex) {
                                    onSelect(settledIndex)
                                }
                            },
                            onDragCancel = {
                                draggingVisualIndex = null
                                bottomBarHeld = false
                            }
                        ) { change, _ ->
                            change.consume()
                            val slotWidthPx = size.width / items.size.coerceAtLeast(1).toFloat()
                            draggingVisualIndex = (change.position.x / slotWidthPx)
                                .coerceIn(0f, items.lastIndex.toFloat())
                        }
                    }
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = slotWidth * targetVisualIndex),
                    shape = RoundedCornerShape(32.dp),
                    color = accent.copy(alpha = if (darkMode) 0.30f + (heldProgress * 0.08f) else 0.20f + (heldProgress * 0.08f)),
                    border = BorderStroke(1.dp, accent.copy(alpha = if (darkMode) 0.42f else 0.34f)),
                    shadowElevation = 0.dp
                ) {
                    Spacer(
                        modifier = Modifier
                            .widthIn(min = slotWidth - 8.dp)
                            .height(56.dp + (heldProgress * 4).dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { index, item ->
                        val selected = index == selectedIndex
                        val selectionProgress by animateFloatAsState(
                            targetValue = (1f - abs(targetVisualIndex - index).coerceIn(0f, 1f)),
                            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                            label = "reasonixBottomBarItemProgress$index"
                        )
                        val interactionSource = remember { MutableInteractionSource() }
                        val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (darkMode) 0.90f else 0.78f
                        )
                        val activeColor = if (darkMode) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        val itemColor = lerp(idleColor, activeColor, selectionProgress)
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
                                color = accent.copy(
                                    alpha = if (selectionProgress > 0.55f) {
                                        if (darkMode) 0.12f else 0.09f
                                    } else {
                                        0f
                                    }
                                )
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .size(18.dp + (selectionProgress * 2f).dp),
                                    tint = itemColor
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = itemColor,
                                fontWeight = if (selected || selectionProgress > 0.55f) {
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 128.dp),
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
