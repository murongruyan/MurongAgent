package com.murong.agent.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AskPromptSessionPresentationTest {

    @Test
    fun buildAskPromptSessionPresentation_projectsCurrentQuestionAndAnswers() {
        val session = buildAskPromptSessionPresentation(
            presentation = AskPromptPresentationTestFixtures.pendingAsk().toAskPromptPresentation(),
            interactionState = AskPromptInteractionState(
                activeIndex = 1,
                selectedAnswers = mapOf(
                    "q1" to setOf("继续执行"),
                    "q2" to setOf("限制网络", "限制写入")
                )
            )
        )!!

        assertEquals(1, session.activeIndex)
        assertEquals("问题 2 / 2", session.questionProgressLabel)
        assertEquals("请确认这个选择", session.currentQuestion.title)
        assertEquals(setOf("限制网络", "限制写入"), session.currentSelections)
        assertEquals(2, session.answeredCount)
        assertTrue(session.allAnswered)
        assertEquals(2, session.answers.size)
        assertEquals("关闭", session.dismissLabel)
        assertEquals("上一题", session.previousAction?.label)
        assertEquals("提交全部回答", session.primaryAction.label)
        assertTrue(session.primaryAction.enabled)
        assertEquals(AskPromptPrimaryActionKind.SUBMIT_ANSWERS, session.primaryAction.kind)
        assertTrue(session.questionChips.all { it.isAnswered })
    }

    @Test
    fun buildAskPromptSessionPresentation_prefersCustomAnswerOverSelections() {
        val session = buildAskPromptSessionPresentation(
            presentation = AskPromptPresentationTestFixtures.pendingAsk().toAskPromptPresentation(),
            interactionState = AskPromptInteractionState(
                selectedAnswers = mapOf("q1" to setOf("继续执行")),
                customAnswers = mapOf("q1" to "自定义方案")
            )
        )!!

        assertEquals("自定义方案", session.currentCustomAnswer)
        assertEquals(listOf("自定义方案"), session.answers.first().selectedOptions)
    }

    @Test
    fun buildAskPromptSessionPresentation_marksReplayOnlyHintAndPlaceholder() {
        val session = buildAskPromptSessionPresentation(
            presentation = AskPromptPresentationTestFixtures.pendingAsk(
                replayOnly = true,
                replayNotice = "恢复态只读"
            ).toAskPromptPresentation(),
            interactionState = buildInitialAskPromptInteractionState()
        )!!

        assertEquals("恢复态只读，暂不接受输入", session.customInputPlaceholder)
        assertTrue(session.replayOnlyHint!!.contains("回放只读"))
        assertEquals("查看下一题", session.primaryAction.label)
        assertTrue(session.primaryAction.enabled)
        assertEquals(AskPromptPrimaryActionKind.NEXT_QUESTION, session.primaryAction.kind)
    }

    @Test
    fun buildAskPromptSessionPresentation_whenCurrentQuestionUnanswered_blocksAdvance() {
        val session = buildAskPromptSessionPresentation(
            presentation = AskPromptPresentationTestFixtures.pendingAsk().toAskPromptPresentation(),
            interactionState = buildInitialAskPromptInteractionState()
        )!!

        assertEquals("下一题", session.primaryAction.label)
        assertFalse(session.primaryAction.enabled)
        assertEquals(AskPromptPrimaryActionKind.NEXT_QUESTION, session.primaryAction.kind)
        assertEquals("当前题还没回答，所以暂时不能继续下一题。", session.status.disabledHint)
    }

    @Test
    fun askPromptInteractionReducers_updateSelectionsCustomAnswerAndNavigation() {
        val presentation = AskPromptPresentationTestFixtures.pendingAsk().toAskPromptPresentation()
        val firstQuestion = presentation.questions.first()
        val secondQuestion = presentation.questions.last()

        val selectedState = updateAskPromptOptionSelection(
            state = buildInitialAskPromptInteractionState(),
            question = firstQuestion,
            optionLabel = "继续执行"
        )
        val customState = updateAskPromptCustomAnswer(
            state = selectedState,
            questionId = firstQuestion.id,
            value = "自定义方案"
        )
        val advancedState = advanceAskPromptQuestion(
            state = customState,
            questionCount = presentation.questions.size
        )
        val selectedSecondState = updateAskPromptOptionSelection(
            state = advancedState,
            question = secondQuestion,
            optionLabel = "限制网络"
        )

        assertEquals(emptySet(), customState.selectedAnswers[firstQuestion.id].orEmpty())
        assertEquals("自定义方案", customState.customAnswers[firstQuestion.id])
        assertEquals(1, advancedState.activeIndex)
        assertEquals(setOf("限制网络"), selectedSecondState.selectedAnswers[secondQuestion.id])
        assertEquals(0, goToPreviousAskPromptQuestion(selectedSecondState).activeIndex)
    }

    @Test
    fun performAskPromptActions_driveNavigationAndSubmissionFromSessionPresentation() {
        val presentation = AskPromptPresentationTestFixtures.pendingAsk().toAskPromptPresentation()
        val firstState = updateAskPromptOptionSelection(
            state = buildInitialAskPromptInteractionState(),
            question = presentation.questions.first(),
            optionLabel = "继续执行"
        )
        val firstSession = buildAskPromptSessionPresentation(
            presentation = presentation,
            interactionState = firstState
        )!!

        val nextResult = performAskPromptPrimaryAction(
            sessionPresentation = firstSession,
            state = firstState
        )
        assertTrue(nextResult is AskPromptPrimaryActionResult.Navigate)
        val secondState = nextResult.state
        assertEquals(1, secondState.activeIndex)

        val answeredState = updateAskPromptOptionSelection(
            state = secondState,
            question = presentation.questions.last(),
            optionLabel = "限制网络"
        )
        val answeredSession = buildAskPromptSessionPresentation(
            presentation = presentation,
            interactionState = answeredState
        )!!
        val submitResult = performAskPromptPrimaryAction(
            sessionPresentation = answeredSession,
            state = answeredState
        )
        assertTrue(submitResult is AskPromptPrimaryActionResult.Submit)
        assertEquals(2, submitResult.answers.size)
        assertEquals(0, performAskPromptPreviousAction(answeredSession, answeredState)?.activeIndex)
    }
}
