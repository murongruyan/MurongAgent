package com.murong.agent.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.murong.agent.backup.MURONG_BACKUP_EXTENSION
import com.murong.agent.backup.MurongBackupSettingsSnapshot
import com.murong.agent.ui.MurongDialog
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BackupRestoreSettingsSection(
    state: BackupRestoreUiState,
    suggestedFileName: String,
    onRefresh: () -> Unit,
    onSettingsChanged: (MurongBackupSettingsSnapshot) -> Unit,
    onExport: (Uri) -> Unit,
    onRestore: (Uri) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(onExport) }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingRestoreUri = uri
    }
    LaunchedEffect(expanded) {
        if (expanded) onRefresh()
    }

    SettingsExpandableSectionCard(
        title = "完整备份与恢复",
        subtitle = "会话、记忆、Skill、MCP、工作流与普通设置",
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        Text(
            "v2 备份包含原生完整状态和跨平台可移植状态，可在 Android、Windows、macOS 与 Linux 之间互导。",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "同系统恢复会精确替换完整状态；其他系统的备份只合并会话、模型配置、通用设置、规则/记忆/Skill、无凭据 MCP 与无路径工作流，并保留本机路径和设备专用内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "默认排除 API Key、GitHub/Codex 登录、MCP 鉴权值、语音模型、终端扩展与工具链、Shell 历史和缓存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("每日自动备份", style = MaterialTheme.typography.bodyLarge)
                    Text("每天凌晨约 03:00，低存储时不执行", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.status.settings.dailyBackupEnabled,
                    onCheckedChange = { enabled ->
                        onSettingsChanged(state.status.settings.copy(dailyBackupEnabled = enabled))
                    },
                    enabled = !state.isBusy
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("保留数量", style = MaterialTheme.typography.bodyLarge)
                    Text("自动备份和恢复前快照分别保留", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = {
                        onSettingsChanged(
                            state.status.settings.copy(
                                maxBackupCount = (state.status.settings.maxBackupCount - 1).coerceAtLeast(1)
                            )
                        )
                    },
                    enabled = !state.isBusy && state.status.settings.maxBackupCount > 1
                ) { Text("−") }
                Text(
                    state.status.settings.maxBackupCount.toString(),
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedButton(
                    onClick = {
                        onSettingsChanged(
                            state.status.settings.copy(
                                maxBackupCount = (state.status.settings.maxBackupCount + 1).coerceAtMost(100)
                            )
                        )
                    },
                    enabled = !state.isBusy && state.status.settings.maxBackupCount < 100
                ) { Text("+") }
            }
            Text(
                "已有自动备份 ${state.status.automaticBackupCount} 个 · 恢复前快照 ${state.status.preRestoreSnapshotCount} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (state.status.usesDurablePublicStorage) {
                    "自动备份位置：${state.status.storageLocation}（卸载应用后仍保留）"
                } else {
                    "自动备份位置：应用内部存储。授权“全部文件访问”后将改存到 Download/Murong/backups。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.status.usesDurablePublicStorage) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            state.status.lastBackupAt?.let { timestamp ->
                Text(
                    "最近结果：${DateFormat.getDateTimeInstance().format(Date(timestamp))} · ${state.status.lastBackupMessage.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.status.lastBackupFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { createLauncher.launch(suggestedFileName) },
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f)
                ) { Text("手动备份") }
                OutlinedButton(
                    onClick = { openLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f)
                ) { Text("选择备份恢复") }
            }
            if (state.isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(state.message ?: "处理中…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                state.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                }
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (state.restartRequired) {
                Text(
                    "恢复已经写入磁盘。请完全退出并重新打开 Murong，以重新加载会话和界面缓存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
    }

    pendingRestoreUri?.let { uri ->
        MurongDialog(onDismissRequest = { pendingRestoreUri = null }) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("恢复完整备份？", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Murong 会先校验版本、路径、大小和所有哈希，再自动创建恢复前快照。同系统备份会精确恢复；桌面系统备份会安全合并跨平台内容，不覆盖本机路径、API Key、登录状态或设备运行态。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "文件类型：.$MURONG_BACKUP_EXTENSION。校验失败时不会开始替换；替换中失败会自动回滚。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { pendingRestoreUri = null }, modifier = Modifier.weight(1f)) {
                            Text("取消")
                        }
                        Button(
                            onClick = {
                                pendingRestoreUri = null
                                onRestore(uri)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("校验并恢复") }
                    }
                }
            }
        }
    }
}
