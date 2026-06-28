package com.murong.agent.ui

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

enum class MurongThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class MurongThemeStyle {
    CLASSIC,
    GLASS
}

enum class MurongBackgroundMode {
    GRADIENT,
    SOLID,
    WALLPAPER,
    CUSTOM_IMAGE
}

data class MurongAccentPreset(
    val label: String,
    val color: Color
)

private const val UI_PREFS_NAME = "murong_ui"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_THEME_STYLE = "theme_style"
private const val KEY_THEME_COLOR_HEX = "theme_color_hex"
private const val KEY_BACKGROUND_MODE = "background_mode"
private const val KEY_BACKGROUND_COLOR_HEX = "background_color_hex"
private const val KEY_SURFACE_COLOR_HEX = "surface_color_hex"
private const val KEY_CHROME_COLOR_HEX = "chrome_color_hex"
private const val KEY_MUTED_TEXT_COLOR_HEX = "muted_text_color_hex"
private const val KEY_TERMINAL_ICON_COLOR_HEX = "terminal_icon_color_hex"
private const val KEY_TERMINAL_PATH_COLOR_HEX = "terminal_path_color_hex"
private const val KEY_TERMINAL_ERROR_COLOR_HEX = "terminal_error_color_hex"
private const val KEY_CUSTOM_BACKGROUND_URI = "custom_background_uri"
private const val KEY_BACKGROUND_BLUR_RADIUS = "background_blur_radius"
private const val KEY_FONT_SCALE = "font_scale"
private const val KEY_UI_SCALE = "ui_scale"
private const val MURONG_INTERACTION_TARGET_FRAME_RATE = 120L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val MURONG_CPU_PULSE_WARMUP_WINDOW_MS = 260L
private const val MURONG_CPU_PULSE_MAINTAIN_WINDOW_MS = 180L
private const val MURONG_CPU_PULSE_WARMUP_SLICE_MS = 18L
private const val MURONG_CPU_PULSE_MAINTAIN_SLICE_MS = 8L
private const val MURONG_CPU_PULSE_IDLE_SLEEP_MS = 6L
@Volatile
private var murongInteractionCpuPulseSink = 0L

private fun runMurongCpuPulseSlice(durationMs: Long) {
    val durationNanos = durationMs.coerceAtLeast(1L) * 1_000_000L
    val start = System.nanoTime()
    var sink = murongInteractionCpuPulseSink xor start
    while (System.nanoTime() - start < durationNanos) {
        sink = sink xor (sink shl 13)
        sink = sink xor (sink ushr 7)
        sink = sink xor (sink shl 17)
        sink += System.nanoTime()
    }
    murongInteractionCpuPulseSink = sink
}

private object MurongInteractionCpuBooster {
    private val lock = Object()
    @Volatile
    private var started = false
    @Volatile
    private var activeUntilUptimeMs = 0L
    @Volatile
    private var warmupUntilUptimeMs = 0L

    fun boost(warmup: Boolean) {
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            val maintainUntil = now + MURONG_CPU_PULSE_MAINTAIN_WINDOW_MS
            if (maintainUntil > activeUntilUptimeMs) {
                activeUntilUptimeMs = maintainUntil
            }
            if (warmup) {
                val warmupUntil = now + MURONG_CPU_PULSE_WARMUP_WINDOW_MS
                if (warmupUntil > warmupUntilUptimeMs) {
                    warmupUntilUptimeMs = warmupUntil
                }
            }
            if (!started) {
                started = true
                Thread(
                    {
                        runCatching {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                        }
                        while (true) {
                            val nowMs = SystemClock.uptimeMillis()
                            val activeUntil = activeUntilUptimeMs
                            if (nowMs >= activeUntil) {
                                synchronized(lock) {
                                    if (SystemClock.uptimeMillis() >= activeUntilUptimeMs) {
                                        lock.wait(64L)
                                    }
                                }
                                continue
                            }
                            val inWarmup = nowMs < warmupUntilUptimeMs
                            runMurongCpuPulseSlice(
                                if (inWarmup) {
                                    MURONG_CPU_PULSE_WARMUP_SLICE_MS
                                } else {
                                    MURONG_CPU_PULSE_MAINTAIN_SLICE_MS
                                }
                            )
                            Thread.sleep(MURONG_CPU_PULSE_IDLE_SLEEP_MS)
                        }
                    },
                    "MurongInteractionBooster"
                ).apply {
                    isDaemon = true
                    start()
                }
            }
            lock.notifyAll()
        }
    }
}

@Composable
fun MurongInteractionPerformanceHint(
    active: Boolean,
    targetFrameRate: Long = MURONG_INTERACTION_TARGET_FRAME_RATE
) {
    val context = LocalContext.current
    val targetWorkDurationNanos = remember(targetFrameRate) {
        (NANOS_PER_SECOND / targetFrameRate.coerceAtLeast(1L)).coerceAtLeast(1L)
    }
    LaunchedEffect(context, active, targetWorkDurationNanos) {
        if (!active) return@LaunchedEffect
        val hintManager = context.getSystemService(PerformanceHintManager::class.java)
            ?: return@LaunchedEffect
        val session = runCatching {
            hintManager.createHintSession(
                intArrayOf(Process.myTid()),
                targetWorkDurationNanos
            )
        }.getOrNull() ?: return@LaunchedEffect
        session.updateTargetWorkDuration(targetWorkDurationNanos)
        var previousFrameNanos = withFrameNanos { it }
        try {
            while (currentCoroutineContext().isActive) {
                val frameNanos = withFrameNanos { it }
                session.reportActualWorkDuration(
                    (frameNanos - previousFrameNanos).coerceAtLeast(1L)
                )
                previousFrameNanos = frameNanos
            }
        } finally {
            session.close()
        }
    }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        MurongInteractionCpuBooster.boost(warmup = true)
        while (currentCoroutineContext().isActive) {
            delay(48L)
            MurongInteractionCpuBooster.boost(warmup = false)
        }
    }
}

private val AccentPresets = listOf(
    MurongAccentPreset("樱粉", Color(0xFFF663A6)),
    MurongAccentPreset("晴蓝", Color(0xFF5B8CFF)),
    MurongAccentPreset("薄荷", Color(0xFF34C8A3)),
    MurongAccentPreset("琥珀", Color(0xFFFFB347)),
    MurongAccentPreset("紫雾", Color(0xFF9B7BFF))
)

val MurongGlassBottomBarScrollPadding = 112.dp
val MurongClassicBottomBarScrollPadding = 156.dp

@Stable
class MurongUiController(private val context: Context) {
    private val prefs = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(
        runCatching {
            MurongThemeMode.valueOf(
                prefs.getString(KEY_THEME_MODE, MurongThemeMode.SYSTEM.name)
                    ?: MurongThemeMode.SYSTEM.name
            )
        }.getOrDefault(MurongThemeMode.SYSTEM)
    )
        private set

    var themeStyle by mutableStateOf(
        runCatching {
            MurongThemeStyle.valueOf(
                prefs.getString(KEY_THEME_STYLE, MurongThemeStyle.GLASS.name)
                    ?: MurongThemeStyle.GLASS.name
            )
        }.getOrDefault(MurongThemeStyle.GLASS)
    )
        private set

    var themeColorHex by mutableStateOf(
        normalizeMurongColorHex(
            prefs.getString(KEY_THEME_COLOR_HEX, null),
            AccentPresets.first().color
        )
    )
        private set

    var backgroundMode by mutableStateOf(
        runCatching {
            MurongBackgroundMode.valueOf(
                prefs.getString(KEY_BACKGROUND_MODE, MurongBackgroundMode.GRADIENT.name)
                    ?: MurongBackgroundMode.GRADIENT.name
            )
        }.getOrDefault(MurongBackgroundMode.GRADIENT)
    )
        private set

    var backgroundColorHex by mutableStateOf(
        prefs.getString(KEY_BACKGROUND_COLOR_HEX, "").orEmpty()
    )
        private set

    var surfaceColorHex by mutableStateOf(
        prefs.getString(KEY_SURFACE_COLOR_HEX, "").orEmpty()
    )
        private set

    var chromeColorHex by mutableStateOf(
        prefs.getString(KEY_CHROME_COLOR_HEX, "").orEmpty()
    )
        private set

    var mutedTextColorHex by mutableStateOf(
        prefs.getString(KEY_MUTED_TEXT_COLOR_HEX, "").orEmpty()
    )
        private set

    var terminalIconColorHex by mutableStateOf(
        normalizeMurongColorHex(
            prefs.getString(KEY_TERMINAL_ICON_COLOR_HEX, null),
            Color(0xFF22C55E)
        )
    )
        private set

    var terminalPathColorHex by mutableStateOf(
        normalizeMurongColorHex(
            prefs.getString(KEY_TERMINAL_PATH_COLOR_HEX, null),
            Color(0xFF86EFAC)
        )
    )
        private set

    var terminalErrorColorHex by mutableStateOf(
        normalizeMurongColorHex(
            prefs.getString(KEY_TERMINAL_ERROR_COLOR_HEX, null),
            Color(0xFFEF4444)
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

    val accentPreset: MurongAccentPreset
        get() = AccentPresets.getOrNull(accentIndex)
            ?: MurongAccentPreset("自定义", accentColor)

    val accentColor: Color
        get() = parseMurongColor(themeColorHex, AccentPresets.first().color)

    val backgroundColor: Color
        get() = parseMurongColor(
            backgroundColorHex,
            if (themeMode == MurongThemeMode.DARK) Color(0xFF090B12) else Color(0xFFF5F7FD)
        )

    fun resolvedBackgroundColor(darkMode: Boolean): Color {
        return parseMurongColor(backgroundColorHex, defaultMurongBackgroundColor(darkMode))
    }

    fun resolvedSurfaceColor(darkMode: Boolean): Color {
        return parseMurongColor(surfaceColorHex, defaultMurongSurfaceColor(darkMode))
    }

    fun resolvedChromeColor(darkMode: Boolean): Color {
        return parseMurongColor(chromeColorHex, defaultMurongChromeColor(darkMode))
    }

    fun resolvedMutedTextColor(darkMode: Boolean): Color {
        return parseMurongColor(mutedTextColorHex, defaultMurongMutedTextColor(darkMode))
    }

    fun resolvedBackgroundColorHex(darkMode: Boolean): String = resolvedBackgroundColor(darkMode).toMurongHex()

    fun resolvedSurfaceColorHex(darkMode: Boolean): String = resolvedSurfaceColor(darkMode).toMurongHex()

    fun resolvedChromeColorHex(darkMode: Boolean): String = resolvedChromeColor(darkMode).toMurongHex()

    fun resolvedMutedTextColorHex(darkMode: Boolean): String = resolvedMutedTextColor(darkMode).toMurongHex()

    fun updateThemeMode(value: MurongThemeMode) {
        themeMode = value
        prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
    }

    fun updateThemeStyle(value: MurongThemeStyle) {
        themeStyle = value
        prefs.edit().putString(KEY_THEME_STYLE, value.name).apply()
    }

    fun updateAccentIndex(value: Int) {
        val safeValue = value.coerceIn(0, AccentPresets.lastIndex)
        updateThemeColorHex(AccentPresets[safeValue].color.toMurongHex())
    }

    fun updateThemeColorHex(value: String) {
        themeColorHex = normalizeMurongColorHex(value, accentColor)
        prefs.edit().putString(KEY_THEME_COLOR_HEX, themeColorHex).apply()
    }

    fun updateBackgroundMode(value: MurongBackgroundMode) {
        backgroundMode = value
        prefs.edit().putString(KEY_BACKGROUND_MODE, value.name).apply()
    }

    fun updateBackgroundColorHex(value: String) {
        backgroundColorHex = normalizeMurongColorHex(value, backgroundColor)
        prefs.edit().putString(KEY_BACKGROUND_COLOR_HEX, backgroundColorHex).apply()
    }

    fun updateCustomBackgroundUri(value: String) {
        customBackgroundUri = value.trim()
        prefs.edit().putString(KEY_CUSTOM_BACKGROUND_URI, customBackgroundUri).apply()
    }

    fun updateSurfaceColorHex(value: String) {
        surfaceColorHex = normalizeMurongColorHex(value, Color.White)
        prefs.edit().putString(KEY_SURFACE_COLOR_HEX, surfaceColorHex).apply()
    }

    fun updateChromeColorHex(value: String) {
        chromeColorHex = normalizeMurongColorHex(value, Color.White)
        prefs.edit().putString(KEY_CHROME_COLOR_HEX, chromeColorHex).apply()
    }

    fun updateMutedTextColorHex(value: String) {
        mutedTextColorHex = normalizeMurongColorHex(value, Color(0xFF6A738A))
        prefs.edit().putString(KEY_MUTED_TEXT_COLOR_HEX, mutedTextColorHex).apply()
    }

    fun updateTerminalIconColorHex(value: String) {
        terminalIconColorHex = normalizeMurongColorHex(value, Color(0xFF22C55E))
        prefs.edit().putString(KEY_TERMINAL_ICON_COLOR_HEX, terminalIconColorHex).apply()
    }

    fun updateTerminalPathColorHex(value: String) {
        terminalPathColorHex = normalizeMurongColorHex(value, Color(0xFF86EFAC))
        prefs.edit().putString(KEY_TERMINAL_PATH_COLOR_HEX, terminalPathColorHex).apply()
    }

    fun updateTerminalErrorColorHex(value: String) {
        terminalErrorColorHex = normalizeMurongColorHex(value, Color(0xFFEF4444))
        prefs.edit().putString(KEY_TERMINAL_ERROR_COLOR_HEX, terminalErrorColorHex).apply()
    }

    fun clearCustomBackgroundUri() {
        customBackgroundUri = ""
        prefs.edit().remove(KEY_CUSTOM_BACKGROUND_URI).apply()
        if (backgroundMode == MurongBackgroundMode.CUSTOM_IMAGE) {
            updateBackgroundMode(MurongBackgroundMode.GRADIENT)
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

    fun accentPresets(): List<MurongAccentPreset> = AccentPresets

    fun restoreThemeDefaults() {
        themeMode = MurongThemeMode.SYSTEM
        themeStyle = MurongThemeStyle.GLASS
        themeColorHex = AccentPresets.first().color.toMurongHex()
        backgroundMode = MurongBackgroundMode.GRADIENT
        backgroundColorHex = ""
        surfaceColorHex = ""
        chromeColorHex = ""
        mutedTextColorHex = ""
        terminalIconColorHex = Color(0xFF22C55E).toMurongHex()
        terminalPathColorHex = Color(0xFF86EFAC).toMurongHex()
        terminalErrorColorHex = Color(0xFFEF4444).toMurongHex()
        customBackgroundUri = ""
        backgroundBlurRadius = 18
        fontScale = 1.0f
        uiScale = 1.0f
        prefs.edit()
            .putString(KEY_THEME_MODE, themeMode.name)
            .putString(KEY_THEME_STYLE, themeStyle.name)
            .putString(KEY_THEME_COLOR_HEX, themeColorHex)
            .putString(KEY_BACKGROUND_MODE, backgroundMode.name)
            .remove(KEY_BACKGROUND_COLOR_HEX)
            .remove(KEY_SURFACE_COLOR_HEX)
            .remove(KEY_CHROME_COLOR_HEX)
            .remove(KEY_MUTED_TEXT_COLOR_HEX)
            .putString(KEY_TERMINAL_ICON_COLOR_HEX, terminalIconColorHex)
            .putString(KEY_TERMINAL_PATH_COLOR_HEX, terminalPathColorHex)
            .putString(KEY_TERMINAL_ERROR_COLOR_HEX, terminalErrorColorHex)
            .remove(KEY_CUSTOM_BACKGROUND_URI)
            .putInt(KEY_BACKGROUND_BLUR_RADIUS, backgroundBlurRadius)
            .putFloat(KEY_FONT_SCALE, fontScale)
            .putFloat(KEY_UI_SCALE, uiScale)
            .apply()
    }
}

internal fun defaultMurongBackgroundColor(darkMode: Boolean): Color {
    return if (darkMode) Color(0xFF09101A) else Color(0xFFF5F7FD)
}

internal fun defaultMurongSurfaceColor(darkMode: Boolean): Color {
    return if (darkMode) Color(0xFF141C28) else Color.White
}

internal fun defaultMurongChromeColor(darkMode: Boolean): Color {
    return if (darkMode) Color(0xFF0F1723) else Color.White
}

internal fun defaultMurongMutedTextColor(darkMode: Boolean): Color {
    return if (darkMode) Color(0xFF97A4BF) else Color(0xFF6A738A)
}

internal fun normalizeMurongColorHex(raw: String?, fallback: Color): String {
    val value = raw?.trim().orEmpty().ifBlank { fallback.toMurongHex() }
    val normalized = if (value.startsWith("#")) value else "#$value"
    return when (normalized.length) {
        7, 9 -> normalized.uppercase()
        else -> fallback.toMurongHex()
    }
}

internal fun parseMurongColor(hex: String, fallback: Color): Color {
    return runCatching { Color(AndroidColor.parseColor(hex)) }.getOrDefault(fallback)
}

internal fun Color.toMurongHex(): String {
    return String.format("#%08X", toArgb())
}

internal fun murongIsDarkColor(color: Color): Boolean {
    val luminance = (0.299f * color.red) + (0.587f * color.green) + (0.114f * color.blue)
    return luminance < 0.5f
}

private fun Modifier.murongBackgroundBlur(radiusDp: Int): Modifier {
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

val LocalMurongUiController = compositionLocalOf<MurongUiController> {
    error("MurongUiController not provided")
}

val LocalMurongHazeState = compositionLocalOf<HazeState?> { null }

@Stable
data class MurongSurfaceTokens(
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
fun rememberMurongUiController(): MurongUiController {
    val context = LocalContext.current.applicationContext
    return remember(context) { MurongUiController(context) }
}

@Composable
@ReadOnlyComposable
fun rememberMurongAccentColor(): Color {
    return LocalMurongUiController.current.accentColor
}

@Composable
@ReadOnlyComposable
fun rememberMurongBottomBarScrollPadding(): Dp {
    val ui = LocalMurongUiController.current
    return if (ui.themeStyle == MurongThemeStyle.GLASS) {
        MurongGlassBottomBarScrollPadding
    } else {
        MurongClassicBottomBarScrollPadding
    }
}

@Composable
@ReadOnlyComposable
fun rememberMurongSurfaceColor(): Color {
    val ui = LocalMurongUiController.current
    val darkMode = murongIsDarkColor(MaterialTheme.colorScheme.background)
    return ui.resolvedSurfaceColor(darkMode)
}

@Composable
@ReadOnlyComposable
fun rememberMurongChromeColor(): Color {
    val ui = LocalMurongUiController.current
    val darkMode = murongIsDarkColor(MaterialTheme.colorScheme.background)
    return ui.resolvedChromeColor(darkMode)
}

@Composable
@ReadOnlyComposable
fun rememberMurongMutedTextColor(): Color {
    val ui = LocalMurongUiController.current
    val darkMode = murongIsDarkColor(MaterialTheme.colorScheme.background)
    return ui.resolvedMutedTextColor(darkMode)
}

@Composable
@ReadOnlyComposable
fun rememberMurongTerminalIconColor(): Color {
    val ui = LocalMurongUiController.current
    return parseMurongColor(ui.terminalIconColorHex, Color(0xFF22C55E))
}

@Composable
@ReadOnlyComposable
fun rememberMurongTerminalPathColor(): Color {
    val ui = LocalMurongUiController.current
    return parseMurongColor(ui.terminalPathColorHex, Color(0xFF86EFAC))
}

@Composable
@ReadOnlyComposable
fun rememberMurongTerminalErrorColor(): Color {
    val ui = LocalMurongUiController.current
    return parseMurongColor(ui.terminalErrorColorHex, Color(0xFFEF4444))
}

@Composable
fun rememberMurongSurfaceTokens(): MurongSurfaceTokens {
    val ui = LocalMurongUiController.current
    val darkMode = murongIsDarkColor(MaterialTheme.colorScheme.background)
    val accent = rememberMurongAccentColor()
    val backgroundSeed = ui.resolvedBackgroundColor(darkMode)
    val surfaceSeed = rememberMurongSurfaceColor()
    val chromeSeed = rememberMurongChromeColor()
    val imageBackgroundMode = ui.backgroundMode == MurongBackgroundMode.WALLPAPER ||
        ui.backgroundMode == MurongBackgroundMode.CUSTOM_IMAGE
    val sharedBlurRadius = if (imageBackgroundMode) {
        ui.backgroundBlurRadius.coerceIn(0, 42)
    } else {
        0
    }
    // List cards keep the glass tint but skip live blur to protect scroll performance.
    val cardBlurRadius = 0
    val popupBlurRadius = sharedBlurRadius
    val secondaryPageBlurRadius = 0
    val cardGlassColor = rememberMurongGlassColor(
        baseColor = surfaceSeed,
        blurRadius = cardBlurRadius,
        darkMode = darkMode
    )
    val bottomBarBase = when (ui.backgroundMode) {
        MurongBackgroundMode.SOLID -> Color(
            ColorUtils.blendARGB(
                backgroundSeed.toArgb(),
                accent.toArgb(),
                if (darkMode) 0.14f else 0.18f
            )
        )
        else -> surfaceSeed
    }
    val bottomBarGlassColor = rememberMurongGlassColor(
        baseColor = bottomBarBase,
        blurRadius = sharedBlurRadius,
        darkMode = darkMode
    )
    val popupGlassColor = rememberMurongGlassColor(
        baseColor = surfaceSeed,
        blurRadius = popupBlurRadius,
        darkMode = darkMode
    )
    val secondaryPageGlassColor = rememberMurongGlassColor(
        baseColor = surfaceSeed,
        blurRadius = secondaryPageBlurRadius,
        darkMode = darkMode
    )
    val secondaryPageBase = when (ui.backgroundMode) {
        MurongBackgroundMode.WALLPAPER,
        MurongBackgroundMode.CUSTOM_IMAGE -> surfaceSeed
        MurongBackgroundMode.SOLID -> backgroundSeed
        MurongBackgroundMode.GRADIENT -> chromeSeed
    }

    return MurongSurfaceTokens(
        accent = accent,
        cardGlassColor = cardGlassColor,
        cardContainerColor = murongGlassContainerColor(
            glassColor = cardGlassColor,
            blurRadius = cardBlurRadius,
            darkMode = darkMode
        ),
        cardBlurRadius = cardBlurRadius,
        bottomBarGlassColor = bottomBarGlassColor,
        bottomBarContainerColor = murongGlassContainerColor(
            glassColor = bottomBarGlassColor,
            blurRadius = sharedBlurRadius,
            darkMode = darkMode
        ),
        bottomBarBlurRadius = sharedBlurRadius,
        popupGlassColor = popupGlassColor,
        popupContainerColor = murongGlassContainerColor(
            glassColor = popupGlassColor,
            blurRadius = popupBlurRadius,
            darkMode = darkMode
        ),
        popupBlurRadius = popupBlurRadius,
        secondaryPageGlassColor = secondaryPageGlassColor,
        secondaryPageContainerColor = if (imageBackgroundMode) {
            if (secondaryPageBlurRadius > 0) {
                murongGlassContainerColor(
                    glassColor = secondaryPageGlassColor,
                    blurRadius = secondaryPageBlurRadius,
                    darkMode = darkMode
                )
            } else {
                Color.Transparent
            }
        } else {
            if (ui.themeStyle == MurongThemeStyle.GLASS) {
                Color.Transparent
            } else {
                secondaryPageBase.copy(alpha = 1f)
            }
        },
        secondaryPageBlurRadius = secondaryPageBlurRadius
    )
}

@Composable
fun rememberOpaqueMurongSecondaryPageColor(): Color {
    val ui = LocalMurongUiController.current
    val darkMode = murongIsDarkColor(MaterialTheme.colorScheme.background)
    val backgroundSeed = ui.resolvedBackgroundColor(darkMode)
    val chromeSeed = rememberMurongChromeColor()
    val surfaceSeed = rememberMurongSurfaceColor()
    return when (ui.backgroundMode) {
        MurongBackgroundMode.WALLPAPER,
        MurongBackgroundMode.CUSTOM_IMAGE -> surfaceSeed.copy(alpha = 1f)
        MurongBackgroundMode.SOLID -> backgroundSeed.copy(alpha = 1f)
        MurongBackgroundMode.GRADIENT -> chromeSeed.copy(alpha = 1f)
    }
}

@Composable
fun MurongBackgroundLayer(
    modifier: Modifier = Modifier,
    darkMode: Boolean
) {
    val context = LocalContext.current
    val ui = LocalMurongUiController.current
    val backgroundSeed = ui.resolvedBackgroundColor(darkMode)
    val hazeState = LocalMurongHazeState.current
    val bitmapState = produceState<Bitmap?>(initialValue = null, ui.backgroundMode, ui.customBackgroundUri) {
        value = withContext(Dispatchers.IO) {
            when (ui.backgroundMode) {
                MurongBackgroundMode.WALLPAPER -> loadWallpaperBitmap(context)
                MurongBackgroundMode.CUSTOM_IMAGE -> loadBitmapFromUri(context, ui.customBackgroundUri)
                else -> null
            }
        }
    }
    val backgroundModifier = modifier
        .fillMaxSize()
        .murongGlassSource(hazeState)
        .then(
            if (ui.backgroundBlurRadius > 0 &&
                ui.backgroundMode in setOf(
                    MurongBackgroundMode.WALLPAPER,
                    MurongBackgroundMode.CUSTOM_IMAGE
                )
            ) {
                Modifier.murongBackgroundBlur(ui.backgroundBlurRadius)
            } else {
                Modifier
            }
        )

    when (ui.backgroundMode) {
        MurongBackgroundMode.WALLPAPER,
        MurongBackgroundMode.CUSTOM_IMAGE -> {
            val bitmap = bitmapState.value
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = backgroundModifier,
                    contentScale = ContentScale.Crop
                )
            } else {
                MurongGradientBackground(
                    modifier = modifier.fillMaxSize().murongGlassSource(hazeState),
                    darkMode = darkMode,
                    baseColor = backgroundSeed,
                    accent = ui.accentColor
                )
            }
        }

        MurongBackgroundMode.SOLID -> {
            Box(modifier = backgroundModifier.background(backgroundSeed))
        }

        MurongBackgroundMode.GRADIENT -> {
            MurongGradientBackground(
                modifier = modifier.fillMaxSize().murongGlassSource(hazeState),
                darkMode = darkMode,
                baseColor = backgroundSeed,
                accent = ui.accentColor
            )
        }
    }
}

@Composable
fun MurongGradientBackground(
    modifier: Modifier = Modifier,
    darkMode: Boolean,
    baseColor: Color = if (darkMode) Color(0xFF090B12) else Color(0xFFF5F7FD),
    accent: Color = rememberMurongAccentColor()
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

fun Modifier.murongGlassSource(hazeState: HazeState?): Modifier {
    return if (hazeState == null) this else hazeSource(state = hazeState)
}

fun Modifier.murongBackdropGlass(
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

private fun murongOpaqueGlassColor(
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
private fun rememberMurongGlassColor(
    baseColor: Color,
    blurRadius: Int,
    darkMode: Boolean = murongIsDarkColor(MaterialTheme.colorScheme.background)
): Color {
    val isGlassStyle = LocalMurongUiController.current.themeStyle == MurongThemeStyle.GLASS
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

private fun murongGlassTone(
    seed: Color,
    accent: Color,
    darkMode: Boolean,
    accentBlendRatio: Float,
    alpha: Float
): Color {
    return murongOpaqueGlassColor(
        seed = seed,
        accent = accent,
        darkMode = darkMode,
        accentBlendRatio = accentBlendRatio,
        alpha = alpha
    )
}

private fun murongGlassContainerColor(
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
fun MurongGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    surfaceColorOverride: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = rememberMurongSurfaceTokens()
    val ui = LocalMurongUiController.current
    val hazeState = LocalMurongHazeState.current
    val isGlassStyle = ui.themeStyle == MurongThemeStyle.GLASS
    val effectiveBlurRadius = tokens.cardBlurRadius
    val surfaceColor = surfaceColorOverride ?: tokens.cardContainerColor
    val borderColor = tokens.accent.copy(alpha = 0.18f)
    Surface(
        modifier = modifier
            .clip(shape)
            .murongBackdropGlass(
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
fun MurongInfoCard(
    title: String,
    modifier: Modifier = Modifier,
    titleVisible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val accent = rememberMurongAccentColor()
    val ui = LocalMurongUiController.current
    val isGlassStyle = ui.themeStyle == MurongThemeStyle.GLASS
    MurongGlassSurface(
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
fun MurongSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val ui = LocalMurongUiController.current
    val isGlassStyle = ui.themeStyle == MurongThemeStyle.GLASS
    MurongGlassSurface(
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
fun MurongOutlinedActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val ui = LocalMurongUiController.current
    val accent = rememberMurongAccentColor()
    if (ui.themeStyle == MurongThemeStyle.GLASS) {
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
fun MurongTagButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = rememberMurongAccentColor()
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
fun MurongAlertDialog(
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
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            MurongPopupSurface(
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
fun MurongDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        MurongDisableDialogDimEffect()
        content()
    }
}

@Composable
private fun MurongDisableDialogDimEffect() {
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
fun MurongLargeDialogScaffold(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    topPadding: androidx.compose.ui.unit.Dp = 20.dp,
    horizontalPadding: androidx.compose.ui.unit.Dp = 8.dp,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    MurongDialog(onDismissRequest = onDismissRequest) {
        MurongSecondaryPageSurface(
            modifier = modifier
                .fillMaxSize()
                .imePadding()
                .padding(top = topPadding, start = horizontalPadding, end = horizontalPadding),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            forceOpaque = true,
            contentPadding = contentPadding,
            content = content
        )
    }
}

data class MurongBottomBarItem(
    val label: String,
    val icon: ImageVector
)

data class MurongChoiceDialogItem(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false
)

@Composable
fun MurongSecondaryPageSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(34.dp),
    forceOpaque: Boolean = false,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = rememberMurongSurfaceTokens()
    val opaqueColor = rememberOpaqueMurongSecondaryPageColor()
    val hazeState = LocalMurongHazeState.current
    val surfaceModifier = if (tokens.secondaryPageBlurRadius > 0) {
        modifier
            .clip(shape)
            .murongBackdropGlass(
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
fun MurongPrimaryPageSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(34.dp),
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = rememberMurongSurfaceTokens()
    val ui = LocalMurongUiController.current
    val darkMode = murongIsDarkColor(MaterialTheme.colorScheme.background)
    var touchBoostActive by remember { mutableStateOf(false) }
    val containerColor = if (ui.themeStyle == MurongThemeStyle.GLASS) {
        Color.Transparent
    } else {
        tokens.secondaryPageContainerColor.copy(alpha = if (darkMode) 0.98f else 0.94f)
    }
    MurongInteractionPerformanceHint(active = touchBoostActive)
    Surface(
        modifier = modifier
            .clip(shape)
            .pointerInput(Unit) {
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val isPressed = event.changes.any { it.pressed }
                            if (isPressed && !touchBoostActive) {
                                MurongInteractionCpuBooster.boost(warmup = true)
                            }
                            touchBoostActive = isPressed
                        }
                    }
                } finally {
                    touchBoostActive = false
                }
            },
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
private fun MurongBottomBarSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(40.dp),
    glassTintColorOverride: Color? = null,
    glassMinTintAlpha: Float? = null,
    glassMaxTintAlpha: Float? = null,
    glassShadowElevation: Dp? = null,
    content: @Composable () -> Unit
) {
    val tokens = rememberMurongSurfaceTokens()
    val ui = LocalMurongUiController.current
    val hazeState = LocalMurongHazeState.current
    val darkMode = murongIsDarkColor(MaterialTheme.colorScheme.background)
    val isGlassStyle = ui.themeStyle == MurongThemeStyle.GLASS
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
    Surface(
        modifier = modifier
            .clip(shape)
            .murongBackdropGlass(
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


@Composable
fun MurongPopupSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(30.dp),
    forceOpaque: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = rememberMurongSurfaceTokens()
    val opaqueColor = rememberOpaqueMurongSecondaryPageColor()
    val ui = LocalMurongUiController.current
    val hazeState = LocalMurongHazeState.current
    val isGlassStyle = ui.themeStyle == MurongThemeStyle.GLASS
    val effectiveBlurRadius = if (isGlassStyle && tokens.popupBlurRadius <= 0) {
        24
    } else {
        tokens.popupBlurRadius
    }
    Surface(
        modifier = modifier
            .clip(shape)
            .murongBackdropGlass(
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
fun MurongCompactChoiceDialog(
    title: String,
    items: List<MurongChoiceDialogItem>,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dialogAlignment: Alignment = Alignment.Center,
    popupOffset: IntOffset = IntOffset.Zero,
    showCancelButton: Boolean = true,
    onSelect: (MurongChoiceDialogItem) -> Unit
) {
    val density = LocalDensity.current
    val popupProperties = PopupProperties(
        focusable = false,
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        clippingEnabled = true
    )
    val usesAnchoredPopupPosition = dialogAlignment == Alignment.TopStart && popupOffset != IntOffset.Zero
    val popupModifier = if (usesAnchoredPopupPosition) {
        Modifier
    } else {
        when (dialogAlignment) {
            Alignment.TopStart -> Modifier.padding(start = 8.dp, top = 12.dp)
            Alignment.TopCenter -> Modifier.padding(top = 12.dp)
            Alignment.TopEnd -> Modifier.padding(end = 8.dp, top = 12.dp)
            Alignment.CenterStart -> Modifier.padding(start = 8.dp)
            Alignment.CenterEnd -> Modifier.padding(end = 8.dp)
            Alignment.BottomStart -> Modifier.padding(start = 8.dp, bottom = 8.dp)
            Alignment.BottomCenter -> Modifier.padding(bottom = 8.dp)
            Alignment.BottomEnd -> Modifier.padding(end = 8.dp, bottom = 8.dp)
            else -> Modifier.padding(8.dp)
        }
    }
    val anchoredPopupPositionProvider = remember(popupOffset, density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val marginPx = with(density) { 8.dp.roundToPx() }
                val x = popupOffset.x.coerceIn(
                    marginPx,
                    (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
                )
                val y = popupOffset.y.coerceIn(
                    marginPx,
                    (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
                )
                return IntOffset(x, y)
            }
        }
    }
    val popupContent: @Composable () -> Unit = {
        MurongPopupSurface(
            modifier = modifier
                .then(popupModifier)
                .widthIn(min = 168.dp, max = 240.dp)
                .heightIn(max = 360.dp),
            shape = RoundedCornerShape(20.dp),
            forceOpaque = true
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                subtitle?.takeIf { it.isNotBlank() }?.let { helper ->
                    Text(
                        text = helper,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                    )
                }
                items.forEachIndexed { index, item ->
                    val itemShape = when {
                        items.size == 1 -> RoundedCornerShape(12.dp)
                        index == 0 -> RoundedCornerShape(
                            topStart = 12.dp,
                            topEnd = 12.dp,
                            bottomStart = 8.dp,
                            bottomEnd = 8.dp
                        )
                        index == items.lastIndex -> RoundedCornerShape(
                            topStart = 8.dp,
                            topEnd = 8.dp,
                            bottomStart = 12.dp,
                            bottomEnd = 12.dp
                        )
                        else -> RoundedCornerShape(8.dp)
                    }
                    val titleColor = when {
                        !item.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                        item.destructive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(itemShape)
                            .clickable(enabled = item.enabled) {
                                onSelect(item)
                                onDismissRequest()
                            },
                        shape = itemShape,
                        color = if (item.enabled) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (item.destructive && item.enabled) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = titleColor,
                                fontWeight = FontWeight.Medium
                            )
                            item.subtitle?.takeIf { it.isNotBlank() }?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = if (item.enabled) 0.82f else 0.6f
                                    )
                                )
                            }
                        }
                    }
                }
                if (showCancelButton) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismissRequest) {
                            Text("取消")
                        }
                    }
                }
            }
        }
    }
    if (usesAnchoredPopupPosition) {
        Popup(
            popupPositionProvider = anchoredPopupPositionProvider,
            onDismissRequest = onDismissRequest,
            properties = popupProperties,
            content = popupContent
        )
    } else {
        Popup(
            alignment = dialogAlignment,
            offset = popupOffset,
            onDismissRequest = onDismissRequest,
            properties = popupProperties,
            content = popupContent
        )
    }
}

@Composable
fun MurongFloatingBottomBar(
    items: List<MurongBottomBarItem>,
    selectedIndex: Int,
    visualIndex: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = rememberMurongAccentColor()
    val ui = LocalMurongUiController.current
    val hazeState = LocalMurongHazeState.current
    val darkMode = murongIsDarkColor(MaterialTheme.colorScheme.background)
    val isGlassStyle = ui.themeStyle == MurongThemeStyle.GLASS
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
        label = "murongBottomBarHoldProgress"
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
    MurongBottomBarSurface(
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
                        .murongBackdropGlass(
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
                            label = "murongBottomBarItemProgress$index"
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
fun MurongSecondaryPageFrame(
    title: String,
    subtitle: String? = null,
    includeBottomBarPadding: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val bottomBarScrollPadding = rememberMurongBottomBarScrollPadding()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = 12.dp,
                top = 12.dp,
                end = 12.dp,
                bottom = if (includeBottomBarPadding) bottomBarScrollPadding else 12.dp
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        MurongInfoCard(title = title, titleVisible = false) {
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
