package com.murong.agent.core.loop

import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TurnOrchestratorPolicyTest {

    @Test
    fun resolveTurnOrchestratorDecision_prefersClarificationStageWhenRequestPending() {
        val decision = resolveTurnOrchestratorDecision(
            state = SessionState(
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复登录问题",
                    question = "是否需要先保留旧接口兼容？"
                )
            ),
            requestedExecutionGoal = "修复登录问题",
            forceWritableTools = false
        )

        assertEquals(TurnOrchestratorStage.CLARIFICATION, decision.stage)
        assertFalse(decision.allowWriteTools)
        assertEquals("修复登录问题", decision.activeGoal)
    }

    @Test
    fun resolveTurnOrchestratorDecision_returnsPlanReviewForReadyWorkflowPlan() {
        val decision = resolveTurnOrchestratorDecision(
            state = SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复崩溃",
                    summary = "先定位再修复",
                    status = WorkflowPlanStatusUi.READY,
                    nextStepHint = "等待用户确认执行"
                )
            ),
            requestedExecutionGoal = "修复崩溃",
            forceWritableTools = false
        )

        assertEquals(TurnOrchestratorStage.PLAN_REVIEW, decision.stage)
        assertFalse(decision.allowWriteTools)
        assertEquals("修复崩溃", decision.activePlan?.goal)
    }

    @Test
    fun resolveTurnOrchestratorDecision_returnsPlanExecutionForMatchingCanonicalPlan() {
        val plan = WorkflowPlanUi(
            goal = "修复卡顿",
            summary = "按步骤执行",
            steps = listOf("分析", "修改", "验证"),
            currentStepIndex = 1,
            nextStepHint = "继续修改",
            status = WorkflowPlanStatusUi.EXECUTING
        )
        val decision = resolveTurnOrchestratorDecision(
            state = SessionState(
                canonicalWorkflowPlan = plan
            ),
            requestedExecutionGoal = "修复卡顿",
            forceWritableTools = true
        )

        assertEquals(TurnOrchestratorStage.PLAN_EXECUTION, decision.stage)
        assertTrue(decision.allowWriteTools)
        assertEquals(plan, decision.activePlan)
    }

    @Test
    fun buildTurnOrchestratorContext_includesExecutionGuidanceForPlanExecution() {
        val context = buildTurnOrchestratorContext(
            TurnOrchestratorDecision(
                stage = TurnOrchestratorStage.PLAN_EXECUTION,
                allowWriteTools = true,
                readOnlyPlanModeDecision = ReadOnlyPlanModeDecision(
                    enabled = false,
                    reason = ReadOnlyPlanModeReason.NONE
                ),
                activeGoal = "修复卡顿",
                activePlan = WorkflowPlanUi(
                    goal = "修复卡顿",
                    summary = "按步骤执行",
                    steps = listOf("分析", "修改", "验证"),
                    currentStepIndex = 2,
                    nextStepHint = "继续验证",
                    status = WorkflowPlanStatusUi.EXECUTING
                )
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("Stage: plan_execution"))
        assertTrue(context.contains("Primary Goal: 修复卡顿"))
        assertTrue(context.contains("complete_step"))
    }

    @Test
    fun buildTurnOrchestratorContext_warnsWhenParallelBatchStillActive() {
        val context = buildTurnOrchestratorContext(
            resolveTurnOrchestratorDecision(
                state = SessionState(
                    canonicalWorkflowPlan = WorkflowPlanUi(
                        goal = "修复卡顿",
                        summary = "按步骤执行",
                        steps = listOf("分析", "修改", "验证"),
                        currentStepIndex = 1,
                        nextStepHint = "等待并行搜索结果",
                        status = WorkflowPlanStatusUi.EXECUTING
                    ),
                    subagentBatches = listOf(
                        SubagentBatchUi(
                            batchId = "batch-1",
                            parentGoal = "修复卡顿",
                            label = "并行搜索批次",
                            runIds = listOf("run-1", "run-2"),
                            status = "running",
                            queuedRuns = 1,
                            runningRuns = 1
                        )
                    )
                ),
                requestedExecutionGoal = "修复卡顿",
                forceWritableTools = true
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("Active Parallel Batches: 1"))
        assertTrue(context.contains("并行搜索批次"))
        assertTrue(context.contains("do not call `complete_step`"))
    }

    @Test
    fun buildTurnOrchestratorContext_warnsWhenSettledParallelBatchStillHasFailures() {
        val context = buildTurnOrchestratorContext(
            resolveTurnOrchestratorDecision(
                state = SessionState(
                    canonicalWorkflowPlan = WorkflowPlanUi(
                        goal = "修复卡顿",
                        summary = "按步骤执行",
                        steps = listOf("分析", "修改", "验证"),
                        currentStepIndex = 1,
                        nextStepHint = "继续修改",
                        status = WorkflowPlanStatusUi.EXECUTING
                    ),
                    subagentBatches = listOf(
                        SubagentBatchUi(
                            batchId = "batch-1",
                            parentGoal = "修复卡顿",
                            label = "并行验证批次",
                            runIds = listOf("run-1", "run-2", "run-3"),
                            status = "completed_with_failures",
                            summary = "编排批次 并行验证批次 已结束。成功 2 个，未成功 1 个。",
                            finishedAt = 100L
                        )
                    )
                ),
                requestedExecutionGoal = "修复卡顿",
                forceWritableTools = true
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("Recent Settled Parallel Batch: 并行验证批次"))
        assertTrue(context.contains("completed_with_failures"))
        assertTrue(context.contains("retry or explicit acceptance"))
        assertTrue(context.contains("complete_step"))
    }

    @Test
    fun resolveTurnOrchestratorDecision_marksApprovalBlockedWhenApprovalPending() {
        val decision = resolveTurnOrchestratorDecision(
            state = SessionState(
                pendingApproval = PendingApprovalUi(
                    toolName = "shell",
                    summary = "运行验证命令",
                    detail = "执行测试",
                    rawArgs = "{\"command\":\"./gradlew test\"}",
                    riskLevel = ApprovalRiskLevel.HIGH
                )
            ),
            requestedExecutionGoal = "修复卡顿",
            forceWritableTools = false
        )

        assertEquals(TurnOrchestratorStage.APPROVAL_BLOCKED, decision.stage)
        assertFalse(decision.allowWriteTools)
        assertEquals("修复卡顿", decision.activeGoal)
    }
}
