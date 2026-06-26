package com.murong.agent.core.loop

import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeStatusPolicyTest {

    @Test
    fun resolveRuntimeStatusSnapshot_prefersPendingApprovalOverOtherStates() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                isProcessing = true,
                pendingApproval = PendingApprovalUi(
                    toolName = "shell",
                    summary = "运行命令",
                    detail = "等待审批",
                    rawArgs = "{}",
                    riskLevel = ApprovalRiskLevel.HIGH
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.PENDING_APPROVAL, snapshot.kind)
        assertTrue(snapshot.message.contains("运行命令"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_includesApprovalPostureReasonForPendingApproval() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                pendingApproval = PendingApprovalUi(
                    toolName = "ask_user",
                    summary = "向用户确认发布方案",
                    detail = "等待用户确认",
                    rawArgs = "{}",
                    riskLevel = ApprovalRiskLevel.LOW,
                    explanationLabel = "关键工具始终审批",
                    explanationDetail = "工具 `ask_user` 属于关键配置或交互操作，即使在自动模式下也需要重新人工确认。"
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.PENDING_APPROVAL, snapshot.kind)
        assertTrue(snapshot.message.contains("关键工具始终审批"))
        assertTrue(snapshot.message.contains("向用户确认发布方案"))
        assertTrue(snapshot.message.contains("重新人工确认"))
        assertEquals(RuntimeApprovalPostureDisplayMode.LABEL_ONLY, snapshot.approvalPostureDisplayMode)
    }

    @Test
    fun resolveRuntimeStatusSnapshot_hidesApprovalPostureForReplayOnlyPendingApproval() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                pendingApproval = PendingApprovalUi(
                    toolName = "shell",
                    summary = "运行命令",
                    detail = "等待审批",
                    rawArgs = "{}",
                    riskLevel = ApprovalRiskLevel.HIGH,
                    isReplayOnly = true,
                    replayNotice = "这是从已恢复会话中重放的审批请求。"
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.PENDING_APPROVAL, snapshot.kind)
        assertEquals("这是从已恢复会话中重放的审批请求。", snapshot.message)
        assertEquals(false, snapshot.showApprovalPosture)
        assertEquals(RuntimeApprovalPostureDisplayMode.HIDE, snapshot.approvalPostureDisplayMode)
    }

    @Test
    fun resolveRuntimeStatusSnapshot_whenReplayOnlyPendingApprovalLacksNotice_fallsBackToCanonicalReplayCopy() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                pendingApproval = PendingApprovalUi(
                    toolName = "shell",
                    summary = "运行命令",
                    detail = "等待审批",
                    rawArgs = "{}",
                    riskLevel = ApprovalRiskLevel.HIGH,
                    isReplayOnly = true,
                    replayNotice = null
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.PENDING_APPROVAL, snapshot.kind)
        assertEquals(REPLAY_ONLY_APPROVAL_NOTICE, snapshot.message)
        assertEquals(false, snapshot.showApprovalPosture)
    }

    @Test
    fun resolveRuntimeStatusSnapshot_hidesApprovalPostureForReplayOnlyPendingAsk() {
        val snapshot = resolveRuntimeStatusSnapshot(
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
                    isReplayOnly = true,
                    replayNotice = "这是从已恢复会话中重放的提问卡片。"
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.PENDING_ASK, snapshot.kind)
        assertEquals("这是从已恢复会话中重放的提问卡片。", snapshot.message)
        assertEquals(false, snapshot.showApprovalPosture)
        assertEquals(RuntimeApprovalPostureDisplayMode.HIDE, snapshot.approvalPostureDisplayMode)
    }

    @Test
    fun resolveRuntimeStatusSnapshot_whenReplayOnlyPendingAskLacksNotice_fallsBackToCanonicalReplayCopy() {
        val snapshot = resolveRuntimeStatusSnapshot(
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
                    isReplayOnly = true,
                    replayNotice = null
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.PENDING_ASK, snapshot.kind)
        assertEquals(REPLAY_ONLY_ASK_NOTICE, snapshot.message)
        assertEquals(false, snapshot.showApprovalPosture)
    }

    @Test
    fun resolveRuntimeStatusSnapshot_keepsFullApprovalPostureMessageForLivePendingAsk() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                pendingAskRequest = PendingAskRequestUi(
                    title = "需要确认",
                    questions = listOf(
                        AskQuestionUi(
                            id = "q1",
                            question = "优先修哪一块？",
                            options = listOf(AskOptionUi("runtime status"))
                        )
                    )
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.PENDING_ASK, snapshot.kind)
        assertEquals(RuntimeApprovalPostureDisplayMode.FULL_MESSAGE, snapshot.approvalPostureDisplayMode)
    }

    @Test
    fun resolveRuntimeStatusSnapshot_reportsExecutingWhenOnlyProcessingIsActive() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(isProcessing = true)
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.EXECUTING, snapshot.kind)
    }

    @Test
    fun resolveRuntimeStatusSnapshot_reportsPendingWorkflowPlanBeforeExecuting() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                isProcessing = true,
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复工作流状态栏",
                    summary = "先生成计划，再等待用户确认执行。"
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.PENDING_WORKFLOW_PLAN, snapshot.kind)
        assertTrue(snapshot.message.contains("修复工作流状态栏"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_reportsPendingClarificationQuestion() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复运行态快照",
                    question = "你希望先统一计划确认态，还是先统一澄清回答态？"
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.PENDING_CLARIFICATION, snapshot.kind)
        assertTrue(snapshot.message.contains("统一计划确认态"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_reportsBackgroundSubagentWork() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                subagentRuns = listOf(
                    SubagentRunUi(
                        runId = "run-1",
                        status = "running",
                        goal = "修复问题",
                        statusMessage = "正在执行子代理任务，占用槽位 1/2。",
                        model = "test-model"
                    ),
                    SubagentRunUi(
                        runId = "run-2",
                        status = "queued",
                        goal = "补充验证",
                        statusMessage = "已进入子代理队列，当前位于第 2 位。",
                        queuePosition = 2,
                        model = "test-model"
                    )
                ),
                subagentBatches = listOf(
                    SubagentBatchUi(
                        batchId = "batch-1",
                        parentGoal = "修复问题",
                        label = "批次一",
                        status = "queued"
                    )
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.BACKGROUND_ACTIVITY, snapshot.kind)
        assertEquals("后台任务运行中", snapshot.title)
        assertEquals(BackgroundActivityFocusKind.RUNNING, snapshot.backgroundActivityFocusKind)
        assertEquals(RuntimeApprovalPostureDisplayMode.FULL_MESSAGE, snapshot.approvalPostureDisplayMode)
        assertEquals("后台运行", snapshot.backgroundActivityFocusTelemetry?.label)
        assertEquals(snapshot.message, snapshot.backgroundActivityFocusTelemetry?.summary)
        assertTrue(snapshot.message.contains("运行中 1 个"))
        assertTrue(snapshot.message.contains("排队中 1 个"))
        assertTrue(snapshot.message.contains("排队批次 1 个"))
        assertTrue(snapshot.message.contains("占用槽位 1/2"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_prefersRunningSubagentDetailOverQueuedOne() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                subagentRuns = listOf(
                    SubagentRunUi(
                        runId = "run-queued",
                        status = "queued",
                        goal = "等待中的任务",
                        statusMessage = "已进入子代理队列，当前位于第 1 位。",
                        queuePosition = 1,
                        model = "test-model"
                    ),
                    SubagentRunUi(
                        runId = "run-running",
                        status = "running",
                        goal = "执行中的任务",
                        batchLabel = "主修复批次",
                        batchIndex = 2,
                        batchSize = 3,
                        statusMessage = "正在执行子代理任务，占用槽位 2/2。",
                        assignedSlot = 2,
                        model = "test-model"
                    )
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.BACKGROUND_ACTIVITY, snapshot.kind)
        assertTrue(snapshot.message.contains("[主修复批次（2/3）] 执行中的任务"))
        assertTrue(snapshot.message.contains("当前: "))
        assertTrue(snapshot.message.contains("正在执行子代理任务"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_reportsQueuedSubagentBatchTitleWhenNoRunHasStarted() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                subagentBatches = listOf(
                    SubagentBatchUi(
                        batchId = "batch-queued",
                        parentGoal = "修复问题",
                        label = "后台批次",
                        status = "queued",
                        queuePosition = 1
                    )
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.BACKGROUND_ACTIVITY, snapshot.kind)
        assertEquals("后台任务排队中", snapshot.title)
        assertEquals(BackgroundActivityFocusKind.QUEUED, snapshot.backgroundActivityFocusKind)
        assertTrue(snapshot.message.contains("当前: 批次 `后台批次` 修复问题"))
        assertTrue(snapshot.message.contains("排队批次 1 个"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_truncatesLongFocusedSubagentRunIdentity() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                subagentRuns = listOf(
                    SubagentRunUi(
                        runId = "run-long",
                        status = "running",
                        goal = "这是一个非常长的执行目标，需要在顶部统一状态条中被截断以避免挤占过多空间，并且不能把整段目标都挤进去",
                        batchLabel = "这是一个非常长而且明显会超过限制的主修复批次标签名字",
                        batchIndex = 3,
                        batchSize = 8,
                        statusMessage = "正在执行子代理任务，占用槽位 1/2。",
                        assignedSlot = 1,
                        model = "test-model"
                    )
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.BACKGROUND_ACTIVITY, snapshot.kind)
        assertTrue(snapshot.message.contains("["))
        assertTrue(snapshot.message.contains("（3/8）]"))
        assertTrue(snapshot.message.contains("..."))
        assertTrue(snapshot.message.contains("这是一个非常长的执行目标"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_truncatesLongFocusedBatchIdentity() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                subagentBatches = listOf(
                    SubagentBatchUi(
                        batchId = "batch-long",
                        parentGoal = "这是一个很长的父级目标描述，需要被压缩后再放进顶部统一状态条，而且不能完整铺满整个状态区域",
                        label = "这是一个特别长而且明显会超过限制的后台编排批次标签名字",
                        status = "queued",
                        queuePosition = 1
                    )
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.BACKGROUND_ACTIVITY, snapshot.kind)
        assertTrue(snapshot.message.contains("批次 `"))
        assertTrue(snapshot.message.contains("...`"))
        assertTrue(snapshot.message.contains("这是一个很长的父级目标描述"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_reportsCancellingSubagentWorkAsBackgroundStatus() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                subagentRuns = listOf(
                    SubagentRunUi(
                        runId = "run-cancelling",
                        status = "cancelling",
                        goal = "回收长时间运行的子代理",
                        statusMessage = "已请求终止，等待当前步骤结束。",
                        model = "test-model"
                    )
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.BACKGROUND_ACTIVITY, snapshot.kind)
        assertEquals("后台任务终止中", snapshot.title)
        assertEquals(BackgroundActivityFocusKind.CANCELLING, snapshot.backgroundActivityFocusKind)
        assertTrue(snapshot.message.contains("终止中 1 个"))
        assertTrue(snapshot.message.contains("已请求终止"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_reportsBackgroundJobsBeforeIdle() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                backgroundJobs = listOf(
                    BackgroundJobUi(
                        jobId = "job-running",
                        toolName = "shell",
                        title = "Shell 后台任务",
                        summary = "logcat -d",
                        status = "running",
                        statusMessage = "后台任务已启动，正在执行。"
                    ),
                    BackgroundJobUi(
                        jobId = "job-queued",
                        toolName = "shell",
                        title = "Shell 后台任务",
                        summary = "dmesg | tail",
                        status = "queued",
                        statusMessage = "后台任务已入队，等待可用执行槽位。",
                        queuePosition = 1
                    )
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.BACKGROUND_ACTIVITY, snapshot.kind)
        assertEquals("后台任务运行中", snapshot.title)
        assertTrue(snapshot.message.contains("运行中 1 个"))
        assertTrue(snapshot.message.contains("排队中 1 个"))
        assertTrue(snapshot.message.contains("logcat -d"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_prefersPendingApprovalTitleOverQueuedBackgroundWork() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                backgroundJobs = listOf(
                    BackgroundJobUi(
                        jobId = "job-queued",
                        toolName = "shell",
                        title = "Shell 后台任务",
                        summary = "dmesg | tail",
                        status = "queued",
                        statusMessage = "后台任务已入队，等待可用执行槽位。",
                        queuePosition = 1
                    )
                ),
                subagentRuns = listOf(
                    SubagentRunUi(
                        runId = "run-approval",
                        status = "pending_approval",
                        goal = "请求高权限修复",
                        statusMessage = "子代理需要更高工具权限。",
                        model = "test-model"
                    )
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.BACKGROUND_ACTIVITY, snapshot.kind)
        assertEquals("后台任务待审批", snapshot.title)
        assertEquals(BackgroundActivityFocusKind.PENDING_APPROVAL, snapshot.backgroundActivityFocusKind)
        assertEquals(RuntimeApprovalPostureDisplayMode.LABEL_ONLY, snapshot.approvalPostureDisplayMode)
        assertEquals("后台待审批", snapshot.backgroundActivityFocusTelemetry?.label)
        assertEquals(snapshot.message, snapshot.backgroundActivityFocusTelemetry?.summary)
        assertTrue(snapshot.message.contains("待审批 1 个"))
        assertTrue(snapshot.message.contains("排队中 1 个"))
    }

    @Test
    fun resolveBackgroundActivityRuntimeSummary_usesApprovalLabelOnlyWhenBackgroundFocusIsPendingApproval() {
        val summary = resolveBackgroundActivityRuntimeSummary(
            activeJobs = listOf(
                BackgroundJobUi(
                    jobId = "job-queued",
                    toolName = "shell",
                    title = "Shell 后台任务",
                    summary = "dmesg | tail",
                    status = "queued",
                    statusMessage = "后台任务已入队，等待可用执行槽位。",
                    queuePosition = 1
                )
            ),
            activeRuns = listOf(
                SubagentRunUi(
                    runId = "run-approval",
                    status = "pending_approval",
                    goal = "请求高权限修复",
                    statusMessage = "子代理需要更高工具权限。",
                    model = "test-model"
                )
            ),
            activeBatches = emptyList()
        )

        assertEquals("后台任务待审批", summary.title)
        assertEquals(BackgroundActivityFocusKind.PENDING_APPROVAL, summary.focusKind)
        assertEquals(RuntimeApprovalPostureDisplayMode.LABEL_ONLY, summary.approvalPostureDisplayMode)
        assertEquals("后台待审批", summary.focusTelemetry.label)
        assertEquals(summary.message, summary.focusTelemetry.summary)
        assertTrue(summary.message.contains("待审批 1 个"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_mergesBackgroundJobsAndSubagentsIntoSingleBackgroundKind() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                backgroundJobs = listOf(
                    BackgroundJobUi(
                        jobId = "job-running",
                        toolName = "shell",
                        title = "Shell 后台任务",
                        summary = "logcat -d",
                        status = "running",
                        statusMessage = "后台任务已启动，正在执行。"
                    )
                ),
                subagentRuns = listOf(
                    SubagentRunUi(
                        runId = "run-1",
                        status = "queued",
                        goal = "补充验证",
                        statusMessage = "已进入子代理队列，当前位于第 2 位。",
                        queuePosition = 2,
                        model = "test-model"
                    )
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.BACKGROUND_ACTIVITY, snapshot.kind)
        assertTrue(snapshot.message.contains("会话级后台任务未终结"))
        assertTrue(snapshot.message.contains("当前仍有后台子代理任务未终结"))
        assertTrue(snapshot.message.contains("排队中 1 个"))
        assertTrue(snapshot.message.contains("logcat -d"))
        assertTrue(snapshot.message.contains("补充验证"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_reportsAccurateSubagentCompanionStatuses() {
        val snapshot = resolveRuntimeStatusSnapshot(
            SessionState(
                backgroundJobs = listOf(
                    BackgroundJobUi(
                        jobId = "job-running",
                        toolName = "shell",
                        title = "Shell 后台任务",
                        summary = "logcat -d",
                        status = "running",
                        statusMessage = "后台任务已启动，正在执行。"
                    )
                ),
                subagentRuns = listOf(
                    SubagentRunUi(
                        runId = "run-approval",
                        status = "pending_approval",
                        goal = "请求高权限修复",
                        statusMessage = "子代理需要更高工具权限。",
                        model = "test-model"
                    ),
                    SubagentRunUi(
                        runId = "run-queued",
                        status = "queued",
                        goal = "补充验证",
                        statusMessage = "已进入子代理队列，当前位于第 2 位。",
                        queuePosition = 2,
                        model = "test-model"
                    )
                ),
                subagentBatches = listOf(
                    SubagentBatchUi(
                        batchId = "batch-approval",
                        parentGoal = "批量修复",
                        label = "需要提权的批次",
                        status = "pending_approval"
                    ),
                    SubagentBatchUi(
                        batchId = "batch-queued",
                        parentGoal = "批量验证",
                        label = "排队批次",
                        status = "queued",
                        queuePosition = 1
                    )
                )
            )
        )

        assertNotNull(snapshot)
        assertEquals(RuntimeStatusKind.BACKGROUND_ACTIVITY, snapshot.kind)
        assertTrue(snapshot.message.contains("当前仍有后台子代理任务未终结"))
        assertTrue(snapshot.message.contains("待审批 1 个"))
        assertTrue(snapshot.message.contains("排队中 1 个"))
        assertTrue(snapshot.message.contains("待审批批次 1 个"))
        assertTrue(snapshot.message.contains("排队批次 1 个"))
    }

    @Test
    fun resolveRuntimeStatusSnapshot_returnsNullWhenIdle() {
        assertNull(resolveRuntimeStatusSnapshot(SessionState()))
    }
}
