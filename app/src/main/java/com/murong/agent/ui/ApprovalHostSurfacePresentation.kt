package com.murong.agent.ui

internal enum class ApprovalHostSurfaceKind {
    NONE,
    DIALOG,
    TOOLS_DETAIL
}

internal data class ApprovalHostSurfacePresentation(
    val kind: ApprovalHostSurfaceKind,
    val pendingApproval: PendingApprovalPresentation? = null
)

internal fun buildApprovalHostSurfacePresentation(
    pendingApproval: PendingApprovalPresentation?,
    isToolsScreenVisible: Boolean,
    showToolsApprovalDetail: Boolean
): ApprovalHostSurfacePresentation {
    if (pendingApproval == null) {
        return ApprovalHostSurfacePresentation(
            kind = ApprovalHostSurfaceKind.NONE
        )
    }
    if (!isToolsScreenVisible) {
        return ApprovalHostSurfacePresentation(
            kind = ApprovalHostSurfaceKind.DIALOG,
            pendingApproval = pendingApproval
        )
    }
    if (showToolsApprovalDetail) {
        return ApprovalHostSurfacePresentation(
            kind = ApprovalHostSurfaceKind.TOOLS_DETAIL,
            pendingApproval = pendingApproval
        )
    }
    return ApprovalHostSurfacePresentation(
        kind = ApprovalHostSurfaceKind.NONE,
        pendingApproval = pendingApproval
    )
}
