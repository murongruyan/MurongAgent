package com.murong.agent.ui.chat

import com.murong.agent.core.loop.AskOptionUi
import com.murong.agent.core.loop.AskQuestionUi
import com.murong.agent.core.loop.ClarificationAnswerUi
import com.murong.agent.core.loop.ClarificationRequestUi
import com.murong.agent.core.loop.ClarificationSource
import com.murong.agent.core.loop.FileMentionUi
import com.murong.agent.core.loop.PendingAskRequestUi
import com.murong.agent.core.loop.SessionHistoryReferenceClueUi
import com.murong.agent.core.loop.WorkflowPlanStatusUi
import com.murong.agent.core.loop.WorkflowPlanUi

internal object AskPromptPresentationTestFixtures {
    fun pendingAsk(
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

internal object PendingExecutionPresentationTestFixtures {
    fun workflowPlan(): WorkflowPlanUi {
        return WorkflowPlanUi(
            id = "plan-1",
            goal = "完成 shared host 收口",
            summary = "先统一 source，再统一 host route。",
            steps = listOf("收口 source", "宿主统一消费"),
            stageLabels = listOf("分析", "改造"),
            currentStageIndex = 0,
            currentStepIndex = 1,
            nextStepHint = "先确认目标与边界，再继续执行。",
            status = WorkflowPlanStatusUi.READY,
            mentionedFiles = listOf(
                FileMentionUi(path = "app/src/MainActivity.kt", displayPath = "app/src/MainActivity.kt")
            ),
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                snippets = listOf("登录态过期后需要补 token 刷新"),
                messageReferences = listOf("session-login#21"),
                excerptWindows = listOf("2-4/6（指定消息附近）")
            ),
            rawPlan = "1. 收口 source\n2. 宿主统一消费"
        )
    }

    fun clarificationRequest(
        turnIndex: Int = 2,
        maxTurns: Int = 3,
        source: ClarificationSource = ClarificationSource.AUTO_ROUTE
    ): ClarificationRequestUi {
        return ClarificationRequestUi(
            id = "clarify-1",
            goal = "继续推进 pending prompt",
            question = "优先推进哪条 source 下沉？",
            previousAnswers = listOf(
                ClarificationAnswerUi(
                    question = "已有统一 route 吗？",
                    answer = "已经有"
                )
            ),
            turnIndex = turnIndex,
            maxTurns = maxTurns,
            source = source,
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                snippets = listOf("发布 smoke test 需要覆盖登录校验"),
                messageReferences = listOf("session-release#34")
            )
        )
    }
}
