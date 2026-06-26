package com.murong.agent.ui.tools

import com.murong.agent.core.loop.ApprovalCardTelemetry
import com.murong.agent.core.loop.ApprovalRecentDecisionTelemetry
import com.murong.agent.core.loop.ApprovalRuntimeTelemetry
import com.murong.agent.core.loop.PendingApprovalDetailTelemetry
import com.murong.agent.core.loop.ProjectApprovalCardTelemetry
import com.murong.agent.core.loop.ProjectApprovalItemKind
import com.murong.agent.core.loop.ProjectApprovalItemTelemetry

internal data class ApprovalListItemPresentation(
    val title: String,
    val subtitle: String
)

internal data class ApprovalCardPresentation(
    val sectionTitle: String,
    val pendingTitle: String? = null,
    val pendingSupportText: String? = null,
    val detailActionLabel: String? = null,
    val approveActionLabel: String? = null,
    val rejectActionLabel: String? = null,
    val approveEnabled: Boolean = false,
    val emptyStateText: String? = null,
    val emptyActionLabel: String? = null,
    val recentApprovalsTitle: String? = null,
    val recentApprovalItems: List<ApprovalListItemPresentation> = emptyList()
)

internal data class ProjectApprovalCardPresentation(
    val sectionTitle: String,
    val summaryText: String,
    val emptyStateText: String? = null,
    val items: List<ApprovalListItemPresentation> = emptyList()
)

internal fun buildApprovalCardPresentation(
    approvalRuntimeTelemetry: ApprovalRuntimeTelemetry
): ApprovalCardPresentation {
    return buildApprovalCardPresentation(
        approvalTelemetry = approvalRuntimeTelemetry.approvalCardTelemetry,
        pendingApprovalTelemetry = approvalRuntimeTelemetry.pendingApprovalHostTelemetry?.detailTelemetry
    )
}

internal fun buildApprovalCardPresentation(
    approvalTelemetry: ApprovalCardTelemetry,
    pendingApprovalTelemetry: PendingApprovalDetailTelemetry? = null
): ApprovalCardPresentation {
    if (!approvalTelemetry.hasPendingApproval) {
        return ApprovalCardPresentation(
            sectionTitle = "审批状态",
            emptyStateText = "当前没有待审批工具调用。",
            emptyActionLabel = "回到对话",
            recentApprovalsTitle = approvalTelemetry.recentApprovals
                .takeIf { it.isNotEmpty() }
                ?.let { "最近审批" },
            recentApprovalItems = buildRecentApprovalItems(approvalTelemetry.recentApprovals)
        )
    }
    return ApprovalCardPresentation(
        sectionTitle = "审批状态",
        pendingTitle = buildPendingApprovalCardTitle(approvalTelemetry),
        pendingSupportText = buildPendingApprovalCardSupportText(
            approvalTelemetry = approvalTelemetry,
            pendingApprovalTelemetry = pendingApprovalTelemetry
        ),
        detailActionLabel = "查看详情",
        approveActionLabel = pendingApprovalTelemetry?.approveLabel
            ?: if (approvalTelemetry.pendingApprovalIsReplayOnly) "无法继续" else "批准",
        rejectActionLabel = pendingApprovalTelemetry?.rejectLabel
            ?: if (approvalTelemetry.pendingApprovalIsReplayOnly) "关闭" else "拒绝",
        approveEnabled = !approvalTelemetry.pendingApprovalIsReplayOnly,
        recentApprovalsTitle = approvalTelemetry.recentApprovals
            .takeIf { it.isNotEmpty() }
            ?.let { "最近审批" },
        recentApprovalItems = buildRecentApprovalItems(approvalTelemetry.recentApprovals)
    )
}

internal fun buildProjectApprovalCardPresentation(
    approvalRuntimeTelemetry: ApprovalRuntimeTelemetry
): ProjectApprovalCardPresentation {
    return buildProjectApprovalCardPresentation(
        projectApprovalTelemetry = approvalRuntimeTelemetry.projectApprovalCardTelemetry
    )
}

internal fun buildProjectApprovalCardPresentation(
    projectApprovalTelemetry: ProjectApprovalCardTelemetry
): ProjectApprovalCardPresentation {
    val items = projectApprovalTelemetry.items.map(::toApprovalListItemPresentation)
    return ProjectApprovalCardPresentation(
        sectionTitle = "项目授权",
        summaryText = buildProjectApprovalHistorySummary(projectApprovalTelemetry.history),
        emptyStateText = if (items.isEmpty()) {
            "当前还没有已记录的会话授权、可继承授权、范围趋势或授权失效记录。"
        } else {
            null
        },
        items = items
    )
}

private fun buildPendingApprovalCardTitle(
    approvalTelemetry: ApprovalCardTelemetry
): String {
    return "等待审批: ${approvalTelemetry.pendingToolName.orEmpty()}"
}

private fun buildPendingApprovalCardSupportText(
    approvalTelemetry: ApprovalCardTelemetry,
    pendingApprovalTelemetry: PendingApprovalDetailTelemetry?
): String {
    return pendingApprovalTelemetry?.supportText
        ?: pendingApprovalTelemetry?.headline
        ?: approvalTelemetry.pendingSummary
        ?: approvalTelemetry.replayNotice
        ?: "等待处理当前审批请求。"
}

private fun buildRecentApprovalItems(
    recentApprovals: List<ApprovalRecentDecisionTelemetry>
): List<ApprovalListItemPresentation> {
    return recentApprovals.map { record ->
        ApprovalListItemPresentation(
            title = "${record.toolName} · ${record.decision}",
            subtitle = record.toApprovalRecordListSubtitle()
        )
    }
}

private fun toApprovalListItemPresentation(
    item: ProjectApprovalItemTelemetry
): ApprovalListItemPresentation {
    return ApprovalListItemPresentation(
        title = "${projectApprovalItemTitlePrefix(item.kind)} · ${item.summary}",
        subtitle = item.toProjectApprovalItemSubtitle()
    )
}

private fun projectApprovalItemTitlePrefix(kind: ProjectApprovalItemKind): String {
    return when (kind) {
        ProjectApprovalItemKind.CURRENT_SCOPE -> "当前会话"
        ProjectApprovalItemKind.INHERITED_SCOPE -> "可继承"
        ProjectApprovalItemKind.TREND -> "趋势"
        ProjectApprovalItemKind.INVALIDATION -> "已失效"
    }
}
