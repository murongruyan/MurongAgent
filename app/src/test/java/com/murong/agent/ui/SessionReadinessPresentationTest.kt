package com.murong.agent.ui

import com.murong.agent.core.loop.FinalReadinessSessionStatusKind
import com.murong.agent.core.loop.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SessionReadinessPresentationTest {

    @Test
    fun toSessionReadinessPresentation_prefersStructuredStatusKindOverSummaryText() {
        val presentation = summary(
            latestSummary = "最近收口状态文本可以变化",
            statusKind = FinalReadinessSessionStatusKind.BLOCKED,
            reasonSummary = "缺少 code_edit 对应签收"
        ).toSessionReadinessPresentation()

        assertNotNull(presentation)
        assertEquals(true, presentation.blocked)
        assertEquals("当前仍阻塞", presentation.statusLabel)
        assertEquals("去处理阻塞", presentation.actionLabel)
        assertEquals("缺少 code_edit 对应签收", presentation.reasonSummary)
    }

    @Test
    fun toSessionReadinessPresentation_fallsBackToLegacySummaryWhenStructuredKindMissing() {
        val presentation = summary(
            latestSummary = "提醒后已恢复放行（计划仍有 0 个未签收步骤）",
            statusKind = FinalReadinessSessionStatusKind.NONE
        ).toSessionReadinessPresentation()

        assertNotNull(presentation)
        assertEquals(true, presentation.recovered)
        assertEquals("提醒后已恢复", presentation.statusLabel)
        assertEquals("查看恢复记录", presentation.actionLabel)
    }

    private fun summary(
        latestSummary: String?,
        statusKind: FinalReadinessSessionStatusKind,
        reasonSummary: String? = null
    ): SessionSummary {
        return SessionSummary(
            id = "session",
            title = "session",
            createdAt = 1L,
            updatedAt = 2L,
            messageCount = 3,
            providerId = "provider",
            modelName = "model",
            latestFinalReadinessAuditSummary = latestSummary,
            latestFinalReadinessStatusKind = statusKind,
            latestFinalReadinessReasonSummary = reasonSummary
        )
    }
}
