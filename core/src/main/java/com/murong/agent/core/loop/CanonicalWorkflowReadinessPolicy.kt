package com.murong.agent.core.loop

internal fun buildCanonicalWorkflowReadinessFailure(
    canonicalPlan: WorkflowPlanUi?,
    executionGoal: String
): String? {
    return buildCanonicalWorkflowReadinessReceipt(
        canonicalPlan = canonicalPlan,
        executionGoal = executionGoal
    )?.message
}

internal fun shouldApplyCanonicalWorkflowReadiness(
    plan: WorkflowPlanUi,
    executionGoal: String
): Boolean {
    if (!plan.goal.trim().equals(executionGoal.trim(), ignoreCase = true)) return false
    if (plan.steps.isEmpty()) return false
    return resolveCanonicalWorkflowUnsignedStepIndices(plan).isNotEmpty()
}

internal fun resolveCanonicalWorkflowUnsignedStepIndices(plan: WorkflowPlanUi): List<Int> {
    if (plan.steps.isEmpty()) return emptyList()
    val signedOffStepIndices = plan.stepSignOffs
        .mapNotNull { signOff ->
            signOff.stepIndex.takeIf { it in plan.steps.indices }
        }
        .toSet()
    return plan.steps.indices.filterNot(signedOffStepIndices::contains)
}

internal fun resolveCanonicalWorkflowNextRequiredStep(
    plan: WorkflowPlanUi,
    unsignedStepIndices: List<Int>
): String {
    val preferredIndex = unsignedStepIndices.firstOrNull { it >= plan.currentStepIndex }
        ?: unsignedStepIndices.firstOrNull()
    return preferredIndex?.let(plan.steps::get)
        ?: plan.nextStepHint.ifBlank { "继续推进剩余计划步骤" }
}
