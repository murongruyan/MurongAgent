package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanonicalWorkflowReadinessPolicyTest {

    @Test
    fun buildCanonicalWorkflowReadinessFailure_reportsRemainingStepForMatchingGoal() {
        val failure = buildCanonicalWorkflowReadinessFailure(
            canonicalPlan = WorkflowPlanUi(
                goal = "修复发布流程",
                summary = "分三步处理",
                steps = listOf("定位问题", "修复工作流", "验证发布"),
                currentStepIndex = 1,
                status = WorkflowPlanStatusUi.EXECUTING,
                nextStepHint = "继续修复工作流",
                stepSignOffs = listOf(
                    WorkflowStepSignOffUi(
                        stepIndex = 0,
                        step = "定位问题",
                        reportedStep = "定位问题",
                        resultSummary = "已经完成排查",
                        matchedEvidenceCount = 1,
                        totalEvidenceCount = 1,
                        signedOffAt = 123L
                    )
                )
            ),
            executionGoal = "修复发布流程"
        )

        assertTrue(failure?.contains("跨轮次计划仍未完成") == true)
        assertTrue(failure.contains("修复工作流"))
        assertTrue(failure.contains("还剩 2 个未签收步骤"))
        assertTrue(failure.contains("最近已签收步骤：定位问题"))
        assertTrue(failure.contains("已经完成排查"))
    }

    @Test
    fun buildCanonicalWorkflowReadinessFailure_returnsNullForDifferentGoalOrFullySignedOffPlan() {
        val fullySignedOffPlan = WorkflowPlanUi(
            goal = "修复发布流程",
            summary = "完成",
            steps = listOf("定位问题"),
            currentStepIndex = 1,
            status = WorkflowPlanStatusUi.COMPLETED,
            stepSignOffs = listOf(
                WorkflowStepSignOffUi(
                    stepIndex = 0,
                    step = "定位问题",
                    reportedStep = "定位问题",
                    resultSummary = "已完成",
                    matchedEvidenceCount = 1,
                    totalEvidenceCount = 1,
                    signedOffAt = 123L
                )
            )
        )
        val otherGoalPlan = WorkflowPlanUi(
            goal = "修复发布流程",
            summary = "处理中",
            steps = listOf("定位问题", "修复工作流"),
            currentStepIndex = 1,
            status = WorkflowPlanStatusUi.EXECUTING
        )

        assertNull(buildCanonicalWorkflowReadinessFailure(fullySignedOffPlan, "修复发布流程"))
        assertNull(buildCanonicalWorkflowReadinessFailure(otherGoalPlan, "新任务"))
    }

    @Test
    fun shouldApplyCanonicalWorkflowReadiness_requiresMatchingGoalAndIncompleteSteps() {
        val plan = WorkflowPlanUi(
            goal = "修复发布流程",
            summary = "处理中",
            steps = listOf("定位问题", "修复工作流"),
            currentStepIndex = 0,
            status = WorkflowPlanStatusUi.EXECUTING
        )
        val fullySignedOffPlan = plan.copy(
            currentStepIndex = 2,
            status = WorkflowPlanStatusUi.COMPLETED,
            stepSignOffs = listOf(
                WorkflowStepSignOffUi(
                    stepIndex = 0,
                    step = "定位问题",
                    reportedStep = "定位问题",
                    resultSummary = "已完成定位",
                    matchedEvidenceCount = 1,
                    totalEvidenceCount = 1,
                    signedOffAt = 100L
                ),
                WorkflowStepSignOffUi(
                    stepIndex = 1,
                    step = "修复工作流",
                    reportedStep = "修复工作流",
                    resultSummary = "已完成修复",
                    matchedEvidenceCount = 1,
                    totalEvidenceCount = 1,
                    signedOffAt = 200L
                )
            )
        )

        assertEquals(true, shouldApplyCanonicalWorkflowReadiness(plan, "修复发布流程"))
        assertEquals(true, shouldApplyCanonicalWorkflowReadiness(plan.copy(status = WorkflowPlanStatusUi.COMPLETED), "修复发布流程"))
        assertEquals(true, shouldApplyCanonicalWorkflowReadiness(plan.copy(currentStepIndex = 2), "修复发布流程"))
        assertEquals(false, shouldApplyCanonicalWorkflowReadiness(fullySignedOffPlan, "修复发布流程"))
        assertEquals(false, shouldApplyCanonicalWorkflowReadiness(plan, "别的目标"))
    }

    @Test
    fun buildCanonicalWorkflowReadinessFailure_blocksCompletedPlanWhenUnsignedStepsRemain() {
        val failure = buildCanonicalWorkflowReadinessFailure(
            canonicalPlan = WorkflowPlanUi(
                goal = "修复发布流程",
                summary = "状态看似完成",
                steps = listOf("定位问题", "修复工作流"),
                currentStepIndex = 2,
                status = WorkflowPlanStatusUi.COMPLETED,
                nextStepHint = "不应仅依赖这个提示",
                stepSignOffs = listOf(
                    WorkflowStepSignOffUi(
                        stepIndex = 0,
                        step = "定位问题",
                        reportedStep = "定位问题",
                        resultSummary = "已完成定位",
                        matchedEvidenceCount = 1,
                        totalEvidenceCount = 1,
                        signedOffAt = 123L
                    )
                )
            ),
            executionGoal = "修复发布流程"
        )

        assertTrue(failure?.contains("跨轮次计划仍未完成") == true)
        assertTrue(failure.contains("还剩 1 个未签收步骤"))
        assertTrue(failure.contains("下一步应为：修复工作流"))
    }
}
