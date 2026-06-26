package com.murong.agent.core.loop

internal enum class SessionLifecycleAction {
    NEW_SESSION,
    LOAD_SESSION,
    START_TASK,
    DELETE_SESSION
}

private val SESSION_LIFECYCLE_ACTIVE_SUBAGENT_STATUSES = setOf(
    "pending_approval",
    "queued",
    "running",
    "summarizing",
    "cancelling"
)

internal fun buildSessionLifecycleGuardNotice(
    state: SessionState,
    action: SessionLifecycleAction
): String? {
    val actionLabel = when (action) {
        SessionLifecycleAction.NEW_SESSION -> "新建会话"
        SessionLifecycleAction.LOAD_SESSION -> "切换会话"
        SessionLifecycleAction.START_TASK -> "开始任务"
        SessionLifecycleAction.DELETE_SESSION -> "删除会话"
    }
    if (state.isProcessing) {
        return buildSessionLifecycleHistoryAwareNotice(
            notice = "当前会话仍在处理中，暂时不能$actionLabel。请先等待当前执行完成。",
            recentSessionHistoryClue = state.recentSessionHistoryClue
        )
    }
    if (hasBlockingPendingPrompt(state)) {
        return buildSessionLifecycleHistoryAwareNotice(
            notice = "当前会话仍有待处理的交互卡片，暂时不能$actionLabel。请先处理审批、提问或工作流确认。",
            recentSessionHistoryClue = state.recentSessionHistoryClue
        )
    }
    if (hasActiveSubagentWork(state)) {
        return buildSessionLifecycleHistoryAwareNotice(
            notice = "当前会话仍有活跃子代理执行，暂时不能$actionLabel。请先等待子代理完成或中断。",
            recentSessionHistoryClue = state.recentSessionHistoryClue
        )
    }
    if (hasActiveBackgroundJobWork(state)) {
        return buildSessionLifecycleHistoryAwareNotice(
            notice = "当前会话仍有后台任务在执行，暂时不能$actionLabel。请先等待后台任务完成。",
            recentSessionHistoryClue = state.recentSessionHistoryClue
        )
    }
    return null
}

private fun buildSessionLifecycleHistoryAwareNotice(
    notice: String,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi?
): String {
    val historySuffix = buildSessionHistoryReferenceInlineSuffix(recentSessionHistoryClue)
    return historySuffix?.let { "$notice（$it）" } ?: notice
}

internal fun buildSessionDeletionNotice(
    deletedCurrentSession: Boolean,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null
): String {
    val notice = if (deletedCurrentSession) {
        "当前会话已删除，已切回新对话。"
    } else {
        "会话已删除，列表已同步更新。"
    }
    val historySuffix = buildSessionHistoryReferenceInlineSuffix(recentSessionHistoryClue)
    return historySuffix?.let { "$notice（$it）" } ?: notice
}

internal fun hasBlockingPendingPrompt(state: SessionState): Boolean {
    if (state.pendingApproval?.isReplayOnly != true && state.pendingApproval != null) {
        return true
    }
    if (state.pendingAskRequest?.isReplayOnly != true && state.pendingAskRequest != null) {
        return true
    }
    return state.pendingWorkflowPlan != null || state.pendingClarificationRequest != null
}

internal fun hasActiveSubagentWork(state: SessionState): Boolean {
    return state.subagentRuns.any { it.status in SESSION_LIFECYCLE_ACTIVE_SUBAGENT_STATUSES } ||
        state.subagentBatches.any { it.status in SESSION_LIFECYCLE_ACTIVE_SUBAGENT_STATUSES }
}
