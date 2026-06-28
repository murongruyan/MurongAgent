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
            FinalReadinessSessionStatusKind.BLOCKED -> "待继续"
            FinalReadinessSessionStatusKind.RECOVERED -> "已恢复"
            FinalReadinessSessionStatusKind.NONE -> "最近状态"
        },
        actionLabel = when (statusKind) {
            FinalReadinessSessionStatusKind.BLOCKED -> "打开聊天"
            FinalReadinessSessionStatusKind.RECOVERED -> "打开聊天"
            FinalReadinessSessionStatusKind.NONE -> "打开聊天"
        },
        summary = when (statusKind) {
            FinalReadinessSessionStatusKind.BLOCKED -> "还有收尾动作没完成，回到聊天继续即可。"
            FinalReadinessSessionStatusKind.RECOVERED -> "上次中断已恢复，可以继续当前任务。"
            FinalReadinessSessionStatusKind.NONE -> "最近有一次收尾记录。"
        },
        reasonSummary = when (statusKind) {
            FinalReadinessSessionStatusKind.BLOCKED -> "建议继续当前会话，让模型补完剩余动作。"
            FinalReadinessSessionStatusKind.RECOVERED -> "最近一次提醒后已恢复。"
            FinalReadinessSessionStatusKind.NONE -> null
        }
    )
}
