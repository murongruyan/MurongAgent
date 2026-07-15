package com.murong.agent.core.loop

enum class RuntimeStatusKind {
    IDLE,
    PENDING_APPROVAL,
    PENDING_ASK,
    PENDING_WORKFLOW_PLAN,
    PENDING_CLARIFICATION,
    AUTO_ROUTING,
    WORKFLOW_PLANNING,
    CLARIFICATION,
    EXECUTING,
    BACKGROUND_ACTIVITY
}

enum class RuntimeApprovalPostureDisplayMode {
    HIDE,
    LABEL_ONLY,
    FULL_MESSAGE
}

enum class BackgroundActivityFocusKind {
    RUNNING,
    SUMMARIZING,
    CANCELLING,
    PENDING_APPROVAL,
    QUEUED,
    OTHER
}

data class BackgroundActivityFocusTelemetry(
    val label: String,
    val summary: String
)

data class BackgroundActivityRuntimeSummary(
    val title: String,
    val message: String,
    val focusKind: BackgroundActivityFocusKind,
    val approvalPostureDisplayMode: RuntimeApprovalPostureDisplayMode,
    val focusTelemetry: BackgroundActivityFocusTelemetry
)

data class RuntimeStatusSnapshot(
    val kind: RuntimeStatusKind,
    val title: String,
    val message: String,
    val approvalPostureDisplayMode: RuntimeApprovalPostureDisplayMode = RuntimeApprovalPostureDisplayMode.FULL_MESSAGE,
    val backgroundActivityFocusKind: BackgroundActivityFocusKind? = null,
    val backgroundActivityFocusTelemetry: BackgroundActivityFocusTelemetry? = null
) {
    val showApprovalPosture: Boolean
        get() = approvalPostureDisplayMode != RuntimeApprovalPostureDisplayMode.HIDE
}

fun resolveRuntimeStatusSnapshot(state: SessionState): RuntimeStatusSnapshot? {
    state.pendingApproval?.let { approval ->
        return RuntimeStatusSnapshot(
            kind = RuntimeStatusKind.PENDING_APPROVAL,
            title = "等待审批",
            message = buildPendingApprovalStatusMessage(approval),
            approvalPostureDisplayMode = if (approval.isReplayOnly) {
                RuntimeApprovalPostureDisplayMode.HIDE
            } else {
                RuntimeApprovalPostureDisplayMode.LABEL_ONLY
            }
        )
    }
    state.pendingAskRequest?.let { request ->
        return RuntimeStatusSnapshot(
            kind = RuntimeStatusKind.PENDING_ASK,
            title = "等待回答",
            message = buildPendingAskStatusMessage(request),
            approvalPostureDisplayMode = if (request.isReplayOnly) {
                RuntimeApprovalPostureDisplayMode.HIDE
            } else {
                RuntimeApprovalPostureDisplayMode.FULL_MESSAGE
            }
        )
    }
    state.pendingWorkflowPlan?.let { plan ->
        return RuntimeStatusSnapshot(
            kind = RuntimeStatusKind.PENDING_WORKFLOW_PLAN,
            title = "等待计划确认",
            message = plan.goal.ifBlank { plan.summary }
        )
    }
    state.pendingClarificationRequest?.let { request ->
        return RuntimeStatusSnapshot(
            kind = RuntimeStatusKind.PENDING_CLARIFICATION,
            title = "等待澄清回答",
            message = request.question
        )
    }
    if (state.autoRoutingInProgress) {
        return RuntimeStatusSnapshot(
            kind = RuntimeStatusKind.AUTO_ROUTING,
            title = "自动分流",
            message = "正在判断本次输入更适合直接执行、先出计划，还是先澄清。"
        )
    }
    if (state.workflowPlanningInProgress) {
        return RuntimeStatusSnapshot(
            kind = RuntimeStatusKind.WORKFLOW_PLANNING,
            title = "执行计划",
            message = "正在生成执行计划，稍后可确认后一次性执行。"
        )
    }
    if (state.clarificationInProgress) {
        return RuntimeStatusSnapshot(
            kind = RuntimeStatusKind.CLARIFICATION,
            title = "澄清问题",
            message = "正在生成澄清问题，回答后会继续执行。"
        )
    }
    if (state.isProcessing) {
        return RuntimeStatusSnapshot(
            kind = RuntimeStatusKind.EXECUTING,
            title = "处理中",
            message = "当前正在执行任务、处理工具结果或整理回复。"
        )
    }
    val activeSubagentRuns = state.subagentRuns.filter { run ->
        run.status in ACTIVE_SUBAGENT_RUN_STATUSES
    }
    val activeSubagentBatches = state.subagentBatches.filter { batch ->
        batch.status in ACTIVE_SUBAGENT_BATCH_STATUSES
    }
    val activeBackgroundJobs = state.backgroundJobs.filter { job ->
        job.status in ACTIVE_BACKGROUND_JOB_STATUSES
    }
    if (activeBackgroundJobs.isNotEmpty() || activeSubagentRuns.isNotEmpty() || activeSubagentBatches.isNotEmpty()) {
        val backgroundSummary = resolveBackgroundActivityRuntimeSummary(
            activeJobs = activeBackgroundJobs,
            activeRuns = activeSubagentRuns,
            activeBatches = activeSubagentBatches
        )
        return RuntimeStatusSnapshot(
            kind = RuntimeStatusKind.BACKGROUND_ACTIVITY,
            title = backgroundSummary.title,
            message = backgroundSummary.message,
            approvalPostureDisplayMode = backgroundSummary.approvalPostureDisplayMode,
            backgroundActivityFocusKind = backgroundSummary.focusKind,
            backgroundActivityFocusTelemetry = backgroundSummary.focusTelemetry
        )
    }
    return null
}

private fun buildPendingApprovalStatusMessage(approval: PendingApprovalUi): String {
    val replayNotice = approval.replayNotice?.trim().orEmpty()
    if (approval.isReplayOnly && replayNotice.isNotBlank()) {
        return replayNotice
    }
    if (approval.isReplayOnly) {
        return REPLAY_ONLY_APPROVAL_NOTICE
    }
    val subject = approval.summary.trim().ifBlank { approval.detail.trim() }
    val reason = approval.explanationLabel?.trim().orEmpty()
    val detail = approval.explanationDetail?.trim().orEmpty()
    return buildString {
        when {
            reason.isNotBlank() && subject.isNotBlank() -> {
                append(reason)
                append("：")
                append(subject)
            }
            subject.isNotBlank() -> append(subject)
            detail.isNotBlank() -> append(detail)
        }
        if (detail.isNotBlank() && !endsWith(detail)) {
            if (isNotEmpty()) {
                append("。")
            }
            append(detail)
        }
    }.ifBlank {
        approval.summary.ifBlank { "当前有一个工具调用等待审批。" }
    }
}

private fun buildPendingAskStatusMessage(request: PendingAskRequestUi): String {
    val replayNotice = request.replayNotice?.trim().orEmpty()
    if (request.isReplayOnly && replayNotice.isNotBlank()) {
        return replayNotice
    }
    if (request.isReplayOnly) {
        return REPLAY_ONLY_ASK_NOTICE
    }
    return request.title
}

private fun buildSubagentBackgroundStatusMessage(
    activeRuns: List<SubagentRunUi>,
    activeBatches: List<SubagentBatchUi>
): String {
    val runStatusSummary = buildList {
        val pendingApprovalCount = activeRuns.count { it.status == "pending_approval" }
        if (pendingApprovalCount > 0) add("待审批 $pendingApprovalCount 个")
        val queuedCount = activeRuns.count { it.status == "queued" }
        if (queuedCount > 0) add("排队中 $queuedCount 个")
        val runningCount = activeRuns.count { it.status == "running" }
        if (runningCount > 0) add("运行中 $runningCount 个")
        val summarizingCount = activeRuns.count { it.status == "summarizing" }
        if (summarizingCount > 0) add("摘要整理 $summarizingCount 个")
        val cancellingCount = activeRuns.count { it.status == "cancelling" }
        if (cancellingCount > 0) add("终止中 $cancellingCount 个")
    }
    val batchStatusSummary = buildList {
        val pendingApprovalCount = activeBatches.count { it.status == "pending_approval" }
        if (pendingApprovalCount > 0) add("待审批批次 $pendingApprovalCount 个")
        val queuedCount = activeBatches.count { it.status == "queued" }
        if (queuedCount > 0) add("排队批次 $queuedCount 个")
        val runningCount = activeBatches.count { it.status == "running" }
        if (runningCount > 0) add("运行批次 $runningCount 个")
        val cancellingCount = activeBatches.count { it.status == "cancelling" }
        if (cancellingCount > 0) add("终止中批次 $cancellingCount 个")
    }
    val focusRun = activeRuns
        .sortedWith(compareBy(::subagentRunStatusPriority).thenBy { it.queuePosition ?: Int.MAX_VALUE })
        .firstOrNull()
    val focusBatch = activeBatches
        .sortedWith(compareBy(::subagentBatchStatusPriority).thenBy { it.queuePosition ?: Int.MAX_VALUE })
        .firstOrNull()
    return buildString {
        append("当前仍有后台子代理任务未终结")
        if (runStatusSummary.isNotEmpty()) {
            append("：")
            append(runStatusSummary.joinToString("，"))
        }
        if (batchStatusSummary.isNotEmpty()) {
            append("。")
            append(batchStatusSummary.joinToString("，"))
        }
        focusRun?.let { run ->
            val detail = run.statusMessage.trim().ifBlank { run.goal.trim() }
            if (detail.isNotBlank()) {
                append("。当前: ")
                val identity = buildFocusedSubagentRunIdentity(run)
                if (identity.isNotBlank()) {
                    append(identity)
                    append(" ")
                }
                append(detail)
            }
        } ?: focusBatch?.let { batch ->
            val identity = buildFocusedSubagentBatchIdentity(batch)
            if (identity.isNotBlank()) {
                append("。当前: ")
                append(identity)
            }
        }
        append("。")
    }
}

private fun buildBackgroundJobsStatusMessage(
    activeJobs: List<BackgroundJobUi>,
    activeSubagentRuns: List<SubagentRunUi>,
    activeSubagentBatches: List<SubagentBatchUi>
): String {
    val queuedCount = activeJobs.count { it.status == "queued" }
    val runningCount = activeJobs.count { it.status == "running" }
    val focusJob = activeJobs
        .sortedWith(compareBy(::backgroundJobStatusPriority).thenBy { it.queuePosition ?: Int.MAX_VALUE })
        .firstOrNull()
    return buildString {
        append("当前仍有会话级后台任务未终结")
        val statusSummary = buildList {
            if (runningCount > 0) add("运行中 $runningCount 个")
            if (queuedCount > 0) add("排队中 $queuedCount 个")
        }
        if (statusSummary.isNotEmpty()) {
            append("：")
            append(statusSummary.joinToString("，"))
        }
        focusJob?.let { job ->
            val focusDetail = job.statusMessage.trim().ifBlank { job.summary.trim() }
            if (focusDetail.isNotBlank()) {
                append("。当前: ")
                append(job.title.trim().ifBlank { "后台任务" })
                job.summary.trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { summary ->
                        append(" · ")
                        append(truncateRuntimeStatusText(summary, 30))
                    }
                append(" ")
                append(focusDetail)
            }
        }
        if (activeSubagentRuns.isNotEmpty() || activeSubagentBatches.isNotEmpty()) {
            append("。")
            append(
                buildSubagentBackgroundCompanionMessage(
                    activeRuns = activeSubagentRuns,
                    activeBatches = activeSubagentBatches
                )
            )
        }
        append("。")
    }
}

private fun buildUnifiedBackgroundStatusMessage(
    activeJobs: List<BackgroundJobUi>,
    activeRuns: List<SubagentRunUi>,
    activeBatches: List<SubagentBatchUi>
): String {
    return when {
        activeJobs.isNotEmpty() && (activeRuns.isNotEmpty() || activeBatches.isNotEmpty()) -> {
            buildString {
                append(
                    buildBackgroundJobsStatusMessage(
                        activeJobs = activeJobs,
                        activeSubagentRuns = emptyList(),
                        activeSubagentBatches = emptyList()
                    ).removeSuffix("。")
                )
                append("。")
                append(
                    buildSubagentBackgroundStatusMessage(
                        activeRuns = activeRuns,
                        activeBatches = activeBatches
                    )
                )
            }
        }
        activeJobs.isNotEmpty() -> {
            buildBackgroundJobsStatusMessage(
                activeJobs = activeJobs,
                activeSubagentRuns = emptyList(),
                activeSubagentBatches = emptyList()
            )
        }
        else -> {
            buildSubagentBackgroundStatusMessage(
                activeRuns = activeRuns,
                activeBatches = activeBatches
            )
        }
    }
}

private fun buildFocusedSubagentRunIdentity(run: SubagentRunUi): String {
    return buildList {
        run.batchLabel?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { label ->
                val indexText = if (run.batchIndex != null && run.batchSize != null) {
                    "（${run.batchIndex}/${run.batchSize}）"
                } else {
                    ""
                }
                add("[${truncateRuntimeStatusText(label, 18)}$indexText]")
            }
        run.goal.trim()
            .takeIf { it.isNotBlank() }
            ?.let { goal -> add(truncateRuntimeStatusText(goal, 30)) }
    }.joinToString(" ")
}

private fun buildFocusedSubagentBatchIdentity(batch: SubagentBatchUi): String {
    return buildList {
        batch.label.trim()
            .takeIf { it.isNotBlank() }
            ?.let { label -> add("批次 `${truncateRuntimeStatusText(label, 18)}`") }
        batch.parentGoal.trim()
            .takeIf { it.isNotBlank() }
            ?.let { goal -> add(truncateRuntimeStatusText(goal, 30)) }
    }.joinToString(" ")
}

private fun truncateRuntimeStatusText(text: String, maxLength: Int): String {
    val normalized = text.trim().replace(Regex("""\s+"""), " ")
    if (normalized.length <= maxLength) return normalized
    return normalized.take((maxLength - 3).coerceAtLeast(1)).trimEnd() + "..."
}

private fun resolveSubagentBackgroundTitle(
    activeRuns: List<SubagentRunUi>,
    activeBatches: List<SubagentBatchUi>
): String {
    val focusRun = activeRuns
        .sortedWith(compareBy(::subagentRunStatusPriority).thenBy { it.queuePosition ?: Int.MAX_VALUE })
        .firstOrNull()
    val focusStatus = focusRun?.status ?: activeBatches
        .sortedWith(compareBy(::subagentBatchStatusPriority).thenBy { it.queuePosition ?: Int.MAX_VALUE })
        .firstOrNull()
        ?.status
    return when (focusStatus) {
        "running" -> "子代理运行中"
        "summarizing" -> "子代理整理中"
        "cancelling" -> "子代理终止中"
        "queued" -> "子代理排队中"
        "pending_approval" -> "子代理待审批"
        else -> "子代理后台"
    }
}

private fun resolveBackgroundJobsTitle(activeJobs: List<BackgroundJobUi>): String {
    val focusStatus = activeJobs
        .sortedWith(compareBy(::backgroundJobStatusPriority).thenBy { it.queuePosition ?: Int.MAX_VALUE })
        .firstOrNull()
        ?.status
    return when (focusStatus) {
        "running" -> "后台任务运行中"
        "queued" -> "后台任务排队中"
        else -> "后台任务"
    }
}

private fun resolveUnifiedBackgroundTitle(
    activeJobs: List<BackgroundJobUi>,
    activeRuns: List<SubagentRunUi>,
    activeBatches: List<SubagentBatchUi>
): String {
    return backgroundActivityTitle(resolveBackgroundActivityFocusKind(activeJobs, activeRuns, activeBatches))
}

internal fun resolveBackgroundActivityRuntimeSummary(
    activeJobs: List<BackgroundJobUi>,
    activeRuns: List<SubagentRunUi>,
    activeBatches: List<SubagentBatchUi>
): BackgroundActivityRuntimeSummary {
    val focusKind = resolveBackgroundActivityFocusKind(
        activeJobs = activeJobs,
        activeRuns = activeRuns,
        activeBatches = activeBatches
    )
    val message = buildUnifiedBackgroundStatusMessage(
        activeJobs = activeJobs,
        activeRuns = activeRuns,
        activeBatches = activeBatches
    )
    return BackgroundActivityRuntimeSummary(
        title = backgroundActivityTitle(focusKind),
        message = message,
        focusKind = focusKind,
        approvalPostureDisplayMode = if (focusKind == BackgroundActivityFocusKind.PENDING_APPROVAL) {
            RuntimeApprovalPostureDisplayMode.LABEL_ONLY
        } else {
            RuntimeApprovalPostureDisplayMode.FULL_MESSAGE
        },
        focusTelemetry = BackgroundActivityFocusTelemetry(
            label = backgroundActivityFocusLabel(focusKind),
            summary = message
        )
    )
}

private fun resolveBackgroundActivityFocusKind(
    activeJobs: List<BackgroundJobUi>,
    activeRuns: List<SubagentRunUi>,
    activeBatches: List<SubagentBatchUi>
): BackgroundActivityFocusKind {
    val focusJobStatus = activeJobs
        .sortedWith(compareBy(::backgroundJobStatusPriority).thenBy { it.queuePosition ?: Int.MAX_VALUE })
        .firstOrNull()
        ?.status
    val focusSubagentStatus = activeRuns
        .sortedWith(compareBy(::subagentRunStatusPriority).thenBy { it.queuePosition ?: Int.MAX_VALUE })
        .firstOrNull()
        ?.status ?: activeBatches
        .sortedWith(compareBy(::subagentBatchStatusPriority).thenBy { it.queuePosition ?: Int.MAX_VALUE })
        .firstOrNull()
        ?.status
    return when {
        focusJobStatus == "running" || focusSubagentStatus == "running" -> BackgroundActivityFocusKind.RUNNING
        focusSubagentStatus == "summarizing" -> BackgroundActivityFocusKind.SUMMARIZING
        focusSubagentStatus == "cancelling" -> BackgroundActivityFocusKind.CANCELLING
        focusSubagentStatus == "pending_approval" -> BackgroundActivityFocusKind.PENDING_APPROVAL
        focusJobStatus == "queued" || focusSubagentStatus == "queued" -> BackgroundActivityFocusKind.QUEUED
        else -> BackgroundActivityFocusKind.OTHER
    }
}

private fun backgroundActivityTitle(focusKind: BackgroundActivityFocusKind): String {
    return when (focusKind) {
        BackgroundActivityFocusKind.RUNNING -> "后台任务运行中"
        BackgroundActivityFocusKind.SUMMARIZING -> "后台任务整理中"
        BackgroundActivityFocusKind.CANCELLING -> "后台任务终止中"
        BackgroundActivityFocusKind.PENDING_APPROVAL -> "后台任务待审批"
        BackgroundActivityFocusKind.QUEUED -> "后台任务排队中"
        BackgroundActivityFocusKind.OTHER -> "后台任务"
    }
}

private fun backgroundActivityFocusLabel(focusKind: BackgroundActivityFocusKind): String {
    return when (focusKind) {
        BackgroundActivityFocusKind.RUNNING -> "后台运行"
        BackgroundActivityFocusKind.SUMMARIZING -> "后台整理"
        BackgroundActivityFocusKind.CANCELLING -> "后台终止"
        BackgroundActivityFocusKind.PENDING_APPROVAL -> "后台待审批"
        BackgroundActivityFocusKind.QUEUED -> "后台排队"
        BackgroundActivityFocusKind.OTHER -> "后台任务"
    }
}

private fun buildSubagentBackgroundCompanionMessage(
    activeRuns: List<SubagentRunUi>,
    activeBatches: List<SubagentBatchUi>
): String {
    val parts = buildList {
        val runPendingApprovalCount = activeRuns.count { it.status == "pending_approval" }
        if (runPendingApprovalCount > 0) add("后台子代理待审批 $runPendingApprovalCount 个")
        val runQueuedCount = activeRuns.count { it.status == "queued" }
        if (runQueuedCount > 0) add("后台子代理排队中 $runQueuedCount 个")
        val runRunningCount = activeRuns.count { it.status == "running" }
        if (runRunningCount > 0) add("后台子代理运行中 $runRunningCount 个")
        val runSummarizingCount = activeRuns.count { it.status == "summarizing" }
        if (runSummarizingCount > 0) add("后台子代理整理中 $runSummarizingCount 个")
        val runCancellingCount = activeRuns.count { it.status == "cancelling" }
        if (runCancellingCount > 0) add("后台子代理终止中 $runCancellingCount 个")

        val batchPendingApprovalCount = activeBatches.count { it.status == "pending_approval" }
        if (batchPendingApprovalCount > 0) add("子代理批次待审批 $batchPendingApprovalCount 个")
        val batchQueuedCount = activeBatches.count { it.status == "queued" }
        if (batchQueuedCount > 0) add("子代理批次排队中 $batchQueuedCount 个")
        val batchRunningCount = activeBatches.count { it.status == "running" }
        if (batchRunningCount > 0) add("子代理批次运行中 $batchRunningCount 个")
        val batchCancellingCount = activeBatches.count { it.status == "cancelling" }
        if (batchCancellingCount > 0) add("子代理批次终止中 $batchCancellingCount 个")
    }
    return parts.joinToString("，").ifBlank { "另有后台子代理任务未终结" }
}

private fun subagentRunStatusPriority(run: SubagentRunUi): Int {
    return when (run.status) {
        "running" -> 0
        "summarizing" -> 1
        "cancelling" -> 2
        "queued" -> 3
        "pending_approval" -> 4
        else -> 5
    }
}

private fun subagentBatchStatusPriority(batch: SubagentBatchUi): Int {
    return when (batch.status) {
        "running" -> 0
        "cancelling" -> 1
        "queued" -> 2
        "pending_approval" -> 3
        else -> 4
    }
}

private fun backgroundJobStatusPriority(job: BackgroundJobUi): Int {
    return when (job.status) {
        "running" -> 0
        "queued" -> 1
        else -> 2
    }
}

private val ACTIVE_SUBAGENT_RUN_STATUSES = setOf(
    "pending_approval",
    "queued",
    "running",
    "summarizing",
    "cancelling"
)

private val ACTIVE_SUBAGENT_BATCH_STATUSES = setOf(
    "pending_approval",
    "queued",
    "running",
    "cancelling"
)
