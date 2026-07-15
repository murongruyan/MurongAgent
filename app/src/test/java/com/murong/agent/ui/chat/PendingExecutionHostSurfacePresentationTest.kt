package com.murong.agent.ui.chat

import com.murong.agent.core.loop.ClarificationRequestUi
import com.murong.agent.core.loop.ClarificationSource
import com.murong.agent.core.loop.WorkflowPlanStatusUi
import com.murong.agent.core.loop.WorkflowPlanUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingExecutionHostSurfacePresentationTest {

    @Test
    fun buildWorkflowPlanHostSurfacePresentation_whenNoPendingPlan_returnsNone() {
        val presentation = buildWorkflowPlanHostSurfacePresentation(
            workflowPlanPresentation = null,
            interactionState = WorkflowPlanInteractionState(showRawPlan = true),
            isChatScreenVisible = false
        )

        assertEquals(WorkflowPlanHostSurfaceKind.NONE, presentation.kind)
        assertNull(presentation.workflowPlanPresentation)
        assertEquals(true, presentation.interactionState.showRawPlan)
    }

    @Test
    fun buildWorkflowPlanHostSurfacePresentation_whenChatVisible_routesToInlineCard() {
        val workflowPlanPresentation = workflowPlan().toWorkflowPlanPromptPresentation()

        val presentation = buildWorkflowPlanHostSurfacePresentation(
            workflowPlanPresentation = workflowPlanPresentation,
            interactionState = WorkflowPlanInteractionState(showRawPlan = true),
            isChatScreenVisible = true
        )

        assertEquals(WorkflowPlanHostSurfaceKind.CHAT_INLINE, presentation.kind)
        assertEquals(workflowPlanPresentation, presentation.workflowPlanPresentation)
        assertEquals(true, presentation.interactionState.showRawPlan)
    }

    @Test
    fun buildWorkflowPlanHostSurfacePresentation_whenChatHidden_routesToDialog() {
        val workflowPlanPresentation = workflowPlan().toWorkflowPlanPromptPresentation()

        val presentation = buildWorkflowPlanHostSurfacePresentation(
            workflowPlanPresentation = workflowPlanPresentation,
            interactionState = WorkflowPlanInteractionState(showRawPlan = false),
            isChatScreenVisible = false
        )

        assertEquals(WorkflowPlanHostSurfaceKind.DIALOG, presentation.kind)
        assertEquals(workflowPlanPresentation, presentation.workflowPlanPresentation)
        assertEquals(false, presentation.interactionState.showRawPlan)
    }

    @Test
    fun buildClarificationHostSurfacePresentation_whenNoPendingRequest_returnsNone() {
        val presentation = buildClarificationHostSurfacePresentation(
            clarificationPresentation = null,
            interactionState = ClarificationInteractionState(answer = "保留草稿"),
            isChatScreenVisible = false
        )

        assertEquals(ClarificationHostSurfaceKind.NONE, presentation.kind)
        assertNull(presentation.clarificationPresentation)
        assertEquals("保留草稿", presentation.interactionState.answer)
    }

    @Test
    fun buildClarificationHostSurfacePresentation_whenChatVisible_routesToInlineCard() {
        val clarificationPresentation = clarificationRequest().toClarificationPromptPresentation()

        val presentation = buildClarificationHostSurfacePresentation(
            clarificationPresentation = clarificationPresentation,
            interactionState = ClarificationInteractionState(answer = "继续推进"),
            isChatScreenVisible = true
        )

        assertEquals(ClarificationHostSurfaceKind.CHAT_INLINE, presentation.kind)
        assertEquals(clarificationPresentation, presentation.clarificationPresentation)
        assertEquals("继续推进", presentation.interactionState.answer)
    }

    @Test
    fun buildClarificationHostSurfacePresentation_whenChatHidden_routesToDialog() {
        val clarificationPresentation = clarificationRequest().toClarificationPromptPresentation()

        val presentation = buildClarificationHostSurfacePresentation(
            clarificationPresentation = clarificationPresentation,
            interactionState = ClarificationInteractionState(answer = "先补限制"),
            isChatScreenVisible = false
        )

        assertEquals(ClarificationHostSurfaceKind.DIALOG, presentation.kind)
        assertEquals(clarificationPresentation, presentation.clarificationPresentation)
        assertEquals("先补限制", presentation.interactionState.answer)
    }

    private fun workflowPlan(): WorkflowPlanUi {
        return WorkflowPlanUi(
            id = "plan-1",
            goal = "完成借鉴收口",
            summary = "先统一 surface，再继续下沉 source。",
            steps = listOf("抽 shared surface", "宿主路由接管"),
            stageLabels = listOf("收口", "验证"),
            currentStageIndex = 0,
            currentStepIndex = 1,
            nextStepHint = "把非聊天页入口也接上。",
            status = WorkflowPlanStatusUi.READY,
            rawPlan = "1. 抽 shared surface\n2. 接 MainActivity route"
        )
    }

    private fun clarificationRequest(): ClarificationRequestUi {
        return ClarificationRequestUi(
            id = "clarify-1",
            goal = "继续推进 host route",
            question = "优先收口哪条 pending surface？",
            turnIndex = 1,
            maxTurns = 3,
            source = ClarificationSource.AUTO_INTERRUPT
        )
    }
}
