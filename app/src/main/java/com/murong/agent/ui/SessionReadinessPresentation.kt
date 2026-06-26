package com.murong.agent.ui

import com.murong.agent.core.loop.FinalReadinessSessionStatusKind
import com.murong.agent.core.loop.SessionSummary

internal data class SessionReadinessPresentation(
    val blocked: Boolean,
    val recovered: Boolean,
    val statusLabel: String,
    val actionLabel: String,
    val summary: String,
    val reasonSummary: String? = null
)

internal fun SessionSummary.toSessionReadinessPresentation(): SessionReadinessPresentation? {
    val summary = latestFinalReadinessAuditSummary?.trim().orEmpty()
    if (summary.isBlank()) return null
    val statusKind = when {
        latestFinalReadinessStatusKind != FinalReadinessSessionStatusKind.NONE ->
            latestFinalReadinessStatusKind
        "仍阻塞" in summary -> FinalReadinessSessionStatusKind.BLOCKED
        "恢复放行" in summary -> FinalReadinessSessionStatusKind.RECOVERED
        else -> FinalReadinessSessionStatusKind.NONE
    }
    return SessionReadinessPresentation(
        blocked = statusKind == FinalReadinessSessionStatusKind.BLOCKED,
        recovered = statusKind == FinalReadinessSessionStatusKind.RECOVERED,
        statusLabel = when (statusKind) {
            FinalReadinessSessionStatusKind.BLOCKED -> "当前仍阻塞"
            FinalReadinessSessionStatusKind.RECOVERED -> "提醒后已恢复"
            FinalReadinessSessionStatusKind.NONE -> "最近收口记录"
        },
        actionLabel = when (statusKind) {
            FinalReadinessSessionStatusKind.BLOCKED -> "去处理阻塞"
            FinalReadinessSessionStatusKind.RECOVERED -> "查看恢复记录"
            FinalReadinessSessionStatusKind.NONE -> "打开聊天"
        },
        summary = summary,
        reasonSummary = latestFinalReadinessReasonSummary
    )
}
