package dev.reasonix.mobile.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.LocalReasonixUiController
import dev.reasonix.mobile.ui.ReasonixInfoCard
import dev.reasonix.mobile.ui.ReasonixOutlinedActionButton
import dev.reasonix.mobile.ui.ReasonixSectionCard
import dev.reasonix.mobile.ui.ReasonixSecondaryPageFrame
import dev.reasonix.mobile.ui.ReasonixSecondaryPageSurface
import dev.reasonix.mobile.ui.ReasonixTagButton
import dev.reasonix.mobile.ui.ReasonixGlassSurface
import dev.reasonix.mobile.ui.ReasonixThemeMode
import dev.reasonix.mobile.ui.ReasonixThemeStyle
import dev.reasonix.mobile.ui.rememberReasonixAccentColor

@Composable
fun ThemeSettingsPage() {
    val ui = LocalReasonixUiController.current
    val accent = rememberReasonixAccentColor()
    var showModeDialog by remember { mutableStateOf(false) }
    var showStyleDialog by remember { mutableStateOf(false) }
    val styleLabel = when (ui.themeStyle) {
        ReasonixThemeStyle.CLASSIC -> "经典纯色"
        ReasonixThemeStyle.GLASS -> "现代玻璃"
        else -> "现代玻璃"
    }
    val modeLabel = when (ui.themeMode) {
        ReasonixThemeMode.SYSTEM -> "跟随系统"
        ReasonixThemeMode.LIGHT -> "浅色模式"
        ReasonixThemeMode.DARK -> "深色模式"
        else -> "跟随系统"
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
                subtitle = "参考 murong 的主题页结构，统一收口风格、模式和强调色，并给出更接近最终效果的实时预览。"
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
                        text = "现代玻璃会启用悬浮底栏、模糊卡片和更柔和的背景流光；经典纯色更接近传统 Material。这里的层级和交互会继续往参考项目靠拢。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ReasonixSectionCard(title = "强调色") {
                    Text(
                        text = "底栏高亮、操作按钮、聊天气泡边框和重要标签都会跟随这里的颜色，风格上和参考项目一样把 accent 当作全局语言。",
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
                }

                ReasonixSectionCard(title = "实时预览") {
                    Text(
                        text = "这里直接预览按钮、标签和玻璃卡片的组合，避免切出去后才看到效果。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ReasonixGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
                    ) {
                        Text(
                            text = "玻璃卡片预览",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "底栏、对话气泡和统一弹窗都会继续跟这一套风格对齐。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
