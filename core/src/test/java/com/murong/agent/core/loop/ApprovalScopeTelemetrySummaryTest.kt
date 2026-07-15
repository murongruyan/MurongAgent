package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals

class ApprovalScopeTelemetrySummaryTest {

    @Test
    fun resolveApprovalScopeTelemetrySummary_countsCurrentInheritedRecentAndInvalidatedScopes() {
        val summary = resolveApprovalScopeTelemetrySummary(
            recentApprovals = listOf(
                ApprovalRecordUi(toolName = "file", summary = "允许读取配置", decision = "Approved"),
                ApprovalRecordUi(toolName = "shell", summary = "允许执行命令", decision = "Approved")
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
                ),
                InheritedApprovalScopeUi(
                    id = "inherited-2",
                    capabilities = listOf("grep"),
                    summary = "继承搜索",
                    policyLabel = "可自动继承",
                    policyDetail = "同项目可继承",
                    sourceSessionId = "session-2",
                    sourceSessionTitle = "上上次修复",
                    sourceUpdatedAt = 2L
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

        assertEquals(1, summary.currentApprovalCount)
        assertEquals(2, summary.inheritedApprovalCount)
        assertEquals(2, summary.recentApprovalCount)
        assertEquals(1, summary.recentInvalidationCount)
    }
}
