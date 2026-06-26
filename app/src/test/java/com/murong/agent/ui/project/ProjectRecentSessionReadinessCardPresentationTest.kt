package com.murong.agent.ui.project

import com.murong.agent.core.loop.FinalReadinessSessionStatusKind
import com.murong.agent.core.loop.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProjectRecentSessionReadinessCardPresentationTest {

    @Test
    fun buildProjectRecentSessionReadinessCardModel_prefersSelectedRepoRoot() {
        val model = buildProjectRecentSessionReadinessCardModel(
            sessions = listOf(
                summary(
                    id = "workspace",
                    projectPath = "C:/workspace",
                    latestSummary = "提醒后已恢复放行",
                    statusKind = FinalReadinessSessionStatusKind.RECOVERED,
                    reasonSummary = "计划仍有 0 个未签收步骤"
                ),
                summary(
                    id = "repo",
                    projectPath = "C:/workspace/repo-a",
                    latestSummary = "仍阻塞",
                    statusKind = FinalReadinessSessionStatusKind.BLOCKED,
                    reasonSummary = "缺少 code_edit 对应签收"
                )
            ),
            currentProjectPath = "C:/workspace",
            selectedRepoRoot = "C:/workspace/repo-a"
        )

        assertNotNull(model)
        assertEquals("repo", model.session.id)
        assertEquals("当前仓库", model.sourceLabel)
        assertEquals("当前仍阻塞", model.statusLabel)
        assertEquals("去处理阻塞", model.actionLabel)
        assertEquals(true, model.blocked)
        assertEquals("当前仓库 · 3 条消息 · 收口 2 次 · provider", model.supportText)
        assertEquals("仍阻塞", model.readinessSummary)
        assertEquals("缺少 code_edit 对应签收", model.readinessReasonSummary)
    }

    @Test
    fun buildProjectRecentSessionReadinessCardModel_fallsBackToCurrentProject() {
        val model = buildProjectRecentSessionReadinessCardModel(
            sessions = listOf(
                summary(
                    id = "project",
                    projectPath = "C:/workspace",
                    latestSummary = "提醒后已恢复放行",
                    statusKind = FinalReadinessSessionStatusKind.RECOVERED,
                    reasonSummary = "计划仍有 0 个未签收步骤"
                ),
                summary(
                    id = "other",
                    projectPath = "C:/other",
                    latestSummary = "仍阻塞",
                    statusKind = FinalReadinessSessionStatusKind.BLOCKED
                )
            ),
            currentProjectPath = "C:/workspace",
            selectedRepoRoot = null
        )

        assertNotNull(model)
        assertEquals("project", model.session.id)
        assertEquals("当前项目", model.sourceLabel)
        assertEquals("提醒后已恢复", model.statusLabel)
        assertEquals("查看恢复记录", model.actionLabel)
        assertEquals(false, model.blocked)
        assertEquals("计划仍有 0 个未签收步骤", model.readinessReasonSummary)
    }

    @Test
    fun buildProjectRecentSessionReadinessCardModel_usesLatestActiveFallback() {
        val model = buildProjectRecentSessionReadinessCardModel(
            sessions = listOf(
                summary(
                    id = "fallback",
                    projectPath = "C:/other",
                    latestSummary = "仍阻塞",
                    statusKind = FinalReadinessSessionStatusKind.BLOCKED
                ),
                summary(
                    id = "empty",
                    projectPath = "C:/workspace",
                    latestSummary = null
                )
            ),
            currentProjectPath = "C:/workspace",
            selectedRepoRoot = "C:/workspace/repo-a"
        )

        assertNotNull(model)
        assertEquals("fallback", model.session.id)
        assertEquals("最近活跃会话", model.sourceLabel)
        assertEquals("当前仍阻塞", model.statusLabel)
    }

    @Test
    fun buildProjectRecentSessionReadinessCardModel_returnsNullWhenNoReadinessSummaryExists() {
        val model = buildProjectRecentSessionReadinessCardModel(
            sessions = listOf(
                summary(
                    id = "empty",
                    projectPath = "C:/workspace",
                    latestSummary = null
                )
            ),
            currentProjectPath = "C:/workspace",
            selectedRepoRoot = null
        )

        assertNull(model)
    }

    private fun summary(
        id: String,
        projectPath: String?,
        latestSummary: String?,
        statusKind: FinalReadinessSessionStatusKind = FinalReadinessSessionStatusKind.NONE,
        reasonSummary: String? = null
    ): SessionSummary {
        return SessionSummary(
            id = id,
            title = id,
            createdAt = 1L,
            updatedAt = 2L,
            messageCount = 3,
            providerId = "provider",
            modelName = "model",
            projectPath = projectPath,
            finalReadinessAuditCount = 2,
            latestFinalReadinessAuditSummary = latestSummary,
            latestFinalReadinessStatusKind = statusKind,
            latestFinalReadinessReasonSummary = reasonSummary
        )
    }
}
