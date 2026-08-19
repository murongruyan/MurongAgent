package com.murong.agent.core.loop

enum class ReadOnlyPlanModeReason {
    PENDING_WORKFLOW_PLAN,
    PENDING_CLARIFICATION,
    PLANNING_REQUEST,
    NONE
}

data class ReadOnlyPlanModeDecision(
    val enabled: Boolean,
    val reason: ReadOnlyPlanModeReason
)

internal fun resolveReadOnlyPlanModeDecision(
    state: SessionState,
    forceWritableTools: Boolean,
    planModeEnabled: Boolean = false
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
        // 计划模式开启且没有待确认计划/澄清时,本轮进入"先调查再出计划"的只读首轮,
        // 与电脑端计划模式行为对齐:模型先检查项目,再输出计划等待用户确认。
        planModeEnabled -> ReadOnlyPlanModeDecision(
            enabled = true,
            reason = ReadOnlyPlanModeReason.PLANNING_REQUEST
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
        ReadOnlyPlanModeReason.PLANNING_REQUEST ->
            "A new task is waiting for an execution plan. Investigate the project first (read files, search, understand the relevant code), then produce one concise, actionable, verifiable step plan. Do not modify files, run write-capable shell commands, or perform any other writes; your final reply this turn is the plan only, and execution starts only after the user confirms."
        ReadOnlyPlanModeReason.NONE ->
            "Stay in read-only planning mode."
    }
    return buildString {
        appendLine("Planning Mode:")
        appendLine(reason)
        appendLine("Writable tools are disabled in this turn. You may inspect, search, read, summarize, or prepare the next action, but do not modify files, branches, PRs, or run write-capable shell commands.")
    }.trim()
}
