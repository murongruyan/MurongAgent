package com.murong.agent.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApprovalHostSurfacePresentationTest {

    @Test
    fun buildApprovalHostSurfacePresentation_whenNoPendingApproval_returnsNone() {
        val presentation = buildApprovalHostSurfacePresentation(
            pendingApproval = null,
            isToolsScreenVisible = false,
            showToolsApprovalDetail = false
        )

        assertEquals(ApprovalHostSurfaceKind.NONE, presentation.kind)
        assertNull(presentation.pendingApproval)
    }

    @Test
    fun buildApprovalHostSurfacePresentation_whenToolsNotVisible_routesToDialog() {
        val pendingApproval = PendingApprovalPresentation(
            toolName = "shell",
            headline = "执行命令需要确认",
            supportText = null,
            rows = emptyList(),
            rawArgsLabel = "原始参数",
            approveLabel = "允许",
            rejectLabel = "拒绝"
        )
        val presentation = buildApprovalHostSurfacePresentation(
            pendingApproval = pendingApproval,
            isToolsScreenVisible = false,
            showToolsApprovalDetail = true
        )

        assertEquals(ApprovalHostSurfaceKind.DIALOG, presentation.kind)
        assertEquals(pendingApproval, presentation.pendingApproval)
    }

    @Test
    fun buildApprovalHostSurfacePresentation_whenToolsVisibleAndDetailRequested_routesToToolsDetail() {
        val pendingApproval = PendingApprovalPresentation(
            toolName = "shell",
            headline = "执行命令需要确认",
            supportText = null,
            rows = emptyList(),
            rawArgsLabel = "原始参数",
            approveLabel = "允许",
            rejectLabel = "拒绝"
        )
        val presentation = buildApprovalHostSurfacePresentation(
            pendingApproval = pendingApproval,
            isToolsScreenVisible = true,
            showToolsApprovalDetail = true
        )

        assertEquals(ApprovalHostSurfaceKind.TOOLS_DETAIL, presentation.kind)
        assertEquals(pendingApproval, presentation.pendingApproval)
    }
}
