package com.murong.agent.ui.tools

import com.murong.agent.core.loop.ApprovalInvalidationRecordUi
import com.murong.agent.core.loop.ApprovalRecordUi
import com.murong.agent.core.loop.ApprovedApprovalScopeUi
import com.murong.agent.core.loop.InheritedApprovalScopeUi
import com.murong.agent.core.loop.PendingApprovalUi
import com.murong.agent.core.loop.ProjectApprovalHistoryUi
import com.murong.agent.core.loop.ProjectApprovalScopeTrendUi
import com.murong.agent.core.loop.resolveApprovalRuntimeTelemetry
import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalCardsPresentationTest {

    @Test
    fun buildApprovalCardPresentation_reportsPendingActionsAndRecentApprovals() {
        val pendingApproval = PendingApprovalUi(
            toolName = "shell",
            summary = "执行命令需要确认",
            detail = "shell 仍需 fresh approval",
            rawArgs = "{}",
            riskLevel = ApprovalRiskLevel.HIGH
        )
        val presentation = buildApprovalCardPresentation(
            approvalRuntimeTelemetry = resolveApprovalRuntimeTelemetry(
                pendingApproval = pendingApproval,
                recentApprovals = listOf(
                    ApprovalRecordUi(toolName = "file", summary = "允许读取配置", decision = "Approved"),
                    ApprovalRecordUi(toolName = "shell", summary = "允许执行命令", decision = "Rejected")
                ),
                approvedApprovalScopes = emptyList(),
                inheritedApprovalScopes = emptyList(),
                recentInvalidations = emptyList(),
                projectApprovalHistory = null
            )
        )

        assertEquals("审批状态", presentation.sectionTitle)
        assertEquals("等待审批: shell", presentation.pendingTitle)
        assertEquals("允许", presentation.approveActionLabel)
        assertEquals("拒绝", presentation.rejectActionLabel)
        assertEquals(true, presentation.approveEnabled)
        assertEquals("最近审批", presentation.recentApprovalsTitle)
        assertEquals(2, presentation.recentApprovalItems.size)
        assertTrue(presentation.recentApprovalItems.first().title.contains("file"))
    }

    @Test
    fun buildApprovalCardPresentation_reportsEmptyStateWhenNoPendingApproval() {
        val presentation = buildApprovalCardPresentation(
            approvalRuntimeTelemetry = resolveApprovalRuntimeTelemetry(
                pendingApproval = null,
                recentApprovals = emptyList(),
                approvedApprovalScopes = emptyList(),
                inheritedApprovalScopes = emptyList(),
                recentInvalidations = emptyList(),
                projectApprovalHistory = null
            )
        )

        assertEquals("当前没有待审批工具调用。", presentation.emptyStateText)
        assertEquals("回到对话", presentation.emptyActionLabel)
        assertEquals(null, presentation.pendingTitle)
        assertTrue(presentation.recentApprovalItems.isEmpty())
    }

    @Test
    fun buildProjectApprovalCardPresentation_mergesSessionInheritedTrendAndInvalidationItems() {
        val invalidations = listOf(
            ApprovalInvalidationRecordUi(
                summary = "GitHub 写入",
                sourceLabel = "项目自动继承",
                sourceDetail = "继承自会话：发布准备",
                reasonLabel = "项目路径已切换",
                reasonDetail = "当前项目路径与原授权来源不一致，已撤销自动继承授权。"
            )
        )
        val presentation = buildProjectApprovalCardPresentation(
            approvalRuntimeTelemetry = resolveApprovalRuntimeTelemetry(
                pendingApproval = null,
                recentApprovals = emptyList(),
                recentInvalidations = invalidations,
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
        )

        assertEquals("项目授权", presentation.sectionTitle)
        assertTrue(presentation.summaryText.contains("已分析 4 个会话"))
        assertEquals(null, presentation.emptyStateText)
        assertEquals(4, presentation.items.size)
        assertTrue(presentation.items[0].title.contains("当前会话"))
        assertTrue(presentation.items[1].title.contains("可继承"))
        assertTrue(presentation.items[2].title.contains("趋势"))
        assertTrue(presentation.items[3].title.contains("已失效"))
    }
}
