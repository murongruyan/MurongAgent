package com.murong.agent.ui.chat

import com.murong.agent.core.loop.AskOptionUi
import com.murong.agent.core.loop.AskQuestionUi
import com.murong.agent.core.loop.PendingAskRequestUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AskHostSurfacePresentationTest {

    @Test
    fun buildAskHostSurfacePresentation_whenNoPendingAsk_returnsNone() {
        val presentation = buildAskHostSurfacePresentation(
            askPresentation = null,
            interactionState = AskPromptInteractionState(activeIndex = 1),
            isChatScreenVisible = false
        )

        assertEquals(AskHostSurfaceKind.NONE, presentation.kind)
        assertNull(presentation.askPresentation)
        assertEquals(1, presentation.interactionState.activeIndex)
    }

    @Test
    fun buildAskHostSurfacePresentation_whenChatVisible_routesToInlineCard() {
        val askPresentation = pendingAsk().toAskPromptPresentation()

        val presentation = buildAskHostSurfacePresentation(
            askPresentation = askPresentation,
            interactionState = AskPromptInteractionState(
                selectedAnswers = mapOf("q1" to setOf("继续执行"))
            ),
            isChatScreenVisible = true
        )

        assertEquals(AskHostSurfaceKind.CHAT_INLINE, presentation.kind)
        assertEquals(askPresentation, presentation.askPresentation)
        assertEquals(setOf("继续执行"), presentation.interactionState.selectedAnswers["q1"])
    }

    @Test
    fun buildAskHostSurfacePresentation_whenChatHidden_routesToDialog() {
        val askPresentation = pendingAsk().toAskPromptPresentation()

        val presentation = buildAskHostSurfacePresentation(
            askPresentation = askPresentation,
            interactionState = AskPromptInteractionState(
                customAnswers = mapOf("q1" to "自定义回答")
            ),
            isChatScreenVisible = false
        )

        assertEquals(AskHostSurfaceKind.DIALOG, presentation.kind)
        assertEquals(askPresentation, presentation.askPresentation)
        assertEquals("自定义回答", presentation.interactionState.customAnswers["q1"])
    }

    private fun pendingAsk(): PendingAskRequestUi {
        return PendingAskRequestUi(
            title = "需要确认",
            questions = listOf(
                AskQuestionUi(
                    id = "q1",
                    header = "执行方式",
                    question = "这一步要怎么继续？",
                    options = listOf(
                        AskOptionUi(label = "继续执行", description = "直接往下做"),
                        AskOptionUi(label = "先停一下", description = "等你确认后再继续")
                    )
                )
            )
        )
    }
}
