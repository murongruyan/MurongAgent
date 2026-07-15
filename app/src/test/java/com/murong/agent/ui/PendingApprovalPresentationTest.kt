package com.murong.agent.ui

import com.murong.agent.core.loop.PendingApprovalUi
import com.murong.agent.core.loop.REPLAY_ONLY_APPROVAL_NOTICE
import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingApprovalPresentationTest {

    @Test
    fun toPendingApprovalPresentation_whenReplayOnly_usesReplayLabelsAndNotice() {
        val approval = PendingApprovalUi(
            toolName = "shell",
            summary = "运行命令",
            detail = "等待用户确认",
            rawArgs = """{"command":"gradlew test"}""",
            riskLevel = ApprovalRiskLevel.HIGH,
            isReplayOnly = true,
            replayNotice = "这是恢复出来的旧审批"
        )

        val presentation = approval.toPendingApprovalPresentation()

        assertEquals("shell", presentation.toolName)
        assertEquals("运行命令", presentation.headline)
        assertEquals("关闭", presentation.rejectLabel)
        assertEquals("无法继续", presentation.approveLabel)
        assertEquals("原始参数", presentation.rawArgsLabel)
        assertEquals("""{"command":"gradlew test"}""", presentation.rawArgs)
        assertEquals(false, presentation.approveEnabled)
        assertEquals(ApprovalRiskLevel.HIGH, presentation.riskLevel)
        assertEquals("这是恢复出来的旧审批", presentation.replayNotice)
        assertTrue(presentation.supportText?.contains("旧审批") == true)
    }

    @Test
    fun toPendingApprovalPresentation_whenReplayOnlyWithoutNotice_usesCanonicalFallbackNotice() {
        val approval = PendingApprovalUi(
            toolName = "shell",
            summary = "运行命令",
            detail = "等待用户确认",
            rawArgs = """{"command":"gradlew test"}""",
            riskLevel = ApprovalRiskLevel.HIGH,
            isReplayOnly = true,
            replayNotice = null
        )

        val presentation = approval.toPendingApprovalPresentation()

        assertEquals(REPLAY_ONLY_APPROVAL_NOTICE, presentation.supportText)
        assertEquals("无法继续", presentation.approveLabel)
        assertEquals("关闭", presentation.rejectLabel)
    }

    @Test
    fun toPendingApprovalPresentation_keepsExplanationFieldsForDialogConsumers() {
        val approval = PendingApprovalUi(
            toolName = "shell",
            summary = "运行命令",
            detail = "等待用户确认",
            rawArgs = """{"command":"gradlew test"}""",
            riskLevel = ApprovalRiskLevel.HIGH,
            explanationLabel = "高风险命令",
            explanationDetail = "需要人工确认后再继续。"
        )

        val presentation = approval.toPendingApprovalPresentation()

        assertEquals("高风险命令", presentation.explanationLabel)
        assertEquals("需要人工确认后再继续。", presentation.explanationDetail)
        assertEquals(true, presentation.approveEnabled)
    }
}
