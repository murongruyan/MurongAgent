package com.murong.agent.ui.chat

import com.murong.agent.core.loop.ConversationCheckpointKind
import com.murong.agent.core.loop.ConversationCheckpointSource
import com.murong.agent.core.loop.ConversationCheckpointUi
import com.murong.agent.core.loop.FileChangeRecordUi
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator

internal data class ChatWorkspaceReviewFileUi(
    val record: FileChangeRecordUi,
    val addedLines: Int,
    val deletedLines: Int
)

internal data class ChatWorkspaceReviewUi(
    val checkpoint: ConversationCheckpointUi,
    val files: List<ChatWorkspaceReviewFileUi>,
    val addedLines: Int,
    val deletedLines: Int
)

internal fun buildChatWorkspaceReviews(
    checkpoints: List<ConversationCheckpointUi>,
    fileChanges: List<FileChangeRecordUi>
): List<ChatWorkspaceReviewUi> {
    if (checkpoints.isEmpty() || fileChanges.isEmpty()) return emptyList()
    val recordsByCheckpointId = fileChanges
        .filter { !it.checkpointId.isNullOrBlank() }
        .groupBy { it.checkpointId }
    return checkpoints
        .asSequence()
        .filter { checkpoint ->
            checkpoint.kind == ConversationCheckpointKind.FILE_TURN &&
                checkpoint.source == ConversationCheckpointSource.TOOL_EXECUTION &&
                checkpoint.changedFiles.isNotEmpty()
        }
        .mapNotNull { checkpoint ->
            buildChatWorkspaceReview(
                checkpoint = checkpoint,
                records = recordsByCheckpointId[checkpoint.id].orEmpty()
            )
        }
        .sortedBy { it.checkpoint.messageIndex }
        .toList()
}

internal fun buildChatWorkspaceReview(
    checkpoint: ConversationCheckpointUi,
    records: List<FileChangeRecordUi>
): ChatWorkspaceReviewUi? {
    if (records.isEmpty()) return null
    val latestRecordByPath = records
        .sortedByDescending { it.changedAt }
        .distinctBy { it.path }
        .associateBy { it.path }
    val orderedPaths = LinkedHashSet<String>().apply {
        addAll(checkpoint.changedFiles)
        addAll(latestRecordByPath.keys)
    }
    val files = orderedPaths.mapNotNull { path ->
        latestRecordByPath[path]?.let { record ->
            val stats = calculateLineChangeStats(
                beforeContent = record.beforeContent,
                afterContent = record.afterContent
            )
            ChatWorkspaceReviewFileUi(
                record = record,
                addedLines = stats.first,
                deletedLines = stats.second
            )
        }
    }
    if (files.isEmpty()) return null
    return ChatWorkspaceReviewUi(
        checkpoint = checkpoint,
        files = files,
        addedLines = files.sumOf { it.addedLines },
        deletedLines = files.sumOf { it.deletedLines }
    )
}

internal fun calculateLineChangeStats(
    beforeContent: String?,
    afterContent: String?
): Pair<Int, Int> {
    if (beforeContent == afterContent) return 0 to 0
    return runCatching {
        val before = RawText(beforeContent.orEmpty().toByteArray(Charsets.UTF_8))
        val after = RawText(afterContent.orEmpty().toByteArray(Charsets.UTF_8))
        val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, before, after)
        edits.sumOf { it.endB - it.beginB } to edits.sumOf { it.endA - it.beginA }
    }.getOrElse {
        calculateLineChangeStatsFallback(beforeContent.orEmpty(), afterContent.orEmpty())
    }
}

private fun calculateLineChangeStatsFallback(
    beforeContent: String,
    afterContent: String
): Pair<Int, Int> {
    val beforeLines = beforeContent.lineSequence().toList()
    val afterLines = afterContent.lineSequence().toList()
    var prefix = 0
    while (
        prefix < beforeLines.size &&
        prefix < afterLines.size &&
        beforeLines[prefix] == afterLines[prefix]
    ) {
        prefix += 1
    }
    var suffix = 0
    while (
        suffix < beforeLines.size - prefix &&
        suffix < afterLines.size - prefix &&
        beforeLines[beforeLines.lastIndex - suffix] == afterLines[afterLines.lastIndex - suffix]
    ) {
        suffix += 1
    }
    return (afterLines.size - prefix - suffix).coerceAtLeast(0) to
        (beforeLines.size - prefix - suffix).coerceAtLeast(0)
}
