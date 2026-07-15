package com.murong.agent.ui

import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.core.config.approvalModeDescription
import com.murong.agent.core.config.approvalModeLabel
import com.murong.agent.core.config.resolveApprovalModePresentation

internal data class ApprovalModeHostPresentation(
    val label: String,
    val message: String,
    val shortcutLabel: String
)

internal data class ApprovalModeOptionPresentation(
    val mode: ToolApprovalMode?,
    val title: String,
    val subtitle: String
)

internal fun buildApprovalModeHostPresentation(
    globalMode: ToolApprovalMode,
    overrideMode: ToolApprovalMode?
): ApprovalModeHostPresentation {
    val resolvedPresentation = resolveApprovalModePresentation(
        globalMode = globalMode,
        overrideMode = overrideMode
    )
    return ApprovalModeHostPresentation(
        label = resolvedPresentation.labelWithSource,
        message = resolvedPresentation.runtimeMessage,
        shortcutLabel = resolvedPresentation.shortcutLabel
    )
}

internal fun buildApprovalModeOptionPresentation(
    mode: ToolApprovalMode
): ApprovalModeOptionPresentation {
    return ApprovalModeOptionPresentation(
        mode = mode,
        title = mode.approvalModeLabel(),
        subtitle = mode.approvalModeDescription()
    )
}

internal fun buildApprovalModeOptionPresentations(): List<ApprovalModeOptionPresentation> {
    return ToolApprovalMode.entries.map(::buildApprovalModeOptionPresentation)
}

internal fun buildApprovalModeFollowGlobalOptionPresentation(
    globalMode: ToolApprovalMode
): ApprovalModeOptionPresentation {
    return ApprovalModeOptionPresentation(
        mode = null,
        title = "跟随全局",
        subtitle = "当前全局：${globalMode.approvalModeLabel()}"
    )
}
