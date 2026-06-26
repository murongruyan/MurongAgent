package com.murong.agent.ui.chat

import com.murong.agent.core.loop.ClarificationRequestUi
import com.murong.agent.core.loop.ClarificationSource
import com.murong.agent.core.loop.SessionHistoryReferenceClueUi
import com.murong.agent.core.loop.WorkflowPlanStatusUi
import com.murong.agent.core.loop.WorkflowPlanUi

internal data class RecentHistoryCluePresentation(
    val title: String,
    val summary: String,
    val detailRows: List<RecentHistoryClueDetailPresentation> = emptyList()
)

internal data class RecentHistoryClueDetailPresentation(
    val label: String,
    val value: String
)

internal data class WorkflowPlanPromptPresentation(
    val requestId: String,
    val title: String,
    val goal: String,
    val status: WorkflowPlanStatusUi,
    val statusLabel: String,
    val stageSectionTitle: String,
    val progressSectionTitle: String,
    val mentionedFilesSectionTitle: String,
    val progressLabel: String,
    val progressFraction: Float,
    val summary: String,
    val nextStepTitle: String,
    val nextStepHint: String,
    val executeLabel: String,
    val stageChips: List<WorkflowPlanStagePresentation>,
    val stepRows: List<WorkflowPlanStepPresentation>,
    val recentHistoryClue: RecentHistoryCluePresentation? = null,
    val mentionedFiles: List<String>,
    val rawPlan: String
)

internal data class WorkflowPlanStagePresentation(
    val label: String,
    val isCurrent: Boolean,
    val isCompleted: Boolean
)

internal data class WorkflowPlanStepPresentation(
    val badgeLabel: String,
    val step: String,
    val status: WorkflowPlanStepPresentationStatus
)

internal data class PendingExecutionPrimaryActionPresentation(
    val label: String,
    val enabled: Boolean
)

internal data class PendingExecutionStatusPresentation(
    val summaryLabel: String,
    val guidance: String,
    val disabledHint: String? = null
)

internal data class WorkflowPlanSessionPresentation(
    val showRawPlan: Boolean,
    val rawPlanToggleLabel: String? = null,
    val rawPlanContent: String? = null,
    val recentHistorySummary: String? = null,
    val dismissLabel: String,
    val executeAction: PendingExecutionPrimaryActionPresentation,
    val status: PendingExecutionStatusPresentation
)

internal data class WorkflowPlanInteractionState(
    val showRawPlan: Boolean = false
)

internal enum class WorkflowPlanStepPresentationStatus {
    COMPLETED,
    CURRENT,
    PENDING
}

internal fun WorkflowPlanUi.toWorkflowPlanPromptPresentation(): WorkflowPlanPromptPresentation {
    return WorkflowPlanPromptPresentation(
        requestId = id,
        title = "执行计划",
        goal = goal,
        status = status,
        statusLabel = workflowPlanStatusText(status),
        stageSectionTitle = "阶段状态",
        progressSectionTitle = "步骤进度",
        mentionedFilesSectionTitle = "关联文件",
        progressLabel = "${currentStepIndex}/${steps.size}",
        progressFraction = if (steps.isEmpty()) 0f else currentStepIndex.toFloat() / steps.size.toFloat(),
        summary = summary,
        nextStepTitle = "下一步提示",
        nextStepHint = nextStepHint.ifBlank { "先确认目标与边界，再继续执行。" },
        executeLabel = workflowPlanExecuteLabel(status),
        stageChips = stageLabels.mapIndexed { index, stage ->
            WorkflowPlanStagePresentation(
                label = stage,
                isCurrent = index == currentStageIndex,
                isCompleted = index < currentStageIndex || status == WorkflowPlanStatusUi.COMPLETED
            )
        },
        stepRows = steps.mapIndexed { index, step ->
            val stepStatus = workflowPlanStepStatus(plan = this, stepIndex = index)
            WorkflowPlanStepPresentation(
                badgeLabel = when (stepStatus) {
                    WorkflowPlanStepPresentationStatus.COMPLETED -> "已完成"
                    WorkflowPlanStepPresentationStatus.CURRENT -> "当前"
                    WorkflowPlanStepPresentationStatus.PENDING -> "${index + 1}"
                },
                step = step,
                status = stepStatus
            )
        },
        recentHistoryClue = recentSessionHistoryClue?.toRecentHistoryCluePresentation(),
        mentionedFiles = mentionedFiles.map { it.displayPath },
        rawPlan = rawPlan
    )
}

internal data class ClarificationPromptPresentation(
    val requestId: String,
    val title: String,
    val subtitle: String? = null,
    val turnLabel: String,
    val previousAnswersSummary: String? = null,
    val recentHistoryClue: RecentHistoryCluePresentation? = null,
    val question: String,
    val inputPlaceholder: String,
    val submitLabel: String
)

internal data class ClarificationSessionPresentation(
    val draftAnswer: String,
    val submitAnswer: String,
    val recentHistorySummary: String? = null,
    val dismissLabel: String,
    val submitAction: PendingExecutionPrimaryActionPresentation,
    val status: PendingExecutionStatusPresentation
)

internal data class ClarificationInteractionState(
    val answer: String = ""
)

internal fun ClarificationRequestUi.toClarificationPromptPresentation(): ClarificationPromptPresentation {
    return ClarificationPromptPresentation(
        requestId = id,
        title = clarificationTitle(source),
        subtitle = clarificationSubtitle(source),
        turnLabel = "第 $turnIndex 轮 / 最多 $maxTurns 轮",
        previousAnswersSummary = previousAnswers.takeIf { it.isNotEmpty() }?.let {
            "已确认 ${it.size} 条澄清信息，本轮会在此基础上继续追问最关键的缺口。"
        },
        recentHistoryClue = recentSessionHistoryClue?.toRecentHistoryCluePresentation(),
        question = question,
        inputPlaceholder = "输入你的补充回答",
        submitLabel = if (turnIndex >= maxTurns) "继续执行" else "继续判断"
    )
}

internal fun SessionHistoryReferenceClueUi.toRecentHistoryCluePresentation(): RecentHistoryCluePresentation? {
    val summary = buildList {
        if (snippets.isNotEmpty()) {
            add("摘要：${snippets.toRecentHistoryPreview(limit = 2)}")
        }
        if (messageReferences.isNotEmpty()) {
            add("历史消息：${messageReferences.toRecentHistoryPreview(limit = 2)}")
        }
        if (excerptWindows.isNotEmpty()) {
            add("摘录窗口：${excerptWindows.toRecentHistoryPreview(limit = 2)}")
        }
        if (isEmpty() && queries.isNotEmpty()) {
            add("最近查询：${queries.toRecentHistoryPreview(limit = 2)}")
        }
        if (isEmpty() && sessionIds.isNotEmpty()) {
            add("历史会话：${sessionIds.toRecentHistoryPreview(limit = 2)}")
        }
    }.takeIf { it.isNotEmpty() }?.joinToString("；")
    val detailRows = buildList {
        if (queries.isNotEmpty()) {
            add(
                RecentHistoryClueDetailPresentation(
                    label = "最近查询",
                    value = queries.toRecentHistoryPreview(limit = 3)
                )
            )
        }
        if (sessionIds.isNotEmpty()) {
            add(
                RecentHistoryClueDetailPresentation(
                    label = "历史会话",
                    value = sessionIds.toRecentHistoryPreview(limit = 3)
                )
            )
        }
        if (messageReferences.isNotEmpty()) {
            add(
                RecentHistoryClueDetailPresentation(
                    label = "历史消息",
                    value = messageReferences.toRecentHistoryPreview(limit = 3)
                )
            )
        }
        if (snippets.isNotEmpty()) {
            add(
                RecentHistoryClueDetailPresentation(
                    label = "线索摘要",
                    value = snippets.toRecentHistoryPreview(limit = 3)
                )
            )
        }
        if (excerptWindows.isNotEmpty()) {
            add(
                RecentHistoryClueDetailPresentation(
                    label = "摘录窗口",
                    value = excerptWindows.toRecentHistoryPreview(limit = 3)
                )
            )
        }
    }
    return summary?.let {
        RecentHistoryCluePresentation(
            title = "最近历史线索",
            summary = it,
            detailRows = detailRows
        )
    }
}

private fun List<String>.toRecentHistoryPreview(limit: Int): String {
    val preview = take(limit)
    val remaining = size - preview.size
    return buildString {
        append(preview.joinToString("、"))
        if (remaining > 0) {
            append(" 等 ")
            append(size)
            append(" 条")
        }
    }
}

internal fun buildWorkflowPlanSessionPresentation(
    presentation: WorkflowPlanPromptPresentation,
    interactionState: WorkflowPlanInteractionState,
    isProcessing: Boolean
): WorkflowPlanSessionPresentation {
    val canToggleRawPlan = presentation.rawPlan.isNotBlank()
    val showRawPlan = interactionState.showRawPlan
    return WorkflowPlanSessionPresentation(
        showRawPlan = canToggleRawPlan && showRawPlan,
        rawPlanToggleLabel = if (canToggleRawPlan) {
            if (showRawPlan) "收起原始计划" else "展开原始计划"
        } else {
            null
        },
        rawPlanContent = presentation.rawPlan.takeIf { canToggleRawPlan && showRawPlan },
        recentHistorySummary = presentation.recentHistoryClue.toRuntimeHistorySummary(),
        dismissLabel = "关闭",
        executeAction = PendingExecutionPrimaryActionPresentation(
            label = presentation.executeLabel,
            enabled = !isProcessing
        ),
        status = buildWorkflowPlanStatusPresentation(
            presentation = presentation,
            isProcessing = isProcessing
        )
    )
}

internal fun buildClarificationSessionPresentation(
    presentation: ClarificationPromptPresentation,
    interactionState: ClarificationInteractionState,
    isProcessing: Boolean
): ClarificationSessionPresentation {
    val answer = interactionState.answer
    val trimmedAnswer = answer.trim()
    val hasAnswer = trimmedAnswer.isNotBlank()
    return ClarificationSessionPresentation(
        draftAnswer = answer,
        submitAnswer = trimmedAnswer,
        recentHistorySummary = presentation.recentHistoryClue.toRuntimeHistorySummary(),
        dismissLabel = "关闭",
        submitAction = PendingExecutionPrimaryActionPresentation(
            label = presentation.submitLabel,
            enabled = !isProcessing && hasAnswer
        ),
        status = buildClarificationStatusPresentation(
            presentation = presentation,
            hasAnswer = hasAnswer,
            isProcessing = isProcessing
        )
    )
}

internal fun buildInitialWorkflowPlanInteractionState(): WorkflowPlanInteractionState {
    return WorkflowPlanInteractionState()
}

internal fun toggleWorkflowPlanRawPlan(
    state: WorkflowPlanInteractionState,
    presentation: WorkflowPlanPromptPresentation
): WorkflowPlanInteractionState {
    if (presentation.rawPlan.isBlank()) return state.copy(showRawPlan = false)
    return state.copy(showRawPlan = !state.showRawPlan)
}

internal fun buildInitialClarificationInteractionState(): ClarificationInteractionState {
    return ClarificationInteractionState()
}

internal fun updateClarificationAnswer(
    state: ClarificationInteractionState,
    answer: String
): ClarificationInteractionState {
    return state.copy(answer = answer)
}

internal fun consumeClarificationSubmitAnswer(
    sessionPresentation: ClarificationSessionPresentation
): String? {
    if (!sessionPresentation.submitAction.enabled) return null
    return sessionPresentation.submitAnswer.takeIf { it.isNotBlank() }
}

private fun workflowPlanStepStatus(
    plan: WorkflowPlanUi,
    stepIndex: Int
): WorkflowPlanStepPresentationStatus {
    return when (plan.status) {
        WorkflowPlanStatusUi.COMPLETED -> WorkflowPlanStepPresentationStatus.COMPLETED
        WorkflowPlanStatusUi.READY -> if (stepIndex == 0) {
            WorkflowPlanStepPresentationStatus.CURRENT
        } else {
            WorkflowPlanStepPresentationStatus.PENDING
        }
        WorkflowPlanStatusUi.EXECUTING,
        WorkflowPlanStatusUi.BLOCKED -> {
            val activeIndex = (plan.currentStepIndex - 1).coerceAtLeast(0)
            when {
                stepIndex < activeIndex -> WorkflowPlanStepPresentationStatus.COMPLETED
                stepIndex == activeIndex -> WorkflowPlanStepPresentationStatus.CURRENT
                else -> WorkflowPlanStepPresentationStatus.PENDING
            }
        }
    }
}

private fun buildWorkflowPlanStatusPresentation(
    presentation: WorkflowPlanPromptPresentation,
    isProcessing: Boolean
): PendingExecutionStatusPresentation {
    val guidance = when (presentation.status) {
        WorkflowPlanStatusUi.READY -> "计划已整理完成，确认后会按当前步骤继续执行。"
        WorkflowPlanStatusUi.EXECUTING -> "当前计划正在推进中，现在更适合继续观察执行结果。"
        WorkflowPlanStatusUi.BLOCKED -> "计划执行已被阻塞，确认后会沿当前上下文继续尝试。"
        WorkflowPlanStatusUi.COMPLETED -> "当前计划已经完成，如需重跑可再次发起执行。"
    }
    return PendingExecutionStatusPresentation(
        summaryLabel = "${presentation.statusLabel} · 步骤 ${presentation.progressLabel}",
        guidance = guidance,
        disabledHint = if (isProcessing) {
            "当前正在处理上一条动作，暂时不能再次操作这张计划卡片。"
        } else {
            null
        }
    )
}

internal fun workflowPlanStatusText(status: WorkflowPlanStatusUi): String {
    return when (status) {
        WorkflowPlanStatusUi.READY -> "待执行"
        WorkflowPlanStatusUi.EXECUTING -> "执行中"
        WorkflowPlanStatusUi.BLOCKED -> "已阻塞"
        WorkflowPlanStatusUi.COMPLETED -> "已完成"
    }
}

private fun buildClarificationStatusPresentation(
    presentation: ClarificationPromptPresentation,
    hasAnswer: Boolean,
    isProcessing: Boolean
): PendingExecutionStatusPresentation {
    val guidance = when {
        hasAnswer -> "补充内容已准备好，提交后会沿当前问题继续判断。"
        presentation.previousAnswersSummary != null ->
            "本轮会基于已确认的信息继续追问最关键的缺口。"
        else -> "先补一个关键信息，再继续当前判断。"
    }
    val disabledHint = when {
        isProcessing -> "当前正在处理上一条动作，暂时不能提交新的澄清回答。"
        !hasAnswer -> "先输入回答内容，才能继续判断。"
        else -> null
    }
    return PendingExecutionStatusPresentation(
        summaryLabel = presentation.turnLabel,
        guidance = guidance,
        disabledHint = disabledHint
    )
}

private fun workflowPlanExecuteLabel(status: WorkflowPlanStatusUi): String {
    return when (status) {
        WorkflowPlanStatusUi.READY -> "按计划执行"
        WorkflowPlanStatusUi.EXECUTING -> "继续观察"
        WorkflowPlanStatusUi.BLOCKED -> "继续执行"
        WorkflowPlanStatusUi.COMPLETED -> "再次执行"
    }
}

private fun RecentHistoryCluePresentation?.toRuntimeHistorySummary(): String? {
    return this?.let { "${it.title}：${it.summary}" }
}

internal fun clarificationTitle(source: ClarificationSource): String {
    return when (source) {
        ClarificationSource.MANUAL -> "澄清问题"
        ClarificationSource.AUTO_ROUTE -> "自动分流澄清"
        ClarificationSource.AUTO_INTERRUPT -> "执行中自动打断"
    }
}

internal fun clarificationSubtitle(source: ClarificationSource): String? {
    return when (source) {
        ClarificationSource.MANUAL -> null
        ClarificationSource.AUTO_ROUTE ->
            "发送前自动判断到当前信息还不够完整，先补一个关键条件再继续。"
        ClarificationSource.AUTO_INTERRUPT ->
            "执行过程中识别到继续猜测风险较高，先补充一个关键信息再往下执行。"
    }
}
