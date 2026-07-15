package com.murong.agent.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class PendingPromptHostPresentationTest {

    @Test
    fun syncPendingPromptHostInteractionState_preservesMatchingStateAndResetsChangedRequests() {
        val askPresentation = AskPromptPresentationTestFixtures.pendingAsk().toAskPromptPresentation()
        val workflowPresentation = PendingExecutionPresentationTestFixtures
            .workflowPlan()
            .toWorkflowPlanPromptPresentation()
        val nextWorkflowPresentation = workflowPresentation.copy(requestId = "plan-2")

        val state = PendingPromptHostInteractionState(
            askRequestId = askPresentation.requestId,
            askInteractionState = AskPromptInteractionState(activeIndex = 1),
            workflowPlanRequestId = workflowPresentation.requestId,
            workflowPlanInteractionState = WorkflowPlanInteractionState(showRawPlan = true),
            clarificationRequestId = "clarify-1",
            clarificationInteractionState = ClarificationInteractionState(answer = "旧草稿")
        )

        val synced = syncPendingPromptHostInteractionState(
            state = state,
            askPresentation = askPresentation,
            workflowPlanPresentation = nextWorkflowPresentation,
            clarificationPresentation = null
        )

        assertEquals(1, synced.askInteractionState.activeIndex)
        assertEquals(false, synced.workflowPlanInteractionState.showRawPlan)
        assertEquals("", synced.clarificationInteractionState.answer)
        assertEquals(nextWorkflowPresentation.requestId, synced.workflowPlanRequestId)
        assertEquals(null, synced.clarificationRequestId)
    }

    @Test
    fun buildPendingPromptHostPresentation_routesAndCarriesSharedInteractionState() {
        val askPresentation = AskPromptPresentationTestFixtures.pendingAsk().toAskPromptPresentation()
        val workflowPresentation = PendingExecutionPresentationTestFixtures
            .workflowPlan()
            .toWorkflowPlanPromptPresentation()
        val clarificationPresentation = PendingExecutionPresentationTestFixtures
            .clarificationRequest()
            .toClarificationPromptPresentation()
        val interactionState = PendingPromptHostInteractionState(
            askRequestId = askPresentation.requestId,
            askInteractionState = AskPromptInteractionState(
                customAnswers = mapOf("q1" to "自定义回答")
            ),
            workflowPlanRequestId = workflowPresentation.requestId,
            workflowPlanInteractionState = WorkflowPlanInteractionState(showRawPlan = true),
            clarificationRequestId = clarificationPresentation.requestId,
            clarificationInteractionState = ClarificationInteractionState(answer = "继续推进")
        )

        val dialogPresentation = buildPendingPromptHostPresentation(
            askPresentation = askPresentation,
            workflowPlanPresentation = workflowPresentation,
            clarificationPresentation = clarificationPresentation,
            interactionState = interactionState,
            isChatScreenVisible = false
        )
        val inlinePresentation = buildPendingPromptHostPresentation(
            askPresentation = askPresentation,
            workflowPlanPresentation = workflowPresentation,
            clarificationPresentation = clarificationPresentation,
            interactionState = interactionState,
            isChatScreenVisible = true
        )

        assertEquals(AskHostSurfaceKind.DIALOG, dialogPresentation.askHostSurface.kind)
        assertEquals("自定义回答", dialogPresentation.askHostSurface.interactionState.customAnswers["q1"])
        assertEquals(WorkflowPlanHostSurfaceKind.CHAT_INLINE, inlinePresentation.workflowPlanHostSurface.kind)
        assertEquals(true, inlinePresentation.workflowPlanHostSurface.interactionState.showRawPlan)
        assertEquals("继续推进", inlinePresentation.clarificationHostSurface.interactionState.answer)
    }
}
