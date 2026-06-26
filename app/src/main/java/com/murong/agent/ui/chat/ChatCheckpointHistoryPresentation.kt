package com.murong.agent.ui.chat

import com.murong.agent.core.loop.CheckpointRecoveryRecordUi
import com.murong.agent.core.loop.ConversationCheckpointUi
import com.murong.agent.core.loop.FileChangeRecordUi
import com.murong.agent.ui.tools.formatCheckpointPresentationKindLabel
import com.murong.agent.ui.tools.formatCheckpointPresentationPreview
import com.murong.agent.ui.tools.formatCheckpointPresentationTitle
import com.murong.agent.ui.tools.formatCheckpointRecoveryPresentationSummary
import com.murong.agent.ui.tools.formatCheckpointRecoveryActionLabel
import com.murong.agent.ui.tools.formatCheckpointRollbackActionLabel
import com.murong.agent.ui.tools.buildCheckpointRecoveryDetailContent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ChatCheckpointActivityHintPresentation(
    val title: String,
    val message: String
)

internal data class ChatCheckpointHistoryPresentation(
    val title: String,
    val emptyState: String,
    val checkpoints: List<ChatCheckpointBatchPresentation>,
    val recoveries: List<ChatCheckpointRecoveryPresentation>
)

internal data class ChatCheckpointBatchPresentation(
    val id: String,
    val title: String,
    val subtitle: String,
    val changedFilesPreview: String
)

internal data class ChatCheckpointRecoveryPresentation(
    val id: String,
    val title: String,
    val subtitle: String,
    val detailTitle: String,
    val detailSubtitle: String,
    val detailContent: String
)

internal fun buildChatCheckpointActivityHintPresentation(
    checkpoints: List<ConversationCheckpointUi>,
    fileChanges: List<FileChangeRecordUi>,
    recentRecoveryRecords: List<CheckpointRecoveryRecordUi>
): ChatCheckpointActivityHintPresentation? {
    if (checkpoints.isEmpty() && recentRecoveryRecords.isEmpty()) return null
    val checkpointsById = checkpoints.associateBy { it.id }
    val latestCheckpoint = checkpoints.firstOrNull()
    val latestRecovery = recentRecoveryRecords.firstOrNull()
    val latestSummary = when {
        latestRecovery != null &&
            (latestCheckpoint == null || latestRecovery.timestamp >= latestCheckpoint.createdAt) ->
            "${formatCheckpointRecoveryActionLabel(latestRecovery.scope)} · " +
                "${formatCheckpointRecoveryPresentationSummary(latestRecovery, checkpointsById[latestRecovery.checkpointId])} · " +
                formatChatCheckpointPresentationTime(latestRecovery.timestamp)

        latestCheckpoint != null ->
            buildString {
                append(formatCheckpointPresentationTitle(latestCheckpoint))
                formatCheckpointPresentationPreview(latestCheckpoint)?.let { preview ->
                    append(" · ")
                    append(preview)
                }
                append(" · ")
                append(formatChatCheckpointPresentationTime(latestCheckpoint.createdAt))
            }

        else -> "最近有新的恢复记录"
    }
    return ChatCheckpointActivityHintPresentation(
        title = "修改/恢复活动 · ${checkpoints.size} 批 / ${fileChanges.size} 文件 / ${recentRecoveryRecords.size} 次恢复",
        message = buildString {
            append(latestSummary)
            append("\n点击查看修改批次和最近恢复记录。")
        }
    )
}

internal fun buildChatCheckpointHistoryPresentation(
    checkpoints: List<ConversationCheckpointUi>,
    fileChanges: List<FileChangeRecordUi>,
    recentRecoveryRecords: List<CheckpointRecoveryRecordUi>
): ChatCheckpointHistoryPresentation {
    val checkpointsById = checkpoints.associateBy { it.id }
    val recordsByCheckpointId = fileChanges
        .filter { !it.checkpointId.isNullOrBlank() }
        .groupBy { it.checkpointId.orEmpty() }
    val checkpointPresentations = checkpoints.map { checkpoint ->
        val batchRecords = recordsByCheckpointId[checkpoint.id].orEmpty()
        ChatCheckpointBatchPresentation(
            id = checkpoint.id,
            title = formatCheckpointPresentationTitle(checkpoint),
            subtitle = "${formatCheckpointPresentationKindLabel(checkpoint)} · ${
                formatCheckpointRollbackActionLabel(checkpoint.scope)
            } · ${
                formatChatCheckpointPresentationTime(checkpoint.createdAt)
            }",
            changedFilesPreview = checkpoint.changedFiles
                .take(3)
                .joinToString("\n")
                .ifBlank {
                    formatCheckpointPresentationPreview(checkpoint)
                        ?: "没有记录到可展示的文件路径"
                }
        )
    }
    val recoveryPresentations = recentRecoveryRecords.map { record ->
        val actionLabel = formatCheckpointRecoveryActionLabel(record.scope)
        val sourceCheckpoint = checkpointsById[record.checkpointId]
        ChatCheckpointRecoveryPresentation(
            id = record.id,
            title = actionLabel,
            subtitle = "${formatCheckpointRecoveryPresentationSummary(record, sourceCheckpoint)} · ${
                formatChatCheckpointPresentationTime(record.timestamp)
            }",
            detailTitle = actionLabel,
            detailSubtitle = "恢复文件 ${record.restoredFileCount} · 目标消息位置 ${record.targetMessageIndex + 1} · ${
                formatChatCheckpointPresentationTime(record.timestamp)
            }",
            detailContent = buildCheckpointRecoveryDetailContent(record, sourceCheckpoint)
        )
    }
    return ChatCheckpointHistoryPresentation(
        title = "本轮修改与恢复",
        emptyState = "还没有可汇总的修改或恢复记录",
        checkpoints = checkpointPresentations,
        recoveries = recoveryPresentations
    )
}

internal fun hasChatCheckpointActivity(presentation: ChatCheckpointHistoryPresentation): Boolean {
    return presentation.checkpoints.isNotEmpty() || presentation.recoveries.isNotEmpty()
}

internal fun findChatCheckpointRecoveryPresentation(
    presentation: ChatCheckpointHistoryPresentation,
    recordId: String
): ChatCheckpointRecoveryPresentation? {
    return presentation.recoveries.firstOrNull { it.id == recordId }
}

private fun formatChatCheckpointPresentationTime(timestamp: Long): String {
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
