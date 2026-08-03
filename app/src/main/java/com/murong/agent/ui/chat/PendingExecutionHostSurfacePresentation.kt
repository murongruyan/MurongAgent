package com.murong.agent.ui.chat

internal enum class WorkflowPlanHostSurfaceKind {
    NONE,
    CHAT_INLINE,
    DIALOG
}

internal data class WorkflowPlanHostSurfacePresentation(
    val kind: WorkflowPlanHostSurfaceKind,
    val workflowPlanPresentation: WorkflowPlanPromptPresentation? = null,
    val interactionState: WorkflowPlanInteractionState = WorkflowPlanInteractionState()
)

internal fun buildWorkflowPlanHostSurfacePresentation(
    workflowPlanPresentation: WorkflowPlanPromptPresentation?,
    interactionState: WorkflowPlanInteractionState,
    isChatScreenVisible: Boolean
): WorkflowPlanHostSurfacePresentation {
    if (workflowPlanPresentation == null) {
        return WorkflowPlanHostSurfacePresentation(
            kind = WorkflowPlanHostSurfaceKind.NONE,
            interactionState = interactionState
        )
    }
    return WorkflowPlanHostSurfacePresentation(
        // The execution plan always lives inline in the chat as a tappable
        // card. A full-screen dialog hides the ongoing conversation and makes
        // the agent's work invisible, so we no longer route to DIALOG even
        // when the chat page is not the currently visible screen.
        kind = WorkflowPlanHostSurfaceKind.CHAT_INLINE,
        workflowPlanPresentation = workflowPlanPresentation,
        interactionState = interactionState
    )
}

internal enum class ClarificationHostSurfaceKind {
    NONE,
    CHAT_INLINE,
    DIALOG
}

internal data class ClarificationHostSurfacePresentation(
    val kind: ClarificationHostSurfaceKind,
    val clarificationPresentation: ClarificationPromptPresentation? = null,
    val interactionState: ClarificationInteractionState = ClarificationInteractionState()
)

internal fun buildClarificationHostSurfacePresentation(
    clarificationPresentation: ClarificationPromptPresentation?,
    interactionState: ClarificationInteractionState,
    isChatScreenVisible: Boolean
): ClarificationHostSurfacePresentation {
    if (clarificationPresentation == null) {
        return ClarificationHostSurfacePresentation(
            kind = ClarificationHostSurfaceKind.NONE,
            interactionState = interactionState
        )
    }
    return ClarificationHostSurfacePresentation(
        kind = if (isChatScreenVisible) ClarificationHostSurfaceKind.CHAT_INLINE else ClarificationHostSurfaceKind.DIALOG,
        clarificationPresentation = clarificationPresentation,
        interactionState = interactionState
    )
}
