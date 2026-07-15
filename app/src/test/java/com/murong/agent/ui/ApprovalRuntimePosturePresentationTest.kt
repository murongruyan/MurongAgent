package com.murong.agent.ui

import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.core.loop.ApprovalPostureStatusKind
import com.murong.agent.core.loop.ApprovalPostureTelemetry
import com.murong.agent.core.loop.ApprovalScopeTelemetrySummary
import com.murong.agent.core.loop.BackgroundActivityFocusKind
import com.murong.agent.core.loop.BackgroundActivityFocusTelemetry
import com.murong.agent.core.loop.RuntimeApprovalPostureDisplayMode
import com.murong.agent.core.loop.RuntimeStatusKind
import com.murong.agent.core.loop.RuntimeStatusSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalRuntimePosturePresentationTest {

    @Test
    fun buildApprovalRuntimePosturePresentation_mergesModeHostAndSecondaryRuntimeFocus() {
        val presentation = buildApprovalRuntimePosturePresentation(
            globalMode = ToolApprovalMode.WHITELIST_AUTO,
            overrideMode = null,
            postureTelemetry = ApprovalPostureTelemetry(
                statusKind = ApprovalPostureStatusKind.IDLE,
                hasPendingApproval = false,
                pendingApprovalIsReplayOnly = false,
                hasReusableScopes = false,
                hasRecentInvalidations = false,
                scopeSummary = ApprovalScopeTelemetrySummary(
                    currentApprovalCount = 1,
                    inheritedApprovalCount = 2,
                    recentApprovalCount = 3,
                    recentInvalidationCount = 0
                )
            ),
            runtimeStatusSnapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.BACKGROUND_ACTIVITY,
                title = "后台任务待审批",
                message = "后台摘要",
                approvalPostureDisplayMode = RuntimeApprovalPostureDisplayMode.LABEL_ONLY,
                backgroundActivityFocusKind = BackgroundActivityFocusKind.PENDING_APPROVAL,
                backgroundActivityFocusTelemetry = BackgroundActivityFocusTelemetry(
                    label = "核心待审批",
                    summary = "来自 core 的后台焦点摘要。"
                )
            )
        )

        val runtimeFocus = presentation.secondary.runtimeFocus
        assertEquals("Auto（白名单）（跟随全局）", presentation.label)
        assertEquals("审批: 跟随全局 (Auto（白名单）)", presentation.shortcutLabel)
        assertEquals("当前按审批姿态守门", presentation.postureHost.headline)
        assertEquals("当前无待审批", presentation.postureHost.currentStatus)
        assertEquals("核心待审批", runtimeFocus?.value)
        assertTrue(runtimeFocus != null)
        assertTrue(runtimeFocus.summary.contains("来自 core 的后台焦点摘要"))
        assertTrue(runtimeFocus.summary.contains("当前审批姿态：Auto（白名单）（跟随全局）"))
        assertEquals(1, presentation.secondary.scopeSummary.currentApprovalCount)
        assertEquals(2, presentation.secondary.scopeSummary.inheritedApprovalCount)
    }
}
