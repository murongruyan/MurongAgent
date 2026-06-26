package com.murong.agent.core.loop

import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovalCardTelemetryTest {

    @Test
    fun resolveApprovalCardTelemetry_keepsPendingStateAndTrimsRecentApprovals() {
        val telemetry = resolveApprovalCardTelemetry(
            pendingApproval = PendingApprovalUi(
                toolName = "shell",
                summary = " 执行命令需要确认 ",
                detail = "fresh approval",
                rawArgs = "{}",
                riskLevel = ApprovalRiskLevel.HIGH,
                replayNotice = " 恢复态只读 "
            ),
            recentApprovals = listOf(
                ApprovalRecordUi(
                    toolName = "file",
                    summary = " 允许读取配置 ",
                    decision = "Approved",
                    explanationLabel = "人工确认",
                    scopeSummary = "read"
                ),
                ApprovalRecordUi(toolName = "shell", summary = "允许执行命令", decision = "Rejected"),
                ApprovalRecordUi(toolName = "code_edit", summary = "允许改动代码", decision = "Approved"),
                ApprovalRecordUi(toolName = "web_search", summary = "允许联网", decision = "Approved")
            )
        )

        assertTrue(telemetry.hasPendingApproval)
        assertEquals("shell", telemetry.pendingToolName)
        assertEquals("执行命令需要确认", telemetry.pendingSummary)
        assertEquals("恢复态只读", telemetry.replayNotice)
        assertEquals(3, telemetry.recentApprovals.size)
        assertEquals("file", telemetry.recentApprovals.first().toolName)
        assertEquals("允许读取配置", telemetry.recentApprovals.first().summary)
        assertEquals("人工确认", telemetry.recentApprovals.first().explanationLabel)
        assertEquals("read", telemetry.recentApprovals.first().scopeSummary)
    }

    @Test
    fun resolveApprovalCardTelemetry_whenReplayOnlyPendingApprovalLacksNotice_fallsBackToCanonicalReplayNotice() {
        val telemetry = resolveApprovalCardTelemetry(
            pendingApproval = PendingApprovalUi(
                toolName = "shell",
                summary = "执行命令需要确认",
                detail = "fresh approval",
                rawArgs = "{}",
                riskLevel = ApprovalRiskLevel.HIGH,
                isReplayOnly = true,
                replayNotice = null
            ),
            recentApprovals = emptyList()
        )

        assertTrue(telemetry.hasPendingApproval)
        assertEquals(true, telemetry.pendingApprovalIsReplayOnly)
        assertEquals(REPLAY_ONLY_APPROVAL_NOTICE, telemetry.replayNotice)
    }

    @Test
    fun resolveProjectApprovalCardTelemetry_mergesAllProjectApprovalSourcesIntoTypedItems() {
        val telemetry = resolveProjectApprovalCardTelemetry(
            recentApprovalInvalidations = listOf(
                ApprovalInvalidationRecordUi(
                    summary = "GitHub 写入",
                    sourceLabel = "项目自动继承",
                    sourceDetail = "继承自会话：发布准备",
                    reasonLabel = "项目路径已切换",
                    reasonDetail = "当前项目路径与原授权来源不一致，已撤销自动继承授权。"
                )
            ),
            approvedApprovalScopes = listOf(
                ApprovedApprovalScopeUi(
                    id = "scope-1",
                    capabilities = listOf("code_edit"),
                    summary = "代码编辑",
                    sourceLabel = "当前会话"
                )
            ),
            projectApprovalHistory = ProjectApprovalHistoryUi(
                analyzedSessionCount = 4,
                sessionsWithApprovedScopes = 3,
                distinctScopeCount = 2,
                scopeTrends = listOf(
                    ProjectApprovalScopeTrendUi(
                        id = "trend-1",
                        summary = "GitHub 写入",
                        capabilities = listOf("GitHub 写入"),
                        sessionCount = 5,
                        directSessionCount = 3,
                        importedSessionCount = 1,
                        autoInheritedSessionCount = 1,
                        policyLabel = "需手动导入",
                        policyDetail = "该范围包含高风险写能力，不自动继承。",
                        latestSourceSessionTitle = "发布准备",
                        latestSourceUpdatedAt = 123L
                    )
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
            )
        )

        assertEquals(4, telemetry.history?.analyzedSessionCount)
        assertEquals(3, telemetry.history?.sessionsWithApprovedScopes)
        assertEquals(2, telemetry.history?.distinctScopeCount)
        assertEquals(4, telemetry.items.size)
        assertEquals(ProjectApprovalItemKind.CURRENT_SCOPE, telemetry.items[0].kind)
        assertEquals(ProjectApprovalItemKind.INHERITED_SCOPE, telemetry.items[1].kind)
        assertEquals(ProjectApprovalItemKind.TREND, telemetry.items[2].kind)
        assertEquals(ProjectApprovalItemKind.INVALIDATION, telemetry.items[3].kind)
        assertEquals("代码编辑", telemetry.items[0].summary)
        assertEquals("修复工作流", telemetry.items[1].sourceSessionTitle)
        assertEquals(5, telemetry.items[2].sessionCount)
        assertEquals("项目路径已切换", telemetry.items[3].reasonLabel)
    }

    @Test
    fun resolveProjectApprovalCardTelemetry_keepsEmptyItemsWhenNothingReusableExists() {
        val telemetry = resolveProjectApprovalCardTelemetry(
            recentApprovalInvalidations = emptyList(),
            approvedApprovalScopes = emptyList(),
            projectApprovalHistory = null,
            inheritedApprovalScopes = emptyList()
        )

        assertNull(telemetry.history)
        assertTrue(telemetry.items.isEmpty())
    }
}
