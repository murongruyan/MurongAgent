package com.murong.agent.core.loop

import com.murong.agent.core.config.ApprovalPosture
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.core.config.toApprovalModePresentation

internal data class ApprovalRuntimePostureContext(
    val effectiveMode: ToolApprovalMode,
    val posture: ApprovalPosture
)

internal enum class ApprovalRuntimeDecisionKind {
    REUSE_EXISTING_SCOPE,
    AUTO_INHERIT_SCOPE,
    AUTO_APPROVE_BY_POSTURE,
    AUTO_APPROVE_BY_PLAN_WINDOW,
    BLOCKED_BY_PROMPT_SERIALIZATION,
    REQUIRE_MANUAL_APPROVAL
}

internal data class ApprovalRuntimePostureDecision(
    val context: ApprovalRuntimePostureContext,
    val kind: ApprovalRuntimeDecisionKind,
    val explanationLabel: String? = null,
    val explanationDetail: String? = null
) {
    val allowsExecution: Boolean
        get() = when (kind) {
            ApprovalRuntimeDecisionKind.REUSE_EXISTING_SCOPE,
            ApprovalRuntimeDecisionKind.AUTO_INHERIT_SCOPE,
            ApprovalRuntimeDecisionKind.AUTO_APPROVE_BY_POSTURE,
            ApprovalRuntimeDecisionKind.AUTO_APPROVE_BY_PLAN_WINDOW -> true

            ApprovalRuntimeDecisionKind.BLOCKED_BY_PROMPT_SERIALIZATION,
            ApprovalRuntimeDecisionKind.REQUIRE_MANUAL_APPROVAL -> false
        }

    val blockedByPromptSerialization: Boolean
        get() = kind == ApprovalRuntimeDecisionKind.BLOCKED_BY_PROMPT_SERIALIZATION

    val requiresManualApproval: Boolean
        get() = kind == ApprovalRuntimeDecisionKind.REQUIRE_MANUAL_APPROVAL
}

internal data class PromptRuntimePostureDecision(
    val context: ApprovalRuntimePostureContext,
    val requestedKind: PendingPromptKind,
    val blocked: Boolean,
    val explanationLabel: String? = null,
    val explanationDetail: String? = null
)

internal fun resolveApprovalRuntimePostureContext(
    effectiveMode: ToolApprovalMode
): ApprovalRuntimePostureContext {
    return ApprovalRuntimePostureContext(
        effectiveMode = effectiveMode,
        posture = effectiveMode.toApprovalModePresentation().posture
    )
}

internal fun resolvePromptRuntimePostureDecision(
    effectiveMode: ToolApprovalMode,
    requestedKind: PendingPromptKind,
    hasPendingApproval: Boolean,
    hasPendingAsk: Boolean
): PromptRuntimePostureDecision {
    val context = resolveApprovalRuntimePostureContext(effectiveMode)
    val serializationDecision = resolvePromptSerializationDecision(
        requestedKind = requestedKind,
        hasPendingApproval = hasPendingApproval,
        hasPendingAsk = hasPendingAsk
    )
    return PromptRuntimePostureDecision(
        context = context,
        requestedKind = requestedKind,
        blocked = serializationDecision.blocked,
        explanationLabel = serializationDecision.label,
        explanationDetail = serializationDecision.detail
    )
}

internal fun resolveApprovalRuntimePostureDecision(
    effectiveMode: ToolApprovalMode,
    hasReusableScope: Boolean,
    hasAutoInheritedScope: Boolean,
    approvalRequirement: ProviderConfig.ApprovalDecisionExplanation,
    planWindowDecision: PlanApprovalAutoApproveDecision,
    promptDecision: PromptRuntimePostureDecision
): ApprovalRuntimePostureDecision {
    val context = resolveApprovalRuntimePostureContext(effectiveMode)
    return when {
        hasReusableScope -> ApprovalRuntimePostureDecision(
            context = context,
            kind = ApprovalRuntimeDecisionKind.REUSE_EXISTING_SCOPE
        )

        hasAutoInheritedScope -> ApprovalRuntimePostureDecision(
            context = context,
            kind = ApprovalRuntimeDecisionKind.AUTO_INHERIT_SCOPE
        )

        !approvalRequirement.requiresApproval -> ApprovalRuntimePostureDecision(
            context = context,
            kind = ApprovalRuntimeDecisionKind.AUTO_APPROVE_BY_POSTURE,
            explanationLabel = approvalRequirement.explanationLabel,
            explanationDetail = approvalRequirement.explanationDetail
        )

        planWindowDecision.autoApprove -> ApprovalRuntimePostureDecision(
            context = context,
            kind = ApprovalRuntimeDecisionKind.AUTO_APPROVE_BY_PLAN_WINDOW,
            explanationLabel = planWindowDecision.label,
            explanationDetail = planWindowDecision.detail
        )

        promptDecision.blocked -> ApprovalRuntimePostureDecision(
            context = context,
            kind = ApprovalRuntimeDecisionKind.BLOCKED_BY_PROMPT_SERIALIZATION,
            explanationLabel = promptDecision.explanationLabel,
            explanationDetail = promptDecision.explanationDetail
        )

        else -> ApprovalRuntimePostureDecision(
            context = context,
            kind = ApprovalRuntimeDecisionKind.REQUIRE_MANUAL_APPROVAL,
            explanationLabel = approvalRequirement.explanationLabel,
            explanationDetail = approvalRequirement.explanationDetail
        )
    }
}
