package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanModePolicyTest {

    @Test
    fun resolveReadOnlyPlanModeDecision_enablesForPendingWorkflowPlan() {
        val decision = resolveReadOnlyPlanModeDecision(
            state = SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复问题",
                    summary = "先分析再执行",
                    status = WorkflowPlanStatusUi.READY
                )
            ),
            forceWritableTools = false
        )

        assertTrue(decision.enabled)
        assertEquals(ReadOnlyPlanModeReason.PENDING_WORKFLOW_PLAN, decision.reason)
    }

    @Test
    fun resolveReadOnlyPlanModeDecision_enablesForPendingClarification() {
        val decision = resolveReadOnlyPlanModeDecision(
            state = SessionState(
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复问题",
                    question = "要不要先跑测试？"
                )
            ),
            forceWritableTools = false
        )

        assertTrue(decision.enabled)
        assertEquals(ReadOnlyPlanModeReason.PENDING_CLARIFICATION, decision.reason)
    }

    @Test
    fun resolveReadOnlyPlanModeDecision_forceWritableOverridesPendingState() {
        val decision = resolveReadOnlyPlanModeDecision(
            state = SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复问题",
                    summary = "先分析再执行",
                    status = WorkflowPlanStatusUi.READY
                ),
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复问题",
                    question = "要不要先跑测试？"
                )
            ),
            forceWritableTools = true
        )

        assertFalse(decision.enabled)
        assertEquals(ReadOnlyPlanModeReason.NONE, decision.reason)
    }

    @Test
    fun buildReadOnlyPlanModeContext_includesReasonSpecificHint() {
        val context = buildReadOnlyPlanModeContext(
            ReadOnlyPlanModeDecision(
                enabled = true,
                reason = ReadOnlyPlanModeReason.PENDING_CLARIFICATION
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("Planning Mode:"))
        assertTrue(context.contains("pending clarification request"))
        assertTrue(context.contains("Writable tools are disabled"))
    }
}
