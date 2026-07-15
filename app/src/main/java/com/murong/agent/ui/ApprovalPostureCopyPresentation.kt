package com.murong.agent.ui

import com.murong.agent.core.loop.ApprovalScopeTelemetrySummary

private const val APPROVAL_POSTURE_SECTION_TITLE = "审批姿态"

internal data class ApprovalPostureCopyPresentation(
    val sectionTitle: String,
    val approvalModeLabel: String,
    val currentStatusLabel: String,
    val runtimeFocusLabel: String,
    val runtimeFocusValue: String,
    val scopeSummaryLabel: String,
    val pendingSummaryText: String? = null
)

internal data class ApprovalPostureDetailRow(
    val label: String,
    val value: String
)

internal data class ApprovalPostureDetailNote(
    val text: String,
    val emphasized: Boolean = false
)

internal fun approvalPostureSectionTitle(): String = APPROVAL_POSTURE_SECTION_TITLE

internal fun buildApprovalPostureCopyPresentation(
    runtimeFocusLabel: String?,
    pendingSummary: String?
): ApprovalPostureCopyPresentation {
    return ApprovalPostureCopyPresentation(
        sectionTitle = approvalPostureSectionTitle(),
        approvalModeLabel = "当前姿态",
        currentStatusLabel = "当前状态",
        runtimeFocusLabel = "后台焦点",
        runtimeFocusValue = runtimeFocusLabel ?: "当前无后台任务",
        scopeSummaryLabel = "授权概览",
        pendingSummaryText = pendingSummary?.let { "当前审批: $it" }
    )
}

internal fun buildApprovalPostureScopeSummaryValue(
    summary: ApprovalScopeTelemetrySummary
): String {
    return buildList {
        add("当前授权 ${summary.currentApprovalCount}")
        add("可继承 ${summary.inheritedApprovalCount}")
        add("最近审批 ${summary.recentApprovalCount}")
        if (summary.recentInvalidationCount > 0) {
            add("最近失效 ${summary.recentInvalidationCount}")
        }
    }.joinToString(" · ")
}
