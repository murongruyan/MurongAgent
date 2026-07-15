package com.murong.agent.ui.tools

import com.murong.agent.core.loop.ApprovalRuntimeTelemetry
import com.murong.agent.ui.PendingApprovalPresentation
import com.murong.agent.ui.ApprovalRuntimePosturePresentation
import com.murong.agent.ui.toPendingApprovalPresentation

internal data class ApprovalToolsPresentation(
    val postureOverview: ApprovalPostureOverviewPresentation,
    val approvalCard: ApprovalCardPresentation,
    val projectApprovalCard: ProjectApprovalCardPresentation,
    val pendingApproval: PendingApprovalPresentation? = null
)

internal fun buildApprovalToolsPresentation(
    runtimePosturePresentation: ApprovalRuntimePosturePresentation,
    approvalRuntimeTelemetry: ApprovalRuntimeTelemetry,
): ApprovalToolsPresentation {
    return ApprovalToolsPresentation(
        postureOverview = buildApprovalPostureOverviewPresentation(
            runtimePosturePresentation = runtimePosturePresentation
        ),
        approvalCard = buildApprovalCardPresentation(
            approvalRuntimeTelemetry = approvalRuntimeTelemetry
        ),
        projectApprovalCard = buildProjectApprovalCardPresentation(
            approvalRuntimeTelemetry = approvalRuntimeTelemetry
        ),
        pendingApproval = approvalRuntimeTelemetry.pendingApprovalHostTelemetry
            ?.toPendingApprovalPresentation()
    )
}
