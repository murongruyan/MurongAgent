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
    return if (isChatScreenVisible) {
        WorkflowPlanHostSurfacePresentation(
            kind = WorkflowPlanHostSurfaceKind.CHAT_INLINE,
            workflowPlanPresentation = workflowPlanPresentation,
            interactionState = interactionState
        )
    } else {
        WorkflowPlanHostSurfacePresentation(
            kind = WorkflowPlanHostSurfaceKind.DIALOG,
            workflowPlanPresentation = workflowPlanPresentation,
            interactionState = interactionState
        )
    }
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
    return if (isChatScreenVisible) {
        ClarificationHostSurfacePresentation(
            kind = ClarificationHostSurfaceKind.CHAT_INLINE,
            clarificationPresentation = clarificationPresentation,
            interactionState = interactionState
        )
    } else {
        ClarificationHostSurfacePresentation(
            kind = ClarificationHostSurfaceKind.DIALOG,
            clarificationPresentation = clarificationPresentation,
            interactionState = interactionState
        )
    }
}
