package com.murong.agent.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalPosturePolicyTest {

    @Test
    fun toApprovalModePresentation_mapsReadOnlyToAskPosture() {
        val presentation = ToolApprovalMode.READ_ONLY.toApprovalModePresentation()

        assertEquals(ApprovalPosture.ASK, presentation.posture)
        assertTrue(presentation.label.contains("Ask"))
        assertTrue(presentation.description.contains("只读自动放行"))
    }

    @Test
    fun toApprovalModePresentation_mapsWhitelistAutoToAutoPostureWithFreshApprovalNote() {
        val presentation = ToolApprovalMode.WHITELIST_AUTO.toApprovalModePresentation()

        assertEquals(ApprovalPosture.AUTO, presentation.posture)
        assertTrue(presentation.label.contains("Auto"))
        assertTrue(presentation.description.contains("fresh approval"))
    }

    @Test
    fun toApprovalModePresentation_mapsAllAutoToYoloPostureWithFreshApprovalNote() {
        val presentation = ToolApprovalMode.ALL_AUTO.toApprovalModePresentation()

        assertEquals(ApprovalPosture.YOLO, presentation.posture)
        assertTrue(presentation.label.contains("Yolo"))
        assertTrue(presentation.description.contains("fresh approval"))
    }

    @Test
    fun resolveApprovalModePresentation_marksGlobalModeAsFollowingGlobal() {
        val presentation = resolveApprovalModePresentation(
            globalMode = ToolApprovalMode.WHITELIST_AUTO,
            overrideMode = null
        )

        assertEquals(ToolApprovalMode.WHITELIST_AUTO, presentation.effectiveMode)
        assertEquals(ApprovalModeSource.FOLLOW_GLOBAL, presentation.source)
        assertEquals("Auto（白名单）（跟随全局）", presentation.labelWithSource)
        assertEquals("审批: 跟随全局 (Auto（白名单）)", presentation.shortcutLabel)
        assertTrue(presentation.runtimeMessage.contains("当前审批姿态"))
    }

    @Test
    fun resolveApprovalModePresentation_marksOverrideAsProjectScoped() {
        val presentation = resolveApprovalModePresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL,
            overrideMode = ToolApprovalMode.ALL_AUTO
        )

        assertEquals(ToolApprovalMode.ALL_AUTO, presentation.effectiveMode)
        assertEquals(ApprovalModeSource.PROJECT_OVERRIDE, presentation.source)
        assertEquals("Yolo（全自动）（项目覆盖）", presentation.labelWithSource)
        assertEquals("审批: Yolo（全自动）", presentation.shortcutLabel)
        assertTrue(presentation.runtimeMessage.contains("大多数操作默认直接通过"))
    }
}
