package com.murong.agent.ui.chat

import com.murong.agent.core.loop.AskOptionUi
import com.murong.agent.core.loop.AskQuestionUi
import com.murong.agent.core.loop.PendingAskRequestUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskPromptPresentationTest {

    @Test
    fun toAskPromptPresentation_projectsRequestIntoSharedPromptPresentation() {
        val presentation = pendingAsk().toAskPromptPresentation()

        assertEquals("ask-1", presentation.requestId)
        assertEquals("需要确认", presentation.title)
        assertFalse(presentation.replayOnly)
        assertNull(presentation.replayNotice)
        assertEquals(2, presentation.questions.size)
        assertEquals("执行方式", presentation.questions[0].chipLabel)
        assertEquals("执行方式", presentation.questions[0].title)
        assertEquals("单选题，也可直接输入自定义回答。", presentation.questions[0].selectionGuidance)
        assertEquals("问题 2", presentation.questions[1].chipLabel)
        assertEquals("请确认这个选择", presentation.questions[1].title)
        assertEquals("可多选，也可直接输入自定义回答。", presentation.questions[1].selectionGuidance)
        assertEquals("继续执行", presentation.questions[0].options[0].label)
        assertEquals("直接往下做", presentation.questions[0].options[0].description)
    }

    @Test
    fun toAskPromptPresentation_preservesReplayOnlyNotice() {
        val presentation = pendingAsk(
            replayOnly = true,
            replayNotice = "恢复态只读"
        ).toAskPromptPresentation()

        assertTrue(presentation.replayOnly)
        assertEquals("恢复态只读", presentation.replayNotice)
    }

    private fun pendingAsk(
        replayOnly: Boolean = false,
        replayNotice: String? = null
    ): PendingAskRequestUi {
        return PendingAskRequestUi(
            id = "ask-1",
            title = "需要确认",
            isReplayOnly = replayOnly,
            replayNotice = replayNotice,
            questions = listOf(
                AskQuestionUi(
                    id = "q1",
                    header = "执行方式",
                    question = "这一步要怎么继续？",
                    options = listOf(
                        AskOptionUi(label = "继续执行", description = "直接往下做"),
                        AskOptionUi(label = "先停一下", description = "等你确认后再继续")
                    )
                ),
                AskQuestionUi(
                    id = "q2",
                    question = "还要补哪些限制？",
                    options = listOf(
                        AskOptionUi(label = "限制网络"),
                        AskOptionUi(label = "限制写入")
                    ),
                    multiSelect = true
                )
            )
        )
    }
}
