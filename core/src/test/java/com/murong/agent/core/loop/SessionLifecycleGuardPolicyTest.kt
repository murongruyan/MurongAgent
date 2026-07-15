package com.murong.agent.core.loop

import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionLifecycleGuardPolicyTest {

    @Test
    fun buildSessionLifecycleGuardNotice_whenProcessing_blocksAction() {
        val notice = buildSessionLifecycleGuardNotice(
            SessionState(isProcessing = true),
            SessionLifecycleAction.LOAD_SESSION
        )

        assertNotNull(notice)
        assertTrue(notice.contains("处理中"))
        assertTrue(notice.contains("切换会话"))
    }

    @Test
    fun buildSessionLifecycleGuardNotice_whenLivePendingApproval_blocksAction() {
        val notice = buildSessionLifecycleGuardNotice(
            SessionState(
                pendingApproval = PendingApprovalUi(
                    toolName = "shell",
                    summary = "运行命令",
                    detail = "等待审批",
                    rawArgs = "{}",
                    riskLevel = ApprovalRiskLevel.HIGH
                )
            ),
            SessionLifecycleAction.NEW_SESSION
        )

        assertNotNull(notice)
        assertTrue(notice.contains("待处理的交互卡片"))
        assertTrue(notice.contains("新建会话"))
    }

    @Test
    fun buildSessionLifecycleGuardNotice_whenRecentSessionHistoryExists_appendsCompactHistorySuffix() {
        val notice = buildSessionLifecycleGuardNotice(
            SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "发布前检查",
                    summary = "先确认 smoke test",
                    steps = listOf("检查发布脚本"),
                    recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                        queries = listOf("发布"),
                        messageReferences = listOf("session-release#34"),
                        snippets = listOf("发布 smoke test 需要覆盖登录校验")
                    )
                )
            ),
            SessionLifecycleAction.LOAD_SESSION
        )

        assertNotNull(notice)
        assertTrue(notice.contains("待处理的交互卡片"))
        assertTrue(notice.contains("切换会话"))
        assertTrue(notice.contains("沿用历史线索：查询 发布；消息 session-release#34"))
    }

    @Test
    fun buildSessionLifecycleGuardNotice_whenPromptIsReplayOnly_doesNotBlock() {
        val notice = buildSessionLifecycleGuardNotice(
            SessionState(
                pendingApproval = PendingApprovalUi(
                    toolName = "shell",
                    summary = "运行命令",
                    detail = "恢复态审批",
                    rawArgs = "{}",
                    riskLevel = ApprovalRiskLevel.HIGH,
                    isReplayOnly = true
                ),
                pendingAskRequest = PendingAskRequestUi(
                    title = "需要确认",
                    questions = listOf(
                        AskQuestionUi(
                            id = "q1",
                            question = "继续吗？",
                            options = listOf(AskOptionUi("继续"))
                        )
                    ),
                    isReplayOnly = true
                )
            ),
            SessionLifecycleAction.LOAD_SESSION
        )

        assertNull(notice)
    }

    @Test
    fun buildSessionLifecycleGuardNotice_whenActiveSubagentRun_blocksAction() {
        val notice = buildSessionLifecycleGuardNotice(
            SessionState(
                subagentRuns = listOf(
                    SubagentRunUi(
                        runId = "run-1",
                        status = "running",
                        goal = "修复问题",
                        model = "test-model"
                    )
                )
            ),
            SessionLifecycleAction.DELETE_SESSION
        )

        assertNotNull(notice)
        assertTrue(notice.contains("活跃子代理执行"))
        assertTrue(notice.contains("删除会话"))
    }

    @Test
    fun buildSessionLifecycleGuardNotice_whenBackgroundJobRunning_blocksAction() {
        val notice = buildSessionLifecycleGuardNotice(
            SessionState(
                backgroundJobs = listOf(
                    BackgroundJobUi(
                        toolName = "shell",
                        title = "Shell 后台任务",
                        summary = "logcat -d",
                        status = "running"
                    )
                )
            ),
            SessionLifecycleAction.LOAD_SESSION
        )

        assertNotNull(notice)
        assertTrue(notice.contains("后台任务"))
        assertTrue(notice.contains("切换会话"))
    }

    @Test
    fun hasActiveSubagentWork_whenOnlyTerminalStatuses_returnsFalse() {
        val state = SessionState(
            subagentRuns = listOf(
                SubagentRunUi(
                    runId = "run-1",
                    status = "completed",
                    goal = "已完成",
                    model = "test-model"
                )
            ),
            subagentBatches = listOf(
                SubagentBatchUi(
                    batchId = "batch-1",
                    parentGoal = "已完成",
                    label = "完成批次",
                    status = "failed"
                )
            )
        )

        assertFalse(hasActiveSubagentWork(state))
        assertEquals(null, buildSessionLifecycleGuardNotice(state, SessionLifecycleAction.START_TASK))
    }

    @Test
    fun buildSessionDeletionNotice_whenDeletingCurrentSession_mentionsFallbackToBlankChat() {
        assertEquals("当前会话已删除，已切回新对话。", buildSessionDeletionNotice(deletedCurrentSession = true))
    }

    @Test
    fun buildSessionDeletionNotice_whenDeletingOtherSession_mentionsListRefresh() {
        assertEquals("会话已删除，列表已同步更新。", buildSessionDeletionNotice(deletedCurrentSession = false))
    }

    @Test
    fun buildSessionDeletionNotice_whenRecentSessionHistoryExists_appendsCompactHistorySuffix() {
        val notice = buildSessionDeletionNotice(
            deletedCurrentSession = true,
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                queries = listOf("发布"),
                messageReferences = listOf("session-release#34"),
                snippets = listOf("发布 smoke test 需要覆盖登录校验")
            )
        )

        assertEquals(
            "当前会话已删除，已切回新对话。（沿用历史线索：查询 发布；消息 session-release#34）",
            notice
        )
    }
}
