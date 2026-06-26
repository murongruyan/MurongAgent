package com.murong.agent.core.loop

const val REPLAY_ONLY_APPROVAL_NOTICE =
    "这是从已恢复会话中重放的审批请求。原始执行现场已经结束，当前只能查看或关闭，不能继续原调用。"
const val REPLAY_ONLY_ASK_NOTICE =
    "这是从已恢复会话中重放的提问卡片。原始等待链路已经结束，当前只能查看或关闭，不能继续原提问。"
internal const val RESTORED_SUBAGENT_INTERRUPTED_MESSAGE =
    "会话恢复后，原子代理执行现场已丢失，已标记为中断。"
internal const val RESTORED_SUBAGENT_BATCH_INTERRUPTED_MESSAGE =
    "会话恢复后，原子代理批次执行现场已丢失，已标记为中断。"

private val RESTORABLE_SUBAGENT_ACTIVE_STATUSES = setOf(
    "pending_approval",
    "queued",
    "running",
    "summarizing",
    "cancelling"
)

internal fun markPendingApprovalReplayOnly(pending: PendingApprovalUi?): PendingApprovalUi? {
    return markPendingApprovalReplayOnly(pending, recentSessionHistoryClue = null)
}

internal fun markPendingApprovalReplayOnly(
    pending: PendingApprovalUi?,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi?
): PendingApprovalUi? {
    return pending?.copy(
        isReplayOnly = true,
        replayNotice = buildReplayOnlyPromptNotice(
            baseNotice = REPLAY_ONLY_APPROVAL_NOTICE,
            recentSessionHistoryClue = recentSessionHistoryClue
        )
    )
}

internal fun markPendingAskReplayOnly(request: PendingAskRequestUi?): PendingAskRequestUi? {
    return markPendingAskReplayOnly(request, recentSessionHistoryClue = null)
}

internal fun markPendingAskReplayOnly(
    request: PendingAskRequestUi?,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi?
): PendingAskRequestUi? {
    return request?.copy(
        isReplayOnly = true,
        replayNotice = buildReplayOnlyPromptNotice(
            baseNotice = REPLAY_ONLY_ASK_NOTICE,
            recentSessionHistoryClue = recentSessionHistoryClue
        )
    )
}

private fun buildReplayOnlyPromptNotice(
    baseNotice: String,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi?
): String {
    val historySuffix = buildSessionHistoryReferenceInlineSuffix(recentSessionHistoryClue)
    return historySuffix?.let { "$baseNotice（$it）" } ?: baseNotice
}

internal fun normalizeRestoredSubagentRun(run: SubagentRunUi, restoredAt: Long): SubagentRunUi {
    if (run.status !in RESTORABLE_SUBAGENT_ACTIVE_STATUSES) return run
    return run.copy(
        status = "cancelled",
        statusMessage = RESTORED_SUBAGENT_INTERRUPTED_MESSAGE,
        queuePosition = null,
        assignedSlot = null,
        finishedAt = run.finishedAt ?: restoredAt
    )
}

internal fun normalizeRestoredSubagentBatch(batch: SubagentBatchUi, restoredAt: Long): SubagentBatchUi {
    if (batch.status !in RESTORABLE_SUBAGENT_ACTIVE_STATUSES) return batch
    return batch.copy(
        status = "failed",
        statusMessage = RESTORED_SUBAGENT_BATCH_INTERRUPTED_MESSAGE,
        queuePosition = null,
        activeSlots = emptyList(),
        queuedRuns = 0,
        runningRuns = 0,
        finishedAt = batch.finishedAt ?: restoredAt
    )
}

fun buildSessionRestoreReplayNotice(state: SessionState): String? {
    val restoredItems = buildList {
        if (state.pendingApproval?.isReplayOnly == true) {
            add("审批请求")
        }
        if (state.pendingAskRequest?.isReplayOnly == true) {
            add("提问卡片")
        }
    }
    val interruptedRunCount = state.subagentRuns.count { run ->
        run.status == "cancelled" && run.statusMessage == RESTORED_SUBAGENT_INTERRUPTED_MESSAGE
    }
    val interruptedBatchCount = state.subagentBatches.count { batch ->
        batch.status == "failed" && batch.statusMessage == RESTORED_SUBAGENT_BATCH_INTERRUPTED_MESSAGE
    }
    val interruptedBackgroundJobCount = state.backgroundJobs.count { job ->
        job.status == "interrupted" && job.statusMessage == RESTORED_BACKGROUND_JOB_INTERRUPTED_MESSAGE
    }
    val latestFinalReadinessAuditSummary = buildLatestFinalReadinessAuditSummary(
        state.recentFinalReadinessAudits
    )
    val latestFinalReadinessHistoryReferenceSummary = buildSessionHistoryReferenceContext(
        mergeSessionHistoryReferenceClues(
            buildLatestFinalReadinessHistoryReferenceClue(state.recentFinalReadinessAudits),
            state.recentSessionHistoryClue
        )
    )
    if (
        restoredItems.isEmpty() &&
        interruptedRunCount == 0 &&
        interruptedBatchCount == 0 &&
        interruptedBackgroundJobCount == 0 &&
        latestFinalReadinessAuditSummary == null &&
        latestFinalReadinessHistoryReferenceSummary == null
    ) {
        return null
    }
    return buildString {
        if (restoredItems.isNotEmpty()) {
            append("已恢复会话中的")
            append(restoredItems.joinToString("和"))
            append("；原始等待链路不可继续。")
        }
        if (interruptedRunCount > 0) {
            if (isNotEmpty()) append(" ")
            append(
                if (interruptedRunCount == 1) {
                    "1 个子代理执行已标记为中断。"
                } else {
                    "$interruptedRunCount 个子代理执行已标记为中断。"
                }
            )
        }
        if (interruptedBatchCount > 0) {
            if (isNotEmpty()) append(" ")
            append(
                if (interruptedBatchCount == 1) {
                    "1 个子代理批次已标记为中断。"
                } else {
                    "$interruptedBatchCount 个子代理批次已标记为中断。"
                }
            )
        }
        if (interruptedBackgroundJobCount > 0) {
            if (isNotEmpty()) append(" ")
            append(
                if (interruptedBackgroundJobCount == 1) {
                    "1 个后台任务已标记为中断。"
                } else {
                    "$interruptedBackgroundJobCount 个后台任务已标记为中断。"
                }
            )
        }
        latestFinalReadinessAuditSummary?.let { auditSummary ->
            if (isNotEmpty()) append(" ")
            append("最近一次最终收口状态：")
            append(auditSummary)
            append("。")
        }
        latestFinalReadinessHistoryReferenceSummary?.let { historySummary ->
            if (isNotEmpty()) append(" ")
            append(historySummary)
        }
    }
}
