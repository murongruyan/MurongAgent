package com.murong.agent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.MurongPrimaryPageSurface

/** The compact settings landing page. Detail pages deliberately own the dense controls. */
enum class SettingsFocus(val title: String, val summary: String) {
    ALL("全部设置", "查看全部可配置项"),
    MODELS("模型与连接", "API、模型、账号、用量与生成参数"),
    AGENT("对话与 Agent", "系统提示词、搜索、规则和长期记忆"),
    DEVICE("设备与权限", "标准、无障碍、Shizuku、Root 与系统能力"),
    VOICE("语音助手", "声纹、唤醒、识别、离线模型、朗读与默认助手"),
    AUTOMATION("扩展与 Skills", "Skills、扩展能力与高级连接设置"),
    DATA("数据与同步", "GitHub、备份恢复与远程网页")
}

@Composable
internal fun SettingsDirectoryPage(
    onOpenProjects: () -> Unit,
    onOpenProjectConfig: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenBuiltInTools: () -> Unit,
    onOpenPhoneTools: () -> Unit,
    onOpenAutomationTools: () -> Unit,
    onOpenApprovalTools: () -> Unit,
    onOpenActivityTools: () -> Unit,
    onOpenFocus: (SettingsFocus) -> Unit,
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var query by remember { mutableStateOf("") }
    val workspaceItems = listOf(
        SettingsDirectoryNavigationItem(
            title = "项目与文件",
            summary = "文件树、编辑器、多文件标签与项目配置",
            onClick = onOpenProjects
        ),
        SettingsDirectoryNavigationItem(
            title = "项目配置",
            summary = "项目规则、记忆、Skills 与工具偏好",
            onClick = onOpenProjectConfig
        ),
        SettingsDirectoryNavigationItem(
            title = "终端",
            summary = "当前项目的命令行与运行环境",
            onClick = onOpenTerminal
        ),
        SettingsDirectoryNavigationItem(
            title = "Git 与 GitHub",
            summary = "仓库状态、提交、分支与远程工作流",
            onClick = onOpenGit
        )
    ).matchingNavigation(query)
    val toolItems = listOf(
        SettingsDirectoryNavigationItem(
            title = "内置工具",
            summary = "文件、代码、命令、搜索与子代理开关",
            onClick = onOpenBuiltInTools
        ),
        SettingsDirectoryNavigationItem(
            title = "手机操作",
            summary = "无障碍、Root、视觉识别与操作模型",
            onClick = onOpenPhoneTools
        ),
        SettingsDirectoryNavigationItem(
            title = "自动化与 MCP",
            summary = "工作流、外部触发与 MCP 连接",
            onClick = onOpenAutomationTools
        ),
        SettingsDirectoryNavigationItem(
            title = "审批策略",
            summary = "待处理请求、风险姿态与项目偏好",
            onClick = onOpenApprovalTools
        ),
        SettingsDirectoryNavigationItem(
            title = "执行记录",
            summary = "审计、错误、文件改动与检查点",
            onClick = onOpenActivityTools
        )
    ).matchingNavigation(query)
    val preferenceItems = listOf(
        SettingsFocus.MODELS,
        SettingsFocus.AGENT,
        SettingsFocus.DEVICE,
        SettingsFocus.VOICE
    ).matchingFocus(query)
    val extensionItems = listOf(
        SettingsFocus.AUTOMATION,
        SettingsFocus.DATA,
        SettingsFocus.ALL
    ).matchingFocus(query)
    val showTheme = matchesSettingsQuery(
        query,
        "主题与外观",
        "颜色、背景、字体与界面密度",
    )
    val showAbout = matchesSettingsQuery(
        query,
        "关于 Murong Agent",
        "版本、更新与开源信息",
    )
    val hasResults = workspaceItems.isNotEmpty() ||
        toolItems.isNotEmpty() ||
        preferenceItems.isNotEmpty() ||
        extensionItems.isNotEmpty() ||
        showTheme ||
        showAbout
    MurongPrimaryPageSurface(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "清除搜索")
                        }
                    }
                } else {
                    null
                },
                placeholder = { Text("搜索设置，例如：声纹、模型、Root、Git") },
            )
            if (workspaceItems.isNotEmpty()) {
                SettingsDirectoryNavigationGroup("工作区", workspaceItems)
            }
            if (toolItems.isNotEmpty()) {
                SettingsDirectoryNavigationGroup("工具与自动化", toolItems)
            }
            if (preferenceItems.isNotEmpty()) {
                SettingsDirectoryGroup("偏好设置", preferenceItems, onOpenFocus)
            }
            if (extensionItems.isNotEmpty()) {
                SettingsDirectoryGroup("扩展与数据", extensionItems, onOpenFocus)
            }
            if (showTheme) {
                SettingsDirectoryAction(
                    "主题与外观",
                    "颜色、背景、字体与界面密度",
                    onOpenTheme,
                )
            }
            if (showAbout) {
                SettingsDirectoryAction(
                    "关于 Murong Agent",
                    "版本、更新与开源信息",
                    onOpenAbout,
                )
            }
            if (!hasResults) {
                Text(
                    "没有找到“${query.trim()}”相关设置",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 18.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsDirectoryGroup(
    title: String,
    items: List<SettingsFocus>,
    onOpenFocus: (SettingsFocus) -> Unit,
) {
    SettingsDirectoryNavigationGroup(
        title = title,
        items = items.map { item ->
            SettingsDirectoryNavigationItem(
                title = item.title,
                summary = item.summary,
                onClick = { onOpenFocus(item) }
            )
        }
    )
}

private data class SettingsDirectoryNavigationItem(
    val title: String,
    val summary: String,
    val onClick: () -> Unit,
)

private fun List<SettingsDirectoryNavigationItem>.matchingNavigation(
    query: String,
): List<SettingsDirectoryNavigationItem> =
    filter { item -> matchesSettingsQuery(query, item.title, item.summary) }

private fun List<SettingsFocus>.matchingFocus(query: String): List<SettingsFocus> =
    filter { item -> matchesSettingsQuery(query, item.title, item.summary) }

private fun matchesSettingsQuery(query: String, vararg fields: String): Boolean {
    val normalized = query.trim().lowercase()
    return normalized.isBlank() || fields.any { field -> normalized in field.lowercase() }
}

@Composable
private fun SettingsDirectoryNavigationGroup(
    title: String,
    items: List<SettingsDirectoryNavigationItem>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    SettingsDirectoryRow(
                        title = item.title,
                        summary = item.summary,
                        onClick = item.onClick
                    )
                    if (index != items.lastIndex) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        ) {
                            Spacer(modifier = Modifier.fillMaxWidth().height(1.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDirectoryAction(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        SettingsDirectoryRow(title, summary, onClick)
    }
}

@Composable
private fun SettingsDirectoryRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            "›",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
