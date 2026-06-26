package com.murong.agent.ui

import com.murong.agent.core.loop.BackgroundActivityFocusKind

internal fun backgroundActivityFocusLabel(
    focusKind: BackgroundActivityFocusKind?,
    fallbackTitle: String? = null
): String? {
    return when (focusKind) {
        BackgroundActivityFocusKind.PENDING_APPROVAL -> "后台待审批"
        BackgroundActivityFocusKind.QUEUED -> "后台排队"
        BackgroundActivityFocusKind.RUNNING -> "后台运行"
        BackgroundActivityFocusKind.SUMMARIZING -> "后台整理"
        BackgroundActivityFocusKind.CANCELLING -> "后台终止"
        BackgroundActivityFocusKind.OTHER,
        null -> fallbackTitle
    }
}
