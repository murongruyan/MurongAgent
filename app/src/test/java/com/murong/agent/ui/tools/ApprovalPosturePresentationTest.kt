package com.murong.agent.ui.tools

import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.core.loop.ApprovalInvalidationRecordUi
import com.murong.agent.core.loop.ApprovalRecordUi
import com.murong.agent.core.loop.ApprovedApprovalScopeUi
import com.murong.agent.core.loop.BackgroundActivityFocusTelemetry
import com.murong.agent.core.loop.BackgroundActivityFocusKind
import com.murong.agent.core.loop.InheritedApprovalScopeUi
import com.murong.agent.core.loop.PendingApprovalUi
import com.murong.agent.core.loop.RuntimeApprovalPostureDisplayMode
import com.murong.agent.core.loop.RuntimeStatusKind
import com.murong.agent.core.loop.RuntimeStatusSnapshot
import com.murong.agent.core.loop.resolveApprovalPostureTelemetry
import com.murong.agent.core.loop.resolveApprovalRuntimeTelemetry
import com.murong.agent.core.tool.ApprovalRiskLevel
import com.murong.agent.ui.buildApprovalRuntimePosturePresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalPosturePresentationTest {

    @Test
    fun buildApprovalPostureOverviewPresentation_whenPendingApprovalExists_reportsBlockingStatus() {
        val pendingApproval = PendingApprovalUi(
            toolName = "shell",
            summary = "执行命令需要确认",
            detail = "shell 仍需 fresh approval",
            rawArgs = "{\"command\":\"git status\"}",
            riskLevel = ApprovalRiskLevel.HIGH
        )
        val recentApprovals = listOf(
            ApprovalRecordUi(
                toolName = "file",
                summary = "允许读取配置",
                decision = "Approved"
            )
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
        val presentation = postureOverviewPresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL,
            overrideMode = ToolApprovalMode.WHITELIST_AUTO,
            postureTelemetry = resolveApprovalPostureTelemetry(
                pendingApproval = pendingApproval,
                recentApprovals = recentApprovals,
                approvedApprovalScopes = approvedScopes,
                inheritedApprovalScopes = inheritedScopes,
                recentInvalidations = invalidations
            ),
            runtimeStatusSnapshot = null
        )

        assertEquals("审批姿态", presentation.sectionTitle)
        assertEquals("当前有待审批工具调用", presentation.headline)
        assertEquals(
            listOf("当前姿态", "当前状态", "后台焦点", "授权概览"),
            presentation.detailRows.map { it.label }
        )
        assertEquals("Auto（白名单）（项目覆盖）", presentation.detailRows[0].value)
        assertEquals("等待审批", presentation.detailRows[1].value)
        assertTrue(presentation.detailRows[3].value.contains("当前授权 1"))
        assertTrue(presentation.detailRows[3].value.contains("可继承 1"))
        assertTrue(presentation.detailRows[3].value.contains("最近审批 1"))
        assertTrue(presentation.detailRows[3].value.contains("最近失效 1"))
        assertTrue(presentation.secondaryNotes.first().text.contains("shell"))
        assertEquals(true, presentation.secondaryNotes.first().emphasized)
    }

    @Test
    fun buildApprovalPostureOverviewPresentation_whenUsingApprovalRuntimeTelemetry_reportsBlockingStatus() {
        val pendingApproval = PendingApprovalUi(
            toolName = "shell",
            summary = "执行命令需要确认",
            detail = "shell 仍需 fresh approval",
            rawArgs = "{\"command\":\"git status\"}",
            riskLevel = ApprovalRiskLevel.HIGH
        )
        val runtimeTelemetry = resolveApprovalRuntimeTelemetry(
            pendingApproval = pendingApproval,
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
            inheritedApprovalScopes = emptyList(),
            recentInvalidations = emptyList(),
            projectApprovalHistory = null
        )
        val presentation = buildApprovalPostureOverviewPresentation(
            runtimePosturePresentation = buildApprovalRuntimePosturePresentation(
                config = config(
                globalMode = ToolApprovalMode.ALL_APPROVAL,
                overrideMode = ToolApprovalMode.WHITELIST_AUTO
                ),
                approvalRuntimeTelemetry = runtimeTelemetry,
                runtimeStatusSnapshot = null
            )
        )

        assertEquals("当前有待审批工具调用", presentation.headline)
        assertEquals("等待审批", presentation.detailRows[1].value)
        assertTrue(presentation.detailRows[3].value.contains("当前授权 1"))
        assertTrue(presentation.secondaryNotes.first().text.contains("shell"))
    }

    @Test
    fun buildApprovalPostureOverviewPresentation_whenReplayOnlyApprovalExists_reportsReplayStatus() {
        val pendingApproval = PendingApprovalUi(
            toolName = "mcp_create_pull_request",
            summary = "恢复时带出旧审批",
            detail = "当前只允许回放",
            rawArgs = "{}",
            riskLevel = ApprovalRiskLevel.HIGH,
            isReplayOnly = true,
            replayNotice = "恢复态只读，不能继续原审批链路。"
        )
        val presentation = postureOverviewPresentation(
            globalMode = ToolApprovalMode.READ_ONLY,
            overrideMode = null,
            postureTelemetry = resolveApprovalPostureTelemetry(
                pendingApproval = pendingApproval,
                recentApprovals = emptyList(),
                approvedApprovalScopes = emptyList(),
                inheritedApprovalScopes = emptyList(),
                recentInvalidations = emptyList()
            ),
            runtimeStatusSnapshot = null
        )

        assertEquals("审批姿态", presentation.sectionTitle)
        assertEquals("恢复态审批回放", presentation.headline)
        assertTrue(presentation.supportText.contains("当前审批姿态"))
        assertTrue(presentation.supportText.contains("恢复态只读"))
        assertEquals("只读回放", presentation.detailRows[1].value)
        assertEquals("当前授权 0 · 可继承 0 · 最近审批 0", presentation.detailRows[3].value)
        assertEquals(true, presentation.secondaryNotes.first().emphasized)
    }

    @Test
    fun buildApprovalPostureOverviewPresentation_whenBackgroundPendingApprovalExists_reportsFocusedRuntimeSummary() {
        val presentation = postureOverviewPresentation(
            globalMode = ToolApprovalMode.WHITELIST_AUTO,
            overrideMode = null,
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
            )
        )

        assertEquals("核心待审批", presentation.detailRows[2].value)
        assertTrue(presentation.secondaryNotes.single().text.contains("来自 core 的后台焦点摘要"))
        assertTrue(presentation.secondaryNotes.single().text.contains("当前审批姿态：Auto（白名单）（跟随全局）"))
        assertTrue(!presentation.secondaryNotes.single().text.contains("命中白名单的操作可自动通过"))
    }

    @Test
    fun buildApprovalPostureOverviewPresentation_whenBackgroundRunningExists_reportsFocusedRuntimeBadge() {
        val presentation = postureOverviewPresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL,
            overrideMode = null,
            postureTelemetry = resolveApprovalPostureTelemetry(
                pendingApproval = null,
                recentApprovals = emptyList(),
                approvedApprovalScopes = emptyList(),
                inheritedApprovalScopes = emptyList(),
                recentInvalidations = emptyList()
            ),
            runtimeStatusSnapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.BACKGROUND_ACTIVITY,
                title = "后台任务运行中",
                message = "当前仍有后台子代理任务未终结：运行中 1 个。",
                approvalPostureDisplayMode = RuntimeApprovalPostureDisplayMode.FULL_MESSAGE,
                backgroundActivityFocusKind = BackgroundActivityFocusKind.RUNNING,
                backgroundActivityFocusTelemetry = BackgroundActivityFocusTelemetry(
                    label = "核心后台运行",
                    summary = "来自 core 的后台运行摘要。"
                )
            )
        )

        assertEquals("核心后台运行", presentation.detailRows[2].value)
        assertTrue(presentation.secondaryNotes.single().text.contains("来自 core 的后台运行摘要"))
        assertTrue(presentation.secondaryNotes.single().text.contains("所有工具调用都需要你显式确认"))
    }

    private fun config(
        globalMode: ToolApprovalMode,
        overrideMode: ToolApprovalMode?
    ): com.murong.agent.core.config.ProviderConfig {
        return com.murong.agent.core.config.ProviderConfig(
            approvalMode = globalMode,
            projectToolPreferences = overrideMode?.let {
                com.murong.agent.core.config.ProjectToolPreferences(approvalMode = it)
            }
        )
    }

    private fun postureOverviewPresentation(
        globalMode: ToolApprovalMode,
        overrideMode: ToolApprovalMode?,
        postureTelemetry: com.murong.agent.core.loop.ApprovalPostureTelemetry,
        runtimeStatusSnapshot: RuntimeStatusSnapshot?
    ): ApprovalPostureOverviewPresentation {
        return buildApprovalPostureOverviewPresentation(
            runtimePosturePresentation = buildApprovalRuntimePosturePresentation(
                globalMode = globalMode,
                overrideMode = overrideMode,
                postureTelemetry = postureTelemetry,
                runtimeStatusSnapshot = runtimeStatusSnapshot
            )
        )
    }
}
