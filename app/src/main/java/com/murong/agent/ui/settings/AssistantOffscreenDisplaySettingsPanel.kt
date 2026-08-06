package com.murong.agent.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.assistant.AssistantOffscreenDisplayPreferences
import com.murong.agent.ui.assistant.AssistantOffscreenDisplaySettings

@Composable
internal fun AssistantOffscreenDisplaySettingsPanel() {
    val context = LocalContext.current
    var settings by remember {
        mutableStateOf(AssistantOffscreenDisplayPreferences.read(context))
    }
    val metrics = context.resources.displayMetrics
    val supportedRefreshRates = remember(context) {
        (context as? Activity)?.display?.supportedModes
            .orEmpty()
            .map { mode -> kotlin.math.round(mode.refreshRate).toInt() }
            .filter { it in 10..240 }
            .distinct()
            .sorted()
            .ifEmpty { listOf(30, 60, 90, 120) }
    }

    fun update(transform: (AssistantOffscreenDisplaySettings) -> AssistantOffscreenDisplaySettings) {
        settings = transform(settings).normalized()
        AssistantOffscreenDisplayPreferences.write(context, settings)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "离屏预览画面",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Root 设备直接使用 Root app_process；无 Root 时才回退 Shizuku/Sui。" +
                    "只在打开离屏查看器时持续取帧，设置下次进入时生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OffscreenChoiceRow(
                label = "分辨率",
                choices = listOf(
                    0 to "原机 ${metrics.widthPixels}×${metrics.heightPixels}",
                    1_080 to "1080 长边",
                    1_600 to "1600 长边",
                    2_160 to "2160 长边",
                ),
                selected = settings.maxLongEdge,
                onSelect = { update { value -> value.copy(maxLongEdge = it) } },
            )
            OffscreenChoiceRow(
                label = "目标刷新率",
                choices = listOf(0 to "跟随原机") +
                    supportedRefreshRates.map { it to "$it FPS" },
                selected = settings.targetFps,
                onSelect = { update { value -> value.copy(targetFps = it) } },
            )
            OffscreenChoiceRow(
                label = "JPEG 画质",
                choices = listOf(
                    75 to "省流 75",
                    85 to "清晰 85",
                    92 to "高清 92",
                    100 to "无损级 100",
                ),
                selected = settings.jpegQuality,
                onSelect = { update { value -> value.copy(jpegQuality = it) } },
            )
            OffscreenChoiceRow(
                label = "目标带宽",
                choices = listOf(
                    0 to "不限",
                    8 to "8 Mbps",
                    16 to "16 Mbps",
                    32 to "32 Mbps",
                    64 to "64 Mbps",
                ),
                selected = settings.targetBitrateMbps,
                onSelect = { update { value -> value.copy(targetBitrateMbps = it) } },
            )
            Text(
                "这里的带宽是 JPEG 帧流的自适应目标。查看器会显示“实际 FPS / 目标 FPS”；" +
                    "原机分辨率可以保留，但 JPEG + IPC 链路不保证达到面板原生 90/120 Hz。" +
                    "“全屏接管”直接使用物理主屏原生渲染，不受这些预览设置影响。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OffscreenChoiceRow(
    label: String,
    choices: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        choices.forEach { (value, text) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(text) },
            )
        }
    }
}
