package com.murong.agent.core.loop

import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApprovalRuntimeTelemetryTest {

    @Test
    fun resolveApprovalRuntimeTelemetry_unifiesPostureCardPendingAndProjectApprovalSources() {
        val telemetry = resolveApprovalRuntimeTelemetry(
            pendingApproval = PendingApprovalUi(
                toolName = "shell",
                summary = "执行命令需要确认",
                detail = "shell 仍需 fresh approval",
                rawArgs = "{}",
                riskLevel = ApprovalRiskLevel.HIGH
            ),
            recentApprovals = listOf(
                ApprovalRecordUi(toolName = "file", summary = "允许读取配置", decision = "Approved")
            ),
            approvedApprovalScopes = listOf(
                ApprovedApprovalScopeUi(
                    id = "scope-1",
                    capabilities = listOf("code_edit"),
                    summary = "代码编辑",
                    sourceLabel = "当前会话"
                )
            ),
            inheritedApprovalScopes = listOf(
                InheritedApprovalScopeUi(
                    id = "scope-2",
                    capabilities = listOf("shell"),
                    summary = "Shell",
                    policyLabel = "同项目自动继承",
                    policyDetail = "仅继承仍然有效且边界不扩大的范围。",
                    sourceSessionId = "session-1",
                    sourceSessionTitle = "修复工作流",
                    sourceUpdatedAt = 123L
                )
            ),
            recentInvalidations = listOf(
                ApprovalInvalidationRecordUi(
                    summary = "GitHub 写入",
                    sourceLabel = "项目自动继承",
                    sourceDetail = "继承自会话：发布准备",
                    reasonLabel = "项目路径已切换",
                    reasonDetail = "当前项目路径与原授权来源不一致，已撤销自动继承授权。"
                )
            ),
            projectApprovalHistory = ProjectApprovalHistoryUi(
                analyzedSessionCount = 4,
                sessionsWithApprovedScopes = 3,
                distinctScopeCount = 2
            )
        )

        assertTrue(telemetry.postureTelemetry.hasPendingApproval)
        assertEquals("shell", telemetry.approvalCardTelemetry.pendingToolName)
        assertNotNull(telemetry.pendingApprovalHostTelemetry)
        assertEquals("允许", telemetry.pendingApprovalHostTelemetry.detailTelemetry.approveLabel)
        assertEquals(4, telemetry.projectApprovalCardTelemetry.history?.analyzedSessionCount)
        assertEquals(3, telemetry.projectApprovalCardTelemetry.items.size)
        assertEquals(1, telemetry.postureTelemetry.scopeSummary.currentApprovalCount)
    }
}
