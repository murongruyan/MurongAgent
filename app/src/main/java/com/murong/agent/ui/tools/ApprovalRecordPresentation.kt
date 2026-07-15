package com.murong.agent.ui.tools

import com.murong.agent.core.loop.ApprovalRecentDecisionTelemetry
import com.murong.agent.core.loop.ApprovalRecordUi
import com.murong.agent.ui.sanitizeForUiDisplay

internal fun ApprovalRecordUi.toApprovalRecordListSubtitle(): String {
    return ApprovalRecentDecisionTelemetry(
        toolName = toolName,
        decision = decision,
        summary = summary,
        explanationLabel = explanationLabel,
        scopeSummary = scopeSummary
    ).toApprovalRecordListSubtitle()
}

internal fun ApprovalRecentDecisionTelemetry.toApprovalRecordListSubtitle(): String {
    return buildList {
        summary?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { add(sanitizeForUiDisplay(it)) }
        explanationLabel?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { add("原因：${sanitizeForUiDisplay(it)}") }
        scopeSummary?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { add("范围：${sanitizeForUiDisplay(it)}") }
    }.joinToString("\n").ifBlank {
        "无摘要"
    }
}
