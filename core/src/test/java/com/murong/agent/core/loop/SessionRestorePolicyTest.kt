package com.murong.agent.core.loop

import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionRestorePolicyTest {

    @Test
    fun markPendingApprovalReplayOnly_marksReplayState() {
        val pending = PendingApprovalUi(
            toolName = "shell",
            summary = "运行命令",
            detail = "等待审批",
            rawArgs = """{"command":"gradlew test"}""",
            riskLevel = ApprovalRiskLevel.HIGH
        )

        val restored = assertNotNull(markPendingApprovalReplayOnly(pending))

        assertTrue(restored.isReplayOnly)
        assertEquals(REPLAY_ONLY_APPROVAL_NOTICE, restored.replayNotice)
    }

    @Test
    fun markPendingApprovalReplayOnly_whenRecentSessionHistoryExists_appendsCompactHistorySuffix() {
        val restored = assertNotNull(
            markPendingApprovalReplayOnly(
                pending = PendingApprovalUi(
                    toolName = "shell",
                    summary = "运行命令",
                    detail = "等待审批",
                    rawArgs = """{"command":"gradlew test"}""",
                    riskLevel = ApprovalRiskLevel.HIGH
                ),
                recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                    queries = listOf("发布"),
                    messageReferences = listOf("session-release#34"),
                    snippets = listOf("发布 smoke test 需要覆盖登录校验")
                )
            )
        )

        assertTrue(restored.isReplayOnly)
        assertEquals(
            "$REPLAY_ONLY_APPROVAL_NOTICE（沿用历史线索：查询 发布；消息 session-release#34）",
            restored.replayNotice
        )
    }

    @Test
    fun normalizeRestoredSubagentRun_whenActive_marksCancelledAndClearsLiveFields() {
        val restoredAt = 12345L
        val run = SubagentRunUi(
            runId = "run-1",
            status = "running",
            goal = "检查 pending prompt",
            model = "test-model",
            queuePosition = 2,
            assignedSlot = 1
        )

        val normalized = normalizeRestoredSubagentRun(run, restoredAt)

        assertEquals("cancelled", normalized.status)
        assertEquals(RESTORED_SUBAGENT_INTERRUPTED_MESSAGE, normalized.statusMessage)
        assertEquals(restoredAt, normalized.finishedAt)
        assertEquals(null, normalized.queuePosition)
        assertEquals(null, normalized.assignedSlot)
    }

    @Test
    fun normalizeRestoredSubagentRun_whenAlreadyTerminal_keepsOriginalInstance() {
        val run = SubagentRunUi(
            runId = "run-2",
            status = "completed",
            goal = "完成任务",
            model = "test-model"
        )

        val normalized = normalizeRestoredSubagentRun(run, restoredAt = 999L)

        assertSame(run, normalized)
    }

    @Test
    fun normalizeRestoredSubagentBatch_whenActive_marksFailedAndClearsQueueState() {
        val restoredAt = 54321L
        val batch = SubagentBatchUi(
            batchId = "batch-1",
            parentGoal = "批量检查",
            label = "检查批次",
            status = "queued",
            queuePosition = 3,
            activeSlots = listOf(0, 1),
            queuedRuns = 2,
            runningRuns = 1
        )

        val normalized = normalizeRestoredSubagentBatch(batch, restoredAt)

        assertEquals("failed", normalized.status)
        assertEquals(RESTORED_SUBAGENT_BATCH_INTERRUPTED_MESSAGE, normalized.statusMessage)
        assertEquals(restoredAt, normalized.finishedAt)
        assertEquals(null, normalized.queuePosition)
        assertTrue(normalized.activeSlots.isEmpty())
        assertEquals(0, normalized.queuedRuns)
        assertEquals(0, normalized.runningRuns)
    }

    @Test
    fun normalizeRestoredBackgroundJob_whenActive_marksInterruptedAndClearsLiveFields() {
        val restoredAt = 24680L
        val job = BackgroundJobUi(
            jobId = "job-1",
            toolName = "shell",
            title = "Shell 后台任务",
            summary = "logcat -d",
            status = "running",
            queuePosition = 2,
            assignedSlot = 1
        )

        val normalized = normalizeRestoredBackgroundJob(job, restoredAt)

        assertEquals("interrupted", normalized.status)
        assertEquals(RESTORED_BACKGROUND_JOB_INTERRUPTED_MESSAGE, normalized.statusMessage)
        assertEquals(restoredAt, normalized.finishedAt)
        assertEquals(null, normalized.queuePosition)
        assertEquals(null, normalized.assignedSlot)
    }

    @Test
    fun markPendingAskReplayOnly_marksReplayState() {
        val request = PendingAskRequestUi(
            title = "需要确认",
            questions = listOf(
                AskQuestionUi(
                    id = "q1",
                    question = "选哪个？",
                    options = listOf(
                        AskOptionUi("A"),
                        AskOptionUi("B")
                    )
                )
            )
        )

        val restored = assertNotNull(markPendingAskReplayOnly(request))

        assertTrue(restored.isReplayOnly)
        assertEquals(REPLAY_ONLY_ASK_NOTICE, restored.replayNotice)
        assertFalse(restored.questions.isEmpty())
    }

    @Test
    fun markPendingAskReplayOnly_whenRecentSessionHistoryExists_appendsCompactHistorySuffix() {
        val restored = assertNotNull(
            markPendingAskReplayOnly(
                request = PendingAskRequestUi(
                    title = "需要确认",
                    questions = listOf(
                        AskQuestionUi(
                            id = "q1",
                            question = "选哪个？",
                            options = listOf(
                                AskOptionUi("A"),
                                AskOptionUi("B")
                            )
                        )
                    )
                ),
                recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                    queries = listOf("登录"),
                    sessionIds = listOf("session-login"),
                    snippets = listOf("登录态过期后需要补 token 刷新")
                )
            )
        )

        assertTrue(restored.isReplayOnly)
        assertEquals(
            "$REPLAY_ONLY_ASK_NOTICE（沿用历史线索：查询 登录；会话 session-login）",
            restored.replayNotice
        )
    }

    @Test
    fun buildSessionRestoreReplayNotice_whenReplayStateExists_summarizesRestoredPromptsAndInterruptedRuns() {
        val notice = buildSessionRestoreReplayNotice(
            SessionState(
                pendingApproval = PendingApprovalUi(
                    toolName = "shell",
                    summary = "运行命令",
                    detail = "等待审批",
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
                ),
                subagentRuns = listOf(
                    SubagentRunUi(
                        runId = "run-1",
                        status = "cancelled",
                        goal = "检查恢复态",
                        model = "test-model",
                        statusMessage = RESTORED_SUBAGENT_INTERRUPTED_MESSAGE
                    ),
                    SubagentRunUi(
                        runId = "run-2",
                        status = "completed",
                        goal = "已完成任务",
                        model = "test-model"
                    )
                ),
                subagentBatches = listOf(
                    SubagentBatchUi(
                        batchId = "batch-1",
                        parentGoal = "批量检查恢复态",
                        label = "恢复后的批次",
                        status = "failed",
                        statusMessage = RESTORED_SUBAGENT_BATCH_INTERRUPTED_MESSAGE
                    )
                ),
                backgroundJobs = listOf(
                    BackgroundJobUi(
                        jobId = "job-1",
                        toolName = "shell",
                        title = "Shell 后台任务",
                        summary = "logcat -d",
                        status = "interrupted",
                        statusMessage = RESTORED_BACKGROUND_JOB_INTERRUPTED_MESSAGE
                    )
                ),
                recentFinalReadinessAudits = listOf(
                    FinalReadinessAuditRecord(
                        result = FinalReadinessAuditResult.BLOCKED,
                        recovered = false,
                        receiptKind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                        requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
                        latestSuccessfulWriteToolName = "code_edit"
                    )
                )
            )
        )

        assertNotNull(notice)
        assertTrue(notice.contains("审批请求"))
        assertTrue(notice.contains("提问卡片"))
        assertTrue(notice.contains("1 个子代理执行已标记为中断"))
        assertTrue(notice.contains("1 个子代理批次已标记为中断"))
        assertTrue(notice.contains("1 个后台任务已标记为中断"))
        assertTrue(notice.contains("最近一次最终收口状态"))
        assertTrue(notice.contains("code_edit"))
    }

    @Test
    fun buildSessionRestoreReplayNotice_whenNothingToReplay_returnsNull() {
        val notice = buildSessionRestoreReplayNotice(SessionState())

        assertEquals(null, notice)
    }

    @Test
    fun buildSessionRestoreReplayNotice_whenOnlyRecoveredFinalReadinessAuditExists_returnsAuditSummary() {
        val notice = buildSessionRestoreReplayNotice(
            SessionState(
                recentFinalReadinessAudits = listOf(
                    FinalReadinessAuditRecord(
                        result = FinalReadinessAuditResult.ALLOWED,
                        recovered = true,
                        receiptKind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
                        requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
                        remainingUnsignedSteps = 2
                    )
                )
            )
        )

        assertNotNull(notice)
        assertTrue(notice.contains("最近一次最终收口状态"))
        assertTrue(notice.contains("提醒后已恢复放行"))
        assertTrue(notice.contains("2 个未签收步骤"))
    }

    @Test
    fun buildSessionRestoreReplayNotice_whenFinalReadinessAuditCarriesHistoryRefs_includesHistorySummary() {
        val notice = buildSessionRestoreReplayNotice(
            SessionState(
                recentFinalReadinessAudits = listOf(
                    FinalReadinessAuditRecord(
                        result = FinalReadinessAuditResult.BLOCKED,
                        recovered = false,
                        receiptKind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
                        requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
                        remainingUnsignedSteps = 1,
                        latestSignedOffSessionHistorySessionIds = listOf("session-release"),
                        latestSignedOffSessionHistoryMessageReferences = listOf("session-release#34")
                    )
                )
            )
        )

        assertNotNull(notice)
        assertTrue(notice.contains("最近一次最终收口状态"))
        assertTrue(notice.contains("最近命中的历史会话：session-release"))
        assertTrue(notice.contains("最近命中的历史消息：session-release#34"))
    }

    @Test
    fun buildSessionRestoreReplayNotice_whenReplayStateHasSessionLevelHistory_mergesIntoNotice() {
        val notice = buildSessionRestoreReplayNotice(
            SessionState(
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
                ),
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "发布前检查",
                    summary = "先确认 smoke test",
                    steps = listOf("检查发布脚本"),
                    recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                        queries = listOf("发布"),
                        sessionIds = listOf("session-release"),
                        messageReferences = listOf("session-release#34"),
                        snippets = listOf("发布 smoke test 需要覆盖登录校验"),
                        excerptWindows = listOf("2-4/6（指定消息附近）")
                    )
                )
            )
        )

        assertNotNull(notice)
        assertTrue(notice.contains("提问卡片"))
        assertTrue(notice.contains("最近查询：发布"))
        assertTrue(notice.contains("最近命中的历史会话：session-release"))
        assertTrue(notice.contains("最近命中的历史消息：session-release#34"))
        assertTrue(notice.contains("最近历史线索摘要：发布 smoke test 需要覆盖登录校验"))
    }
}
