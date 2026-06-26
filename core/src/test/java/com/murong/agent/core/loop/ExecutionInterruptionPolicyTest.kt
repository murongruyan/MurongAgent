package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals

class ExecutionInterruptionPolicyTest {

    @Test
    fun buildResumeExecutionPayload_usesGoalDirectlyWhenNoClarificationAnswersExist() {
        val payload = buildResumeExecutionPayload(
            executionGoal = "修复问题",
            existingClarificationAnswers = emptyList()
        ) { goal, answers ->
            "unexpected: $goal / ${answers.size}"
        }

        assertEquals("继续执行: 修复问题", payload.userVisibleText)
        assertEquals("修复问题", payload.modelInput)
        assertEquals("修复问题", payload.executionGoal)
        assertEquals(emptyList(), payload.existingClarificationAnswers)
    }

    @Test
    fun buildResumeExecutionPayload_usesClarificationPromptWhenAnswersExist() {
        val answers = listOf(
            ClarificationAnswerUi(
                question = "要不要先跑测试？",
                answer = "先跑"
            )
        )

        val payload = buildResumeExecutionPayload(
            executionGoal = "修复问题",
            existingClarificationAnswers = answers
        ) { goal, existingAnswers ->
            "prompt:$goal:${existingAnswers.size}"
        }

        assertEquals("继续执行: 修复问题", payload.userVisibleText)
        assertEquals("prompt:修复问题:1", payload.modelInput)
        assertEquals(answers, payload.existingClarificationAnswers)
    }

    @Test
    fun buildResumeExecutionPayload_whenStateAwareBuilderIsUsed_mergesSessionAndLocalHistoryClues() {
        val answers = listOf(
            ClarificationAnswerUi(
                question = "要不要先跑测试？",
                answer = "先跑"
            )
        )

        val payload = buildResumeExecutionPayload(
            executionGoal = "修复问题",
            existingClarificationAnswers = answers,
            state = SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复问题",
                    summary = "先确认历史约束",
                    recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                        queries = listOf("发布"),
                        sessionIds = listOf("session-release")
                    )
                )
            ),
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                messageReferences = listOf("session-login#21")
            )
        ) { goal, existingAnswers, state, clue ->
            "${goal}:${existingAnswers.size}:${state.recentSessionHistoryClue?.queries?.joinToString()}:" +
                "${clue?.messageReferences?.joinToString()}"
        }

        assertEquals("继续执行: 修复问题", payload.userVisibleText)
        assertEquals("修复问题:1:发布:session-login#21", payload.modelInput)
        assertEquals(answers, payload.existingClarificationAnswers)
    }

    @Test
    fun buildExecutionInterruptionClarificationRequest_deduplicatesFilesAndCarriesAnswersForward() {
        val answers = listOf(
            ClarificationAnswerUi(
                question = "要不要先跑测试？",
                answer = "先跑"
            )
        )
        val request = buildExecutionInterruptionClarificationRequest(
            executionGoal = "修复问题",
            question = "还缺少目标环境信息。",
            mentionedFiles = listOf(
                FileMentionUi(path = "a.kt", displayPath = "a.kt"),
                FileMentionUi(path = "a.kt", displayPath = "src/a.kt"),
                FileMentionUi(path = "b.kt", displayPath = "b.kt")
            ),
            existingClarificationAnswers = answers,
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                messageReferences = listOf("session-login#21"),
                snippets = listOf("登录态过期后需要补 token 刷新")
            )
        )

        assertEquals("修复问题", request.goal)
        assertEquals("还缺少目标环境信息。", request.question)
        assertEquals(listOf("a.kt", "b.kt"), request.mentionedFiles.map { it.path })
        assertEquals(answers, request.previousAnswers)
        assertEquals(2, request.turnIndex)
        assertEquals(MAX_CLARIFICATION_TURNS, request.maxTurns)
        assertEquals(ClarificationSource.AUTO_INTERRUPT, request.source)
        assertEquals(listOf("session-login#21"), request.recentSessionHistoryClue?.messageReferences)
    }

    @Test
    fun buildExecutionInterruptionClarificationRequest_expandsMaxTurnsWhenAnswersAlreadyExceedDefault() {
        val answers = List(MAX_CLARIFICATION_TURNS + 1) { index ->
            ClarificationAnswerUi(
                question = "问题 ${index + 1}",
                answer = "回答 ${index + 1}"
            )
        }

        val request = buildExecutionInterruptionClarificationRequest(
            executionGoal = "修复问题",
            question = "还缺一个条件。",
            mentionedFiles = emptyList(),
            existingClarificationAnswers = answers
        )

        assertEquals(MAX_CLARIFICATION_TURNS + 2, request.turnIndex)
        assertEquals(MAX_CLARIFICATION_TURNS + 2, request.maxTurns)
    }

    @Test
    fun buildExecutionInterruptionClarificationRequest_whenStateAwareOverloadIsUsed_mergesSessionAndLocalHistoryClues() {
        val request = buildExecutionInterruptionClarificationRequest(
            executionGoal = "修复问题",
            question = "还缺少目标环境信息。",
            mentionedFiles = emptyList(),
            existingClarificationAnswers = emptyList(),
            state = SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复问题",
                    summary = "先确认历史约束",
                    recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                        queries = listOf("发布"),
                        sessionIds = listOf("session-release")
                    )
                )
            ),
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                messageReferences = listOf("session-login#21"),
                snippets = listOf("登录态过期后需要补 token 刷新")
            )
        )

        assertEquals(listOf("发布"), request.recentSessionHistoryClue?.queries)
        assertEquals(listOf("session-release"), request.recentSessionHistoryClue?.sessionIds)
        assertEquals(listOf("session-login#21"), request.recentSessionHistoryClue?.messageReferences)
        assertEquals(listOf("登录态过期后需要补 token 刷新"), request.recentSessionHistoryClue?.snippets)
    }
}
