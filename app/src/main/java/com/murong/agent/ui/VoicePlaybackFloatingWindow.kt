package com.murong.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.murong.agent.core.voice.VoicePlaybackState

/** App-level playback controls that stay visible while the user navigates away from chat. */
@Composable
internal fun VoicePlaybackFloatingWindow(
    playbackState: VoicePlaybackState,
    activeMessageId: Long?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (
        activeMessageId == null ||
        playbackState !in setOf(
            VoicePlaybackState.PREPARING,
            VoicePlaybackState.SPEAKING,
            VoicePlaybackState.PAUSED,
        )
    ) {
        return
    }

    val title = when (playbackState) {
        VoicePlaybackState.PREPARING -> "正在准备朗读"
        VoicePlaybackState.SPEAKING -> "正在朗读"
        VoicePlaybackState.PAUSED -> "朗读已暂停"
        else -> return
    }
    MurongGlassSurface(
        modifier = modifier.widthIn(max = 420.dp),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "切换页面、滑动或点击不会中断",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            FilledTonalButton(
                onClick = if (playbackState == VoicePlaybackState.PAUSED) onResume else onPause,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            ) {
                Text(if (playbackState == VoicePlaybackState.PAUSED) "继续" else "暂停")
            }
            TextButton(
                onClick = onStop,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("结束")
            }
        }
    }
}
