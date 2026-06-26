package com.murong.agent.ui.tools

import com.murong.agent.core.loop.CheckpointRecoveryRecordUi
import com.murong.agent.core.loop.ClarificationRequestUi
import com.murong.agent.core.loop.ClarificationSource
import com.murong.agent.core.loop.ConversationCheckpointScope
import com.murong.agent.core.loop.ConversationCheckpointSource
import com.murong.agent.core.loop.ConversationCheckpointUi
import com.murong.agent.core.loop.FileChangeRecordUi
import com.murong.agent.core.loop.PendingAskRequestUi
import com.murong.agent.core.loop.WorkflowPlanUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class CheckpointToolsPresentation(
    val checkpointCountLabel: String,
    val fileChangeCountLabel: String,
    val recoveryCountLabel: String,
    val checkpoints: List<CheckpointToolPresentation>,
    val recoveries: List<CheckpointRecoveryToolPresentation>,
    val fileChanges: List<FileChangeToolPresentation>
)

internal data class CheckpointToolPresentation(
    val id: String,
    val title: String,
    val subtitle: String,
    val detailTitle: String,
    val detailSubtitle: String,
    val detailContent: String,
    val rollbackScope: ConversationCheckpointScope,
    val scopeLabel: String,
    val sourceLabel: String,
    val changedFiles: List<String>,
    val rollbackLabel: String,
    val rollbackDescription: String,
    val recordIds: List<String> = emptyList()
)

internal data class FileChangeToolPresentation(
    val id: String,
    val title: String,
    val subtitle: String,
    val detailTitle: String,
    val detailSubtitle: String,
    val detailContent: String,
    val checkpointId: String? = null
)

internal data class CheckpointRecoveryToolPresentation(
    val id: String,
    val title: String,
    val subtitle: String,
    val detailTitle: String,
    val detailSubtitle: String,
    val detailContent: String
)

internal fun buildCheckpointToolsPresentation(
    checkpoints: List<ConversationCheckpointUi>,
    fileChanges: List<FileChangeRecordUi>,
    recentRecoveryRecords: List<CheckpointRecoveryRecordUi> = emptyList()
): CheckpointToolsPresentation {
    val checkpointsById = checkpoints.associateBy { it.id }
    val fileChangePresentations = fileChanges.map { record ->
        FileChangeToolPresentation(
            id = record.id,
            title = record.operation,
            subtitle = record.path,
            detailTitle = "${record.operation} · ${record.path}",
            detailSubtitle = "时间 ${formatCheckpointPresentationTime(record.changedAt)}",
            detailContent = record.diffPreview.ifBlank {
                record.afterContent ?: record.beforeContent ?: "没有可展示内容"
            },
            checkpointId = record.checkpointId
        )
    }
    val recordsByCheckpointId = fileChangePresentations
        .filter { it.checkpointId != null }
        .groupBy { it.checkpointId }
    val checkpointPresentations = checkpoints.map { checkpoint ->
        val matchingRecords = recordsByCheckpointId[checkpoint.id].orEmpty()
        val scopeLabel = formatCheckpointScopeLabel(checkpoint.scope)
        val sourceLabel = formatCheckpointSourceLabel(checkpoint.source)
        val activityTitle = formatCheckpointPresentationTitle(checkpoint)
        val activityKindLabel = formatCheckpointPresentationKindLabel(checkpoint)
        CheckpointToolPresentation(
            id = checkpoint.id,
            title = activityTitle,
            subtitle = "$scopeLabel · $sourceLabel · $activityKindLabel · ${
                formatCheckpointPresentationTime(checkpoint.createdAt)
            }",
            detailTitle = activityTitle,
            detailSubtitle = "恢复域: $scopeLabel · 来源: $sourceLabel · 创建时间: ${
                formatCheckpointPresentationTime(checkpoint.createdAt)
            }",
            detailContent = buildCheckpointPresentationDetailContent(checkpoint, matchingRecords.size),
            rollbackScope = checkpoint.scope,
            scopeLabel = scopeLabel,
            sourceLabel = sourceLabel,
            changedFiles = checkpoint.changedFiles,
            rollbackLabel = formatCheckpointRollbackActionLabel(checkpoint.scope),
            rollbackDescription = formatCheckpointRollbackImpactCopy(checkpoint.scope),
            recordIds = matchingRecords.map { it.id }
        )
    }
    val recoveryPresentations = recentRecoveryRecords.map { record ->
        val sourceCheckpoint = checkpointsById[record.checkpointId]
        val scopeLabel = formatCheckpointScopeLabel(record.scope)
        val restoredFilesLabel = when (record.scope) {
            ConversationCheckpointScope.CONVERSATION -> "文件恢复 0"
            ConversationCheckpointScope.CODE,
            ConversationCheckpointScope.BOTH -> "文件恢复 ${record.restoredFileCount}"
        }
        CheckpointRecoveryToolPresentation(
            id = record.id,
            title = formatCheckpointRecoveryActionLabel(record.scope),
            subtitle = "$scopeLabel · $restoredFilesLabel · ${
                formatCheckpointPresentationTime(record.timestamp)
            }",
            detailTitle = formatCheckpointRecoveryActionLabel(record.scope),
            detailSubtitle = "恢复域: $scopeLabel · 目标消息位置: ${record.targetMessageIndex + 1} · 时间: ${
                formatCheckpointPresentationTime(record.timestamp)
            }",
            detailContent = buildCheckpointRecoveryDetailContent(record, sourceCheckpoint)
        )
    }
    return CheckpointToolsPresentation(
        checkpointCountLabel = checkpoints.size.toString(),
        fileChangeCountLabel = fileChanges.size.toString(),
        recoveryCountLabel = recentRecoveryRecords.size.toString(),
        checkpoints = checkpointPresentations,
        recoveries = recoveryPresentations,
        fileChanges = fileChangePresentations
    )
}

internal fun findCheckpointToolPresentation(
    presentation: CheckpointToolsPresentation,
    checkpointId: String
): CheckpointToolPresentation? {
    return presentation.checkpoints.firstOrNull { it.id == checkpointId }
}

internal fun findFileChangeToolPresentation(
    presentation: CheckpointToolsPresentation,
    recordId: String
): FileChangeToolPresentation? {
    return presentation.fileChanges.firstOrNull { it.id == recordId }
}

internal fun findCheckpointRecoveryToolPresentation(
    presentation: CheckpointToolsPresentation,
    recordId: String
): CheckpointRecoveryToolPresentation? {
    return presentation.recoveries.firstOrNull { it.id == recordId }
}

internal fun resolveCheckpointRecordPresentations(
    presentation: CheckpointToolsPresentation,
    checkpoint: CheckpointToolPresentation
): List<FileChangeToolPresentation> {
    if (checkpoint.recordIds.isEmpty()) return emptyList()
    val recordsById = presentation.fileChanges.associateBy { it.id }
    return checkpoint.recordIds.mapNotNull(recordsById::get)
}

private fun formatCheckpointPresentationTime(timestamp: Long): String {
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

private fun formatCheckpointScopeLabel(scope: ConversationCheckpointScope): String {
    return when (scope) {
        ConversationCheckpointScope.CODE -> "代码恢复"
        ConversationCheckpointScope.CONVERSATION -> "对话恢复"
        ConversationCheckpointScope.BOTH -> "代码/对话"
    }
}

internal fun formatCheckpointRollbackActionLabel(scope: ConversationCheckpointScope): String {
    return when (scope) {
        ConversationCheckpointScope.CODE -> "回滚代码"
        ConversationCheckpointScope.CONVERSATION -> "恢复对话"
        ConversationCheckpointScope.BOTH -> "恢复全部"
    }
}

internal fun formatCheckpointRollbackImpactCopy(scope: ConversationCheckpointScope): String {
    return when (scope) {
        ConversationCheckpointScope.CODE ->
            "点击后，这批文件会直接恢复到该检查点之前的状态，之后基于这些文件产生的后续结果也可能失效。"
        ConversationCheckpointScope.CONVERSATION ->
            "点击后，对话会回到该检查点之前的状态，之后的助手回复、工具记录和相关上下文会一起撤回。"
        ConversationCheckpointScope.BOTH ->
            "点击后，文件修改和对话上下文都会一起恢复到该检查点之前的状态，之后产生的结果也可能失效。"
    }
}

internal fun buildCheckpointRollbackSuccessMessage(
    scope: ConversationCheckpointScope,
    restoredFileCount: Int
): String {
    return when (scope) {
        ConversationCheckpointScope.CODE -> "已按检查点回滚代码修改，恢复 $restoredFileCount 个文件"
        ConversationCheckpointScope.CONVERSATION -> "已按检查点恢复对话上下文"
        ConversationCheckpointScope.BOTH -> "已按检查点恢复对话与代码，恢复 $restoredFileCount 个文件"
    }
}

internal fun formatCheckpointRecoveryActionLabel(scope: ConversationCheckpointScope): String {
    return when (scope) {
        ConversationCheckpointScope.CODE -> "最近回滚代码"
        ConversationCheckpointScope.CONVERSATION -> "最近恢复对话"
        ConversationCheckpointScope.BOTH -> "最近恢复全部"
    }
}

private fun formatCheckpointSourceLabel(source: ConversationCheckpointSource): String {
    return when (source) {
        ConversationCheckpointSource.TOOL_EXECUTION -> "工具执行"
        ConversationCheckpointSource.ROLLBACK -> "回滚生成"
        ConversationCheckpointSource.LEGACY_EMBEDDED -> "旧版迁移"
    }
}

internal fun formatCheckpointPresentationTitle(checkpoint: ConversationCheckpointUi): String {
    val promptSnapshot = checkpoint.promptSnapshot
    val clarificationRequest = promptSnapshot?.pendingClarificationRequest
    if (clarificationRequest != null && checkpoint.changedFiles.isEmpty()) {
        return when (clarificationRequest.source) {
            ClarificationSource.AUTO_INTERRUPT -> "执行中等待澄清回答"
            ClarificationSource.AUTO_ROUTE -> "自动分流等待澄清回答"
            else -> "等待澄清回答"
        }
    }
    if (promptSnapshot?.pendingAskRequest != null && checkpoint.changedFiles.isEmpty()) {
        return "等待用户确认"
    }
    if (promptSnapshot?.pendingWorkflowPlan != null && checkpoint.changedFiles.isEmpty()) {
        return "已生成执行计划"
    }
    return checkpoint.summary
}

internal fun formatCheckpointPresentationKindLabel(checkpoint: ConversationCheckpointUi): String {
    val promptSnapshot = checkpoint.promptSnapshot
    if (checkpoint.changedFiles.isNotEmpty()) {
        return "变更 ${checkpoint.changedFiles.size} 个文件"
    }
    return when {
        promptSnapshot?.pendingClarificationRequest != null -> "澄清状态"
        promptSnapshot?.pendingAskRequest != null -> "确认状态"
        promptSnapshot?.pendingWorkflowPlan != null -> "计划状态"
        else -> "变更 0 个文件"
    }
}

internal fun formatCheckpointPresentationPreview(checkpoint: ConversationCheckpointUi): String? {
    checkpoint.promptSnapshot?.pendingClarificationRequest?.let { request ->
        return "澄清问题: ${request.question}"
    }
    checkpoint.promptSnapshot?.pendingAskRequest?.let { request ->
        val firstQuestion = request.questions.firstOrNull()?.question
        return if (firstQuestion.isNullOrBlank()) {
            "提问卡片: ${request.title}"
        } else {
            "提问卡片: $firstQuestion"
        }
    }
    checkpoint.promptSnapshot?.pendingWorkflowPlan?.let { plan ->
        val preview = plan.nextStepHint.ifBlank { plan.summary }.ifBlank { plan.goal }
        return "执行计划: $preview"
    }
    return null
}

internal fun formatCheckpointRecoveryPresentationSummary(
    record: CheckpointRecoveryRecordUi,
    checkpoint: ConversationCheckpointUi? = null
): String {
    val title = checkpoint?.let(::formatCheckpointPresentationTitle)
    val preview = checkpoint?.let(::formatCheckpointPresentationPreview)
    val fallbackSummary = normalizeCheckpointRecoverySummary(record)
    return buildString {
        append(title ?: fallbackSummary)
        if (!preview.isNullOrBlank()) {
            append(" · ")
            append(preview)
        }
    }
}

internal fun buildCheckpointRecoveryDetailContent(
    record: CheckpointRecoveryRecordUi,
    checkpoint: ConversationCheckpointUi? = null
): String {
    return buildString {
        appendLine("来源检查点: ${record.checkpointId}")
        appendLine("恢复动作: ${formatCheckpointRecoveryActionLabel(record.scope)}")
        appendLine("恢复摘要: ${formatCheckpointRecoveryPresentationSummary(record, checkpoint)}")
        append("恢复文件数: ${record.restoredFileCount}")
    }
}

private fun normalizeCheckpointRecoverySummary(record: CheckpointRecoveryRecordUi): String {
    val summary = record.checkpointSummary.trim()
    if (summary.isBlank()) {
        return when (record.scope) {
            ConversationCheckpointScope.CODE -> "已恢复代码修改"
            ConversationCheckpointScope.CONVERSATION -> "已恢复对话上下文"
            ConversationCheckpointScope.BOTH -> "已恢复对话与代码"
        }
    }
    val knownPrefixes = buildList {
        add("恢复代码/对话:")
        add("恢复对话:")
        add("恢复代码:")
        add("${formatCheckpointRecoveryActionLabel(record.scope)}:")
    }
    val normalized = knownPrefixes.firstNotNullOfOrNull { prefix ->
        summary.removePrefix(prefix).takeIf { it != summary }?.trim()
    }.orEmpty()
    return normalized.ifBlank { summary }
}

private fun buildCheckpointPresentationDetailContent(
    checkpoint: ConversationCheckpointUi,
    matchingRecordCount: Int
): String {
    val promptSnapshot = checkpoint.promptSnapshot
    val clarificationRequest = promptSnapshot?.pendingClarificationRequest
    val askRequest = promptSnapshot?.pendingAskRequest
    val workflowPlan = promptSnapshot?.pendingWorkflowPlan
    val promptContent = when {
        clarificationRequest != null ->
            buildClarificationCheckpointDetailContent(clarificationRequest)
        askRequest != null ->
            buildAskCheckpointDetailContent(askRequest)
        workflowPlan != null ->
            buildWorkflowCheckpointDetailContent(workflowPlan)
        else -> null
    }
    return buildString {
        appendLine("检查点摘要: ${formatCheckpointPresentationTitle(checkpoint)}")
        appendLine("检查点类型: ${formatCheckpointPresentationKindLabel(checkpoint)}")
        appendLine("关联记录: $matchingRecordCount")
        if (checkpoint.changedFiles.isNotEmpty()) {
            appendLine("变更文件数: ${checkpoint.changedFiles.size}")
        }
        if (!promptContent.isNullOrBlank()) {
            appendLine()
            append(promptContent)
        } else if (checkpoint.summary.isNotBlank()) {
            appendLine()
            append("原始摘要: ${checkpoint.summary}")
        }
    }.trim()
}

private fun buildClarificationCheckpointDetailContent(request: ClarificationRequestUi): String {
    return buildString {
        appendLine("交互状态: 等待澄清回答")
        request.goal.takeIf { it.isNotBlank() }?.let { goal ->
            appendLine("任务目标: $goal")
        }
        appendLine("澄清问题: ${request.question}")
        append("澄清来源: ${formatClarificationSourceLabel(request.source)}")
    }
}

private fun buildAskCheckpointDetailContent(request: PendingAskRequestUi): String {
    return buildString {
        appendLine("交互状态: 等待用户确认")
        request.title.takeIf { it.isNotBlank() }?.let { title ->
            appendLine("卡片标题: $title")
        }
        appendLine("问题数量: ${request.questions.size}")
        request.questions.take(3).forEachIndexed { index, question ->
            appendLine("问题 ${index + 1}: ${question.question}")
            val options = question.options
                .mapNotNull { option -> option.label.takeIf { it.isNotBlank() } }
                .take(4)
            if (options.isNotEmpty()) {
                appendLine("可选项: ${options.joinToString(" / ")}")
            }
        }
        if (request.questions.size > 3) {
            append("其余问题: 省略 ${request.questions.size - 3} 条")
        } else {
            append("等待用户提交答案后继续执行。")
        }
    }
}

private fun buildWorkflowCheckpointDetailContent(request: WorkflowPlanUi): String {
    return buildString {
        appendLine("交互状态: 已生成执行计划")
        request.goal.takeIf { it.isNotBlank() }?.let { goal ->
            appendLine("任务目标: $goal")
        }
        request.summary.takeIf { it.isNotBlank() }?.let { summary ->
            appendLine("计划摘要: $summary")
        }
        request.nextStepHint.takeIf { it.isNotBlank() }?.let { nextStepHint ->
            appendLine("下一步建议: $nextStepHint")
        }
        append("等待用户确认后再进入后续执行。")
    }
}

private fun formatClarificationSourceLabel(source: ClarificationSource): String {
    return when (source) {
        ClarificationSource.MANUAL -> "手动触发"
        ClarificationSource.AUTO_ROUTE -> "自动分流"
        ClarificationSource.AUTO_INTERRUPT -> "执行中断"
    }
}
