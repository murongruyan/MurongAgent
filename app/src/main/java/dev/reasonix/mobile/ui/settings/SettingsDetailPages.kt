package dev.reasonix.mobile.ui.settings

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.LocalReasonixUiController
import dev.reasonix.mobile.ui.ReasonixBackgroundMode
import dev.reasonix.mobile.ui.ReasonixGlassSurface
import dev.reasonix.mobile.ui.ReasonixInfoCard
import dev.reasonix.mobile.ui.ReasonixOutlinedActionButton
import dev.reasonix.mobile.ui.ReasonixSectionCard
import dev.reasonix.mobile.ui.ReasonixSecondaryPageFrame
import dev.reasonix.mobile.ui.ReasonixSecondaryPageSurface
import dev.reasonix.mobile.ui.ReasonixTagButton
import dev.reasonix.mobile.ui.ReasonixThemeMode
import dev.reasonix.mobile.ui.ReasonixThemeStyle
import dev.reasonix.mobile.ui.rememberReasonixAccentColor
import dev.reasonix.mobile.ui.rememberReasonixChromeColor
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor

@Composable
fun ThemeSettingsPage() {
    val ui = LocalReasonixUiController.current
    val context = LocalContext.current
    val activity = context.findActivity()
    val accent = rememberReasonixAccentColor()
    val surfaceColor = rememberReasonixSurfaceColor()
    val chromeColor = rememberReasonixChromeColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    var showModeDialog by remember { mutableStateOf(false) }
    var showStyleDialog by remember { mutableStateOf(false) }
    var showBackgroundDialog by remember { mutableStateOf(false) }
    var showWallpaperAccessDialog by remember { mutableStateOf(false) }
    var pendingWallpaperAction by remember { mutableStateOf<ReasonixBackgroundMode?>(null) }
    var colorDialogMode by remember { mutableStateOf<String?>(null) }
    var fontScaleDraft by remember(ui.fontScale) { mutableFloatStateOf(ui.fontScale) }
    var uiScaleDraft by remember(ui.uiScale) { mutableFloatStateOf(ui.uiScale) }
    var blurRadiusDraft by remember(ui.backgroundBlurRadius) { mutableFloatStateOf(ui.backgroundBlurRadius.toFloat()) }
    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        pendingWallpaperAction = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        ui.updateCustomBackgroundUri(uri.toString())
        ui.updateBackgroundMode(ReasonixBackgroundMode.CUSTOM_IMAGE)
        Toast.makeText(context, "已应用自定义背景图", Toast.LENGTH_SHORT).show()
    }
    val permissionRequester = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || ReasonixWallpaperAccess.hasAccess(context)) {
            pendingWallpaperAction?.let { mode ->
                if (mode == ReasonixBackgroundMode.CUSTOM_IMAGE) {
                    backgroundPicker.launch(arrayOf("image/*"))
                } else {
                    ui.updateBackgroundMode(mode)
                }
            }
        } else {
            Toast.makeText(context, "未授予背景图访问权限", Toast.LENGTH_SHORT).show()
        }
        pendingWallpaperAction = null
    }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (ReasonixWallpaperAccess.hasAccess(context)) {
            pendingWallpaperAction?.let { mode ->
                if (mode == ReasonixBackgroundMode.CUSTOM_IMAGE) {
                    backgroundPicker.launch(arrayOf("image/*"))
                } else {
                    ui.updateBackgroundMode(mode)
                }
            }
        } else {
            Toast.makeText(context, "未授予所有文件访问权限", Toast.LENGTH_SHORT).show()
        }
        pendingWallpaperAction = null
    }
    val runWallpaperAction: (ReasonixBackgroundMode) -> Unit = { mode ->
        pendingWallpaperAction = mode
        if (ReasonixWallpaperAccess.hasAccess(context)) {
            if (mode == ReasonixBackgroundMode.CUSTOM_IMAGE) {
                backgroundPicker.launch(arrayOf("image/*"))
            } else {
                ui.updateBackgroundMode(mode)
            }
            pendingWallpaperAction = null
        } else {
            showWallpaperAccessDialog = true
        }
    }
    val styleLabel = when (ui.themeStyle) {
        ReasonixThemeStyle.CLASSIC -> "经典纯色"
        ReasonixThemeStyle.GLASS -> "现代玻璃"
    }
    val modeLabel = when (ui.themeMode) {
        ReasonixThemeMode.SYSTEM -> "跟随系统"
        ReasonixThemeMode.LIGHT -> "浅色模式"
        ReasonixThemeMode.DARK -> "深色模式"
    }
    val backgroundModeLabel = backgroundModeLabel(ui.backgroundMode)
    val colorDialogTitle = when (colorDialogMode) {
        "theme" -> "自定义主题色"
        "background" -> "自定义背景色"
        "surface" -> "自定义卡片色"
        "chrome" -> "自定义顶栏色"
        "muted" -> "自定义次级文字色"
        else -> ""
    }
    val colorDialogValue = when (colorDialogMode) {
        "theme" -> ui.themeColorHex
        "background" -> ui.backgroundColorHex
        "surface" -> ui.surfaceColorHex
        "chrome" -> ui.chromeColorHex
        "muted" -> ui.mutedTextColorHex
        else -> ""
    }

    ReasonixSecondaryPageSurface(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ReasonixSecondaryPageFrame(
                title = "主题界面",
                subtitle = "继续往 murong 那套成熟主题靠齐，保留现有预设色，并补齐表面层、顶栏层和文字层级颜色。"
            ) {
                ReasonixInfoCard(title = "", titleVisible = false) {
                    Text(
                        text = "当前外观",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = styleLabel,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "主题模式：$modeLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "背景模式：$backgroundModeLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReasonixTagButton(
                            text = ui.accentPreset.label,
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        )
                        ReasonixOutlinedActionButton(
                            text = "切换风格",
                            onClick = { showStyleDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                ReasonixSectionCard(title = "风格与模式") {
                    ThemeValueRow(
                        title = "UI 风格",
                        value = styleLabel,
                        onClick = { showStyleDialog = true }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ThemeValueRow(
                        title = "主题模式",
                        value = modeLabel,
                        onClick = { showModeDialog = true }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "现代玻璃会启用悬浮底栏、长按后滑动切页、模糊卡片和更柔和的背景层；经典纯色更接近传统 Material。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ReasonixSectionCard(title = "背景") {
                    ThemeValueRow(
                        title = "界面背景",
                        value = backgroundModeLabel,
                        onClick = { showBackgroundDialog = true }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ThemeValueRow(
                        title = if (ui.backgroundMode == ReasonixBackgroundMode.GRADIENT) {
                            "渐变基础色"
                        } else {
                            "背景颜色"
                        },
                        value = ui.backgroundColorHex,
                        onClick = { colorDialogMode = "background" }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReasonixOutlinedActionButton(
                            text = "选择背景图",
                            onClick = { runWallpaperAction(ReasonixBackgroundMode.CUSTOM_IMAGE) },
                            modifier = Modifier.weight(1f)
                        )
                        ReasonixOutlinedActionButton(
                            text = "桌面壁纸",
                            onClick = { runWallpaperAction(ReasonixBackgroundMode.WALLPAPER) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (ui.customBackgroundUri.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ReasonixOutlinedActionButton(
                            text = "清除自定义背景",
                            onClick = { ui.clearCustomBackgroundUri() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                ReasonixSectionCard(title = "颜色") {
                    Text(
                        text = "保留你说好看的预设色，同时把卡片层、顶栏层和次级文字色独立出来，整体更接近参考项目。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ui.accentPresets().chunked(3).forEachIndexed { rowIndex, row ->
                        if (rowIndex > 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { preset ->
                                val index = ui.accentPresets().indexOf(preset)
                                ReasonixGlassSurface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(MaterialTheme.shapes.large)
                                        .clickable { ui.updateAccentIndex(index) },
                                    shape = MaterialTheme.shapes.large,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(preset.color)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = preset.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = if (ui.accentIndex == index) FontWeight.SemiBold else FontWeight.Medium
                                            )
                                            Text(
                                                text = if (ui.accentIndex == index) "当前使用" else "点按切换",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (ui.accentIndex == index) accent else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                            repeat(3 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    ThemeValueRow(
                        title = "主题色",
                        value = ui.themeColorHex,
                        onClick = { colorDialogMode = "theme" }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ThemeValueRow(
                        title = "卡片色",
                        value = ui.surfaceColorHex,
                        onClick = { colorDialogMode = "surface" }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ThemeValueRow(
                        title = "顶栏色",
                        value = ui.chromeColorHex,
                        onClick = { colorDialogMode = "chrome" }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ThemeValueRow(
                        title = "次级文字色",
                        value = ui.mutedTextColorHex,
                        onClick = { colorDialogMode = "muted" }
                    )
                }

                ReasonixSectionCard(title = "显示") {
                    ThemeSliderControl(
                        title = "字体大小",
                        value = fontScaleDraft,
                        valueText = "${(fontScaleDraft * 100).toInt()}%",
                        valueRange = 0.85f..1.30f,
                        onValueChange = {
                            fontScaleDraft = it
                            ui.updateFontScale(it)
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ThemeSliderControl(
                        title = "界面大小",
                        value = uiScaleDraft,
                        valueText = "${(uiScaleDraft * 100).toInt()}%",
                        valueRange = 0.85f..1.20f,
                        onValueChange = {
                            uiScaleDraft = it
                            ui.updateUiScale(it)
                        }
                    )
                }

                ReasonixSectionCard(title = "模糊与质感") {
                    Text(
                        text = "背景模糊在桌面壁纸和自定义图片模式下最明显，玻璃风下纯色和渐变背景也会保留悬浮质感。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ThemeSliderControl(
                        title = "背景模糊",
                        value = blurRadiusDraft,
                        valueText = "${blurRadiusDraft.toInt()}",
                        valueRange = 0f..60f,
                        onValueChange = {
                            blurRadiusDraft = it
                            ui.updateBackgroundBlurRadius(it.toInt())
                        }
                    )
                }

                ReasonixSectionCard(title = "实时预览") {
                    Text(
                        text = "这里直接看顶部栏、卡片、按钮和文字层级，避免切出去后才知道这套颜色压不压得住。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ReasonixGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                        surfaceColorOverride = chromeColor.copy(alpha = 0.72f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "顶部栏预览",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "标题、副标题和标签会一起跟这一层的颜色关系走",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            }
                            ReasonixTagButton(text = "状态标签", onClick = {})
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    ReasonixGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.70f)
                    ) {
                        Text(
                            text = "内容卡片预览",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "这层主要影响卡片、设置块、弹窗和内容容器的厚重感与通透感。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ReasonixOutlinedActionButton(
                            text = "主操作",
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        )
                        ReasonixTagButton(
                            text = "筛选标签",
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ThemePreviewSwatch(
                            label = "主题色",
                            color = accent,
                            modifier = Modifier.weight(1f)
                        )
                        ThemePreviewSwatch(
                            label = "背景色",
                            color = ui.backgroundColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ThemePreviewSwatch(
                            label = "卡片色",
                            color = surfaceColor,
                            modifier = Modifier.weight(1f)
                        )
                        ThemePreviewSwatch(
                            label = "顶栏色",
                            color = chromeColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    ReasonixGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.66f)
                    ) {
                        Text(
                            text = "文字层级预览",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "这一行是主文字，用来保证重要信息优先可见。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "这一行是次级文字，会用于说明、描述和较弱的信息层。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }
                }
            }
        }
    }

    if (showModeDialog) {
        ThemeChoiceDialog(
            title = "主题模式",
            options = listOf(
                "跟随系统" to ReasonixThemeMode.SYSTEM,
                "浅色模式" to ReasonixThemeMode.LIGHT,
                "深色模式" to ReasonixThemeMode.DARK
            ),
            currentValue = ui.themeMode,
            onDismiss = { showModeDialog = false },
            onSelect = {
                ui.updateThemeMode(it)
                showModeDialog = false
            }
        )
    }

    if (showWallpaperAccessDialog) {
        AlertDialog(
            onDismissRequest = {
                showWallpaperAccessDialog = false
                pendingWallpaperAction = null
            },
            title = { Text("需要背景访问权限") },
            text = {
                Text(
                    text = ReasonixWallpaperAccess.explanation(),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWallpaperAccessDialog = false
                        when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                                allFilesAccessLauncher.launch(
                                    ReasonixWallpaperAccess.allFilesAccessIntent(context)
                                )
                            }
                            activity != null -> {
                                ReasonixWallpaperAccess.permissionToRequest()?.let { permission ->
                                    permissionRequester.launch(permission)
                                } ?: run { pendingWallpaperAction = null }
                            }
                            else -> {
                                pendingWallpaperAction = null
                                Toast.makeText(context, "当前页面无法发起权限请求", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            "去授权"
                        } else {
                            "授予权限"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showWallpaperAccessDialog = false
                        pendingWallpaperAction = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showStyleDialog) {
        ThemeChoiceDialog(
            title = "UI 风格",
            options = listOf(
                "现代玻璃" to ReasonixThemeStyle.GLASS,
                "经典纯色" to ReasonixThemeStyle.CLASSIC
            ),
            currentValue = ui.themeStyle,
            onDismiss = { showStyleDialog = false },
            onSelect = {
                ui.updateThemeStyle(it)
                showStyleDialog = false
            }
        )
    }

    if (showBackgroundDialog) {
        ThemeChoiceDialog(
            title = "界面背景",
            options = listOf(
                "流光渐变" to ReasonixBackgroundMode.GRADIENT,
                "纯色背景" to ReasonixBackgroundMode.SOLID,
                "桌面壁纸" to ReasonixBackgroundMode.WALLPAPER,
                "自定义图片" to ReasonixBackgroundMode.CUSTOM_IMAGE
            ),
            currentValue = ui.backgroundMode,
            onDismiss = { showBackgroundDialog = false },
            onSelect = {
                showBackgroundDialog = false
                if (it == ReasonixBackgroundMode.WALLPAPER || it == ReasonixBackgroundMode.CUSTOM_IMAGE) {
                    runWallpaperAction(it)
                } else {
                    ui.updateBackgroundMode(it)
                }
            }
        )
    }

    colorDialogMode?.let { dialogMode ->
        ThemeColorHexDialog(
            title = colorDialogTitle,
            initialValue = colorDialogValue,
            onDismiss = { colorDialogMode = null },
            onConfirm = { hex ->
                when (dialogMode) {
                    "theme" -> ui.updateThemeColorHex(hex)
                    "background" -> {
                        ui.updateBackgroundColorHex(hex)
                        if (ui.backgroundMode == ReasonixBackgroundMode.WALLPAPER ||
                            ui.backgroundMode == ReasonixBackgroundMode.CUSTOM_IMAGE
                        ) {
                            ui.updateBackgroundMode(ReasonixBackgroundMode.SOLID)
                        }
                    }
                    "surface" -> ui.updateSurfaceColorHex(hex)
                    "chrome" -> ui.updateChromeColorHex(hex)
                    "muted" -> ui.updateMutedTextColorHex(hex)
                }
                colorDialogMode = null
            }
        )
    }
}

private fun backgroundModeLabel(mode: ReasonixBackgroundMode): String {
    return when (mode) {
        ReasonixBackgroundMode.GRADIENT -> "流光渐变"
        ReasonixBackgroundMode.SOLID -> "纯色背景"
        ReasonixBackgroundMode.WALLPAPER -> "桌面壁纸"
        ReasonixBackgroundMode.CUSTOM_IMAGE -> "自定义图片"
    }
}

@Composable
fun AboutPage() {
    val context = LocalContext.current
    val accent = rememberReasonixAccentColor()
    val uriHandler = LocalUriHandler.current
    val packageInfo = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
    }
    val versionName = packageInfo?.versionName ?: "1.0.0"

    ReasonixSecondaryPageSurface(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ReasonixSecondaryPageFrame(
                title = "关于",
                subtitle = "Reasonix Mobile 是面向移动端的代码与项目助手，强调产品感、结构化工具输出和更完整的项目工作流。"
            ) {
                ReasonixInfoCard(title = "", titleVisible = false) {
                    Text(
                        text = "Reasonix Mobile",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "移动端代码与项目助手",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    ReasonixGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
                    ) {
                        Text(
                            text = "当前版本",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = versionName,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "这套界面正在继续往现代玻璃、统一弹窗、横向分页和更像桌面端的项目工作流收口。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                ReasonixSectionCard(title = "应用信息") {
                    AboutInfoRow("应用", "Reasonix Mobile")
                    AboutInfoRow("版本", versionName)
                    AboutInfoRow("引擎", "Reasonix Mobile Core")
                    AboutInfoRow("设计方向", "现代玻璃 / 桌面端式信息密度")
                    AboutInfoRow("当前重点", "统一 UI 壳层 / 编辑页 / 对话体验")
                }

                ReasonixSectionCard(title = "项目链接") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ReasonixOutlinedActionButton(
                            text = "GitHub",
                            onClick = { uriHandler.openUri("https://github.com/murongruyan/reasonix-mobile") },
                            modifier = Modifier.weight(1f)
                        )
                        ReasonixOutlinedActionButton(
                            text = "README",
                            onClick = { uriHandler.openUri("https://github.com/murongruyan/reasonix-mobile/blob/main/README.md") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                ReasonixSectionCard(title = "说明") {
                    Text(
                        text = "这一版 UI 重点朝正式产品化收口：模糊悬浮底栏、统一弹窗、现代玻璃卡片、可横滑的一级页、主题页和关于页，以及更接近桌面端的信息组织方式。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ReasonixGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
                    ) {
                        Text(
                            text = "产品方向",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "优先保证聊天、项目编辑、工具和设置四个一级界面的统一质感，同时把二级页层级、顶部 chrome 和底部交互继续向参考项目靠齐。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeValueRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        ReasonixTagButton(text = value, onClick = onClick)
    }
}

@Composable
private fun ThemeSliderControl(
    title: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            ReasonixTagButton(text = valueText, onClick = {})
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
private fun ThemePreviewSwatch(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    ReasonixGlassSurface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(color)
        )
    }
}

@Composable
private fun ThemeColorHexDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val normalizedValue = value.trim().let { raw ->
        when {
            raw.isBlank() -> ""
            raw.startsWith("#") -> raw
            else -> "#$raw"
        }
    }
    val isValid = normalizedValue.length == 7 || normalizedValue.length == 9
    dev.reasonix.mobile.ui.ReasonixAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.uppercase() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("颜色值") },
                    placeholder = { Text("#F663A6 或 #FFF663A6") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                Text(
                    text = "支持 `#RRGGBB` 和 `#AARRGGBB`。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalizedValue) },
                enabled = isValid
            ) {
                Text("应用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun <T> ThemeChoiceDialog(
    title: String,
    options: List<Pair<String, T>>,
    currentValue: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    dev.reasonix.mobile.ui.ReasonixAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onSelect(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentValue == value,
                            onClick = { onSelect(value) }
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            ReasonixOutlinedActionButton(text = "关闭", onClick = onDismiss)
        }
    )
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}
