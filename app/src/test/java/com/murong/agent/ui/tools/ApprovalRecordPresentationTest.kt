package com.murong.agent.ui.tools

import com.murong.agent.core.loop.ApprovalRecordUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalRecordPresentationTest {

    @Test
    fun toApprovalRecordListSubtitle_includesReasonAndScopeWhenPresent() {
        val subtitle = ApprovalRecordUi(
            toolName = "ask_user",
            summary = "向用户确认发布方案",
            decision = "Approved",
            scopeSummary = "GitHub 远端写入",
            explanationLabel = "关键工具始终审批"
        ).toApprovalRecordListSubtitle()

        assertTrue(subtitle.contains("向用户确认发布方案"))
        assertTrue(subtitle.contains("原因：关键工具始终审批"))
        assertTrue(subtitle.contains("范围：GitHub 远端写入"))
    }

    @Test
    fun toApprovalRecordListSubtitle_fallsBackWhenNothingUsefulExists() {
        val subtitle = ApprovalRecordUi(
            toolName = "shell",
            summary = "   ",
            decision = "Approved"
        ).toApprovalRecordListSubtitle()

        assertEquals("无摘要", subtitle)
    }
}
