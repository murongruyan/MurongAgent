package com.murong.agent.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApprovalPostureCopyPresentationTest {

    @Test
    fun buildApprovalPostureCopyPresentation_providesSharedSectionLabelsAndFallbacks() {
        val presentation = buildApprovalPostureCopyPresentation(
            runtimeFocusLabel = null,
            pendingSummary = null
        )

        assertEquals("审批姿态", presentation.sectionTitle)
        assertEquals("当前姿态", presentation.approvalModeLabel)
        assertEquals("当前状态", presentation.currentStatusLabel)
        assertEquals("后台焦点", presentation.runtimeFocusLabel)
        assertEquals("当前无后台任务", presentation.runtimeFocusValue)
        assertEquals("授权概览", presentation.scopeSummaryLabel)
        assertNull(presentation.pendingSummaryText)
    }

    @Test
    fun buildApprovalPostureCopyPresentation_formatsPendingSummaryLine() {
        val presentation = buildApprovalPostureCopyPresentation(
            runtimeFocusLabel = "后台待审批",
            pendingSummary = "shell · 执行命令需要确认"
        )

        assertEquals("后台待审批", presentation.runtimeFocusValue)
        assertEquals("当前审批: shell · 执行命令需要确认", presentation.pendingSummaryText)
    }
}
