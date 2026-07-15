package com.murong.agent.core.loop

internal enum class PendingPromptKind {
    APPROVAL,
    ASK
}

internal data class PromptSerializationDecision(
    val blocked: Boolean,
    val label: String? = null,
    val detail: String? = null
)

internal fun resolvePromptSerializationDecision(
    requestedKind: PendingPromptKind,
    hasPendingApproval: Boolean,
    hasPendingAsk: Boolean
): PromptSerializationDecision {
    if (hasPendingApproval) {
        return PromptSerializationDecision(
            blocked = true,
            label = "待审批卡片占用中",
            detail = buildString {
                append("当前已经有一个审批请求等待用户处理，")
                append(if (requestedKind == PendingPromptKind.ASK) "ask_user" else "新的审批请求")
                append("不能并行弹出。请先完成当前审批，再继续后续交互。")
            }
        )
    }
    if (hasPendingAsk) {
        return PromptSerializationDecision(
            blocked = true,
            label = "待提问卡片占用中",
            detail = buildString {
                append("当前已经有一个 ask_user 提问卡片等待用户处理，")
                append(if (requestedKind == PendingPromptKind.APPROVAL) "新的审批请求" else "新的提问卡片")
                append("不能并行弹出。请先完成当前提问，再继续后续交互。")
            }
        )
    }
    return PromptSerializationDecision(blocked = false)
}
