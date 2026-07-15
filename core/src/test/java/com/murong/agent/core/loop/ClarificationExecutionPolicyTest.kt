package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClarificationExecutionPolicyTest {

    @Test
    fun accumulateClarificationAnswers_appendsCurrentAnswerToHistory() {
        val request = ClarificationRequestUi(
            goal = "修复问题",
            question = "要不要先跑测试？",
            previousAnswers = listOf(
                ClarificationAnswerUi(
                    question = "优先处理哪个模块？",
                    answer = "先处理 core"
                )
            )
        )

        val accumulated = accumulateClarificationAnswers(
            request = request,
            normalizedAnswer = "先跑测试"
        )

        assertEquals(2, accumulated.size)
        assertEquals("要不要先跑测试？", accumulated.last().question)
        assertEquals("先跑测试", accumulated.last().answer)
    }

    @Test
    fun shouldForceClarificationExecution_returnsTrueAtMaxTurns() {
        val request = ClarificationRequestUi(
            goal = "修复问题",
            question = "要不要先跑测试？",
            turnIndex = 3,
            maxTurns = 3
        )

        assertEquals(true, shouldForceClarificationExecution(request))
    }

    @Test
    fun resolveClarificationAnswerResolution_buildsNextQuestionForClarifyAction() {
        val request = ClarificationRequestUi(
            goal = "修复问题",
            question = "要不要先跑测试？",
            turnIndex = 1,
            maxTurns = 3
        )
        val accumulated = listOf(
            ClarificationAnswerUi(
                question = request.question,
                answer = "先跑测试"
            )
        )

        val resolution = resolveClarificationAnswerResolution(
            request = request,
            accumulatedAnswers = accumulated,
            action = AutoRouteAction.CLARIFY,
            question = "还需要确认目标平台。"
        )

        assertEquals(ClarificationResolutionAction.ASK_FOLLOW_UP, resolution.action)
        val nextRequest = assertNotNull(resolution.nextClarificationRequest)
        assertEquals("还需要确认目标平台。", nextRequest.question)
        assertEquals(2, nextRequest.turnIndex)
        assertEquals(accumulated, nextRequest.previousAnswers)
    }

    @Test
    fun resolveClarificationAnswerResolution_fallsBackToExecutionForDirectAction() {
        val request = ClarificationRequestUi(
            goal = "修复问题",
            question = "要不要先跑测试？"
        )
        val accumulated = listOf(
            ClarificationAnswerUi(
                question = request.question,
                answer = "先跑测试"
            )
        )

        val resolution = resolveClarificationAnswerResolution(
            request = request,
            accumulatedAnswers = accumulated,
            action = AutoRouteAction.DIRECT,
            question = null
        )

        assertEquals(ClarificationResolutionAction.EXECUTE, resolution.action)
        assertEquals(null, resolution.nextClarificationRequest)
        assertEquals(accumulated, resolution.accumulatedAnswers)
    }
}
