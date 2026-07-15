package com.murong.agent.ui.settings

import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.murong.agent.ui.defaultMurongChromeColor
import com.murong.agent.ui.defaultMurongMutedTextColor
import com.murong.agent.ui.defaultMurongSurfaceColor
import com.murong.agent.ui.LocalMurongUiController
import com.murong.agent.ui.MurongDialog
import com.murong.agent.ui.MurongBackgroundMode
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongInfoCard
import com.murong.agent.ui.MurongOutlinedActionButton
import com.murong.agent.ui.MurongPopupSurface
import com.murong.agent.ui.MurongSectionCard
import com.murong.agent.ui.MurongSecondaryPageFrame
import com.murong.agent.ui.MurongSecondaryPageSurface
import com.murong.agent.ui.MurongTagButton
import com.murong.agent.ui.MurongThemeMode
import com.murong.agent.ui.MurongThemeStyle
import com.murong.agent.ui.MurongTransientMessageBus
import com.murong.agent.ui.murongIsDarkColor
import com.murong.agent.ui.parseMurongColor
import com.murong.agent.ui.rememberMurongAccentColor
import com.murong.agent.ui.rememberMurongChromeColor
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor
import com.murong.agent.ui.rememberMurongTerminalErrorColor
import com.murong.agent.ui.rememberMurongTerminalIconColor
import com.murong.agent.ui.rememberMurongTerminalPathColor

@Composable
fun ThemeSettingsPage() {
    val ui = LocalMurongUiController.current
    val context = LocalContext.current
    val activity = context.findActivity()
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val terminalIconColor = rememberMurongTerminalIconColor()
    val terminalPathColor = rememberMurongTerminalPathColor()
    val terminalErrorColor = rememberMurongTerminalErrorColor()
    val darkMode = when (ui.themeMode) {
        MurongThemeMode.DARK -> true
        MurongThemeMode.LIGHT -> false
        MurongThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    var showModeDialog by remember { mutableStateOf(false) }
    var showStyleDialog by remember { mutableStateOf(false) }
    var showBackgroundDialog by remember { mutableStateOf(false) }
    var showRestoreDefaultsDialog by remember { mutableStateOf(false) }
    var showWallpaperAccessDialog by remember { mutableStateOf(false) }
    var pendingWallpaperAction by remember { mutableStateOf<MurongBackgroundMode?>(null) }
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
        ui.updateBackgroundMode(MurongBackgroundMode.CUSTOM_IMAGE)
        MurongTransientMessageBus.show("已应用自定义背景图")
    }
    val permissionRequester = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || MurongWallpaperAccess.hasAccess(context)) {
            pendingWallpaperAction?.let { mode ->
                if (mode == MurongBackgroundMode.CUSTOM_IMAGE) {
                    backgroundPicker.launch(arrayOf("image/*"))
                } else {
                    ui.updateBackgroundMode(mode)
                }
            }
        } else {
            MurongTransientMessageBus.show("未授予背景图访问权限")
        }
        pendingWallpaperAction = null
    }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (MurongWallpaperAccess.hasAccess(context)) {
            pendingWallpaperAction?.let { mode ->
                if (mode == MurongBackgroundMode.CUSTOM_IMAGE) {
                    backgroundPicker.launch(arrayOf("image/*"))
                } else {
                    ui.updateBackgroundMode(mode)
                }
            }
        } else {
            MurongTransientMessageBus.show("未授予所有文件访问权限")
        }
        pendingWallpaperAction = null
    }
    val runWallpaperAction: (MurongBackgroundMode) -> Unit = { mode ->
        pendingWallpaperAction = mode
        if (MurongWallpaperAccess.hasAccess(context)) {
            if (mode == MurongBackgroundMode.CUSTOM_IMAGE) {
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
        MurongThemeStyle.CLASSIC -> "经典纯色"
        MurongThemeStyle.GLASS -> "现代玻璃"
    }
    val modeLabel = when (ui.themeMode) {
        MurongThemeMode.SYSTEM -> "跟随系统"
        MurongThemeMode.LIGHT -> "浅色模式"
        MurongThemeMode.DARK -> "深色模式"
    }
    val backgroundModeLabel = backgroundModeLabel(ui.backgroundMode)
    val colorDialogTitle = when (colorDialogMode) {
        "theme" -> "自定义主题色"
        "background" -> "自定义背景色"
        "surface" -> "自定义卡片色"
        "chrome" -> "自定义顶栏色"
        "muted" -> "自定义次级文字色"
        "terminal_icon" -> "终端图标色"
        "terminal_path" -> "终端路径色"
        "terminal_error" -> "终端错误色"
        else -> ""
    }
    val colorDialogInitialColor = when (colorDialogMode) {
        "theme" -> ui.accentColor
        "background" -> ui.resolvedBackgroundColor(darkMode)
        "surface" -> ui.resolvedSurfaceColor(darkMode)
        "chrome" -> ui.resolvedChromeColor(darkMode)
        "muted" -> ui.resolvedMutedTextColor(darkMode)
        "terminal_icon" -> parseMurongColor(ui.terminalIconColorHex, Color(0xFF22C55E))
        "terminal_path" -> parseMurongColor(ui.terminalPathColorHex, Color(0xFF86EFAC))
        "terminal_error" -> parseMurongColor(ui.terminalErrorColorHex, Color(0xFFEF4444))
        else -> Color.Transparent
    }

    MurongSecondaryPageSurface(
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
            MurongSecondaryPageFrame(
                title = "主题界面",
                subtitle = "继续往 murong 那套成熟主题靠齐，保留现有预设色，并补齐表面层、顶栏层和文字层级颜色。"
            ) {
                MurongInfoCard(title = "", titleVisible = false) {
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
                        MurongTagButton(
                            text = ui.accentPreset.label,
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        )
                        MurongOutlinedActionButton(
                            text = "切换风格",
                            onClick = { showStyleDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    MurongOutlinedActionButton(
                        text = "还原默认",
                        onClick = { showRestoreDefaultsDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                MurongSectionCard(title = "风格与模式") {
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
                        text = if (darkMode) {
                            "深色模式已改成更明确的三层结构：更深的背景、更稳的卡片层和更克制的顶栏层，避免整页发灰发闷。"
                        } else {
                            "现代玻璃会启用悬浮底栏、长按后滑动切页、模糊卡片和更柔和的背景层；经典纯色更接近传统 Material。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                MurongSectionCard(title = "背景") {
                    ThemeValueRow(
                        title = "界面背景",
                        value = backgroundModeLabel,
                        onClick = { showBackgroundDialog = true }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ThemeValueRow(
                        title = if (ui.backgroundMode == MurongBackgroundMode.GRADIENT) {
                            "渐变基础色"
                        } else {
                            "背景颜色"
                        },
                        value = ui.resolvedBackgroundColorHex(darkMode),
                        onClick = { colorDialogMode = "background" }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MurongOutlinedActionButton(
                            text = "选择背景图",
                            onClick = { runWallpaperAction(MurongBackgroundMode.CUSTOM_IMAGE) },
                            modifier = Modifier.weight(1f)
                        )
                        MurongOutlinedActionButton(
                            text = "桌面壁纸",
                            onClick = { runWallpaperAction(MurongBackgroundMode.WALLPAPER) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (ui.customBackgroundUri.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        MurongOutlinedActionButton(
                            text = "清除自定义背景",
                            onClick = { ui.clearCustomBackgroundUri() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                MurongSectionCard(title = "颜色") {
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
                                MurongGlassSurface(
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
                        value = ui.resolvedSurfaceColorHex(darkMode),
                        onClick = { colorDialogMode = "surface" }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ThemeValueRow(
                        title = "顶栏色",
                        value = ui.resolvedChromeColorHex(darkMode),
                        onClick = { colorDialogMode = "chrome" }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ThemeValueRow(
                        title = "次级文字色",
                        value = ui.resolvedMutedTextColorHex(darkMode),
                        onClick = { colorDialogMode = "muted" }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "终端颜色",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ThemeValueRow(
                        title = "终端图标色",
                        value = ui.terminalIconColorHex,
                        onClick = { colorDialogMode = "terminal_icon" }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ThemeValueRow(
                        title = "终端路径色",
                        value = ui.terminalPathColorHex,
                        onClick = { colorDialogMode = "terminal_path" }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ThemeValueRow(
                        title = "终端错误色",
                        value = ui.terminalErrorColorHex,
                        onClick = { colorDialogMode = "terminal_error" }
                    )
                }

                MurongSectionCard(title = "显示") {
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

                MurongSectionCard(title = "模糊与质感") {
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

                MurongSectionCard(title = "实时预览") {
                    Text(
                        text = "这里直接看顶部栏、卡片、按钮和文字层级，避免切出去后才知道这套颜色压不压得住。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    MurongGlassSurface(
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
                            MurongTagButton(text = "状态标签", onClick = {})
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    MurongGlassSurface(
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
                        MurongOutlinedActionButton(
                            text = "主操作",
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        )
                        MurongTagButton(
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
                            color = ui.resolvedBackgroundColor(darkMode),
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
                    MurongGlassSurface(
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
                "跟随系统" to MurongThemeMode.SYSTEM,
                "浅色模式" to MurongThemeMode.LIGHT,
                "深色模式" to MurongThemeMode.DARK
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
        SettingsPopupDialog(
            title = "需要背景访问权限",
            onDismissRequest = {
                showWallpaperAccessDialog = false
                pendingWallpaperAction = null
            },
            actions = {
                TextButton(
                    onClick = {
                        showWallpaperAccessDialog = false
                        pendingWallpaperAction = null
                    }
                ) {
                    Text("取消")
                }
                TextButton(
                    onClick = {
                        showWallpaperAccessDialog = false
                        when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                                allFilesAccessLauncher.launch(
                                    MurongWallpaperAccess.allFilesAccessIntent(context)
                                )
                            }
                            activity != null -> {
                                MurongWallpaperAccess.permissionToRequest()?.let { permission ->
                                    permissionRequester.launch(permission)
                                } ?: run { pendingWallpaperAction = null }
                            }
                            else -> {
                                pendingWallpaperAction = null
                                MurongTransientMessageBus.show("当前页面无法发起权限请求")
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
            }
        ) {
            Text(
                text = MurongWallpaperAccess.explanation(),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    if (showRestoreDefaultsDialog) {
        SettingsPopupDialog(
            title = "还原默认主题",
            onDismissRequest = { showRestoreDefaultsDialog = false },
            actions = {
                TextButton(onClick = { showRestoreDefaultsDialog = false }) {
                    Text("取消")
                }
                TextButton(
                    onClick = {
                        ui.restoreThemeDefaults()
                        fontScaleDraft = ui.fontScale
                        uiScaleDraft = ui.uiScale
                        blurRadiusDraft = ui.backgroundBlurRadius.toFloat()
                        showRestoreDefaultsDialog = false
                        MurongTransientMessageBus.show("已还原默认主题")
                    }
                ) {
                    Text("确认")
                }
            }
        ) {
            Text(
                text = "这会恢复默认主题模式、风格、背景、颜色、模糊和显示缩放。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    if (showStyleDialog) {
        ThemeChoiceDialog(
            title = "UI 风格",
            options = listOf(
                "现代玻璃" to MurongThemeStyle.GLASS,
                "经典纯色" to MurongThemeStyle.CLASSIC
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
                "流光渐变" to MurongBackgroundMode.GRADIENT,
                "纯色背景" to MurongBackgroundMode.SOLID,
                "桌面壁纸" to MurongBackgroundMode.WALLPAPER,
                "自定义图片" to MurongBackgroundMode.CUSTOM_IMAGE
            ),
            currentValue = ui.backgroundMode,
            onDismiss = { showBackgroundDialog = false },
            onSelect = {
                showBackgroundDialog = false
                if (it == MurongBackgroundMode.WALLPAPER || it == MurongBackgroundMode.CUSTOM_IMAGE) {
                    runWallpaperAction(it)
                } else {
                    ui.updateBackgroundMode(it)
                }
            }
        )
    }

    colorDialogMode?.let { dialogMode ->
        ThemeColorSliderDialog(
            title = colorDialogTitle,
            initialColor = colorDialogInitialColor,
            onDismiss = { colorDialogMode = null },
            onConfirm = { color ->
                val hex = themeColorToHex(color)
                when (dialogMode) {
                    "theme" -> ui.updateThemeColorHex(hex)
                    "background" -> {
                        ui.updateBackgroundColorHex(hex)
                        if (ui.backgroundMode == MurongBackgroundMode.WALLPAPER ||
                            ui.backgroundMode == MurongBackgroundMode.CUSTOM_IMAGE
                        ) {
                            ui.updateBackgroundMode(MurongBackgroundMode.SOLID)
                        }
                    }
                    "surface" -> ui.updateSurfaceColorHex(hex)
                    "chrome" -> ui.updateChromeColorHex(hex)
                    "muted" -> ui.updateMutedTextColorHex(hex)
                    "terminal_icon" -> ui.updateTerminalIconColorHex(hex)
                    "terminal_path" -> ui.updateTerminalPathColorHex(hex)
                    "terminal_error" -> ui.updateTerminalErrorColorHex(hex)
                }
                colorDialogMode = null
            }
        )
    }
}

private fun backgroundModeLabel(mode: MurongBackgroundMode): String {
    return when (mode) {
        MurongBackgroundMode.GRADIENT -> "流光渐变"
        MurongBackgroundMode.SOLID -> "纯色背景"
        MurongBackgroundMode.WALLPAPER -> "桌面壁纸"
        MurongBackgroundMode.CUSTOM_IMAGE -> "自定义图片"
    }
}

@Composable
fun AboutPage(
    settingsVm: SettingsViewModel = hiltViewModel(),
    onDownloadAppUpdate: ((AppUpdateUiState) -> Unit)? = null,
    onDownloadExtensionUpdate: ((AppUpdateUiState) -> Unit)? = null
) {
    val context = LocalContext.current
    val accent = rememberMurongAccentColor()
    val uriHandler = LocalUriHandler.current
    val settingsConfig by settingsVm.config.collectAsState()
    val appUpdateState by settingsVm.appUpdateState.collectAsState()
    val extensionUpdateState by settingsVm.extensionUpdateState.collectAsState()
    val packageInfo = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
    }
    val extensionPackageInfo = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(MURONG_EXTENSION_PACKAGE_NAME, 0)
        }.getOrNull()
    }
    val versionName = packageInfo?.versionName ?: "0.9.0-preview"
    val versionCode = remember(packageInfo) {
        packageInfo?.let(::resolvePackageVersionCode) ?: 0
    }
    val extensionVersionName = extensionPackageInfo?.versionName ?: "未安装"
    val extensionVersionCode = remember(extensionPackageInfo) {
        extensionPackageInfo?.let(::resolvePackageVersionCode)
    }
    val isAppVersionSkipped = settingsConfig.isAppUpdateSkipped(appUpdateState.latestVersionCode)
    val isExtensionVersionIgnored = settingsConfig.isExtensionUpdateIgnored(extensionUpdateState.latestVersionCode)
    LaunchedEffect(versionCode, versionName, extensionVersionCode, extensionVersionName) {
        if (versionCode > 0) {
            settingsVm.checkAllUpdates(
                appVersionCode = versionCode,
                appVersionName = versionName,
                extensionVersionCode = extensionVersionCode,
                extensionVersionName = extensionVersionName.takeIf { extensionPackageInfo != null }
            )
        }
    }
    fun openSupportLink(primaryUrl: String, fallbackUrl: String? = null, errorMessage: String) {
        val opened = runCatching {
            uriHandler.openUri(primaryUrl)
        }.isSuccess
        if (opened) return
        val fallbackOpened = fallbackUrl?.let { target ->
            runCatching {
                uriHandler.openUri(target)
            }.isSuccess
        } ?: false
        if (!fallbackOpened) {
            MurongTransientMessageBus.show(errorMessage)
        }
    }

    MurongSecondaryPageSurface(
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
            MurongSecondaryPageFrame(
                title = "关于",
                subtitle = "Murong Agent 是面向移动端的多模型 AI Agent，强调代码与项目协作、结构化工具输出和更完整的工作流。"
            ) {
                MurongInfoCard(title = "", titleVisible = false) {
                    Text(
                        text = "Murong Agent",
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
                    MurongGlassSurface(
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
                        if (versionCode > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "versionCode $versionCode",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    MurongGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
                    ) {
                        Text(
                            text = "检查更新",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when {
                                appUpdateState.isChecking -> "正在检查远端版本..."
                                !appUpdateState.latestVersionName.isNullOrBlank() ->
                                    "最新版本 ${appUpdateState.latestVersionName}"
                                appUpdateState.latestVersionCode != null ->
                                    "最新版本 code ${appUpdateState.latestVersionCode}"
                                else -> "暂未获取到远端版本"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        val updateStatusText = appUpdateState.error
                            ?: appUpdateState.updateMessage
                            ?: appUpdateState.message
                            ?: "通过 MurongAgent 后端检查当前 stable 通道版本。"
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = updateStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (appUpdateState.error != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (appUpdateState.forceUpdate && appUpdateState.isInstallOrUpdateAvailable) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "该版本为强制更新版本，不能跳过提醒。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (isAppVersionSkipped && appUpdateState.isInstallOrUpdateAvailable) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "当前版本已被跳过，后续自动弹窗不会再提醒这一版。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        appUpdateState.publishedAt?.takeIf { it.isNotBlank() }?.let { publishedAt ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "发布时间：$publishedAt",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            MurongOutlinedActionButton(
                                text = if (appUpdateState.isChecking) "检查中..." else "重新检查",
                                onClick = {
                                    if (versionCode > 0) {
                                        settingsVm.checkAllUpdates(
                                            appVersionCode = versionCode,
                                            appVersionName = versionName,
                                            extensionVersionCode = extensionVersionCode,
                                            extensionVersionName = extensionVersionName.takeIf { extensionPackageInfo != null }
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !appUpdateState.isChecking && versionCode > 0
                            )
                            MurongOutlinedActionButton(
                                text = if (appUpdateState.isUpdateAvailable) "下载新版本" else "打开下载页",
                                onClick = {
                                    if (appUpdateState.isInstallOrUpdateAvailable) {
                                        onDownloadAppUpdate?.invoke(appUpdateState) ?: uriHandler.openUri(
                                            appUpdateState.preferredDownloadUrl
                                                ?: settingsConfig.getMurongDownloadsPageUrl()
                                        )
                                    } else {
                                        uriHandler.openUri(
                                            appUpdateState.downloadUrl
                                                ?: settingsConfig.getMurongDownloadsPageUrl()
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (appUpdateState.isInstallOrUpdateAvailable &&
                            appUpdateState.latestVersionCode != null &&
                            !appUpdateState.forceUpdate
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            MurongOutlinedActionButton(
                                text = if (isAppVersionSkipped) "恢复提醒" else "跳过此版本",
                                onClick = {
                                    if (isAppVersionSkipped) {
                                        settingsVm.clearSkippedAppUpdateVersion()
                                    } else {
                                        settingsVm.skipAppUpdateVersion(appUpdateState.latestVersionCode)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    MurongGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
                    ) {
                        Text(
                            text = "终端扩展包",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when {
                                extensionUpdateState.isChecking -> "正在检查扩展包版本..."
                                extensionPackageInfo == null &&
                                    extensionUpdateState.isInstallOrUpdateAvailable -> {
                                    "检测到可安装扩展包"
                                }
                                extensionPackageInfo == null -> "当前未安装扩展包"
                                !extensionUpdateState.latestVersionName.isNullOrBlank() ->
                                    "最新版本 ${extensionUpdateState.latestVersionName}"
                                extensionUpdateState.latestVersionCode != null ->
                                    "最新版本 code ${extensionUpdateState.latestVersionCode}"
                                else -> "暂未获取到扩展包远端版本"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = buildString {
                                append("当前状态：")
                                append(extensionVersionName)
                                extensionVersionCode?.let { append(" (code $it)") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val extensionStatusText = extensionUpdateState.error
                            ?: extensionUpdateState.updateMessage
                            ?: extensionUpdateState.message
                            ?: "通过 MurongAgent 后端检查扩展包版本。"
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = extensionStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (extensionUpdateState.error != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (isExtensionVersionIgnored && extensionUpdateState.isInstallOrUpdateAvailable) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "当前扩展包版本已忽略，自动弹窗不会再提醒这一版。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        extensionUpdateState.publishedAt?.takeIf { it.isNotBlank() }?.let { publishedAt ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "发布时间：$publishedAt",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            MurongOutlinedActionButton(
                                text = if (extensionUpdateState.isChecking) "检查中..." else "重新检查",
                                onClick = {
                                    settingsVm.checkExtensionUpdate(
                                        currentVersionCode = extensionVersionCode,
                                        currentVersionName = extensionVersionName.takeIf { extensionPackageInfo != null }
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !extensionUpdateState.isChecking
                            )
                            MurongOutlinedActionButton(
                                text = if (extensionUpdateState.isInstallOrUpdateAvailable) "下载扩展包" else "打开下载页",
                                onClick = {
                                    if (extensionUpdateState.isInstallOrUpdateAvailable) {
                                        onDownloadExtensionUpdate?.invoke(extensionUpdateState) ?: uriHandler.openUri(
                                            extensionUpdateState.preferredDownloadUrl
                                                ?: settingsConfig.getMurongDownloadsPageUrl()
                                        )
                                    } else {
                                        uriHandler.openUri(
                                            extensionUpdateState.downloadUrl
                                                ?: settingsConfig.getMurongDownloadsPageUrl()
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (extensionUpdateState.isInstallOrUpdateAvailable &&
                            extensionUpdateState.latestVersionCode != null
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            MurongOutlinedActionButton(
                                text = if (isExtensionVersionIgnored) "取消忽略" else "忽略此版本",
                                onClick = {
                                    if (isExtensionVersionIgnored) {
                                        settingsVm.clearIgnoredExtensionUpdateVersion()
                                    } else {
                                        settingsVm.ignoreExtensionUpdateVersion(extensionUpdateState.latestVersionCode)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                MurongSectionCard(title = "应用信息") {
                    AboutInfoRow("应用", "Murong Agent")
                    AboutInfoRow("版本", versionName)
                    if (versionCode > 0) {
                        AboutInfoRow("Version Code", versionCode.toString())
                    }
                    AboutInfoRow(
                        "更新通道",
                        if (appUpdateState.isUpdateAvailable) "stable · 有新版本" else "stable"
                    )
                    AboutInfoRow(
                        "扩展包",
                        when {
                            extensionPackageInfo == null &&
                                extensionUpdateState.isInstallOrUpdateAvailable -> "未安装 · 可下载"
                            extensionPackageInfo == null -> "未安装"
                            extensionUpdateState.isUpdateAvailable -> "已安装 · 有新版本"
                            else -> "已安装"
                        }
                    )
                    AboutInfoRow("引擎", "Murong Agent Core")
                    AboutInfoRow("设计方向", "现代玻璃 / 桌面端式信息密度")
                    AboutInfoRow("当前重点", "登录与更新闭环 / 下载页联动")
                }

                MurongSectionCard(title = "项目链接") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        MurongOutlinedActionButton(
                            text = "GitHub",
                            onClick = { uriHandler.openUri("https://github.com/murongruyan/MurongAgent") },
                            modifier = Modifier.weight(1f)
                        )
                        MurongOutlinedActionButton(
                            text = "README",
                            onClick = { uriHandler.openUri("https://github.com/murongruyan/MurongAgent/blob/main/README.md") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                MurongSectionCard(title = "交流与支持") {
                    Text(
                        text = "开发者：慕容茹艳（酷安慕容雪绒）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "需要获取动态、进群交流、看看流量卡或支持作者，都可以从这里直接打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        MurongOutlinedActionButton(
                            text = "酷安",
                            onClick = {
                                openSupportLink(
                                    primaryUrl = ABOUT_COOLAPK_URL,
                                    fallbackUrl = ABOUT_COOLAPK_WEB_URL,
                                    errorMessage = "无法打开酷安主页"
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        MurongOutlinedActionButton(
                            text = "QQ 群",
                            onClick = {
                                openSupportLink(
                                    primaryUrl = ABOUT_QQ_GROUP_URL,
                                    errorMessage = "请加入 QQ 群：974835379"
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        MurongOutlinedActionButton(
                            text = "流量卡",
                            onClick = {
                                openSupportLink(
                                    primaryUrl = ABOUT_TRAFFIC_CARD_URL,
                                    errorMessage = "无法打开流量卡页面"
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        MurongOutlinedActionButton(
                            text = "打赏作者",
                            onClick = {
                                openSupportLink(
                                    primaryUrl = ABOUT_ALIPAY_URL,
                                    errorMessage = "无法打开打赏页面"
                                )
                            },
                            modifier = Modifier.weight(1f)
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
        MurongTagButton(text = value, onClick = onClick)
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
            MurongTagButton(text = valueText, onClick = {})
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
    MurongGlassSurface(
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
private fun SettingsPopupDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    MurongDialog(onDismissRequest = onDismissRequest) {
        MurongPopupSurface(
            shape = MaterialTheme.shapes.large,
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium
                        )
                        subtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun ThemeColorSliderDialog(
    title: String,
    initialColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit
) {
    var alpha by remember(initialColor) { mutableFloatStateOf(initialColor.alpha) }
    var red by remember(initialColor) { mutableFloatStateOf(initialColor.red) }
    var green by remember(initialColor) { mutableFloatStateOf(initialColor.green) }
    var blue by remember(initialColor) { mutableFloatStateOf(initialColor.blue) }
    val currentColor = remember(alpha, red, green, blue) {
        Color(
            red = red.coerceIn(0f, 1f),
            green = green.coerceIn(0f, 1f),
            blue = blue.coerceIn(0f, 1f),
            alpha = alpha.coerceIn(0f, 1f)
        )
    }
    val previewTextColor = if (murongIsDarkColor(currentColor)) Color.White else Color.Black
    SettingsPopupDialog(
        title = title,
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
            TextButton(onClick = { onConfirm(currentColor) }) {
                Text("应用")
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(currentColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = themeColorToHex(currentColor),
                color = previewTextColor,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        ThemeColorSliderChannel(
            label = "A",
            title = "透明度",
            value = alpha,
            onValueChange = { alpha = it }
        )
        ThemeColorSliderChannel(
            label = "R",
            title = "红色",
            value = red,
            onValueChange = { red = it }
        )
        ThemeColorSliderChannel(
            label = "G",
            title = "绿色",
            value = green,
            onValueChange = { green = it }
        )
        ThemeColorSliderChannel(
            label = "B",
            title = "蓝色",
            value = blue,
            onValueChange = { blue = it }
        )
        Text(
            text = "拖动滑块直接选色，应用后会自动保存为带透明度的颜色值。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThemeColorSliderChannel(
    label: String,
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val normalized = value.coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$label ($title)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = (normalized * 255).toInt().toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = normalized,
            onValueChange = onValueChange,
            valueRange = 0f..1f
        )
    }
}

private fun themeColorToHex(color: Color): String {
    return String.format("#%08X", color.toArgb())
}

private fun resolvePackageVersionCode(packageInfo: PackageInfo): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode.toInt()
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode
    }
}

@Composable
private fun <T> ThemeChoiceDialog(
    title: String,
    options: List<Pair<String, T>>,
    currentValue: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    SettingsPopupDialog(
        title = title,
        onDismissRequest = onDismiss,
        actions = {
            MurongOutlinedActionButton(text = "关闭", onClick = onDismiss)
        }
    ) {
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
    }
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

private const val ABOUT_QQ_GROUP_URL =
    "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=974835379&card_type=group&source=qrcode"
private const val ABOUT_COOLAPK_URL = "coolmarket://u/5037694"
private const val ABOUT_COOLAPK_WEB_URL = "https://www.coolapk.com/u/5037694"
private const val ABOUT_TRAFFIC_CARD_URL = "https://h5.lot-ml.com/ProductEn/Index/beb66e222fcdb4b6"
private const val ABOUT_ALIPAY_URL = "https://qr.alipay.com/fkx19856muvtznqt6onipea"
