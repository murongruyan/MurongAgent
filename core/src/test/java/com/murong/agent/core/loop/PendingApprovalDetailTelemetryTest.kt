package com.murong.agent.core.loop

import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingApprovalDetailTelemetryTest {

    @Test
    fun resolvePendingApprovalDetailTelemetry_whenReplayOnly_usesReplayLabelsAndNotice() {
        val telemetry = resolvePendingApprovalDetailTelemetry(
            PendingApprovalUi(
                toolName = "shell",
                summary = "运行命令",
                detail = "等待用户确认",
                rawArgs = """{"command":"gradlew test"}""",
                riskLevel = ApprovalRiskLevel.HIGH,
                isReplayOnly = true,
                replayNotice = "这是恢复出来的旧审批"
            )
        )

        assertEquals("运行命令", telemetry.headline)
        assertEquals("这是恢复出来的旧审批", telemetry.supportText)
        assertEquals("无法继续", telemetry.approveLabel)
        assertEquals("关闭", telemetry.rejectLabel)
        assertEquals("原始参数", telemetry.rawArgsLabel)
        assertTrue(telemetry.rows.isEmpty())
    }

    @Test
    fun resolvePendingApprovalDetailTelemetry_whenReplayOnlyWithoutNotice_fallsBackToCanonicalReplayNotice() {
        val telemetry = resolvePendingApprovalDetailTelemetry(
            PendingApprovalUi(
                toolName = "shell",
                summary = "运行命令",
                detail = "等待用户确认",
                rawArgs = """{"command":"gradlew test"}""",
                riskLevel = ApprovalRiskLevel.HIGH,
                isReplayOnly = true,
                replayNotice = null
            )
        )

        assertEquals(REPLAY_ONLY_APPROVAL_NOTICE, telemetry.supportText)
        assertEquals("无法继续", telemetry.approveLabel)
        assertEquals("关闭", telemetry.rejectLabel)
    }

    @Test
    fun resolvePendingApprovalDetailTelemetry_whenGitHubPending_buildsTypedRowsAndLabels() {
        val telemetry = resolvePendingApprovalDetailTelemetry(
            PendingApprovalUi(
                toolName = "mcp_create_or_update_file",
                summary = "GitHub 写入",
                detail = "MCP/GitHub 远端写入需要审批",
                rawArgs = """{"owner":"murong","repo":"agent","branch":"main","path":"README.md"}""",
                riskLevel = ApprovalRiskLevel.HIGH
            )
        )

        assertEquals("GitHub 远端写操作", telemetry.headline)
        assertEquals("允许写入 GitHub", telemetry.approveLabel)
        assertEquals("拒绝远端修改", telemetry.rejectLabel)
        assertEquals("原始参数 (JSON)", telemetry.rawArgsLabel)
        assertEquals(4, telemetry.rows.size)
        assertEquals("操作", telemetry.rows[0].label)
        assertEquals("写入远端文件", telemetry.rows[0].value)
        assertEquals("仓库", telemetry.rows[1].label)
        assertEquals("murong/agent", telemetry.rows[1].value)
    }

    @Test
    fun resolvePendingApprovalHostTelemetry_keepsDialogSpecificFieldsAndApproveState() {
        val telemetry = resolvePendingApprovalHostTelemetry(
            PendingApprovalUi(
                toolName = "shell",
                summary = "执行命令需要确认",
                detail = "shell 仍需 fresh approval",
                rawArgs = """{"command":"gradlew test"}""",
                riskLevel = ApprovalRiskLevel.HIGH,
                explanationLabel = "高风险命令",
                explanationDetail = "需要人工确认后再继续。",
                isReplayOnly = true,
                replayNotice = "这是恢复出来的旧审批"
            )
        )

        assertEquals("shell", telemetry.toolName)
        assertEquals(ApprovalRiskLevel.HIGH, telemetry.riskLevel)
        assertEquals("""{"command":"gradlew test"}""", telemetry.rawArgs)
        assertEquals(false, telemetry.approveEnabled)
        assertEquals("高风险命令", telemetry.explanationLabel)
        assertEquals("需要人工确认后再继续。", telemetry.explanationDetail)
        assertEquals("这是恢复出来的旧审批", telemetry.replayNotice)
        assertEquals("无法继续", telemetry.detailTelemetry.approveLabel)
    }

    @Test
    fun resolvePendingApprovalHostTelemetry_whenReplayOnlyWithoutNotice_fallsBackToCanonicalReplayNotice() {
        val telemetry = resolvePendingApprovalHostTelemetry(
            PendingApprovalUi(
                toolName = "shell",
                summary = "执行命令需要确认",
                detail = "shell 仍需 fresh approval",
                rawArgs = """{"command":"gradlew test"}""",
                riskLevel = ApprovalRiskLevel.HIGH,
                isReplayOnly = true,
                replayNotice = null
            )
        )

        assertEquals(false, telemetry.approveEnabled)
        assertEquals(REPLAY_ONLY_APPROVAL_NOTICE, telemetry.replayNotice)
        assertEquals(REPLAY_ONLY_APPROVAL_NOTICE, telemetry.detailTelemetry.supportText)
    }
}
