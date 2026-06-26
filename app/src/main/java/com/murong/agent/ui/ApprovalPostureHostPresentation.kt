package com.murong.agent.ui

import com.murong.agent.core.loop.ApprovalPostureStatusKind
import com.murong.agent.core.loop.ApprovalPostureTelemetry

internal data class ApprovalPostureHostPresentation(
    val headline: String,
    val currentStatus: String,
    val supportText: String
)

internal fun buildApprovalPostureHostPresentation(
    approvalMode: ApprovalModeHostPresentation,
    postureTelemetry: ApprovalPostureTelemetry
): ApprovalPostureHostPresentation {
    val headline = when (postureTelemetry.statusKind) {
        ApprovalPostureStatusKind.REPLAY_ONLY_PENDING_APPROVAL -> "恢复态审批回放"
        ApprovalPostureStatusKind.PENDING_APPROVAL -> "当前有待审批工具调用"
        ApprovalPostureStatusKind.RECENT_INVALIDATION -> "最近有授权失效"
        ApprovalPostureStatusKind.REUSABLE_SCOPES -> "当前姿态已带授权缓存"
        ApprovalPostureStatusKind.IDLE -> "当前按审批姿态守门"
    }
    val currentStatus = when (postureTelemetry.statusKind) {
        ApprovalPostureStatusKind.REPLAY_ONLY_PENDING_APPROVAL -> "只读回放"
        ApprovalPostureStatusKind.PENDING_APPROVAL -> "等待审批"
        ApprovalPostureStatusKind.RECENT_INVALIDATION -> "最近有失效记录"
        ApprovalPostureStatusKind.REUSABLE_SCOPES -> "可复用部分已有授权"
        ApprovalPostureStatusKind.IDLE -> "当前无待审批"
    }
    val supportText = buildList {
        add(approvalMode.message)
        postureTelemetry.replayNotice
            ?.let { add(sanitizeForUiDisplay(it)) }
    }.joinToString("\n")
    return ApprovalPostureHostPresentation(
        headline = headline,
        currentStatus = currentStatus,
        supportText = supportText
    )
}
