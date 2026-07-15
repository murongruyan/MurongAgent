package com.murong.agent.core.loop

internal data class ApprovalDecisionTransitionPayload(
    val activeGoal: String? = null,
    val shouldBlockWorkflowPlan: Boolean = false,
    val approvedResumePayload: ApprovalResumeExecutionPayload? = null
)

internal data class ApprovalResumeExecutionPayload(
    val goal: String,
    val resumePlanStatus: WorkflowPlanStatusUi,
    val nextStepHint: String,
    val recoverySummary: String
)

internal fun applyPlanExecutionStartTransition(
    state: SessionState,
    executingPlan: WorkflowPlanUi
): SessionState {
    return state.copy(
        pendingWorkflowPlan = executingPlan,
        canonicalWorkflowPlan = executingPlan,
        workflowPlanningInProgress = false
    )
}

internal fun applyWorkflowPlanGenerationStartTransition(
    state: SessionState
): SessionState {
    return state.copy(
        isProcessing = true,
        error = null,
        workflowPlanningInProgress = true,
        pendingWorkflowPlan = null,
        lastWorkflowFallback = null
    )
}

internal fun applyWorkflowPlanGeneratedTransition(
    state: SessionState,
    plan: WorkflowPlanUi
): SessionState {
    return state.copy(
        pendingWorkflowPlan = plan,
        canonicalWorkflowPlan = plan,
        workflowPlanningInProgress = false
    )
}

internal fun applyWorkflowPlanGenerationFailureTransition(
    state: SessionState,
    errorMessage: String
): SessionState {
    return state.copy(
        error = errorMessage,
        workflowPlanningInProgress = false
    )
}

internal fun applyWorkflowFallbackClearedTransition(
    state: SessionState
): SessionState {
    return state.copy(lastWorkflowFallback = null)
}

internal fun applyWorkflowPlanDismissedTransition(
    state: SessionState
): SessionState {
    return state.copy(
        pendingWorkflowPlan = null,
        canonicalWorkflowPlan = null,
        workflowPlanningInProgress = false
    )
}

internal fun applyAutoRouteStartTransition(
    state: SessionState
): SessionState {
    return state.copy(
        error = null,
        autoRoutingInProgress = true,
        lastAutoRouteDecision = null,
        lastWorkflowFallback = null
    )
}

internal fun applyAutoRouteClearedTransition(
    state: SessionState
): SessionState {
    return state.copy(
        autoRoutingInProgress = false,
        lastAutoRouteDecision = null
    )
}

internal fun applyAutoRouteFailureTransition(
    state: SessionState
): SessionState {
    return state.copy(
        error = null,
        autoRoutingInProgress = false,
        lastAutoRouteDecision = null
    )
}

internal fun applyAutoRouteDecisionResolvedTransition(
    state: SessionState,
    decision: AutoRouteDecisionUi
): SessionState {
    return state.copy(
        autoRoutingInProgress = false,
        lastAutoRouteDecision = decision
    )
}

internal fun applyAutoRouteLocalClarificationFallbackTransition(
    state: SessionState,
    request: ClarificationRequestUi
): SessionState {
    return state.copy(
        error = null,
        autoRoutingInProgress = false,
        lastAutoRouteDecision = null,
        clarificationInProgress = false,
        pendingClarificationRequest = request
    )
}

internal fun applyDirectExecutionResumeTransition(
    state: SessionState,
    clearPendingWorkflowPlan: Boolean = false,
    clearPendingClarification: Boolean = true,
    clearError: Boolean = false
): SessionState {
    return state.copy(
        isProcessing = false,
        error = if (clearError) null else state.error,
        pendingWorkflowPlan = if (clearPendingWorkflowPlan) null else state.pendingWorkflowPlan,
        workflowPlanningInProgress = false,
        pendingClarificationRequest =
            if (clearPendingClarification) null else state.pendingClarificationRequest,
        clarificationInProgress = false,
        autoRoutingInProgress = false
    )
}

internal fun applyExecutionInterruptionClarificationTransition(
    state: SessionState,
    request: ClarificationRequestUi
): SessionState {
    return state.copy(
        pendingWorkflowPlan = null,
        workflowPlanningInProgress = false,
        pendingClarificationRequest = request,
        clarificationInProgress = false,
        autoRoutingInProgress = false
    )
}

internal fun applyClarificationGenerationStartTransition(
    state: SessionState
): SessionState {
    return state.copy(
        isProcessing = true,
        error = null,
        clarificationInProgress = true,
        pendingClarificationRequest = null,
        lastWorkflowFallback = null
    )
}

internal fun applyClarificationGeneratedTransition(
    state: SessionState,
    request: ClarificationRequestUi
): SessionState {
    return state.copy(
        pendingClarificationRequest = request,
        clarificationInProgress = false
    )
}

internal fun applyClarificationLocalFallbackTransition(
    state: SessionState,
    request: ClarificationRequestUi
): SessionState {
    return state.copy(
        error = null,
        clarificationInProgress = false,
        pendingClarificationRequest = request
    )
}

internal fun applyClarificationDismissedTransition(
    state: SessionState
): SessionState {
    return state.copy(
        pendingClarificationRequest = null,
        clarificationInProgress = false
    )
}

internal fun applyClarificationAnswerSubmitStartTransition(
    state: SessionState
): SessionState {
    return state.copy(
        isProcessing = true,
        error = null,
        clarificationInProgress = true,
        lastWorkflowFallback = null
    )
}

internal fun applyClarificationAnswerSubmitFailureTransition(
    state: SessionState,
    errorMessage: String
): SessionState {
    return state.copy(
        isProcessing = false,
        clarificationInProgress = false,
        error = errorMessage
    )
}

internal fun applyClarificationFollowUpTransition(
    state: SessionState,
    request: ClarificationRequestUi
): SessionState {
    return state.copy(
        isProcessing = false,
        clarificationInProgress = false,
        pendingClarificationRequest = request
    )
}

internal fun applyClarificationFollowUpLocalFallbackTransition(
    state: SessionState,
    request: ClarificationRequestUi
): SessionState {
    return state.copy(
        isProcessing = false,
        clarificationInProgress = false,
        error = null,
        pendingClarificationRequest = request
    )
}

internal fun applyPendingApprovalClearedTransition(
    state: SessionState
): SessionState {
    return state.copy(
        pendingApproval = null
    )
}

internal fun normalizeRestoredTurnOrchestratorState(
    state: SessionState
): SessionState {
    return state.copy(
        isProcessing = false,
        workflowPlanningInProgress = false,
        clarificationInProgress = false,
        autoRoutingInProgress = false
    )
}

internal fun buildApprovalDecisionTransitionPayload(
    state: SessionState
): ApprovalDecisionTransitionPayload {
    val activePlan = state.pendingWorkflowPlan ?: state.canonicalWorkflowPlan
    val shouldBlockWorkflowPlan = activePlan != null &&
        activePlan.status != WorkflowPlanStatusUi.COMPLETED
    return ApprovalDecisionTransitionPayload(
        activeGoal = activePlan?.goal,
        shouldBlockWorkflowPlan = shouldBlockWorkflowPlan,
        approvedResumePayload = buildApprovalResumeExecutionPayload(activePlan)
    )
}

internal fun buildApprovalResumeExecutionPayload(
    activePlan: WorkflowPlanUi?
): ApprovalResumeExecutionPayload? {
    val plan = activePlan ?: return null
    val goal = plan.goal.trim().takeIf { it.isNotBlank() } ?: return null
    val nextStep = plan.steps.getOrNull(plan.currentStepIndex)?.trim().orEmpty()
    val existingHint = plan.nextStepHint.trim()
    val resumeHint = when {
        nextStep.isNotBlank() -> "审批已通过，继续执行当前计划。下一步：$nextStep"
        existingHint.isNotBlank() -> "审批已通过，继续执行当前计划。$existingHint"
        else -> "审批已通过，继续执行当前目标：$goal。"
    }
    val resumeStatus = when (plan.status) {
        WorkflowPlanStatusUi.READY -> WorkflowPlanStatusUi.READY
        WorkflowPlanStatusUi.COMPLETED -> WorkflowPlanStatusUi.COMPLETED
        WorkflowPlanStatusUi.EXECUTING,
        WorkflowPlanStatusUi.BLOCKED -> WorkflowPlanStatusUi.EXECUTING
    }
    return ApprovalResumeExecutionPayload(
        goal = goal,
        resumePlanStatus = resumeStatus,
        nextStepHint = resumeHint,
        recoverySummary = "交互状态: 审批已通过，恢复执行目标：$goal"
    )
}

internal fun applyApprovalDecisionTransition(
    state: SessionState,
    approved: Boolean,
    payload: ApprovalDecisionTransitionPayload,
    rejectedSummary: String
): SessionState {
    val cleared = applyPendingApprovalClearedTransition(state)
    if (approved) {
        val currentPlan = cleared.pendingWorkflowPlan ?: cleared.canonicalWorkflowPlan
        val resumePayload = payload.approvedResumePayload
        val resumedPlan = if (currentPlan != null && resumePayload != null) {
            currentPlan.copy(
                status = resumePayload.resumePlanStatus,
                nextStepHint = resumePayload.nextStepHint
            )
        } else {
            currentPlan
        }
        return cleared.copy(
            pendingWorkflowPlan = if (cleared.pendingWorkflowPlan != null) resumedPlan else null,
            canonicalWorkflowPlan = resumedPlan ?: cleared.canonicalWorkflowPlan,
            error = null
        )
    }
    if (!payload.shouldBlockWorkflowPlan) {
        return cleared
    }
    val currentPlan = cleared.pendingWorkflowPlan ?: cleared.canonicalWorkflowPlan
    val blockedHint = buildString {
        append("执行所需审批已被拒绝")
        rejectedSummary.trim().takeIf { it.isNotBlank() }?.let { summary ->
            append("：")
            append(summary)
        }
        append("。请调整方案、重新规划，或确认后再重试。")
    }
    val blockedPlan = currentPlan?.copy(
        status = WorkflowPlanStatusUi.BLOCKED,
        nextStepHint = blockedHint
    )
    return cleared.copy(
        error = buildString {
            append("⚠️ 审批已拒绝")
            payload.activeGoal?.trim()?.takeIf { it.isNotBlank() }?.let { goal ->
                append("，当前目标：")
                append(goal)
            }
            rejectedSummary.trim().takeIf { it.isNotBlank() }?.let { summary ->
                append("。")
                append(summary)
            }
        },
        pendingWorkflowPlan = if (cleared.pendingWorkflowPlan != null) blockedPlan else null,
        canonicalWorkflowPlan = blockedPlan ?: cleared.canonicalWorkflowPlan
    )
}
