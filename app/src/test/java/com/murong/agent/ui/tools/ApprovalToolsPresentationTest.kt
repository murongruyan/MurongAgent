package com.murong.agent.ui.tools

import com.murong.agent.core.config.ProjectToolPreferences
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.core.loop.ApprovalRecordUi
import com.murong.agent.core.loop.PendingApprovalUi
import com.murong.agent.core.loop.resolveApprovalRuntimeTelemetry
import com.murong.agent.core.tool.ApprovalRiskLevel
import com.murong.agent.ui.buildApprovalRuntimePosturePresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovalToolsPresentationTest {

    @Test
    fun buildApprovalToolsPresentation_mergesCardsAndPendingDetailIntoSingleHostModel() {
        val config = config(
            globalMode = ToolApprovalMode.ALL_APPROVAL,
            overrideMode = ToolApprovalMode.WHITELIST_AUTO
        )
        val approvalRuntimeTelemetry = resolveApprovalRuntimeTelemetry(
            pendingApproval = PendingApprovalUi(
                toolName = "shell",
                summary = "执行命令需要确认",
                detail = "shell 仍需 fresh approval",
                rawArgs = """{"command":"git status"}""",
                riskLevel = ApprovalRiskLevel.HIGH
            ),
            recentApprovals = listOf(
                ApprovalRecordUi(
                    toolName = "file",
                    summary = "允许读取配置",
                    decision = "Approved"
                )
            ),
            approvedApprovalScopes = emptyList(),
            inheritedApprovalScopes = emptyList(),
            recentInvalidations = emptyList(),
            projectApprovalHistory = null
        )
        val presentation = buildApprovalToolsPresentation(
            runtimePosturePresentation = buildApprovalRuntimePosturePresentation(
                config = config,
                approvalRuntimeTelemetry = approvalRuntimeTelemetry,
                runtimeStatusSnapshot = null
            ),
            approvalRuntimeTelemetry = approvalRuntimeTelemetry
        )

        assertEquals("当前有待审批工具调用", presentation.postureOverview.headline)
        assertEquals("等待审批: shell", presentation.approvalCard.pendingTitle)
        assertEquals("项目授权", presentation.projectApprovalCard.sectionTitle)
        assertNotNull(presentation.pendingApproval)
        assertEquals("shell", presentation.pendingApproval.toolName)
        assertEquals("允许", presentation.pendingApproval.approveLabel)
        assertTrue(presentation.pendingApproval.approveEnabled)
    }

    @Test
    fun buildApprovalToolsPresentation_whenNoPendingApproval_keepsPendingDetailNull() {
        val config = config(
            globalMode = ToolApprovalMode.WHITELIST_AUTO,
            overrideMode = null
        )
        val approvalRuntimeTelemetry = resolveApprovalRuntimeTelemetry(
            pendingApproval = null,
            recentApprovals = emptyList(),
            approvedApprovalScopes = emptyList(),
            inheritedApprovalScopes = emptyList(),
            recentInvalidations = emptyList(),
            projectApprovalHistory = null
        )
        val presentation = buildApprovalToolsPresentation(
            runtimePosturePresentation = buildApprovalRuntimePosturePresentation(
                config = config,
                approvalRuntimeTelemetry = approvalRuntimeTelemetry,
                runtimeStatusSnapshot = null
            ),
            approvalRuntimeTelemetry = approvalRuntimeTelemetry
        )

        assertEquals("当前没有待审批工具调用。", presentation.approvalCard.emptyStateText)
        assertNull(presentation.pendingApproval)
    }

    private fun config(
        globalMode: ToolApprovalMode,
        overrideMode: ToolApprovalMode?
    ): ProviderConfig {
        return ProviderConfig(
            approvalMode = globalMode,
            projectToolPreferences = overrideMode?.let {
                ProjectToolPreferences(approvalMode = it)
            }
        )
    }
}
