package com.murong.agent.core.loop

import com.murong.agent.core.tool.WorkflowStepCompletion

internal fun resolveWorkflowExecutionFinalStatus(state: SessionState): WorkflowPlanStatusUi {
    return when {
        state.pendingClarificationRequest != null -> WorkflowPlanStatusUi.BLOCKED
        state.pendingApproval != null -> WorkflowPlanStatusUi.BLOCKED
        !state.error.isNullOrBlank() -> WorkflowPlanStatusUi.BLOCKED
        resolveActiveParallelBatchSnapshot(state) != null -> WorkflowPlanStatusUi.BLOCKED
        else -> WorkflowPlanStatusUi.COMPLETED
    }
}

internal fun buildBlockedWorkflowExecutionHint(state: SessionState): String {
    return when {
        state.pendingApproval != null -> "先处理审批请求，再继续按计划执行。"
        state.pendingClarificationRequest != null -> "先回答澄清问题，再继续按计划执行。"
        !state.error.isNullOrBlank() -> "先处理执行错误，再决定是重试还是重规划。"
        resolveActiveParallelBatchSnapshot(state) != null ->
            buildActiveParallelBatchExecutionHint(state)
                ?: buildParallelBatchBlockedHintFallback()
        else -> "先补齐阻塞条件，再继续推进当前计划。"
    }
}

internal fun applyParallelBatchWorkflowProgressTransition(
    state: SessionState
): SessionState {
    if (state.pendingApproval != null || state.pendingClarificationRequest != null || !state.error.isNullOrBlank()) {
        return state
    }
    val activeSnapshot = resolveActiveParallelBatchSnapshot(state)
    val activeHint = activeSnapshot?.let { buildActiveParallelBatchExecutionHint(state) }
        ?: buildParallelBatchBlockedHintFallback()
    val updatePlan: (WorkflowPlanUi?) -> WorkflowPlanUi? = { plan ->
        when {
            plan == null -> null
            plan.status == WorkflowPlanStatusUi.COMPLETED -> plan
            activeSnapshot != null && plan.status != WorkflowPlanStatusUi.READY -> plan.copy(
                status = WorkflowPlanStatusUi.BLOCKED,
                nextStepHint = activeHint
            )
            activeSnapshot == null &&
                plan.status == WorkflowPlanStatusUi.BLOCKED &&
                isParallelBatchWorkflowBlockedHint(plan.nextStepHint) -> plan.copy(
                status = WorkflowPlanStatusUi.EXECUTING,
                nextStepHint = buildParallelBatchResumeExecutionHint(plan, state)
            )
            else -> plan
        }
    }
    val updatedPendingPlan = updatePlan(state.pendingWorkflowPlan)
    val updatedCanonicalPlan = updatePlan(state.canonicalWorkflowPlan)
    if (updatedPendingPlan == state.pendingWorkflowPlan && updatedCanonicalPlan == state.canonicalWorkflowPlan) {
        return state
    }
    return state.copy(
        pendingWorkflowPlan = updatedPendingPlan,
        canonicalWorkflowPlan = updatedCanonicalPlan
    )
}

internal fun resolveWorkflowPlanAfterExecution(
    originalPlan: WorkflowPlanUi,
    executingPlan: WorkflowPlanUi,
    state: SessionState
): WorkflowPlanUi {
    val finalStatus = resolveWorkflowExecutionFinalStatus(state)
    return originalPlan.copy(
        status = finalStatus,
        currentStepIndex = when (finalStatus) {
            WorkflowPlanStatusUi.COMPLETED -> originalPlan.steps.size
            WorkflowPlanStatusUi.BLOCKED -> minOf(
                maxOf(executingPlan.currentStepIndex, 1),
                originalPlan.steps.size
            )
            WorkflowPlanStatusUi.EXECUTING -> executingPlan.currentStepIndex
            WorkflowPlanStatusUi.READY -> 0
        },
        nextStepHint = when (finalStatus) {
            WorkflowPlanStatusUi.BLOCKED -> buildBlockedWorkflowExecutionHint(state)
            WorkflowPlanStatusUi.COMPLETED -> "当前计划已完成，可继续发起下一轮任务或重做计划。"
            WorkflowPlanStatusUi.EXECUTING -> executingPlan.nextStepHint
            WorkflowPlanStatusUi.READY -> originalPlan.nextStepHint
        }
    )
}

internal fun resolveWorkflowPlanAfterStepCompletion(
    currentPlan: WorkflowPlanUi,
    completion: WorkflowStepCompletion
): WorkflowPlanUi {
    val advancedStepIndex = maxOf(currentPlan.currentStepIndex, completion.matchedStepIndex + 1)
        .coerceIn(0, currentPlan.steps.size)
    val nextStatus = if (advancedStepIndex >= currentPlan.steps.size) {
        WorkflowPlanStatusUi.COMPLETED
    } else {
        WorkflowPlanStatusUi.EXECUTING
    }
    val updatedSignOffs = (currentPlan.stepSignOffs.filterNot { it.stepIndex == completion.matchedStepIndex } +
        WorkflowStepSignOffUi(
            stepIndex = completion.matchedStepIndex,
            step = completion.matchedStep,
            reportedStep = completion.reportedStep,
            resultSummary = completion.resultSummary,
            matchedEvidenceCount = completion.matchedEvidenceCount,
            totalEvidenceCount = completion.totalEvidenceCount,
            matchedToolNames = completion.matchedToolNames,
            matchedSessionHistorySessionIds = completion.matchedSessionHistorySessionIds,
            matchedSessionHistoryMessageReferences = completion.matchedSessionHistoryMessageReferences,
            signedOffAt = completion.signedOffAt
        )).sortedBy { it.stepIndex }
    return currentPlan.copy(
        status = nextStatus,
        currentStepIndex = advancedStepIndex,
        stepSignOffs = updatedSignOffs,
        nextStepHint = when (nextStatus) {
            WorkflowPlanStatusUi.COMPLETED ->
                "计划步骤已全部签收完成，可继续汇总结果或进入下一轮任务。最后签收：${completion.matchedStep}。"
            WorkflowPlanStatusUi.EXECUTING -> {
                val nextStep = currentPlan.steps.getOrNull(advancedStepIndex).orEmpty()
                buildString {
                    append("最近已签收：")
                    append(completion.matchedStep)
                    append("（证据 ")
                    append(completion.matchedEvidenceCount)
                    append('/')
                    append(completion.totalEvidenceCount)
                    append("）。")
                    if (nextStep.isNotBlank()) {
                        append(" 下一步：")
                        append(nextStep)
                    }
                }
            }
            WorkflowPlanStatusUi.BLOCKED -> currentPlan.nextStepHint
            WorkflowPlanStatusUi.READY -> currentPlan.nextStepHint
        }
    )
}

internal fun applyWorkflowPlanProgressTransition(
    state: SessionState,
    updatedPlan: WorkflowPlanUi
): SessionState {
    return state.copy(
        pendingWorkflowPlan = if (state.pendingWorkflowPlan != null) updatedPlan else null,
        canonicalWorkflowPlan = updatedPlan
    )
}

internal fun applyWorkflowExecutionSettlementTransition(
    state: SessionState,
    originalPlan: WorkflowPlanUi,
    executingPlan: WorkflowPlanUi
): SessionState {
    val updatedPlan = resolveWorkflowPlanAfterExecution(
        originalPlan = originalPlan,
        executingPlan = executingPlan,
        state = state
    )
    return applyParallelBatchWorkflowProgressTransition(
        applyWorkflowPlanProgressTransition(
        state = state,
        updatedPlan = updatedPlan
        )
    )
}
