package com.murong.agent.core.loop

import com.murong.agent.core.tool.ApprovalRiskLevel
import com.murong.agent.core.tool.WorkflowStepCompletion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkflowExecutionPolicyTest {

    @Test
    fun resolveWorkflowExecutionFinalStatus_blocksWhenClarificationIsPending() {
        val status = resolveWorkflowExecutionFinalStatus(
            SessionState(
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复问题",
                    question = "要不要先跑测试？"
                )
            )
        )

        assertEquals(WorkflowPlanStatusUi.BLOCKED, status)
    }

    @Test
    fun buildBlockedWorkflowExecutionHint_prefersApprovalBeforeOtherReasons() {
        val hint = buildBlockedWorkflowExecutionHint(
            SessionState(
                pendingApproval = PendingApprovalUi(
                    toolName = "shell",
                    summary = "执行测试命令",
                    detail = "运行测试",
                    rawArgs = "{\"command\":\"./gradlew test\"}",
                    riskLevel = ApprovalRiskLevel.HIGH
                ),
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复问题",
                    question = "要不要先跑测试？"
                ),
                error = "some error"
            )
        )

        assertEquals("先处理审批请求，再继续按计划执行。", hint)
    }

    @Test
    fun resolveWorkflowExecutionFinalStatus_blocksWhenParallelBatchStillActive() {
        val status = resolveWorkflowExecutionFinalStatus(
            SessionState(
                subagentBatches = listOf(
                    SubagentBatchUi(
                        batchId = "batch-1",
                        parentGoal = "修复问题",
                        label = "并行验证",
                        runIds = listOf("run-1", "run-2"),
                        status = "running",
                        queuedRuns = 1,
                        runningRuns = 1
                    )
                )
            )
        )

        assertEquals(WorkflowPlanStatusUi.BLOCKED, status)
    }

    @Test
    fun buildBlockedWorkflowExecutionHint_mentionsActiveParallelBatchWhenNoOtherBlockerExists() {
        val hint = buildBlockedWorkflowExecutionHint(
            SessionState(
                subagentBatches = listOf(
                    SubagentBatchUi(
                        batchId = "batch-1",
                        parentGoal = "修复问题",
                        label = "并行验证",
                        runIds = listOf("run-1", "run-2", "run-3"),
                        status = "queued",
                        queuedRuns = 2,
                        runningRuns = 1
                    )
                )
            )
        )

        assertEquals(
            "仍有 1 个并行子任务批次处于活跃状态（运行中 1，排队中 2）；先等待这些子任务全部收敛并汇总证据，再继续签收当前计划步骤。",
            hint
        )
    }

    @Test
    fun applyParallelBatchWorkflowProgressTransition_blocksExecutingPlanWhileBatchIsActive() {
        val executingPlan = WorkflowPlanUi(
            goal = "修复问题",
            summary = "先分析再执行",
            steps = listOf("分析", "修改", "验证"),
            currentStepIndex = 2,
            nextStepHint = "继续验证",
            status = WorkflowPlanStatusUi.EXECUTING
        )

        val updated = applyParallelBatchWorkflowProgressTransition(
            SessionState(
                pendingWorkflowPlan = executingPlan,
                canonicalWorkflowPlan = executingPlan,
                subagentBatches = listOf(
                    SubagentBatchUi(
                        batchId = "batch-1",
                        parentGoal = "修复问题",
                        label = "并行验证",
                        runIds = listOf("run-1", "run-2", "run-3"),
                        status = "running",
                        queuedRuns = 1,
                        runningRuns = 1
                    )
                )
            )
        )

        assertEquals(WorkflowPlanStatusUi.BLOCKED, updated.pendingWorkflowPlan?.status)
        assertEquals(
            "仍有 1 个并行子任务批次处于活跃状态（运行中 1，排队中 1）；先等待这些子任务全部收敛并汇总证据，再继续签收当前计划步骤。",
            updated.pendingWorkflowPlan?.nextStepHint
        )
        assertEquals(WorkflowPlanStatusUi.BLOCKED, updated.canonicalWorkflowPlan?.status)
    }

    @Test
    fun applyParallelBatchWorkflowProgressTransition_resumesBlockedPlanWhenBatchesHaveSettled() {
        val blockedPlan = WorkflowPlanUi(
            goal = "修复问题",
            summary = "先分析再执行",
            steps = listOf("分析", "修改", "验证"),
            currentStepIndex = 2,
            nextStepHint = "仍有 1 个并行子任务批次处于活跃状态（运行中 1，排队中 0）；先等待这些子任务全部收敛并汇总证据，再继续签收当前计划步骤。",
            status = WorkflowPlanStatusUi.BLOCKED
        )

        val updated = applyParallelBatchWorkflowProgressTransition(
            SessionState(
                pendingWorkflowPlan = blockedPlan,
                canonicalWorkflowPlan = blockedPlan,
                subagentBatches = listOf(
                    SubagentBatchUi(
                        batchId = "batch-1",
                        parentGoal = "修复问题",
                        label = "并行验证",
                        runIds = listOf("run-1", "run-2"),
                        status = "completed",
                        summary = "编排批次 并行验证 已结束。成功 2 个。",
                        queuedRuns = 0,
                        runningRuns = 0,
                        finishedAt = 200L
                    )
                )
            )
        )

        assertEquals(WorkflowPlanStatusUi.EXECUTING, updated.pendingWorkflowPlan?.status)
        assertEquals(
            "并行子任务批次已全部收敛；最近批次：并行验证（状态：completed）。 批次结论：编排批次 并行验证 已结束。成功 2 个。 请先把这些子任务结果整理成可引用证据，再推进当前计划。 当前步骤：验证。",
            updated.pendingWorkflowPlan?.nextStepHint
        )
        assertEquals(WorkflowPlanStatusUi.EXECUTING, updated.canonicalWorkflowPlan?.status)
    }

    @Test
    fun applyParallelBatchWorkflowProgressTransition_resumeHintWarnsAboutFailedSettledBatch() {
        val blockedPlan = WorkflowPlanUi(
            goal = "修复问题",
            summary = "先分析再执行",
            steps = listOf("分析", "修改", "验证"),
            currentStepIndex = 1,
            nextStepHint = "先等待活跃的并行子任务批次全部收敛，再继续签收当前计划步骤。",
            status = WorkflowPlanStatusUi.BLOCKED
        )

        val updated = applyParallelBatchWorkflowProgressTransition(
            SessionState(
                pendingWorkflowPlan = blockedPlan,
                canonicalWorkflowPlan = blockedPlan,
                subagentBatches = listOf(
                    SubagentBatchUi(
                        batchId = "batch-1",
                        parentGoal = "修复问题",
                        label = "并行验证",
                        runIds = listOf("run-1", "run-2", "run-3"),
                        status = "completed_with_failures",
                        summary = "编排批次 并行验证 已结束。成功 2 个，未成功 1 个。",
                        finishedAt = 300L
                    )
                )
            )
        )

        assertEquals(WorkflowPlanStatusUi.EXECUTING, updated.pendingWorkflowPlan?.status)
        assertEquals(
            "并行子任务批次已全部收敛；最近批次：并行验证（状态：completed_with_failures）。 批次结论：编排批次 并行验证 已结束。成功 2 个，未成功 1 个。 请先判断是否需要补救或重试未成功子任务，再结合现有证据推进当前计划。 当前步骤：修改。",
            updated.pendingWorkflowPlan?.nextStepHint
        )
    }

    @Test
    fun buildSettledParallelBatchExecutionHint_summarizesLatestSettledFailureBatch() {
        val hint = buildSettledParallelBatchExecutionHint(
            SessionState(
                subagentBatches = listOf(
                    SubagentBatchUi(
                        batchId = "batch-old",
                        parentGoal = "修复问题",
                        label = "旧批次",
                        runIds = listOf("run-1", "run-2"),
                        status = "completed",
                        summary = "旧结论",
                        finishedAt = 100L
                    ),
                    SubagentBatchUi(
                        batchId = "batch-new",
                        parentGoal = "修复问题",
                        label = "最新失败批次",
                        runIds = listOf("run-3", "run-4", "run-5"),
                        status = "completed_with_failures",
                        summary = "编排批次 最新失败批次 已结束。成功 2 个，未成功 1 个。",
                        finishedAt = 200L
                    )
                )
            )
        )

        assertEquals(
            "最近并行子任务批次已收敛：最新失败批次（completed_with_failures）。 批次结论：编排批次 最新失败批次 已结束。成功 2 个，未成功 1 个。 在调用 `complete_step` 前，先确认未成功子任务是否需要补救、重试或显式接受。",
            hint
        )
    }

    @Test
    fun resolveWorkflowPlanAfterExecution_marksPlanBlockedAndKeepsProgressWhenClarificationAppears() {
        val originalPlan = WorkflowPlanUi(
            goal = "修复问题",
            summary = "先分析再执行",
            steps = listOf("分析", "修改", "验证")
        )
        val executingPlan = originalPlan.copy(
            status = WorkflowPlanStatusUi.EXECUTING,
            currentStepIndex = 2,
            nextStepHint = "继续验证"
        )

        val finalPlan = resolveWorkflowPlanAfterExecution(
            originalPlan = originalPlan,
            executingPlan = executingPlan,
            state = SessionState(
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复问题",
                    question = "要不要先跑测试？"
                )
            )
        )

        assertEquals(WorkflowPlanStatusUi.BLOCKED, finalPlan.status)
        assertEquals(2, finalPlan.currentStepIndex)
        assertEquals("先回答澄清问题，再继续按计划执行。", finalPlan.nextStepHint)
    }

    @Test
    fun resolveWorkflowPlanAfterExecution_marksPlanCompletedAndAdvancesToEnd() {
        val originalPlan = WorkflowPlanUi(
            goal = "修复问题",
            summary = "先分析再执行",
            steps = listOf("分析", "修改", "验证"),
            nextStepHint = "继续分析"
        )
        val executingPlan = originalPlan.copy(
            status = WorkflowPlanStatusUi.EXECUTING,
            currentStepIndex = 1,
            nextStepHint = "继续验证"
        )

        val finalPlan = resolveWorkflowPlanAfterExecution(
            originalPlan = originalPlan,
            executingPlan = executingPlan,
            state = SessionState()
        )

        assertEquals(WorkflowPlanStatusUi.COMPLETED, finalPlan.status)
        assertEquals(originalPlan.steps.size, finalPlan.currentStepIndex)
        assertEquals("当前计划已完成，可继续发起下一轮任务或重做计划。", finalPlan.nextStepHint)
    }

    @Test
    fun resolveWorkflowPlanAfterExecution_keepsPlanBlockedWhileParallelBatchIsStillActive() {
        val originalPlan = WorkflowPlanUi(
            goal = "修复问题",
            summary = "先分析再执行",
            steps = listOf("分析", "修改", "验证")
        )
        val executingPlan = originalPlan.copy(
            status = WorkflowPlanStatusUi.EXECUTING,
            currentStepIndex = 2,
            nextStepHint = "等待并行验证"
        )

        val finalPlan = resolveWorkflowPlanAfterExecution(
            originalPlan = originalPlan,
            executingPlan = executingPlan,
            state = SessionState(
                subagentBatches = listOf(
                    SubagentBatchUi(
                        batchId = "batch-1",
                        parentGoal = "修复问题",
                        label = "并行验证",
                        runIds = listOf("run-1", "run-2"),
                        status = "running",
                        queuedRuns = 0,
                        runningRuns = 2
                    )
                )
            )
        )

        assertEquals(WorkflowPlanStatusUi.BLOCKED, finalPlan.status)
        assertEquals(2, finalPlan.currentStepIndex)
        assertEquals(
            "仍有 1 个并行子任务批次处于活跃状态（运行中 2，排队中 0）；先等待这些子任务全部收敛并汇总证据，再继续签收当前计划步骤。",
            finalPlan.nextStepHint
        )
    }

    @Test
    fun resolveWorkflowPlanAfterStepCompletion_marksPlanCompletedWhenLastStepSignedOff() {
        val updatedPlan = resolveWorkflowPlanAfterStepCompletion(
            currentPlan = WorkflowPlanUi(
                goal = "修复问题",
                summary = "先分析再执行",
                steps = listOf("分析", "修改", "验证"),
                currentStepIndex = 2,
                status = WorkflowPlanStatusUi.EXECUTING
            ),
            completion = WorkflowStepCompletion(
                matchedStepIndex = 2,
                matchedStep = "验证",
                reportedStep = "验证",
                resultSummary = "验证通过",
                matchedEvidenceCount = 2,
                totalEvidenceCount = 2,
                matchedToolNames = listOf("shell"),
                signedOffAt = 123L
            )
        )

        assertEquals(WorkflowPlanStatusUi.COMPLETED, updatedPlan.status)
        assertEquals(3, updatedPlan.currentStepIndex)
        assertEquals("计划步骤已全部签收完成，可继续汇总结果或进入下一轮任务。最后签收：验证。", updatedPlan.nextStepHint)
        assertEquals(1, updatedPlan.stepSignOffs.size)
    }

    @Test
    fun applyWorkflowPlanProgressTransition_updatesCanonicalAndKeepsPendingOnlyWhenPresent() {
        val updatedPlan = WorkflowPlanUi(
            goal = "修复问题",
            summary = "先分析再执行",
            status = WorkflowPlanStatusUi.COMPLETED
        )

        val withPending = applyWorkflowPlanProgressTransition(
            state = SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复问题",
                    summary = "旧计划"
                )
            ),
            updatedPlan = updatedPlan
        )
        val withoutPending = applyWorkflowPlanProgressTransition(
            state = SessionState(),
            updatedPlan = updatedPlan
        )

        assertEquals(updatedPlan, withPending.pendingWorkflowPlan)
        assertEquals(updatedPlan, withPending.canonicalWorkflowPlan)
        assertNull(withoutPending.pendingWorkflowPlan)
        assertEquals(updatedPlan, withoutPending.canonicalWorkflowPlan)
    }

    @Test
    fun applyWorkflowExecutionSettlementTransition_updatesPendingAndCanonicalFromExecutionState() {
        val originalPlan = WorkflowPlanUi(
            goal = "修复问题",
            summary = "先分析再执行",
            steps = listOf("分析", "修改", "验证")
        )
        val executingPlan = originalPlan.copy(
            status = WorkflowPlanStatusUi.EXECUTING,
            currentStepIndex = 2,
            nextStepHint = "继续验证"
        )

        val result = applyWorkflowExecutionSettlementTransition(
            state = SessionState(
                pendingWorkflowPlan = executingPlan,
                canonicalWorkflowPlan = executingPlan,
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复问题",
                    question = "要不要先跑测试？"
                )
            ),
            originalPlan = originalPlan,
            executingPlan = executingPlan
        )

        assertEquals(WorkflowPlanStatusUi.BLOCKED, result.pendingWorkflowPlan?.status)
        assertEquals(WorkflowPlanStatusUi.BLOCKED, result.canonicalWorkflowPlan?.status)
        assertEquals("先回答澄清问题，再继续按计划执行。", result.canonicalWorkflowPlan?.nextStepHint)
        assertEquals(2, result.canonicalWorkflowPlan?.currentStepIndex)
    }
}
