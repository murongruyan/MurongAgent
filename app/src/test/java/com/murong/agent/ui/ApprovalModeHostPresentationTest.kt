package com.murong.agent.ui

import com.murong.agent.core.config.ToolApprovalMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalModeHostPresentationTest {

    @Test
    fun buildApprovalModeHostPresentation_formatsFollowingGlobalLabel() {
        val presentation = buildApprovalModeHostPresentation(
            globalMode = ToolApprovalMode.WHITELIST_AUTO,
            overrideMode = null
        )

        assertEquals("Auto（白名单）（跟随全局）", presentation.label)
        assertTrue(presentation.message.contains("命中白名单的操作可自动通过"))
    }

    @Test
    fun buildApprovalModeHostPresentation_formatsProjectOverrideMessage() {
        val presentation = buildApprovalModeHostPresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL,
            overrideMode = ToolApprovalMode.ALL_AUTO
        )

        assertEquals("Yolo（全自动）（项目覆盖）", presentation.label)
        assertTrue(presentation.message.contains("所有已启用工具直接执行"))
    }

    @Test
    fun buildApprovalModeFollowGlobalOptionPresentation_usesActualGlobalModeLabel() {
        val presentation = buildApprovalModeFollowGlobalOptionPresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL
        )

        assertEquals(null, presentation.mode)
        assertEquals("跟随全局", presentation.title)
        assertEquals("当前全局：Ask（全审批）", presentation.subtitle)
    }

    @Test
    fun buildApprovalModeOptionPresentations_matchModeCopy() {
        val presentations = buildApprovalModeOptionPresentations()

        assertEquals(ToolApprovalMode.entries.size, presentations.size)
        assertEquals(ToolApprovalMode.READ_ONLY, presentations.first().mode)
        assertTrue(presentations.first().title.contains("Ask"))
        assertTrue(presentations.first().subtitle.contains("只读"))
        assertTrue(
            presentations.any { option ->
                option.mode == ToolApprovalMode.ALL_AUTO && option.title.contains("Yolo")
            }
        )
    }
}
