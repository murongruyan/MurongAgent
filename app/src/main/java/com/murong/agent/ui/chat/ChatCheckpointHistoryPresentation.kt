package com.murong.agent.ui.chat

import com.murong.agent.core.loop.CheckpointRecoveryRecordUi
import com.murong.agent.core.loop.ConversationCheckpointScope
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
    val recoveryOverviewLabel: String? = null,
    val checkpoints: List<ChatCheckpointBatchPresentation>,
    val recoveries: List<ChatCheckpointRecoveryPresentation>,
    val recoveryTimelineGroups: List<ChatCheckpointRecoveryTimelineGroup> = emptyList()
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
    val detailContent: String,
    val scope: ConversationCheckpointScope,
    val summaryPreview: String,
    val timestamp: Long
)

internal data class ChatCheckpointRecoveryTimelineGroup(
    val dayLabel: String,
    val summaryLabel: String,
    val records: List<ChatCheckpointRecoveryPresentation>
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
    val recoveryPresentations = recentRecoveryRecords
        .sortedByDescending { it.timestamp }
        .map { record ->
        val actionLabel = formatCheckpointRecoveryActionLabel(record.scope)
        val sourceCheckpoint = checkpointsById[record.checkpointId]
        val summaryPreview = formatCheckpointRecoveryPresentationSummary(record, sourceCheckpoint)
        ChatCheckpointRecoveryPresentation(
            id = record.id,
            title = actionLabel,
            subtitle = "$summaryPreview · ${
                formatChatCheckpointPresentationTime(record.timestamp)
            }",
            detailTitle = actionLabel,
            detailSubtitle = "恢复文件 ${record.restoredFileCount} · 目标消息位置 ${record.targetMessageIndex + 1} · ${
                formatChatCheckpointPresentationTime(record.timestamp)
            }",
            detailContent = buildCheckpointRecoveryDetailContent(record, sourceCheckpoint),
            scope = record.scope,
            summaryPreview = summaryPreview,
            timestamp = record.timestamp
        )
    }
    return ChatCheckpointHistoryPresentation(
        title = "本轮修改与恢复",
        emptyState = "还没有可汇总的修改或恢复记录",
        recoveryOverviewLabel = buildChatCheckpointRecoveryOverviewLabel(recentRecoveryRecords),
        checkpoints = checkpointPresentations,
        recoveries = recoveryPresentations,
        recoveryTimelineGroups = buildChatCheckpointRecoveryTimelineGroups(recoveryPresentations)
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

private fun formatChatCheckpointPresentationDay(timestamp: Long): String {
    return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
}

internal fun buildChatCheckpointRecoveryOverviewLabel(
    recentRecoveryRecords: List<CheckpointRecoveryRecordUi>
): String? {
    if (recentRecoveryRecords.isEmpty()) return null
    val bothCount = recentRecoveryRecords.count { it.scope == ConversationCheckpointScope.BOTH }
    val conversationCount = recentRecoveryRecords.count { it.scope == ConversationCheckpointScope.CONVERSATION }
    val codeCount = recentRecoveryRecords.count { it.scope == ConversationCheckpointScope.CODE }
    val parts = buildList {
        if (bothCount > 0) add("全部 $bothCount")
        if (conversationCount > 0) add("对话 $conversationCount")
        if (codeCount > 0) add("代码 $codeCount")
    }
    return when {
        parts.isEmpty() -> null
        parts.size == 1 -> "最近恢复以 ${parts.single()} 为主"
        else -> "最近恢复分布: ${parts.joinToString(" · ")}"
    }
}

internal fun buildChatCheckpointRecoveryTimelineGroups(
    recoveries: List<ChatCheckpointRecoveryPresentation>
): List<ChatCheckpointRecoveryTimelineGroup> {
    return recoveries
        .sortedByDescending { it.timestamp }
        .groupBy { formatChatCheckpointPresentationDay(it.timestamp) }
        .map { (dayLabel, dayRecords) ->
            ChatCheckpointRecoveryTimelineGroup(
                dayLabel = dayLabel,
                summaryLabel = buildChatCheckpointRecoveryTimelineSummary(dayRecords),
                records = dayRecords
            )
        }
}

private fun buildChatCheckpointRecoveryTimelineSummary(
    records: List<ChatCheckpointRecoveryPresentation>
): String {
    val bothCount = records.count { it.scope == ConversationCheckpointScope.BOTH }
    val conversationCount = records.count { it.scope == ConversationCheckpointScope.CONVERSATION }
    val codeCount = records.count { it.scope == ConversationCheckpointScope.CODE }
    return buildList {
        if (bothCount > 0) add("全部 $bothCount")
        if (conversationCount > 0) add("对话 $conversationCount")
        if (codeCount > 0) add("代码 $codeCount")
    }.joinToString(" · ")
}
