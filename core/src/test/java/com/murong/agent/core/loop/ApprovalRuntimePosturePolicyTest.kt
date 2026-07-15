package com.murong.agent.core.loop

import com.murong.agent.core.config.ApprovalPosture
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.ToolApprovalMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalRuntimePosturePolicyTest {

    @Test
    fun resolveApprovalRuntimePostureDecision_prefersReusableScopeBeforeOtherPaths() {
        val decision = resolveApprovalRuntimePostureDecision(
            effectiveMode = ToolApprovalMode.ALL_APPROVAL,
            hasReusableScope = true,
            hasAutoInheritedScope = true,
            approvalRequirement = ProviderConfig.ApprovalDecisionExplanation(
                requiresApproval = true,
                explanationLabel = "全部审批",
                explanationDetail = "所有工具调用都需要人工确认。"
            ),
            planWindowDecision = PlanApprovalAutoApproveDecision(
                autoApprove = true,
                label = "计划审批窗口放行",
                detail = "计划执行窗口内可直接继续。"
            ),
            promptDecision = PromptRuntimePostureDecision(
                context = resolveApprovalRuntimePostureContext(ToolApprovalMode.ALL_APPROVAL),
                requestedKind = PendingPromptKind.APPROVAL,
                blocked = true,
                explanationLabel = "待提问卡片占用中",
                explanationDetail = "新的审批请求不能并行弹出。"
            )
        )

        assertEquals(ApprovalRuntimeDecisionKind.REUSE_EXISTING_SCOPE, decision.kind)
        assertTrue(decision.allowsExecution)
        assertEquals(ApprovalPosture.ASK, decision.context.posture)
    }

    @Test
    fun resolveApprovalRuntimePostureDecision_prefersPlanWindowBeforeSerializationBlock() {
        val decision = resolveApprovalRuntimePostureDecision(
            effectiveMode = ToolApprovalMode.WHITELIST_AUTO,
            hasReusableScope = false,
            hasAutoInheritedScope = false,
            approvalRequirement = ProviderConfig.ApprovalDecisionExplanation(
                requiresApproval = true,
                explanationLabel = "关键工具始终审批",
                explanationDetail = "关键配置仍需 fresh approval。"
            ),
            planWindowDecision = PlanApprovalAutoApproveDecision(
                autoApprove = true,
                label = "计划审批窗口放行",
                detail = "计划执行窗口内的低层操作可直接继续。"
            ),
            promptDecision = PromptRuntimePostureDecision(
                context = resolveApprovalRuntimePostureContext(ToolApprovalMode.WHITELIST_AUTO),
                requestedKind = PendingPromptKind.APPROVAL,
                blocked = true,
                explanationLabel = "待提问卡片占用中",
                explanationDetail = "新的审批请求不能并行弹出。"
            )
        )

        assertEquals(ApprovalRuntimeDecisionKind.AUTO_APPROVE_BY_PLAN_WINDOW, decision.kind)
        assertTrue(decision.allowsExecution)
        assertEquals("计划审批窗口放行", decision.explanationLabel)
    }

    @Test
    fun resolveApprovalRuntimePostureDecision_blocksBySerializationBeforeManualApproval() {
        val decision = resolveApprovalRuntimePostureDecision(
            effectiveMode = ToolApprovalMode.ALL_APPROVAL,
            hasReusableScope = false,
            hasAutoInheritedScope = false,
            approvalRequirement = ProviderConfig.ApprovalDecisionExplanation(
                requiresApproval = true,
                explanationLabel = "全部审批",
                explanationDetail = "当前审批模式为全部审批。"
            ),
            planWindowDecision = PlanApprovalAutoApproveDecision(autoApprove = false),
            promptDecision = PromptRuntimePostureDecision(
                context = resolveApprovalRuntimePostureContext(ToolApprovalMode.ALL_APPROVAL),
                requestedKind = PendingPromptKind.APPROVAL,
                blocked = true,
                explanationLabel = "待提问卡片占用中",
                explanationDetail = "ask_user 尚未处理完成。"
            )
        )

        assertEquals(ApprovalRuntimeDecisionKind.BLOCKED_BY_PROMPT_SERIALIZATION, decision.kind)
        assertTrue(decision.blockedByPromptSerialization)
        assertFalse(decision.allowsExecution)
        assertEquals("ask_user 尚未处理完成。", decision.explanationDetail)
    }

    @Test
    fun resolvePromptRuntimePostureDecision_keepsSerializationResultAndPostureContext() {
        val decision = resolvePromptRuntimePostureDecision(
            effectiveMode = ToolApprovalMode.ALL_AUTO,
            requestedKind = PendingPromptKind.ASK,
            hasPendingApproval = true,
            hasPendingAsk = false
        )

        assertTrue(decision.blocked)
        assertEquals(ApprovalPosture.YOLO, decision.context.posture)
        assertTrue(decision.explanationDetail!!.contains("ask_user"))
        assertTrue(decision.explanationDetail.contains("审批请求"))
    }
}
