package com.murong.agent.ui.chat

import com.murong.agent.core.loop.ClarificationRequestUi
import com.murong.agent.core.loop.ClarificationSource
import com.murong.agent.core.loop.FileMentionUi
import com.murong.agent.core.loop.SessionHistoryReferenceClueUi
import com.murong.agent.core.loop.WorkflowPlanStatusUi
import com.murong.agent.core.loop.WorkflowPlanUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingExecutionPresentationTest {

    @Test
    fun toWorkflowPlanPromptPresentation_projectsPlanIntoSharedPromptPresentation() {
        val presentation = workflowPlan().toWorkflowPlanPromptPresentation()

        assertEquals("plan-1", presentation.requestId)
        assertEquals("执行计划", presentation.title)
        assertEquals("待执行", presentation.statusLabel)
        assertEquals("阶段状态", presentation.stageSectionTitle)
        assertEquals("步骤进度", presentation.progressSectionTitle)
        assertEquals("关联文件", presentation.mentionedFilesSectionTitle)
        assertEquals("按计划执行", presentation.executeLabel)
        assertEquals("1/2", presentation.progressLabel)
        assertEquals(2, presentation.stageChips.size)
        assertTrue(presentation.stageChips.first().isCurrent)
        assertEquals(2, presentation.stepRows.size)
        assertEquals("当前", presentation.stepRows.first().badgeLabel)
        assertEquals("2", presentation.stepRows.last().badgeLabel)
        assertEquals(listOf("app/src/MainActivity.kt"), presentation.mentionedFiles)
        assertTrue(presentation.nextStepHint.contains("确认目标"))
        val recentHistoryClue = assertNotNull(presentation.recentHistoryClue)
        assertEquals("最近历史线索", recentHistoryClue.title)
        assertTrue(recentHistoryClue.summary.contains("登录态过期后需要补 token 刷新"))
        assertTrue(recentHistoryClue.summary.contains("session-login#21"))
        assertEquals(
            listOf("历史消息", "线索摘要"),
            recentHistoryClue.detailRows.take(2).map { it.label }
        )
        assertTrue(recentHistoryClue.detailRows.any { it.label == "摘录窗口" })
    }

    @Test
    fun toClarificationPromptPresentation_projectsRequestIntoSharedPromptPresentation() {
        val presentation = clarificationRequest().toClarificationPromptPresentation()

        assertEquals("clarify-1", presentation.requestId)
        assertEquals("自动分流澄清", presentation.title)
        assertTrue(presentation.subtitle!!.contains("信息还不够完整"))
        assertEquals("第 2 轮 / 最多 3 轮", presentation.turnLabel)
        assertEquals("已确认 1 条澄清信息，本轮会在此基础上继续追问最关键的缺口。", presentation.previousAnswersSummary)
        val recentHistoryClue = assertNotNull(presentation.recentHistoryClue)
        assertEquals("最近历史线索", recentHistoryClue.title)
        assertTrue(recentHistoryClue.summary.contains("发布 smoke test 需要覆盖登录校验"))
        assertEquals(
            listOf("历史消息", "线索摘要"),
            recentHistoryClue.detailRows.map { it.label }
        )
        assertEquals("输入你的补充回答", presentation.inputPlaceholder)
        assertEquals("继续判断", presentation.submitLabel)
    }

    @Test
    fun toClarificationPromptPresentation_usesContinueExecutionLabelOnLastTurn() {
        val presentation = clarificationRequest(
            turnIndex = 3,
            maxTurns = 3,
            source = ClarificationSource.AUTO_INTERRUPT
        ).toClarificationPromptPresentation()

        assertEquals("执行中自动打断", presentation.title)
        assertEquals("继续执行", presentation.submitLabel)
    }

    private fun workflowPlan(): WorkflowPlanUi {
        return WorkflowPlanUi(
            id = "plan-1",
            goal = "完成 shared presentation 收口",
            summary = "先统一 source，再统一 host route。",
            steps = listOf("收口 source", "宿主统一消费"),
            stageLabels = listOf("分析", "改造"),
            currentStageIndex = 0,
            currentStepIndex = 1,
            nextStepHint = "先确认目标与边界，再继续执行。",
            status = WorkflowPlanStatusUi.READY,
            mentionedFiles = listOf(FileMentionUi(path = "app/src/MainActivity.kt", displayPath = "app/src/MainActivity.kt")),
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                snippets = listOf("登录态过期后需要补 token 刷新"),
                messageReferences = listOf("session-login#21"),
                excerptWindows = listOf("2-4/6（指定消息附近）")
            ),
            rawPlan = "1. 收口 source\n2. 宿主统一消费"
        )
    }

    private fun clarificationRequest(
        turnIndex: Int = 2,
        maxTurns: Int = 3,
        source: ClarificationSource = ClarificationSource.AUTO_ROUTE
    ): ClarificationRequestUi {
        return ClarificationRequestUi(
            id = "clarify-1",
            goal = "继续推进 pending prompt",
            question = "优先推进哪条 source 下沉？",
            previousAnswers = listOf(
                com.murong.agent.core.loop.ClarificationAnswerUi(
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
