package com.murong.agent.ui

import com.murong.agent.core.loop.RuntimeApprovalPostureDisplayMode

internal fun buildRuntimeApprovalPostureMessage(
    displayMode: RuntimeApprovalPostureDisplayMode,
    postureLabel: String,
    fullMessage: String
): String? {
    return when (displayMode) {
        RuntimeApprovalPostureDisplayMode.HIDE -> null
        RuntimeApprovalPostureDisplayMode.LABEL_ONLY -> "当前审批姿态：$postureLabel"
        RuntimeApprovalPostureDisplayMode.FULL_MESSAGE -> fullMessage
    }
}
