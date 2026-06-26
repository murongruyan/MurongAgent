package com.murong.agent.core.loop

internal data class ResumeExecutionPayload(
    val userVisibleText: String,
    val modelInput: String,
    val executionGoal: String,
    val existingClarificationAnswers: List<ClarificationAnswerUi>
)

internal fun buildResumeExecutionPayload(
    executionGoal: String,
    existingClarificationAnswers: List<ClarificationAnswerUi>,
    clarificationExecutionPromptBuilder: (String, List<ClarificationAnswerUi>) -> String
): ResumeExecutionPayload {
    val modelInput = if (existingClarificationAnswers.isNotEmpty()) {
        clarificationExecutionPromptBuilder(executionGoal, existingClarificationAnswers)
    } else {
        executionGoal
    }
    return ResumeExecutionPayload(
        userVisibleText = "继续执行: $executionGoal",
        modelInput = modelInput,
        executionGoal = executionGoal,
        existingClarificationAnswers = existingClarificationAnswers
    )
}

internal fun buildResumeExecutionPayload(
    executionGoal: String,
    existingClarificationAnswers: List<ClarificationAnswerUi>,
    state: SessionState,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null,
    clarificationExecutionPromptBuilder: (
        String,
        List<ClarificationAnswerUi>,
        SessionState,
        SessionHistoryReferenceClueUi?
    ) -> String
): ResumeExecutionPayload {
    val modelInput = if (existingClarificationAnswers.isNotEmpty()) {
        clarificationExecutionPromptBuilder(
            executionGoal,
            existingClarificationAnswers,
            state,
            mergeSessionHistoryReferenceClues(
                state.recentSessionHistoryClue,
                recentSessionHistoryClue
            )
        )
    } else {
        executionGoal
    }
    return ResumeExecutionPayload(
        userVisibleText = "继续执行: $executionGoal",
        modelInput = modelInput,
        executionGoal = executionGoal,
        existingClarificationAnswers = existingClarificationAnswers
    )
}

internal fun buildExecutionInterruptionClarificationRequest(
    executionGoal: String,
    question: String,
    mentionedFiles: List<FileMentionUi>,
    existingClarificationAnswers: List<ClarificationAnswerUi>,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null
): ClarificationRequestUi {
    val nextTurnIndex = existingClarificationAnswers.size + 1
    return ClarificationRequestUi(
        goal = executionGoal,
        question = question,
        mentionedFiles = mentionedFiles.distinctBy { it.path },
        previousAnswers = existingClarificationAnswers,
        turnIndex = nextTurnIndex,
        maxTurns = maxOf(MAX_CLARIFICATION_TURNS, nextTurnIndex),
        source = ClarificationSource.AUTO_INTERRUPT,
        recentSessionHistoryClue = recentSessionHistoryClue
    )
}

internal fun buildExecutionInterruptionClarificationRequest(
    executionGoal: String,
    question: String,
    mentionedFiles: List<FileMentionUi>,
    existingClarificationAnswers: List<ClarificationAnswerUi>,
    state: SessionState,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null
): ClarificationRequestUi {
    return buildExecutionInterruptionClarificationRequest(
        executionGoal = executionGoal,
        question = question,
        mentionedFiles = mentionedFiles,
        existingClarificationAnswers = existingClarificationAnswers,
        recentSessionHistoryClue = mergeSessionHistoryReferenceClues(
            state.recentSessionHistoryClue,
            recentSessionHistoryClue
        )
    )
}
