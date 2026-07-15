package com.murong.agent.ui.chat

import com.murong.agent.core.loop.RuntimeStatusKind
import com.murong.agent.core.loop.RuntimeStatusSnapshot
import com.murong.agent.core.config.WorkflowExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupplementalRuntimeStatusPresentationTest {

    @Test
    fun buildSupplementalRuntimeStatusPresentations_returnsSecondaryNoticesWhenHigherPriorityStatusIsActive() {
        val presentations = buildSupplementalRuntimeStatusPresentations(
            workflowExecutionMode = WorkflowExecutionMode.SINGLE_PASS,
            autoRouteBeforeExecution = true,
            autoRoutingInProgress = true,
            workflowPlanningInProgress = true,
            clarificationInProgress = true,
            runtimeStatusSnapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.PENDING_WORKFLOW_PLAN,
                title = "等待计划确认",
                message = "pending"
            )
        )

        assertEquals(listOf("自动分流", "执行计划", "澄清问题"), presentations.map { it.title })
        assertTrue(presentations[0].message.contains("直接执行"))
        assertTrue(presentations[1].message.contains("稍后可确认后一次性执行"))
        assertTrue(presentations[2].message.contains("回答后会继续执行"))
    }

    @Test
    fun buildSupplementalRuntimeStatusPresentations_hidesNoticeWhenSameRuntimeKindIsPrimary() {
        val presentations = buildSupplementalRuntimeStatusPresentations(
            workflowExecutionMode = WorkflowExecutionMode.SINGLE_PASS,
            autoRouteBeforeExecution = true,
            autoRoutingInProgress = true,
            workflowPlanningInProgress = true,
            clarificationInProgress = true,
            runtimeStatusSnapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.WORKFLOW_PLANNING,
                title = "执行计划",
                message = "planning"
            )
        )

        assertEquals(listOf("自动分流", "澄清问题"), presentations.map { it.title })
    }

    @Test
    fun buildSupplementalRuntimeStatusPresentations_skipsAutoRoutingWhenDisabled() {
        val presentations = buildSupplementalRuntimeStatusPresentations(
            workflowExecutionMode = WorkflowExecutionMode.SINGLE_PASS,
            autoRouteBeforeExecution = false,
            autoRoutingInProgress = true,
            workflowPlanningInProgress = false,
            clarificationInProgress = false,
            runtimeStatusSnapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.EXECUTING,
                title = "处理中",
                message = "running"
            )
        )

        assertTrue(presentations.isEmpty())
    }
}
