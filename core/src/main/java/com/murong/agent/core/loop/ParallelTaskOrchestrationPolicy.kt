package com.murong.agent.core.loop

internal data class ActiveParallelBatchSnapshot(
    val activeBatchCount: Int,
    val totalRunCount: Int,
    val completedRunCount: Int,
    val failedRunCount: Int,
    val queuedRunCount: Int,
    val runningRunCount: Int,
    val labels: List<String>
)

internal data class SettledParallelBatchEvidenceSnapshot(
    val settledBatchCount: Int,
    val failedBatchCount: Int,
    val latestLabel: String? = null,
    val latestStatus: String = "",
    val latestSummaryPreview: String? = null
)

private val PARALLEL_BATCH_TERMINAL_STATUSES = setOf("completed", "completed_with_failures", "failed", "rejected")
private const val PARALLEL_BATCH_BLOCKED_HINT_FALLBACK =
    "先等待活跃的并行子任务批次全部收敛，再继续签收当前计划步骤。"

internal fun resolveActiveParallelBatchSnapshot(state: SessionState): ActiveParallelBatchSnapshot? {
    val activeBatches = state.subagentBatches
        .filter { batch ->
            batch.runIds.size > 1 && batch.status !in PARALLEL_BATCH_TERMINAL_STATUSES
        }
    if (activeBatches.isEmpty()) return null
    return ActiveParallelBatchSnapshot(
        activeBatchCount = activeBatches.size,
        totalRunCount = activeBatches.sumOf { batch -> maxOf(batch.runIds.size, 1) },
        completedRunCount = activeBatches.sumOf { batch ->
            val totalRuns = maxOf(batch.runIds.size, 1)
            (totalRuns - batch.queuedRuns - batch.runningRuns).coerceAtLeast(0)
        },
        failedRunCount = activeBatches.count { batch ->
            batch.status == "failed" || batch.status == "completed_with_failures"
        },
        queuedRunCount = activeBatches.sumOf(SubagentBatchUi::queuedRuns),
        runningRunCount = activeBatches.sumOf(SubagentBatchUi::runningRuns),
        labels = activeBatches.mapNotNull { batch ->
            batch.label.trim().takeIf { it.isNotBlank() }
        }
    )
}

internal fun buildActiveParallelBatchExecutionHint(state: SessionState): String? {
    val snapshot = resolveActiveParallelBatchSnapshot(state) ?: return null
    return buildString {
        append("仍有 ")
        append(snapshot.activeBatchCount)
        append(" 个并行子任务批次处于活跃状态")
        if (snapshot.runningRunCount > 0 || snapshot.queuedRunCount > 0) {
            append("（运行中 ")
            append(snapshot.runningRunCount)
            append("，排队中 ")
            append(snapshot.queuedRunCount)
            append("）")
        }
        append("；先等待这些子任务全部收敛并汇总证据，再继续签收当前计划步骤。")
    }
}

internal fun buildParallelBatchBlockedHintFallback(): String = PARALLEL_BATCH_BLOCKED_HINT_FALLBACK

internal fun isParallelBatchWorkflowBlockedHint(hint: String): Boolean {
    val normalized = hint.trim()
    return normalized == PARALLEL_BATCH_BLOCKED_HINT_FALLBACK ||
        (normalized.startsWith("仍有 ") &&
            normalized.contains("并行子任务批次处于活跃状态") &&
            normalized.contains("继续签收当前计划步骤"))
}

internal fun resolveSettledParallelBatchEvidenceSnapshot(
    state: SessionState
): SettledParallelBatchEvidenceSnapshot? {
    val terminalBatches = state.subagentBatches
        .asSequence()
        .filter { batch ->
            batch.runIds.size > 1 &&
                batch.status in PARALLEL_BATCH_TERMINAL_STATUSES &&
                batch.statusMessage != RESTORED_SUBAGENT_BATCH_INTERRUPTED_MESSAGE
        }
        .sortedByDescending { batch ->
            batch.lastRunFinishedAt
                ?: batch.finishedAt
                ?: batch.firstRunStartedAt
                ?: batch.queuedAt
                ?: batch.approvalRequestedAt
                ?: 0L
        }
        .toList()
    val latestBatch = terminalBatches.firstOrNull() ?: return null
    return SettledParallelBatchEvidenceSnapshot(
        settledBatchCount = terminalBatches.size,
        failedBatchCount = terminalBatches.count { batch ->
            batch.status == "failed" || batch.status == "completed_with_failures"
        },
        latestLabel = latestBatch.label.trim().takeIf { it.isNotBlank() },
        latestStatus = latestBatch.status,
        latestSummaryPreview = latestBatch.summary
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(160)
    )
}

internal fun buildParallelBatchResumeExecutionHint(
    plan: WorkflowPlanUi,
    state: SessionState
): String {
    val nextStep = plan.steps.getOrNull(plan.currentStepIndex)?.trim().orEmpty()
    val evidence = resolveSettledParallelBatchEvidenceSnapshot(state)
    val base = if (nextStep.isNotBlank()) {
        "并行子任务批次已全部收敛，请先汇总子任务结果与真实工具证据，再继续当前计划步骤：$nextStep。"
    } else {
        "并行子任务批次已全部收敛，请先汇总子任务结果与真实工具证据，再决定是否调用 `complete_step`。"
    }
    evidence ?: return base
    return buildString {
        append("并行子任务批次已全部收敛")
        evidence.latestLabel?.let { label ->
            append("；最近批次：")
            append(label)
        }
        if (evidence.latestStatus.isNotBlank()) {
            append("（状态：")
            append(evidence.latestStatus)
            append("）")
        }
        append("。")
        evidence.latestSummaryPreview?.let { preview ->
            append(" 批次结论：")
            append(preview.trimEnd('。', '！', '？', '.', ';', '；'))
            append("。")
        }
        append(
            if (evidence.failedBatchCount > 0) {
                " 请先判断是否需要补救或重试未成功子任务，再结合现有证据推进当前计划。"
            } else {
                " 请先把这些子任务结果整理成可引用证据，再推进当前计划。"
            }
        )
        if (nextStep.isNotBlank()) {
            append(" 当前步骤：")
            append(nextStep)
            append("。")
        } else {
            append(" 再决定是否调用 `complete_step`。")
        }
    }
}

internal fun buildSettledParallelBatchExecutionHint(state: SessionState): String? {
    val evidence = resolveSettledParallelBatchEvidenceSnapshot(state) ?: return null
    return buildString {
        append("最近并行子任务批次已收敛")
        evidence.latestLabel?.let { label ->
            append("：")
            append(label)
        }
        if (evidence.latestStatus.isNotBlank()) {
            append("（")
            append(evidence.latestStatus)
            append("）")
        }
        append("。")
        evidence.latestSummaryPreview?.let { preview ->
            append(" 批次结论：")
            append(preview.trimEnd('。', '！', '？', '.', ';', '；'))
            append("。")
        }
        append(
            if (evidence.failedBatchCount > 0) {
                " 在调用 `complete_step` 前，先确认未成功子任务是否需要补救、重试或显式接受。"
            } else {
                " 在调用 `complete_step` 前，先把这些并行结果整理成可引用证据。"
            }
        )
    }
}
