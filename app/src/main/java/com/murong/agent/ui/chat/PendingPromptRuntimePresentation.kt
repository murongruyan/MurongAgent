package com.murong.agent.ui.chat

import com.murong.agent.core.config.WorkflowExecutionMode
import com.murong.agent.core.loop.RuntimeStatusKind
import com.murong.agent.core.loop.RuntimeStatusSnapshot

internal data class PendingPromptRuntimePresentation(
    val title: String,
    val message: String,
    val badge: String? = null
)

internal data class SupplementalRuntimeStatusPresentation(
    val title: String,
    val message: String,
    val badge: String? = null
)

internal fun buildPendingPromptRuntimePresentation(
    runtimeStatusSnapshot: RuntimeStatusSnapshot?,
    askPresentation: AskPromptPresentation?,
    workflowPlanPresentation: WorkflowPlanPromptPresentation?,
    clarificationPresentation: ClarificationPromptPresentation?
): PendingPromptRuntimePresentation? {
    return when (runtimeStatusSnapshot?.kind) {
        RuntimeStatusKind.PENDING_ASK -> {
            askPresentation?.let { presentation ->
                PendingPromptRuntimePresentation(
                    title = "等待回答",
                    message = buildString {
                        append(presentation.replayNotice ?: presentation.title)
                        if (presentation.questions.isNotEmpty()) {
                            append("\n共 ${presentation.questions.size} 题")
                            if (presentation.replayOnly) {
                                append(" · 恢复态只读")
                            }
                        }
                    },
                    badge = "问"
                )
            }
        }

        RuntimeStatusKind.PENDING_WORKFLOW_PLAN -> {
            workflowPlanPresentation?.let { presentation ->
                val sessionPresentation = buildWorkflowPlanSessionPresentation(
                    presentation = presentation,
                    interactionState = buildInitialWorkflowPlanInteractionState(),
                    isProcessing = false
                )
                PendingPromptRuntimePresentation(
                    title = "等待计划确认",
                    message = buildString {
                        append(presentation.goal.ifBlank { presentation.summary })
                        append("\n${sessionPresentation.status.summaryLabel}")
                        sessionPresentation.recentHistorySummary?.let {
                            append("\n")
                            append(it)
                        }
                    },
                    badge = "计"
                )
            }
        }

        RuntimeStatusKind.PENDING_CLARIFICATION -> {
            clarificationPresentation?.let { presentation ->
                val sessionPresentation = buildClarificationSessionPresentation(
                    presentation = presentation,
                    interactionState = buildInitialClarificationInteractionState(),
                    isProcessing = false
                )
                PendingPromptRuntimePresentation(
                    title = "等待澄清回答",
                    message = buildString {
                        append(presentation.question)
                        append("\n${sessionPresentation.status.summaryLabel}")
                        sessionPresentation.recentHistorySummary?.let {
                            append("\n")
                            append(it)
                        }
                    },
                    badge = "澄"
                )
            }
        }

        else -> null
    }
}

internal fun buildSupplementalRuntimeStatusPresentations(
    workflowExecutionMode: WorkflowExecutionMode,
    autoRouteBeforeExecution: Boolean,
    autoRoutingInProgress: Boolean,
    workflowPlanningInProgress: Boolean,
    clarificationInProgress: Boolean,
    runtimeStatusSnapshot: RuntimeStatusSnapshot?
): List<SupplementalRuntimeStatusPresentation> {
    val runtimeStatusKind = runtimeStatusSnapshot?.kind
    return buildList {
        if (
            workflowExecutionMode == WorkflowExecutionMode.SINGLE_PASS &&
            autoRouteBeforeExecution &&
            autoRoutingInProgress &&
            runtimeStatusKind != RuntimeStatusKind.AUTO_ROUTING
        ) {
            add(
                SupplementalRuntimeStatusPresentation(
                    title = "自动分流",
                    message = "正在自动判断本次输入更适合直接执行、先出计划，还是先澄清。"
                )
            )
        }
        if (
            workflowPlanningInProgress &&
            runtimeStatusKind != RuntimeStatusKind.WORKFLOW_PLANNING
        ) {
            add(
                SupplementalRuntimeStatusPresentation(
                    title = "执行计划",
                    message = "正在生成执行计划，稍后可确认后一次性执行。"
                )
            )
        }
        if (
            clarificationInProgress &&
            runtimeStatusKind != RuntimeStatusKind.CLARIFICATION
        ) {
            add(
                SupplementalRuntimeStatusPresentation(
                    title = "澄清问题",
                    message = "正在生成澄清问题，回答后会继续执行。"
                )
            )
        }
    }
}
