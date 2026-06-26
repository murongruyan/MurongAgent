package com.murong.agent.ui.project

import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.ui.buildApprovalModeFollowGlobalOptionPresentation
import com.murong.agent.ui.buildApprovalModeHostPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectApprovalModePresentationTest {

    @Test
    fun buildProjectApprovalModeSummary_marksFollowGlobalWithActualGlobalMode() {
        val summary = buildApprovalModeHostPresentation(
            globalMode = ToolApprovalMode.WHITELIST_AUTO,
            overrideMode = null
        ).message

        assertTrue(summary.contains("当前审批姿态"))
        assertTrue(summary.contains("Auto（白名单）（跟随全局）"))
        assertTrue(summary.contains("命中白名单的操作可自动通过"))
    }

    @Test
    fun buildProjectApprovalModeSummary_marksProjectOverrideWhenPresent() {
        val summary = buildApprovalModeHostPresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL,
            overrideMode = ToolApprovalMode.ALL_AUTO
        ).message

        assertTrue(summary.contains("Yolo（全自动）（项目覆盖）"))
        assertTrue(summary.contains("大多数操作默认直接通过"))
    }

    @Test
    fun buildProjectApprovalModeFollowGlobalSubtitle_usesPlainGlobalLabel() {
        val subtitle = buildApprovalModeFollowGlobalOptionPresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL
        ).subtitle

        assertEquals("当前全局：Ask（全审批）", subtitle)
    }
}
