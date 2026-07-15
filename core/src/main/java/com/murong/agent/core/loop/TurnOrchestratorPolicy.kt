package com.murong.agent.core.loop

internal enum class TurnOrchestratorStage {
    DIRECT_EXECUTION,
    PLAN_REVIEW,
    PLAN_EXECUTION,
    CLARIFICATION,
    APPROVAL_BLOCKED
}

internal data class TurnOrchestratorDecision(
    val stage: TurnOrchestratorStage,
    val allowWriteTools: Boolean,
    val readOnlyPlanModeDecision: ReadOnlyPlanModeDecision,
    val activeGoal: String? = null,
    val activePlan: WorkflowPlanUi? = null,
    val activeClarificationRequest: ClarificationRequestUi? = null,
    val pendingApproval: PendingApprovalUi? = null,
    val activeParallelBatchSnapshot: ActiveParallelBatchSnapshot? = null,
    val settledParallelBatchEvidenceSnapshot: SettledParallelBatchEvidenceSnapshot? = null
)

internal fun resolveTurnOrchestratorDecision(
    state: SessionState,
    requestedExecutionGoal: String? = null,
    forceWritableTools: Boolean
): TurnOrchestratorDecision {
    val normalizedGoal = requestedExecutionGoal?.trim().orEmpty()
    val readOnlyPlanModeDecision = resolveReadOnlyPlanModeDecision(
        state = state,
        forceWritableTools = forceWritableTools
    )
    val activePlan = resolveTurnOrchestratorActivePlan(
        state = state,
        normalizedGoal = normalizedGoal
    )
    val stage = when {
        state.pendingClarificationRequest != null -> TurnOrchestratorStage.CLARIFICATION
        state.pendingWorkflowPlan?.status == WorkflowPlanStatusUi.READY ->
            TurnOrchestratorStage.PLAN_REVIEW
        state.pendingApproval != null -> TurnOrchestratorStage.APPROVAL_BLOCKED
        activePlan != null && activePlan.status != WorkflowPlanStatusUi.READY ->
            TurnOrchestratorStage.PLAN_EXECUTION
        else -> TurnOrchestratorStage.DIRECT_EXECUTION
    }
    val activeGoal = when (stage) {
        TurnOrchestratorStage.CLARIFICATION -> state.pendingClarificationRequest?.goal
        TurnOrchestratorStage.PLAN_REVIEW,
        TurnOrchestratorStage.PLAN_EXECUTION -> activePlan?.goal
        TurnOrchestratorStage.APPROVAL_BLOCKED ->
            activePlan?.goal ?: normalizedGoal.takeIf { it.isNotBlank() }
        TurnOrchestratorStage.DIRECT_EXECUTION -> normalizedGoal.takeIf { it.isNotBlank() }
    }
    return TurnOrchestratorDecision(
        stage = stage,
        allowWriteTools = !readOnlyPlanModeDecision.enabled &&
            stage != TurnOrchestratorStage.APPROVAL_BLOCKED,
        readOnlyPlanModeDecision = readOnlyPlanModeDecision,
        activeGoal = activeGoal,
        activePlan = activePlan,
        activeClarificationRequest = state.pendingClarificationRequest,
        pendingApproval = state.pendingApproval,
        activeParallelBatchSnapshot = resolveActiveParallelBatchSnapshot(state),
        settledParallelBatchEvidenceSnapshot = resolveSettledParallelBatchEvidenceSnapshot(state)
    )
}

internal fun buildTurnOrchestratorContext(
    decision: TurnOrchestratorDecision
): String? {
    val goal = decision.activeGoal?.trim().orEmpty()
    return when (decision.stage) {
        TurnOrchestratorStage.DIRECT_EXECUTION -> null
        TurnOrchestratorStage.PLAN_REVIEW -> {
            val plan = decision.activePlan ?: return null
            buildString {
                appendLine("Turn Orchestrator:")
                appendLine("Stage: plan_review")
                appendLine("Primary Goal: ${plan.goal}")
                appendLine("Workflow Status: ${plan.status.name.lowercase()}")
                appendLine("A workflow plan is pending review or execution. Stay in read-only planning mode until the user executes or dismisses the plan.")
                plan.nextStepHint.trim().takeIf { it.isNotBlank() }?.let { hint ->
                    append("Plan Hint: $hint")
                }
            }.trim()
        }
        TurnOrchestratorStage.PLAN_EXECUTION -> {
            val plan = decision.activePlan ?: return null
            buildString {
                appendLine("Turn Orchestrator:")
                appendLine("Stage: plan_execution")
                appendLine("Primary Goal: ${plan.goal}")
                appendLine("Workflow Status: ${plan.status.name.lowercase()}")
                appendLine("Current Step Index: ${plan.currentStepIndex}/${plan.steps.size}")
                plan.nextStepHint.trim().takeIf { it.isNotBlank() }?.let { hint ->
                    appendLine("Next Step Hint: $hint")
                }
                decision.activeParallelBatchSnapshot?.let { snapshot ->
                    appendLine("Active Parallel Batches: ${snapshot.activeBatchCount}")
                    if (snapshot.labels.isNotEmpty()) {
                        appendLine("Batch Labels: ${snapshot.labels.joinToString(" / ")}")
                    }
                    appendLine("Parallel status: completed=${snapshot.completedRunCount}/${snapshot.totalRunCount}, running=${snapshot.runningRunCount}, queued=${snapshot.queuedRunCount}")
                    appendLine("Controller mode: keep the canonical workflow aligned with the current step, but do not call `complete_step` until the active parallel subagent batches have fully settled and their evidence has been synthesized.")
                } ?: decision.settledParallelBatchEvidenceSnapshot?.let { settled ->
                    settled.latestLabel?.let { label ->
                        appendLine("Recent Settled Parallel Batch: $label")
                    }
                    if (settled.latestStatus.isNotBlank()) {
                        appendLine("Settled Batch Status: ${settled.latestStatus}")
                    }
                    settled.latestSummaryPreview?.let { preview ->
                        appendLine("Settled Batch Summary: $preview")
                    }
                    appendLine(
                        if (settled.failedBatchCount > 0) {
                            "Controller mode: recent parallel work settled with unresolved failures; verify whether failed sub tasks need retry or explicit acceptance before calling `complete_step`."
                        } else {
                            "Controller mode: recent parallel work has settled; synthesize those results into real evidence before calling `complete_step`."
                        }
                    )
                } ?: appendLine("Controller mode: continue the canonical workflow, keep tool work aligned with the current step, and call `complete_step` with real evidence when a step is actually finished.")
            }.trim()
        }
        TurnOrchestratorStage.CLARIFICATION -> {
            val request = decision.activeClarificationRequest ?: return null
            buildString {
                appendLine("Turn Orchestrator:")
                appendLine("Stage: clarification")
                appendLine("Primary Goal: ${request.goal}")
                appendLine("A clarification request is active for this goal. Use the latest follow-up answer to reduce ambiguity, then resume the same execution goal instead of branching into a new task.")
                append("Clarification Question: ${request.question}")
            }.trim()
        }
        TurnOrchestratorStage.APPROVAL_BLOCKED -> {
            val approval = decision.pendingApproval ?: return null
            buildString {
                appendLine("Turn Orchestrator:")
                appendLine("Stage: approval_blocked")
                decision.activeGoal?.takeIf { it.isNotBlank() }?.let { activeGoal ->
                    appendLine("Primary Goal: $activeGoal")
                }
                appendLine("Execution is currently blocked by a pending approval request.")
                appendLine("Pending Tool: ${approval.toolName}")
                append("Controller mode: do not bypass the approval boundary; continue with analysis only until the approval is resolved.")
            }.trim()
        }
    }
}

private fun resolveTurnOrchestratorActivePlan(
    state: SessionState,
    normalizedGoal: String
): WorkflowPlanUi? {
    val pendingPlan = state.pendingWorkflowPlan
    if (pendingPlan != null) return pendingPlan
    val canonicalPlan = state.canonicalWorkflowPlan ?: return null
    if (normalizedGoal.isBlank()) return null
    return canonicalPlan.takeIf { plan ->
        plan.goal.trim().equals(normalizedGoal, ignoreCase = true)
    }
}
