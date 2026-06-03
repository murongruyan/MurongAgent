package dev.reasonix.mobile.ui

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.ColorUtils
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

enum class ReasonixThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class ReasonixThemeStyle {
    CLASSIC,
    GLASS
}

data class ReasonixAccentPreset(
    val label: String,
    val color: Color
)

private const val UI_PREFS_NAME = "reasonix_ui"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_THEME_STYLE = "theme_style"
private const val KEY_ACCENT_INDEX = "accent_index"

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

    var accentIndex by mutableIntStateOf(
        prefs.getInt(KEY_ACCENT_INDEX, 0).coerceIn(0, AccentPresets.lastIndex)
    )
        private set

    val accentPreset: ReasonixAccentPreset
        get() = AccentPresets[accentIndex.coerceIn(0, AccentPresets.lastIndex)]

    fun setThemeMode(value: ReasonixThemeMode) {
        themeMode = value
        prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
    }

    fun setThemeStyle(value: ReasonixThemeStyle) {
        themeStyle = value
        prefs.edit().putString(KEY_THEME_STYLE, value.name).apply()
    }

    fun setAccentIndex(value: Int) {
        val safeValue = value.coerceIn(0, AccentPresets.lastIndex)
        accentIndex = safeValue
        prefs.edit().putInt(KEY_ACCENT_INDEX, safeValue).apply()
    }

    fun accentPresets(): List<ReasonixAccentPreset> = AccentPresets
}

internal fun reasonixIsDarkColor(color: Color): Boolean {
    val luminance = (0.299f * color.red) + (0.587f * color.green) + (0.114f * color.blue)
    return luminance < 0.5f
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
    return LocalReasonixUiController.current.accentPreset.color
}

@Composable
fun ReasonixGradientBackground(
    modifier: Modifier = Modifier,
    darkMode: Boolean
) {
    val accent = rememberReasonixAccentColor()
    val baseColor = if (darkMode) Color(0xFF090B12) else Color(0xFFF5F7FD)
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

fun Modifier.reasonixGlassSource(hazeState: HazeState?): Modifier {
    return if (hazeState == null) this else hazeSource(state = hazeState)
}

fun Modifier.reasonixBackdropGlass(
    surfaceColor: Color,
    enabled: Boolean,
    hazeState: HazeState? = null
): Modifier {
    if (!enabled || hazeState == null) return this
    return hazeEffect(
        state = hazeState,
        style = HazeStyle(
            blurRadius = 24.dp,
            backgroundColor = Color.Transparent,
            tint = HazeTint(surfaceColor.copy(alpha = surfaceColor.alpha.coerceIn(0.08f, 0.20f)))
        )
    )
}

@Composable
fun ReasonixGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val ui = LocalReasonixUiController.current
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    val accent = rememberReasonixAccentColor()
    val hazeState = LocalReasonixHazeState.current
    val surfaceColor = if (ui.themeStyle == ReasonixThemeStyle.GLASS) {
        if (darkMode) Color(0xFF151A24).copy(alpha = 0.52f) else Color.White.copy(alpha = 0.58f)
    } else {
        MaterialTheme.colorScheme.surface
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
fun ReasonixFloatingBottomBar(
    items: List<ReasonixBottomBarItem>,
    selectedIndex: Int,
    visualIndex: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = rememberReasonixAccentColor()
    val darkMode = reasonixIsDarkColor(MaterialTheme.colorScheme.background)
    val animatedVisualIndex by animateFloatAsState(
        targetValue = visualIndex.coerceIn(0f, (items.lastIndex).coerceAtLeast(0).toFloat()),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "reasonixBottomBarVisualIndex"
    )
    ReasonixGlassSurface(
        modifier = modifier
            .navigationBarsPadding()
            .widthIn(max = 520.dp)
            .zIndex(5f),
        shape = RoundedCornerShape(40.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val slotWidth = maxWidth / items.size.coerceAtLeast(1)
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = slotWidth * animatedVisualIndex),
                    shape = RoundedCornerShape(32.dp),
                    color = accent.copy(alpha = if (darkMode) 0.26f else 0.18f),
                    border = BorderStroke(1.dp, accent.copy(alpha = if (darkMode) 0.34f else 0.28f)),
                    shadowElevation = 0.dp
                ) {
                    Spacer(
                        modifier = Modifier
                            .widthIn(min = slotWidth - 8.dp)
                            .height(56.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { index, item ->
                        val selected = index == selectedIndex
                        val interactionSource = remember { MutableInteractionSource() }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(32.dp))
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null
                                ) { onSelect(index) }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Transparent
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (selected) {
                                        if (darkMode) Color.White else MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    if (darkMode) Color.White else MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
