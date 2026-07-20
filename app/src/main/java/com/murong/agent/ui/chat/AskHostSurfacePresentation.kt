package com.murong.agent.ui.chat

internal enum class AskHostSurfaceKind {
    NONE,
    CHAT_INLINE,
    DIALOG
}

internal data class AskHostSurfacePresentation(
    val kind: AskHostSurfaceKind,
    val askPresentation: AskPromptPresentation? = null,
    val interactionState: AskPromptInteractionState = AskPromptInteractionState()
)

internal fun buildAskHostSurfacePresentation(
    askPresentation: AskPromptPresentation?,
    interactionState: AskPromptInteractionState,
    isChatScreenVisible: Boolean
): AskHostSurfacePresentation {
    if (askPresentation == null) {
        return AskHostSurfacePresentation(
            kind = AskHostSurfaceKind.NONE,
            interactionState = interactionState
        )
    }
    return AskHostSurfacePresentation(
        kind = if (isChatScreenVisible) AskHostSurfaceKind.CHAT_INLINE else AskHostSurfaceKind.DIALOG,
        askPresentation = askPresentation,
        interactionState = interactionState
    )
}
