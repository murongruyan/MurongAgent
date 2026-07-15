package com.murong.agent.ui

import com.murong.agent.core.loop.ApprovalPostureStatusKind
import com.murong.agent.core.loop.ApprovalPostureTelemetry
import com.murong.agent.core.loop.ApprovalScopeTelemetrySummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalPostureHostPresentationTest {

    @Test
    fun buildApprovalPostureHostPresentation_reportsReplayOnlyStateAndNotice() {
        val presentation = buildApprovalPostureHostPresentation(
            approvalMode = ApprovalModeHostPresentation(
                label = "Ask（只读）（跟随全局）",
                message = "当前审批姿态：Ask（只读）（跟随全局）。",
                shortcutLabel = "审批: 跟随全局 (Ask（只读）)"
            ),
            postureTelemetry = ApprovalPostureTelemetry(
                statusKind = ApprovalPostureStatusKind.REPLAY_ONLY_PENDING_APPROVAL,
                hasPendingApproval = true,
                pendingApprovalIsReplayOnly = true,
                hasReusableScopes = false,
                hasRecentInvalidations = false,
                scopeSummary = ApprovalScopeTelemetrySummary(0, 0, 0, 0),
                replayNotice = "恢复态只读，不能继续原审批链路。"
            ),
        )

        assertEquals("恢复态审批回放", presentation.headline)
        assertEquals("只读回放", presentation.currentStatus)
        assertTrue(presentation.supportText.contains("当前审批姿态"))
        assertTrue(presentation.supportText.contains("恢复态只读"))
    }

    @Test
    fun buildApprovalPostureHostPresentation_prefersInvalidationBeforeReusableScopes() {
        val presentation = buildApprovalPostureHostPresentation(
            approvalMode = ApprovalModeHostPresentation(
                label = "Auto（白名单）（项目覆盖）",
                message = "命中白名单的操作可自动通过。",
                shortcutLabel = "审批: Auto（白名单）"
            ),
            postureTelemetry = ApprovalPostureTelemetry(
                statusKind = ApprovalPostureStatusKind.RECENT_INVALIDATION,
                hasPendingApproval = false,
                pendingApprovalIsReplayOnly = false,
                hasReusableScopes = true,
                hasRecentInvalidations = true,
                scopeSummary = ApprovalScopeTelemetrySummary(1, 0, 0, 1)
            )
        )

        assertEquals("最近有授权失效", presentation.headline)
        assertEquals("最近有失效记录", presentation.currentStatus)
    }
}
