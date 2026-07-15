package com.murong.agent.ui.chat

import com.murong.agent.core.loop.AskAnswerUi
import com.murong.agent.core.loop.AskOptionUi
import com.murong.agent.core.loop.AskQuestionUi
import com.murong.agent.core.loop.PendingAskRequestUi

internal data class AskPromptPresentation(
    val requestId: String,
    val title: String,
    val replayOnly: Boolean,
    val replayNotice: String? = null,
    val questions: List<AskQuestionPresentation>
)

internal data class AskQuestionPresentation(
    val id: String,
    val chipLabel: String,
    val title: String,
    val prompt: String,
    val selectionGuidance: String,
    val multiSelect: Boolean,
    val options: List<AskOptionPresentation>
)

internal data class AskOptionPresentation(
    val label: String,
    val description: String? = null
)

internal fun PendingAskRequestUi.toAskPromptPresentation(): AskPromptPresentation {
    return AskPromptPresentation(
        requestId = id,
        title = title,
        replayOnly = isReplayOnly,
        replayNotice = replayNotice,
        questions = questions.mapIndexed { index, question ->
            question.toAskQuestionPresentation(index)
        }
    )
}

private fun AskQuestionUi.toAskQuestionPresentation(index: Int): AskQuestionPresentation {
    return AskQuestionPresentation(
        id = id,
        chipLabel = header.ifBlank { "问题 ${index + 1}" },
        title = header.ifBlank { "请确认这个选择" },
        prompt = question,
        selectionGuidance = if (multiSelect) {
            "可多选，也可直接输入自定义回答。"
        } else {
            "单选题，也可直接输入自定义回答。"
        },
        multiSelect = multiSelect,
        options = options.map(AskOptionUi::toAskOptionPresentation)
    )
}

private fun AskOptionUi.toAskOptionPresentation(): AskOptionPresentation {
    return AskOptionPresentation(
        label = label,
        description = description?.takeIf { it.isNotBlank() }
    )
}

internal data class AskUserPrimaryActionPresentation(
    val label: String,
    val enabled: Boolean,
    val kind: AskPromptPrimaryActionKind
)

internal data class AskUserCardStatusPresentation(
    val progressLabel: String,
    val guidance: String,
    val disabledHint: String? = null
)

internal data class AskPromptQuestionChipPresentation(
    val questionId: String,
    val label: String,
    val isActive: Boolean,
    val isAnswered: Boolean
)

internal data class AskPromptNavigationActionPresentation(
    val label: String,
    val enabled: Boolean = true
)

internal enum class AskPromptPrimaryActionKind {
    NEXT_QUESTION,
    SUBMIT_ANSWERS,
    NONE
}

internal data class AskPromptInteractionState(
    val activeIndex: Int = 0,
    val selectedAnswers: Map<String, Set<String>> = emptyMap(),
    val customAnswers: Map<String, String> = emptyMap()
)

internal data class AskPromptSessionPresentation(
    val activeIndex: Int,
    val questionProgressLabel: String,
    val currentQuestion: AskQuestionPresentation,
    val currentSelections: Set<String>,
    val currentCustomAnswer: String,
    val answeredCount: Int,
    val allAnswered: Boolean,
    val questionChips: List<AskPromptQuestionChipPresentation>,
    val answers: List<AskAnswerUi>,
    val primaryAction: AskUserPrimaryActionPresentation,
    val status: AskUserCardStatusPresentation,
    val dismissLabel: String,
    val previousAction: AskPromptNavigationActionPresentation? = null,
    val customInputPlaceholder: String,
    val replayOnlyHint: String? = null
)

internal sealed interface AskPromptPrimaryActionResult {
    data class Navigate(val state: AskPromptInteractionState) : AskPromptPrimaryActionResult
    data class Submit(val answers: List<AskAnswerUi>) : AskPromptPrimaryActionResult
    data object NoOp : AskPromptPrimaryActionResult
}

internal fun buildInitialAskPromptInteractionState(): AskPromptInteractionState {
    return AskPromptInteractionState()
}

internal fun selectAskPromptQuestion(
    state: AskPromptInteractionState,
    questionIndex: Int,
    questionCount: Int
): AskPromptInteractionState {
    if (questionCount <= 0) return state.copy(activeIndex = 0)
    return state.copy(activeIndex = questionIndex.coerceIn(0, questionCount - 1))
}

internal fun goToPreviousAskPromptQuestion(
    state: AskPromptInteractionState
): AskPromptInteractionState {
    return state.copy(activeIndex = (state.activeIndex - 1).coerceAtLeast(0))
}

internal fun advanceAskPromptQuestion(
    state: AskPromptInteractionState,
    questionCount: Int
): AskPromptInteractionState {
    if (questionCount <= 0) return state.copy(activeIndex = 0)
    return state.copy(activeIndex = (state.activeIndex + 1).coerceAtMost(questionCount - 1))
}

internal fun updateAskPromptOptionSelection(
    state: AskPromptInteractionState,
    question: AskQuestionPresentation,
    optionLabel: String
): AskPromptInteractionState {
    val nextSelectedAnswers = state.selectedAnswers.toMutableMap()
    val currentSelections = nextSelectedAnswers[question.id].orEmpty()
    nextSelectedAnswers[question.id] = if (question.multiSelect) {
        currentSelections.toMutableSet().apply {
            if (!add(optionLabel)) {
                remove(optionLabel)
            }
        }
    } else {
        setOf(optionLabel)
    }
    return state.copy(
        selectedAnswers = nextSelectedAnswers,
        customAnswers = state.customAnswers.toMutableMap().apply {
            this[question.id] = ""
        }
    )
}

internal fun updateAskPromptCustomAnswer(
    state: AskPromptInteractionState,
    questionId: String,
    value: String
): AskPromptInteractionState {
    val nextSelectedAnswers = state.selectedAnswers.toMutableMap()
    if (value.isNotBlank()) {
        nextSelectedAnswers.remove(questionId)
    }
    return state.copy(
        selectedAnswers = nextSelectedAnswers,
        customAnswers = state.customAnswers.toMutableMap().apply {
            this[questionId] = value
        }
    )
}

internal fun buildAskPromptSessionPresentation(
    presentation: AskPromptPresentation,
    interactionState: AskPromptInteractionState
): AskPromptSessionPresentation? {
    if (presentation.questions.isEmpty()) return null
    val safeActiveIndex = interactionState.activeIndex.coerceIn(0, presentation.questions.lastIndex)
    val currentQuestion = presentation.questions[safeActiveIndex]
    val answersByQuestionId = presentation.questions.associate { question ->
        question.id to resolveAskPromptAnswer(
            question = question,
            selectedAnswers = interactionState.selectedAnswers,
            customAnswers = interactionState.customAnswers
        )
    }
    val answers = presentation.questions.mapNotNull { question ->
        answersByQuestionId[question.id]
            .orEmpty()
            .takeIf { it.isNotEmpty() }
            ?.let { AskAnswerUi(questionId = question.id, selectedOptions = it) }
    }
    val answeredCount = answers.size
    val allAnswered = answeredCount == presentation.questions.size
    val currentAnswer = answersByQuestionId[currentQuestion.id].orEmpty()
    return AskPromptSessionPresentation(
        activeIndex = safeActiveIndex,
        questionProgressLabel = "问题 ${safeActiveIndex + 1} / ${presentation.questions.size}",
        currentQuestion = currentQuestion,
        currentSelections = interactionState.selectedAnswers[currentQuestion.id].orEmpty(),
        currentCustomAnswer = interactionState.customAnswers[currentQuestion.id].orEmpty(),
        answeredCount = answeredCount,
        allAnswered = allAnswered,
        questionChips = presentation.questions.mapIndexed { index, question ->
            AskPromptQuestionChipPresentation(
                questionId = question.id,
                label = question.chipLabel,
                isActive = index == safeActiveIndex,
                isAnswered = answersByQuestionId[question.id].orEmpty().isNotEmpty()
            )
        },
        answers = answers,
        primaryAction = buildAskUserPrimaryActionPresentation(
            activeIndex = safeActiveIndex,
            questionCount = presentation.questions.size,
            hasCurrentAnswer = currentAnswer.isNotEmpty(),
            allAnswered = allAnswered,
            replayOnly = presentation.replayOnly
        ),
        status = buildAskUserCardStatusPresentation(
            activeIndex = safeActiveIndex,
            questionCount = presentation.questions.size,
            answeredCount = answeredCount,
            hasCurrentAnswer = currentAnswer.isNotEmpty(),
            allAnswered = allAnswered,
            replayOnly = presentation.replayOnly
        ),
        dismissLabel = "关闭",
        previousAction = if (safeActiveIndex > 0) {
            AskPromptNavigationActionPresentation(label = "上一题")
        } else {
            null
        },
        customInputPlaceholder = if (presentation.replayOnly) {
            "恢复态只读，暂不接受输入"
        } else {
            "或输入自定义回答"
        },
        replayOnlyHint = if (presentation.replayOnly) {
            "回放只读：选项和自定义输入已禁用，当前仅用于查看待恢复的问题内容。"
        } else {
            null
        }
    )
}

internal fun resolveAskPromptAnswer(
    question: AskQuestionPresentation,
    selectedAnswers: Map<String, Set<String>>,
    customAnswers: Map<String, String>
): List<String> {
    val custom = customAnswers[question.id].orEmpty().trim()
    if (custom.isNotBlank()) return listOf(custom)
    return selectedAnswers[question.id].orEmpty().toList()
}

internal fun buildAskUserPrimaryActionPresentation(
    activeIndex: Int,
    questionCount: Int,
    hasCurrentAnswer: Boolean,
    allAnswered: Boolean,
    replayOnly: Boolean
): AskUserPrimaryActionPresentation {
    val isLastQuestion = activeIndex >= questionCount - 1
    if (!isLastQuestion) {
        return AskUserPrimaryActionPresentation(
            label = if (replayOnly) "查看下一题" else "下一题",
            enabled = replayOnly || hasCurrentAnswer,
            kind = AskPromptPrimaryActionKind.NEXT_QUESTION
        )
    }
    if (replayOnly) {
        return AskUserPrimaryActionPresentation(
            label = "只读回放",
            enabled = false,
            kind = AskPromptPrimaryActionKind.NONE
        )
    }
    return AskUserPrimaryActionPresentation(
        label = "提交全部回答",
        enabled = allAnswered,
        kind = AskPromptPrimaryActionKind.SUBMIT_ANSWERS
    )
}

internal fun performAskPromptPreviousAction(
    sessionPresentation: AskPromptSessionPresentation,
    state: AskPromptInteractionState
): AskPromptInteractionState? {
    if (sessionPresentation.previousAction?.enabled != true) return null
    return goToPreviousAskPromptQuestion(state)
}

internal fun performAskPromptPrimaryAction(
    sessionPresentation: AskPromptSessionPresentation,
    state: AskPromptInteractionState
): AskPromptPrimaryActionResult {
    if (!sessionPresentation.primaryAction.enabled) return AskPromptPrimaryActionResult.NoOp
    return when (sessionPresentation.primaryAction.kind) {
        AskPromptPrimaryActionKind.NEXT_QUESTION -> AskPromptPrimaryActionResult.Navigate(
            advanceAskPromptQuestion(
                state = state,
                questionCount = sessionPresentation.questionChips.size
            )
        )

        AskPromptPrimaryActionKind.SUBMIT_ANSWERS ->
            AskPromptPrimaryActionResult.Submit(sessionPresentation.answers)

        AskPromptPrimaryActionKind.NONE -> AskPromptPrimaryActionResult.NoOp
    }
}

internal fun buildAskUserCardStatusPresentation(
    activeIndex: Int,
    questionCount: Int,
    answeredCount: Int,
    hasCurrentAnswer: Boolean,
    allAnswered: Boolean,
    replayOnly: Boolean
): AskUserCardStatusPresentation {
    val safeQuestionCount = questionCount.coerceAtLeast(1)
    val safeActiveIndex = activeIndex.coerceIn(0, safeQuestionCount - 1)
    val remainingCount = (safeQuestionCount - answeredCount).coerceAtLeast(0)
    if (replayOnly) {
        val guidance = if (safeActiveIndex < safeQuestionCount - 1) {
            "当前是恢复态回放，只能浏览题目和原提示；可继续查看后续问题，但不会修改或提交历史回答。"
        } else {
            "当前是恢复态回放，已到最后一题；现在只能查看原问题，不能重新提交这组回答。"
        }
        return AskUserCardStatusPresentation(
            progressLabel = "只读回放 · 第 ${safeActiveIndex + 1} / $safeQuestionCount 题",
            guidance = guidance,
            disabledHint = "关闭后可回到聊天；如需继续，请在活跃会话里重新触发提问。"
        )
    }
    val guidance = when {
        remainingCount > 0 -> "提交前还差 $remainingCount 题，当前答案会保留在这张卡片里。"
        else -> "所有问题都已回答，切到最后一题即可一次性提交。"
    }
    val disabledHint = when {
        safeActiveIndex < safeQuestionCount - 1 && !hasCurrentAnswer ->
            "当前题还没回答，所以暂时不能继续下一题。"
        safeActiveIndex == safeQuestionCount - 1 && !allAnswered ->
            "还有其他问题未完成，暂时不能提交全部回答。"
        else -> null
    }
    return AskUserCardStatusPresentation(
        progressLabel = "已回答 $answeredCount / $safeQuestionCount",
        guidance = guidance,
        disabledHint = disabledHint
    )
}
