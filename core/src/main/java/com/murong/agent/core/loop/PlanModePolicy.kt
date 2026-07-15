package com.murong.agent.core.loop

enum class ReadOnlyPlanModeReason {
    PENDING_WORKFLOW_PLAN,
    PENDING_CLARIFICATION,
    NONE
}

data class ReadOnlyPlanModeDecision(
    val enabled: Boolean,
    val reason: ReadOnlyPlanModeReason
)

internal fun resolveReadOnlyPlanModeDecision(
    state: SessionState,
    forceWritableTools: Boolean
): ReadOnlyPlanModeDecision {
    if (forceWritableTools) {
        return ReadOnlyPlanModeDecision(
            enabled = false,
            reason = ReadOnlyPlanModeReason.NONE
        )
    }
    return when {
        state.pendingClarificationRequest != null -> ReadOnlyPlanModeDecision(
            enabled = true,
            reason = ReadOnlyPlanModeReason.PENDING_CLARIFICATION
        )
        state.pendingWorkflowPlan?.status == WorkflowPlanStatusUi.READY -> ReadOnlyPlanModeDecision(
            enabled = true,
            reason = ReadOnlyPlanModeReason.PENDING_WORKFLOW_PLAN
        )
        else -> ReadOnlyPlanModeDecision(
            enabled = false,
            reason = ReadOnlyPlanModeReason.NONE
        )
    }
}

internal fun buildReadOnlyPlanModeContext(decision: ReadOnlyPlanModeDecision): String? {
    if (!decision.enabled) return null
    val reason = when (decision.reason) {
        ReadOnlyPlanModeReason.PENDING_CLARIFICATION ->
            "There is a pending clarification request. Stay in read-only analysis mode until the clarification is answered or dismissed."
        ReadOnlyPlanModeReason.PENDING_WORKFLOW_PLAN ->
            "There is a confirmed workflow plan waiting to be executed. Stay in read-only planning mode until the user executes or dismisses the pending plan."
        ReadOnlyPlanModeReason.NONE ->
            "Stay in read-only planning mode."
    }
    return buildString {
        appendLine("Planning Mode:")
        appendLine(reason)
        appendLine("Writable tools are disabled in this turn. You may inspect, search, read, summarize, or prepare the next action, but do not modify files, branches, PRs, or run write-capable shell commands.")
    }.trim()
}
