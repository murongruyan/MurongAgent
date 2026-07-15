package com.murong.agent.core.loop

import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalPostureTelemetryTest {

    @Test
    fun resolveApprovalPostureTelemetry_prefersReplayOnlyPendingApprovalOverOtherSignals() {
        val telemetry = resolveApprovalPostureTelemetry(
            pendingApproval = PendingApprovalUi(
                toolName = "shell",
                summary = "恢复态旧审批",
                detail = "只允许查看",
                rawArgs = "{}",
                riskLevel = ApprovalRiskLevel.HIGH,
                isReplayOnly = true,
                replayNotice = "恢复态只读，不能继续原审批链路。"
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
                    id = "inherited-1",
                    capabilities = listOf("read"),
                    summary = "继承读取",
                    policyLabel = "可自动继承",
                    policyDetail = "同项目可继承",
                    sourceSessionId = "session-1",
                    sourceSessionTitle = "上次修复",
                    sourceUpdatedAt = 1L
                )
            ),
            recentInvalidations = listOf(
                ApprovalInvalidationRecordUi(
                    summary = "Shell 授权已失效",
                    sourceLabel = "历史授权",
                    reasonLabel = "项目切换",
                    reasonDetail = "切换项目后自动清理"
                )
            )
        )

        assertEquals(ApprovalPostureStatusKind.REPLAY_ONLY_PENDING_APPROVAL, telemetry.statusKind)
        assertEquals(true, telemetry.hasPendingApproval)
        assertEquals(true, telemetry.pendingApprovalIsReplayOnly)
        assertEquals(true, telemetry.hasReusableScopes)
        assertEquals(true, telemetry.hasRecentInvalidations)
        assertEquals(listOf("shell", "恢复态旧审批"), telemetry.pendingSummary?.parts)
        assertEquals(true, telemetry.pendingSummary?.emphasized)
        assertEquals(1, telemetry.scopeSummary.currentApprovalCount)
        assertEquals(1, telemetry.scopeSummary.inheritedApprovalCount)
        assertEquals(1, telemetry.scopeSummary.recentApprovalCount)
        assertEquals(1, telemetry.scopeSummary.recentInvalidationCount)
        assertEquals("恢复态只读，不能继续原审批链路。", telemetry.replayNotice)
    }

    @Test
    fun resolveApprovalPostureTelemetry_whenReplayOnlyPendingApprovalLacksNotice_fallsBackToCanonicalReplayNotice() {
        val telemetry = resolveApprovalPostureTelemetry(
            pendingApproval = PendingApprovalUi(
                toolName = "shell",
                summary = "恢复态旧审批",
                detail = "只允许查看",
                rawArgs = "{}",
                riskLevel = ApprovalRiskLevel.HIGH,
                isReplayOnly = true,
                replayNotice = null
            ),
            recentApprovals = emptyList(),
            approvedApprovalScopes = emptyList(),
            inheritedApprovalScopes = emptyList(),
            recentInvalidations = emptyList()
        )

        assertEquals(ApprovalPostureStatusKind.REPLAY_ONLY_PENDING_APPROVAL, telemetry.statusKind)
        assertEquals(REPLAY_ONLY_APPROVAL_NOTICE, telemetry.replayNotice)
    }

    @Test
    fun resolveApprovalPostureTelemetry_prefersInvalidationBeforeReusableScopes() {
        val telemetry = resolveApprovalPostureTelemetry(
            pendingApproval = null,
            recentApprovals = emptyList(),
            approvedApprovalScopes = listOf(
                ApprovedApprovalScopeUi(
                    id = "scope-1",
                    capabilities = listOf("code_edit"),
                    summary = "代码编辑",
                    sourceLabel = "当前会话"
                )
            ),
            inheritedApprovalScopes = emptyList(),
            recentInvalidations = listOf(
                ApprovalInvalidationRecordUi(
                    summary = "Shell 授权已失效",
                    sourceLabel = "历史授权",
                    reasonLabel = "项目切换",
                    reasonDetail = "切换项目后自动清理"
                )
            )
        )

        assertEquals(ApprovalPostureStatusKind.RECENT_INVALIDATION, telemetry.statusKind)
        assertTrue(telemetry.hasReusableScopes)
        assertTrue(telemetry.hasRecentInvalidations)
    }
}
