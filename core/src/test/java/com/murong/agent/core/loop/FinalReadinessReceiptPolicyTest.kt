package com.murong.agent.core.loop

import com.murong.agent.core.tool.StepSignOffReceipt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinalReadinessReceiptPolicyTest {

    @Test
    fun buildWriteSignOffReadinessReceipt_returnsStructuredReceiptForMissingCompleteStep() {
        val receipt = buildWriteSignOffReadinessReceipt(
            completedToolRuns = listOf(
                FinalReadinessToolRunSnapshot(
                    toolName = "code_edit",
                    result = "edit succeeded",
                    isSuccess = true
                )
            ),
            language = FinalReadinessLanguage.CHINESE,
            isWriteTool = { toolName -> toolName == "code_edit" }
        )

        assertNotNull(receipt)
        assertEquals(FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE, receipt.kind)
        assertEquals(FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE, receipt.requiredAction)
        assertEquals("code_edit", receipt.latestSuccessfulWriteToolName)
        assertTrue(receipt.message.contains("complete_step"))
    }

    @Test
    fun buildWriteSignOffReadinessReceipt_returnsNullWhenCompleteStepAlreadySucceeded() {
        val receipt = buildWriteSignOffReadinessReceipt(
            completedToolRuns = listOf(
                FinalReadinessToolRunSnapshot(
                    toolName = "code_edit",
                    result = "edit succeeded",
                    isSuccess = true
                ),
                FinalReadinessToolRunSnapshot(
                    toolName = "complete_step",
                    result = "custom receipt format without legacy marker",
                    isSuccess = true,
                    stepSignOffReceipt = StepSignOffReceipt(
                        reportedStep = "修改文件",
                        resultSummary = "文件已更新",
                        matchedEvidenceCount = 1,
                        totalEvidenceCount = 1,
                        matchedToolNames = listOf("code_edit"),
                        signOffTimestamp = 200L
                    )
                )
            ),
            language = FinalReadinessLanguage.CHINESE,
            isWriteTool = { toolName -> toolName == "code_edit" }
        )

        assertNull(receipt)
    }

    @Test
    fun buildWriteSignOffReadinessReceipt_blocksWhenCompleteStepIsUnrelatedToLatestWrite() {
        val receipt = buildWriteSignOffReadinessReceipt(
            completedToolRuns = listOf(
                FinalReadinessToolRunSnapshot(
                    toolName = "code_edit",
                    result = "edit succeeded",
                    isSuccess = true
                ),
                FinalReadinessToolRunSnapshot(
                    toolName = "complete_step",
                    result = "custom receipt format without legacy marker",
                    isSuccess = true,
                    stepSignOffReceipt = StepSignOffReceipt(
                        reportedStep = "运行测试",
                        resultSummary = "测试已通过",
                        matchedEvidenceCount = 1,
                        totalEvidenceCount = 1,
                        matchedToolNames = listOf("shell"),
                        signOffTimestamp = 200L
                    )
                )
            ),
            language = FinalReadinessLanguage.CHINESE,
            isWriteTool = { toolName -> toolName == "code_edit" }
        )

        assertNotNull(receipt)
        assertEquals(FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE, receipt.kind)
        assertTrue(receipt.message.contains("关联到该写工具"))
    }

    @Test
    fun buildWriteSignOffReadinessReceipt_blocksLegacyTextOnlyCompleteStepWithoutStructuredReceipt() {
        val receipt = buildWriteSignOffReadinessReceipt(
            completedToolRuns = listOf(
                FinalReadinessToolRunSnapshot(
                    toolName = "code_edit",
                    result = "edit succeeded",
                    isSuccess = true
                ),
                FinalReadinessToolRunSnapshot(
                    toolName = "complete_step",
                    result = """
                        Step signed off.
                        matched_tools=code_edit
                    """.trimIndent(),
                    isSuccess = true
                )
            ),
            language = FinalReadinessLanguage.CHINESE,
            isWriteTool = { toolName -> toolName == "code_edit" }
        )

        assertNotNull(receipt)
        assertEquals(FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE, receipt.kind)
    }

    @Test
    fun buildCanonicalWorkflowReadinessReceipt_carriesProgressMetadata() {
        val receipt = buildCanonicalWorkflowReadinessReceipt(
            canonicalPlan = WorkflowPlanUi(
                goal = "修复发布流程",
                summary = "分步执行",
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
                        matchedToolNames = listOf("shell"),
                        matchedSessionHistorySessionIds = listOf("session-login"),
                        matchedSessionHistoryMessageReferences = listOf("session-login#21"),
                        signedOffAt = 123L
                    )
                )
            ),
            executionGoal = "修复发布流程"
        )

        assertNotNull(receipt)
        assertEquals(FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW, receipt.kind)
        assertEquals(FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN, receipt.requiredAction)
        assertEquals(2, receipt.remainingUnsignedSteps)
        assertEquals("修复工作流", receipt.nextRequiredStep)
        assertEquals("定位问题", receipt.latestSignedOffStep)
        assertEquals("已经完成排查", receipt.latestSignedOffResultSummary)
        assertEquals(listOf("shell"), receipt.latestSignedOffMatchedTools)
        assertEquals(listOf("session-login"), receipt.latestSignedOffSessionHistorySessionIds)
        assertEquals(
            listOf("session-login#21"),
            receipt.latestSignedOffSessionHistoryMessageReferences
        )
        assertTrue(receipt.message.contains("最近已签收步骤"))
    }

    @Test
    fun buildCanonicalWorkflowReadinessReceipt_blocksCompletedPlanUntilAllStepsSignedOff() {
        val receipt = buildCanonicalWorkflowReadinessReceipt(
            canonicalPlan = WorkflowPlanUi(
                goal = "修复发布流程",
                summary = "状态已被标成完成",
                steps = listOf("定位问题", "修复工作流", "验证发布"),
                currentStepIndex = 3,
                status = WorkflowPlanStatusUi.COMPLETED,
                nextStepHint = "不应只依赖这个提示",
                stepSignOffs = listOf(
                    WorkflowStepSignOffUi(
                        stepIndex = 0,
                        step = "定位问题",
                        reportedStep = "定位问题",
                        resultSummary = "先完成定位",
                        matchedEvidenceCount = 1,
                        totalEvidenceCount = 1,
                        matchedToolNames = listOf("shell"),
                        signedOffAt = 100L
                    ),
                    WorkflowStepSignOffUi(
                        stepIndex = 2,
                        step = "验证发布",
                        reportedStep = "验证发布",
                        resultSummary = "后完成验证",
                        matchedEvidenceCount = 1,
                        totalEvidenceCount = 1,
                        matchedToolNames = listOf("shell", "code_edit"),
                        signedOffAt = 300L
                    )
                )
            ),
            executionGoal = "修复发布流程"
        )

        assertNotNull(receipt)
        assertEquals(FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW, receipt.kind)
        assertEquals(1, receipt.remainingUnsignedSteps)
        assertEquals("修复工作流", receipt.nextRequiredStep)
        assertEquals("验证发布", receipt.latestSignedOffStep)
        assertEquals("后完成验证", receipt.latestSignedOffResultSummary)
        assertEquals(listOf("shell", "code_edit"), receipt.latestSignedOffMatchedTools)
    }

    @Test
    fun buildCanonicalWorkflowReadinessReceipt_returnsNullWhenAllStepsSignedOff() {
        val receipt = buildCanonicalWorkflowReadinessReceipt(
            canonicalPlan = WorkflowPlanUi(
                goal = "修复发布流程",
                summary = "全部已签收",
                steps = listOf("定位问题", "修复工作流"),
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
                        matchedToolNames = listOf("shell"),
                        signedOffAt = 100L
                    ),
                    WorkflowStepSignOffUi(
                        stepIndex = 1,
                        step = "修复工作流",
                        reportedStep = "修复工作流",
                        resultSummary = "已完成修复",
                        matchedEvidenceCount = 1,
                        totalEvidenceCount = 1,
                        matchedToolNames = listOf("code_edit", "shell"),
                        signedOffAt = 200L
                    )
                )
            ),
            executionGoal = "修复发布流程"
        )

        assertNull(receipt)
    }

    @Test
    fun buildFinalReadinessAuditExportLines_summarizesLatestRecoveredStatus() {
        val lines = buildFinalReadinessAuditExportLines(
            listOf(
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.ALLOWED,
                    recovered = true,
                    receiptKind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                    requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
                    latestSuccessfulWriteToolName = "code_edit"
                ),
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.BLOCKED,
                    recovered = false,
                    receiptKind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
                    requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
                    remainingUnsignedSteps = 2
                )
            )
        )

        assertEquals(7, lines.size)
        assertTrue(lines.contains("- 最终收口审计数: 2"))
        assertTrue(lines.contains("- 最终收口拦截数: 1"))
        assertTrue(lines.contains("- 最终收口允许结束数: 1"))
        assertTrue(lines.contains("- 最终收口恢复数: 1"))
        assertTrue(lines.contains("- 写后待签收阻塞数: 1"))
        assertTrue(lines.contains("- 计划未收口阻塞数: 1"))
        assertTrue(lines.last().contains("提醒后已恢复放行"))
        assertTrue(lines.last().contains("code_edit"))
    }

    @Test
    fun buildFinalReadinessAuditOverview_summarizesCountsAndCurrentStatus() {
        val overview = buildFinalReadinessAuditOverview(
            listOf(
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.BLOCKED,
                    recovered = false,
                    receiptKind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                    requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
                    latestSuccessfulWriteToolName = "code_edit"
                ),
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.ALLOWED,
                    recovered = true,
                    receiptKind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
                    requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
                    remainingUnsignedSteps = 1
                )
            )
        )

        assertNotNull(overview)
        assertEquals(2, overview.totalCount)
        assertEquals(1, overview.blockedCount)
        assertEquals(1, overview.allowedCount)
        assertEquals(1, overview.recoveredCount)
        assertEquals(1, overview.writeSignOffBlockCount)
        assertEquals(1, overview.canonicalWorkflowBlockCount)
        assertTrue(overview.currentlyBlocked)
        assertTrue(overview.latestStatusSummary.contains("仍阻塞"))
        assertTrue(overview.latestReasonSummary.contains("code_edit"))
    }

    @Test
    fun toStatusSummary_reportsCanonicalWorkflowBlockReason() {
        val summary = FinalReadinessAuditRecord(
            result = FinalReadinessAuditResult.BLOCKED,
            recovered = false,
            receiptKind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
            requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
            remainingUnsignedSteps = 3
        ).toStatusSummary()

        assertTrue(summary.contains("仍阻塞"))
        assertTrue(summary.contains("3 个未签收步骤"))
    }

    @Test
    fun buildLatestFinalReadinessAuditSummary_returnsBlockedSummary() {
        val summary = buildLatestFinalReadinessAuditSummary(
            listOf(
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.BLOCKED,
                    recovered = false,
                    receiptKind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                    requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
                    latestSuccessfulWriteToolName = "code_edit"
                )
            )
        )

        assertNotNull(summary)
        assertTrue(summary.contains("仍阻塞"))
        assertTrue(summary.contains("code_edit"))
    }

    @Test
    fun buildLatestFinalReadinessSessionTelemetry_returnsRecoveredKindAndReason() {
        val telemetry = buildLatestFinalReadinessSessionTelemetry(
            listOf(
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.ALLOWED,
                    recovered = true,
                    receiptKind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
                    requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
                    remainingUnsignedSteps = 0
                )
            )
        )

        assertNotNull(telemetry)
        assertEquals(FinalReadinessSessionStatusKind.RECOVERED, telemetry.statusKind)
        assertTrue(telemetry.statusSummary.contains("提醒后已恢复放行"))
        assertTrue(telemetry.reasonSummary.contains("0 个未签收步骤"))
    }

    @Test
    fun buildLatestFinalReadinessAuditSummary_ignoresPlainAllowedAudit() {
        val summary = buildLatestFinalReadinessAuditSummary(
            listOf(
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.ALLOWED,
                    recovered = false,
                    receiptKind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
                    requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
                    remainingUnsignedSteps = 0
                )
            )
        )

        assertNull(summary)
    }
}
