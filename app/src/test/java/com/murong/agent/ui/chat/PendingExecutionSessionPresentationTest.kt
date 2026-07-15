package com.murong.agent.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingExecutionSessionPresentationTest {

    @Test
    fun buildWorkflowPlanSessionPresentation_projectsToggleAndActionState() {
        val presentation = PendingExecutionPresentationTestFixtures
            .workflowPlan()
            .toWorkflowPlanPromptPresentation()
        val session = buildWorkflowPlanSessionPresentation(
            presentation = presentation,
            interactionState = WorkflowPlanInteractionState(showRawPlan = true),
            isProcessing = false
        )

        assertEquals("最近历史线索", presentation.recentHistoryClue?.title)
        assertTrue(presentation.recentHistoryClue?.summary?.contains("登录态过期后需要补 token 刷新") == true)
        assertTrue(session.showRawPlan)
        assertEquals("收起原始计划", session.rawPlanToggleLabel)
        assertEquals("1. 收口 source\n2. 宿主统一消费", session.rawPlanContent)
        val recentHistorySummary = requireNotNull(session.recentHistorySummary)
        assertTrue(recentHistorySummary.contains("最近历史线索"))
        assertTrue(recentHistorySummary.contains("session-login#21"))
        assertEquals("关闭", session.dismissLabel)
        assertEquals("按计划执行", session.executeAction.label)
        assertTrue(session.executeAction.enabled)
        assertEquals("待执行 · 步骤 1/2", session.status.summaryLabel)
        assertTrue(session.status.guidance.contains("确认后会按当前步骤继续执行"))
        assertNull(session.status.disabledHint)
    }

    @Test
    fun buildWorkflowPlanSessionPresentation_blocksActionWhileProcessing() {
        val session = buildWorkflowPlanSessionPresentation(
            presentation = PendingExecutionPresentationTestFixtures
                .workflowPlan()
                .toWorkflowPlanPromptPresentation(),
            interactionState = buildInitialWorkflowPlanInteractionState(),
            isProcessing = true
        )

        assertFalse(session.showRawPlan)
        assertEquals("展开原始计划", session.rawPlanToggleLabel)
        assertNull(session.rawPlanContent)
        assertFalse(session.executeAction.enabled)
        assertEquals("当前正在处理上一条动作，暂时不能再次操作这张计划卡片。", session.status.disabledHint)
    }

    @Test
    fun buildClarificationSessionPresentation_trimsAnswerAndEnablesSubmit() {
        val presentation = PendingExecutionPresentationTestFixtures
            .clarificationRequest()
            .toClarificationPromptPresentation()
        val session = buildClarificationSessionPresentation(
            presentation = presentation,
            interactionState = ClarificationInteractionState(answer = "  先补 source 下沉  "),
            isProcessing = false
        )

        assertEquals("最近历史线索", presentation.recentHistoryClue?.title)
        assertTrue(presentation.recentHistoryClue?.summary?.contains("发布 smoke test 需要覆盖登录校验") == true)
        assertEquals("  先补 source 下沉  ", session.draftAnswer)
        assertEquals("先补 source 下沉", session.submitAnswer)
        val recentHistorySummary = requireNotNull(session.recentHistorySummary)
        assertTrue(recentHistorySummary.contains("最近历史线索"))
        assertTrue(recentHistorySummary.contains("session-release#34"))
        assertEquals("关闭", session.dismissLabel)
        assertTrue(session.submitAction.enabled)
        assertEquals("继续判断", session.submitAction.label)
        assertEquals("第 2 轮 / 最多 3 轮", session.status.summaryLabel)
        assertTrue(session.status.guidance.contains("提交后会沿当前问题继续判断"))
        assertNull(session.status.disabledHint)
        assertEquals("先补 source 下沉", consumeClarificationSubmitAnswer(session))
    }

    @Test
    fun buildClarificationSessionPresentation_surfacesDisabledReasons() {
        val blankSession = buildClarificationSessionPresentation(
            presentation = PendingExecutionPresentationTestFixtures
                .clarificationRequest()
                .toClarificationPromptPresentation(),
            interactionState = ClarificationInteractionState(answer = "   "),
            isProcessing = false
        )
        val processingSession = buildClarificationSessionPresentation(
            presentation = PendingExecutionPresentationTestFixtures
                .clarificationRequest()
                .toClarificationPromptPresentation(),
            interactionState = ClarificationInteractionState(answer = "继续"),
            isProcessing = true
        )

        assertFalse(blankSession.submitAction.enabled)
        assertEquals("先输入回答内容，才能继续判断。", blankSession.status.disabledHint)
        assertNull(consumeClarificationSubmitAnswer(blankSession))
        assertFalse(processingSession.submitAction.enabled)
        assertEquals("当前正在处理上一条动作，暂时不能提交新的澄清回答。", processingSession.status.disabledHint)
        assertNull(consumeClarificationSubmitAnswer(processingSession))
    }

    @Test
    fun pendingExecutionInteractionReducers_toggleRawPlanAndTrackClarificationAnswer() {
        val workflowPresentation = PendingExecutionPresentationTestFixtures
            .workflowPlan()
            .toWorkflowPlanPromptPresentation()

        val toggled = toggleWorkflowPlanRawPlan(
            state = buildInitialWorkflowPlanInteractionState(),
            presentation = workflowPresentation
        )
        val updatedAnswer = updateClarificationAnswer(
            state = buildInitialClarificationInteractionState(),
            answer = "继续补信息"
        )

        assertTrue(toggled.showRawPlan)
        assertEquals("继续补信息", updatedAnswer.answer)
    }
}
