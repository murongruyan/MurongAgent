package com.murong.agent.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AskUserCardPresentationTest {

    @Test
    fun buildAskUserPrimaryActionPresentation_allowsReplayOnlyCardToAdvanceWithoutAnswers() {
        val action = buildAskUserPrimaryActionPresentation(
            activeIndex = 0,
            questionCount = 3,
            hasCurrentAnswer = false,
            allAnswered = false,
            replayOnly = true
        )

        assertEquals("查看下一题", action.label)
        assertTrue(action.enabled)
    }

    @Test
    fun buildAskUserPrimaryActionPresentation_blocksReplayOnlySubmitOnLastQuestion() {
        val action = buildAskUserPrimaryActionPresentation(
            activeIndex = 2,
            questionCount = 3,
            hasCurrentAnswer = false,
            allAnswered = false,
            replayOnly = true
        )

        assertEquals("只读回放", action.label)
        assertFalse(action.enabled)
    }

    @Test
    fun buildAskUserPrimaryActionPresentation_requiresAnswerBeforeAdvancingLiveCard() {
        val action = buildAskUserPrimaryActionPresentation(
            activeIndex = 0,
            questionCount = 2,
            hasCurrentAnswer = false,
            allAnswered = false,
            replayOnly = false
        )

        assertEquals("下一题", action.label)
        assertFalse(action.enabled)
    }

    @Test
    fun buildAskUserPrimaryActionPresentation_usesSubmitAllLabelOnLastLiveQuestion() {
        val action = buildAskUserPrimaryActionPresentation(
            activeIndex = 1,
            questionCount = 2,
            hasCurrentAnswer = true,
            allAnswered = true,
            replayOnly = false
        )

        assertEquals("提交全部回答", action.label)
        assertTrue(action.enabled)
    }

    @Test
    fun buildAskUserCardStatusPresentation_reportsReplayOnlyBrowsingGuidanceBeforeLastQuestion() {
        val presentation = buildAskUserCardStatusPresentation(
            activeIndex = 0,
            questionCount = 3,
            answeredCount = 0,
            hasCurrentAnswer = false,
            allAnswered = false,
            replayOnly = true
        )

        assertEquals("只读回放 · 第 1 / 3 题", presentation.progressLabel)
        assertTrue(presentation.guidance.contains("可继续查看后续问题"))
        assertTrue(presentation.disabledHint!!.contains("重新触发提问"))
    }

    @Test
    fun buildAskUserCardStatusPresentation_reportsExplicitDisabledHintForIncompleteLiveQuestion() {
        val presentation = buildAskUserCardStatusPresentation(
            activeIndex = 0,
            questionCount = 3,
            answeredCount = 1,
            hasCurrentAnswer = false,
            allAnswered = false,
            replayOnly = false
        )

        assertEquals("已回答 1 / 3", presentation.progressLabel)
        assertTrue(presentation.guidance.contains("提交前还差 2 题"))
        assertEquals("当前题还没回答，所以暂时不能继续下一题。", presentation.disabledHint)
    }

    @Test
    fun buildAskUserCardStatusPresentation_reportsSubmitBlockReasonOnLastQuestionWhenOthersRemain() {
        val presentation = buildAskUserCardStatusPresentation(
            activeIndex = 2,
            questionCount = 3,
            answeredCount = 2,
            hasCurrentAnswer = true,
            allAnswered = false,
            replayOnly = false
        )

        assertEquals("已回答 2 / 3", presentation.progressLabel)
        assertEquals("还有其他问题未完成，暂时不能提交全部回答。", presentation.disabledHint)
    }
}
