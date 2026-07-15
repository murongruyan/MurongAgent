package com.murong.agent.ui.chat

internal data class PendingPromptHostInteractionState(
    val askRequestId: String? = null,
    val askInteractionState: AskPromptInteractionState = AskPromptInteractionState(),
    val workflowPlanRequestId: String? = null,
    val workflowPlanInteractionState: WorkflowPlanInteractionState = WorkflowPlanInteractionState(),
    val clarificationRequestId: String? = null,
    val clarificationInteractionState: ClarificationInteractionState = ClarificationInteractionState()
)

internal data class PendingPromptHostPresentation(
    val askHostSurface: AskHostSurfacePresentation = AskHostSurfacePresentation(
        kind = AskHostSurfaceKind.NONE
    ),
    val workflowPlanHostSurface: WorkflowPlanHostSurfacePresentation =
        WorkflowPlanHostSurfacePresentation(
            kind = WorkflowPlanHostSurfaceKind.NONE
        ),
    val clarificationHostSurface: ClarificationHostSurfacePresentation =
        ClarificationHostSurfacePresentation(
            kind = ClarificationHostSurfaceKind.NONE
        )
)

internal fun buildInitialPendingPromptHostInteractionState(): PendingPromptHostInteractionState {
    return PendingPromptHostInteractionState()
}

internal fun syncPendingPromptHostInteractionState(
    state: PendingPromptHostInteractionState,
    askPresentation: AskPromptPresentation?,
    workflowPlanPresentation: WorkflowPlanPromptPresentation?,
    clarificationPresentation: ClarificationPromptPresentation?
): PendingPromptHostInteractionState {
    return PendingPromptHostInteractionState(
        askRequestId = askPresentation?.requestId,
        askInteractionState = state.askInteractionState.takeIf {
            askPresentation?.requestId != null && askPresentation.requestId == state.askRequestId
        } ?: AskPromptInteractionState(),
        workflowPlanRequestId = workflowPlanPresentation?.requestId,
        workflowPlanInteractionState = state.workflowPlanInteractionState.takeIf {
            workflowPlanPresentation?.requestId != null &&
                workflowPlanPresentation.requestId == state.workflowPlanRequestId
        } ?: WorkflowPlanInteractionState(),
        clarificationRequestId = clarificationPresentation?.requestId,
        clarificationInteractionState = state.clarificationInteractionState.takeIf {
            clarificationPresentation?.requestId != null &&
                clarificationPresentation.requestId == state.clarificationRequestId
        } ?: ClarificationInteractionState()
    )
}

internal fun updatePendingPromptAskInteractionState(
    state: PendingPromptHostInteractionState,
    interactionState: AskPromptInteractionState
): PendingPromptHostInteractionState {
    return state.copy(askInteractionState = interactionState)
}

internal fun updatePendingPromptWorkflowPlanInteractionState(
    state: PendingPromptHostInteractionState,
    interactionState: WorkflowPlanInteractionState
): PendingPromptHostInteractionState {
    return state.copy(workflowPlanInteractionState = interactionState)
}

internal fun updatePendingPromptClarificationInteractionState(
    state: PendingPromptHostInteractionState,
    interactionState: ClarificationInteractionState
): PendingPromptHostInteractionState {
    return state.copy(clarificationInteractionState = interactionState)
}

internal fun buildPendingPromptHostPresentation(
    askPresentation: AskPromptPresentation?,
    workflowPlanPresentation: WorkflowPlanPromptPresentation?,
    clarificationPresentation: ClarificationPromptPresentation?,
    interactionState: PendingPromptHostInteractionState,
    isChatScreenVisible: Boolean
): PendingPromptHostPresentation {
    return PendingPromptHostPresentation(
        askHostSurface = buildAskHostSurfacePresentation(
            askPresentation = askPresentation,
            interactionState = interactionState.askInteractionState,
            isChatScreenVisible = isChatScreenVisible
        ),
        workflowPlanHostSurface = buildWorkflowPlanHostSurfacePresentation(
            workflowPlanPresentation = workflowPlanPresentation,
            interactionState = interactionState.workflowPlanInteractionState,
            isChatScreenVisible = isChatScreenVisible
        ),
        clarificationHostSurface = buildClarificationHostSurfacePresentation(
            clarificationPresentation = clarificationPresentation,
            interactionState = interactionState.clarificationInteractionState,
            isChatScreenVisible = isChatScreenVisible
        )
    )
}
