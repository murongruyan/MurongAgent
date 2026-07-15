package com.murong.agent.core.loop

private const val DEFAULT_CLARIFICATION_FOLLOW_UP_QUESTION =
    "基于你刚补充的信息，我还需要再确认一个关键点：你最希望我优先按哪种方式继续？"

internal enum class ClarificationResolutionAction {
    EXECUTE,
    ASK_FOLLOW_UP
}

internal data class ClarificationAnswerResolution(
    val action: ClarificationResolutionAction,
    val accumulatedAnswers: List<ClarificationAnswerUi>,
    val nextClarificationRequest: ClarificationRequestUi? = null
)

internal fun accumulateClarificationAnswers(
    request: ClarificationRequestUi,
    normalizedAnswer: String
): List<ClarificationAnswerUi> {
    return request.previousAnswers + ClarificationAnswerUi(
        question = request.question,
        answer = normalizedAnswer
    )
}

internal fun shouldForceClarificationExecution(request: ClarificationRequestUi): Boolean {
    return request.turnIndex >= request.maxTurns
}

internal fun resolveClarificationAnswerResolution(
    request: ClarificationRequestUi,
    accumulatedAnswers: List<ClarificationAnswerUi>,
    action: AutoRouteAction,
    question: String?
): ClarificationAnswerResolution {
    return if (action == AutoRouteAction.CLARIFY) {
        ClarificationAnswerResolution(
            action = ClarificationResolutionAction.ASK_FOLLOW_UP,
            accumulatedAnswers = accumulatedAnswers,
            nextClarificationRequest = ClarificationRequestUi(
                goal = request.goal,
                question = question ?: DEFAULT_CLARIFICATION_FOLLOW_UP_QUESTION,
                mentionedFiles = request.mentionedFiles,
                previousAnswers = accumulatedAnswers,
                turnIndex = request.turnIndex + 1,
                maxTurns = request.maxTurns,
                source = request.source
            )
        )
    } else {
        ClarificationAnswerResolution(
            action = ClarificationResolutionAction.EXECUTE,
            accumulatedAnswers = accumulatedAnswers
        )
    }
}
