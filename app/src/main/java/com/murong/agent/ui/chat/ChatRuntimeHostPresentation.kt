package com.murong.agent.ui.chat

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.loop.ArchivedMemoryCandidate
import com.murong.agent.core.loop.ArchivedMemoryCandidateScope
import com.murong.agent.core.loop.ApprovalRuntimeTelemetry
import com.murong.agent.core.loop.RuntimeStatusKind
import com.murong.agent.core.loop.RuntimeStatusSnapshot
import com.murong.agent.ui.ApprovalRuntimePosturePresentation
import com.murong.agent.ui.approvalPostureSectionTitle
import com.murong.agent.ui.backgroundActivityFocusLabel
import com.murong.agent.ui.buildApprovalRuntimePosturePresentation
import com.murong.agent.ui.buildRuntimeApprovalPostureMessage

internal data class TopStatusStripPresentation(
    val title: String,
    val message: String,
    val badge: String? = null,
    val compact: Boolean = false
)

internal data class ChatRecentHistorySurfacePresentation(
    val title: String,
    val message: String,
    val detailTitle: String,
    val detailSubtitle: String,
    val detailRows: List<RecentHistoryClueDetailPresentation>
)

internal data class ChatArchivedMemorySurfacePresentation(
    val title: String,
    val message: String,
    val detailTitle: String,
    val detailSubtitle: String,
    val totalCandidateCount: Int,
    val batchCandidates: List<ChatArchivedMemoryCandidateBatchTargetPresentation>,
    val candidates: List<ChatArchivedMemoryCandidateItemPresentation>
)

internal data class ChatArchivedMemoryCandidateBatchTargetPresentation(
    val sessionId: String,
    val suggestedScope: ArchivedMemoryCandidateScope,
    val suggestedTitle: String
)

internal data class ChatArchivedMemoryCandidateItemPresentation(
    val sessionId: String,
    val sourceSessionTitle: String,
    val suggestedScope: ArchivedMemoryCandidateScope,
    val suggestedTitle: String,
    val suggestedContentPreview: String,
    val sourceAnchorMessageReference: String? = null,
    val sourceFinalReadinessSummary: String? = null
)

internal data class ChatRuntimeHostPresentation(
    val approvalRuntimePosturePresentation: ApprovalRuntimePosturePresentation,
    val pendingPromptRuntimePresentation: PendingPromptRuntimePresentation?,
    val pendingPromptHostPresentation: PendingPromptHostPresentation = PendingPromptHostPresentation(),
    val recentHistorySurfacePresentation: ChatRecentHistorySurfacePresentation? = null,
    val archivedMemorySurfacePresentation: ChatArchivedMemorySurfacePresentation? = null,
    val supplementalRuntimeStatuses: List<SupplementalRuntimeStatusPresentation>,
    val topStatusStrip: TopStatusStripPresentation
) {
    val currentApprovalModeLabel: String
        get() = approvalRuntimePosturePresentation.shortcutLabel
}

internal fun buildChatRuntimeHostPresentation(
    config: ProviderConfig,
    approvalRuntimeTelemetry: ApprovalRuntimeTelemetry,
    runtimeStatusSnapshot: RuntimeStatusSnapshot?,
    pendingPromptHostPresentation: PendingPromptHostPresentation = PendingPromptHostPresentation(),
    recentHistoryCluePresentation: RecentHistoryCluePresentation? = null,
    archivedMemoryCandidates: List<ArchivedMemoryCandidate> = emptyList(),
    askPresentation: AskPromptPresentation?,
    workflowPlanPresentation: WorkflowPlanPromptPresentation?,
    clarificationPresentation: ClarificationPromptPresentation?,
    autoRoutingInProgress: Boolean,
    workflowPlanningInProgress: Boolean,
    clarificationInProgress: Boolean
): ChatRuntimeHostPresentation {
    val approvalRuntimePosturePresentation = buildApprovalRuntimePosturePresentation(
        config = config,
        approvalRuntimeTelemetry = approvalRuntimeTelemetry,
        runtimeStatusSnapshot = runtimeStatusSnapshot
    )
    val pendingPromptRuntimePresentation = buildPendingPromptRuntimePresentation(
        runtimeStatusSnapshot = runtimeStatusSnapshot,
        askPresentation = askPresentation,
        workflowPlanPresentation = workflowPlanPresentation,
        clarificationPresentation = clarificationPresentation
    )
    val supplementalRuntimeStatuses = buildSupplementalRuntimeStatusPresentations(
        workflowExecutionMode = config.workflowExecutionMode,
        autoRouteBeforeExecution = config.autoRouteBeforeExecution,
        autoRoutingInProgress = autoRoutingInProgress,
        workflowPlanningInProgress = workflowPlanningInProgress,
        clarificationInProgress = clarificationInProgress,
        runtimeStatusSnapshot = runtimeStatusSnapshot
    )
    return buildChatRuntimeHostPresentation(
        approvalRuntimePosturePresentation = approvalRuntimePosturePresentation,
        runtimeStatusSnapshot = runtimeStatusSnapshot,
        pendingPromptRuntimePresentation = pendingPromptRuntimePresentation,
        pendingPromptHostPresentation = pendingPromptHostPresentation,
        recentHistoryCluePresentation = recentHistoryCluePresentation,
        archivedMemoryCandidates = archivedMemoryCandidates,
        supplementalRuntimeStatuses = supplementalRuntimeStatuses
    )
}

internal fun buildChatRuntimeHostPresentation(
    approvalRuntimePosturePresentation: ApprovalRuntimePosturePresentation,
    runtimeStatusSnapshot: RuntimeStatusSnapshot?,
    pendingPromptRuntimePresentation: PendingPromptRuntimePresentation?,
    pendingPromptHostPresentation: PendingPromptHostPresentation = PendingPromptHostPresentation(),
    recentHistoryCluePresentation: RecentHistoryCluePresentation? = null,
    archivedMemoryCandidates: List<ArchivedMemoryCandidate> = emptyList(),
    supplementalRuntimeStatuses: List<SupplementalRuntimeStatusPresentation>
): ChatRuntimeHostPresentation {
    return ChatRuntimeHostPresentation(
        approvalRuntimePosturePresentation = approvalRuntimePosturePresentation,
        pendingPromptRuntimePresentation = pendingPromptRuntimePresentation,
        pendingPromptHostPresentation = pendingPromptHostPresentation,
        recentHistorySurfacePresentation = buildChatRecentHistorySurfacePresentation(
            recentHistoryCluePresentation = recentHistoryCluePresentation,
            pendingPromptHostPresentation = pendingPromptHostPresentation
        ),
        archivedMemorySurfacePresentation = buildChatArchivedMemorySurfacePresentation(
            archivedMemoryCandidates = archivedMemoryCandidates
        ),
        supplementalRuntimeStatuses = supplementalRuntimeStatuses,
        topStatusStrip = buildTopStatusStripPresentation(
            snapshot = runtimeStatusSnapshot,
            approvalRuntimePosturePresentation = approvalRuntimePosturePresentation,
            pendingPromptRuntimePresentation = pendingPromptRuntimePresentation
        )
    )
}

private fun buildRuntimeStatusMessage(
    snapshot: RuntimeStatusSnapshot,
    approvalRuntimePosturePresentation: ApprovalRuntimePosturePresentation
): String {
    if (!snapshot.showApprovalPosture) {
        return snapshot.message
    }
    val postureMessage = buildRuntimeApprovalPostureMessage(
        displayMode = snapshot.approvalPostureDisplayMode,
        postureLabel = approvalRuntimePosturePresentation.label,
        fullMessage = approvalRuntimePosturePresentation.message
    ) ?: return snapshot.message
    return buildString {
        append(snapshot.message)
        if (isNotBlank()) {
            append('\n')
        }
        append(postureMessage)
    }
}

private fun buildTopStatusStripBadge(snapshot: RuntimeStatusSnapshot?): String? {
    if (snapshot?.kind != RuntimeStatusKind.BACKGROUND_ACTIVITY) return null
    return snapshot.backgroundActivityFocusTelemetry?.label
        ?: backgroundActivityFocusLabel(snapshot.backgroundActivityFocusKind)
}

internal fun buildTopStatusStripPresentation(
    snapshot: RuntimeStatusSnapshot?,
    approvalRuntimePosturePresentation: ApprovalRuntimePosturePresentation,
    pendingPromptRuntimePresentation: PendingPromptRuntimePresentation? = null
): TopStatusStripPresentation {
    if (snapshot == null) {
        return TopStatusStripPresentation(
            title = approvalPostureSectionTitle(),
            message = approvalRuntimePosturePresentation.message,
            compact = true
        )
    }
    val runtimeTitle = pendingPromptRuntimePresentation?.title ?: snapshot.title
    val runtimeMessage = pendingPromptRuntimePresentation?.message ?: buildRuntimeStatusMessage(
        snapshot = snapshot,
        approvalRuntimePosturePresentation = approvalRuntimePosturePresentation
    )
    val runtimeBadge = pendingPromptRuntimePresentation?.badge ?: buildTopStatusStripBadge(snapshot)
    return TopStatusStripPresentation(
        title = runtimeTitle,
        badge = runtimeBadge,
        message = runtimeMessage
    )
}

private fun buildChatRecentHistorySurfacePresentation(
    recentHistoryCluePresentation: RecentHistoryCluePresentation?,
    pendingPromptHostPresentation: PendingPromptHostPresentation
): ChatRecentHistorySurfacePresentation? {
    if (recentHistoryCluePresentation != null) {
        return ChatRecentHistorySurfacePresentation(
            title = "跨会话历史线索",
            message = recentHistoryCluePresentation.summary,
            detailTitle = recentHistoryCluePresentation.title,
            detailSubtitle = "来源：当前会话上下文",
            detailRows = recentHistoryCluePresentation.detailRows
        )
    }
    val workflowClue = pendingPromptHostPresentation.workflowPlanHostSurface.workflowPlanPresentation
        ?.recentHistoryClue
    if (workflowClue != null) {
        return ChatRecentHistorySurfacePresentation(
            title = "跨会话历史线索",
            message = workflowClue.summary,
            detailTitle = workflowClue.title,
            detailSubtitle = "来源：执行计划",
            detailRows = workflowClue.detailRows
        )
    }
    val clarificationClue = pendingPromptHostPresentation.clarificationHostSurface.clarificationPresentation
        ?.recentHistoryClue
    if (clarificationClue != null) {
        return ChatRecentHistorySurfacePresentation(
            title = "跨会话历史线索",
            message = clarificationClue.summary,
            detailTitle = clarificationClue.title,
            detailSubtitle = "来源：澄清问题",
            detailRows = clarificationClue.detailRows
        )
    }
    return null
}

private fun buildChatArchivedMemorySurfacePresentation(
    archivedMemoryCandidates: List<ArchivedMemoryCandidate>
): ChatArchivedMemorySurfacePresentation? {
    if (archivedMemoryCandidates.isEmpty()) return null
    val visibleCandidates = archivedMemoryCandidates.take(3)
    val leadingCandidate = visibleCandidates.first()
    val summaryPrefix = if (archivedMemoryCandidates.size == 1) {
        "待处理 1 条"
    } else {
        "待处理 ${archivedMemoryCandidates.size} 条"
    }
    return ChatArchivedMemorySurfacePresentation(
        title = "归档记忆候选",
        message = "$summaryPrefix，最近：${leadingCandidate.suggestedTitle} · ${
            formatArchivedMemoryCandidateScopeLabel(leadingCandidate.suggestedScope)
        }",
        detailTitle = "归档记忆候选",
        detailSubtitle = "来源：archive-on-forget · 当前待处理 ${archivedMemoryCandidates.size} 条",
        totalCandidateCount = archivedMemoryCandidates.size,
        batchCandidates = archivedMemoryCandidates.map { candidate ->
            ChatArchivedMemoryCandidateBatchTargetPresentation(
                sessionId = candidate.sourceSessionId,
                suggestedScope = candidate.suggestedScope,
                suggestedTitle = candidate.suggestedTitle
            )
        },
        candidates = visibleCandidates.map { candidate ->
            ChatArchivedMemoryCandidateItemPresentation(
                sessionId = candidate.sourceSessionId,
                sourceSessionTitle = candidate.sourceSessionTitle,
                suggestedScope = candidate.suggestedScope,
                suggestedTitle = candidate.suggestedTitle,
                suggestedContentPreview = candidate.suggestedContent.trim().take(160),
                sourceAnchorMessageReference = candidate.sourceAnchorMessageReference?.trim()
                    ?.takeIf { it.isNotBlank() },
                sourceFinalReadinessSummary = candidate.sourceFinalReadinessSummary?.trim()
                    ?.takeIf { it.isNotBlank() }
            )
        }
    )
}

private fun formatArchivedMemoryCandidateScopeLabel(scope: ArchivedMemoryCandidateScope): String {
    return when (scope) {
        ArchivedMemoryCandidateScope.PROJECT -> "项目记忆建议"
        ArchivedMemoryCandidateScope.GLOBAL -> "全局记忆建议"
    }
}
