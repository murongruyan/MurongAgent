package com.murong.agent.ui.tools

import com.murong.agent.core.loop.ApprovalInvalidationRecordUi
import com.murong.agent.core.loop.resolveApprovalPostureTelemetry
import com.murong.agent.core.loop.ApprovalRecordUi
import com.murong.agent.core.loop.ApprovedApprovalScopeUi
import com.murong.agent.core.loop.BackgroundActivityFocusTelemetry
import com.murong.agent.core.loop.BackgroundActivityFocusKind
import com.murong.agent.core.loop.InheritedApprovalScopeUi
import com.murong.agent.core.loop.PendingApprovalUi
import com.murong.agent.core.loop.RuntimeApprovalPostureDisplayMode
import com.murong.agent.core.loop.RuntimeStatusKind
import com.murong.agent.core.loop.RuntimeStatusSnapshot
import com.murong.agent.core.tool.ApprovalRiskLevel
import com.murong.agent.ui.ApprovalModeHostPresentation
import com.murong.agent.ui.buildApprovalPostureSecondaryPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalPostureSecondaryPresentationTest {

    @Test
    fun buildApprovalPostureSecondaryPresentation_formatsPendingAndScopeSummary() {
        val pendingApproval = PendingApprovalUi(
            toolName = "shell",
            summary = "执行命令需要确认",
            detail = "shell 仍需 fresh approval",
            rawArgs = "{}",
            riskLevel = ApprovalRiskLevel.HIGH
        )
        val approvedScopes = listOf(
            ApprovedApprovalScopeUi(
                id = "scope-1",
                capabilities = listOf("code_edit"),
                summary = "代码编辑",
                sourceLabel = "当前会话"
            )
        )
        val inheritedScopes = listOf(
            InheritedApprovalScopeUi(
                id = "inherited-1",
                capabilities = listOf("code_edit"),
                summary = "继承代码编辑",
                policyLabel = "可自动继承",
                policyDetail = "同项目代码编辑可继承",
                sourceSessionId = "session-1",
                sourceSessionTitle = "上次修复会话",
                sourceUpdatedAt = 1L
            )
        )
        val invalidations = listOf(
            ApprovalInvalidationRecordUi(
                summary = "Shell 授权已失效",
                sourceLabel = "历史授权",
                reasonLabel = "项目切换",
                reasonDetail = "切换项目后自动清理"
            )
        )
        val presentation = buildApprovalPostureSecondaryPresentation(
            postureTelemetry = resolveApprovalPostureTelemetry(
                pendingApproval = pendingApproval,
                recentApprovals = listOf(ApprovalRecordUi(toolName = "file", summary = "允许读取配置", decision = "Approved")),
                approvedApprovalScopes = approvedScopes,
                inheritedApprovalScopes = inheritedScopes,
                recentInvalidations = invalidations
            ),
            runtimeStatusSnapshot = null,
            approvalMode = approvalMode()
        )

        assertEquals("shell · 执行命令需要确认", presentation.pendingSummary!!.text)
        assertEquals(true, presentation.pendingSummary.emphasized)
        assertEquals(1, presentation.scopeSummary.currentApprovalCount)
        assertEquals(1, presentation.scopeSummary.inheritedApprovalCount)
        assertEquals(1, presentation.scopeSummary.recentApprovalCount)
        assertEquals(1, presentation.scopeSummary.recentInvalidationCount)
        assertEquals(null, presentation.runtimeFocus)
    }

    @Test
    fun buildApprovalPostureSecondaryPresentation_formatsBackgroundFocusSummary() {
        val presentation = buildApprovalPostureSecondaryPresentation(
            postureTelemetry = resolveApprovalPostureTelemetry(
                pendingApproval = null,
                recentApprovals = emptyList(),
                approvedApprovalScopes = emptyList(),
                inheritedApprovalScopes = emptyList(),
                recentInvalidations = emptyList()
            ),
            runtimeStatusSnapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.BACKGROUND_ACTIVITY,
                title = "后台任务待审批",
                message = "当前仍有后台子代理任务未终结：待审批 1 个。",
                approvalPostureDisplayMode = RuntimeApprovalPostureDisplayMode.LABEL_ONLY,
                backgroundActivityFocusKind = BackgroundActivityFocusKind.PENDING_APPROVAL,
                backgroundActivityFocusTelemetry = BackgroundActivityFocusTelemetry(
                    label = "核心待审批",
                    summary = "来自 core 的后台焦点摘要。"
                )
            ),
            approvalMode = approvalMode()
        )

        assertEquals("核心待审批", presentation.runtimeFocus!!.value)
        assertTrue(presentation.runtimeFocus.summary.contains("来自 core 的后台焦点摘要"))
        assertTrue(presentation.runtimeFocus.summary.contains("当前审批姿态：Auto（白名单）（跟随全局）"))
    }

    private fun approvalMode(): ApprovalModeHostPresentation {
        return ApprovalModeHostPresentation(
            label = "Auto（白名单）（跟随全局）",
            message = "当前审批姿态：Auto（白名单）（跟随全局）。命中白名单的操作可自动通过。",
            shortcutLabel = "审批: 跟随全局 (Auto（白名单）)"
        )
    }
}
