package com.murong.agent.ui.tools

import com.murong.agent.core.loop.FinalReadinessAuditOverview
import com.murong.agent.core.loop.ErrorRecordKind
import com.murong.agent.core.loop.ErrorRecordUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolsAuditPresentationTest {

    @Test
    fun errorRecordTypeLabel_whenFinalReadinessError_returnsReadinessLabel() {
        val label = errorRecordTypeLabel(
            ErrorRecordUi(
                message = "最终收口校验未通过：缺少 complete_step。",
                kind = ErrorRecordKind.FINAL_READINESS
            )
        )

        assertEquals("最终收口阻塞", label)
    }

    @Test
    fun errorRecordTypeLabel_whenGeneralError_returnsDefaultLabel() {
        val label = errorRecordTypeLabel(
            ErrorRecordUi(
                message = "普通网络错误",
                kind = ErrorRecordKind.GENERAL
            )
        )

        assertEquals("错误", label)
    }

    @Test
    fun buildFinalReadinessAuditOverviewHeadline_formatsCounts() {
        val headline = buildFinalReadinessAuditOverviewHeadline(
            FinalReadinessAuditOverview(
                totalCount = 4,
                blockedCount = 2,
                allowedCount = 2,
                recoveredCount = 1,
                writeSignOffBlockCount = 1,
                canonicalWorkflowBlockCount = 2,
                latestStatusSummary = "仍阻塞（计划仍有 1 个未签收步骤）",
                latestReasonSummary = "计划仍有 1 个未签收步骤",
                currentlyBlocked = true
            )
        )

        assertEquals("阻塞 2 · 恢复 1 · 允许 2", headline)
    }

    @Test
    fun buildFinalReadinessAuditOverviewBreakdown_formatsReasonCounts() {
        val breakdown = buildFinalReadinessAuditOverviewBreakdown(
            FinalReadinessAuditOverview(
                totalCount = 3,
                blockedCount = 2,
                allowedCount = 1,
                recoveredCount = 1,
                writeSignOffBlockCount = 1,
                canonicalWorkflowBlockCount = 2,
                latestStatusSummary = "提醒后已恢复放行（缺少 `code_edit` 对应的 complete_step 签收）",
                latestReasonSummary = "缺少 `code_edit` 对应的 complete_step 签收",
                currentlyBlocked = false
            )
        )

        assertEquals("写后待签收 1 · 计划未收口 2", breakdown)
    }

    @Test
    fun buildFinalReadinessAuditOverviewBreakdown_whenNoReasonCounts_returnsNull() {
        val breakdown = buildFinalReadinessAuditOverviewBreakdown(
            FinalReadinessAuditOverview(
                totalCount = 1,
                blockedCount = 0,
                allowedCount = 1,
                recoveredCount = 0,
                writeSignOffBlockCount = 0,
                canonicalWorkflowBlockCount = 0,
                latestStatusSummary = "允许结束（计划仍有 0 个未签收步骤）",
                latestReasonSummary = "计划仍有 0 个未签收步骤",
                currentlyBlocked = false
            )
        )

        assertNull(breakdown)
    }
}
