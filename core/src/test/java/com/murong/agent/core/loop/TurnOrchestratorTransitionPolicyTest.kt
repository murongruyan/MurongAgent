package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnOrchestratorTransitionPolicyTest {

    @Test
    fun applyPlanExecutionStartTransition_setsExecutingPlanAsPendingAndCanonical() {
        val plan = WorkflowPlanUi(
            goal = "修复卡顿",
            summary = "按步骤执行",
            status = WorkflowPlanStatusUi.EXECUTING
        )

        val result = applyPlanExecutionStartTransition(
            state = SessionState(workflowPlanningInProgress = true),
            executingPlan = plan
        )

        assertEquals(plan, result.pendingWorkflowPlan)
        assertEquals(plan, result.canonicalWorkflowPlan)
        assertFalse(result.workflowPlanningInProgress)
    }

    @Test
    fun applyWorkflowPlanGenerationStartTransition_resetsPlanPromptStateForPlanning() {
        val result = applyWorkflowPlanGenerationStartTransition(
            SessionState(
                error = "old error",
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复卡顿",
                    summary = "旧计划"
                ),
                lastWorkflowFallback = WorkflowFallbackUi(message = "old fallback")
            )
        )

        assertTrue(result.workflowPlanningInProgress)
        assertTrue(result.isProcessing)
        assertNull(result.error)
        assertNull(result.pendingWorkflowPlan)
        assertNull(result.lastWorkflowFallback)
    }

    @Test
    fun applyWorkflowPlanGeneratedTransition_setsPendingAndCanonicalPlan() {
        val plan = WorkflowPlanUi(
            goal = "修复卡顿",
            summary = "按步骤执行"
        )

        val result = applyWorkflowPlanGeneratedTransition(
            state = SessionState(workflowPlanningInProgress = true),
            plan = plan
        )

        assertEquals(plan, result.pendingWorkflowPlan)
        assertEquals(plan, result.canonicalWorkflowPlan)
        assertFalse(result.workflowPlanningInProgress)
    }

    @Test
    fun applyWorkflowPlanGenerationFailureTransition_keepsProcessingStateButClearsPlanningFlag() {
        val result = applyWorkflowPlanGenerationFailureTransition(
            state = SessionState(
                isProcessing = true,
                workflowPlanningInProgress = true
            ),
            errorMessage = "⚠️ 生成执行计划失败"
        )

        assertTrue(result.isProcessing)
        assertEquals("⚠️ 生成执行计划失败", result.error)
        assertFalse(result.workflowPlanningInProgress)
    }

    @Test
    fun applyWorkflowFallbackClearedTransition_onlyClearsLastFallback() {
        val result = applyWorkflowFallbackClearedTransition(
            SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复卡顿",
                    summary = "按步骤执行"
                ),
                lastWorkflowFallback = WorkflowFallbackUi(message = "old fallback")
            )
        )

        assertNull(result.lastWorkflowFallback)
        assertEquals("修复卡顿", result.pendingWorkflowPlan?.goal)
    }

    @Test
    fun applyWorkflowPlanDismissedTransition_clearsPlanState() {
        val result = applyWorkflowPlanDismissedTransition(
            SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复卡顿",
                    summary = "按步骤执行"
                ),
                canonicalWorkflowPlan = WorkflowPlanUi(
                    goal = "修复卡顿",
                    summary = "按步骤执行"
                ),
                workflowPlanningInProgress = true
            )
        )

        assertNull(result.pendingWorkflowPlan)
        assertNull(result.canonicalWorkflowPlan)
        assertFalse(result.workflowPlanningInProgress)
    }

    @Test
    fun applyAutoRouteStartTransition_resetsRouteState() {
        val result = applyAutoRouteStartTransition(
            SessionState(
                error = "old error",
                autoRoutingInProgress = false,
                lastAutoRouteDecision = AutoRouteDecisionUi(
                    action = AutoRouteAction.PLAN,
                    reason = "old decision"
                ),
                lastWorkflowFallback = WorkflowFallbackUi(message = "old fallback")
            )
        )

        assertNull(result.error)
        assertTrue(result.autoRoutingInProgress)
        assertNull(result.lastAutoRouteDecision)
        assertNull(result.lastWorkflowFallback)
    }

    @Test
    fun applyAutoRouteClearedTransition_clearsDecisionAndFlag() {
        val result = applyAutoRouteClearedTransition(
            SessionState(
                autoRoutingInProgress = true,
                lastAutoRouteDecision = AutoRouteDecisionUi(
                    action = AutoRouteAction.CLARIFY,
                    reason = "need clarify"
                )
            )
        )

        assertFalse(result.autoRoutingInProgress)
        assertNull(result.lastAutoRouteDecision)
    }

    @Test
    fun applyAutoRouteFailureTransition_clearsDecisionAndError() {
        val result = applyAutoRouteFailureTransition(
            SessionState(
                error = "old error",
                autoRoutingInProgress = true,
                lastAutoRouteDecision = AutoRouteDecisionUi(
                    action = AutoRouteAction.DIRECT,
                    reason = "old"
                )
            )
        )

        assertNull(result.error)
        assertFalse(result.autoRoutingInProgress)
        assertNull(result.lastAutoRouteDecision)
    }

    @Test
    fun applyAutoRouteDecisionResolvedTransition_recordsDecisionAndStopsRouting() {
        val decision = AutoRouteDecisionUi(
            action = AutoRouteAction.PLAN,
            reason = "task is multi-step"
        )

        val result = applyAutoRouteDecisionResolvedTransition(
            state = SessionState(autoRoutingInProgress = true),
            decision = decision
        )

        assertFalse(result.autoRoutingInProgress)
        assertEquals(decision, result.lastAutoRouteDecision)
    }

    @Test
    fun applyAutoRouteLocalClarificationFallbackTransition_setsFallbackRequest() {
        val request = ClarificationRequestUi(
            goal = "修复卡顿",
            question = "还缺一个关键约束。"
        )

        val result = applyAutoRouteLocalClarificationFallbackTransition(
            state = SessionState(
                error = "old error",
                autoRoutingInProgress = true,
                lastAutoRouteDecision = AutoRouteDecisionUi(
                    action = AutoRouteAction.CLARIFY,
                    reason = "old"
                )
            ),
            request = request
        )

        assertNull(result.error)
        assertFalse(result.autoRoutingInProgress)
        assertNull(result.lastAutoRouteDecision)
        assertFalse(result.clarificationInProgress)
        assertEquals(request, result.pendingClarificationRequest)
    }

    @Test
    fun applyDirectExecutionResumeTransition_clearsClarificationAndKeepsPlanByDefault() {
        val plan = WorkflowPlanUi(
            goal = "修复卡顿",
            summary = "按步骤执行"
        )
        val result = applyDirectExecutionResumeTransition(
            state = SessionState(
                isProcessing = true,
                error = "some error",
                pendingWorkflowPlan = plan,
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复卡顿",
                    question = "需要先跑测试吗？"
                ),
                clarificationInProgress = true,
                autoRoutingInProgress = true
            )
        )

        assertFalse(result.isProcessing)
        assertEquals(plan, result.pendingWorkflowPlan)
        assertNull(result.pendingClarificationRequest)
        assertFalse(result.clarificationInProgress)
        assertFalse(result.autoRoutingInProgress)
        assertEquals("some error", result.error)
    }

    @Test
    fun applyClarificationGenerationStartTransition_resetsClarificationPromptState() {
        val result = applyClarificationGenerationStartTransition(
            SessionState(
                error = "old error",
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复卡顿",
                    question = "旧问题"
                ),
                lastWorkflowFallback = WorkflowFallbackUi(message = "old fallback")
            )
        )

        assertTrue(result.isProcessing)
        assertNull(result.error)
        assertTrue(result.clarificationInProgress)
        assertNull(result.pendingClarificationRequest)
        assertNull(result.lastWorkflowFallback)
    }

    @Test
    fun applyClarificationGeneratedTransition_setsPendingClarificationAndStopsGeneration() {
        val request = ClarificationRequestUi(
            goal = "修复卡顿",
            question = "需要先保留兼容逻辑吗？"
        )

        val result = applyClarificationGeneratedTransition(
            state = SessionState(clarificationInProgress = true),
            request = request
        )

        assertEquals(request, result.pendingClarificationRequest)
        assertFalse(result.clarificationInProgress)
    }

    @Test
    fun applyClarificationDismissedTransition_clearsPendingRequest() {
        val result = applyClarificationDismissedTransition(
            SessionState(
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复卡顿",
                    question = "旧问题"
                ),
                clarificationInProgress = true
            )
        )

        assertNull(result.pendingClarificationRequest)
        assertFalse(result.clarificationInProgress)
    }

    @Test
    fun applyClarificationAnswerSubmitStartTransition_marksClarificationProcessing() {
        val result = applyClarificationAnswerSubmitStartTransition(
            SessionState(
                error = "old error",
                lastWorkflowFallback = WorkflowFallbackUi(message = "old fallback")
            )
        )

        assertTrue(result.isProcessing)
        assertNull(result.error)
        assertTrue(result.clarificationInProgress)
        assertNull(result.lastWorkflowFallback)
    }

    @Test
    fun applyClarificationAnswerSubmitFailureTransition_setsFailureState() {
        val result = applyClarificationAnswerSubmitFailureTransition(
            state = SessionState(
                isProcessing = true,
                clarificationInProgress = true
            ),
            errorMessage = "⚠️ 未配置 API Key。请先到设置页完成模型配置。"
        )

        assertFalse(result.isProcessing)
        assertFalse(result.clarificationInProgress)
        assertEquals("⚠️ 未配置 API Key。请先到设置页完成模型配置。", result.error)
    }

    @Test
    fun applyClarificationFollowUpTransition_updatesRequestAndStopsProcessing() {
        val request = ClarificationRequestUi(
            goal = "修复卡顿",
            question = "还需要目标平台信息。"
        )

        val result = applyClarificationFollowUpTransition(
            state = SessionState(
                isProcessing = true,
                clarificationInProgress = true
            ),
            request = request
        )

        assertFalse(result.isProcessing)
        assertFalse(result.clarificationInProgress)
        assertEquals(request, result.pendingClarificationRequest)
    }

    @Test
    fun applyClarificationFollowUpLocalFallbackTransition_clearsErrorAndStopsProcessing() {
        val request = ClarificationRequestUi(
            goal = "修复卡顿",
            question = "还缺一个关键条件。"
        )

        val result = applyClarificationFollowUpLocalFallbackTransition(
            state = SessionState(
                isProcessing = true,
                clarificationInProgress = true,
                error = "old error"
            ),
            request = request
        )

        assertFalse(result.isProcessing)
        assertFalse(result.clarificationInProgress)
        assertNull(result.error)
        assertEquals(request, result.pendingClarificationRequest)
    }

    @Test
    fun applyClarificationLocalFallbackTransition_replacesRequestAndClearsError() {
        val request = ClarificationRequestUi(
            goal = "修复卡顿",
            question = "还缺一个关键条件。"
        )

        val result = applyClarificationLocalFallbackTransition(
            state = SessionState(
                error = "old error",
                clarificationInProgress = true
            ),
            request = request
        )

        assertNull(result.error)
        assertFalse(result.clarificationInProgress)
        assertEquals(request, result.pendingClarificationRequest)
    }

    @Test
    fun applyDirectExecutionResumeTransition_canClearPlanAndErrorForFallbackResume() {
        val result = applyDirectExecutionResumeTransition(
            state = SessionState(
                isProcessing = true,
                error = "some error",
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复卡顿",
                    summary = "按步骤执行"
                )
            ),
            clearPendingWorkflowPlan = true,
            clearError = true
        )

        assertFalse(result.isProcessing)
        assertNull(result.pendingWorkflowPlan)
        assertNull(result.error)
    }

    @Test
    fun applyExecutionInterruptionClarificationTransition_replacesPendingPlanWithClarification() {
        val request = ClarificationRequestUi(
            goal = "修复卡顿",
            question = "需要先保留兼容逻辑吗？"
        )
        val result = applyExecutionInterruptionClarificationTransition(
            state = SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复卡顿",
                    summary = "按步骤执行"
                ),
                workflowPlanningInProgress = true,
                clarificationInProgress = true,
                autoRoutingInProgress = true
            ),
            request = request
        )

        assertNull(result.pendingWorkflowPlan)
        assertFalse(result.workflowPlanningInProgress)
        assertEquals(request, result.pendingClarificationRequest)
        assertFalse(result.clarificationInProgress)
        assertFalse(result.autoRoutingInProgress)
    }

    @Test
    fun applyPendingApprovalClearedTransition_clearsOnlyPendingApproval() {
        val plan = WorkflowPlanUi(
            goal = "修复卡顿",
            summary = "按步骤执行"
        )
        val result = applyPendingApprovalClearedTransition(
            SessionState(
                pendingApproval = PendingApprovalUi(
                    toolName = "shell",
                    summary = "运行验证命令",
                    detail = "执行测试",
                    rawArgs = "{\"command\":\"./gradlew test\"}",
                    riskLevel = com.murong.agent.core.tool.ApprovalRiskLevel.HIGH
                ),
                pendingWorkflowPlan = plan
            )
        )

        assertNull(result.pendingApproval)
        assertEquals(plan, result.pendingWorkflowPlan)
    }

    @Test
    fun normalizeRestoredTurnOrchestratorState_clearsTransientControllerFlags() {
        val result = normalizeRestoredTurnOrchestratorState(
            SessionState(
                isProcessing = true,
                workflowPlanningInProgress = true,
                clarificationInProgress = true,
                autoRoutingInProgress = true,
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复卡顿",
                    summary = "按步骤执行"
                ),
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复卡顿",
                    question = "需要先跑测试吗？"
                )
            )
        )

        assertFalse(result.isProcessing)
        assertFalse(result.workflowPlanningInProgress)
        assertFalse(result.clarificationInProgress)
        assertFalse(result.autoRoutingInProgress)
        assertEquals("修复卡顿", result.pendingWorkflowPlan?.goal)
        assertEquals("修复卡顿", result.pendingClarificationRequest?.goal)
    }

    @Test
    fun buildApprovalDecisionTransitionPayload_marksExecutingPlanAsBlockable() {
        val payload = buildApprovalDecisionTransitionPayload(
            SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复卡顿",
                    summary = "按步骤执行",
                    status = WorkflowPlanStatusUi.EXECUTING
                )
            )
        )

        assertEquals("修复卡顿", payload.activeGoal)
        assertEquals(true, payload.shouldBlockWorkflowPlan)
    }

    @Test
    fun buildApprovalDecisionTransitionPayload_buildsApprovedResumePayloadForActivePlan() {
        val payload = buildApprovalDecisionTransitionPayload(
            SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复卡顿",
                    summary = "按步骤执行",
                    steps = listOf("收集 trace", "调整调度"),
                    currentStepIndex = 1,
                    status = WorkflowPlanStatusUi.EXECUTING
                )
            )
        )

        assertEquals("修复卡顿", payload.approvedResumePayload?.goal)
        assertEquals(WorkflowPlanStatusUi.EXECUTING, payload.approvedResumePayload?.resumePlanStatus)
        assertEquals("审批已通过，继续执行当前计划。下一步：调整调度", payload.approvedResumePayload?.nextStepHint)
        assertEquals("交互状态: 审批已通过，恢复执行目标：修复卡顿", payload.approvedResumePayload?.recoverySummary)
    }

    @Test
    fun applyApprovalDecisionTransition_whenRejected_blocksActivePlanAndSetsError() {
        val result = applyApprovalDecisionTransition(
            state = SessionState(
                pendingApproval = PendingApprovalUi(
                    toolName = "shell",
                    summary = "运行验证命令",
                    detail = "执行测试",
                    rawArgs = "{\"command\":\"./gradlew test\"}",
                    riskLevel = com.murong.agent.core.tool.ApprovalRiskLevel.HIGH
                ),
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复卡顿",
                    summary = "按步骤执行",
                    status = WorkflowPlanStatusUi.EXECUTING
                ),
                canonicalWorkflowPlan = WorkflowPlanUi(
                    goal = "修复卡顿",
                    summary = "按步骤执行",
                    status = WorkflowPlanStatusUi.EXECUTING
                )
            ),
            approved = false,
            payload = ApprovalDecisionTransitionPayload(
                activeGoal = "修复卡顿",
                shouldBlockWorkflowPlan = true
            ),
            rejectedSummary = "运行验证命令"
        )

        assertNull(result.pendingApproval)
        assertEquals(WorkflowPlanStatusUi.BLOCKED, result.pendingWorkflowPlan?.status)
        assertEquals(WorkflowPlanStatusUi.BLOCKED, result.canonicalWorkflowPlan?.status)
        assertEquals("执行所需审批已被拒绝：运行验证命令。请调整方案、重新规划，或确认后再重试。", result.pendingWorkflowPlan?.nextStepHint)
        assertEquals("⚠️ 审批已拒绝，当前目标：修复卡顿。运行验证命令", result.error)
    }

    @Test
    fun applyApprovalDecisionTransition_whenApproved_onlyClearsPendingApproval() {
        val plan = WorkflowPlanUi(
            goal = "修复卡顿",
            summary = "按步骤执行",
            steps = listOf("收集 trace", "调整调度"),
            currentStepIndex = 1,
            status = WorkflowPlanStatusUi.EXECUTING
        )
        val result = applyApprovalDecisionTransition(
            state = SessionState(
                pendingApproval = PendingApprovalUi(
                    toolName = "shell",
                    summary = "运行验证命令",
                    detail = "执行测试",
                    rawArgs = "{\"command\":\"./gradlew test\"}",
                    riskLevel = com.murong.agent.core.tool.ApprovalRiskLevel.HIGH
                ),
                pendingWorkflowPlan = plan,
                canonicalWorkflowPlan = plan
            ),
            approved = true,
            payload = buildApprovalDecisionTransitionPayload(
                SessionState(
                    pendingWorkflowPlan = plan,
                    canonicalWorkflowPlan = plan
                )
            ),
            rejectedSummary = "运行验证命令"
        )

        assertNull(result.pendingApproval)
        assertEquals(WorkflowPlanStatusUi.EXECUTING, result.pendingWorkflowPlan?.status)
        assertEquals("审批已通过，继续执行当前计划。下一步：调整调度", result.pendingWorkflowPlan?.nextStepHint)
        assertEquals(result.pendingWorkflowPlan, result.canonicalWorkflowPlan)
        assertNull(result.error)
    }
}
