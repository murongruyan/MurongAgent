package com.murong.agent.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.murong.agent.core.voice.VoiceRecognitionProvider
import com.murong.agent.core.voice.VoiceSettings
import com.murong.agent.voice.OfflineVoiceModelDescriptor
import com.murong.agent.voice.OfflineVoiceModelInstallStatus
import com.murong.agent.voice.OfflineVoiceModelUiState

@Composable
fun VoiceRecognitionProviderSetting(
    settings: VoiceSettings,
    onUpdateSettings: ((VoiceSettings) -> VoiceSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("语音识别方式", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recognitionProviderOptions().forEach { (provider, label) ->
                FilterChip(
                    selected = settings.recognitionProvider == provider,
                    onClick = { onUpdateSettings { it.copy(recognitionProvider = provider) } },
                    label = { Text(label) },
                )
            }
        }
        Text(
            when (settings.recognitionProvider) {
                VoiceRecognitionProvider.AUTOMATIC -> "优先使用系统识别；没有系统服务时使用已安装的离线模型。"
                VoiceRecognitionProvider.SYSTEM -> "只使用手机系统或用户安装的语音识别服务。"
                VoiceRecognitionProvider.OFFLINE -> "只使用 Murong 内置的本地识别运行时和你主动下载的模型。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun OfflineVoiceModelSetting(
    state: OfflineVoiceModelUiState,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("实时离线中英模型", style = MaterialTheme.typography.labelLarge)
        Text(
            offlineVoiceModelStatusText(state),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.status == OfflineVoiceModelInstallStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.isBusy) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            "来源：Sherpa-onnx 官方发布包 · 实时识别版本 ${OfflineVoiceModelDescriptor.VERSION} · 下载约 128 MB · 解包约 162 MB · 160 ms 低延迟流式增量识别 · 模型原生输出中英文标点和英文大小写 · 安装至少需 500 MB 可用空间 · 许可：Apache-2.0。仅在你点击安装后下载，存放在应用私有目录；原始录音不会保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "SHA-256：${OfflineVoiceModelDescriptor.ARCHIVE_SHA256}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "运行时随 APK 内置；模型文件按需下载，因此不会把安装包从十几 MB 放大到一百多 MB。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!state.isInstalled) {
                Button(onClick = onInstall, enabled = !state.isBusy) {
                    Text(
                        when (state.status) {
                            OfflineVoiceModelInstallStatus.FAILED -> "重新下载"
                            OfflineVoiceModelInstallStatus.LEGACY_READY -> "升级实时模型"
                            else -> "安装实时模型"
                        }
                    )
                }
            } else {
                OutlinedButton(onClick = onDelete, enabled = !state.isBusy) { Text("删除模型") }
            }
        }
    }
}

private fun recognitionProviderOptions(): List<Pair<VoiceRecognitionProvider, String>> = listOf(
    VoiceRecognitionProvider.AUTOMATIC to "自动",
    VoiceRecognitionProvider.SYSTEM to "系统服务",
    VoiceRecognitionProvider.OFFLINE to "离线模型",
)

private fun offlineVoiceModelStatusText(state: OfflineVoiceModelUiState): String = when (state.status) {
    OfflineVoiceModelInstallStatus.NOT_INSTALLED -> "未安装"
    OfflineVoiceModelInstallStatus.DOWNLOADING -> "正在下载${state.message?.let { " $it" }.orEmpty()}：${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}"
    OfflineVoiceModelInstallStatus.VERIFYING -> "正在校验${state.message?.let { " $it" }.orEmpty()} SHA-256…"
    OfflineVoiceModelInstallStatus.EXTRACTING -> "正在安全解包并启用${state.message?.let { " $it" }.orEmpty()}…"
    OfflineVoiceModelInstallStatus.READY -> "已安装，可实时显示中英文识别结果，并由模型原生输出标点"
    OfflineVoiceModelInstallStatus.LEGACY_READY -> "旧版模型仍可用；升级后由实时模型原生输出中英文标点"
    OfflineVoiceModelInstallStatus.FAILED -> state.message ?: "安装失败，请重新下载"
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L -> "%.1f MB".format(value / (1024f * 1024f))
    value >= 1024L -> "%.1f KB".format(value / 1024f)
    else -> "$value B"
}
