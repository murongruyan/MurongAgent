package com.murong.agent.ui.tools

import com.murong.agent.core.loop.ApprovalInvalidationRecordUi
import com.murong.agent.core.loop.ApprovedApprovalScopeUi
import com.murong.agent.core.loop.InheritedApprovalScopeUi
import com.murong.agent.core.loop.ProjectApprovalHistoryUi
import com.murong.agent.core.loop.ProjectApprovalScopeTrendUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectApprovalPresentationTest {

    @Test
    fun buildProjectApprovalHistorySummary_formatsCounts() {
        val summary = buildProjectApprovalHistorySummary(
            ProjectApprovalHistoryUi(
                analyzedSessionCount = 6,
                sessionsWithApprovedScopes = 4,
                distinctScopeCount = 3
            )
        )

        assertTrue(summary.contains("已分析 6 个会话"))
        assertTrue(summary.contains("含授权会话 4 个"))
        assertTrue(summary.contains("范围趋势 3 项"))
    }

    @Test
    fun approvedAndInheritedScopeSubtitles_includePolicyContext() {
        val approvedSubtitle = ApprovedApprovalScopeUi(
            id = "scope-1",
            capabilities = listOf("GitHub 写入"),
            summary = "GitHub 写入",
            sourceLabel = "当前会话授权",
            sourceDetail = "由用户直接批准"
        ).toApprovedScopeSubtitle()
        val inheritedSubtitle = InheritedApprovalScopeUi(
            id = "scope-2",
            capabilities = listOf("Shell"),
            summary = "Shell",
            policyLabel = "同项目自动继承",
            policyDetail = "仅继承仍然有效且边界不扩大的范围。",
            sourceSessionId = "session-1",
            sourceSessionTitle = "修复工作流",
            sourceUpdatedAt = 123L
        ).toInheritedScopeSubtitle()

        assertTrue(approvedSubtitle.contains("来源：当前会话授权"))
        assertTrue(approvedSubtitle.contains("由用户直接批准"))
        assertTrue(inheritedSubtitle.contains("策略：同项目自动继承"))
        assertTrue(inheritedSubtitle.contains("来源会话：修复工作流"))
    }

    @Test
    fun trendSubtitle_includesCountsAndPolicy() {
        val subtitle = ProjectApprovalScopeTrendUi(
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
        ).toTrendSubtitle()

        assertTrue(subtitle.contains("命中会话 5 个"))
        assertTrue(subtitle.contains("直接批准 3 个"))
        assertTrue(subtitle.contains("手动导入 1 个"))
        assertTrue(subtitle.contains("自动继承 1 个"))
        assertTrue(subtitle.contains("策略：需手动导入"))
        assertTrue(subtitle.contains("不自动继承"))
    }

    @Test
    fun buildProjectApprovalHistorySummary_handlesMissingHistory() {
        assertEquals(
            "当前项目还没有可复用的授权历史。",
            buildProjectApprovalHistorySummary(null as ProjectApprovalHistoryUi?)
        )
    }

    @Test
    fun invalidationSubtitle_includesSourceAndReason() {
        val subtitle = ApprovalInvalidationRecordUi(
            summary = "GitHub 写入",
            sourceLabel = "项目自动继承",
            sourceDetail = "继承自会话：发布准备",
            reasonLabel = "项目路径已切换",
            reasonDetail = "当前项目路径与原授权来源不一致，已撤销自动继承授权。"
        ).toInvalidationSubtitle()

        assertTrue(subtitle.contains("来源：项目自动继承"))
        assertTrue(subtitle.contains("继承自会话：发布准备"))
        assertTrue(subtitle.contains("失效原因：项目路径已切换"))
        assertTrue(subtitle.contains("已撤销自动继承授权"))
    }
}
