package com.murong.agent.ui.chat

import com.murong.agent.core.loop.RuntimeStatusKind
import com.murong.agent.core.loop.RuntimeStatusSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingPromptRuntimePresentationTest {

    @Test
    fun buildPendingPromptRuntimePresentation_whenPendingAsk_usesAskPresentationSummary() {
        val presentation = buildPendingPromptRuntimePresentation(
            runtimeStatusSnapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.PENDING_ASK,
                title = "等待回答",
                message = "raw"
            ),
            askPresentation = pendingAsk().toAskPromptPresentation(),
            workflowPlanPresentation = null,
            clarificationPresentation = null
        )

        assertEquals("等待回答", presentation!!.title)
        assertEquals("问", presentation.badge)
        assertTrue(presentation.message.contains("需要确认"))
        assertTrue(presentation.message.contains("共 2 题"))
    }

    @Test
    fun buildPendingPromptRuntimePresentation_whenPendingWorkflowPlan_usesWorkflowPresentationSummary() {
        val presentation = buildPendingPromptRuntimePresentation(
            runtimeStatusSnapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.PENDING_WORKFLOW_PLAN,
                title = "等待计划确认",
                message = "raw"
            ),
            askPresentation = null,
            workflowPlanPresentation = workflowPlan().toWorkflowPlanPromptPresentation(),
            clarificationPresentation = null
        )

        assertEquals("等待计划确认", presentation!!.title)
        assertEquals("计", presentation.badge)
        assertTrue(presentation.message.contains("完成 shared host 收口"))
        assertTrue(presentation.message.contains("步骤 1/2"))
        assertTrue(presentation.message.contains("最近历史线索"))
        assertTrue(presentation.message.contains("session-login#21"))
    }

    @Test
    fun buildPendingPromptRuntimePresentation_whenPendingClarification_usesClarificationPresentationSummary() {
        val presentation = buildPendingPromptRuntimePresentation(
            runtimeStatusSnapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.PENDING_CLARIFICATION,
                title = "等待澄清回答",
                message = "raw"
            ),
            askPresentation = null,
            workflowPlanPresentation = null,
            clarificationPresentation = clarificationRequest().toClarificationPromptPresentation()
        )

        assertEquals("等待澄清回答", presentation!!.title)
        assertEquals("澄", presentation.badge)
        assertTrue(presentation.message.contains("优先推进哪条 source 下沉"))
        assertTrue(presentation.message.contains("第 2 轮 / 最多 3 轮"))
        assertTrue(presentation.message.contains("最近历史线索"))
        assertTrue(presentation.message.contains("session-release#34"))
    }

    @Test
    fun buildPendingPromptRuntimePresentation_whenRuntimeKindDoesNotMatch_returnsNull() {
        val presentation = buildPendingPromptRuntimePresentation(
            runtimeStatusSnapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.EXECUTING,
                title = "处理中",
                message = "raw"
            ),
            askPresentation = pendingAsk().toAskPromptPresentation(),
            workflowPlanPresentation = workflowPlan().toWorkflowPlanPromptPresentation(),
            clarificationPresentation = clarificationRequest().toClarificationPromptPresentation()
        )

        assertNull(presentation)
    }

    private fun pendingAsk() = AskPromptPresentationTestFixtures.pendingAsk()

    private fun workflowPlan() = PendingExecutionPresentationTestFixtures.workflowPlan()

    private fun clarificationRequest() = PendingExecutionPresentationTestFixtures.clarificationRequest()
}
