package com.murong.agent.core.loop

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.murong.agent.common.utils.RootFile
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.GlobalRule
import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.ProjectWorkflowType
import com.murong.agent.core.config.ProjectWorkflowRiskLevel
import com.murong.agent.core.config.ProjectToolPreferences
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.ActiveProjectScopeResolution
import com.murong.agent.core.config.RestoredProjectConfigSnapshot
import com.murong.agent.core.config.SessionProjectConfig
import com.murong.agent.core.config.SkillRunAs
import com.murong.agent.core.config.WorkflowFailureFallbackContext
import com.murong.agent.core.config.WorkflowFailureFallbackSource
import com.murong.agent.core.config.WorkflowFailureType
import com.murong.agent.core.config.WorkflowFailureFallbackMode
import com.murong.agent.core.config.applyProjectConfigUpdate
import com.murong.agent.core.config.buildSessionProjectConfig
import com.murong.agent.core.config.persistProjectScopeConfig
import com.murong.agent.core.config.preparePersistedProjectConfig
import com.murong.agent.core.config.restorePersistedProjectConfig
import com.murong.agent.core.config.resolveActiveProjectScope
import com.murong.agent.core.config.resolveProjectScopeConfig
import com.murong.agent.core.config.resolveSessionConfig
import com.murong.agent.core.config.toPersistedSessionProjectConfigProjection
import com.murong.agent.core.config.toPersistedSessionProjectConfigSeed
import com.murong.agent.core.config.toLegacySessionProjectConfig
import com.murong.agent.core.doctor.buildDoctorReport
import com.murong.agent.core.doctor.PendingCrashStore
import com.murong.agent.core.doctor.SensitiveDataSanitizer
import com.murong.agent.core.config.toPersistedRepoScopedProjectConfigMap
import com.murong.agent.core.config.toSessionProjectConfig
import com.murong.agent.core.memory.MemoryScope
import com.murong.agent.core.memory.PersistedMemoryStore
import com.murong.agent.core.mcp.McpRegistry
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpTransportType
import com.murong.agent.core.skill.PersistedSkillStore
import com.murong.agent.core.provider.ChatRequest
import com.murong.agent.core.provider.ChatMessage
import com.murong.agent.core.provider.Usage
import com.murong.agent.core.provider.ProviderRegistry
import com.murong.agent.core.tool.ApprovalRiskLevel
import com.murong.agent.core.tool.ToolFileChange
import com.murong.agent.core.tool.ToolApprovalRequest
import com.murong.agent.core.tool.*
import com.murong.agent.core.tool.buildDiffPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.max
import kotlin.math.roundToInt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

const val WEB_FETCH_RESULT_PREFIX = "WEB_FETCH_RESULT\n"

/**
 * 聊天会话中的单条消息（UI 层数据模型）
 */
data class ChatMessageUi(
    val id: Long,
    val role: String,
    val content: String,
    val imageAttachments: List<MessageImageAttachmentUi> = emptyList(),
    val reasoning: String? = null,
    val isStreaming: Boolean = false,
    val subagentRunId: String? = null,
    val subagentBatchId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class MessageImageAttachmentUi(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val mimeType: String,
    val localCachePath: String,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null
)

data class PendingImageAttachmentUi(
    val uri: String,
    val fileName: String = "",
    val mimeType: String? = null
)

data class SubagentRunUi(
    val runId: String,
    val status: String,
    val goal: String,
    val summary: String = "",
    val error: String? = null,
    val templateId: String? = null,
    val templateTitle: String? = null,
    val model: String,
    val reasoningEffort: String? = null,
    val allowedTools: List<String> = emptyList(),
    val statusMessage: String = "",
    val retryCount: Int = 0,
    val sourceRunId: String? = null,
    val batchId: String? = null,
    val batchLabel: String? = null,
    val batchIndex: Int? = null,
    val batchSize: Int? = null,
    val queuePosition: Int? = null,
    val assignedSlot: Int? = null,
    val concurrencyLimit: Int? = null,
    val approvalRequestedAt: Long? = null,
    val queuedAt: Long? = null,
    val executionStartedAt: Long? = null,
    val summarizingAt: Long? = null,
    val usageSummary: UsageSummarySnapshot = UsageSummarySnapshot(),
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)

data class SubagentBatchUi(
    val batchId: String,
    val parentGoal: String,
    val label: String,
    val runIds: List<String> = emptyList(),
    val splitStrategyLabel: String = "",
    val splitStrategyDetail: String = "",
    val status: String,
    val statusMessage: String = "",
    val summary: String = "",
    val queuePosition: Int? = null,
    val activeSlots: List<Int> = emptyList(),
    val queuedRuns: Int = 0,
    val runningRuns: Int = 0,
    val concurrencyLimit: Int? = null,
    val approvalRequestedAt: Long? = null,
    val queuedAt: Long? = null,
    val firstRunStartedAt: Long? = null,
    val lastRunFinishedAt: Long? = null,
    val timeline: List<SubagentTimelineEntryUi> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)

data class SubagentTimelineEntryUi(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,
    val title: String,
    val detail: String = "",
    val relatedRunId: String? = null
)

data class ContextCompressionUi(
    val id: String,
    val version: Int = 1,
    val summary: String,
    val sourceMessageCount: Int,
    val sourceEndMessageId: Long,
    val sourceEndMessageIndex: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val active: Boolean = true
)

data class ContextCompressionPreviewUi(
    val compressibleMessageCount: Int,
    val recentMessageCount: Int,
    val estimatedCurrentContextTokens: Int,
    val estimatedCompressedContextTokens: Int,
    val estimatedSummaryTokens: Int,
    val estimatedTokensSaved: Int,
    val estimatedReductionPercent: Int
)

data class ConversationCheckpointUi(
    val id: String,
    val messageIndex: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val summary: String,
    val changedFiles: List<String> = emptyList(),
    val kind: ConversationCheckpointKind = ConversationCheckpointKind.FILE_TURN,
    val scope: ConversationCheckpointScope = ConversationCheckpointScope.CODE,
    val source: ConversationCheckpointSource = ConversationCheckpointSource.TOOL_EXECUTION,
    val toolNames: List<String> = emptyList(),
    val promptSnapshot: ConversationCheckpointPromptSnapshotUi? = null
)

enum class ConversationCheckpointKind {
    FILE_TURN,
    ROLLBACK
}

enum class ConversationCheckpointScope {
    CODE,
    CONVERSATION,
    BOTH
}

enum class ConversationCheckpointSource {
    TOOL_EXECUTION,
    ROLLBACK,
    LEGACY_EMBEDDED
}

data class FileMentionUi(
    val path: String,
    val displayPath: String,
    val inlineContent: String? = null
)

data class ProjectKnowledgeSnapshotUi(
    val id: String,
    val name: String,
    val paths: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAppliedAt: Long? = null
)

data class ProjectKnowledgeSnapshotMutationResult(
    val success: Boolean,
    val message: String
)

data class ArchivedMemoryCandidateMutationResult(
    val success: Boolean,
    val message: String,
    val status: ArchivedMemoryCandidateStatus? = null,
    val resolutionSource: ArchivedMemoryCandidateResolutionSource? = null,
    val resolutionSummary: String? = null,
    val scope: ArchivedMemoryCandidateScope? = null,
    val memoryId: String? = null,
    val deduplicated: Boolean = false
)

internal data class ArchivedMemoryCandidateResolutionTelemetry(
    val source: ArchivedMemoryCandidateResolutionSource,
    val summary: String
)

enum class MemoryUpdateSuggestionStatusUi {
    PENDING,
    APPLIED
}

data class MemoryUpdateSuggestionUi(
    val id: String = "mem-suggest-${UUID.randomUUID()}",
    val title: String,
    val content: String,
    val scope: String,
    val reason: String,
    val sourceKind: String,
    val sourceUserMessageId: Long? = null,
    val sourceAssistantMessageId: Long? = null,
    val linkedMemoryId: String? = null,
    val status: MemoryUpdateSuggestionStatusUi = MemoryUpdateSuggestionStatusUi.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)

enum class WorkflowPlanStatusUi {
    READY,
    EXECUTING,
    BLOCKED,
    COMPLETED
}

data class WorkflowStepSignOffUi(
    val stepIndex: Int,
    val step: String,
    val reportedStep: String,
    val resultSummary: String,
    val matchedEvidenceCount: Int,
    val totalEvidenceCount: Int,
    val matchedToolNames: List<String> = emptyList(),
    val matchedSessionHistorySessionIds: List<String> = emptyList(),
    val matchedSessionHistoryMessageReferences: List<String> = emptyList(),
    val signedOffAt: Long = System.currentTimeMillis()
)

data class SessionHistoryReferenceClueUi(
    val queries: List<String> = emptyList(),
    val sessionIds: List<String> = emptyList(),
    val messageReferences: List<String> = emptyList(),
    val snippets: List<String> = emptyList(),
    val excerptWindows: List<String> = emptyList()
)

data class SkillUsageClueUi(
    val skillTitles: List<String> = emptyList(),
    val queries: List<String> = emptyList(),
    val tasks: List<String> = emptyList(),
    val runModes: List<String> = emptyList(),
    val delegatedSkillTitles: List<String> = emptyList()
)

data class WorkflowPlanUi(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val summary: String,
    val steps: List<String> = emptyList(),
    val stageLabels: List<String> = emptyList(),
    val currentStageIndex: Int = 0,
    val currentStepIndex: Int = 0,
    val nextStepHint: String = "",
    val status: WorkflowPlanStatusUi = WorkflowPlanStatusUi.READY,
    val mentionedFiles: List<FileMentionUi> = emptyList(),
    val stepSignOffs: List<WorkflowStepSignOffUi> = emptyList(),
    val recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null,
    val rawPlan: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

enum class ClarificationSource {
    MANUAL,
    AUTO_ROUTE,
    AUTO_INTERRUPT
}

data class ClarificationRequestUi(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val question: String,
    val mentionedFiles: List<FileMentionUi> = emptyList(),
    val previousAnswers: List<ClarificationAnswerUi> = emptyList(),
    val turnIndex: Int = 1,
    val maxTurns: Int = MAX_CLARIFICATION_TURNS,
    val source: ClarificationSource = ClarificationSource.MANUAL,
    val recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class ClarificationAnswerUi(
    val question: String,
    val answer: String,
    val answeredAt: Long = System.currentTimeMillis()
)

data class AskOptionUi(
    val label: String,
    val description: String? = null
)

data class AskQuestionUi(
    val id: String,
    val header: String = "",
    val question: String,
    val options: List<AskOptionUi>,
    val multiSelect: Boolean = false
)

data class AskAnswerUi(
    val questionId: String,
    val selectedOptions: List<String> = emptyList()
)

data class PendingAskRequestUi(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "需要确认",
    val questions: List<AskQuestionUi>,
    val createdAt: Long = System.currentTimeMillis(),
    val recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null,
    val isReplayOnly: Boolean = false,
    val replayNotice: String? = null
)

data class ConversationCheckpointPromptSnapshotUi(
    val pendingAskRequest: PendingAskRequestUi? = null,
    val pendingWorkflowPlan: WorkflowPlanUi? = null,
    val canonicalWorkflowPlan: WorkflowPlanUi? = null,
    val pendingClarificationRequest: ClarificationRequestUi? = null
)

enum class AutoRouteAction {
    DIRECT,
    PLAN,
    CLARIFY
}

data class AutoRouteDecisionUi(
    val action: AutoRouteAction,
    val reason: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class WorkflowFallbackUi(
    val message: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class FileChangeRecordUi(
    val id: String,
    val path: String,
    val operation: String,
    val beforeContent: String? = null,
    val afterContent: String? = null,
    val diffPreview: String = "",
    val changedAt: Long = System.currentTimeMillis(),
    val checkpointId: String? = null
)

data class ErrorRecordUi(
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val kind: ErrorRecordKind = ErrorRecordKind.GENERAL,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ErrorRecordKind {
    GENERAL,
    FINAL_READINESS
}

data class ToolCallRecordUi(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val args: String,
    val result: String? = null,
    val isSuccess: Boolean = true,
    val stepSignOffReceipt: StepSignOffReceipt? = null,
    val structuredPayload: ToolStructuredPayload? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class CheckpointRecoveryRecordUi(
    val id: String = UUID.randomUUID().toString(),
    val checkpointId: String,
    val checkpointSummary: String,
    val scope: ConversationCheckpointScope,
    val restoredFileCount: Int = 0,
    val targetMessageIndex: Int,
    val timestamp: Long = System.currentTimeMillis()
)

data class ApprovalRecordUi(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val summary: String,
    val decision: String, // "Approved", "Rejected"
    val scopeSummary: String? = null,
    val explanationLabel: String? = null,
    val explanationDetail: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ApprovalInvalidationRecordUi(
    val id: String = UUID.randomUUID().toString(),
    val summary: String,
    val sourceLabel: String,
    val sourceDetail: String? = null,
    val reasonLabel: String,
    val reasonDetail: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ApprovedApprovalScopeUi(
    val id: String,
    val capabilities: List<String>,
    val summary: String,
    val sourceLabel: String,
    val sourceDetail: String? = null
)

data class InheritedApprovalScopeUi(
    val id: String,
    val capabilities: List<String>,
    val summary: String,
    val policyLabel: String,
    val policyDetail: String,
    val sourceSessionId: String,
    val sourceSessionTitle: String,
    val sourceUpdatedAt: Long
)

data class ApprovalScopeTelemetrySummary(
    val currentApprovalCount: Int,
    val inheritedApprovalCount: Int,
    val recentApprovalCount: Int,
    val recentInvalidationCount: Int
)

data class ApprovalPosturePendingSummaryTelemetry(
    val parts: List<String>,
    val emphasized: Boolean
)

enum class ApprovalPostureStatusKind {
    REPLAY_ONLY_PENDING_APPROVAL,
    PENDING_APPROVAL,
    RECENT_INVALIDATION,
    REUSABLE_SCOPES,
    IDLE
}

data class ApprovalPostureTelemetry(
    val statusKind: ApprovalPostureStatusKind,
    val hasPendingApproval: Boolean,
    val pendingApprovalIsReplayOnly: Boolean,
    val hasReusableScopes: Boolean,
    val hasRecentInvalidations: Boolean,
    val scopeSummary: ApprovalScopeTelemetrySummary,
    val pendingSummary: ApprovalPosturePendingSummaryTelemetry? = null,
    val replayNotice: String? = null
)

data class ApprovalRecentDecisionTelemetry(
    val toolName: String,
    val decision: String,
    val summary: String? = null,
    val explanationLabel: String? = null,
    val scopeSummary: String? = null
)

data class ApprovalCardTelemetry(
    val hasPendingApproval: Boolean,
    val pendingToolName: String? = null,
    val pendingSummary: String? = null,
    val pendingApprovalIsReplayOnly: Boolean = false,
    val replayNotice: String? = null,
    val recentApprovals: List<ApprovalRecentDecisionTelemetry> = emptyList()
)

data class PendingApprovalRowTelemetry(
    val label: String,
    val value: String
)

data class PendingApprovalDetailTelemetry(
    val headline: String,
    val supportText: String?,
    val rows: List<PendingApprovalRowTelemetry> = emptyList(),
    val rawArgsLabel: String,
    val approveLabel: String,
    val rejectLabel: String
)

data class PendingApprovalHostTelemetry(
    val toolName: String,
    val riskLevel: ApprovalRiskLevel,
    val rawArgs: String,
    val approveEnabled: Boolean,
    val replayNotice: String? = null,
    val explanationLabel: String? = null,
    val explanationDetail: String? = null,
    val detailTelemetry: PendingApprovalDetailTelemetry
)

enum class ProjectApprovalItemKind {
    CURRENT_SCOPE,
    INHERITED_SCOPE,
    TREND,
    INVALIDATION
}

data class ProjectApprovalHistoryTelemetry(
    val analyzedSessionCount: Int,
    val sessionsWithApprovedScopes: Int,
    val distinctScopeCount: Int
)

data class ProjectApprovalItemTelemetry(
    val kind: ProjectApprovalItemKind,
    val summary: String,
    val sourceLabel: String? = null,
    val sourceDetail: String? = null,
    val policyLabel: String? = null,
    val policyDetail: String? = null,
    val sourceSessionTitle: String? = null,
    val sessionCount: Int? = null,
    val directSessionCount: Int? = null,
    val importedSessionCount: Int? = null,
    val autoInheritedSessionCount: Int? = null,
    val reasonLabel: String? = null,
    val reasonDetail: String? = null
)

data class ProjectApprovalCardTelemetry(
    val history: ProjectApprovalHistoryTelemetry? = null,
    val items: List<ProjectApprovalItemTelemetry> = emptyList()
)

data class ApprovalRuntimeTelemetry(
    val postureTelemetry: ApprovalPostureTelemetry,
    val approvalCardTelemetry: ApprovalCardTelemetry,
    val pendingApprovalHostTelemetry: PendingApprovalHostTelemetry? = null,
    val projectApprovalCardTelemetry: ProjectApprovalCardTelemetry
)

fun resolveApprovalScopeTelemetrySummary(
    recentApprovals: List<ApprovalRecordUi>,
    approvedApprovalScopes: List<ApprovedApprovalScopeUi>,
    inheritedApprovalScopes: List<InheritedApprovalScopeUi>,
    recentInvalidations: List<ApprovalInvalidationRecordUi>
): ApprovalScopeTelemetrySummary {
    return ApprovalScopeTelemetrySummary(
        currentApprovalCount = approvedApprovalScopes.size,
        inheritedApprovalCount = inheritedApprovalScopes.size,
        recentApprovalCount = recentApprovals.size,
        recentInvalidationCount = recentInvalidations.size
    )
}

fun resolveApprovalPostureTelemetry(
    pendingApproval: PendingApprovalUi?,
    recentApprovals: List<ApprovalRecordUi>,
    approvedApprovalScopes: List<ApprovedApprovalScopeUi>,
    inheritedApprovalScopes: List<InheritedApprovalScopeUi>,
    recentInvalidations: List<ApprovalInvalidationRecordUi>
): ApprovalPostureTelemetry {
    val hasPendingApproval = pendingApproval != null
    val pendingApprovalIsReplayOnly = pendingApproval?.isReplayOnly == true
    val hasReusableScopes = approvedApprovalScopes.isNotEmpty() || inheritedApprovalScopes.isNotEmpty()
    val hasRecentInvalidations = recentInvalidations.isNotEmpty()
    val statusKind = when {
        pendingApprovalIsReplayOnly -> ApprovalPostureStatusKind.REPLAY_ONLY_PENDING_APPROVAL
        hasPendingApproval -> ApprovalPostureStatusKind.PENDING_APPROVAL
        hasRecentInvalidations -> ApprovalPostureStatusKind.RECENT_INVALIDATION
        hasReusableScopes -> ApprovalPostureStatusKind.REUSABLE_SCOPES
        else -> ApprovalPostureStatusKind.IDLE
    }
    return ApprovalPostureTelemetry(
        statusKind = statusKind,
        hasPendingApproval = hasPendingApproval,
        pendingApprovalIsReplayOnly = pendingApprovalIsReplayOnly,
        hasReusableScopes = hasReusableScopes,
        hasRecentInvalidations = hasRecentInvalidations,
        scopeSummary = resolveApprovalScopeTelemetrySummary(
            recentApprovals = recentApprovals,
            approvedApprovalScopes = approvedApprovalScopes,
            inheritedApprovalScopes = inheritedApprovalScopes,
            recentInvalidations = recentInvalidations
        ),
        pendingSummary = pendingApproval?.let(::buildApprovalPosturePendingSummaryTelemetry),
        replayNotice = pendingApproval?.let(::resolveReplayOnlyApprovalNotice)
    )
}

private fun buildApprovalPosturePendingSummaryTelemetry(
    pendingApproval: PendingApprovalUi
): ApprovalPosturePendingSummaryTelemetry {
    return ApprovalPosturePendingSummaryTelemetry(
        parts = buildList {
            pendingApproval.toolName.trim()
                .takeIf { it.isNotBlank() }
                ?.let(::add)
            pendingApproval.summary.trim()
                .takeIf { it.isNotBlank() }
                ?.let(::add)
        },
        emphasized = true
    )
}

fun resolveApprovalRuntimeTelemetry(
    pendingApproval: PendingApprovalUi?,
    recentApprovals: List<ApprovalRecordUi>,
    approvedApprovalScopes: List<ApprovedApprovalScopeUi>,
    inheritedApprovalScopes: List<InheritedApprovalScopeUi>,
    recentInvalidations: List<ApprovalInvalidationRecordUi>,
    projectApprovalHistory: ProjectApprovalHistoryUi?
): ApprovalRuntimeTelemetry {
    return ApprovalRuntimeTelemetry(
        postureTelemetry = resolveApprovalPostureTelemetry(
            pendingApproval = pendingApproval,
            recentApprovals = recentApprovals,
            approvedApprovalScopes = approvedApprovalScopes,
            inheritedApprovalScopes = inheritedApprovalScopes,
            recentInvalidations = recentInvalidations
        ),
        approvalCardTelemetry = resolveApprovalCardTelemetry(
            pendingApproval = pendingApproval,
            recentApprovals = recentApprovals
        ),
        pendingApprovalHostTelemetry = pendingApproval?.let(::resolvePendingApprovalHostTelemetry),
        projectApprovalCardTelemetry = resolveProjectApprovalCardTelemetry(
            recentApprovalInvalidations = recentInvalidations,
            approvedApprovalScopes = approvedApprovalScopes,
            projectApprovalHistory = projectApprovalHistory,
            inheritedApprovalScopes = inheritedApprovalScopes
        )
    )
}

fun resolveApprovalCardTelemetry(
    pendingApproval: PendingApprovalUi?,
    recentApprovals: List<ApprovalRecordUi>
): ApprovalCardTelemetry {
    return ApprovalCardTelemetry(
        hasPendingApproval = pendingApproval != null,
        pendingToolName = pendingApproval?.toolName
            ?.trim()
            ?.takeIf { it.isNotBlank() },
        pendingSummary = pendingApproval?.summary
            ?.trim()
            ?.takeIf { it.isNotBlank() },
        pendingApprovalIsReplayOnly = pendingApproval?.isReplayOnly == true,
        replayNotice = pendingApproval?.let(::resolveReplayOnlyApprovalNotice),
        recentApprovals = recentApprovals.take(3).map { approval ->
            ApprovalRecentDecisionTelemetry(
                toolName = approval.toolName,
                decision = approval.decision,
                summary = approval.summary
                    .trim()
                    .takeIf { it.isNotBlank() },
                explanationLabel = approval.explanationLabel
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
                scopeSummary = approval.scopeSummary
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            )
        }
    )
}

fun resolvePendingApprovalDetailTelemetry(
    pendingApproval: PendingApprovalUi
): PendingApprovalDetailTelemetry {
    if (pendingApproval.isReplayOnly) {
        return PendingApprovalDetailTelemetry(
            headline = pendingApproval.summary,
            supportText = resolveReplayOnlyApprovalNotice(pendingApproval),
            rows = emptyList(),
            rawArgsLabel = "原始参数",
            approveLabel = "无法继续",
            rejectLabel = "关闭"
        )
    }
    if (!pendingApproval.isGitHubPendingApproval()) {
        return PendingApprovalDetailTelemetry(
            headline = pendingApproval.summary,
            supportText = pendingApproval.detail.trim().takeIf { it.isNotBlank() },
            rows = emptyList(),
            rawArgsLabel = "参数",
            approveLabel = "允许",
            rejectLabel = "拒绝"
        )
    }

    val normalizedToolName = pendingApproval.toolName.removePrefix("mcp_")
    val argsObject = parsePendingApprovalArgs(pendingApproval.rawArgs)
    val actionLabel = gitHubActionLabel(normalizedToolName)
    val owner = argsObject?.get("owner")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val repo = argsObject?.get("repo")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val repoName = argsObject?.get("name")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val branch = argsObject?.get("branch")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val path = argsObject?.get("path")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val issueNumber = argsObject?.get("issue_number")?.jsonPrimitive?.intOrNull
    val pullNumber = argsObject?.get("pull_number")?.jsonPrimitive?.intOrNull

    val rows = buildList {
        add(PendingApprovalRowTelemetry(label = "操作", value = actionLabel))
        when {
            owner.isNotBlank() && repo.isNotBlank() ->
                add(PendingApprovalRowTelemetry(label = "仓库", value = "$owner/$repo"))
            owner.isNotBlank() ->
                add(PendingApprovalRowTelemetry(label = "所有者", value = owner))
        }
        if (repoName.isNotBlank() && repo.isBlank()) {
            add(PendingApprovalRowTelemetry(label = "仓库名", value = repoName))
        }
        if (branch.isNotBlank()) {
            add(PendingApprovalRowTelemetry(label = "分支", value = branch))
        }
        if (path.isNotBlank()) {
            add(PendingApprovalRowTelemetry(label = "文件", value = path))
        }
        if (pullNumber != null) {
            add(PendingApprovalRowTelemetry(label = "Pull Request", value = "#$pullNumber"))
        }
        if (issueNumber != null) {
            add(PendingApprovalRowTelemetry(label = "Issue", value = "#$issueNumber"))
        }
    }

    return PendingApprovalDetailTelemetry(
        headline = "GitHub 远端写操作",
        supportText = "批准后才会真正修改远端 GitHub 资源；当前展示的是本次要操作的目标。",
        rows = rows,
        rawArgsLabel = "原始参数 (JSON)",
        approveLabel = "允许写入 GitHub",
        rejectLabel = "拒绝远端修改"
    )
}

fun resolvePendingApprovalHostTelemetry(
    pendingApproval: PendingApprovalUi
): PendingApprovalHostTelemetry {
    return PendingApprovalHostTelemetry(
        toolName = pendingApproval.toolName.trim().takeIf { it.isNotBlank() } ?: pendingApproval.toolName,
        riskLevel = pendingApproval.riskLevel,
        rawArgs = pendingApproval.rawArgs,
        approveEnabled = !pendingApproval.isReplayOnly,
        replayNotice = resolveReplayOnlyApprovalNotice(pendingApproval),
        explanationLabel = pendingApproval.explanationLabel
            ?.trim()
            ?.takeIf { it.isNotBlank() },
        explanationDetail = pendingApproval.explanationDetail
            ?.trim()
            ?.takeIf { it.isNotBlank() },
        detailTelemetry = resolvePendingApprovalDetailTelemetry(pendingApproval)
    )
}

private fun resolveReplayOnlyApprovalNotice(
    pendingApproval: PendingApprovalUi
): String? {
    val replayNotice = pendingApproval.replayNotice
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    if (replayNotice != null) {
        return replayNotice
    }
    return if (pendingApproval.isReplayOnly) {
        REPLAY_ONLY_APPROVAL_NOTICE
    } else {
        null
    }
}

fun resolveProjectApprovalCardTelemetry(
    recentApprovalInvalidations: List<ApprovalInvalidationRecordUi>,
    approvedApprovalScopes: List<ApprovedApprovalScopeUi>,
    projectApprovalHistory: ProjectApprovalHistoryUi?,
    inheritedApprovalScopes: List<InheritedApprovalScopeUi>
): ProjectApprovalCardTelemetry {
    val items = buildList {
        approvedApprovalScopes.take(2).forEach { scope ->
            add(
                ProjectApprovalItemTelemetry(
                    kind = ProjectApprovalItemKind.CURRENT_SCOPE,
                    summary = scope.summary,
                    sourceLabel = scope.sourceLabel,
                    sourceDetail = scope.sourceDetail
                )
            )
        }
        inheritedApprovalScopes.take(2).forEach { scope ->
            add(
                ProjectApprovalItemTelemetry(
                    kind = ProjectApprovalItemKind.INHERITED_SCOPE,
                    summary = scope.summary,
                    policyLabel = scope.policyLabel,
                    policyDetail = scope.policyDetail,
                    sourceSessionTitle = scope.sourceSessionTitle
                )
            )
        }
        projectApprovalHistory?.scopeTrends.orEmpty().take(2).forEach { trend ->
            add(
                ProjectApprovalItemTelemetry(
                    kind = ProjectApprovalItemKind.TREND,
                    summary = trend.summary,
                    policyLabel = trend.policyLabel,
                    policyDetail = trend.policyDetail,
                    sessionCount = trend.sessionCount,
                    directSessionCount = trend.directSessionCount,
                    importedSessionCount = trend.importedSessionCount,
                    autoInheritedSessionCount = trend.autoInheritedSessionCount
                )
            )
        }
        recentApprovalInvalidations.take(2).forEach { record ->
            add(
                ProjectApprovalItemTelemetry(
                    kind = ProjectApprovalItemKind.INVALIDATION,
                    summary = record.summary,
                    sourceLabel = record.sourceLabel,
                    sourceDetail = record.sourceDetail,
                    reasonLabel = record.reasonLabel,
                    reasonDetail = record.reasonDetail
                )
            )
        }
    }
    return ProjectApprovalCardTelemetry(
        history = projectApprovalHistory?.let { history ->
            ProjectApprovalHistoryTelemetry(
                analyzedSessionCount = history.analyzedSessionCount,
                sessionsWithApprovedScopes = history.sessionsWithApprovedScopes,
                distinctScopeCount = history.distinctScopeCount
            )
        },
        items = items
    )
}

private fun PendingApprovalUi.isGitHubPendingApproval(): Boolean {
    return toolName.contains("github", ignoreCase = true) ||
        summary.contains("GitHub", ignoreCase = true) ||
        detail.contains("MCP/GitHub", ignoreCase = true)
}

private fun parsePendingApprovalArgs(rawArgs: String) = runCatching {
    Json.parseToJsonElement(rawArgs).jsonObject
}.getOrNull()

private fun gitHubActionLabel(toolName: String): String {
    return when (toolName.lowercase()) {
        "create_repository" -> "创建仓库"
        "create_branch" -> "创建分支"
        "create_issue" -> "创建 Issue"
        "create_pull_request" -> "创建 Pull Request"
        "create_pull_request_review" -> "提交 Pull Request Review"
        "create_or_update_file" -> "写入远端文件"
        "push_files" -> "批量推送文件"
        "update_issue" -> "更新 Issue"
        "add_issue_comment" -> "添加 Issue 评论"
        "merge_pull_request" -> "合并 Pull Request"
        "update_pull_request_branch" -> "更新 Pull Request 分支"
        "fork_repository" -> "Fork 仓库"
        else -> "执行 GitHub 写操作"
    }
}

data class ProjectApprovalHistoryUi(
    val analyzedSessionCount: Int = 0,
    val sessionsWithApprovedScopes: Int = 0,
    val directApprovalSessionCount: Int = 0,
    val importedApprovalSessionCount: Int = 0,
    val autoInheritedApprovalSessionCount: Int = 0,
    val distinctScopeCount: Int = 0,
    val scopeTrends: List<ProjectApprovalScopeTrendUi> = emptyList()
)

data class ProjectApprovalScopeTrendUi(
    val id: String,
    val summary: String,
    val capabilities: List<String>,
    val sessionCount: Int,
    val directSessionCount: Int,
    val importedSessionCount: Int,
    val autoInheritedSessionCount: Int,
    val policyLabel: String,
    val policyDetail: String,
    val latestSourceSessionTitle: String,
    val latestSourceUpdatedAt: Long
)

private data class CompressionSummaryParts(
    val sessionTitle: String = "",
    val projectPath: String = "",
    val currentStage: String = "",
    val initialTask: String = "",
    val keyUserNeeds: List<String> = emptyList(),
    val findings: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    val continueRequirements: List<String> = emptyList()
)

private data class ClarificationFollowUpDecision(
    val action: AutoRouteAction,
    val question: String? = null
)

private data class ExecutionInterruptionDecision(
    val question: String,
    val reason: String? = null,
    val usedFallbackQuestion: Boolean = false
)

private data class AutoInheritedApprovalCandidate(
    val scope: Set<String>,
    val sourceSessionId: String,
    val sourceSessionTitle: String,
    val sourceUpdatedAt: Long
)

private data class ApprovalScopePolicyUi(
    val label: String,
    val detail: String,
    val autoInheritable: Boolean
)

private data class ApprovalScopeTrendAggregate(
    val tokens: Set<String>,
    val sessionIds: MutableSet<String> = linkedSetOf(),
    val directSessionIds: MutableSet<String> = linkedSetOf(),
    val importedSessionIds: MutableSet<String> = linkedSetOf(),
    val autoInheritedSessionIds: MutableSet<String> = linkedSetOf(),
    var latestSourceSessionTitle: String = "新对话",
    var latestSourceUpdatedAt: Long = 0L
)

private data class ApprovalScopeInvalidationReason(
    val label: String,
    val detail: String
)

/**
 * 会话状态
 */
data class SessionState(
    val messages: List<ChatMessageUi> = emptyList(),
    val subagentRuns: List<SubagentRunUi> = emptyList(),
    val subagentBatches: List<SubagentBatchUi> = emptyList(),
    val backgroundJobs: List<BackgroundJobUi> = emptyList(),
    val isProcessing: Boolean = false,
    val error: String? = null,
    val sessionId: String = "",
    val sessionTitle: String = "新对话",
    val sessionGoal: String? = null,
    val projectPath: String? = null,
    val remoteTaskRepositoryOwner: String? = null,
    val remoteTaskRepositoryName: String? = null,
    val remoteTaskRepositoryLabel: String? = null,
    val remoteTaskRepositoryEditable: Boolean = false,
    val activeProjectScopePath: String? = null,
    val projectRules: List<GlobalRule> = emptyList(),
    val projectMemories: List<GlobalMemory> = emptyList(),
    val projectSkills: List<GlobalSkill> = emptyList(),
    val projectKnowledgePaths: List<String> = emptyList(),
    val projectKnowledgeSnapshots: List<ProjectKnowledgeSnapshotUi> = emptyList(),
    val projectToolPreferences: ProjectToolPreferences? = null,
    val repoScopedConfigs: Map<String, SessionProjectConfig> = emptyMap(),
    val usageSummary: UsageSummarySnapshot = UsageSummarySnapshot(),
    val compressionSnapshot: ContextCompressionUi? = null,
    val compressionSnapshots: List<ContextCompressionUi> = emptyList(),
    val checkpoints: List<ConversationCheckpointUi> = emptyList(),
    val fileChanges: List<FileChangeRecordUi> = emptyList(),
    val recentErrors: List<ErrorRecordUi> = emptyList(),
    val recentToolCalls: List<ToolCallRecordUi> = emptyList(),
    val recentMemoryUpdateSuggestions: List<MemoryUpdateSuggestionUi> = emptyList(),
    val recentFinalReadinessAudits: List<FinalReadinessAuditRecord> = emptyList(),
    val recentApprovals: List<ApprovalRecordUi> = emptyList(),
    val recentRecoveryRecords: List<CheckpointRecoveryRecordUi> = emptyList(),
    val recentApprovalInvalidations: List<ApprovalInvalidationRecordUi> = emptyList(),
    val approvedApprovalScopes: List<ApprovedApprovalScopeUi> = emptyList(),
    val projectApprovalHistory: ProjectApprovalHistoryUi? = null,
    val projectInheritedApprovalScopes: List<InheritedApprovalScopeUi> = emptyList(),
    val pendingApproval: PendingApprovalUi? = null,
    val pendingAskRequest: PendingAskRequestUi? = null,
    val pendingWorkflowPlan: WorkflowPlanUi? = null,
    val canonicalWorkflowPlan: WorkflowPlanUi? = null,
    val workflowPlanningInProgress: Boolean = false,
    val pendingClarificationRequest: ClarificationRequestUi? = null,
    val clarificationInProgress: Boolean = false,
    val autoRoutingInProgress: Boolean = false,
    val lastAutoRouteDecision: AutoRouteDecisionUi? = null,
    val lastWorkflowFallback: WorkflowFallbackUi? = null,
    val lastFinalReadinessReceipt: FinalReadinessReceipt? = null
) {
    val recentSessionHistoryClue: SessionHistoryReferenceClueUi?
        get() = mergeSessionHistoryReferenceClues(
            pendingAskRequest?.recentSessionHistoryClue,
            pendingWorkflowPlan?.recentSessionHistoryClue,
            pendingClarificationRequest?.recentSessionHistoryClue,
            canonicalWorkflowPlan?.recentSessionHistoryClue,
            buildRecentSessionHistoryReferenceClue(recentToolCalls)
        )

    val recentSessionHistoryContext: String?
        get() = buildSessionHistoryReferenceContext(recentSessionHistoryClue)

    val recentSkillUsageClue: SkillUsageClueUi?
        get() = buildRecentSkillUsageClue(recentToolCalls)

    val recentSkillUsageContext: String?
        get() = buildSkillUsageContext(recentSkillUsageClue)

    val recentPendingMemoryUpdateSuggestionContext: String?
        get() = buildMemoryUpdateSuggestionContext(recentMemoryUpdateSuggestions)
}

data class PendingApprovalUi(
    val toolName: String,
    val summary: String,
    val detail: String,
    val rawArgs: String,
    val riskLevel: ApprovalRiskLevel,
    val explanationLabel: String? = null,
    val explanationDetail: String? = null,
    val isReplayOnly: Boolean = false,
    val replayNotice: String? = null
)

enum class ConversationExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String
) {
    MARKDOWN("md", "text/markdown", "Markdown"),
    JSON("json", "application/json", "JSON"),
    DOCTOR("md", "text/markdown", "Doctor Report")
}

data class ConversationExportData(
    val fileName: String,
    val mimeType: String,
    val content: String
)

fun estimateContextCompressionPreview(state: SessionState): ContextCompressionPreviewUi? {
    val eligibleMessages = compressionEligibleMessages(state)
    if (eligibleMessages.size < MIN_MESSAGES_FOR_COMPRESSION) return null

    val keepRecentCount = RECENT_MESSAGES_TO_KEEP
        .coerceAtMost(eligibleMessages.size - MIN_MESSAGES_TO_COMPRESS)
    val compressedMessages = eligibleMessages.dropLast(keepRecentCount)
    if (compressedMessages.size < MIN_MESSAGES_TO_COMPRESS) return null

    val recentMessages = eligibleMessages.takeLast(keepRecentCount)
    val estimatedCurrentContextTokens = estimateMessagesTokenCount(eligibleMessages)
    val estimatedRecentContextTokens = estimateMessagesTokenCount(recentMessages)
    val estimatedSummaryTokens = estimateSummaryTokenCount(compressedMessages)
    val estimatedCompressedContextTokens = estimatedRecentContextTokens + estimatedSummaryTokens
    val estimatedTokensSaved =
        (estimatedCurrentContextTokens - estimatedCompressedContextTokens).coerceAtLeast(0)
    val estimatedReductionPercent = if (estimatedCurrentContextTokens == 0) {
        0
    } else {
        ((estimatedTokensSaved.toFloat() / estimatedCurrentContextTokens) * 100)
            .roundToInt()
            .coerceIn(0, 95)
    }

    return ContextCompressionPreviewUi(
        compressibleMessageCount = compressedMessages.size,
        recentMessageCount = recentMessages.size,
        estimatedCurrentContextTokens = estimatedCurrentContextTokens,
        estimatedCompressedContextTokens = estimatedCompressedContextTokens,
        estimatedSummaryTokens = estimatedSummaryTokens,
        estimatedTokensSaved = estimatedTokensSaved,
        estimatedReductionPercent = estimatedReductionPercent
    )
}

internal fun hasPersistableSessionContent(state: SessionState): Boolean {
    return state.messages.isNotEmpty() ||
        !state.sessionGoal.isNullOrBlank() ||
        !state.projectPath.isNullOrBlank() ||
        !state.remoteTaskRepositoryOwner.isNullOrBlank() ||
        !state.remoteTaskRepositoryName.isNullOrBlank() ||
        state.projectRules.isNotEmpty() ||
        state.projectMemories.isNotEmpty() ||
        state.projectSkills.isNotEmpty() ||
        state.projectKnowledgePaths.isNotEmpty() ||
        state.projectToolPreferences != null ||
        state.repoScopedConfigs.isNotEmpty() ||
        state.pendingApproval != null ||
        state.pendingAskRequest != null ||
        state.pendingWorkflowPlan != null ||
        state.pendingClarificationRequest != null ||
        state.lastAutoRouteDecision != null ||
        state.lastWorkflowFallback != null ||
        state.subagentRuns.isNotEmpty() ||
        state.backgroundJobs.isNotEmpty() ||
        state.compressionSnapshot != null ||
        state.compressionSnapshots.isNotEmpty() ||
        state.checkpoints.isNotEmpty() ||
        state.fileChanges.isNotEmpty() ||
        state.recentErrors.isNotEmpty() ||
        state.recentToolCalls.isNotEmpty() ||
        state.recentFinalReadinessAudits.isNotEmpty() ||
        state.recentApprovals.isNotEmpty() ||
        state.recentRecoveryRecords.isNotEmpty()
}

internal fun canExportSession(
    state: SessionState,
    format: ConversationExportFormat
): Boolean {
    return format == ConversationExportFormat.DOCTOR || hasPersistableSessionContent(state)
}

internal fun isFinalReadinessErrorRecord(record: ErrorRecordUi): Boolean {
    return record.kind == ErrorRecordKind.FINAL_READINESS ||
        record.message.contains("最终收口") ||
        record.message.contains("Final Readiness", ignoreCase = true)
}

internal fun clearRecoveredFinalReadinessState(
    state: SessionState,
    audit: FinalReadinessAuditRecord
): SessionState {
    if (audit.result != FinalReadinessAuditResult.ALLOWED || !audit.recovered) {
        return state
    }
    return state.copy(
        recentErrors = state.recentErrors.filterNot(::isFinalReadinessErrorRecord),
        lastFinalReadinessReceipt = null
    )
}

internal fun normalizeRecoveredFinalReadinessState(state: SessionState): SessionState {
    val latestAudit = state.recentFinalReadinessAudits.firstOrNull() ?: return state
    return clearRecoveredFinalReadinessState(state, latestAudit)
}

internal data class TurnCheckpointCaptureState(
    val checkpointId: String? = null,
    val nextRecordOrdinal: Int = 0,
    val toolNames: Set<String> = emptySet(),
    val changedFiles: List<String> = emptyList(),
    val beforeContentByPath: Map<String, String?> = emptyMap()
)

internal data class TurnCheckpointCaptureUpdate(
    val nextState: TurnCheckpointCaptureState,
    val records: List<FileChangeRecordUi>,
    val checkpoint: ConversationCheckpointUi
)

internal fun captureTurnCheckpointFileChanges(
    captureState: TurnCheckpointCaptureState,
    toolName: String,
    fileChanges: List<ToolFileChange>,
    existingCheckpoint: ConversationCheckpointUi?,
    messageIndex: Int,
    checkpointIdFactory: () -> String
): TurnCheckpointCaptureUpdate? {
    if (fileChanges.isEmpty()) return null
    val checkpointId = captureState.checkpointId ?: checkpointIdFactory()
    val nextBeforeContentByPath = captureState.beforeContentByPath.toMutableMap()
    val records = fileChanges.mapIndexed { index, change ->
        val baselineBeforeContent = nextBeforeContentByPath.getOrPut(change.path) { change.beforeContent }
        FileChangeRecordUi(
            id = "$checkpointId-${captureState.nextRecordOrdinal + index}",
            path = change.path,
            operation = change.operation,
            beforeContent = baselineBeforeContent,
            afterContent = change.afterContent,
            diffPreview = change.diffPreview,
            changedAt = change.changedAt,
            checkpointId = checkpointId
        )
    }
    val nextChangedFiles = LinkedHashSet(captureState.changedFiles).apply {
        fileChanges.forEach { add(it.path) }
    }.toList()
    val nextToolNames = LinkedHashSet(captureState.toolNames).apply { add(toolName) }
    val checkpoint = ConversationCheckpointUi(
        id = checkpointId,
        messageIndex = messageIndex,
        createdAt = maxOf(
            existingCheckpoint?.createdAt ?: 0L,
            records.maxOfOrNull { it.changedAt } ?: System.currentTimeMillis()
        ),
        summary = buildCheckpointSummary(nextToolNames, nextChangedFiles),
        changedFiles = nextChangedFiles,
        kind = ConversationCheckpointKind.FILE_TURN,
        scope = ConversationCheckpointScope.CODE,
        source = ConversationCheckpointSource.TOOL_EXECUTION,
        toolNames = nextToolNames.toList()
    )
    return TurnCheckpointCaptureUpdate(
        nextState = TurnCheckpointCaptureState(
            checkpointId = checkpointId,
            nextRecordOrdinal = captureState.nextRecordOrdinal + records.size,
            toolNames = nextToolNames,
            changedFiles = nextChangedFiles,
            beforeContentByPath = nextBeforeContentByPath
        ),
        records = records,
        checkpoint = checkpoint
    )
}

internal fun upsertCheckpointHistory(
    checkpoints: List<ConversationCheckpointUi>,
    checkpoint: ConversationCheckpointUi,
    maxSize: Int
): List<ConversationCheckpointUi> {
    return (listOf(checkpoint) + checkpoints.filterNot { it.id == checkpoint.id })
        .sortedByDescending { it.createdAt }
        .take(maxSize)
}

internal fun resolveCheckpointRollbackRecords(
    records: List<FileChangeRecordUi>
): List<FileChangeRecordUi> {
    return records.sortedByDescending { it.changedAt }
}

internal fun buildCheckpointSummary(
    toolNames: Set<String>,
    changedFiles: List<String>
): String {
    val paths = changedFiles.map { it.substringAfterLast('/').substringAfterLast('\\') }
    val toolLabel = when (toolNames.size) {
        0 -> "工具"
        1 -> toolNames.first()
        else -> "${toolNames.joinToString("/")} 等"
    }
    return "$toolLabel 修改了 ${changedFiles.size} 个文件: ${paths.take(3).joinToString("、")}" +
        if (paths.size > 3) " 等" else ""
}

internal fun formatCheckpointScopeLabel(scope: ConversationCheckpointScope): String {
    return when (scope) {
        ConversationCheckpointScope.CODE -> "代码恢复"
        ConversationCheckpointScope.CONVERSATION -> "对话恢复"
        ConversationCheckpointScope.BOTH -> "代码/对话"
    }
}

internal data class CheckpointRecoveryPlan(
    val targetExclusiveIndex: Int,
    val checkpointRecords: List<FileChangeRecordUi>,
    val futureRecords: List<FileChangeRecordUi>
)

internal fun formatCheckpointRecoveryScopeMessage(
    checkpointScope: ConversationCheckpointScope,
    requestedScope: ConversationCheckpointScope
): String {
    return when (requestedScope) {
        ConversationCheckpointScope.CODE ->
            "该检查点只有对话可恢复，不能回滚代码"

        ConversationCheckpointScope.CONVERSATION ->
            "该检查点只有代码可恢复，不能恢复对话"

        ConversationCheckpointScope.BOTH ->
            "该检查点只支持 ${formatCheckpointScopeLabel(checkpointScope)}，不能同时恢复代码和对话"
    }
}

internal fun collectFutureCheckpointRollbackRecords(
    state: SessionState,
    targetExclusiveIndex: Int
): List<FileChangeRecordUi> {
    val checkpointsToRollback = state.checkpoints
        .filter { it.messageIndex >= targetExclusiveIndex }
        .sortedByDescending { it.createdAt }
    return checkpointsToRollback.flatMap { checkpoint ->
        state.fileChanges
            .filter { it.checkpointId == checkpoint.id }
            .sortedByDescending { it.changedAt }
    }
}

internal fun resolveCheckpointRecoveryPlan(
    state: SessionState,
    checkpoint: ConversationCheckpointUi,
    requestedScope: ConversationCheckpointScope
): CheckpointRecoveryPlan {
    val scopeSupported = when (requestedScope) {
        ConversationCheckpointScope.CODE ->
            checkpoint.scope == ConversationCheckpointScope.CODE ||
                checkpoint.scope == ConversationCheckpointScope.BOTH

        ConversationCheckpointScope.CONVERSATION ->
            checkpoint.scope == ConversationCheckpointScope.CONVERSATION ||
                checkpoint.scope == ConversationCheckpointScope.BOTH

        ConversationCheckpointScope.BOTH ->
            checkpoint.scope == ConversationCheckpointScope.BOTH
    }
    require(scopeSupported) {
        formatCheckpointRecoveryScopeMessage(
            checkpointScope = checkpoint.scope,
            requestedScope = requestedScope
        )
    }

    val targetExclusiveIndex = (checkpoint.messageIndex + 1).coerceIn(0, state.messages.size)
    val checkpointRecords = resolveCheckpointRollbackRecords(
        state.fileChanges.filter { it.checkpointId == checkpoint.id }
    )
    val futureRecords = collectFutureCheckpointRollbackRecords(
        state = state,
        targetExclusiveIndex = targetExclusiveIndex
    )

    when (requestedScope) {
        ConversationCheckpointScope.CODE ->
            require(checkpointRecords.isNotEmpty()) { "该检查点没有可回滚的代码修改" }

        ConversationCheckpointScope.CONVERSATION ->
            require(
                targetExclusiveIndex < state.messages.size ||
                    hasCheckpointPromptSnapshotDifference(state, checkpoint)
            ) {
                "该检查点已经是当前对话边界，无需恢复"
            }

        ConversationCheckpointScope.BOTH ->
            require(
                targetExclusiveIndex < state.messages.size ||
                    futureRecords.isNotEmpty() ||
                    hasCheckpointPromptSnapshotDifference(state, checkpoint)
            ) {
                "该检查点之后没有新的对话或文件变更，无需恢复"
            }
    }

    return CheckpointRecoveryPlan(
        targetExclusiveIndex = targetExclusiveIndex,
        checkpointRecords = checkpointRecords,
        futureRecords = futureRecords
    )
}

internal fun buildCheckpointRecoverySummary(
    checkpoint: ConversationCheckpointUi,
    restoredScope: ConversationCheckpointScope,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null
): String {
    val summary = when (restoredScope) {
        ConversationCheckpointScope.CODE -> "回滚代码: ${checkpoint.summary}"
        ConversationCheckpointScope.CONVERSATION -> "恢复对话: ${checkpoint.summary}"
        ConversationCheckpointScope.BOTH -> "恢复代码/对话: ${checkpoint.summary}"
    }
    val historySuffix = buildSessionHistoryReferenceInlineSuffix(recentSessionHistoryClue)
    return historySuffix?.let { "$summary（$it）" } ?: summary
}

internal fun buildCheckpointRecoverySystemMessage(
    checkpoint: ConversationCheckpointUi,
    restoredScope: ConversationCheckpointScope,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null
): String {
    val message = when (restoredScope) {
        ConversationCheckpointScope.CODE -> "已按检查点回滚代码：${checkpoint.summary}"
        ConversationCheckpointScope.CONVERSATION -> "已按检查点恢复对话：${checkpoint.summary}"
        ConversationCheckpointScope.BOTH -> "已按检查点恢复代码与对话：${checkpoint.summary}"
    }
    val historySuffix = buildSessionHistoryReferenceInlineSuffix(recentSessionHistoryClue)
    return historySuffix?.let { "$message（$it）" } ?: message
}

internal fun buildCheckpointRecoveryEvent(
    rollbackCheckpointId: String,
    checkpoint: ConversationCheckpointUi,
    restoredScope: ConversationCheckpointScope,
    messageIndex: Int,
    createdAt: Long,
    changedFiles: List<String>,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null
): ConversationCheckpointUi {
    return ConversationCheckpointUi(
        id = rollbackCheckpointId,
        messageIndex = messageIndex,
        createdAt = createdAt,
        summary = buildCheckpointRecoverySummary(
            checkpoint = checkpoint,
            restoredScope = restoredScope,
            recentSessionHistoryClue = recentSessionHistoryClue
        ),
        changedFiles = changedFiles,
        kind = ConversationCheckpointKind.ROLLBACK,
        scope = restoredScope,
        source = ConversationCheckpointSource.ROLLBACK,
        toolNames = listOf("rollback")
    )
}

internal fun buildCheckpointRecoveryRecord(
    checkpoint: ConversationCheckpointUi,
    restoredScope: ConversationCheckpointScope,
    restoredFileCount: Int,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null,
    timestamp: Long = System.currentTimeMillis()
): CheckpointRecoveryRecordUi {
    return CheckpointRecoveryRecordUi(
        checkpointId = checkpoint.id,
        checkpointSummary = buildCheckpointRecoverySummary(
            checkpoint = checkpoint,
            restoredScope = restoredScope,
            recentSessionHistoryClue = recentSessionHistoryClue
        ),
        scope = restoredScope,
        restoredFileCount = restoredFileCount,
        targetMessageIndex = checkpoint.messageIndex,
        timestamp = timestamp
    )
}

internal data class ConversationRollbackProjection(
    val messages: List<ChatMessageUi>,
    val subagentRuns: List<SubagentRunUi>,
    val subagentBatches: List<SubagentBatchUi>,
    val compressionSnapshot: ContextCompressionUi?,
    val compressionSnapshots: List<ContextCompressionUi>,
    val checkpoints: List<ConversationCheckpointUi>,
    val fileChanges: List<FileChangeRecordUi>,
    val pendingAskRequest: PendingAskRequestUi?,
    val pendingWorkflowPlan: WorkflowPlanUi?,
    val canonicalWorkflowPlan: WorkflowPlanUi?,
    val pendingClarificationRequest: ClarificationRequestUi?,
    val workflowPlanningInProgress: Boolean = false,
    val clarificationInProgress: Boolean = false
)

internal fun resolveCompressionSnapshotsAfterRollback(
    snapshots: List<ContextCompressionUi>,
    targetExclusiveIndex: Int
): List<ContextCompressionUi> {
    val remaining = snapshots
        .filter { it.sourceEndMessageIndex < targetExclusiveIndex }
        .sortedBy { it.version }
    val reactivatedId = remaining.maxByOrNull { it.version }?.id
    return remaining.map { snapshot ->
        snapshot.copy(active = snapshot.id == reactivatedId)
    }
}

internal fun projectConversationRollbackState(
    state: SessionState,
    targetExclusiveIndex: Int,
    targetCheckpoint: ConversationCheckpointUi? = null
): ConversationRollbackProjection {
    require(targetExclusiveIndex in 0..state.messages.size) {
        "无效的回退目标位置: $targetExclusiveIndex"
    }
    val keptMessages = state.messages.take(targetExclusiveIndex)
    val effectiveTargetCheckpoint = targetCheckpoint ?: state.checkpoints
        .filter { it.messageIndex < targetExclusiveIndex }
        .maxByOrNull { it.createdAt }
    val keptCheckpoints = state.checkpoints
        .filter { checkpoint ->
            when {
                effectiveTargetCheckpoint == null -> checkpoint.messageIndex < targetExclusiveIndex
                checkpoint.messageIndex < effectiveTargetCheckpoint.messageIndex -> true
                checkpoint.messageIndex > effectiveTargetCheckpoint.messageIndex -> false
                checkpoint.createdAt < effectiveTargetCheckpoint.createdAt -> true
                checkpoint.createdAt > effectiveTargetCheckpoint.createdAt -> false
                else -> checkpoint.id == effectiveTargetCheckpoint.id
            }
        }
        .sortedByDescending { it.createdAt }
    val keptCheckpointIds = keptCheckpoints.map { it.id }.toSet()
    val keptFileChanges = state.fileChanges.filter { change ->
        change.checkpointId == null || change.checkpointId in keptCheckpointIds
    }
    val keptCompressionSnapshots = resolveCompressionSnapshotsAfterRollback(
        snapshots = state.compressionSnapshots,
        targetExclusiveIndex = targetExclusiveIndex
    )
    val currentCompressionSnapshot = keptCompressionSnapshots.firstOrNull { it.active }
    val keptSubagentRunIds = keptMessages.mapNotNull { it.subagentRunId }.toSet()
    val keptSubagentBatchIds = keptMessages.mapNotNull { it.subagentBatchId }.toSet()
    val promptSnapshot = effectiveTargetCheckpoint?.promptSnapshot
    return ConversationRollbackProjection(
        messages = keptMessages,
        subagentRuns = state.subagentRuns.filter { it.runId in keptSubagentRunIds },
        subagentBatches = state.subagentBatches.filter { it.batchId in keptSubagentBatchIds },
        compressionSnapshot = currentCompressionSnapshot,
        compressionSnapshots = keptCompressionSnapshots,
        checkpoints = keptCheckpoints,
        fileChanges = keptFileChanges,
        pendingAskRequest = markPendingAskReplayOnly(
            request = promptSnapshot?.pendingAskRequest,
            recentSessionHistoryClue = state.recentSessionHistoryClue
        ),
        pendingWorkflowPlan = promptSnapshot?.pendingWorkflowPlan,
        canonicalWorkflowPlan = promptSnapshot?.canonicalWorkflowPlan,
        pendingClarificationRequest = promptSnapshot?.pendingClarificationRequest,
        workflowPlanningInProgress = false,
        clarificationInProgress = false
    )
}

internal fun buildConversationCheckpointPromptSnapshot(
    state: SessionState
): ConversationCheckpointPromptSnapshotUi? {
    val normalizedAskRequest = state.pendingAskRequest?.copy(
        isReplayOnly = false,
        replayNotice = null
    )
    if (
        normalizedAskRequest == null &&
        state.pendingWorkflowPlan == null &&
        state.canonicalWorkflowPlan == null &&
        state.pendingClarificationRequest == null
    ) {
        return null
    }
    return ConversationCheckpointPromptSnapshotUi(
        pendingAskRequest = normalizedAskRequest,
        pendingWorkflowPlan = state.pendingWorkflowPlan,
        canonicalWorkflowPlan = state.canonicalWorkflowPlan,
        pendingClarificationRequest = state.pendingClarificationRequest
    )
}

internal fun hasCheckpointPromptSnapshotDifference(
    state: SessionState,
    checkpoint: ConversationCheckpointUi
): Boolean {
    return buildConversationCheckpointPromptSnapshot(state) != checkpoint.promptSnapshot
}

internal fun buildConversationCheckpointSummary(
    messages: List<ChatMessageUi>,
    messageIndex: Int
): String {
    val preview = messages
        .take((messageIndex + 1).coerceAtMost(messages.size))
        .asReversed()
        .firstOrNull { it.role == "assistant" && it.content.isNotBlank() }
        ?.content
        ?.replace('\n', ' ')
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(24)
    return if (preview.isNullOrBlank()) {
        "对话推进检查点"
    } else {
        "对话推进: $preview"
    }
}

internal fun finalizeTurnCheckpoint(
    captureState: TurnCheckpointCaptureState,
    checkpoints: List<ConversationCheckpointUi>,
    messages: List<ChatMessageUi>,
    checkpointIdFactory: () -> String
): ConversationCheckpointUi? {
    val finalMessageIndex = messages.lastIndex
    if (finalMessageIndex < 0) return null
    val existingCheckpoint = captureState.checkpointId?.let { checkpointId ->
        checkpoints.firstOrNull { it.id == checkpointId }
    }
    return if (existingCheckpoint != null) {
        existingCheckpoint.copy(
            messageIndex = finalMessageIndex,
            scope = ConversationCheckpointScope.BOTH
        )
    } else {
        ConversationCheckpointUi(
            id = checkpointIdFactory(),
            messageIndex = finalMessageIndex,
            createdAt = messages.lastOrNull()?.timestamp ?: System.currentTimeMillis(),
            summary = buildConversationCheckpointSummary(messages, finalMessageIndex),
            kind = ConversationCheckpointKind.FILE_TURN,
            scope = ConversationCheckpointScope.CONVERSATION,
            source = ConversationCheckpointSource.TOOL_EXECUTION
        )
    }
}

internal fun buildFinalReadinessContinuationContext(
    audits: List<FinalReadinessAuditRecord>,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null
): String? {
    val canonicalAudits = audits.filter {
        it.receiptKind == FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW
    }
    val summary = buildLatestFinalReadinessAuditSummary(canonicalAudits) ?: return null
    val historyReferenceSummary = buildSessionHistoryReferenceContext(
        mergeSessionHistoryReferenceClues(
            buildLatestFinalReadinessHistoryReferenceClue(canonicalAudits),
            recentSessionHistoryClue
        )
    )
    return buildString {
        append("最近一次最终收口状态：")
        append(summary)
        append("。")
        historyReferenceSummary?.let {
            append(it)
        }
        append("继续执行时请保持 `complete_step` 与计划签收闭环，避免再次在最终收口被阻塞。")
    }
}

internal fun buildLatestFinalReadinessHistoryReferenceClue(
    audits: List<FinalReadinessAuditRecord>
): SessionHistoryReferenceClueUi? {
    val latestAudit = audits.firstOrNull()
        ?.takeIf { audit -> audit.result == FinalReadinessAuditResult.BLOCKED || audit.recovered }
        ?: return null
    val sessionIds = latestAudit.latestSignedOffSessionHistorySessionIds.distinct()
    val messageReferences = latestAudit.latestSignedOffSessionHistoryMessageReferences.distinct()
    if (sessionIds.isEmpty() && messageReferences.isEmpty()) return null
    return SessionHistoryReferenceClueUi(
        sessionIds = sessionIds,
        messageReferences = messageReferences
    )
}

internal fun buildRecentSessionHistoryReferenceContext(
    recentToolCalls: List<ToolCallRecordUi>
): String? {
    return buildSessionHistoryReferenceContext(
        buildRecentSessionHistoryReferenceClue(recentToolCalls)
    )
}

internal fun buildRecentSessionHistoryReferenceClue(
    recentToolCalls: List<ToolCallRecordUi>
): SessionHistoryReferenceClueUi? {
    val recentHistoryPayloads = recentToolCalls.asSequence()
        .filter { it.isSuccess }
        .mapNotNull { it.structuredPayload?.sessionHistory }
        .take(5)
        .toList()
    if (recentHistoryPayloads.isEmpty()) return null
    val queries = recentHistoryPayloads.mapNotNull { payload ->
        payload.query?.trim()?.takeIf { it.isNotBlank() }
    }.distinct()
    val sessionIds = recentHistoryPayloads.flatMap { it.sessionIds }.distinct()
    val messageReferences = recentHistoryPayloads.flatMap { it.messageReferences }.distinct()
    val snippets = recentHistoryPayloads.flatMap { it.snippets }.distinct()
    val excerptWindows = recentHistoryPayloads.flatMap { it.excerptWindows }.distinct()
    if (
        queries.isEmpty() &&
        sessionIds.isEmpty() &&
        messageReferences.isEmpty() &&
        snippets.isEmpty() &&
        excerptWindows.isEmpty()
    ) {
        return null
    }
    return SessionHistoryReferenceClueUi(
        queries = queries,
        sessionIds = sessionIds,
        messageReferences = messageReferences,
        snippets = snippets,
        excerptWindows = excerptWindows
    )
}

internal fun buildRecentSkillUsageClue(
    recentToolCalls: List<ToolCallRecordUi>
): SkillUsageClueUi? {
    val recentSkillPayloads = recentToolCalls.asSequence()
        .filter { it.isSuccess }
        .mapNotNull { it.structuredPayload?.skill }
        .take(5)
        .toList()
    if (recentSkillPayloads.isEmpty()) return null
    val skillTitles = recentSkillPayloads.mapNotNull { payload ->
        payload.skillTitle?.trim()?.takeIf { it.isNotBlank() }
    }.distinct()
    val queries = recentSkillPayloads.mapNotNull { payload ->
        payload.query?.trim()?.takeIf { it.isNotBlank() }
    }.distinct()
    val tasks = recentSkillPayloads.mapNotNull { payload ->
        payload.task?.trim()?.takeIf { it.isNotBlank() }
    }.distinct()
    val runModes = recentSkillPayloads.mapNotNull { payload ->
        payload.runAs?.trim()?.takeIf { it.isNotBlank() }
    }.distinct()
    val delegatedSkillTitles = recentSkillPayloads
        .filter { it.delegatedToSubagent }
        .mapNotNull { payload ->
            payload.skillTitle?.trim()?.takeIf { it.isNotBlank() }
        }
        .distinct()
    if (
        skillTitles.isEmpty() &&
        queries.isEmpty() &&
        tasks.isEmpty() &&
        runModes.isEmpty() &&
        delegatedSkillTitles.isEmpty()
    ) {
        return null
    }
    return SkillUsageClueUi(
        skillTitles = skillTitles,
        queries = queries,
        tasks = tasks,
        runModes = runModes,
        delegatedSkillTitles = delegatedSkillTitles
    )
}

internal fun buildSkillUsageContext(
    clue: SkillUsageClueUi?
): String? {
    clue ?: return null
    if (
        clue.skillTitles.isEmpty() &&
        clue.queries.isEmpty() &&
        clue.tasks.isEmpty() &&
        clue.runModes.isEmpty() &&
        clue.delegatedSkillTitles.isEmpty()
    ) {
        return null
    }
    return buildString {
        append("最近已调用过 Skill 能力：")
        val segments = buildList {
            if (clue.skillTitles.isNotEmpty()) {
                add("最近 Skill：${clue.skillTitles.toSessionHistoryReferencePreviewList()}")
            }
            if (clue.tasks.isNotEmpty()) {
                add("最近 Skill 任务：${clue.tasks.toSessionHistoryReferencePreviewList()}")
            }
            if (clue.runModes.isNotEmpty()) {
                add("执行模式：${clue.runModes.toSessionHistoryReferencePreviewList()}")
            }
            if (clue.delegatedSkillTitles.isNotEmpty()) {
                add("已委派子代理：${clue.delegatedSkillTitles.toSessionHistoryReferencePreviewList()}")
            }
            if (clue.queries.isNotEmpty()) {
                add("原始匹配查询：${clue.queries.toSessionHistoryReferencePreviewList()}")
            }
        }
        append(segments.joinToString("；"))
        append("。如当前请求仍沿同一能力推进，优先复用这些 Skill，而不是重复检索或改换执行模式。")
    }
}

internal fun mergeSessionHistoryReferenceClues(
    vararg clues: SessionHistoryReferenceClueUi?
): SessionHistoryReferenceClueUi? {
    val mergedClues = clues.filterNotNull()
    if (mergedClues.isEmpty()) return null
    val queries = mergedClues.flatMap { it.queries }.distinct()
    val sessionIds = mergedClues.flatMap { it.sessionIds }.distinct()
    val messageReferences = mergedClues.flatMap { it.messageReferences }.distinct()
    val snippets = mergedClues.flatMap { it.snippets }.distinct()
    val excerptWindows = mergedClues.flatMap { it.excerptWindows }.distinct()
    if (
        queries.isEmpty() &&
        sessionIds.isEmpty() &&
        messageReferences.isEmpty() &&
        snippets.isEmpty() &&
        excerptWindows.isEmpty()
    ) {
        return null
    }
    return SessionHistoryReferenceClueUi(
        queries = queries,
        sessionIds = sessionIds,
        messageReferences = messageReferences,
        snippets = snippets,
        excerptWindows = excerptWindows
    )
}

internal fun buildSessionHistoryReferenceContext(
    clue: SessionHistoryReferenceClueUi?
): String? {
    clue ?: return null
    if (
        clue.queries.isEmpty() &&
        clue.sessionIds.isEmpty() &&
        clue.messageReferences.isEmpty() &&
        clue.snippets.isEmpty() &&
        clue.excerptWindows.isEmpty()
    ) {
        return null
    }
    return buildString {
        append("最近已引用过会话历史检索结果：")
        val segments = buildList {
            if (clue.queries.isNotEmpty()) {
                add("最近查询：${clue.queries.toSessionHistoryReferencePreviewList()}")
            }
            if (clue.sessionIds.isNotEmpty()) {
                add("最近命中的历史会话：${clue.sessionIds.toSessionHistoryReferencePreviewList()}")
            }
            if (clue.messageReferences.isNotEmpty()) {
                add("最近命中的历史消息：${clue.messageReferences.toSessionHistoryReferencePreviewList()}")
            }
            if (clue.snippets.isNotEmpty()) {
                add("最近历史线索摘要：${clue.snippets.toSessionHistoryReferencePreviewList()}")
            }
            if (clue.excerptWindows.isNotEmpty()) {
                add("最近摘录窗口：${clue.excerptWindows.toSessionHistoryReferencePreviewList()}")
            }
        }
        append(segments.joinToString("；"))
        append("。如需继续沿这些历史线索推进，优先复用已有 `session_id/message_reference`，避免重复全文搜索。")
    }
}

internal fun buildSessionHistoryReferenceInlineSuffix(
    clue: SessionHistoryReferenceClueUi?
): String? {
    clue ?: return null
    val segments = buildList {
        if (clue.queries.isNotEmpty()) {
            add("查询 ${clue.queries.toSessionHistoryReferencePreviewList()}")
        }
        if (clue.messageReferences.isNotEmpty()) {
            add("消息 ${clue.messageReferences.toSessionHistoryReferencePreviewList()}")
        } else if (clue.sessionIds.isNotEmpty()) {
            add("会话 ${clue.sessionIds.toSessionHistoryReferencePreviewList()}")
        }
        if (clue.snippets.isNotEmpty()) {
            add("摘要 ${clue.snippets.toSessionHistoryReferencePreviewList()}")
        }
    }.take(2)
    return segments.takeIf { it.isNotEmpty() }
        ?.joinToString("；", prefix = "沿用历史线索：")
}

internal fun buildTurnScopedAuxiliaryUserContext(
    recentToolCalls: List<ToolCallRecordUi>,
    mentionedFilesContext: String?,
    extraContext: String? = null
): String? {
    return buildTurnScopedAuxiliaryUserContext(
        recentSessionHistoryClue = buildRecentSessionHistoryReferenceClue(recentToolCalls),
        mentionedFilesContext = mentionedFilesContext,
        extraContext = extraContext
    )
}

internal fun buildTurnScopedAuxiliaryUserContext(
    recentSessionHistoryClue: SessionHistoryReferenceClueUi?,
    mentionedFilesContext: String?,
    extraContext: String? = null
): String? {
    return listOfNotNull(
        buildSessionHistoryReferenceContext(recentSessionHistoryClue),
        mentionedFilesContext?.trim()?.takeIf { it.isNotBlank() },
        extraContext?.trim()?.takeIf { it.isNotBlank() }
    ).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

internal fun buildControllerSessionHistoryContext(
    state: SessionState,
    localClue: SessionHistoryReferenceClueUi? = null
): String? {
    return buildSessionHistoryReferenceContext(
        mergeSessionHistoryReferenceClues(
            state.recentSessionHistoryClue,
            localClue
        )
    )
}

internal fun buildStateAwarePendingAskRequest(
    request: PendingAskRequestUi,
    state: SessionState,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null
): PendingAskRequestUi {
    return request.copy(
        recentSessionHistoryClue = mergeSessionHistoryReferenceClues(
            state.recentSessionHistoryClue,
            request.recentSessionHistoryClue,
            recentSessionHistoryClue
        )
    )
}

internal fun buildDefaultLocalFallbackClarificationQuestion(
    goal: String,
    previousAnswers: List<ClarificationAnswerUi>
): String {
    return when {
        previousAnswers.isNotEmpty() ->
            "为了继续准确执行，请再补充目前最关键的限制条件、优先级或预期结果。"
        goal.isNotBlank() ->
            "为了避免做错，请先补充这个任务最关键的限制条件、目标范围或预期结果。"
        else ->
            "为了继续准确执行，请先补充当前任务最关键的限制条件或预期结果。"
    }
}

internal fun buildStateAwareLocalFallbackClarificationRequest(
    goal: String,
    mentionedFiles: List<FileMentionUi>,
    previousAnswers: List<ClarificationAnswerUi>,
    turnIndex: Int,
    maxTurns: Int,
    source: ClarificationSource,
    state: SessionState,
    recentSessionHistoryClue: SessionHistoryReferenceClueUi? = null
): ClarificationRequestUi {
    return ClarificationRequestUi(
        goal = goal,
        question = buildDefaultLocalFallbackClarificationQuestion(
            goal = goal,
            previousAnswers = previousAnswers
        ),
        mentionedFiles = mentionedFiles.distinctBy { it.path },
        previousAnswers = previousAnswers,
        turnIndex = turnIndex,
        maxTurns = maxTurns,
        source = source,
        recentSessionHistoryClue = mergeSessionHistoryReferenceClues(
            state.recentSessionHistoryClue,
            recentSessionHistoryClue
        )
    )
}

private fun List<String>.toSessionHistoryReferencePreviewList(limit: Int = 3): String {
    val preview = take(limit)
    val remainingCount = (size - preview.size).coerceAtLeast(0)
    return buildString {
        append(preview.joinToString("、"))
        if (remainingCount > 0) {
            append(" 等 ")
            append(size)
            append(" 条")
        }
    }
}

/**
 * 会话管理器——管理对话生命周期 + 持久化 + MCP
 */
class ChatSessionManager(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val mcpRegistry: McpRegistry? = null,
    private val hookBus: HookBusRunner = HookBusRunner()
) {
    private companion object {
        val SUBAGENT_TERMINAL_STATUSES = setOf("completed", "failed", "cancelled", "rejected")
        val SUBAGENT_ACTIVE_STATUSES = setOf("pending_approval", "queued", "running", "summarizing", "cancelling")
        val READ_ONLY_FILE_OPERATIONS = setOf("read", "list", "exists")
        val WRITE_LIKE_FILE_OPERATIONS = setOf("write", "delete", "chmod")
        val READ_ONLY_PLAN_MODE_BLOCKED_TOOL_NAMES = setOf(
            "task_repo_create_branch",
            "task_repo_create_pr",
            "task_repo_close_pr",
            "task_repo_delete_branch",
            "task_repo_search_replace",
            "task_repo_apply_patch",
            "task_repo_update_file",
            "task_repo_delete_file",
            "task_repo_commit_files"
        )
        val AUTO_COMPLEXITY_KEYWORDS = listOf(
            "自动", "下载", "接入", "集成", "重构", "架构", "规划", "计划", "迁移",
            "批量", "排查", "调试", "分析", "审查", "搜索", "联网", "修复", "实现",
            "mcp", "skill", "workflow", "subagent", "review", "debug", "refactor"
        )
        val AUTO_DISCOVERY_ACTION_KEYWORDS = listOf(
            "安装", "接入", "添加", "导入", "配置", "启用", "连接", "加载",
            "下载", "搜索", "查找", "接好", "接上", "install", "import",
            "add", "connect", "enable", "setup", "configure", "find"
        )
        val AUTO_DISCOVERY_TARGET_KEYWORDS = listOf(
            "mcp", "skill", "server", "工具", "插件", "tool", "prompt"
        )
    }

    private data class ToolHistoryPruningPlan(
        val keptIndices: Set<Int>,
        val removedMessages: List<ChatMessageUi>
    )

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private val _pendingPromptReplayNotices = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pendingPromptReplayNotices: SharedFlow<String> = _pendingPromptReplayNotices.asSharedFlow()
    private val _sessionLifecycleNotices = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val sessionLifecycleNotices: SharedFlow<String> = _sessionLifecycleNotices.asSharedFlow()

    private val conversationStore = ConversationStore(context)
    private var messageCounter = 0L
    private var agentLoop: AgentLoop? = null
    private var currentStreamingId: Long? = null
    private var currentSessionId: String = ""
    private var lastSessionConfig: ProviderConfig = ProviderConfig()
    private var currentTurnCheckpointCaptureState: TurnCheckpointCaptureState = TurnCheckpointCaptureState()
    private var pendingApprovalDecision: CompletableDeferred<Boolean>? = null
    private var pendingAskDecision: CompletableDeferred<List<AskAnswerUi>?>? = null
    private var processingCancelledByUser: Boolean = false
    private val approvedApprovalScopes = mutableListOf<PersistedApprovedApprovalScope>()
    private val cancelledSubagentRunIds = mutableSetOf<String>()
    private val subagentExecutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundJobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streamingAggregationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val maxConcurrentSubagentExecutions = 2
    private val maxConcurrentBackgroundJobs = 2
    private val pendingStreamingLock = Any()
    private val pendingStreamingContent = StringBuilder()
    private val pendingStreamingReasoning = StringBuilder()
    @Volatile
    private var latestPersistJob: Job? = null
    @Volatile
    private var cachedCurrentPersistedSession: PersistedSession? = null
    @Volatile
    private var pendingStreamingMessageId: Long? = null
    private var subagentSchedulerJobs: List<BackgroundJobUi> = emptyList()
    private val backgroundJobsManager = SessionBackgroundJobsManager(
        scope = backgroundJobScope,
        maxConcurrentJobs = maxConcurrentBackgroundJobs,
        currentJobsProvider = { _state.value.backgroundJobs },
        onJobsUpdated = { jobs ->
            _state.value = _state.value.copy(backgroundJobs = jobs)
        },
        onPersistRequested = ::saveCurrentSession,
        onJobCompleted = ::appendBackgroundJobCompletionNotice
    )
    private val subagentBackgroundJobsManager = SessionBackgroundJobsManager(
        scope = subagentExecutionScope,
        maxConcurrentJobs = maxConcurrentSubagentExecutions,
        currentJobsProvider = { subagentSchedulerJobs },
        onJobsUpdated = { jobs ->
            subagentSchedulerJobs = jobs
            refreshBackgroundExecutionState(jobs)
        },
        onPersistRequested = ::saveCurrentSession,
        onJobCompleted = { completedJob ->
            subagentSchedulerJobs = subagentSchedulerJobs.filterNot { it.jobId == completedJob.jobId }
            refreshBackgroundExecutionState(subagentSchedulerJobs)
        }
    )
    @Volatile
    private var pendingPromptCacheShape: PromptCacheShape? = null
    @Volatile
    private var streamingFlushJob: Job? = null

    private fun recordError(
        message: String,
        kind: ErrorRecordKind = ErrorRecordKind.GENERAL
    ) {
        val record = applyErrorRecordedHook(
            message = message,
            kind = kind,
            hookBus = hookBus
        )
        _state.value = _state.value.copy(
            recentErrors = (listOf(record) + _state.value.recentErrors).take(30)
        )
    }

    private fun recordToolCall(
        toolName: String,
        args: String,
        result: String? = null,
        isSuccess: Boolean = true,
        stepSignOffReceipt: StepSignOffReceipt? = null,
        structuredPayload: ToolStructuredPayload? = null
    ) {
        val record = ToolCallRecordUi(
            toolName = toolName,
            args = args,
            result = result,
            isSuccess = isSuccess,
            stepSignOffReceipt = stepSignOffReceipt,
            structuredPayload = structuredPayload
        )
        _state.value = _state.value.copy(
            recentToolCalls = (listOf(record) + _state.value.recentToolCalls).take(50)
        )
    }

    private fun recordFinalReadinessAudit(audit: FinalReadinessAuditRecord) {
        val clearedState = clearRecoveredFinalReadinessState(_state.value, audit)
        _state.value = clearedState.copy(
            recentFinalReadinessAudits = (listOf(audit) + clearedState.recentFinalReadinessAudits).take(20)
        )
    }

    private fun recordApproval(
        toolName: String,
        summary: String,
        decision: String,
        scopeSummary: String? = null,
        explanationLabel: String? = null,
        explanationDetail: String? = null
    ) {
        val updated = applyApprovalResolvedHook(
            toolName = toolName,
            summary = summary,
            decision = decision,
            scopeSummary = scopeSummary,
            explanationLabel = explanationLabel,
            explanationDetail = explanationDetail,
            hookBus = hookBus
        )
        val record = ApprovalRecordUi(
            toolName = updated.toolName,
            summary = updated.summary,
            decision = updated.decision,
            scopeSummary = updated.scopeSummary,
            explanationLabel = updated.explanationLabel,
            explanationDetail = updated.explanationDetail
        )
        _state.value = _state.value.copy(
            recentApprovals = (listOf(record) + _state.value.recentApprovals).take(30)
        )
    }

    private fun emitSessionLifecycleNotice(
        notice: String,
        source: String
    ) {
        val lifecycleNotice = applySessionLifecycleNoticeHook(
            sessionId = currentSessionId,
            notice = notice,
            source = source,
            hookBus = hookBus
        )
        emitNotification(
            channel = "session_lifecycle",
            message = lifecycleNotice,
            source = source
        )
    }

    private suspend fun connectMcpServersWithHook(
        configs: List<McpServerConfig>,
        trigger: String
    ) {
        val registry = mcpRegistry ?: return
        try {
            registry.connectAll(configs)
        } finally {
            applyMcpConnectionStatusHook(
                trigger = trigger,
                attemptedServerNames = configs.filter { it.enabled }.map { it.name }.sorted(),
                statuses = registry.getServerStatuses(),
                hookBus = hookBus
            )
        }
    }

    private fun emitMcpConnectionStatusSnapshot(trigger: String) {
        val registry = mcpRegistry ?: return
        val configs = registry.loadConfigs()
        applyMcpConnectionStatusHook(
            trigger = trigger,
            attemptedServerNames = configs.filter { it.enabled }.map { it.name }.sorted(),
            statuses = registry.getServerStatuses(),
            hookBus = hookBus
        )
    }

    private fun emitNotification(
        channel: String,
        message: String,
        source: String
    ) {
        val updatedMessage = applyNotificationHook(
            sessionId = currentSessionId,
            channel = channel,
            message = message,
            source = source,
            hookBus = hookBus
        )
        when (channel) {
            "session_lifecycle" -> _sessionLifecycleNotices.tryEmit(updatedMessage)
            "pending_prompt_replay" -> _pendingPromptReplayNotices.tryEmit(updatedMessage)
        }
    }

    private fun emitSessionTransition(
        sessionId: String,
        phase: SessionTransitionPhase,
        trigger: String,
        counterpartSessionId: String?,
        sessionTitle: String?,
        projectPath: String?
    ) {
        if (sessionId.isBlank()) return
        applySessionTransitionHook(
            sessionId = sessionId,
            phase = phase,
            trigger = trigger,
            counterpartSessionId = counterpartSessionId,
            sessionTitle = sessionTitle,
            projectPath = projectPath,
            hookBus = hookBus
        )
    }

    private fun appendSystemMessage(
        content: String,
        source: String
    ) {
        val messageId = nextId()
        appendMessage(
            ChatMessageUi(
                id = messageId,
                role = "system",
                content = applySystemMessageHook(
                    sessionId = currentSessionId,
                    messageId = messageId,
                    content = content,
                    source = source,
                    hookBus = hookBus
                )
            )
        )
    }

    private fun decorateUserVisibleErrorMessage(message: String): String {
        return if (message.startsWith("⚠️") ||
            message.startsWith("⏹️") ||
            message.startsWith("ℹ️")
        ) {
            message
        } else {
            "⚠️ $message"
        }
    }

    private fun recordCheckpointRecovery(record: CheckpointRecoveryRecordUi) {
        _state.value = _state.value.copy(
            recentRecoveryRecords = (listOf(record) + _state.value.recentRecoveryRecords).take(30)
        )
    }

    private fun recordConversationPromptCheckpoint(
        summary: String,
        createdAt: Long = System.currentTimeMillis()
    ) {
        val currentState = _state.value
        val checkpoint = ConversationCheckpointUi(
            id = "chk-$createdAt",
            messageIndex = currentState.messages.lastIndex,
            createdAt = createdAt,
            summary = summary,
            kind = ConversationCheckpointKind.FILE_TURN,
            scope = ConversationCheckpointScope.CONVERSATION,
            source = ConversationCheckpointSource.TOOL_EXECUTION,
            promptSnapshot = buildConversationCheckpointPromptSnapshot(currentState)
        )
        _state.value = currentState.copy(
            checkpoints = upsertCheckpointHistory(
                checkpoints = currentState.checkpoints,
                checkpoint = checkpoint,
                maxSize = MAX_CHECKPOINT_HISTORY
            )
        )
    }

    private fun recordClarificationPromptCheckpoint(
        request: ClarificationRequestUi,
        summary: String = "交互状态: 等待澄清回答"
    ) {
        recordConversationPromptCheckpoint(
            summary = summary,
            createdAt = request.createdAt
        )
    }

    private fun recordApprovalInvalidations(records: List<ApprovalInvalidationRecordUi>) {
        if (records.isEmpty()) return
        _state.value = _state.value.copy(
            recentApprovalInvalidations = (records + _state.value.recentApprovalInvalidations).take(30)
        )
    }

    private fun clearApprovedApprovalScopes() {
        approvedApprovalScopes.clear()
    }

    private fun approvalScopeId(scope: Set<String>): String {
        return scope.toList().sorted().joinToString("|")
    }

    private fun approvalScopeId(scope: PersistedApprovedApprovalScope): String {
        return approvalScopeId(scope.tokens.toSet())
    }

    private fun normalizeProjectPath(path: String?): String? {
        return path?.trim()?.trimEnd('/', '\\')?.takeIf { it.isNotBlank() }
    }

    private fun SessionState.matchesActiveProjectConfig(
        activeProjectScope: ActiveProjectScopeResolution
    ): Boolean {
        return activeProjectScopePath == activeProjectScope.activeScopePath &&
            projectRules == activeProjectScope.activeProjectConfig.projectRules &&
            projectMemories == activeProjectScope.activeProjectConfig.projectMemories &&
            projectSkills == activeProjectScope.activeProjectConfig.projectSkills &&
            projectToolPreferences == activeProjectScope.activeProjectConfig.projectToolPreferences
    }

    private fun SessionState.copyWithProjectConfig(
        activeProjectScopePath: String?,
        projectConfig: SessionProjectConfig,
        repoScopedConfigs: Map<String, SessionProjectConfig> = this.repoScopedProjectConfigMap()
    ): SessionState {
        return copy(
            activeProjectScopePath = activeProjectScopePath,
            projectRules = projectConfig.projectRules,
            projectMemories = projectConfig.projectMemories,
            projectSkills = projectConfig.projectSkills,
            projectToolPreferences = projectConfig.projectToolPreferences,
            repoScopedConfigs = repoScopedConfigs
        )
    }

    private fun SessionState.copyWithRepoScopedProjectConfigs(
        repoScopedConfigs: Map<String, SessionProjectConfig>
    ): SessionState {
        return copy(repoScopedConfigs = repoScopedConfigs)
    }

    private fun SessionState.repoScopedProjectConfigMap(): Map<String, SessionProjectConfig> {
        return repoScopedConfigs
    }

    private fun SessionState.normalizedRepoScopedProjectConfigMap(): Map<String, SessionProjectConfig> {
        return normalizeSessionProjectConfigs(
            configs = repoScopedConfigs,
            normalizePath = ::normalizeProjectPath
        )
    }

    private fun SessionState.copyWithRestoredProjectConfig(
        restoredProjectConfig: RestoredProjectConfigSnapshot
    ): SessionState {
        return copyWithProjectConfig(
            activeProjectScopePath = restoredProjectConfig.activeProjectScope.activeScopePath,
            projectConfig = restoredProjectConfig.activeProjectScope.activeProjectConfig,
            repoScopedConfigs = restoredProjectConfig.repoScopedConfigs
        )
    }

    private fun SessionState.resolveProjectConfig(scopePath: String?): SessionProjectConfig {
        return resolveProjectScopeConfig(
            scopePath = normalizeProjectPath(scopePath),
            repoScopedConfigs = repoScopedProjectConfigMap(),
            fallbackProjectConfig = toSessionProjectConfig()
        )
    }

    private fun activeProjectScopePath(
        activeScopePath: String?,
        projectPath: String?
    ): String? {
        return normalizeProjectPath(activeScopePath) ?: normalizeProjectPath(projectPath)
    }

    private fun findLatestProjectSession(projectPath: String): PersistedSession? {
        val normalizedProjectPath = normalizeProjectPath(projectPath) ?: return null
        return conversationStore.listSessions()
            .asSequence()
            .filter { normalizeProjectPath(it.projectPath) == normalizedProjectPath }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { sessionSummary -> conversationStore.loadSession(sessionSummary.id) }
            .firstOrNull()
    }

    private fun normalizeApprovalScopeTokens(tokens: Collection<String>): List<String> {
        val tokenSet = tokens.toSet()
        val hasCodeEdit = tokenSet.any { token ->
            token == "subagent:cap:code_edit" || token == "subagent:cap:code_edit_apply"
        }
        return buildSet {
            tokenSet.forEach { token ->
                when (token) {
                    "subagent:cap:write", "subagent:cap:file_write" -> {
                        add(
                            if (hasCodeEdit) {
                                "subagent:cap:file_write_code"
                            } else {
                                "subagent:cap:file_write_general"
                            }
                        )
                    }
                    "subagent:cap:file_write_general" -> add("subagent:cap:file_write_general")
                    "subagent:cap:file_write_code" -> add("subagent:cap:file_write_code")
                    "subagent:cap:code_edit", "subagent:cap:code_edit_apply" -> add("subagent:cap:code_edit_apply")
                    "subagent:cap:task_repo_write" -> add("subagent:cap:task_repo_write")
                    "subagent:cap:shell", "subagent:cap:shell_exec" -> add("subagent:cap:shell_exec")
                }
            }
        }.sorted()
    }

    private fun approvalScopeLabel(token: String): String? = when (token) {
        "subagent:cap:file_write_general" -> "通用文件写入"
        "subagent:cap:file_write_code" -> "代码文件写入"
        "subagent:cap:code_edit_apply" -> "代码编辑"
        "subagent:cap:task_repo_write" -> "远端任务仓库写入"
        "subagent:cap:shell_exec" -> "Shell 执行"
        "mcp:github:write" -> "GitHub 写操作"
        else -> null
    }

    private fun approvalScopePolicy(token: String): ApprovalScopePolicyUi? = when (token) {
        "subagent:cap:file_write_general" -> ApprovalScopePolicyUi(
            label = "需手动导入",
            detail = "通用文件写入包含任意文件写入能力，当前仍需手动导入或重新审批。",
            autoInheritable = false
        )
        "subagent:cap:file_write_code" -> ApprovalScopePolicyUi(
            label = "可自动继承",
            detail = "代码文件写入会在与代码编辑配套的保守场景下自动继承。",
            autoInheritable = true
        )
        "subagent:cap:code_edit_apply" -> ApprovalScopePolicyUi(
            label = "可自动继承",
            detail = "代码编辑类授权在同项目下可按保守边界自动继承。",
            autoInheritable = true
        )
        "subagent:cap:task_repo_write" -> ApprovalScopePolicyUi(
            label = "需手动导入",
            detail = "远端任务仓库写入会修改 GitHub 任务仓库内容，默认需要单独确认，不自动继承。",
            autoInheritable = false
        )
        "mcp:github:write" -> ApprovalScopePolicyUi(
            label = "需手动导入",
            detail = "GitHub 的创建、推送、合并、发评论等远端写操作默认需要单独确认，不自动继承。",
            autoInheritable = false
        )
        "subagent:cap:shell_exec" -> ApprovalScopePolicyUi(
            label = "必须重审",
            detail = "Shell 执行始终要求重新审批，不参与自动继承。",
            autoInheritable = false
        )
        else -> null
    }

    private fun approvalScopeSummary(tokens: Set<String>): String {
        return tokens.mapNotNull(::approvalScopeLabel).sorted().joinToString("、").ifBlank { "未命名范围" }
    }

    private fun approvalScopeSummaryOrNull(tokens: Set<String>): String? {
        return tokens.takeIf { it.isNotEmpty() }?.let(::approvalScopeSummary)
    }

    private fun approvalRequestSubjectLabel(request: ToolApprovalRequest): String {
        return when {
            request.summary.startsWith("子代理编排请求提权") -> "子代理编排批次"
            request.summary.startsWith("子代理请求提权") -> "子代理任务"
            else -> "当前请求"
        }
    }

    private fun normalizeApprovedApprovalScope(scope: PersistedApprovedApprovalScope): PersistedApprovedApprovalScope? {
        val normalizedTokens = normalizeApprovalScopeTokens(scope.tokens)
        if (normalizedTokens.isEmpty()) return null
        return scope.copy(tokens = normalizedTokens)
    }

    private fun buildApprovedApprovalScopeUi(scope: PersistedApprovedApprovalScope): ApprovedApprovalScopeUi {
        val capabilities = scope.tokens.mapNotNull(::approvalScopeLabel).sorted()
        val sourceLabel = when (scope.sourceKind) {
            "auto_inherited_session" -> "项目自动继承"
            "imported_session" -> "手动导入授权"
            else -> "当前会话授权"
        }
        val sourceDetail = when (scope.sourceKind) {
            "auto_inherited_session" -> scope.sourceSessionTitle?.ifBlank { "历史会话" }?.let { "自动继承自: $it" }
            "imported_session" -> scope.sourceSessionTitle?.ifBlank { "历史会话" }?.let { "手动导入自: $it" }
            else -> null
        }
        return ApprovedApprovalScopeUi(
            id = approvalScopeId(scope),
            capabilities = capabilities,
            summary = approvalScopeSummary(scope.tokens.toSet()),
            sourceLabel = sourceLabel,
            sourceDetail = sourceDetail
        )
    }

    private fun normalizeApprovedApprovalScopes(scopes: List<PersistedApprovedApprovalScope>): List<PersistedApprovedApprovalScope> {
        val deduplicated = scopes
            .mapNotNull(::normalizeApprovedApprovalScope)
            .distinctBy(::approvalScopeId)
            .sortedByDescending { it.tokens.size }
        val normalized = mutableListOf<PersistedApprovedApprovalScope>()
        deduplicated.forEach { candidate ->
            val candidateTokens = candidate.tokens.toSet()
            if (normalized.none { existing -> existing.tokens.toSet().containsAll(candidateTokens) }) {
                normalized.add(candidate)
            }
        }
        return normalized.sortedBy { it.tokens.size }
    }

    private fun buildCurrentSessionApprovalScope(scope: Set<String>): PersistedApprovedApprovalScope {
        return PersistedApprovedApprovalScope(
            tokens = scope.toList().sorted(),
            sourceKind = "current_session"
        )
    }

    private fun buildImportedApprovalScope(
        scope: Set<String>,
        sourceSessionId: String,
        sourceSessionTitle: String,
        sourceUpdatedAt: Long
    ): PersistedApprovedApprovalScope {
        return PersistedApprovedApprovalScope(
            tokens = scope.toList().sorted(),
            sourceKind = "imported_session",
            sourceSessionId = sourceSessionId,
            sourceSessionTitle = sourceSessionTitle,
            sourceSessionUpdatedAt = sourceUpdatedAt,
            importedAt = System.currentTimeMillis()
        )
    }

    private fun buildAutoInheritedApprovalScope(
        scope: Set<String>,
        sourceSessionId: String,
        sourceSessionTitle: String,
        sourceUpdatedAt: Long
    ): PersistedApprovedApprovalScope {
        return PersistedApprovedApprovalScope(
            tokens = scope.toList().sorted(),
            sourceKind = "auto_inherited_session",
            sourceSessionId = sourceSessionId,
            sourceSessionTitle = sourceSessionTitle,
            sourceSessionUpdatedAt = sourceUpdatedAt,
            importedAt = System.currentTimeMillis()
        )
    }

    private fun isHistoryDerivedApprovalScope(scope: PersistedApprovedApprovalScope): Boolean {
        return scope.sourceKind == "imported_session" || scope.sourceKind == "auto_inherited_session"
    }

    private fun approvalScopeInvalidationReason(
        scope: PersistedApprovedApprovalScope,
        normalizedProjectPath: String?
    ): ApprovalScopeInvalidationReason? {
        if (!isHistoryDerivedApprovalScope(scope)) return null
        val projectPath = normalizedProjectPath ?: return ApprovalScopeInvalidationReason(
            label = "当前项目缺失",
            detail = "当前会话未绑定项目，无法继续验证历史继承授权的项目边界，因此已自动清理。"
        )
        val sourceSessionId = scope.sourceSessionId ?: return ApprovalScopeInvalidationReason(
            label = "来源信息缺失",
            detail = "该历史授权没有完整的来源会话信息，无法确认是否仍然安全，已自动清理。"
        )
        val sourceSession = conversationStore.loadSession(sourceSessionId) ?: return ApprovalScopeInvalidationReason(
            label = "来源会话不存在",
            detail = "该历史授权对应的来源会话已不存在，无法继续确认来源范围，已自动清理。"
        )
        if (normalizeProjectPath(sourceSession.projectPath) != projectPath) {
            return ApprovalScopeInvalidationReason(
                label = "来源项目不匹配",
                detail = "该历史授权来源会话的项目路径已与当前会话不一致，已按项目边界自动失效。"
            )
        }
        val sourceScopes = normalizeApprovedApprovalScopes(
            conversationStore.restoreApprovedApprovalScopes(
                sourceSession.approvedApprovalScopeEntries,
                sourceSession.approvedApprovalScopes
            )
        )
        val importedTokens = scope.tokens.toSet()
        val stillCovered = sourceScopes.any { sourceScope ->
            sourceScope.tokens.toSet().containsAll(importedTokens)
        }
        return if (stillCovered) {
            null
        } else {
            ApprovalScopeInvalidationReason(
                label = "来源范围已收缩",
                detail = "来源会话里已不存在能覆盖当前范围的授权记录，说明历史授权边界已经收缩，已自动清理。"
            )
        }
    }

    private fun pruneInvalidImportedApprovalScopes(projectPath: String? = _state.value.projectPath): Boolean {
        val normalizedProjectPath = normalizeProjectPath(projectPath)
        val invalidations = approvedApprovalScopes.mapNotNull { scope ->
            approvalScopeInvalidationReason(scope, normalizedProjectPath)?.let { reason ->
                scope to reason
            }
        }
        if (invalidations.isNotEmpty()) {
            recordApprovalInvalidations(
                invalidations.map { (scope, reason) ->
                    ApprovalInvalidationRecordUi(
                        summary = approvalScopeSummary(scope.tokens.toSet()),
                        sourceLabel = when (scope.sourceKind) {
                            "auto_inherited_session" -> "项目自动继承"
                            "imported_session" -> "手动导入授权"
                            else -> "当前会话授权"
                        },
                        sourceDetail = when (scope.sourceKind) {
                            "auto_inherited_session" ->
                                scope.sourceSessionTitle?.ifBlank { "历史会话" }?.let { "自动继承自: $it" }
                            "imported_session" ->
                                scope.sourceSessionTitle?.ifBlank { "历史会话" }?.let { "手动导入自: $it" }
                            else -> null
                        },
                        reasonLabel = reason.label,
                        reasonDetail = reason.detail
                    )
                }
            )
        }
        val invalidScopeIds = invalidations.mapTo(hashSetOf()) { (scope, _) -> approvalScopeId(scope) }
        val validScopes = approvedApprovalScopes.filterNot { scope ->
            approvalScopeId(scope) in invalidScopeIds
        }
        val normalizedValidScopes = normalizeApprovedApprovalScopes(validScopes)
        if (normalizedValidScopes == approvedApprovalScopes) return false
        approvedApprovalScopes.clear()
        approvedApprovalScopes.addAll(normalizedValidScopes)
        return true
    }

    private fun syncApprovedApprovalScopesState() {
        pruneInvalidImportedApprovalScopes()
        val normalizedScopes = normalizeApprovedApprovalScopes(approvedApprovalScopes)
        if (normalizedScopes != approvedApprovalScopes) {
            approvedApprovalScopes.clear()
            approvedApprovalScopes.addAll(normalizedScopes)
        }
        val projectApprovalHistory = collectProjectApprovalHistory()
        val inheritedScopes = collectInheritedApprovalScopes()
        _state.value = _state.value.copy(
            approvedApprovalScopes = approvedApprovalScopes
                .map(::buildApprovedApprovalScopeUi)
                .sortedBy { it.capabilities.size },
            projectApprovalHistory = projectApprovalHistory,
            projectInheritedApprovalScopes = inheritedScopes
        )
    }

    private fun restoreApprovedApprovalScopes(scopes: List<PersistedApprovedApprovalScope>) {
        approvedApprovalScopes.clear()
        approvedApprovalScopes.addAll(normalizeApprovedApprovalScopes(scopes))
    }

    private fun collectInheritedApprovalScopes(projectPath: String? = _state.value.projectPath): List<InheritedApprovalScopeUi> {
        val normalizedProjectPath = normalizeProjectPath(projectPath) ?: return emptyList()
        val currentGrantedScopes = normalizeApprovedApprovalScopes(approvedApprovalScopes)
        val inheritedById = linkedMapOf<String, InheritedApprovalScopeUi>()
        conversationStore.listSessions()
            .asSequence()
            .filter { it.id != currentSessionId }
            .filter { normalizeProjectPath(it.projectPath) == normalizedProjectPath }
            .sortedByDescending { it.updatedAt }
            .forEach { sessionSummary ->
                val persistedSession = conversationStore.loadSession(sessionSummary.id) ?: return@forEach
                val normalizedScopes = normalizeApprovedApprovalScopes(
                    conversationStore.restoreApprovedApprovalScopes(
                        persistedSession.approvedApprovalScopeEntries,
                        persistedSession.approvedApprovalScopes
                    )
                )
                normalizedScopes.forEach { scope ->
                    buildInheritedScopeCandidates(scope).forEach { (candidateTokens, policy) ->
                        if (currentGrantedScopes.any { granted -> granted.tokens.toSet().containsAll(candidateTokens) }) return@forEach
                        val scopeId = approvalScopeId(candidateTokens)
                        if (inheritedById.containsKey(scopeId)) return@forEach
                        inheritedById[scopeId] = InheritedApprovalScopeUi(
                            id = scopeId,
                            capabilities = candidateTokens.mapNotNull(::approvalScopeLabel).sorted(),
                            summary = approvalScopeSummary(candidateTokens),
                            policyLabel = policy.label,
                            policyDetail = policy.detail,
                            sourceSessionId = sessionSummary.id,
                            sourceSessionTitle = sessionSummary.title.ifBlank { "新对话" },
                            sourceUpdatedAt = sessionSummary.updatedAt
                        )
                    }
                }
            }
        return inheritedById.values.toList()
    }

    private fun collectProjectApprovalHistory(projectPath: String? = _state.value.projectPath): ProjectApprovalHistoryUi? {
        val normalizedProjectPath = normalizeProjectPath(projectPath) ?: return null
        val sameProjectSessions = conversationStore.listSessions()
            .asSequence()
            .filter { it.id != currentSessionId }
            .filter { normalizeProjectPath(it.projectPath) == normalizedProjectPath }
            .sortedByDescending { it.updatedAt }
            .toList()
        if (sameProjectSessions.isEmpty()) {
            return ProjectApprovalHistoryUi()
        }
        var sessionsWithApprovedScopes = 0
        var directApprovalSessionCount = 0
        var importedApprovalSessionCount = 0
        var autoInheritedApprovalSessionCount = 0
        val scopeTrends = linkedMapOf<String, ApprovalScopeTrendAggregate>()
        sameProjectSessions.forEach { sessionSummary ->
            val persistedSession = conversationStore.loadSession(sessionSummary.id) ?: return@forEach
            val normalizedScopes = normalizeApprovedApprovalScopes(
                conversationStore.restoreApprovedApprovalScopes(
                    persistedSession.approvedApprovalScopeEntries,
                    persistedSession.approvedApprovalScopes
                )
            )
            if (normalizedScopes.isEmpty()) return@forEach
            sessionsWithApprovedScopes += 1
            var hasDirectApproval = false
            var hasImportedApproval = false
            var hasAutoInheritedApproval = false
            normalizedScopes.forEach { scope ->
                val scopeId = approvalScopeId(scope)
                val aggregate = scopeTrends.getOrPut(scopeId) {
                    ApprovalScopeTrendAggregate(tokens = scope.tokens.toSet())
                }
                aggregate.sessionIds.add(sessionSummary.id)
                when (scope.sourceKind) {
                    "auto_inherited_session" -> {
                        hasAutoInheritedApproval = true
                        aggregate.autoInheritedSessionIds.add(sessionSummary.id)
                    }
                    "imported_session" -> {
                        hasImportedApproval = true
                        aggregate.importedSessionIds.add(sessionSummary.id)
                    }
                    else -> {
                        hasDirectApproval = true
                        aggregate.directSessionIds.add(sessionSummary.id)
                    }
                }
                if (sessionSummary.updatedAt >= aggregate.latestSourceUpdatedAt) {
                    aggregate.latestSourceUpdatedAt = sessionSummary.updatedAt
                    aggregate.latestSourceSessionTitle = sessionSummary.title.ifBlank { "新对话" }
                }
            }
            if (hasDirectApproval) directApprovalSessionCount += 1
            if (hasImportedApproval) importedApprovalSessionCount += 1
            if (hasAutoInheritedApproval) autoInheritedApprovalSessionCount += 1
        }
        return ProjectApprovalHistoryUi(
            analyzedSessionCount = sameProjectSessions.size,
            sessionsWithApprovedScopes = sessionsWithApprovedScopes,
            directApprovalSessionCount = directApprovalSessionCount,
            importedApprovalSessionCount = importedApprovalSessionCount,
            autoInheritedApprovalSessionCount = autoInheritedApprovalSessionCount,
            distinctScopeCount = scopeTrends.size,
            scopeTrends = scopeTrends.values
                .map { aggregate ->
                    val policy = buildInheritedApprovalScopePolicy(aggregate.tokens)
                    ProjectApprovalScopeTrendUi(
                        id = approvalScopeId(aggregate.tokens),
                        summary = approvalScopeSummary(aggregate.tokens),
                        capabilities = aggregate.tokens.mapNotNull(::approvalScopeLabel).sorted(),
                        sessionCount = aggregate.sessionIds.size,
                        directSessionCount = aggregate.directSessionIds.size,
                        importedSessionCount = aggregate.importedSessionIds.size,
                        autoInheritedSessionCount = aggregate.autoInheritedSessionIds.size,
                        policyLabel = policy.label,
                        policyDetail = policy.detail,
                        latestSourceSessionTitle = aggregate.latestSourceSessionTitle,
                        latestSourceUpdatedAt = aggregate.latestSourceUpdatedAt
                    )
                }
                .sortedWith(
                    compareByDescending<ProjectApprovalScopeTrendUi> { it.sessionCount }
                        .thenByDescending { it.latestSourceUpdatedAt }
                        .thenByDescending { it.directSessionCount }
                )
                .take(6)
        )
    }

    private fun isApprovalScopeAlreadyGranted(request: ToolApprovalRequest): Boolean {
        val requestedScope = request.approvalScopeTokens
        if (requestedScope.isEmpty()) return false
        return approvedApprovalScopes.any { grantedScope ->
            grantedScope.tokens.toSet().containsAll(requestedScope)
        }
    }

    private fun isAutoInheritableApprovalScopeToken(token: String): Boolean {
        return approvalScopePolicy(token)?.autoInheritable == true
    }

    private fun buildInheritedApprovalScopePolicy(tokens: Set<String>): ApprovalScopePolicyUi {
        val policies = tokens.mapNotNull(::approvalScopePolicy)
        val autoLabels = tokens.filter(::isAutoInheritableApprovalScopeToken).mapNotNull(::approvalScopeLabel).sorted()
        val manualLabels = tokens
            .filterNot(::isAutoInheritableApprovalScopeToken)
            .filter { it != "subagent:cap:shell_exec" }
            .mapNotNull(::approvalScopeLabel)
            .sorted()
        return when {
            policies.isEmpty() -> ApprovalScopePolicyUi(
                label = "需手动导入",
                detail = "当前范围未命中自动继承白名单，需手动导入或重新审批。",
                autoInheritable = false
            )
            policies.all { it.autoInheritable } -> ApprovalScopePolicyUi(
                label = "可自动继承",
                detail = policies.joinToString(" ") { it.detail }.trim(),
                autoInheritable = true
            )
            policies.any { it.label == "必须重审" } -> ApprovalScopePolicyUi(
                label = "必须重审",
                detail = buildString {
                    append(policies.first { it.label == "必须重审" }.detail)
                    if (autoLabels.isNotEmpty()) {
                        append(" ")
                        append(autoLabels.joinToString("、"))
                        append("可作为更小范围单独自动继承。")
                    }
                }.trim(),
                autoInheritable = false
            )
            autoLabels.isNotEmpty() && manualLabels.isNotEmpty() -> ApprovalScopePolicyUi(
                label = "部分可自动继承",
                detail = buildString {
                    append(autoLabels.joinToString("、"))
                    append("可自动继承；")
                    append(manualLabels.joinToString("、"))
                    append("仍需手动导入或重新审批。")
                },
                autoInheritable = false
            )
            else -> ApprovalScopePolicyUi(
                label = "需手动导入",
                detail = policies.firstOrNull { !it.autoInheritable }?.detail
                    ?: "当前范围未命中自动继承白名单，需手动导入或重新审批。",
                autoInheritable = false
            )
        }
    }

    private fun buildInheritedScopeCandidates(scope: PersistedApprovedApprovalScope): List<Pair<Set<String>, ApprovalScopePolicyUi>> {
        val normalizedTokens = scope.tokens.toSet()
        if (normalizedTokens.isEmpty()) return emptyList()
        val autoTokens = normalizedTokens.filter(::isAutoInheritableApprovalScopeToken).toSet()
        return buildList {
            add(normalizedTokens to buildInheritedApprovalScopePolicy(normalizedTokens))
            if (autoTokens.isNotEmpty() && autoTokens != normalizedTokens) {
                add(autoTokens to buildInheritedApprovalScopePolicy(autoTokens))
            }
        }
    }

    private fun canAutoInheritApprovalScope(request: ToolApprovalRequest): Boolean {
        val requestedScope = request.approvalScopeTokens
        if (requestedScope.isEmpty()) return false
        if (normalizeProjectPath(_state.value.projectPath) == null) return false
        return requestedScope.all(::isAutoInheritableApprovalScopeToken)
    }

    private fun findAutoInheritedApprovalCandidate(request: ToolApprovalRequest): AutoInheritedApprovalCandidate? {
        if (!canAutoInheritApprovalScope(request)) return null
        val normalizedProjectPath = normalizeProjectPath(_state.value.projectPath) ?: return null
        val requestedScope = request.approvalScopeTokens
        return conversationStore.listSessions()
            .asSequence()
            .filter { it.id != currentSessionId }
            .filter { normalizeProjectPath(it.projectPath) == normalizedProjectPath }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { sessionSummary ->
                val persistedSession = conversationStore.loadSession(sessionSummary.id) ?: return@mapNotNull null
                normalizeApprovedApprovalScopes(
                    conversationStore.restoreApprovedApprovalScopes(
                        persistedSession.approvedApprovalScopeEntries,
                        persistedSession.approvedApprovalScopes
                    )
                ).firstOrNull { scope ->
                    scope.sourceKind == "current_session" &&
                        scope.tokens.toSet().containsAll(requestedScope)
                }?.let {
                    AutoInheritedApprovalCandidate(
                        scope = requestedScope,
                        sourceSessionId = sessionSummary.id,
                        sourceSessionTitle = sessionSummary.title.ifBlank { "新对话" },
                        sourceUpdatedAt = sessionSummary.updatedAt
                    )
                }
            }
            .firstOrNull()
    }

    private fun mergeApprovedApprovalScope(scope: PersistedApprovedApprovalScope): Boolean {
        val normalizedScope = normalizeApprovedApprovalScope(scope) ?: return false
        if (approvedApprovalScopes.any { it.tokens.toSet().containsAll(normalizedScope.tokens.toSet()) }) return false
        val updatedScopes = normalizeApprovedApprovalScopes(approvedApprovalScopes + normalizedScope)
        approvedApprovalScopes.clear()
        approvedApprovalScopes.addAll(updatedScopes)
        syncApprovedApprovalScopesState()
        return true
    }

    private fun registerApprovedApprovalScope(request: ToolApprovalRequest) {
        val requestedScope = request.approvalScopeTokens
        if (requestedScope.isEmpty()) return
        mergeApprovedApprovalScope(buildCurrentSessionApprovalScope(requestedScope))
    }

    fun clearApprovedApprovalScopesForCurrentSession(): Boolean {
        if (approvedApprovalScopes.isEmpty()) return false
        clearApprovedApprovalScopes()
        syncApprovedApprovalScopesState()
        saveCurrentSession()
        return true
    }

    fun clearImportedApprovalScopesForCurrentSession(): Boolean {
        val removed = approvedApprovalScopes.removeAll(::isHistoryDerivedApprovalScope)
        if (!removed) return false
        syncApprovedApprovalScopesState()
        saveCurrentSession()
        return true
    }

    fun removeApprovedApprovalScopeForCurrentSession(scopeId: String): Boolean {
        val removed = approvedApprovalScopes.removeAll { scope ->
            approvalScopeId(scope) == scopeId
        }
        if (!removed) return false
        syncApprovedApprovalScopesState()
        saveCurrentSession()
        return true
    }

    fun importInheritedApprovalScopeForCurrentSession(scopeId: String): Boolean {
        val normalizedProjectPath = normalizeProjectPath(_state.value.projectPath) ?: return false
        val importedScope = conversationStore.listSessions()
            .asSequence()
            .filter { it.id != currentSessionId }
            .filter { normalizeProjectPath(it.projectPath) == normalizedProjectPath }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { sessionSummary ->
                val persistedSession = conversationStore.loadSession(sessionSummary.id) ?: return@mapNotNull null
                normalizeApprovedApprovalScopes(
                    conversationStore.restoreApprovedApprovalScopes(
                        persistedSession.approvedApprovalScopeEntries,
                        persistedSession.approvedApprovalScopes
                    )
                ).firstOrNull { scope -> approvalScopeId(scope) == scopeId }?.let { scope ->
                    buildImportedApprovalScope(
                        scope = scope.tokens.toSet(),
                        sourceSessionId = sessionSummary.id,
                        sourceSessionTitle = sessionSummary.title.ifBlank { "新对话" },
                        sourceUpdatedAt = sessionSummary.updatedAt
                    )
                }
            }
            .firstOrNull()
            ?: return false
        if (!mergeApprovedApprovalScope(importedScope)) return false
        saveCurrentSession()
        return true
    }

    init {
        // 启动时自动创建新会话
        currentSessionId = UUID.randomUUID().toString().take(8)
        clearApprovedApprovalScopes()
        _state.value = _state.value.copy(sessionId = currentSessionId)
        syncApprovedApprovalScopesState()
        emitSessionTransition(
            sessionId = currentSessionId,
            phase = SessionTransitionPhase.STARTED,
            trigger = "init_session",
            counterpartSessionId = null,
            sessionTitle = _state.value.sessionTitle,
            projectPath = _state.value.projectPath
        )
    }

    /**
     * 加载已有会话
     */
    fun loadSession(sessionId: String): Boolean {
        if (!ensureSessionLifecycleAllowed(SessionLifecycleAction.LOAD_SESSION)) return false
        val session = conversationStore.loadSession(sessionId) ?: return false
        val previousState = _state.value
        val previousSessionId = currentSessionId
        val restoredProjectSeed = session.toPersistedSessionProjectConfigSeed()
        val resolvedScopePath = activeProjectScopePath(
            activeScopePath = session.activeProjectScopePath,
            projectPath = session.projectPath
        )
        val restoredProjectConfig = restorePersistedProjectConfig(
            workspaceScopePath = normalizeProjectPath(session.projectPath),
            activeScopePath = resolvedScopePath,
            legacyProjectConfig = restoredProjectSeed.legacyProjectConfig,
            repoScopedConfigs = restoredProjectSeed.repoScopedConfigs
        )
        val restoredCompressionSnapshots = conversationStore.restoreCompressionSnapshots(
            snapshots = session.compressionSnapshots,
            fallbackSnapshot = session.compressionSnapshot
        )
        val restoredAt = System.currentTimeMillis()
        val restoredSubagentRuns = conversationStore.restoreSubagentRuns(session.subagentRuns)
            .map { run -> normalizeRestoredSubagentRun(run, restoredAt) }
        val restoredSubagentBatches = conversationStore.restoreSubagentBatches(session.subagentBatches)
            .map { batch -> normalizeRestoredSubagentBatch(batch, restoredAt) }
        val restoredBackgroundJobs = conversationStore.restoreBackgroundJobs(session.backgroundJobs)
            .map { job -> normalizeRestoredBackgroundJob(job, restoredAt) }
        val interruptedSubagentRunIds = restoredSubagentRuns
            .filter { run ->
                run.status == "cancelled" &&
                    run.statusMessage == RESTORED_SUBAGENT_INTERRUPTED_MESSAGE
            }
            .map { it.runId }
        val currentCompressionSnapshot = restoredCompressionSnapshots
            .lastOrNull { it.active }
            ?: restoredCompressionSnapshots.lastOrNull()
        resetPendingStreamingUpdates()
        subagentSchedulerJobs = emptyList()
        pendingApprovalDecision = null
        pendingAskDecision = null
        currentSessionId = session.id
        cachedCurrentPersistedSession = session
        restoreApprovedApprovalScopes(
            conversationStore.restoreApprovedApprovalScopes(
                session.approvedApprovalScopeEntries,
                session.approvedApprovalScopes
            )
        )
        messageCounter = session.messages.maxOfOrNull { it.id } ?: 0
        val restoredState = SessionState(
            messages = conversationStore.restoreMessages(session.messages),
            subagentRuns = restoredSubagentRuns,
            subagentBatches = restoredSubagentBatches,
            backgroundJobs = restoredBackgroundJobs,
            sessionId = session.id,
            sessionTitle = session.title,
            sessionGoal = session.sessionGoal,
            projectPath = session.projectPath,
            remoteTaskRepositoryOwner = session.remoteTaskRepositoryOwner,
            remoteTaskRepositoryName = session.remoteTaskRepositoryName,
            remoteTaskRepositoryLabel = session.remoteTaskRepositoryLabel,
            remoteTaskRepositoryEditable = session.remoteTaskRepositoryEditable,
            projectKnowledgePaths = session.projectKnowledgePaths,
            projectKnowledgeSnapshots = session.projectKnowledgeSnapshots.map { snapshot ->
                ProjectKnowledgeSnapshotUi(
                    id = snapshot.id,
                    name = snapshot.name,
                    paths = snapshot.paths,
                    createdAt = snapshot.createdAt,
                    updatedAt = snapshot.updatedAt,
                    lastAppliedAt = snapshot.lastAppliedAt
                )
            },
            usageSummary = session.usageSummary,
            compressionSnapshot = currentCompressionSnapshot,
            compressionSnapshots = restoredCompressionSnapshots,
            checkpoints = conversationStore.restoreConversationCheckpoints(session.checkpoints),
            fileChanges = conversationStore.restoreFileChanges(session.fileChanges),
            recentErrors = conversationStore.restoreErrorRecords(session.recentErrors),
            recentToolCalls = conversationStore.restoreToolCallRecords(session.recentToolCalls),
            recentMemoryUpdateSuggestions = conversationStore.restoreMemoryUpdateSuggestions(
                session.recentMemoryUpdateSuggestions
            ),
            recentFinalReadinessAudits = conversationStore.restoreFinalReadinessAuditRecords(
                session.recentFinalReadinessAudits
            ),
            recentApprovals = conversationStore.restoreApprovalRecords(session.recentApprovals),
            recentRecoveryRecords = conversationStore.restoreCheckpointRecoveryRecords(
                session.recentRecoveryRecords
            ),
            recentApprovalInvalidations = conversationStore.restoreApprovalInvalidationRecords(
                session.recentApprovalInvalidations
            ),
            approvedApprovalScopes = approvedApprovalScopes
                .map(::buildApprovedApprovalScopeUi)
                .sortedBy { it.capabilities.size },
            pendingApproval = conversationStore.restorePendingApproval(session.pendingApproval),
            pendingAskRequest = conversationStore.restorePendingAskRequest(session.pendingAskRequest),
            pendingWorkflowPlan = conversationStore.restoreWorkflowPlan(session.pendingWorkflowPlan)
                ?.let(::normalizeWorkflowPlan),
            canonicalWorkflowPlan = conversationStore.restoreWorkflowPlan(session.canonicalWorkflowPlan)
                ?.let(::normalizeWorkflowPlan),
            pendingClarificationRequest = conversationStore.restoreClarificationRequest(
                session.pendingClarificationRequest
            ),
            lastAutoRouteDecision = session.lastAutoRouteDecision?.let { persisted ->
                AutoRouteDecisionUi(
                    action = when (persisted.action.uppercase()) {
                        "PLAN" -> AutoRouteAction.PLAN
                        "CLARIFY" -> AutoRouteAction.CLARIFY
                        else -> AutoRouteAction.DIRECT
                    },
                    reason = persisted.reason,
                    createdAt = persisted.createdAt
                )
            },
            lastWorkflowFallback = session.lastWorkflowFallback?.let { persisted ->
                WorkflowFallbackUi(
                    message = persisted.message,
                    createdAt = persisted.createdAt
                )
            },
            lastFinalReadinessReceipt = conversationStore.restoreFinalReadinessReceipt(
                session.lastFinalReadinessReceipt
            )
        ).copyWithRestoredProjectConfig(restoredProjectConfig)
        _state.value = applyParallelBatchWorkflowProgressTransition(
            normalizeRestoredTurnOrchestratorState(
                normalizeRecoveredFinalReadinessState(restoredState)
            )
        )
        val restoredRecentSessionHistoryClue = _state.value.recentSessionHistoryClue
        _state.value = _state.value.copy(
            pendingApproval = markPendingApprovalReplayOnly(
                pending = _state.value.pendingApproval,
                recentSessionHistoryClue = restoredRecentSessionHistoryClue
            ),
            pendingAskRequest = markPendingAskReplayOnly(
                request = _state.value.pendingAskRequest,
                recentSessionHistoryClue = restoredRecentSessionHistoryClue
            )
        )
        interruptedSubagentRunIds.forEach { runId ->
            updateSubagentMessage(runId, "子代理因会话恢复已中断")
        }
        restoredSubagentBatches
            .map { it.batchId }
            .distinct()
            .forEach(::refreshSubagentBatchState)
        syncApprovedApprovalScopesState()
        if (previousSessionId != session.id) {
            emitSessionTransition(
                sessionId = previousSessionId,
                phase = SessionTransitionPhase.STOPPED,
                trigger = "load_session",
                counterpartSessionId = session.id,
                sessionTitle = previousState.sessionTitle,
                projectPath = previousState.projectPath
            )
            emitSessionTransition(
                sessionId = session.id,
                phase = SessionTransitionPhase.STARTED,
                trigger = "load_session",
                counterpartSessionId = previousSessionId,
                sessionTitle = _state.value.sessionTitle,
                projectPath = _state.value.projectPath
            )
        }
        return true
    }

    fun replayPendingPrompts(): Boolean {
        val state = _state.value
        val hasReplayOnlyPrompt =
            state.pendingApproval?.isReplayOnly == true ||
                state.pendingAskRequest?.isReplayOnly == true
        if (!hasReplayOnlyPrompt) return false
        val notice = buildSessionRestoreReplayNotice(state) ?: return false
        emitNotification(
            channel = "pending_prompt_replay",
            message = notice,
            source = "session_restore_replay"
        )
        return true
    }

    /**
     * 新建会话
     */
    fun newSession() {
        if (!ensureSessionLifecycleAllowed(SessionLifecycleAction.NEW_SESSION)) return
        replaceCurrentSessionWithBlank(
            savePrevious = true,
            trigger = "new_session"
        )
    }

    fun startTask(projectPath: String) {
        if (!ensureSessionLifecycleAllowed(SessionLifecycleAction.START_TASK)) return
        val previousState = _state.value
        val previousSessionId = currentSessionId
        val normalizedPath = projectPath.trim().removeSuffix("/")
        val inheritedProjectSession = findLatestProjectSession(normalizedPath)
        val inheritedProjectSeed = inheritedProjectSession?.toPersistedSessionProjectConfigSeed()
        val resolvedScopePath = activeProjectScopePath(
            activeScopePath = inheritedProjectSession?.activeProjectScopePath,
            projectPath = normalizedPath
        )
        val restoredProjectConfig = restorePersistedProjectConfig(
            workspaceScopePath = normalizeProjectPath(normalizedPath),
            activeScopePath = resolvedScopePath,
            legacyProjectConfig = inheritedProjectSeed?.legacyProjectConfig ?: buildSessionProjectConfig(),
            repoScopedConfigs = inheritedProjectSeed?.repoScopedConfigs.orEmpty()
        )
        saveCurrentSession()
        currentSessionId = UUID.randomUUID().toString().take(8)
        cachedCurrentPersistedSession = null
        clearApprovedApprovalScopes()
        messageCounter = 0
        _state.value = SessionState(
            sessionId = currentSessionId,
            sessionTitle = buildTaskTitle(normalizedPath),
            projectPath = normalizedPath,
            projectKnowledgePaths = inheritedProjectSession?.projectKnowledgePaths.orEmpty(),
            projectKnowledgeSnapshots = inheritedProjectSession?.projectKnowledgeSnapshots.orEmpty().map { snapshot ->
                ProjectKnowledgeSnapshotUi(
                    id = snapshot.id,
                    name = snapshot.name,
                    paths = snapshot.paths,
                    createdAt = snapshot.createdAt,
                    updatedAt = snapshot.updatedAt,
                    lastAppliedAt = snapshot.lastAppliedAt
                )
            }
        ).copyWithRestoredProjectConfig(restoredProjectConfig)
        syncApprovedApprovalScopesState()
        emitSessionTransition(
            sessionId = previousSessionId,
            phase = SessionTransitionPhase.STOPPED,
            trigger = "start_task",
            counterpartSessionId = currentSessionId,
            sessionTitle = previousState.sessionTitle,
            projectPath = previousState.projectPath
        )
        emitSessionTransition(
            sessionId = currentSessionId,
            phase = SessionTransitionPhase.STARTED,
            trigger = "start_task",
            counterpartSessionId = previousSessionId,
            sessionTitle = _state.value.sessionTitle,
            projectPath = _state.value.projectPath
        )
    }

    fun updateCurrentTask(projectPath: String) {
        val normalizedPath = projectPath.trim().removeSuffix("/")
        if (normalizedPath.isBlank()) return
        val inheritedProjectSession = findLatestProjectSession(normalizedPath)
        val inheritedProjectSeed = inheritedProjectSession?.toPersistedSessionProjectConfigSeed()
        val resolvedScopePath = activeProjectScopePath(
            activeScopePath = inheritedProjectSession?.activeProjectScopePath,
            projectPath = normalizedPath
        )
        val restoredProjectConfig = restorePersistedProjectConfig(
            workspaceScopePath = normalizeProjectPath(normalizedPath),
            activeScopePath = resolvedScopePath,
            legacyProjectConfig = inheritedProjectSeed?.legacyProjectConfig ?: buildSessionProjectConfig(),
            repoScopedConfigs = inheritedProjectSeed?.repoScopedConfigs.orEmpty()
        )
        val current = _state.value
        val previousAutoTitle = current.projectPath?.let(::buildTaskTitle)
        val shouldUpdateTitle = current.sessionTitle.isBlank() ||
            current.sessionTitle == "新对话" ||
            current.sessionTitle == previousAutoTitle
        clearApprovedApprovalScopes()
        _state.value = _state.value.copy(
            sessionTitle = if (shouldUpdateTitle) buildTaskTitle(normalizedPath) else current.sessionTitle,
            projectPath = normalizedPath,
            projectKnowledgePaths = inheritedProjectSession?.projectKnowledgePaths.orEmpty(),
            projectKnowledgeSnapshots = inheritedProjectSession?.projectKnowledgeSnapshots.orEmpty().map { snapshot ->
                ProjectKnowledgeSnapshotUi(
                    id = snapshot.id,
                    name = snapshot.name,
                    paths = snapshot.paths,
                    createdAt = snapshot.createdAt,
                    updatedAt = snapshot.updatedAt,
                    lastAppliedAt = snapshot.lastAppliedAt
                )
            }
        ).copyWithRestoredProjectConfig(restoredProjectConfig)
        saveCurrentSession()
        syncApprovedApprovalScopesState()
    }

    fun updateRemoteTaskRepositoryContext(
        repositoryOwner: String?,
        repositoryName: String?,
        repositoryLabel: String?,
        editable: Boolean
    ) {
        val normalizedOwner = repositoryOwner?.trim()?.takeIf { it.isNotBlank() }
        val normalizedName = repositoryName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedLabel = repositoryLabel?.trim()?.takeIf { it.isNotBlank() }
            ?: if (normalizedOwner != null && normalizedName != null) {
                "$normalizedOwner/$normalizedName"
            } else {
                null
            }
        val current = _state.value
        val normalizedEditable = normalizedLabel != null && editable
        if (
            current.remoteTaskRepositoryOwner == normalizedOwner &&
            current.remoteTaskRepositoryName == normalizedName &&
            current.remoteTaskRepositoryLabel == normalizedLabel &&
            current.remoteTaskRepositoryEditable == normalizedEditable
        ) {
            return
        }
        _state.value = current.copy(
            remoteTaskRepositoryOwner = normalizedOwner,
            remoteTaskRepositoryName = normalizedName,
            remoteTaskRepositoryLabel = normalizedLabel,
            remoteTaskRepositoryEditable = normalizedEditable
        )
        saveCurrentSession()
    }

    fun setCurrentSessionGoal(goal: String) {
        val normalizedGoal = goal.trim()
        if (normalizedGoal.isBlank()) return
        _state.value = _state.value.copy(sessionGoal = normalizedGoal)
        saveCurrentSession()
    }

    fun clearCurrentSessionGoal() {
        if (_state.value.sessionGoal.isNullOrBlank()) return
        _state.value = _state.value.copy(sessionGoal = null)
        saveCurrentSession()
    }

    fun switchProjectScope(scopePath: String?) {
        val current = _state.value
        val resolvedScopePath = activeProjectScopePath(
            activeScopePath = scopePath,
            projectPath = current.projectPath
        )
        val activeProjectScope = resolveActiveProjectScope(
            activeScopePath = resolvedScopePath,
            repoScopedConfigs = current.repoScopedProjectConfigMap(),
            fallbackProjectConfig = current.toSessionProjectConfig()
        )
        if (current.matchesActiveProjectConfig(activeProjectScope)) {
            return
        }
        _state.value = current.copyWithProjectConfig(
            activeProjectScopePath = activeProjectScope.activeScopePath,
            projectConfig = activeProjectScope.activeProjectConfig
        )
        saveCurrentSession()
    }

    /**
     * 列出所有会话
     */
    fun listSessions(): List<SessionSummary> = conversationStore.listSessions()

    fun listArchivedSessions(): List<ArchivedSessionSummary> = conversationStore.listArchivedSessions()

    fun loadArchivedSessionSummary(sessionId: String): ArchivedSessionSummary? =
        conversationStore.loadArchivedSessionSummary(sessionId)

    fun listArchivedMemoryCandidates(limit: Int = 20): List<ArchivedMemoryCandidate> =
        conversationStore.listArchivedMemoryCandidates(limit)

    fun dismissArchivedMemoryCandidate(
        sessionId: String,
        reason: String? = null
    ): ArchivedMemoryCandidateMutationResult {
        val resolutionSummary = reason?.trim()?.takeIf { it.isNotBlank() } ?: "已关闭归档记忆候选。"
        if (!conversationStore.markArchivedMemoryCandidateDismissed(
                sessionId,
                dismissedReason = reason,
                resolutionSummary = resolutionSummary
            )
        ) {
            return ArchivedMemoryCandidateMutationResult(
                success = false,
                message = "未找到待关闭的归档记忆候选。"
            )
        }
        return ArchivedMemoryCandidateMutationResult(
            success = true,
            message = "已关闭归档记忆候选。",
            status = ArchivedMemoryCandidateStatus.DISMISSED,
            resolutionSource = ArchivedMemoryCandidateResolutionSource.USER_ACTION,
            resolutionSummary = resolutionSummary
        )
    }

    fun consumeArchivedMemoryCandidate(
        sessionId: String,
        reason: String? = null
    ): ArchivedMemoryCandidateMutationResult {
        val resolutionSummary = reason?.trim()?.takeIf { it.isNotBlank() } ?: "已将归档记忆候选标记为已处理。"
        if (!conversationStore.markArchivedMemoryCandidateConsumed(
                sessionId,
                consumedReason = reason,
                resolutionSummary = resolutionSummary
            )
        ) {
            return ArchivedMemoryCandidateMutationResult(
                success = false,
                message = "未找到待处理的归档记忆候选。"
            )
        }
        return ArchivedMemoryCandidateMutationResult(
            success = true,
            message = "已将归档记忆候选标记为已处理。",
            status = ArchivedMemoryCandidateStatus.CONSUMED,
            resolutionSource = ArchivedMemoryCandidateResolutionSource.USER_ACTION,
            resolutionSummary = resolutionSummary
        )
    }

    suspend fun acceptArchivedMemoryCandidate(
        sessionId: String,
        scope: ArchivedMemoryCandidateScope? = null
    ): ArchivedMemoryCandidateMutationResult {
        val candidate = conversationStore.loadArchivedMemoryCandidate(sessionId)
            ?: return ArchivedMemoryCandidateMutationResult(
                success = false,
                message = "未找到待接受的归档记忆候选。"
            )
        return when (scope ?: candidate.suggestedScope) {
            ArchivedMemoryCandidateScope.PROJECT ->
                acceptArchivedMemoryCandidateAsProjectMemory(candidate)
            ArchivedMemoryCandidateScope.GLOBAL ->
                acceptArchivedMemoryCandidateAsGlobalMemory(candidate)
        }
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String): Boolean {
        if (!ensureSessionLifecycleAllowed(SessionLifecycleAction.DELETE_SESSION)) return false
        val stateBeforeDeletion = _state.value
        val deletingCurrentSession = sessionId == currentSessionId
        if (deletingCurrentSession) {
            latestPersistJob?.cancel()
            latestPersistJob = null
        }
        conversationStore.deleteSession(sessionId)
        if (cachedCurrentPersistedSession?.id == sessionId) {
            cachedCurrentPersistedSession = null
        }
        if (deletingCurrentSession) {
            replaceCurrentSessionWithBlank(
                savePrevious = false,
                trigger = "delete_current_session"
            )
        } else {
            syncApprovedApprovalScopesState()
        }
        emitSessionLifecycleNotice(
            notice = buildSessionDeletionNotice(
                deletedCurrentSession = deletingCurrentSession,
                recentSessionHistoryClue = stateBeforeDeletion.recentSessionHistoryClue
            ),
            source = if (deletingCurrentSession) {
                "delete-current-session"
            } else {
                "delete-background-session"
            }
        )
        return true
    }

    fun updateProjectConfig(
        scopePath: String? = activeProjectScopePath(_state.value.activeProjectScopePath, _state.value.projectPath),
        rules: List<GlobalRule>? = null,
        memories: List<GlobalMemory>? = null,
        skills: List<GlobalSkill>? = null
    ) {
        val current = _state.value
        val normalizedScope = normalizeProjectPath(scopePath)
        val workspaceScope = normalizeProjectPath(current.projectPath)
        val updateResult = applyProjectConfigUpdate(
            scopePath = normalizedScope,
            workspaceScopePath = workspaceScope,
            currentProjectConfig = current.toSessionProjectConfig(),
            repoScopedConfigs = current.repoScopedProjectConfigMap()
        ) { projectConfig ->
            projectConfig.copy(
                projectRules = rules ?: projectConfig.projectRules,
                projectMemories = memories ?: projectConfig.projectMemories,
                projectSkills = skills ?: projectConfig.projectSkills
            )
        }
        _state.value = current.copy(
            projectRules = updateResult.activeProjectConfig.projectRules,
            projectMemories = updateResult.activeProjectConfig.projectMemories,
            projectSkills = updateResult.activeProjectConfig.projectSkills
        ).copyWithRepoScopedProjectConfigs(updateResult.repoScopedConfigs)
        saveCurrentSession()
    }

    private suspend fun acceptArchivedMemoryCandidateAsGlobalMemory(
        candidate: ArchivedMemoryCandidate
    ): ArchivedMemoryCandidateMutationResult {
        return runCatching {
            val config = configRepository.getConfig()
            val existingMemory = findMatchingMemory(
                memories = config.globalMemories,
                candidate = candidate
            )
            val targetMemory = existingMemory ?: GlobalMemory(
                id = UUID.randomUUID().toString().take(8),
                title = candidate.suggestedTitle,
                content = candidate.suggestedContent
            )
            if (existingMemory == null) {
                configRepository.saveConfig(
                    config.copy(globalMemories = config.globalMemories + targetMemory)
                )
            }
            val resolution = buildArchivedMemoryCandidateAcceptanceResolution(
                scope = ArchivedMemoryCandidateScope.GLOBAL,
                deduplicated = existingMemory != null
            )
            if (!conversationStore.markArchivedMemoryCandidateAccepted(
                    sessionId = candidate.sourceSessionId,
                    scope = ArchivedMemoryCandidateScope.GLOBAL,
                    memoryId = targetMemory.id,
                    resolutionSource = resolution.source,
                    resolutionSummary = resolution.summary
                )
            ) {
                return ArchivedMemoryCandidateMutationResult(
                    success = false,
                    message = "已写入全局记忆，但归档候选接受状态回写失败。",
                    status = ArchivedMemoryCandidateStatus.ACCEPTED,
                    resolutionSource = resolution.source,
                    resolutionSummary = resolution.summary,
                    scope = ArchivedMemoryCandidateScope.GLOBAL,
                    memoryId = targetMemory.id,
                    deduplicated = existingMemory != null
                )
            }
            ArchivedMemoryCandidateMutationResult(
                success = true,
                message = resolution.summary,
                status = ArchivedMemoryCandidateStatus.ACCEPTED,
                resolutionSource = resolution.source,
                resolutionSummary = resolution.summary,
                scope = ArchivedMemoryCandidateScope.GLOBAL,
                memoryId = targetMemory.id,
                deduplicated = existingMemory != null
            )
        }.getOrElse { error ->
            ArchivedMemoryCandidateMutationResult(
                success = false,
                message = "接受归档候选为全局记忆失败: ${error.message}"
            )
        }
    }

    private fun acceptArchivedMemoryCandidateAsProjectMemory(
        candidate: ArchivedMemoryCandidate
    ): ArchivedMemoryCandidateMutationResult {
        return runCatching {
            val targetScopePath = normalizeProjectPath(candidate.sourceProjectPath)
                ?: activeProjectScopePath(
                    activeScopePath = _state.value.activeProjectScopePath,
                    projectPath = _state.value.projectPath
                )
                ?: return ArchivedMemoryCandidateMutationResult(
                    success = false,
                    message = "该归档候选缺少项目作用域，无法保存为项目记忆。"
                )
            val current = _state.value
            val scopedConfig = current.resolveProjectConfig(targetScopePath)
            val existingMemory = findMatchingMemory(
                memories = scopedConfig.projectMemories,
                candidate = candidate
            )
            val targetMemory = existingMemory ?: GlobalMemory(
                id = UUID.randomUUID().toString().take(8),
                title = candidate.suggestedTitle,
                content = candidate.suggestedContent
            )
            if (existingMemory == null) {
                updateProjectConfig(
                    scopePath = targetScopePath,
                    memories = scopedConfig.projectMemories + targetMemory
                )
            }
            val resolution = buildArchivedMemoryCandidateAcceptanceResolution(
                scope = ArchivedMemoryCandidateScope.PROJECT,
                deduplicated = existingMemory != null
            )
            if (!conversationStore.markArchivedMemoryCandidateAccepted(
                    sessionId = candidate.sourceSessionId,
                    scope = ArchivedMemoryCandidateScope.PROJECT,
                    memoryId = targetMemory.id,
                    resolutionSource = resolution.source,
                    resolutionSummary = resolution.summary
                )
            ) {
                return ArchivedMemoryCandidateMutationResult(
                    success = false,
                    message = "已写入项目记忆，但归档候选接受状态回写失败。",
                    status = ArchivedMemoryCandidateStatus.ACCEPTED,
                    resolutionSource = resolution.source,
                    resolutionSummary = resolution.summary,
                    scope = ArchivedMemoryCandidateScope.PROJECT,
                    memoryId = targetMemory.id,
                    deduplicated = existingMemory != null
                )
            }
            ArchivedMemoryCandidateMutationResult(
                success = true,
                message = resolution.summary,
                status = ArchivedMemoryCandidateStatus.ACCEPTED,
                resolutionSource = resolution.source,
                resolutionSummary = resolution.summary,
                scope = ArchivedMemoryCandidateScope.PROJECT,
                memoryId = targetMemory.id,
                deduplicated = existingMemory != null
            )
        }.getOrElse { error ->
            ArchivedMemoryCandidateMutationResult(
                success = false,
                message = "接受归档候选为项目记忆失败: ${error.message}"
            )
        }
    }

    private fun findMatchingMemory(
        memories: List<GlobalMemory>,
        candidate: ArchivedMemoryCandidate
    ): GlobalMemory? {
        val normalizedTitle = candidate.suggestedTitle.trim()
        val normalizedContent = candidate.suggestedContent.trim()
        return memories.firstOrNull { memory ->
            memory.title.trim() == normalizedTitle &&
                memory.content.trim() == normalizedContent
        }
    }

    private fun buildArchivedMemoryCandidateAcceptanceResolution(
        scope: ArchivedMemoryCandidateScope,
        deduplicated: Boolean
    ): ArchivedMemoryCandidateResolutionTelemetry {
        if (deduplicated) {
            return ArchivedMemoryCandidateResolutionTelemetry(
                source = ArchivedMemoryCandidateResolutionSource.AUTO_DEDUPLICATED_REUSE,
                summary = when (scope) {
                    ArchivedMemoryCandidateScope.PROJECT -> "已将归档候选关联到现有项目记忆。"
                    ArchivedMemoryCandidateScope.GLOBAL -> "已将归档候选关联到现有全局记忆。"
                }
            )
        }
        return ArchivedMemoryCandidateResolutionTelemetry(
            source = ArchivedMemoryCandidateResolutionSource.USER_ACTION,
            summary = when (scope) {
                ArchivedMemoryCandidateScope.PROJECT -> "已将归档候选保存为项目记忆。"
                ArchivedMemoryCandidateScope.GLOBAL -> "已将归档候选保存为全局记忆。"
            }
        )
    }

    fun updateProjectToolPreferences(
        scopePath: String? = activeProjectScopePath(_state.value.activeProjectScopePath, _state.value.projectPath),
        preferences: ProjectToolPreferences?
    ) {
        val current = _state.value
        val normalizedScope = normalizeProjectPath(scopePath)
        val workspaceScope = normalizeProjectPath(current.projectPath)
        val updateResult = applyProjectConfigUpdate(
            scopePath = normalizedScope,
            workspaceScopePath = workspaceScope,
            currentProjectConfig = current.toSessionProjectConfig(),
            repoScopedConfigs = current.repoScopedProjectConfigMap()
        ) { projectConfig ->
            projectConfig.copy(projectToolPreferences = preferences)
        }
        _state.value = current.copy(
            projectToolPreferences = updateResult.activeProjectConfig.projectToolPreferences
        ).copyWithRepoScopedProjectConfigs(updateResult.repoScopedConfigs)
        saveCurrentSession()
    }

    fun updateProjectKnowledgePaths(paths: List<String>) {
        _state.value = _state.value.copy(
            projectKnowledgePaths = paths
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        )
        saveCurrentSession()
    }

    private fun normalizeProjectKnowledgePaths(paths: List<String>): List<String> {
        return paths
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun saveProjectKnowledgeSnapshot(name: String, paths: List<String>): ProjectKnowledgeSnapshotMutationResult {
        val normalizedName = name.trim()
        val normalizedPaths = normalizeProjectKnowledgePaths(paths)
        if (normalizedName.isBlank()) {
            return ProjectKnowledgeSnapshotMutationResult(
                success = false,
                message = "快照名称不能为空"
            )
        }
        if (normalizedPaths.isEmpty()) {
            return ProjectKnowledgeSnapshotMutationResult(
                success = false,
                message = "当前没有可保存的知识文件"
            )
        }
        val current = _state.value
        val existingSnapshot = current.projectKnowledgeSnapshots.firstOrNull {
            it.name.equals(normalizedName, ignoreCase = true)
        }
        val now = System.currentTimeMillis()
        val updatedSnapshots = if (existingSnapshot != null) {
            current.projectKnowledgeSnapshots.map { snapshot ->
                if (snapshot.id == existingSnapshot.id) {
                    snapshot.copy(
                        name = normalizedName,
                        paths = normalizedPaths,
                        updatedAt = now
                    )
                } else {
                    snapshot
                }
            }
        } else {
            listOf(
                ProjectKnowledgeSnapshotUi(
                    id = UUID.randomUUID().toString(),
                    name = normalizedName,
                    paths = normalizedPaths,
                    createdAt = now,
                    updatedAt = now
                )
            ) + current.projectKnowledgeSnapshots
        }
        _state.value = current.copy(
            projectKnowledgeSnapshots = updatedSnapshots
                .sortedByDescending { it.updatedAt }
                .take(8)
        )
        saveCurrentSession()
        return ProjectKnowledgeSnapshotMutationResult(
            success = true,
            message = if (existingSnapshot != null) {
                "已覆盖快照 ${existingSnapshot.name}，更新 ${normalizedPaths.size} 个知识文件"
            } else {
                "已保存快照 $normalizedName，包含 ${normalizedPaths.size} 个知识文件"
            }
        )
    }

    fun renameProjectKnowledgeSnapshot(
        snapshotId: String,
        newName: String
    ): ProjectKnowledgeSnapshotMutationResult {
        val current = _state.value
        val targetSnapshot = current.projectKnowledgeSnapshots.firstOrNull { it.id == snapshotId }
            ?: return ProjectKnowledgeSnapshotMutationResult(
                success = false,
                message = "未找到要重命名的知识快照"
            )
        val normalizedName = newName.trim()
        if (normalizedName.isBlank()) {
            return ProjectKnowledgeSnapshotMutationResult(
                success = false,
                message = "快照名称不能为空"
            )
        }
        val conflictingSnapshot = current.projectKnowledgeSnapshots.firstOrNull { snapshot ->
            snapshot.id != snapshotId && snapshot.name.equals(normalizedName, ignoreCase = true)
        }
        if (conflictingSnapshot != null) {
            return ProjectKnowledgeSnapshotMutationResult(
                success = false,
                message = "已存在同名快照 ${conflictingSnapshot.name}，请换一个名称"
            )
        }
        if (targetSnapshot.name == normalizedName) {
            return ProjectKnowledgeSnapshotMutationResult(
                success = true,
                message = "快照名称未变化，仍为 ${targetSnapshot.name}"
            )
        }
        val now = System.currentTimeMillis()
        _state.value = current.copy(
            projectKnowledgeSnapshots = current.projectKnowledgeSnapshots.map { snapshot ->
                if (snapshot.id == snapshotId) {
                    snapshot.copy(
                        name = normalizedName,
                        updatedAt = now
                    )
                } else {
                    snapshot
                }
            }
                .sortedByDescending { it.updatedAt }
                .take(8)
        )
        saveCurrentSession()
        return ProjectKnowledgeSnapshotMutationResult(
            success = true,
            message = "已将快照 ${targetSnapshot.name} 重命名为 $normalizedName"
        )
    }

    fun applyProjectKnowledgeSnapshot(snapshotId: String): ProjectKnowledgeSnapshotMutationResult {
        val current = _state.value
        val targetSnapshot = current.projectKnowledgeSnapshots.firstOrNull { it.id == snapshotId }
            ?: return ProjectKnowledgeSnapshotMutationResult(
                success = false,
                message = "未找到要应用的知识快照"
            )
        val normalizedPaths = normalizeProjectKnowledgePaths(targetSnapshot.paths)
        if (normalizedPaths.isEmpty()) {
            return ProjectKnowledgeSnapshotMutationResult(
                success = false,
                message = "该快照没有可应用的知识文件"
            )
        }
        val now = System.currentTimeMillis()
        _state.value = current.copy(
            projectKnowledgePaths = normalizedPaths,
            projectKnowledgeSnapshots = current.projectKnowledgeSnapshots.map { snapshot ->
                if (snapshot.id == snapshotId) {
                    snapshot.copy(lastAppliedAt = now)
                } else {
                    snapshot
                }
            }
        )
        saveCurrentSession()
        return ProjectKnowledgeSnapshotMutationResult(
            success = true,
            message = "已应用快照 ${targetSnapshot.name}，当前挂载 ${normalizedPaths.size} 个知识文件"
        )
    }

    fun deleteProjectKnowledgeSnapshot(snapshotId: String): ProjectKnowledgeSnapshotMutationResult {
        val current = _state.value
        val targetSnapshot = current.projectKnowledgeSnapshots.firstOrNull { it.id == snapshotId }
            ?: return ProjectKnowledgeSnapshotMutationResult(
                success = false,
                message = "未找到要删除的知识快照"
            )
        val updatedSnapshots = current.projectKnowledgeSnapshots.filterNot { it.id == snapshotId }
        _state.value = current.copy(projectKnowledgeSnapshots = updatedSnapshots)
        saveCurrentSession()
        return ProjectKnowledgeSnapshotMutationResult(
            success = true,
            message = "已删除快照 ${targetSnapshot.name}"
        )
    }

    fun clearLastAutoRouteDecision() {
        if (_state.value.lastAutoRouteDecision == null && !_state.value.autoRoutingInProgress) return
        _state.value = applyAutoRouteClearedTransition(_state.value)
        saveCurrentSession()
    }

    fun clearLastWorkflowFallback() {
        if (_state.value.lastWorkflowFallback == null) return
        _state.value = applyWorkflowFallbackClearedTransition(_state.value)
        saveCurrentSession()
    }

    fun dismissPendingWorkflowPlan() {
        if (_state.value.pendingWorkflowPlan == null) return
        _state.value = applyWorkflowPlanDismissedTransition(_state.value)
        recordConversationPromptCheckpoint(summary = "交互状态: 已关闭执行计划")
        saveCurrentSession()
    }

    fun dismissPendingClarification() {
        if (_state.value.pendingClarificationRequest == null) return
        _state.value = applyClarificationDismissedTransition(_state.value)
        recordConversationPromptCheckpoint(summary = "交互状态: 已关闭澄清请求")
        saveCurrentSession()
    }

    fun searchProjectFiles(query: String, limit: Int = 20): List<FileMentionUi> {
        val projectPath = activeProjectScopePath(
            _state.value.activeProjectScopePath,
            _state.value.projectPath
        ) ?: return emptyList()
        val normalizedQuery = query.trim().replace('\\', '/')
        val results = linkedMapOf<String, FileMentionUi>()

        resolveMentionFromQuery(projectPath, normalizedQuery)?.let { mention ->
            results[mention.path] = mention
        }

        val discovered = mutableListOf<FileMentionUi>()
        collectProjectFiles(
            rootPath = projectPath,
            currentDir = projectPath,
            depth = 0,
            out = discovered,
            maxFiles = 160
        )

        discovered
            .asSequence()
            .filter { mention ->
                normalizedQuery.isBlank() ||
                    mention.displayPath.contains(normalizedQuery, ignoreCase = true)
            }
            .sortedWith(
                compareBy<FileMentionUi>(
                    { matchRank(it.displayPath, normalizedQuery) },
                    { it.displayPath.length },
                    { it.displayPath }
                )
            )
            .take(limit)
            .forEach { mention ->
                results.putIfAbsent(mention.path, mention)
            }

        return results.values.take(limit)
    }
    fun renameSession(sessionId: String, newTitle: String): Boolean {
        val normalizedTitle = newTitle.trim()
        if (normalizedTitle.isBlank()) return false

        if (sessionId == currentSessionId) {
            _state.value = _state.value.copy(sessionTitle = normalizedTitle)
            if (shouldPersistSession(_state.value)) {
                saveCurrentSession()
            }
            return true
        }

        val renamed = conversationStore.renameSessionTitle(sessionId, normalizedTitle)
        if (renamed) {
            syncApprovedApprovalScopesState()
        }
        return renamed
    }

    fun exportCurrentSession(
        format: ConversationExportFormat,
        config: ProviderConfig? = null
    ): Result<ConversationExportData> {
        return runCatching {
            val state = _state.value
            require(canExportSession(state, format)) { "当前会话还没有可导出的内容" }

            val session = buildPersistedSession(config)
            val resolvedConfig = config ?: lastSessionConfig
            val exportName = buildExportFileName(session.title, format)
            val content = when (format) {
                ConversationExportFormat.MARKDOWN -> buildMarkdownExport(session)
                ConversationExportFormat.JSON ->
                    SensitiveDataSanitizer.sanitizeText(conversationStore.encodeSession(session))
                ConversationExportFormat.DOCTOR -> buildDoctorReport(
                    session = session,
                    config = resolvedConfig,
                    pendingCrash = PendingCrashStore(context).loadPendingCrash()
                )
            }
            ConversationExportData(
                fileName = exportName,
                mimeType = format.mimeType,
                content = content
            )
        }
    }

    suspend fun compressCurrentContext(): Result<ContextCompressionUi> {
        return runCatching {
            val state = _state.value
            val eligibleMessages = compressionEligibleMessages(state)
            require(!state.isProcessing) { "处理中暂时不能压缩上下文" }
            require(eligibleMessages.size >= MIN_MESSAGES_FOR_COMPRESSION) { "消息还不够多，暂时不需要压缩" }

            val keepRecentCount = RECENT_MESSAGES_TO_KEEP.coerceAtMost(
                eligibleMessages.size - MIN_MESSAGES_TO_COMPRESS
            )
            val compressedMessages = eligibleMessages.dropLast(keepRecentCount)
            require(compressedMessages.size >= MIN_MESSAGES_TO_COMPRESS) { "可压缩的历史消息太少" }

            val lastCompressed = compressedMessages.last()
            val nextVersion = (state.compressionSnapshots.maxOfOrNull { it.version } ?: 0) + 1
            val snapshot = ContextCompressionUi(
                id = "cmp-${System.currentTimeMillis()}",
                version = nextVersion,
                summary = generateCompressionSummary(state, compressedMessages),
                sourceMessageCount = compressedMessages.size,
                sourceEndMessageId = lastCompressed.id,
                sourceEndMessageIndex = compressedMessages.lastIndex,
                createdAt = System.currentTimeMillis(),
                active = true
            )
            val updatedSnapshots = state.compressionSnapshots
                .map { it.copy(active = false) } + snapshot

            _state.value = state.copy(
                compressionSnapshot = snapshot,
                compressionSnapshots = updatedSnapshots
            )
            saveCurrentSession()
            snapshot
        }
    }

    fun disableContextCompression(): Boolean {
        val state = _state.value
        val snapshot = state.compressionSnapshot ?: return false
        if (!snapshot.active) return false

        val updatedSnapshot = snapshot.copy(active = false)
        _state.value = state.copy(
            compressionSnapshot = updatedSnapshot,
            compressionSnapshots = replaceCompressionSnapshot(
                snapshots = state.compressionSnapshots,
                updatedSnapshot = updatedSnapshot
            )
        )
        saveCurrentSession()
        return true
    }

    fun enableContextCompression(): Boolean {
        val state = _state.value
        val snapshot = state.compressionSnapshot ?: return false
        if (snapshot.active) return false

        val updatedSnapshot = snapshot.copy(active = true)
        _state.value = state.copy(
            compressionSnapshot = updatedSnapshot,
            compressionSnapshots = state.compressionSnapshots.map { item ->
                if (item.id == updatedSnapshot.id) {
                    updatedSnapshot
                } else {
                    item.copy(active = false)
                }
            }
        )
        saveCurrentSession()
        return true
    }

    private suspend fun maybeAutoCompressContext(
        config: ProviderConfig,
        allowCreateSnapshot: Boolean = true
    ): String? {
        val state = _state.value
        val preview = estimateContextCompressionPreview(state) ?: return null
        if (!shouldAutoCompressContext(state, preview)) return null
        lastSessionConfig = config

        val existingSnapshot = state.compressionSnapshot
        if (
            existingSnapshot != null &&
            !existingSnapshot.active &&
            countNewMessagesSinceSnapshot(state, existingSnapshot) <= AUTO_COMPRESSION_ENABLE_EXISTING_MAX_NEW_MESSAGES &&
            enableContextCompression()
        ) {
            return "已自动启用上下文摘要 V${existingSnapshot.version}"
        }
        if (!allowCreateSnapshot) return null

        val snapshot = compressCurrentContext().getOrNull() ?: return null
        return "已自动压缩 ${snapshot.sourceMessageCount} 条历史消息，生成摘要 V${snapshot.version}"
    }

    private fun shouldAutoCompressContext(
        state: SessionState,
        preview: ContextCompressionPreviewUi
    ): Boolean {
        val largeEnough = preview.compressibleMessageCount >= AUTO_COMPRESSION_MIN_COMPRESSIBLE_MESSAGES ||
            preview.estimatedCurrentContextTokens >= AUTO_COMPRESSION_TRIGGER_TOKENS
        if (!largeEnough) return false

        val savingsWorthwhile = preview.estimatedTokensSaved >= AUTO_COMPRESSION_MIN_SAVED_TOKENS ||
            preview.estimatedReductionPercent >= AUTO_COMPRESSION_MIN_REDUCTION_PERCENT
        if (!savingsWorthwhile) return false

        val currentSnapshot = state.compressionSnapshot
        if (currentSnapshot?.active == true) {
            val newMessages = countNewMessagesSinceSnapshot(state, currentSnapshot)
            if (newMessages < AUTO_COMPRESSION_NEW_MESSAGES_THRESHOLD) return false
        }
        return true
    }

    private fun countNewMessagesSinceSnapshot(
        state: SessionState,
        snapshot: ContextCompressionUi
    ): Int {
        return compressionEligibleMessages(state).count { it.id > snapshot.sourceEndMessageId }
    }

    private fun mergeToastMessages(primary: String?, secondary: String?): String? {
        return listOfNotNull(
            primary?.trim()?.takeIf { it.isNotBlank() },
            secondary?.trim()?.takeIf { it.isNotBlank() }
        ).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * 发送消息
     */
    suspend fun sendMessage(
        text: String,
        mentionedFiles: List<FileMentionUi> = emptyList(),
        pendingImages: List<PendingImageAttachmentUi> = emptyList(),
        selectedSkills: List<GlobalSkill> = emptyList()
    ): String? {
        val normalizedText = text.trim()
        if ((normalizedText.isBlank() && pendingImages.isEmpty()) || _state.value.isProcessing) return null
        val globalConfig = configRepository.getConfig()
        val config = resolveEffectiveSessionConfig(globalConfig = globalConfig)
        if (normalizedText.isBlank() && pendingImages.isNotEmpty() && !config.isMultimodalEnabled()) {
            appendSystemMessage(
                content = "⚠️ 当前已关闭多模态，图片消息未发送。请到设置里开启多模态后再试。",
                source = "send_message:image_only_multimodal_disabled"
            )
            return null
        }
        val autoCompressionToast = maybeAutoCompressContext(
            config = config,
            allowCreateSnapshot = false
        )
        val executionConfig = resolveExecutionConfig(
            baseConfig = config,
            goal = normalizedText,
            mentionedFiles = mentionedFiles
        )
        val executionToast = buildExecutionProfileToast(
            baseConfig = config,
            executionConfig = executionConfig
        )
        sendMessageInternal(
            userVisibleText = normalizedText,
            modelInput = normalizedText,
            mentionedFiles = mentionedFiles,
            pendingImages = pendingImages,
            configOverride = executionConfig,
            extraUserContext = listOfNotNull(
                buildSkillSelectionUserContext(selectedSkills),
                buildExecutionProfileUserContext(
                    goal = normalizedText,
                    baseConfig = config,
                    executionConfig = executionConfig
                )
            ).joinToString("\n\n").takeIf { it.isNotBlank() }
        )
        return mergeToastMessages(autoCompressionToast, executionToast)
    }

    suspend fun autoRouteMessage(
        text: String,
        mentionedFiles: List<FileMentionUi> = emptyList()
    ): String? {
        val normalizedText = text.trim()
        if (normalizedText.isBlank() || _state.value.isProcessing) return null

        val globalConfig = configRepository.getConfig()
        val config = resolveEffectiveSessionConfig(globalConfig = globalConfig)
        val plannerConfig = config.getPlannerResolvedConfig()
        val executionConfig = resolveExecutionConfig(
            baseConfig = config,
            goal = normalizedText,
            mentionedFiles = mentionedFiles
        )
        lastSessionConfig = plannerConfig
        val apiKey = plannerConfig.getActiveApiKey().trim()
        if (apiKey.isBlank()) {
            _state.value = _state.value.copy(
                error = "⚠️ 未配置 API Key。请先到设置页完成模型配置。"
            )
            return null
        }

        val provider = ProviderRegistry.getActiveProvider(plannerConfig.activeProviderId)
        val stateBeforePlanning = _state.value
        val history = buildHistory()
        val compressionContext = buildCompressionContext()
        val recentSessionHistoryClue = stateBeforePlanning.recentSessionHistoryClue
        _state.value = applyAutoRouteStartTransition(_state.value)
        val decision: AutoRouteDecisionUi = try {
            val rawText = callProviderWithConfiguredStreaming(
                provider = provider,
                config = plannerConfig,
                request = ChatRequest(
                    messages = buildAutoRoutePrompt(
                        goal = normalizedText,
                        history = history,
                        compressionContext = compressionContext,
                        mentionedFiles = mentionedFiles
                    ),
                    model = plannerConfig.getActiveModel(),
                    temperature = 0.1,
                    maxTokens = minOf(plannerConfig.maxTokens, 400),
                    stream = plannerConfig.isStreamingResponsesEnabled(),
                    reasoningEffort = plannerConfig.getActiveReasoningEffort(),
                    thinkingMode = plannerConfig.getActiveThinkingMode(),
                    tools = null
                )
            ).content?.trim().orEmpty()
            parseAutoRouteDecision(rawText)
        } catch (e: Exception) {
            _state.value = applyAutoRouteFailureTransition(_state.value)
            when (
                resolveWorkflowFailureFallbackMode(
                    config = plannerConfig,
                    failureType = WorkflowFailureType.AUTO_ROUTE_FAILURE,
                    hasMentionedFiles = mentionedFiles.isNotEmpty(),
                    source = WorkflowFailureFallbackSource.AUTO_ROUTE,
                    projectType = currentProjectWorkflowType(),
                    providerId = executionConfig.activeProviderId,
                    projectRiskLevel = currentProjectWorkflowRiskLevel()
                )
            ) {
                WorkflowFailureFallbackMode.DIRECT_EXECUTION -> {
                    val autoCompressionToast = maybeAutoCompressContext(plannerConfig)
                    val executionToast = buildExecutionProfileToast(
                        baseConfig = config,
                        executionConfig = executionConfig
                    )
                    sendMessageInternal(
                        userVisibleText = normalizedText,
                        modelInput = normalizedText,
                        mentionedFiles = mentionedFiles,
                        executionGoal = normalizedText,
                        configOverride = executionConfig,
                        extraUserContext = buildExecutionProfileUserContext(
                            goal = normalizedText,
                            baseConfig = config,
                            executionConfig = executionConfig
                        )
                    )
                    applyWorkflowFallback(
                        message = "发送前自动分流失败，已按配置回退为直接执行。",
                        config = plannerConfig
                    )
                    return mergeToastMessages(autoCompressionToast, executionToast)
                }
                WorkflowFailureFallbackMode.LOCAL_CLARIFICATION -> {
                    val fallbackRequest = buildStateAwareLocalFallbackClarificationRequest(
                        goal = normalizedText,
                        mentionedFiles = mentionedFiles,
                        previousAnswers = emptyList(),
                        turnIndex = 1,
                        maxTurns = MAX_CLARIFICATION_TURNS,
                        source = ClarificationSource.AUTO_ROUTE,
                        state = _state.value,
                        recentSessionHistoryClue = _state.value.recentSessionHistoryClue
                    )
                    _state.value = applyAutoRouteLocalClarificationFallbackTransition(
                        state = _state.value,
                        request = fallbackRequest
                    )
                    recordClarificationPromptCheckpoint(fallbackRequest)
                    applyWorkflowFallback(
                        message = "发送前自动分流失败，已按配置回退为本地通用澄清问题。",
                        config = plannerConfig
                    )
                    return null
                }
                WorkflowFailureFallbackMode.FOLLOW_SCENARIO_DEFAULT -> return null
            }
        }

        _state.value = applyAutoRouteDecisionResolvedTransition(
            state = _state.value,
            decision = decision
        )
        saveCurrentSession(plannerConfig)
        when (decision.action) {
            AutoRouteAction.DIRECT -> return sendMessage(normalizedText, mentionedFiles)
            AutoRouteAction.PLAN -> generateWorkflowPlan(normalizedText, mentionedFiles)
            AutoRouteAction.CLARIFY -> generateClarificationQuestion(
                goal = normalizedText,
                mentionedFiles = mentionedFiles,
                source = ClarificationSource.AUTO_ROUTE
            )
        }
        return null
    }

    suspend fun generateWorkflowPlan(goal: String, mentionedFiles: List<FileMentionUi> = emptyList()) {
        val normalizedGoal = goal.trim()
        if (normalizedGoal.isBlank() || _state.value.isProcessing) return

        val globalConfig = configRepository.getConfig()
        val config = resolveEffectiveSessionConfig(globalConfig = globalConfig)
        val plannerConfig = config.getPlannerResolvedConfig()
        maybeAutoCompressContext(plannerConfig)
        lastSessionConfig = plannerConfig
        val apiKey = plannerConfig.getActiveApiKey().trim()
        if (apiKey.isBlank()) {
            _state.value = _state.value.copy(
                error = "⚠️ 未配置 API Key。请先到设置页完成模型配置。"
            )
            return
        }

        val provider = ProviderRegistry.getActiveProvider(plannerConfig.activeProviderId)
        val stateBeforePlanning = _state.value
        val history = buildHistory()
        val compressionContext = buildCompressionContext()
        val recentSessionHistoryClue = stateBeforePlanning.recentSessionHistoryClue
        _state.value = applyWorkflowPlanGenerationStartTransition(_state.value)
        try {
            val response = callProviderWithConfiguredStreaming(
                provider = provider,
                config = plannerConfig,
                request = ChatRequest(
                    messages = buildWorkflowPlanPrompt(
                        goal = normalizedGoal,
                        history = history,
                        compressionContext = compressionContext,
                        mentionedFiles = mentionedFiles
                    ),
                    model = plannerConfig.getActiveModel(),
                    temperature = 0.2,
                    maxTokens = minOf(plannerConfig.maxTokens, 1200),
                    stream = plannerConfig.isStreamingResponsesEnabled(),
                    reasoningEffort = plannerConfig.getActiveReasoningEffort(),
                    thinkingMode = plannerConfig.getActiveThinkingMode(),
                    tools = null
                )
            )
            val rawPlan = response.content?.trim().orEmpty()
            val parsedPlan = parseWorkflowPlan(
                goal = normalizedGoal,
                rawPlan = rawPlan,
                mentionedFiles = mentionedFiles,
                recentSessionHistoryClue = recentSessionHistoryClue
            )
            _state.value = applyWorkflowPlanGeneratedTransition(
                state = _state.value,
                plan = parsedPlan
            )
            recordConversationPromptCheckpoint(
                summary = "交互状态: 已生成执行计划",
                createdAt = parsedPlan.createdAt
            )
            saveCurrentSession(plannerConfig)
        } catch (e: Exception) {
            _state.value = applyWorkflowPlanGenerationFailureTransition(
                state = _state.value,
                errorMessage = "⚠️ ${e.message ?: "生成执行计划失败"}"
            )
        } finally {
            _state.value = _state.value.copy(isProcessing = false)
        }
    }

    suspend fun executePendingWorkflowPlan() {
        val plan = _state.value.pendingWorkflowPlan ?: return
        val autoApproveWindow = PlanApprovalAutoApproveWindow(
            planId = plan.id,
            planGoal = plan.goal
        )
        val executingPlan = normalizeWorkflowPlan(
            plan.copy(
                status = WorkflowPlanStatusUi.EXECUTING,
                currentStepIndex = if (plan.steps.isEmpty()) 0 else 1
            )
        )
        _state.value = applyPlanExecutionStartTransition(
            state = _state.value,
            executingPlan = executingPlan
        )
        saveCurrentSession()
        sendMessageInternal(
            userVisibleText = "按计划执行: ${plan.goal}",
            modelInput = buildWorkflowExecutionPrompt(_state.value, executingPlan),
            mentionedFiles = plan.mentionedFiles,
            executionGoal = plan.goal,
            forceWritableTools = true,
            planApprovalAutoApproveWindow = autoApproveWindow
        )
        _state.value = applyWorkflowExecutionSettlementTransition(
            state = _state.value,
            originalPlan = plan,
            executingPlan = executingPlan
        )
        saveCurrentSession()
    }

    suspend fun generateClarificationQuestion(
        goal: String,
        mentionedFiles: List<FileMentionUi> = emptyList(),
        source: ClarificationSource = ClarificationSource.MANUAL
    ) {
        generateClarificationQuestionInternal(
            goal = goal,
            mentionedFiles = mentionedFiles,
            source = source
        )
    }

    private suspend fun generateClarificationQuestionInternal(
        goal: String,
        mentionedFiles: List<FileMentionUi>,
        previousAnswers: List<ClarificationAnswerUi> = emptyList(),
        turnIndex: Int = 1,
        maxTurns: Int = MAX_CLARIFICATION_TURNS,
        source: ClarificationSource = ClarificationSource.MANUAL
    ) {
        val normalizedGoal = goal.trim()
        if (normalizedGoal.isBlank() || _state.value.isProcessing) return

        val globalConfig = configRepository.getConfig()
        val config = resolveEffectiveSessionConfig(globalConfig = globalConfig)
        val plannerConfig = config.getPlannerResolvedConfig()
        lastSessionConfig = plannerConfig
        val apiKey = plannerConfig.getActiveApiKey().trim()
        if (apiKey.isBlank()) {
            _state.value = _state.value.copy(
                error = "⚠️ 未配置 API Key。请先到设置页完成模型配置。"
            )
            return
        }

        val provider = ProviderRegistry.getActiveProvider(plannerConfig.activeProviderId)
        val stateBeforeClarification = _state.value
        val history = buildHistory()
        val compressionContext = buildCompressionContext()
        val recentSessionHistoryClue = stateBeforeClarification.recentSessionHistoryClue
        _state.value = applyClarificationGenerationStartTransition(_state.value)
        try {
            val response = callProviderWithConfiguredStreaming(
                provider = provider,
                config = plannerConfig,
                request = ChatRequest(
                    messages = buildClarificationPrompt(
                        goal = normalizedGoal,
                        history = history,
                        compressionContext = compressionContext,
                        mentionedFiles = mentionedFiles,
                        previousAnswers = previousAnswers,
                        turnIndex = turnIndex,
                        maxTurns = maxTurns
                    ),
                    model = plannerConfig.getActiveModel(),
                    temperature = 0.2,
                    maxTokens = minOf(plannerConfig.maxTokens, 600),
                    stream = plannerConfig.isStreamingResponsesEnabled(),
                    reasoningEffort = plannerConfig.getActiveReasoningEffort(),
                    thinkingMode = plannerConfig.getActiveThinkingMode(),
                    tools = null
                )
            )
            _state.value = applyClarificationGeneratedTransition(
                state = _state.value,
                request = parseClarificationQuestion(
                    goal = normalizedGoal,
                    rawQuestion = response.content?.trim().orEmpty(),
                    mentionedFiles = mentionedFiles,
                    previousAnswers = previousAnswers,
                    turnIndex = turnIndex,
                    maxTurns = maxTurns,
                    source = source,
                    recentSessionHistoryClue = recentSessionHistoryClue
                )
            )
            recordConversationPromptCheckpoint(summary = "交互状态: 等待澄清回答")
            saveCurrentSession(plannerConfig)
        } catch (e: Exception) {
            when (
                resolveWorkflowFailureFallbackMode(
                    config = plannerConfig,
                    failureType = WorkflowFailureType.CLARIFICATION_GENERATION_FAILURE,
                    hasMentionedFiles = mentionedFiles.isNotEmpty(),
                    source = source.toWorkflowFailureFallbackSource(),
                    clarificationTurnIndex = turnIndex,
                    projectType = currentProjectWorkflowType(),
                    providerId = plannerConfig.activeProviderId,
                    projectRiskLevel = currentProjectWorkflowRiskLevel()
                )
            ) {
                WorkflowFailureFallbackMode.DIRECT_EXECUTION -> {
                    _state.value = applyDirectExecutionResumeTransition(
                        state = _state.value,
                        clearError = true
                    )
                    val fallbackModelInput = if (previousAnswers.isNotEmpty()) {
                        buildClarificationExecutionPrompt(
                            goal = normalizedGoal,
                            answers = previousAnswers,
                            state = _state.value,
                            recentSessionHistoryClue = recentSessionHistoryClue
                        )
                    } else {
                        normalizedGoal
                    }
                    sendMessageInternal(
                        userVisibleText = if (previousAnswers.isNotEmpty()) {
                            "继续执行: $normalizedGoal"
                        } else {
                            normalizedGoal
                        },
                        modelInput = fallbackModelInput,
                        mentionedFiles = mentionedFiles,
                        executionGoal = normalizedGoal,
                        existingClarificationAnswers = previousAnswers,
                        forceWritableTools = true
                    )
                    applyWorkflowFallback(
                        message = "澄清问题生成失败，已按配置回退为直接执行。",
                        config = plannerConfig
                    )
                }
                WorkflowFailureFallbackMode.LOCAL_CLARIFICATION -> {
                    _state.value = applyClarificationLocalFallbackTransition(
                        state = _state.value,
                        request = buildStateAwareLocalFallbackClarificationRequest(
                            goal = normalizedGoal,
                            mentionedFiles = mentionedFiles,
                            previousAnswers = previousAnswers,
                            turnIndex = turnIndex,
                            maxTurns = maxTurns,
                            source = source,
                            state = _state.value,
                            recentSessionHistoryClue = recentSessionHistoryClue
                        )
                    )
                    recordConversationPromptCheckpoint(summary = "交互状态: 等待澄清回答")
                    applyWorkflowFallback(
                        message = "澄清问题生成失败，已按配置回退为本地通用澄清问题。",
                        config = plannerConfig
                    )
                }
                WorkflowFailureFallbackMode.FOLLOW_SCENARIO_DEFAULT -> Unit
            }
        } finally {
            _state.value = _state.value.copy(isProcessing = false)
        }
    }

    suspend fun submitClarificationAnswer(answer: String) {
        val request = _state.value.pendingClarificationRequest ?: return
        val normalizedAnswer = answer.trim()
        if (normalizedAnswer.isBlank()) return
        val accumulatedAnswers = accumulateClarificationAnswers(request, normalizedAnswer)
        _state.value = applyClarificationAnswerSubmitStartTransition(_state.value)
        if (shouldForceClarificationExecution(request)) {
            val stateBeforeExecution = _state.value
            _state.value = applyDirectExecutionResumeTransition(
                state = _state.value
            )
            sendMessageInternal(
                userVisibleText = "补充回答: $normalizedAnswer",
                modelInput = buildClarificationExecutionPrompt(
                    goal = request.goal,
                    answers = accumulatedAnswers,
                    state = stateBeforeExecution,
                    recentSessionHistoryClue = request.recentSessionHistoryClue
                ),
                mentionedFiles = request.mentionedFiles,
                executionGoal = request.goal,
                existingClarificationAnswers = accumulatedAnswers,
                forceWritableTools = true
            )
            return
        }

        val globalConfig = configRepository.getConfig()
        val config = resolveEffectiveSessionConfig(globalConfig = globalConfig)
        val plannerConfig = config.getPlannerResolvedConfig()
        lastSessionConfig = plannerConfig
        val apiKey = plannerConfig.getActiveApiKey().trim()
        if (apiKey.isBlank()) {
            _state.value = applyClarificationAnswerSubmitFailureTransition(
                state = _state.value,
                errorMessage = "⚠️ 未配置 API Key。请先到设置页完成模型配置。"
            )
            return
        }

        val provider = ProviderRegistry.getActiveProvider(plannerConfig.activeProviderId)
        val history = buildHistory()
        val compressionContext = buildCompressionContext()
        val followUpDecision = try {
            val rawText = callProviderWithConfiguredStreaming(
                provider = provider,
                config = plannerConfig,
                request = ChatRequest(
                    messages = buildClarificationFollowUpPrompt(
                        state = _state.value,
                        goal = request.goal,
                        history = history,
                        compressionContext = compressionContext,
                        mentionedFiles = request.mentionedFiles,
                        answers = accumulatedAnswers,
                        recentSessionHistoryClue = request.recentSessionHistoryClue,
                        nextTurnIndex = request.turnIndex + 1,
                        maxTurns = request.maxTurns
                    ),
                    model = plannerConfig.getActiveModel(),
                    temperature = 0.1,
                    maxTokens = minOf(plannerConfig.maxTokens, 500),
                    stream = plannerConfig.isStreamingResponsesEnabled(),
                    reasoningEffort = plannerConfig.getActiveReasoningEffort(),
                    thinkingMode = plannerConfig.getActiveThinkingMode(),
                    tools = null
                )
            ).content?.trim().orEmpty()
            parseClarificationFollowUpDecision(rawText)
        } catch (e: Exception) {
            when (
                resolveWorkflowFailureFallbackMode(
                    config = config,
                    failureType = WorkflowFailureType.CLARIFICATION_FOLLOW_UP_FAILURE,
                    hasMentionedFiles = request.mentionedFiles.isNotEmpty(),
                    source = request.source.toWorkflowFailureFallbackSource(),
                    clarificationTurnIndex = request.turnIndex + 1,
                    projectType = currentProjectWorkflowType(),
                    providerId = plannerConfig.activeProviderId,
                    projectRiskLevel = currentProjectWorkflowRiskLevel()
                )
            ) {
                WorkflowFailureFallbackMode.DIRECT_EXECUTION -> {
                    val stateBeforeExecution = _state.value
                    _state.value = applyDirectExecutionResumeTransition(
                        state = _state.value,
                        clearError = true
                    )
                    sendMessageInternal(
                        userVisibleText = "补充回答: $normalizedAnswer",
                        modelInput = buildClarificationExecutionPrompt(
                            goal = request.goal,
                            answers = accumulatedAnswers,
                            state = stateBeforeExecution,
                            recentSessionHistoryClue = request.recentSessionHistoryClue
                        ),
                        mentionedFiles = request.mentionedFiles,
                        executionGoal = request.goal,
                        existingClarificationAnswers = accumulatedAnswers,
                        forceWritableTools = true
                    )
                    applyWorkflowFallback(
                        message = "判断是否继续澄清失败，已按配置回退为直接继续执行。",
                        config = config
                    )
                }
                WorkflowFailureFallbackMode.LOCAL_CLARIFICATION -> {
                    _state.value = applyClarificationFollowUpLocalFallbackTransition(
                        state = _state.value,
                        request = buildStateAwareLocalFallbackClarificationRequest(
                            goal = request.goal,
                            mentionedFiles = request.mentionedFiles,
                            previousAnswers = accumulatedAnswers,
                            turnIndex = request.turnIndex + 1,
                            maxTurns = request.maxTurns,
                            source = request.source,
                            state = _state.value,
                            recentSessionHistoryClue = request.recentSessionHistoryClue
                        )
                    )
                    applyWorkflowFallback(
                        message = "判断是否继续澄清失败，已按配置回退为本地通用澄清问题。",
                        config = config
                    )
                }
                WorkflowFailureFallbackMode.FOLLOW_SCENARIO_DEFAULT -> Unit
            }
            return
        }

        val resolution = resolveClarificationAnswerResolution(
            request = request,
            accumulatedAnswers = accumulatedAnswers,
            action = followUpDecision.action,
            question = followUpDecision.question
        )
        when (resolution.action) {
            ClarificationResolutionAction.ASK_FOLLOW_UP -> {
                _state.value = applyClarificationFollowUpTransition(
                    state = _state.value,
                    request = resolution.nextClarificationRequest ?: return
                )
                recordConversationPromptCheckpoint(summary = "交互状态: 已更新澄清问题")
                saveCurrentSession(plannerConfig)
            }

            ClarificationResolutionAction.EXECUTE -> {
                val stateBeforeExecution = _state.value
                _state.value = applyDirectExecutionResumeTransition(
                    state = _state.value
                )
                sendMessageInternal(
                    userVisibleText = "补充回答: $normalizedAnswer",
                    modelInput = buildClarificationExecutionPrompt(
                        goal = request.goal,
                        answers = accumulatedAnswers,
                        state = stateBeforeExecution,
                        recentSessionHistoryClue = request.recentSessionHistoryClue
                    ),
                    mentionedFiles = request.mentionedFiles,
                    executionGoal = request.goal,
                    existingClarificationAnswers = accumulatedAnswers,
                    forceWritableTools = true
                )
            }
        }
    }

    private suspend fun sendMessageInternal(
        userVisibleText: String,
        modelInput: String,
        mentionedFiles: List<FileMentionUi> = emptyList(),
        pendingImages: List<PendingImageAttachmentUi> = emptyList(),
        executionGoal: String = userVisibleText,
        existingClarificationAnswers: List<ClarificationAnswerUi> = emptyList(),
        configOverride: ProviderConfig? = null,
        extraUserContext: String? = null,
        forceWritableTools: Boolean = false,
        planApprovalAutoApproveWindow: PlanApprovalAutoApproveWindow? = null
    ) {
        val stateBeforeSend = _state.value
        if ((modelInput.isBlank() && pendingImages.isEmpty()) || stateBeforeSend.isProcessing) return
        val requestedExecutionGoal = executionGoal.trim().takeIf { it.isNotBlank() }
            ?: modelInput.trim().takeIf { it.isNotBlank() }
        val orchestratorDecision = resolveTurnOrchestratorDecision(
            state = stateBeforeSend,
            requestedExecutionGoal = requestedExecutionGoal,
            forceWritableTools = forceWritableTools
        )
        val readOnlyPlanModeDecision = orchestratorDecision.readOnlyPlanModeDecision
        val readOnlyPlanMode = readOnlyPlanModeDecision.enabled
        val config = configOverride ?: resolveEffectiveSessionConfig(
            globalConfig = configRepository.getConfig(),
            state = stateBeforeSend
        )
        lastSessionConfig = config
        val apiKey = config.getActiveApiKey()
        if (apiKey.isBlank()) {
            appendSystemMessage(
                content = "⚠️ 未配置 API Key。请点击底部「设置」→ 选择 AI 提供商 → 填入你的 API Key。",
                source = "send_message:missing_api_key"
            )
            _state.value = _state.value.copy(
                isProcessing = false,
                lastWorkflowFallback = null
            )
            return
        }

        val provider = ProviderRegistry.getActiveProvider(config.activeProviderId)

        // 创建 ToolRegistry（包含 MCP 工具）
        val toolRegistry = createToolRegistry(
            provider = provider,
            config = config,
            allowWriteTools = orchestratorDecision.allowWriteTools
        )
        if (orchestratorDecision.allowWriteTools) {
            mcpRegistry?.let { mcp ->
            mcp.getMcpTools()
                .filter { config.isMcpToolEnabled(it.name) }
                .forEach { toolRegistry.register(it) }
            }
        }
        emitMcpConnectionStatusSnapshot(trigger = "send_message_preflight")

        agentLoop = AgentLoop(provider, toolRegistry, config, hookBus)
        val compressionContext = buildCompressionContext(stateBeforeSend)
        val projectContext = buildCurrentProjectContext()
        val sessionGoalContext = buildSessionGoalContext()
        val projectSkillsContext = buildProjectSkillsContext()
        val mcpToolsContext = buildEnabledMcpToolsContext()
        val fileMentionContext = buildMentionedFilesContext(mentionedFiles)
        val executionInterruptContext = buildExecutionInterruptionContext(existingClarificationAnswers)
        if (pendingImages.isNotEmpty() && !config.isMultimodalEnabled()) {
            appendSystemMessage(
                content = "⚠️ 已忽略 ${pendingImages.size} 张图片，因为设置中已关闭多模态。",
                source = "send_message:multimodal_disabled"
            )
        }
        val importedImages = if (config.isMultimodalEnabled()) {
            importPendingImageAttachments(pendingImages)
        } else {
            emptyList()
        }
        if (config.isMultimodalEnabled() && pendingImages.isNotEmpty() && importedImages.isEmpty()) {
            appendSystemMessage(
                content = "⚠️ 当前图片读取失败，请重新选择图片后再试。",
                source = "send_message:image_import_failed"
            )
            return
        }
        val multimodalContext = if (importedImages.isNotEmpty()) {
            buildMultimodalSystemContext(importedImages.size)
        } else {
            null
        }
        val stableSystemContext = listOfNotNull(
            compressionContext,
            projectContext,
            sessionGoalContext,
            projectSkillsContext,
            mcpToolsContext.takeUnless { readOnlyPlanMode }
        )
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
        pendingPromptCacheShape = capturePromptCacheShape(
            stableSystemContext = stableSystemContext,
            toolsJson = toolRegistry.buildToolsJson(),
            compressionContext = compressionContext,
            projectContext = projectContext,
            sessionGoalContext = sessionGoalContext,
            projectSkillsContext = projectSkillsContext,
            mcpToolsContext = mcpToolsContext.takeUnless { readOnlyPlanMode },
            readOnlyPlanMode = readOnlyPlanMode
        )
        val finalReadinessContext = buildFinalReadinessContinuationContext(
            audits = stateBeforeSend.recentFinalReadinessAudits,
            recentSessionHistoryClue = stateBeforeSend.recentSessionHistoryClue
        )
        val recentSessionHistoryReferenceContext = stateBeforeSend.recentSessionHistoryContext
        val recentSkillUsageContext = stateBeforeSend.recentSkillUsageContext
        val recentMemoryUpdateSuggestionContext = stateBeforeSend.recentPendingMemoryUpdateSuggestionContext
        val turnOrchestratorContext = buildTurnOrchestratorContext(orchestratorDecision)
        val turnScopedUserContext = buildTurnScopedUserContext(
            fileMentionContext = fileMentionContext,
            executionInterruptContext = executionInterruptContext,
            multimodalContext = multimodalContext,
            turnOrchestratorContext = turnOrchestratorContext,
            planningModeContext = buildReadOnlyPlanModeContext(readOnlyPlanModeDecision),
            finalReadinessContext = finalReadinessContext,
            recentSessionHistoryReferenceContext = recentSessionHistoryReferenceContext,
            recentSkillUsageContext = recentSkillUsageContext,
            recentMemoryUpdateSuggestionContext = recentMemoryUpdateSuggestionContext,
            extraUserContext = extraUserContext
        )
        val resolvedBaseModelInput = modelInput.ifBlank {
            if (importedImages.isNotEmpty()) {
                "请分析这${if (importedImages.size > 1) "${importedImages.size}张" else "张"}图片，并提取其中的关键信息。"
            } else {
                ""
            }
        }
        val resolvedModelInput = listOfNotNull(
            turnScopedUserContext,
            resolvedBaseModelInput.takeIf { it.isNotBlank() }
        ).joinToString("\n\n")

        val userMsg = ChatMessageUi(
            id = nextId(),
            role = "user",
            content = userVisibleText,
            imageAttachments = importedImages
        )
        val assistantMsg = ChatMessageUi(
            id = nextId(), role = "assistant", content = "",
            isStreaming = true
        )
        appendMessages(userMsg, assistantMsg)
        currentStreamingId = assistantMsg.id
        _state.value = _state.value.copy(
            isProcessing = true,
            error = null,
            lastWorkflowFallback = null,
            lastFinalReadinessReceipt = null
        )
        currentTurnCheckpointCaptureState = TurnCheckpointCaptureState()
        processingCancelledByUser = false
        val history = buildHistory(stateBeforeSend)

        try {
            val userModelMessage = ChatMessage(
                role = "user",
                content = resolvedModelInput.ifBlank { null },
                images = if (config.isMultimodalEnabled()) {
                    importedImages.mapNotNull(::buildChatImageAttachment)
                } else {
                    emptyList()
                }
            )
            val shouldEnforcePersistentFinalReadiness = buildPersistentFinalReadinessReceipt(
                state = stateBeforeSend,
                executionGoal = executionGoal
            ) != null
            agentLoop?.processMessage(
                userMessage = userModelMessage,
                history = history,
                stableSystemContext = stableSystemContext,
                onEvent = { event ->
                    when (event) {
                        is AgentEvent.ContentDelta -> appendToStreaming(event.text)
                        is AgentEvent.ReasoningDelta -> appendToStreamingReasoning(event.text)
                        is AgentEvent.ToolExecution -> {
                            if (event.isPartial) {
                                return@processMessage
                            }
                            finalizeStreaming()
                            val toolMsg = ChatMessageUi(
                                id = nextId(), role = "tool_exec",
                                content = buildToolExecutionMessage(
                                    toolName = event.toolName,
                                    args = event.args
                                )
                            )
                            appendMessage(toolMsg)
                        }
                        is AgentEvent.ToolResult -> {
                            val toolExecutionSucceeded = !event.result.startsWith("Error:", ignoreCase = true) &&
                                !event.result.startsWith("Blocked by", ignoreCase = true) &&
                                !event.result.startsWith("Rejected by user:", ignoreCase = true)
                            recordToolCall(
                                toolName = event.toolName,
                                args = event.args,
                                result = event.result,
                                isSuccess = toolExecutionSucceeded,
                                stepSignOffReceipt = event.stepSignOffReceipt,
                                structuredPayload = event.structuredPayload
                            )
                            val fileChanges = recordFileChanges(
                                toolName = event.toolName,
                                fileChanges = event.fileChanges
                            )
                            val resultMsg = ChatMessageUi(
                                id = nextId(), role = "tool_exec",
                                content = buildToolResultMessage(
                                    toolName = event.toolName,
                                    args = event.args,
                                    result = event.result,
                                    fileChanges = fileChanges
                                )
                            )
                            appendMessage(resultMsg)
                        }
                        is AgentEvent.UsageUpdate -> {
                            appendUsage(event.usage, config)
                        }
                        is AgentEvent.ReadinessAudit -> recordFinalReadinessAudit(event.audit)
                        is AgentEvent.Error -> {
                            finalizeStreaming()
                            recordError(
                                message = event.message,
                                kind = if (event.finalReadinessReceipt != null) {
                                    ErrorRecordKind.FINAL_READINESS
                                } else {
                                    ErrorRecordKind.GENERAL
                                }
                            )
                            _state.value = _state.value.copy(
                                lastFinalReadinessReceipt = event.finalReadinessReceipt
                            )
                            recordError(
                                message = event.message,
                                kind = if (event.finalReadinessReceipt != null) {
                                    ErrorRecordKind.FINAL_READINESS
                                } else {
                                    ErrorRecordKind.GENERAL
                                }
                            )
                            event.userVisibleMessage
                                ?.takeIf(String::isNotBlank)
                                ?.let { userVisibleMessage ->
                                    appendSystemMessage(
                                        content = decorateUserVisibleErrorMessage(userVisibleMessage),
                                        source = "agent_event:error"
                                    )
                                }
                        }
                        is AgentEvent.Done -> finalizeStreaming()
                    }
                },
                requestApproval = { request ->
                    waitForApproval(
                        request = request,
                        planApprovalAutoApproveWindow = planApprovalAutoApproveWindow
                    )
                },
                toolExecutionGuard = if (readOnlyPlanMode) {
                    ::buildReadOnlyPlanModeToolBlockMessage
                } else {
                    null
                },
                finalReadinessGuard = {
                    buildPersistentFinalReadinessReceipt(
                        state = _state.value,
                        executionGoal = executionGoal
                    )
                },
                enforceFinalReadinessWithoutCurrentToolRuns = shouldEnforcePersistentFinalReadiness
            )

            // 更新会话标题（取第一条用户输入）
            if (_state.value.sessionTitle == "新对话") {
                _state.value = _state.value.copy(
                    sessionTitle = conversationStore.generateTitle(_state.value.messages)
                )
            }

            if (handleExecutionInterruptionIfNeeded(
                    assistantMessageId = assistantMsg.id,
                    executionGoal = executionGoal,
                    mentionedFiles = mentionedFiles,
                    existingClarificationAnswers = existingClarificationAnswers,
                    config = config
                )
            ) {
                return
            }

            var completedState = _state.value.copy(
                pendingWorkflowPlan = null,
                workflowPlanningInProgress = false,
                pendingClarificationRequest = null,
                clarificationInProgress = false,
                autoRoutingInProgress = false
            )
            val finalizedTurnCheckpoint = finalizeTurnCheckpoint(
                captureState = currentTurnCheckpointCaptureState,
                checkpoints = completedState.checkpoints,
                messages = completedState.messages,
                checkpointIdFactory = { "chk-${System.currentTimeMillis()}" }
            )
            if (finalizedTurnCheckpoint != null) {
                completedState = completedState.copy(
                    checkpoints = upsertCheckpointHistory(
                        checkpoints = completedState.checkpoints,
                        checkpoint = finalizedTurnCheckpoint,
                        maxSize = MAX_CHECKPOINT_HISTORY
                    )
                )
            }
            buildMidSessionMemoryUpdateSuggestion(
                stateBeforeSend = stateBeforeSend,
                completedState = completedState,
                userMessage = userMsg,
                assistantMessageId = assistantMsg.id,
                executionGoal = executionGoal,
                currentProjectScopePath = activeProjectScopePath(
                    activeScopePath = completedState.activeProjectScopePath,
                    projectPath = completedState.projectPath
                )
            )?.let { suggestion ->
                completedState = applyMemoryUpdateSuggestionTransition(
                    state = completedState,
                    suggestion = suggestion
                )
            }
            _state.value = completedState
            // 自动保存
            saveCurrentSession(config)
        } catch (e: CancellationException) {
            finalizeStreaming()
            if (!processingCancelledByUser) {
                appendSystemMessage(
                    content = "⏹️ 当前处理已取消。",
                    source = "send_message:cancelled"
                )
            }
        } catch (e: Exception) {
            finalizeStreaming()
            _state.value = _state.value.copy(
                error = "❌ ${e.message ?: e.javaClass.simpleName}"
            )
        } finally {
            _state.value = _state.value.copy(isProcessing = false)
            currentTurnCheckpointCaptureState = TurnCheckpointCaptureState()
            processingCancelledByUser = false
        }
    }

    /**
     * 清空当前对话
     */
    fun clear() {
        val previousState = _state.value
        resetPendingStreamingUpdates()
        _state.value = SessionState(sessionId = currentSessionId)
        messageCounter = 0
        currentStreamingId = null
        emitNotification(
            channel = "session_lifecycle",
            message = "当前会话已清空。",
            source = "clear_session"
        )
        emitSessionTransition(
            sessionId = currentSessionId,
            phase = SessionTransitionPhase.STARTED,
            trigger = "clear_session",
            counterpartSessionId = currentSessionId,
            sessionTitle = previousState.sessionTitle,
            projectPath = previousState.projectPath
        )
    }

    fun rollbackLastTurn(): Boolean {
        val messages = _state.value.messages
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        if (lastUserIndex == -1) return false
        return rollbackConversationToMessageIndex(lastUserIndex)
    }

    fun rollbackToUserMessage(messageId: Long): Boolean {
        val messages = _state.value.messages
        val targetIndex = messages.indexOfFirst { it.id == messageId && it.role == "user" }
        if (targetIndex == -1) return false
        return rollbackConversationToMessageIndex(targetIndex)
    }

    fun rollbackCheckpoint(
        checkpointId: String,
        scope: ConversationCheckpointScope = ConversationCheckpointScope.CODE
    ): Result<Int> {
        return runCatching {
            val state = _state.value
            require(!state.isProcessing) { "处理中暂时不能执行恢复" }

            val checkpoint = state.checkpoints.firstOrNull { it.id == checkpointId }
                ?: error("未找到对应的修改批次")
            val recoveryPlan = resolveCheckpointRecoveryPlan(
                state = state,
                checkpoint = checkpoint,
                requestedScope = scope
            )
            when (scope) {
                ConversationCheckpointScope.CODE -> rollbackCheckpointFiles(
                    state = state,
                    checkpoint = checkpoint,
                    scope = scope,
                    records = recoveryPlan.checkpointRecords
                ).getOrThrow()

                ConversationCheckpointScope.CONVERSATION,
                ConversationCheckpointScope.BOTH -> rollbackCheckpointConversation(
                    state = state,
                    checkpoint = checkpoint,
                    scope = scope,
                    targetExclusiveIndex = recoveryPlan.targetExclusiveIndex
                ).getOrThrow()
            }
        }
    }

    fun rollbackFileCheckpoint(checkpointId: String): Result<Int> {
        return rollbackCheckpoint(
            checkpointId = checkpointId,
            scope = ConversationCheckpointScope.CODE
        )
    }

    private fun rollbackConversationToMessageIndex(targetExclusiveIndex: Int): Boolean {
        val state = _state.value
        if (state.isProcessing) return false
        if (targetExclusiveIndex < 0 || targetExclusiveIndex > state.messages.size) return false

        val fileRollback = rollbackFutureFileChangesForMessageIndex(
            state = state,
            targetExclusiveIndex = targetExclusiveIndex
        )
        if (fileRollback.isFailure) {
            _state.value = state.copy(
                error = fileRollback.exceptionOrNull()?.message ?: "回退聊天时恢复文件失败",
                isProcessing = false
            )
            currentStreamingId = null
            clearPendingApproval()
            return false
        }
        applyConversationRollbackProjection(
            state = state,
            projection = projectConversationRollbackState(
                state = state,
                targetExclusiveIndex = targetExclusiveIndex
            )
        )
        currentStreamingId = null
        clearPendingApproval()
        saveCurrentSession()
        return true
    }

    private fun rollbackFutureFileChangesForMessageIndex(
        state: SessionState,
        targetExclusiveIndex: Int
    ): Result<Int> {
        return runCatching {
            var restoredRecordCount = 0

            collectFutureCheckpointRollbackRecords(
                state = state,
                targetExclusiveIndex = targetExclusiveIndex
            ).forEach { record ->
                restoreFileRecord(record).getOrThrow()
                restoredRecordCount += 1
            }
            restoredRecordCount
        }
    }

    private fun rollbackCheckpointFiles(
        state: SessionState,
        checkpoint: ConversationCheckpointUi,
        scope: ConversationCheckpointScope,
        records: List<FileChangeRecordUi>
    ): Result<Int> {
        return runCatching {
            require(records.isNotEmpty()) { "该检查点没有可回滚的代码修改" }

            val rollbackCheckpointId = "chk-${System.currentTimeMillis()}"
            val rollbackRecords = records.mapIndexed { index, record ->
                val currentContent = restoreFileRecord(record).getOrThrow()

                FileChangeRecordUi(
                    id = "$rollbackCheckpointId-$index",
                    path = record.path,
                    operation = "rollback",
                    beforeContent = currentContent,
                    afterContent = record.beforeContent,
                    diffPreview = buildDiffPreview(currentContent, record.beforeContent),
                    changedAt = System.currentTimeMillis(),
                    checkpointId = rollbackCheckpointId
                )
            }

            val rollbackCheckpoint = buildCheckpointRecoveryEvent(
                rollbackCheckpointId = rollbackCheckpointId,
                checkpoint = checkpoint,
                restoredScope = scope,
                messageIndex = state.messages.lastIndex,
                createdAt = rollbackRecords.maxOfOrNull { it.changedAt } ?: System.currentTimeMillis(),
                changedFiles = rollbackRecords.map { it.path },
                recentSessionHistoryClue = state.recentSessionHistoryClue
            )

            _state.value = state.copy(
                fileChanges = (rollbackRecords + state.fileChanges).take(MAX_FILE_CHANGE_HISTORY),
                checkpoints = upsertCheckpointHistory(
                    checkpoints = state.checkpoints,
                    checkpoint = rollbackCheckpoint,
                    maxSize = MAX_CHECKPOINT_HISTORY
                )
            )
            val recentSessionHistoryClue = _state.value.recentSessionHistoryClue
            val recoveryRecord = buildCheckpointRecoveryRecord(
                checkpoint = checkpoint,
                restoredScope = scope,
                restoredFileCount = rollbackRecords.size,
                recentSessionHistoryClue = recentSessionHistoryClue
            )
            recordCheckpointRecovery(recoveryRecord)
            appendSystemMessage(
                content = buildCheckpointRecoverySystemMessage(
                    checkpoint = checkpoint,
                    restoredScope = scope,
                    recentSessionHistoryClue = recentSessionHistoryClue
                ),
                source = "checkpoint_recovery:file_scope"
            )
            saveCurrentSession()
            rollbackRecords.size
        }
    }

    private fun rollbackCheckpointConversation(
        state: SessionState,
        checkpoint: ConversationCheckpointUi,
        scope: ConversationCheckpointScope,
        targetExclusiveIndex: Int
    ): Result<Int> {
        return runCatching {
            val restoredFileRecords = if (scope == ConversationCheckpointScope.BOTH) {
                collectFutureCheckpointRollbackRecords(
                    state = state,
                    targetExclusiveIndex = targetExclusiveIndex
                )
            } else {
                emptyList()
            }
            val restoredFileCount = if (scope == ConversationCheckpointScope.BOTH) {
                rollbackFutureFileChangesForMessageIndex(
                    state = state,
                    targetExclusiveIndex = targetExclusiveIndex
                ).getOrThrow()
            } else {
                0
            }
            applyConversationRollbackProjection(
                state = state,
                projection = projectConversationRollbackState(
                    state = state,
                    targetExclusiveIndex = targetExclusiveIndex,
                    targetCheckpoint = checkpoint
                )
            )
            val recentSessionHistoryClue = _state.value.recentSessionHistoryClue
            val rollbackCheckpoint = buildCheckpointRecoveryEvent(
                rollbackCheckpointId = "chk-${System.currentTimeMillis()}",
                checkpoint = checkpoint,
                restoredScope = scope,
                messageIndex = _state.value.messages.lastIndex,
                createdAt = System.currentTimeMillis(),
                changedFiles = restoredFileRecords.map { it.path },
                recentSessionHistoryClue = recentSessionHistoryClue
            )
            val recoveryRecord = buildCheckpointRecoveryRecord(
                checkpoint = checkpoint,
                restoredScope = scope,
                restoredFileCount = restoredFileCount,
                recentSessionHistoryClue = recentSessionHistoryClue
            )
            _state.value = _state.value.copy(
                checkpoints = upsertCheckpointHistory(
                    checkpoints = _state.value.checkpoints,
                    checkpoint = rollbackCheckpoint,
                    maxSize = MAX_CHECKPOINT_HISTORY
                )
            )
            recordCheckpointRecovery(recoveryRecord)
            appendSystemMessage(
                content = buildCheckpointRecoverySystemMessage(
                    checkpoint = checkpoint,
                    restoredScope = scope,
                    recentSessionHistoryClue = recentSessionHistoryClue
                ),
                source = "checkpoint_recovery:conversation"
            )
            currentStreamingId = null
            clearPendingApproval()
            saveCurrentSession()
            restoredFileCount
        }
    }

    private fun applyConversationRollbackProjection(
        state: SessionState,
        projection: ConversationRollbackProjection
    ) {
        pendingAskDecision = null
        _state.value = state.copy(
            messages = projection.messages,
            subagentRuns = projection.subagentRuns,
            subagentBatches = projection.subagentBatches,
            isProcessing = false,
            error = null,
            compressionSnapshot = projection.compressionSnapshot,
            compressionSnapshots = projection.compressionSnapshots,
            checkpoints = projection.checkpoints,
            fileChanges = projection.fileChanges,
            pendingAskRequest = projection.pendingAskRequest,
            pendingWorkflowPlan = projection.pendingWorkflowPlan,
            canonicalWorkflowPlan = projection.canonicalWorkflowPlan,
            workflowPlanningInProgress = projection.workflowPlanningInProgress,
            pendingClarificationRequest = projection.pendingClarificationRequest,
            clarificationInProgress = projection.clarificationInProgress,
            pendingApproval = null
        )
    }

    private fun restoreFileRecord(record: FileChangeRecordUi): Result<String?> {
        return runCatching {
            val currentContent = readCurrentFileContent(record.path)
            val result = writeTargetFileContent(record.path, record.beforeContent)
            require(result.success) {
                "回滚 ${record.path} 失败: ${result.error ?: result.output}"
            }
            currentContent
        }
    }

    private fun readCurrentFileContent(path: String): String? {
        return if (RootFile.exists(path)) {
            RootFile.readFile(path).takeIf { !it.startsWith("error:") }
        } else {
            null
        }
    }

    private fun writeTargetFileContent(
        path: String,
        targetContent: String?
    ): RootFile.OperationResult {
        return if (targetContent == null) {
            if (RootFile.exists(path)) {
                RootFile.deleteChecked(path)
            } else {
                RootFile.OperationResult(success = true, output = "Already absent")
            }
        } else {
            RootFile.writeFileChecked(path, targetContent)
        }
    }

    fun importConversation(rawText: String, sourceName: String? = null): Int {
        val imported = ConversationImportParser.parse(rawText, sourceName)
        val sessionId = UUID.randomUUID().toString().take(8)
        currentSessionId = sessionId
        messageCounter = imported.messages.maxOfOrNull { it.id } ?: 0

        val title = imported.titleHint?.takeIf { it.isNotBlank() }
            ?: conversationStore.generateTitle(imported.messages)

        _state.value = SessionState(
            messages = imported.messages,
            sessionId = sessionId,
            sessionTitle = title.ifBlank { "导入对话" }
        )
        saveCurrentSession()
        return imported.messages.size
    }

    fun saveCurrentSession(config: ProviderConfig? = null) {
        val stateSnapshot = _state.value
        if (!shouldPersistSession(stateSnapshot)) return
        val sessionIdSnapshot = currentSessionId
        val approvedScopesSnapshot = approvedApprovalScopes.toList()
        val cachedSessionSnapshot = cachedCurrentPersistedSession
            ?.takeIf { it.id == sessionIdSnapshot }
        latestPersistJob = sessionPersistenceScope.launch {
            val persistedSession = buildPersistedSession(
                config = config,
                state = stateSnapshot,
                sessionId = sessionIdSnapshot,
                approvedScopes = approvedScopesSnapshot,
                cachedSession = cachedSessionSnapshot
            )
            val saved = conversationStore.saveSession(persistedSession)
            if (saved && currentSessionId == sessionIdSnapshot) {
                cachedCurrentPersistedSession = persistedSession
            }
        }
    }

    private fun findRememberMemorySuggestion(suggestionId: String): RememberMemorySuggestion? {
        return findRememberMemorySuggestion(
            state = _state.value,
            suggestionId = suggestionId
        )
    }

    private fun markMemoryUpdateSuggestionApplied(
        suggestionId: String,
        memoryId: String
    ) {
        _state.value = applyMemoryUpdateSuggestionAppliedTransition(
            state = _state.value,
            suggestionId = suggestionId,
            memoryId = memoryId
        )
    }

    private fun resolveEffectiveSessionConfig(
        globalConfig: ProviderConfig,
        state: SessionState = _state.value
    ): ProviderConfig {
        return resolveSessionConfig(
            globalConfig = globalConfig,
            projectConfig = state.toSessionProjectConfig()
        ).effectiveProviderConfig
    }

    private fun SessionState.toSessionProjectConfig(): SessionProjectConfig {
        return buildSessionProjectConfig(
            projectRules = projectRules,
            projectMemories = projectMemories,
            projectSkills = projectSkills,
            projectToolPreferences = projectToolPreferences
        )
    }

    private fun shouldPersistSession(state: SessionState): Boolean {
        return hasPersistableSessionContent(state)
    }

    private fun replaceCurrentSessionWithBlank(
        savePrevious: Boolean,
        trigger: String
    ) {
        val previousState = _state.value
        val previousSessionId = currentSessionId
        if (savePrevious) {
            saveCurrentSession()
        }
        resetPendingStreamingUpdates()
        currentSessionId = UUID.randomUUID().toString().take(8)
        cachedCurrentPersistedSession = null
        clearApprovedApprovalScopes()
        messageCounter = 0
        _state.value = SessionState(sessionId = currentSessionId)
        syncApprovedApprovalScopesState()
        emitSessionTransition(
            sessionId = previousSessionId,
            phase = SessionTransitionPhase.STOPPED,
            trigger = trigger,
            counterpartSessionId = currentSessionId,
            sessionTitle = previousState.sessionTitle,
            projectPath = previousState.projectPath
        )
        emitSessionTransition(
            sessionId = currentSessionId,
            phase = SessionTransitionPhase.STARTED,
            trigger = trigger,
            counterpartSessionId = previousSessionId,
            sessionTitle = _state.value.sessionTitle,
            projectPath = _state.value.projectPath
        )
    }

    private fun ensureSessionLifecycleAllowed(action: SessionLifecycleAction): Boolean {
        val notice = buildSessionLifecycleGuardNotice(_state.value, action) ?: return true
        emitSessionLifecycleNotice(
            notice = notice,
            source = "guard:${action.name.lowercase()}"
        )
        return false
    }

    fun approvePendingTool(): Boolean {
        if (_state.value.pendingApproval?.isReplayOnly == true) {
            return false
        }
        val deferred = pendingApprovalDecision ?: return false
        if (!deferred.isCompleted) {
            deferred.complete(true)
        }
        return true
    }

    fun rejectPendingTool(): Boolean {
        val deferred = pendingApprovalDecision
        if (deferred == null) {
            if (_state.value.pendingApproval?.isReplayOnly == true) {
                clearPendingApproval()
                return true
            }
            return false
        }
        if (!deferred.isCompleted) {
            deferred.complete(false)
        }
        return true
    }

    fun submitPendingAskAnswers(answers: List<AskAnswerUi>): Boolean {
        if (_state.value.pendingAskRequest?.isReplayOnly == true) {
            return false
        }
        val deferred = pendingAskDecision ?: return false
        if (!deferred.isCompleted) {
            deferred.complete(answers)
        }
        clearPendingAsk()
        recordConversationPromptCheckpoint(summary = "交互状态: 已提交提问回答")
        saveCurrentSession()
        return true
    }

    fun dismissPendingAsk(): Boolean {
        val deferred = pendingAskDecision
        if (deferred == null) {
            if (_state.value.pendingAskRequest?.isReplayOnly == true) {
                clearPendingAsk()
                return true
            }
            return false
        }
        if (!deferred.isCompleted) {
            deferred.complete(null)
        }
        clearPendingAsk()
        recordConversationPromptCheckpoint(summary = "交互状态: 已关闭提问卡片")
        saveCurrentSession()
        return true
    }

    fun cancelCurrentProcessing(): Boolean {
        val hasActiveProcessing = _state.value.isProcessing || pendingApprovalDecision != null || pendingAskDecision != null
        if (!hasActiveProcessing) return false
        processingCancelledByUser = true
        agentLoop = null
        pendingApprovalDecision?.let { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(false)
            }
        }
        pendingAskDecision?.let { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(null)
            }
        }
        clearPendingApproval()
        clearPendingAsk()
        finalizeStreaming()
        trimDanglingStreamingAssistant()
        _state.value = _state.value.copy(
            isProcessing = false,
            error = null,
            autoRoutingInProgress = false,
            workflowPlanningInProgress = false,
            clarificationInProgress = false
        )
        appendSystemMessage(
            content = "⏹️ 已终止当前处理，可直接修改提示词后重新发送。",
            source = "stop_processing"
        )
        saveCurrentSession()
        return true
    }

    private fun nextId() = ++messageCounter

    private fun appendMessage(msg: ChatMessageUi) {
        appendMessages(msg)
    }

    private fun appendMessages(vararg msgs: ChatMessageUi) {
        if (msgs.isEmpty()) return
        val current = _state.value
        val updatedMessages = ArrayList<ChatMessageUi>(current.messages.size + msgs.size)
        updatedMessages.addAll(current.messages)
        updatedMessages.addAll(msgs)
        _state.value = current.copy(messages = updatedMessages)
    }

    private fun updateMessageById(
        messageId: Long,
        transform: (ChatMessageUi) -> ChatMessageUi
    ) {
        val current = _state.value
        val index = current.messages.indexOfLast { it.id == messageId }
        if (index < 0) return
        val original = current.messages[index]
        val updated = transform(original)
        if (updated == original) return
        val updatedMessages = current.messages.toMutableList()
        updatedMessages[index] = updated
        _state.value = current.copy(messages = updatedMessages)
    }

    private fun updateMessage(messageId: Long, transform: (ChatMessageUi) -> ChatMessageUi) {
        updateMessageById(messageId, transform)
    }

    private fun appendToStreaming(text: String) {
        enqueueStreamingUpdate(contentDelta = text)
    }

    private fun appendToStreamingReasoning(text: String) {
        enqueueStreamingUpdate(reasoningDelta = text)
    }

    private fun ensureStreamingAssistantMessage(): Long {
        currentStreamingId?.let { return it }
        val assistantMsg = ChatMessageUi(
            id = nextId(),
            role = "assistant",
            content = "",
            isStreaming = true
        )
        appendMessage(assistantMsg)
        currentStreamingId = assistantMsg.id
        return assistantMsg.id
    }

    private fun finalizeStreaming() {
        val id = currentStreamingId ?: return
        flushPendingStreamingUpdates(messageId = id)
        updateMessageById(id) { msg ->
            val finalized = if (msg.isStreaming) msg.copy(isStreaming = false) else msg
            applyPostLlmHookToMessage(
                message = finalized,
                hookBus = hookBus
            )
        }
        currentStreamingId = null
    }

    private fun enqueueStreamingUpdate(
        contentDelta: String = "",
        reasoningDelta: String = ""
    ) {
        if (contentDelta.isEmpty() && reasoningDelta.isEmpty()) return
        val messageId = ensureStreamingAssistantMessage()
        var shouldScheduleFlush = false
        synchronized(pendingStreamingLock) {
            if (pendingStreamingMessageId != messageId) {
                pendingStreamingMessageId = messageId
                pendingStreamingContent.setLength(0)
                pendingStreamingReasoning.setLength(0)
            }
            if (contentDelta.isNotEmpty()) {
                pendingStreamingContent.append(contentDelta)
            }
            if (reasoningDelta.isNotEmpty()) {
                pendingStreamingReasoning.append(reasoningDelta)
            }
            if (streamingFlushJob?.isActive != true) {
                shouldScheduleFlush = true
            }
        }
        if (shouldScheduleFlush) {
            streamingFlushJob = streamingAggregationScope.launch {
                delay(STREAMING_FLUSH_INTERVAL_MS)
                flushPendingStreamingUpdates()
            }
        }
    }

    private fun flushPendingStreamingUpdates(messageId: Long? = null) {
        val pendingId: Long
        val contentDelta: String
        val reasoningDelta: String
        val jobToCancel: Job?
        synchronized(pendingStreamingLock) {
            val resolvedPendingId = pendingStreamingMessageId ?: return
            if (messageId != null && resolvedPendingId != messageId) return
            pendingId = resolvedPendingId
            contentDelta = pendingStreamingContent.toString()
            reasoningDelta = pendingStreamingReasoning.toString()
            if (contentDelta.isEmpty() && reasoningDelta.isEmpty()) return
            pendingStreamingContent.setLength(0)
            pendingStreamingReasoning.setLength(0)
            pendingStreamingMessageId = null
            jobToCancel = streamingFlushJob
            streamingFlushJob = null
        }
        jobToCancel?.cancel()
        updateMessageById(pendingId) { msg ->
            msg.copy(
                content = msg.content + contentDelta,
                reasoning = if (reasoningDelta.isEmpty()) {
                    msg.reasoning
                } else {
                    (msg.reasoning ?: "") + reasoningDelta
                }
            )
        }
    }

    private fun resetPendingStreamingUpdates() {
        val jobToCancel: Job?
        synchronized(pendingStreamingLock) {
            pendingStreamingContent.setLength(0)
            pendingStreamingReasoning.setLength(0)
            pendingStreamingMessageId = null
            jobToCancel = streamingFlushJob
            streamingFlushJob = null
        }
        jobToCancel?.cancel()
    }

    private fun trimDanglingStreamingAssistant() {
        val messages = _state.value.messages
        val trailingAssistant = messages.lastOrNull() ?: return
        if (trailingAssistant.role != "assistant") return
        if (trailingAssistant.content.isNotBlank() || !trailingAssistant.reasoning.isNullOrBlank()) return
        _state.value = _state.value.copy(messages = messages.dropLast(1))
    }

    private fun buildHistory(state: SessionState = _state.value): List<ChatMessage> {
        val compression = state.compressionSnapshot?.takeIf { it.active }
        val baseMessages = if (compression != null) {
            state.messages.filter { it.id > compression.sourceEndMessageId }
        } else {
            state.messages
        }
        val pruningPlan = planToolResultHistoryPruning(baseMessages)
        val elisionPlan = planStaleToolResultElision(
            messages = baseMessages,
            keptIndices = pruningPlan.keptIndices,
            recentMessageWindow = TOOL_RESULT_RECENT_MESSAGE_WINDOW
        )
        val foldedSummary = buildFoldedToolHistorySummary(pruningPlan.removedMessages)
        val history = mutableListOf<ChatMessage>()
        val toolSummaryBuffer = mutableListOf<String>()
        var summaryInserted = false

        fun flushToolSummaryBuffer() {
            val combined = buildCombinedToolHistorySummary(toolSummaryBuffer) ?: return
            history += combined
            toolSummaryBuffer.clear()
        }

        baseMessages.forEachIndexed { index, uiMsg ->
            if (isToolResultHistoryMessage(uiMsg) && index !in pruningPlan.keptIndices) {
                if (!summaryInserted && foldedSummary != null) {
                    flushToolSummaryBuffer()
                    history += foldedSummary
                    summaryInserted = true
                }
                return@forEachIndexed
            }
            if (uiMsg.role != "user" && uiMsg.role != "assistant" && !isToolResultHistoryMessage(uiMsg)) {
                return@forEachIndexed
            }
            if (uiMsg.role == "tool_exec") {
                (if (index in elisionPlan.elidedIndices) {
                    buildElidedToolResultHistorySummary(uiMsg.content)
                } else {
                    summarizeToolResultForHistory(uiMsg.content)
                })
                    ?.takeIf { it.isNotBlank() }
                    ?.let { toolSummaryBuffer += it }
                return@forEachIndexed
            }
            flushToolSummaryBuffer()
            history += ChatMessage(
                role = uiMsg.role,
                content = uiMsg.content.ifBlank { null },
                images = uiMsg.imageAttachments.mapNotNull(::buildChatImageAttachment)
            )
        }
        flushToolSummaryBuffer()
        return history.filter { it.content != null || it.images.isNotEmpty() }
    }

    private fun planToolResultHistoryPruning(
        messages: List<ChatMessageUi>
    ): ToolHistoryPruningPlan {
        val toolIndices = messages.mapIndexedNotNull { index, message ->
            index.takeIf { isToolResultHistoryMessage(message) }
        }
        if (toolIndices.size <= MAX_TOOL_RESULTS_IN_HISTORY) {
            return ToolHistoryPruningPlan(
                keptIndices = toolIndices.toSet(),
                removedMessages = emptyList()
            )
        }

        val recentBoundary = (messages.size - TOOL_RESULT_RECENT_MESSAGE_WINDOW).coerceAtLeast(0)
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        val keepIndices = buildSet {
            toolIndices.takeLast(MAX_TOOL_RESULTS_IN_HISTORY).forEach(::add)
            toolIndices.filterTo(this) { index ->
                index >= recentBoundary ||
                    (lastUserIndex >= 0 && index > lastUserIndex) ||
                    shouldKeepToolResultInHistory(messages[index])
            }
        }
        val removedMessages = toolIndices
            .filterNot { it in keepIndices }
            .map { messages[it] }
        return ToolHistoryPruningPlan(
            keptIndices = keepIndices,
            removedMessages = removedMessages
        )
    }

    private fun shouldKeepToolResultInHistory(message: ChatMessageUi): Boolean {
        if (message.content.contains("\n\n本次文件变更:\n")) return true
        val payload = extractToolResultPayload(message.content)
        val firstMeaningfulLine = payload.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return false
        return firstMeaningfulLine.startsWith("Error", ignoreCase = true) ||
            firstMeaningfulLine.startsWith("Rejected by user", ignoreCase = true) ||
            firstMeaningfulLine.contains("Unknown tool", ignoreCase = true)
    }

    private fun buildFoldedToolHistorySummary(
        removedMessages: List<ChatMessageUi>
    ): ChatMessage? {
        if (removedMessages.isEmpty()) return null
        val toolSummary = removedMessages
            .mapNotNull(::extractToolNameFromResultMessage)
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(4)
            .joinToString("、") { (tool, count) ->
                if (count > 1) "$tool x$count" else tool
            }
        val content = buildString {
            append("历史工具结果已折叠，以保持上下文缓存稳定。")
            append(" 已省略 ")
            append(removedMessages.size)
            append(" 条较早的成功工具结果")
            if (toolSummary.isNotBlank()) {
                append("，涉及 ")
                append(toolSummary)
            }
            append("。需要时可继续参考后续保留的最近结果与文件变更摘要。")
        }
        return ChatMessage(role = "assistant", content = content)
    }

    private fun buildCombinedToolHistorySummary(
        summaries: List<String>
    ): ChatMessage? {
        if (summaries.isEmpty()) return null
        val recentSummaries = summaries.takeLast(MAX_COMBINED_TOOL_SUMMARY_ITEMS)
        val omittedCount = (summaries.size - recentSummaries.size).coerceAtLeast(0)
        val content = if (recentSummaries.size == 1 && omittedCount == 0) {
            recentSummaries.first()
        } else {
            buildString {
                append("最近工具结果汇总:\n")
                if (omittedCount > 0) {
                    append("- 已省略更早的 ")
                    append(omittedCount)
                    append(" 条最近工具结果摘要，以保持上下文紧凑。\n")
                }
                recentSummaries.forEach { summary ->
                    append("- ")
                    append(summary)
                    append('\n')
                }
            }.trimEnd()
        }
        return ChatMessage(role = "assistant", content = content)
    }

    private fun buildTurnScopedUserContext(
        fileMentionContext: String?,
        executionInterruptContext: String,
        multimodalContext: String?,
        turnOrchestratorContext: String?,
        planningModeContext: String?,
        finalReadinessContext: String?,
        recentSessionHistoryReferenceContext: String?,
        recentSkillUsageContext: String?,
        recentMemoryUpdateSuggestionContext: String?,
        extraUserContext: String? = null
    ): String {
        return listOfNotNull(
            multimodalContext?.trim()?.takeIf { it.isNotBlank() },
            turnOrchestratorContext?.trim()?.takeIf { it.isNotBlank() },
            planningModeContext?.trim()?.takeIf { it.isNotBlank() },
            finalReadinessContext?.trim()?.takeIf { it.isNotBlank() },
            recentSessionHistoryReferenceContext?.trim()?.takeIf { it.isNotBlank() },
            recentSkillUsageContext?.trim()?.takeIf { it.isNotBlank() },
            recentMemoryUpdateSuggestionContext?.trim()?.takeIf { it.isNotBlank() },
            fileMentionContext?.trim()?.takeIf { it.isNotBlank() },
            executionInterruptContext.trim().takeIf { it.isNotBlank() },
            extraUserContext?.trim()?.takeIf { it.isNotBlank() }
        ).joinToString("\n\n")
    }

    private fun buildSkillSelectionUserContext(selectedSkills: List<GlobalSkill>): String? {
        val activeSkills = selectedSkills
            .mapNotNull { skill ->
                val content = skill.content.trim()
                if (content.isBlank()) return@mapNotNull null
                skill to content
            }
        if (activeSkills.isEmpty()) return null
        return buildString {
            appendLine("本轮由用户手动选择以下 Skills，请显式遵守：")
            activeSkills.forEach { (skill, content) ->
                appendLine()
                appendLine("Skill: ${skill.title.ifBlank { "未命名 Skill" }}")
                skill.description.trim().takeIf { it.isNotBlank() }?.let {
                    appendLine("Description: $it")
                }
                appendLine("Run As: ${skill.runAs.name.lowercase()}")
                if (skill.allowedTools.isNotEmpty()) {
                    appendLine("Allowed Tools: ${skill.allowedTools.joinToString(", ")}")
                }
                skill.preferredModel.trim().takeIf { it.isNotBlank() }?.let {
                    appendLine("Preferred Model: $it")
                }
                appendLine("Instruction:")
                appendLine(content)
            }
        }.trim()
    }

    private fun extractToolNameFromResultMessage(message: ChatMessageUi): String? {
        return Regex("""^📦 \*\*(.+?)\*\* 执行结果:""")
            .find(message.content)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildMultimodalSystemContext(imageCount: Int): String {
        val imageLabel = if (imageCount > 1) "${imageCount}张图片" else "图片"
        return """
            当前用户消息包含$imageLabel。
            请优先做图片理解，并严格按下面固定结构回复，标题必须保留原样：
            
            [图片摘要]
            用 2-4 行概括图中关键信息、场景、控件或报错主题。
            
            [OCR文本]
            尽量提取图中可辨认文字；如果没有明确文字，请写“未识别到明确文字”。
            
            [风险提示]
            仅列出真正需要用户注意的风险、歧义、缺失信息或可能误判点；如果没有明显风险，请写“暂无明显风险”。
            
            [继续追问]
            给出 2-4 条可以直接继续发送的追问建议，每条单独一行，并以 “- ” 开头。
            
            [详细分析]
            可选。若需要再补充更细的推断、解决步骤或实现建议，请放在这里；如果没有可省略。
        """.trimIndent()
    }

    private fun imageCacheDir(): File {
        return File(context.filesDir, "conversation_media").also { it.mkdirs() }
    }

    private fun importPendingImageAttachments(
        pendingImages: List<PendingImageAttachmentUi>
    ): List<MessageImageAttachmentUi> {
        return pendingImages.mapNotNull(::importPendingImageAttachment)
    }

    private fun importPendingImageAttachment(
        pending: PendingImageAttachmentUi
    ): MessageImageAttachmentUi? {
        val uri = runCatching { Uri.parse(pending.uri) }.getOrNull() ?: return null
        val mimeType = pending.mimeType
            ?.takeIf { it.startsWith("image/") }
            ?: context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"
        val bytes = when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { path ->
                runCatching { File(path).readBytes() }.getOrNull()
            }
            else -> context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val targetFile = File(imageCacheDir(), "${UUID.randomUUID()}.$extension")
        targetFile.writeBytes(bytes)
        val resolvedFileName = pending.fileName.ifBlank {
            resolveDisplayName(uri) ?: targetFile.name
        }
        return MessageImageAttachmentUi(
            fileName = resolvedFileName,
            mimeType = mimeType,
            localCachePath = targetFile.absolutePath,
            width = bounds.outWidth.takeIf { it > 0 },
            height = bounds.outHeight.takeIf { it > 0 },
            sizeBytes = bytes.size.toLong()
        )
    }

    private fun buildChatImageAttachment(
        attachment: MessageImageAttachmentUi
    ): com.murong.agent.core.provider.ChatImageAttachment? {
        val file = File(attachment.localCachePath)
        if (!file.exists()) return null
        val encoded = runCatching {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }.getOrNull() ?: return null
        return com.murong.agent.core.provider.ChatImageAttachment(
            mimeType = attachment.mimeType,
            base64Data = encoded,
            fileName = attachment.fileName,
            width = attachment.width,
            height = attachment.height,
            sizeBytes = attachment.sizeBytes
        )
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        ) ?: return uri.lastPathSegment
        cursor.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return it.getString(index)
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun createToolRegistry(
        provider: com.murong.agent.core.provider.ModelProvider,
        config: ProviderConfig,
        allowWriteTools: Boolean = true
    ): ToolRegistry {
        val registry = ToolRegistry()
        val subagentTool = if (config.isBuiltinToolEnabled("subagent")) {
            createSubagentTool(provider, config)
        } else {
            null
        }
        var currentGlobalSkills = config.globalSkills
        val memoryStore = PersistedMemoryStore(
            baseDir = File(context.filesDir, "memories"),
            globalMemoriesProvider = { config.globalMemories },
            projectMemoriesProvider = { _state.value.projectMemories },
            currentProjectPathProvider = {
                activeProjectScopePath(
                    activeScopePath = _state.value.activeProjectScopePath,
                    projectPath = _state.value.projectPath
                )
            }
        )
        val skillStore = PersistedSkillStore(
            globalSkillsProvider = { currentGlobalSkills },
            projectSkillsProvider = { _state.value.projectSkills },
            saveGlobalSkills = { updated ->
                currentGlobalSkills = updated
                val latestConfig = configRepository.getConfig()
                configRepository.saveConfig(latestConfig.copy(globalSkills = updated))
            },
            saveProjectSkills = { updated ->
                updateProjectConfig(skills = updated)
            }
        )
        registry.register(
            AskUserTool(
                requestAnswer = ::requestAskUser
            )
        )
        registry.register(
            CreateGlobalRuleTool(
                configProvider = { configRepository.getConfig() },
                saveConfig = { configRepository.saveConfig(it) }
            )
        )
        registry.register(
            CreateGlobalMemoryTool(
                memoryStore = memoryStore
            )
        )
        registry.register(
            MemoryListTool(
                memoryStore = memoryStore
            )
        )
        registry.register(
            MigrateLegacyMemoriesTool(
                memoryStore = memoryStore,
                currentProjectScopePathProvider = {
                    activeProjectScopePath(
                        activeScopePath = _state.value.activeProjectScopePath,
                        projectPath = _state.value.projectPath
                    )
                }
            )
        )
        registry.register(
            RememberMemoryTool(
                memoryStore = memoryStore,
                currentProjectScopePathProvider = {
                    activeProjectScopePath(
                        activeScopePath = _state.value.activeProjectScopePath,
                        projectPath = _state.value.projectPath
                    )
                },
                suggestionProvider = ::findRememberMemorySuggestion,
                onSuggestionApplied = ::markMemoryUpdateSuggestionApplied
            )
        )
        registry.register(
            MemorySearchTool(
                memoryStore = memoryStore
            )
        )
        registry.register(
            MemoryReadTool(
                memoryStore = memoryStore
            )
        )
        registry.register(
            ForgetMemoryTool(
                memoryStore = memoryStore
            )
        )
        registry.register(
            CreateGlobalSkillTool(
                skillStore = skillStore
            )
        )
        registry.register(
            ReadSkillTool(
                skillStore = skillStore
            )
        )
        registry.register(
            RunSkillTool(
                skillStore = skillStore,
                subagentExecutor = subagentTool?.let { tool ->
                    { args -> tool.executeWithResult(args) }
                }
            )
        )
        if (mcpRegistry != null) {
            registry.register(
                CreateMcpServerTool(
                    configsProvider = { mcpRegistry.loadConfigs() },
                    saveConfigs = { mcpRegistry.saveConfigs(it) },
                    connectAll = { configs ->
                        connectMcpServersWithHook(
                            configs = configs,
                            trigger = "create_mcp_server"
                        )
                    }
                )
            )
        }
        registry.register(
            CreateProjectRuleTool(
                scopePathProvider = {
                    activeProjectScopePath(
                        activeScopePath = _state.value.activeProjectScopePath,
                        projectPath = _state.value.projectPath
                    )
                },
                scopeLabelProvider = ::currentProjectScopeLabel,
                rulesProvider = { _state.value.projectRules },
                updateRules = { updateProjectConfig(rules = it) }
            )
        )
        registry.register(
            CreateProjectMemoryTool(
                scopePathProvider = {
                    activeProjectScopePath(
                        activeScopePath = _state.value.activeProjectScopePath,
                        projectPath = _state.value.projectPath
                    )
                },
                scopeLabelProvider = ::currentProjectScopeLabel,
                memoryStore = memoryStore
            )
        )
        registry.register(
            CreateProjectSkillTool(
                scopePathProvider = {
                    activeProjectScopePath(
                        activeScopePath = _state.value.activeProjectScopePath,
                        projectPath = _state.value.projectPath
                    )
                },
                scopeLabelProvider = ::currentProjectScopeLabel,
                skillStore = skillStore
            )
        )
        registry.register(
            SessionHistorySearchTool(
                sessionsProvider = conversationStore::listSessions,
                sessionLoader = conversationStore::loadSession,
                currentSessionIdProvider = { currentSessionId },
                currentProjectPathProvider = { normalizeProjectPath(_state.value.projectPath) }
            )
        )
        registry.register(
            TaskRepoSearchCodeTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            )
        )
        registry.register(
            TaskRepoListDirTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            )
        )
        registry.register(
            TaskRepoListBranchesTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            )
        )
        registry.register(
            TaskRepoCreateBranchTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            ),
            isEnabled = { allowWriteTools },
            isPromptExposed = { true }
        )
        registry.register(
            TaskRepoCreatePrTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            ),
            isEnabled = { allowWriteTools },
            isPromptExposed = { true }
        )
        registry.register(
            TaskRepoClosePrTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            ),
            isEnabled = { allowWriteTools },
            isPromptExposed = { true }
        )
        registry.register(
            TaskRepoDeleteBranchTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            ),
            isEnabled = { allowWriteTools },
            isPromptExposed = { true }
        )
        registry.register(
            TaskRepoReadFileTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            )
        )
        registry.register(
            TaskRepoSearchReplaceTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            ),
            isEnabled = { allowWriteTools },
            isPromptExposed = { true }
        )
        registry.register(
            TaskRepoApplyPatchTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            ),
            isEnabled = { allowWriteTools },
            isPromptExposed = { true }
        )
        registry.register(
            TaskRepoUpdateFileTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            ),
            isEnabled = { allowWriteTools },
            isPromptExposed = { true }
        )
        registry.register(
            TaskRepoDeleteFileTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            ),
            isEnabled = { allowWriteTools },
            isPromptExposed = { true }
        )
        registry.register(
            TaskRepoCommitFilesTool(
                repositoryProvider = ::currentRemoteTaskRepositoryTarget,
                githubTokenProvider = { config.githubToken },
                githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() }
            ),
            isEnabled = { allowWriteTools },
            isPromptExposed = { true }
        )
        registry.register(
            CompleteStepTool(
                recentToolReceiptsProvider = ::buildRecentToolExecutionReceipts,
                workflowSnapshotProvider = ::buildWorkflowExecutionSnapshot,
                onWorkflowStepCompleted = ::markWorkflowStepCompleted
            )
        )
        if (config.isBuiltinToolEnabled("shell")) {
            registry.register(
                ShellTool(
                    scheduleBackgroundExecution = ::scheduleBackgroundShellExecution
                ),
                isEnabled = { allowWriteTools && shouldExposeLocalShellTool() },
                isPromptExposed = { shouldExposeLocalShellTool() }
            )
        }
        if (config.isBuiltinToolEnabled("file")) {
            val fileOps = config.getEnabledFileToolOperations()
            val filteredOps = if (shouldExposeLocalFileWriteTool()) {
                fileOps
            } else {
                fileOps.filter { it in setOf("read", "list", "exists") }.toSet()
            }
            registry.register(
                FileTool(filteredOps),
                isEnabled = { shouldExposeLocalFileReadTool() }
            )
        }
        if (config.isBuiltinToolEnabled("code_edit")) {
            registry.register(
                CodeEditTool(),
                isEnabled = { allowWriteTools && shouldExposeLocalCodeEditTool() },
                isPromptExposed = { shouldExposeLocalCodeEditTool() }
            )
        }
        if (config.isBuiltinToolEnabled("code_search")) {
            registry.register(
                CodeSearchTool(),
                isEnabled = ::shouldExposeLocalCodeSearchTool
            )
        }
        if (config.isBuiltinToolEnabled("web_search")) {
            registry.register(WebSearchTool(config))
        }
        if (config.isBuiltinToolEnabled("web_fetch")) {
            registry.register(WebFetchTool())
        }
        if (subagentTool != null) {
            registry.register(subagentTool)
            createSubagentPresetTools(subagentTool)
                .filter { preset -> config.isBuiltinToolEnabled(preset.name) }
                .forEach { registry.register(it) }
        }
        return registry
    }

    private fun createSubagentTool(
        provider: com.murong.agent.core.provider.ModelProvider,
        config: ProviderConfig
    ): SubagentTool {
        return SubagentTool(
            provider = provider,
            baseConfig = config,
            projectTemplates = config.projectToolPreferences?.subagentTemplates.orEmpty(),
            allowedFileOperations = config.getEnabledSubagentFileOperations(),
            writableFileOperations = config.getEnabledFileToolOperations().filter { it == "write" }.toSet(),
            allowWebSearchTool = config.isBuiltinToolEnabled("web_search"),
            allowCodeEditTool = config.isBuiltinToolEnabled("code_edit"),
            allowShellTool = config.isBuiltinToolEnabled("shell"),
            remoteTaskRepositoryTargetProvider = ::currentRemoteTaskRepositoryTarget,
            remoteTaskRepositoryEditableProvider = { _state.value.remoteTaskRepositoryEditable },
            githubTokenProvider = { config.githubToken },
            githubApiBaseUrlProvider = { config.getGitHubApiBaseUrl() },
            mcpRegistry = mcpRegistry,
            isCancellationRequested = ::isSubagentCancellationRequested,
            requestApproval = ::waitForApproval,
            scheduleBackgroundExecution = ::scheduleBackgroundSubagentExecution,
            onUiEvent = ::appendSubagentEvent
        )
    }

    private fun buildTaskTitle(projectPath: String): String {
        val normalized = projectPath.trim().trimEnd('/', '\\')
        if (normalized.isBlank()) return "新任务"
        val projectName = normalized.substringAfterLast('/').substringAfterLast('\\')
        return if (projectName.isBlank()) normalized else projectName
    }

    private suspend fun waitForApproval(
        request: ToolApprovalRequest,
        planApprovalAutoApproveWindow: PlanApprovalAutoApproveWindow? = null
    ): Boolean {
        val scopeSummary = approvalScopeSummaryOrNull(request.approvalScopeTokens)
        val requestSubject = approvalRequestSubjectLabel(request)
        val postureMode = lastSessionConfig.approvalMode
        val approvalDecision = lastSessionConfig.evaluateApprovalRequirement(
            riskLevel = request.riskLevel,
            toolName = request.toolName,
            approvalScopeTokens = request.approvalScopeTokens,
            commandBoundaryValue = request.commandBoundaryValue,
            pathBoundaryValue = request.pathBoundaryValue
        )
        val planWindowDecision = resolvePlanApprovalAutoApproveDecision(
            window = planApprovalAutoApproveWindow,
            request = request
        )
        val serializationDecision = resolvePromptRuntimePostureDecision(
            effectiveMode = postureMode,
            requestedKind = PendingPromptKind.APPROVAL,
            hasPendingApproval = hasPendingApprovalPrompt(),
            hasPendingAsk = hasPendingAskPrompt()
        )
        val reusedScope = isApprovalScopeAlreadyGranted(request)
        val autoInheritedCandidate = if (reusedScope) null else findAutoInheritedApprovalCandidate(request)
        val inheritedScope = autoInheritedCandidate?.let { candidate ->
            mergeApprovedApprovalScope(
                buildAutoInheritedApprovalScope(
                    scope = candidate.scope,
                    sourceSessionId = candidate.sourceSessionId,
                    sourceSessionTitle = candidate.sourceSessionTitle,
                    sourceUpdatedAt = candidate.sourceUpdatedAt
                )
            )
        } == true
        val runtimeDecision = resolveApprovalRuntimePostureDecision(
            effectiveMode = postureMode,
            hasReusableScope = reusedScope,
            hasAutoInheritedScope = inheritedScope,
            approvalRequirement = approvalDecision,
            planWindowDecision = planWindowDecision,
            promptDecision = serializationDecision
        )
        when (runtimeDecision.kind) {
            ApprovalRuntimeDecisionKind.REUSE_EXISTING_SCOPE -> {
                recordApproval(
                    toolName = request.toolName,
                    summary = "${request.summary}（复用授权）",
                    decision = "复用授权",
                    scopeSummary = scopeSummary,
                    explanationLabel = "命中当前会话授权",
                    explanationDetail = buildString {
                        append("当前会话里已存在可覆盖本次")
                        append(requestSubject)
                        append("的授权范围")
                        scopeSummary?.let { append("：$it") }
                        append("，因此直接复用，不再重复申请审批。")
                    }
                )
                return true
            }

            ApprovalRuntimeDecisionKind.AUTO_INHERIT_SCOPE -> {
                val candidate = autoInheritedCandidate ?: return false
                recordApproval(
                    toolName = request.toolName,
                    summary = "${request.summary}（项目自动继承: ${candidate.sourceSessionTitle}）",
                    decision = "项目自动继承",
                    scopeSummary = scopeSummary,
                    explanationLabel = "命中同项目自动继承",
                    explanationDetail = buildString {
                        append("同项目历史会话“")
                        append(candidate.sourceSessionTitle)
                        append("”中存在仍然有效且由用户直接批准的授权范围")
                        scopeSummary?.let { append("：$it") }
                        append("，本次按保守边界自动继承。")
                    }
                )
                return true
            }

            ApprovalRuntimeDecisionKind.AUTO_APPROVE_BY_POSTURE -> {
                registerApprovedApprovalScope(request)
                recordApproval(
                    toolName = request.toolName,
                    summary = request.summary,
                    decision = "自动批准",
                    scopeSummary = scopeSummary,
                    explanationLabel = runtimeDecision.explanationLabel,
                    explanationDetail = buildString {
                        append(runtimeDecision.explanationDetail)
                        scopeSummary?.let { append("，并记录范围：$it") }
                        append("。")
                    }
                )
                return true
            }

            ApprovalRuntimeDecisionKind.AUTO_APPROVE_BY_PLAN_WINDOW -> {
                recordApproval(
                    toolName = request.toolName,
                    summary = "${request.summary}（按计划执行窗口）",
                    decision = "计划窗口自动放行",
                    scopeSummary = scopeSummary,
                    explanationLabel = runtimeDecision.explanationLabel,
                    explanationDetail = buildString {
                        append(runtimeDecision.explanationDetail)
                        scopeSummary?.let { append(" 当前请求范围：$it") }
                    }
                )
                return true
            }

            ApprovalRuntimeDecisionKind.BLOCKED_BY_PROMPT_SERIALIZATION -> {
                recordApproval(
                    toolName = request.toolName,
                    summary = "${request.summary}（串行化守门拦截）",
                    decision = "串行化守门拦截",
                    scopeSummary = scopeSummary,
                    explanationLabel = runtimeDecision.explanationLabel,
                    explanationDetail = runtimeDecision.explanationDetail
                )
                appendSystemMessage(
                    content = "⚠️ ${runtimeDecision.explanationDetail}",
                    source = "approval:blocked_by_prompt_serialization"
                )
                return false
            }

            ApprovalRuntimeDecisionKind.REQUIRE_MANUAL_APPROVAL -> Unit
        }
        val requestedApproval = applyApprovalRequestedHook(
            toolName = request.toolName,
            summary = request.summary,
            detail = request.detail,
            rawArgs = request.rawArgs,
            riskLevel = request.riskLevel,
            requestSubject = requestSubject,
            scopeSummary = scopeSummary,
            explanationLabel = runtimeDecision.explanationLabel,
            explanationDetail = runtimeDecision.explanationDetail,
            hookBus = hookBus
        )
        val deferred = CompletableDeferred<Boolean>()
        val approvalTransitionPayload = buildApprovalDecisionTransitionPayload(_state.value)
        pendingApprovalDecision = deferred
        _state.value = _state.value.copy(
            pendingApproval = PendingApprovalUi(
                toolName = requestedApproval.toolName,
                summary = requestedApproval.summary,
                detail = requestedApproval.detail,
                rawArgs = requestedApproval.rawArgs,
                riskLevel = requestedApproval.riskLevel,
                explanationLabel = requestedApproval.explanationLabel,
                explanationDetail = requestedApproval.explanationDetail
            )
        )
        saveCurrentSession()
        return try {
            val decision = deferred.await()
            if (decision) {
                registerApprovedApprovalScope(request)
            }
            recordApproval(
                toolName = requestedApproval.toolName,
                summary = requestedApproval.summary,
                decision = if (decision) "Approved" else "Rejected",
                scopeSummary = requestedApproval.scopeSummary,
                explanationLabel = if (decision) "用户确认放行" else "用户拒绝",
                explanationDetail = buildString {
                    append(requestedApproval.explanationDetail)
                    append("\n")
                    append("本次")
                    append(requestedApproval.requestSubject)
                    append("未命中会话复用、项目自动继承或当前审批放行规则，因此进入人工审批；用户已")
                    append(if (decision) "批准" else "拒绝")
                    append("该请求")
                    requestedApproval.scopeSummary?.let { append("，申请范围：$it") }
                    append("。")
                }
            )
            _state.value = applyApprovalDecisionTransition(
                state = _state.value,
                approved = decision,
                payload = approvalTransitionPayload,
                rejectedSummary = requestedApproval.summary
            )
            if (decision) {
                approvalTransitionPayload.approvedResumePayload?.let { resumePayload ->
                    recordConversationPromptCheckpoint(summary = resumePayload.recoverySummary)
                }
            }
            saveCurrentSession()
            decision
        } finally {
            pendingApprovalDecision = null
            if (_state.value.pendingApproval != null) {
                _state.value = applyPendingApprovalClearedTransition(_state.value)
                saveCurrentSession()
            }
        }
    }

    private fun clearPendingApproval() {
        pendingApprovalDecision = null
        _state.value = applyPendingApprovalClearedTransition(_state.value)
        saveCurrentSession()
    }

    private fun clearPendingAsk() {
        pendingAskDecision = null
        _state.value = _state.value.copy(pendingAskRequest = null)
        saveCurrentSession()
    }

    private fun hasPendingApprovalPrompt(): Boolean {
        return _state.value.pendingApproval != null || pendingApprovalDecision != null
    }

    private fun hasPendingAskPrompt(): Boolean {
        return _state.value.pendingAskRequest != null || pendingAskDecision != null
    }

    private suspend fun requestAskUser(request: PendingAskRequestUi): List<AskAnswerUi>? {
        val serializationDecision = resolvePromptRuntimePostureDecision(
            effectiveMode = lastSessionConfig.approvalMode,
            requestedKind = PendingPromptKind.ASK,
            hasPendingApproval = hasPendingApprovalPrompt(),
            hasPendingAsk = hasPendingAskPrompt()
        )
        if (serializationDecision.blocked) {
            appendSystemMessage(
                content = "⚠️ ${serializationDecision.explanationDetail}",
                source = "ask_user:blocked_by_prompt_serialization"
            )
            return null
        }
        val requestWithHistory = buildStateAwarePendingAskRequest(
            request = request,
            state = _state.value
        )
        val deferred = CompletableDeferred<List<AskAnswerUi>?>()
        pendingAskDecision = deferred
        _state.value = _state.value.copy(pendingAskRequest = requestWithHistory)
        recordConversationPromptCheckpoint(
            summary = "交互状态: 等待用户确认",
            createdAt = requestWithHistory.createdAt
        )
        saveCurrentSession()
        return try {
            deferred.await()
        } finally {
            clearPendingAsk()
        }
    }

    private fun appendUsage(usage: Usage, config: ProviderConfig) {
        val current = _state.value.usageSummary
        val cacheShape = pendingPromptCacheShape ?: capturePromptCacheShape(
            stableSystemContext = null,
            toolsJson = "",
            compressionContext = null,
            projectContext = null,
            sessionGoalContext = null,
            projectSkillsContext = null,
            mcpToolsContext = null,
            readOnlyPlanMode = false
        )
        val cacheDiagnostics = buildPromptCacheDiagnostics(
            previous = current,
            current = cacheShape
        )
        val updatedPromptTokens = current.promptTokens + usage.promptTokens
        val updatedCompletionTokens = current.completionTokens + usage.completionTokens
        val updatedTotalTokens = current.totalTokens + usage.totalTokens
        val updatedCacheHit = current.promptCacheHitTokens + (usage.promptCacheHitTokens ?: 0)
        val updatedCacheMiss = current.promptCacheMissTokens + (usage.promptCacheMissTokens ?: 0)
        val estimatedCostCurrency = config.estimateCostCurrency()
        val updatedEstimatedCostAmount = current.resolvedEstimatedCostAmount() + config.estimateCostAmount(
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens
        )
        val updatedEstimatedCostUsd = current.estimatedCostUsd + config.estimateCostUsd(
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens
        )

        _state.value = _state.value.copy(
            usageSummary = UsageSummarySnapshot(
                promptTokens = updatedPromptTokens,
                completionTokens = updatedCompletionTokens,
                totalTokens = updatedTotalTokens,
                promptCacheHitTokens = updatedCacheHit,
                promptCacheMissTokens = updatedCacheMiss,
                lastTurnPromptCacheHitTokens = usage.promptCacheHitTokens ?: 0,
                lastTurnPromptCacheMissTokens = usage.promptCacheMissTokens ?: 0,
                lastCachePrefixHash = cacheDiagnostics.shape.prefixHash,
                lastCacheStableSystemHash = cacheDiagnostics.shape.stableSystemHash,
                lastCacheToolsHash = cacheDiagnostics.shape.toolsHash,
                lastCacheCompressionHash = cacheDiagnostics.shape.compressionHash,
                lastCacheProjectContextHash = cacheDiagnostics.shape.projectContextHash,
                lastCacheSessionGoalHash = cacheDiagnostics.shape.sessionGoalHash,
                lastCacheProjectSkillsHash = cacheDiagnostics.shape.projectSkillsHash,
                lastCacheMcpToolsHash = cacheDiagnostics.shape.mcpToolsHash,
                lastCachePlanModeHash = cacheDiagnostics.shape.planModeHash,
                lastCachePrefixChanged = cacheDiagnostics.prefixChanged,
                lastCachePrefixChangeReasons = cacheDiagnostics.prefixChangeReasons,
                estimatedCostAmount = updatedEstimatedCostAmount,
                estimatedCostCurrency = estimatedCostCurrency,
                estimatedCostUsd = updatedEstimatedCostUsd
            )
        )
        pendingPromptCacheShape = null
    }

    private fun appendSubagentEvent(originalEvent: SubagentUiEvent) {
        val event = applySubagentLifecycleHook(
            event = originalEvent,
            hookBus = hookBus
        )
        val affectedBatchId = when (event) {
            is SubagentUiEvent.BatchApprovalRequested -> event.batchId
            is SubagentUiEvent.BatchRejected -> event.batchId
            is SubagentUiEvent.BatchQueued -> event.batchId
            is SubagentUiEvent.ApprovalRequested -> event.batchId
            is SubagentUiEvent.Queued -> event.batchId
            is SubagentUiEvent.Started -> event.batchId
            is SubagentUiEvent.Summarizing -> findSubagentBatchIdForRun(event.runId)
            is SubagentUiEvent.Completed -> event.batchId
            is SubagentUiEvent.Failed -> event.batchId
            is SubagentUiEvent.Cancelled -> event.batchId
            is SubagentUiEvent.Rejected -> event.batchId
        }
        when (event) {
            is SubagentUiEvent.BatchApprovalRequested -> {
                val approvalAt = System.currentTimeMillis()
                upsertSubagentBatch(
                    batchId = event.batchId,
                    timestamp = approvalAt
                ) { existing ->
                    (existing ?: SubagentBatchUi(
                        batchId = event.batchId,
                        parentGoal = event.parentGoal,
                        label = event.label,
                        runIds = event.runIds,
                        splitStrategyLabel = event.splitStrategyLabel,
                        splitStrategyDetail = event.splitStrategyDetail,
                        status = "pending_approval",
                        statusMessage = event.statusMessage,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        startedAt = approvalAt
                    )).copy(
                        parentGoal = event.parentGoal,
                        label = event.label,
                        runIds = event.runIds,
                        splitStrategyLabel = event.splitStrategyLabel,
                        splitStrategyDetail = event.splitStrategyDetail,
                        status = "pending_approval",
                        statusMessage = "子代理编排正在等待一次性提权审批。",
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        approvalRequestedAt = existing?.approvalRequestedAt ?: approvalAt,
                        finishedAt = null
                    )
                }
                ensureSubagentBatchMessage(
                    batchId = event.batchId,
                    content = buildSubagentBatchApprovalMessage(
                        label = event.label,
                        parentGoal = event.parentGoal,
                        runIds = event.runIds,
                        splitStrategyLabel = event.splitStrategyLabel,
                        splitStrategyDetail = event.splitStrategyDetail
                    ),
                    timestamp = approvalAt
                )
                appendSubagentBatchTimeline(
                    batchId = event.batchId,
                    timestamp = approvalAt,
                    type = "batch_pending_approval",
                    title = "批次等待审批",
                    detail = "${event.splitStrategyLabel}。${event.splitStrategyDetail}"
                )
            }

            is SubagentUiEvent.BatchRejected -> {
                val finishedAt = System.currentTimeMillis()
                upsertSubagentBatch(
                    batchId = event.batchId,
                    timestamp = finishedAt
                ) { existing ->
                    (existing ?: SubagentBatchUi(
                        batchId = event.batchId,
                        parentGoal = event.parentGoal,
                        label = event.label,
                        runIds = event.runIds,
                        splitStrategyLabel = event.splitStrategyLabel,
                        splitStrategyDetail = event.splitStrategyDetail,
                        status = "rejected",
                        statusMessage = event.reason,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        startedAt = finishedAt
                    )).copy(
                        parentGoal = event.parentGoal,
                        label = event.label,
                        runIds = event.runIds,
                        splitStrategyLabel = event.splitStrategyLabel,
                        splitStrategyDetail = event.splitStrategyDetail,
                        status = "rejected",
                        statusMessage = event.reason,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        approvalRequestedAt = existing?.approvalRequestedAt ?: finishedAt,
                        finishedAt = finishedAt
                    )
                }
                updateSubagentBatchMessage(
                    batchId = event.batchId,
                    content = buildSubagentBatchRejectedMessage(
                        label = event.label,
                        parentGoal = event.parentGoal,
                        runIds = event.runIds,
                        reason = event.reason,
                        splitStrategyLabel = event.splitStrategyLabel,
                        splitStrategyDetail = event.splitStrategyDetail
                    )
                )
                appendSubagentBatchTimeline(
                    batchId = event.batchId,
                    timestamp = finishedAt,
                    type = "batch_rejected",
                    title = "批次审批被拒绝",
                    detail = "${event.splitStrategyLabel}。${event.reason}"
                )
            }

            is SubagentUiEvent.BatchQueued -> {
                val queuedAt = System.currentTimeMillis()
                val statusMessage = "已按${event.splitStrategyLabel}拆分 ${event.runIds.size} 个子任务，后台会按并发上限逐步执行。"
                upsertSubagentBatch(
                    batchId = event.batchId,
                    timestamp = queuedAt
                ) { existing ->
                    (existing ?: SubagentBatchUi(
                        batchId = event.batchId,
                        parentGoal = event.parentGoal,
                        label = event.label,
                        runIds = event.runIds,
                        splitStrategyLabel = event.splitStrategyLabel,
                        splitStrategyDetail = event.splitStrategyDetail,
                        status = "queued",
                        statusMessage = statusMessage,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        startedAt = queuedAt
                    )).copy(
                        parentGoal = event.parentGoal,
                        label = event.label,
                        runIds = event.runIds,
                        splitStrategyLabel = event.splitStrategyLabel,
                        splitStrategyDetail = event.splitStrategyDetail,
                        status = "queued",
                        statusMessage = statusMessage,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        queuedAt = existing?.queuedAt ?: queuedAt,
                        finishedAt = null
                    )
                }
                ensureSubagentBatchMessage(
                    batchId = event.batchId,
                    content = buildSubagentBatchQueuedMessage(
                        label = event.label,
                        parentGoal = event.parentGoal,
                        runIds = event.runIds,
                        splitStrategyLabel = event.splitStrategyLabel,
                        splitStrategyDetail = event.splitStrategyDetail
                    ),
                    timestamp = queuedAt
                )
                appendSubagentBatchTimeline(
                    batchId = event.batchId,
                    timestamp = queuedAt,
                    type = "batch_queued",
                    title = "批次进入后台队列",
                    detail = "${event.splitStrategyDetail} 当前状态: $statusMessage"
                )
            }
            is SubagentUiEvent.ApprovalRequested -> {
                val approvalAt = System.currentTimeMillis()
                upsertSubagentRun(
                    runId = event.runId,
                    timestamp = approvalAt
                ) { existing ->
                    (existing ?: SubagentRunUi(
                        runId = event.runId,
                        status = "pending_approval",
                        goal = event.goal,
                        templateId = event.templateId,
                        templateTitle = event.templateTitle,
                        model = event.model,
                        reasoningEffort = event.reasoningEffort,
                        allowedTools = event.allowedTools,
                        statusMessage = event.statusMessage,
                        retryCount = event.retryCount,
                        sourceRunId = event.sourceRunId,
                        batchId = event.batchId,
                        batchLabel = event.batchLabel,
                        batchIndex = event.batchIndex,
                        batchSize = event.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        startedAt = approvalAt
                    )).copy(
                        status = "pending_approval",
                        goal = event.goal,
                        templateId = event.templateId ?: existing?.templateId,
                        templateTitle = event.templateTitle ?: existing?.templateTitle,
                        model = event.model,
                        reasoningEffort = event.reasoningEffort,
                        allowedTools = event.allowedTools,
                        statusMessage = if (event.retryCount > 0) {
                            "重试任务正在等待权限审批。"
                        } else {
                            "正在等待宿主会话审批更高工具权限。"
                        },
                        retryCount = event.retryCount,
                        sourceRunId = event.sourceRunId,
                        batchId = event.batchId ?: existing?.batchId,
                        batchLabel = event.batchLabel ?: existing?.batchLabel,
                        batchIndex = event.batchIndex ?: existing?.batchIndex,
                        batchSize = event.batchSize ?: existing?.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        approvalRequestedAt = existing?.approvalRequestedAt ?: approvalAt,
                        finishedAt = null
                    )
                }
                ensureSubagentMessage(
                    runId = event.runId,
                    content = "子代理等待权限审批",
                    timestamp = approvalAt
                )
                event.batchId?.let { batchId ->
                    appendSubagentBatchTimeline(
                        batchId = batchId,
                        timestamp = approvalAt,
                        type = "run_pending_approval",
                        title = formatBatchRunTitle(event.batchIndex, event.batchSize, event.goal, "等待审批"),
                        detail = "子任务需要更高工具权限。",
                        relatedRunId = event.runId
                    )
                }
            }
            is SubagentUiEvent.Queued -> {
                val queuedAt = System.currentTimeMillis()
                upsertSubagentRun(
                    runId = event.runId,
                    timestamp = queuedAt
                ) { existing ->
                    (existing ?: SubagentRunUi(
                        runId = event.runId,
                        status = "queued",
                        goal = event.goal,
                        templateId = event.templateId,
                        templateTitle = event.templateTitle,
                        model = event.model,
                        reasoningEffort = event.reasoningEffort,
                        allowedTools = event.allowedTools,
                        statusMessage = "",
                        retryCount = event.retryCount,
                        sourceRunId = event.sourceRunId,
                        batchId = event.batchId,
                        batchLabel = event.batchLabel,
                        batchIndex = event.batchIndex,
                        batchSize = event.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        startedAt = queuedAt
                    )).copy(
                        status = "queued",
                        goal = event.goal,
                        templateId = event.templateId ?: existing?.templateId,
                        templateTitle = event.templateTitle ?: existing?.templateTitle,
                        model = event.model,
                        reasoningEffort = event.reasoningEffort,
                        allowedTools = event.allowedTools,
                        statusMessage = if (event.retryCount > 0) {
                            "第 ${event.retryCount} 次重试已排队，等待启动。"
                        } else {
                            "已进入子代理队列，等待开始。"
                        },
                        retryCount = event.retryCount,
                        sourceRunId = event.sourceRunId,
                        batchId = event.batchId ?: existing?.batchId,
                        batchLabel = event.batchLabel ?: existing?.batchLabel,
                        batchIndex = event.batchIndex ?: existing?.batchIndex,
                        batchSize = event.batchSize ?: existing?.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        approvalRequestedAt = existing?.approvalRequestedAt,
                        queuedAt = existing?.queuedAt ?: queuedAt,
                        finishedAt = null
                    )
                }
                ensureSubagentMessage(
                    runId = event.runId,
                    content = if (event.retryCount > 0) "子代理重试排队中" else "子代理排队中",
                    timestamp = queuedAt
                )
                event.batchId?.let { batchId ->
                    appendSubagentBatchTimeline(
                        batchId = batchId,
                        timestamp = queuedAt,
                        type = "run_queued",
                        title = formatBatchRunTitle(event.batchIndex, event.batchSize, event.goal, "已排队"),
                        detail = "子任务已进入后台队列。",
                        relatedRunId = event.runId
                    )
                }
            }
            is SubagentUiEvent.Started -> {
                val startedAt = System.currentTimeMillis()
                clearSubagentCancellationRequest(event.runId)
                upsertSubagentRun(
                    runId = event.runId,
                    timestamp = startedAt
                ) { existing ->
                    (existing ?: SubagentRunUi(
                        runId = event.runId,
                        status = "running",
                        goal = event.goal,
                        templateId = event.templateId,
                        templateTitle = event.templateTitle,
                        model = event.model,
                        reasoningEffort = event.reasoningEffort,
                        allowedTools = event.allowedTools,
                        statusMessage = "",
                        retryCount = event.retryCount,
                        sourceRunId = event.sourceRunId,
                        batchId = event.batchId,
                        batchLabel = event.batchLabel,
                        batchIndex = event.batchIndex,
                        batchSize = event.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        startedAt = startedAt
                    )).copy(
                        status = "running",
                        goal = event.goal,
                        templateId = event.templateId ?: existing?.templateId,
                        templateTitle = event.templateTitle ?: existing?.templateTitle,
                        model = event.model,
                        reasoningEffort = event.reasoningEffort,
                        allowedTools = event.allowedTools,
                        statusMessage = if (event.retryCount > 0) {
                            "正在执行第 ${event.retryCount} 次重试。"
                        } else {
                            "正在执行子代理任务。"
                        },
                        retryCount = event.retryCount,
                        sourceRunId = event.sourceRunId,
                        batchId = event.batchId ?: existing?.batchId,
                        batchLabel = event.batchLabel ?: existing?.batchLabel,
                        batchIndex = event.batchIndex ?: existing?.batchIndex,
                        batchSize = event.batchSize ?: existing?.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        approvalRequestedAt = existing?.approvalRequestedAt,
                        queuedAt = existing?.queuedAt,
                        executionStartedAt = existing?.executionStartedAt ?: startedAt,
                        finishedAt = null
                    )
                }
                ensureSubagentMessage(
                    runId = event.runId,
                    content = if (event.retryCount > 0) "子代理重试运行中" else "子代理运行中",
                    timestamp = startedAt
                )
                event.batchId?.let { batchId ->
                    appendSubagentBatchTimeline(
                        batchId = batchId,
                        timestamp = startedAt,
                        type = "run_started",
                        title = formatBatchRunTitle(event.batchIndex, event.batchSize, event.goal, "已启动"),
                        detail = "子任务开始执行。",
                        relatedRunId = event.runId
                    )
                }
            }

            is SubagentUiEvent.Summarizing -> {
                val summarizingAt = System.currentTimeMillis()
                upsertSubagentRun(event.runId) { run ->
                    (run ?: SubagentRunUi(
                        runId = event.runId,
                        status = "summarizing",
                        goal = "",
                        model = "",
                        reasoningEffort = null,
                        allowedTools = emptyList(),
                        statusMessage = "执行已结束，正在整理摘要。",
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        startedAt = summarizingAt
                    )).copy(
                        status = "summarizing",
                        statusMessage = "执行已结束，正在整理摘要。",
                        summarizingAt = summarizingAt
                    )
                }
                updateSubagentMessage(event.runId, "子代理正在整理摘要")
            }

            is SubagentUiEvent.Completed -> {
                val finishedAt = System.currentTimeMillis()
                clearSubagentCancellationRequest(event.runId)
                upsertSubagentRun(event.runId) { run ->
                    val existingRun = run ?: SubagentRunUi(
                        runId = event.runId,
                        status = "completed",
                        goal = event.goal,
                        templateId = event.templateId,
                        templateTitle = event.templateTitle,
                        model = event.model,
                        reasoningEffort = event.reasoningEffort,
                        allowedTools = event.allowedTools,
                        summary = event.summary,
                        usageSummary = event.usageSummary,
                        batchId = event.batchId,
                        batchLabel = event.batchLabel,
                        batchIndex = event.batchIndex,
                        batchSize = event.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        statusMessage = "已完成并返回摘要。",
                        startedAt = finishedAt
                    )
                    existingRun.copy(
                        status = "completed",
                        summary = event.summary,
                        error = null,
                        templateId = event.templateId ?: existingRun.templateId,
                        templateTitle = event.templateTitle ?: existingRun.templateTitle,
                        model = event.model,
                        reasoningEffort = event.reasoningEffort,
                        allowedTools = event.allowedTools,
                        batchId = event.batchId ?: existingRun.batchId,
                        batchLabel = event.batchLabel ?: existingRun.batchLabel,
                        batchIndex = event.batchIndex ?: existingRun.batchIndex,
                        batchSize = event.batchSize ?: existingRun.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        statusMessage = if (existingRun.retryCount > 0) {
                            "重试后已完成。"
                        } else {
                            "已完成并返回摘要。"
                        },
                        usageSummary = event.usageSummary,
                        executionStartedAt = existingRun.executionStartedAt,
                        summarizingAt = existingRun.summarizingAt,
                        finishedAt = finishedAt
                    )
                }
                updateSubagentMessage(event.runId, "子代理已完成")
                event.batchId?.let { batchId ->
                    appendSubagentBatchTimeline(
                        batchId = batchId,
                        timestamp = finishedAt,
                        type = "run_completed",
                        title = formatBatchRunTitle(event.batchIndex, event.batchSize, event.goal, "已完成"),
                        detail = event.summary.lineSequence().firstOrNull().orEmpty(),
                        relatedRunId = event.runId
                    )
                }
            }

            is SubagentUiEvent.Failed -> {
                val finishedAt = System.currentTimeMillis()
                clearSubagentCancellationRequest(event.runId)
                upsertSubagentRun(event.runId) { run ->
                    val existingRun = run ?: SubagentRunUi(
                        runId = event.runId,
                        status = "failed",
                        goal = event.goal,
                        templateId = event.templateId,
                        templateTitle = event.templateTitle,
                        model = event.model,
                        reasoningEffort = event.reasoningEffort,
                        allowedTools = event.allowedTools,
                        error = event.error,
                        usageSummary = event.usageSummary,
                        batchId = event.batchId,
                        batchLabel = event.batchLabel,
                        batchIndex = event.batchIndex,
                        batchSize = event.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        statusMessage = "子代理执行失败。",
                        startedAt = finishedAt
                    )
                    existingRun.copy(
                        status = "failed",
                        error = event.error,
                        templateId = event.templateId ?: existingRun.templateId,
                        templateTitle = event.templateTitle ?: existingRun.templateTitle,
                        model = event.model,
                        reasoningEffort = event.reasoningEffort,
                        allowedTools = event.allowedTools,
                        batchId = event.batchId ?: existingRun.batchId,
                        batchLabel = event.batchLabel ?: existingRun.batchLabel,
                        batchIndex = event.batchIndex ?: existingRun.batchIndex,
                        batchSize = event.batchSize ?: existingRun.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        statusMessage = if (existingRun.retryCount > 0) {
                            "重试后仍然失败。"
                        } else {
                            "子代理执行失败。"
                        },
                        usageSummary = event.usageSummary,
                        executionStartedAt = existingRun.executionStartedAt,
                        summarizingAt = existingRun.summarizingAt,
                        finishedAt = finishedAt
                    )
                }
                updateSubagentMessage(event.runId, "子代理失败")
                event.batchId?.let { batchId ->
                    appendSubagentBatchTimeline(
                        batchId = batchId,
                        timestamp = finishedAt,
                        type = "run_failed",
                        title = formatBatchRunTitle(event.batchIndex, event.batchSize, event.goal, "失败"),
                        detail = event.error,
                        relatedRunId = event.runId
                    )
                }
            }

            is SubagentUiEvent.Cancelled -> {
                val finishedAt = System.currentTimeMillis()
                clearSubagentCancellationRequest(event.runId)
                upsertSubagentRun(event.runId) { run ->
                    (run ?: SubagentRunUi(
                        runId = event.runId,
                        status = "cancelled",
                        goal = event.goal,
                        templateId = event.templateId,
                        templateTitle = event.templateTitle,
                        model = event.model,
                        reasoningEffort = event.reasoningEffort,
                        allowedTools = event.allowedTools,
                        batchId = event.batchId,
                        batchLabel = event.batchLabel,
                        batchIndex = event.batchIndex,
                        batchSize = event.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        usageSummary = event.usageSummary
                    )).copy(
                        status = "cancelled",
                        goal = event.goal.ifBlank { run?.goal.orEmpty() },
                        templateId = event.templateId ?: run?.templateId,
                        templateTitle = event.templateTitle ?: run?.templateTitle,
                        model = event.model.ifBlank { run?.model.orEmpty() },
                        reasoningEffort = event.reasoningEffort ?: run?.reasoningEffort,
                        allowedTools = event.allowedTools.ifEmpty { run?.allowedTools ?: emptyList() },
                        batchId = event.batchId ?: run?.batchId,
                        batchLabel = event.batchLabel ?: run?.batchLabel,
                        batchIndex = event.batchIndex ?: run?.batchIndex,
                        batchSize = event.batchSize ?: run?.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        statusMessage = "已按用户请求终止。",
                        approvalRequestedAt = run?.approvalRequestedAt,
                        queuedAt = run?.queuedAt,
                        executionStartedAt = run?.executionStartedAt,
                        summarizingAt = run?.summarizingAt,
                        usageSummary = if (event.usageSummary.totalTokens > 0) {
                            event.usageSummary
                        } else {
                            run?.usageSummary ?: UsageSummarySnapshot()
                        },
                        finishedAt = finishedAt
                    )
                }
                updateSubagentMessage(event.runId, "子代理已终止")
                event.batchId?.let { batchId ->
                    appendSubagentBatchTimeline(
                        batchId = batchId,
                        timestamp = finishedAt,
                        type = "run_cancelled",
                        title = formatBatchRunTitle(event.batchIndex, event.batchSize, event.goal, "已终止"),
                        detail = "子任务被用户终止。",
                        relatedRunId = event.runId
                    )
                }
            }

            is SubagentUiEvent.Rejected -> {
                val finishedAt = System.currentTimeMillis()
                clearSubagentCancellationRequest(event.runId)
                upsertSubagentRun(
                    runId = event.runId,
                    timestamp = finishedAt
                ) { existing ->
                    (existing ?: SubagentRunUi(
                        runId = event.runId,
                        status = "rejected",
                        goal = event.goal,
                        templateId = event.templateId,
                        templateTitle = event.templateTitle,
                        model = event.model,
                        reasoningEffort = event.reasoningEffort,
                        allowedTools = event.allowedTools,
                        statusMessage = event.reason,
                        retryCount = event.retryCount,
                        sourceRunId = event.sourceRunId,
                        batchId = event.batchId,
                        batchLabel = event.batchLabel,
                        batchIndex = event.batchIndex,
                        batchSize = event.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        startedAt = finishedAt
                    )).copy(
                        status = "rejected",
                        goal = event.goal.ifBlank { existing?.goal.orEmpty() },
                        templateId = event.templateId ?: existing?.templateId,
                        templateTitle = event.templateTitle ?: existing?.templateTitle,
                        model = event.model.ifBlank { existing?.model.orEmpty() },
                        reasoningEffort = event.reasoningEffort ?: existing?.reasoningEffort,
                        allowedTools = event.allowedTools.ifEmpty { existing?.allowedTools ?: emptyList() },
                        statusMessage = event.reason,
                        error = event.reason,
                        retryCount = event.retryCount,
                        sourceRunId = event.sourceRunId,
                        batchId = event.batchId ?: existing?.batchId,
                        batchLabel = event.batchLabel ?: existing?.batchLabel,
                        batchIndex = event.batchIndex ?: existing?.batchIndex,
                        batchSize = event.batchSize ?: existing?.batchSize,
                        concurrencyLimit = maxConcurrentSubagentExecutions,
                        approvalRequestedAt = existing?.approvalRequestedAt ?: finishedAt,
                        finishedAt = finishedAt
                    )
                }
                updateSubagentMessage(event.runId, "子代理审批被拒绝")
                event.batchId?.let { batchId ->
                    appendSubagentBatchTimeline(
                        batchId = batchId,
                        timestamp = finishedAt,
                        type = "run_rejected",
                        title = formatBatchRunTitle(event.batchIndex, event.batchSize, event.goal, "已拒绝"),
                        detail = event.reason,
                        relatedRunId = event.runId
                    )
                }
            }
        }
        affectedBatchId?.let(::refreshSubagentBatchState)
        saveCurrentSession()
    }

    private fun formatBatchRunTitle(
        batchIndex: Int?,
        batchSize: Int?,
        goal: String,
        suffix: String
    ): String {
        val prefix = batchIndex?.let { index ->
            batchSize?.let { size -> "子任务 $index/$size" } ?: "子任务 $index"
        } ?: "子任务"
        return "$prefix $suffix"
    }

    private fun findSubagentBatchIdForRun(runId: String): String? {
        return _state.value.subagentRuns.firstOrNull { it.runId == runId }?.batchId
    }

    private fun upsertSubagentBatch(
        batchId: String,
        timestamp: Long = System.currentTimeMillis(),
        transform: (SubagentBatchUi?) -> SubagentBatchUi
    ) {
        val existing = _state.value.subagentBatches.firstOrNull { it.batchId == batchId }
        val updatedBatch = transform(existing).copy(
            startedAt = existing?.startedAt ?: timestamp
        )
        val updatedBatches = if (existing == null) {
            _state.value.subagentBatches + updatedBatch
        } else {
            _state.value.subagentBatches.map { batch ->
                if (batch.batchId == batchId) updatedBatch else batch
            }
        }
        _state.value = _state.value.copy(subagentBatches = updatedBatches)
    }

    private fun appendSubagentBatchTimeline(
        batchId: String,
        timestamp: Long = System.currentTimeMillis(),
        type: String,
        title: String,
        detail: String = "",
        relatedRunId: String? = null
    ) {
        upsertSubagentBatch(batchId = batchId, timestamp = timestamp) { existing ->
            if (existing == null) {
                return@upsertSubagentBatch SubagentBatchUi(
                    batchId = batchId,
                    parentGoal = "",
                    label = "",
                    status = "queued",
                    timeline = listOf(
                        SubagentTimelineEntryUi(
                            timestamp = timestamp,
                            type = type,
                            title = title,
                            detail = detail,
                            relatedRunId = relatedRunId
                        )
                    ),
                    startedAt = timestamp
                )
            }
            val alreadyExists = existing.timeline.any { entry ->
                entry.timestamp == timestamp &&
                    entry.type == type &&
                    entry.title == title &&
                    entry.detail == detail &&
                    entry.relatedRunId == relatedRunId
            }
            if (alreadyExists) {
                existing
            } else {
                existing.copy(
                    timeline = (
                        existing.timeline + SubagentTimelineEntryUi(
                            timestamp = timestamp,
                            type = type,
                            title = title,
                            detail = detail,
                            relatedRunId = relatedRunId
                        )
                        ).sortedBy { it.timestamp }.takeLast(40)
                )
            }
        }
    }

    private fun refreshBackgroundExecutionState(schedulerJobs: List<BackgroundJobUi> = subagentSchedulerJobs) {
        val schedulerJobsByRunId = schedulerJobs.associateBy { it.jobId }
        val currentRuns = _state.value.subagentRuns
        val updatedRuns = currentRuns.map { run ->
            val schedulerJob = schedulerJobsByRunId[run.runId]
            val assignedSlot = schedulerJob?.assignedSlot
            val queuePosition = schedulerJob?.queuePosition
            val updatedStatusMessage = buildSubagentQueueStatusMessage(
                status = run.status,
                retryCount = run.retryCount,
                queuePosition = queuePosition,
                assignedSlot = assignedSlot
            ) ?: run.statusMessage
            run.copy(
                queuePosition = queuePosition,
                assignedSlot = assignedSlot,
                concurrencyLimit = maxConcurrentSubagentExecutions,
                statusMessage = updatedStatusMessage
            )
        }
        if (updatedRuns != currentRuns) {
            _state.value = _state.value.copy(subagentRuns = updatedRuns)
        }
        updatedRuns.mapNotNull { it.batchId }
            .distinct()
            .forEach(::refreshSubagentBatchState)
    }

    private fun buildSubagentQueueStatusMessage(
        status: String,
        retryCount: Int,
        queuePosition: Int?,
        assignedSlot: Int?
    ): String? {
        return when (status) {
            "queued" -> {
                when {
                    queuePosition != null && retryCount > 0 ->
                        "第 $retryCount 次重试排队中，当前位于队列第 $queuePosition 位。"
                    queuePosition != null ->
                        "已进入子代理队列，当前位于第 $queuePosition 位。"
                    else -> null
                }
            }

            "running" -> {
                assignedSlot?.let { slot ->
                    if (retryCount > 0) {
                        "正在执行第 $retryCount 次重试，占用槽位 $slot/$maxConcurrentSubagentExecutions。"
                    } else {
                        "正在执行子代理任务，占用槽位 $slot/$maxConcurrentSubagentExecutions。"
                    }
                }
            }

            "summarizing" -> {
                assignedSlot?.let { slot ->
                    "执行已结束，正在整理摘要，占用槽位 $slot/$maxConcurrentSubagentExecutions。"
                }
            }

            else -> null
        }
    }

    private fun refreshSubagentBatchState(batchId: String) {
        val batch = _state.value.subagentBatches.firstOrNull { it.batchId == batchId } ?: return
        val runs = _state.value.subagentRuns
            .filter { it.batchId == batchId || it.runId in batch.runIds }
            .sortedWith(
                compareBy<SubagentRunUi> { it.batchIndex ?: Int.MAX_VALUE }
                    .thenBy { it.startedAt }
            )
        if (runs.isEmpty()) {
            updateSubagentBatchMessage(
                batchId = batchId,
                content = when (batch.status) {
                    "pending_approval" -> buildSubagentBatchApprovalMessage(
                        label = batch.label,
                        parentGoal = batch.parentGoal,
                        runIds = batch.runIds,
                        splitStrategyLabel = batch.splitStrategyLabel,
                        splitStrategyDetail = batch.splitStrategyDetail
                    )

                    "rejected" -> buildSubagentBatchRejectedMessage(
                        label = batch.label,
                        parentGoal = batch.parentGoal,
                        runIds = batch.runIds,
                        reason = batch.statusMessage.ifBlank { "子代理编排审批被拒绝。" },
                        splitStrategyLabel = batch.splitStrategyLabel,
                        splitStrategyDetail = batch.splitStrategyDetail
                    )

                    else -> buildSubagentBatchQueuedMessage(
                        label = batch.label,
                        parentGoal = batch.parentGoal,
                        runIds = batch.runIds,
                        splitStrategyLabel = batch.splitStrategyLabel,
                        splitStrategyDetail = batch.splitStrategyDetail
                    )
                }
            )
            return
        }

        val totalRuns = max(batch.runIds.size, runs.size)
        val completedCount = runs.count { it.status == "completed" }
        val failedCount = runs.count { it.status in setOf("failed", "cancelled", "rejected") }
        val activeCount = runs.count { it.status in SUBAGENT_ACTIVE_STATUSES }
        val terminalCount = runs.count { it.status in SUBAGENT_TERMINAL_STATUSES }
        val queuePosition = runs.mapNotNull { it.queuePosition }.minOrNull()
        val activeSlots = runs.mapNotNull { it.assignedSlot }.distinct().sorted()
        val queuedRuns = runs.count { it.queuePosition != null }
        val runningRuns = runs.count { it.assignedSlot != null }
        val firstRunStartedAt = runs.mapNotNull { it.executionStartedAt }.minOrNull()
        val lastRunFinishedAt = runs.mapNotNull { it.finishedAt }.maxOrNull()

        val status = when {
            terminalCount >= totalRuns && failedCount == 0 -> "completed"
            terminalCount >= totalRuns && completedCount > 0 -> "completed_with_failures"
            terminalCount >= totalRuns -> "failed"
            activeCount > 0 -> "running"
            else -> "queued"
        }
        val statusMessage = when {
            terminalCount >= totalRuns && failedCount == 0 ->
                "编排完成，$completedCount/$totalRuns 个子任务已成功返回。"
            terminalCount >= totalRuns && completedCount > 0 ->
                "编排完成，成功 $completedCount 个，未完成 $failedCount 个。"
            terminalCount >= totalRuns ->
                "编排完成，但 $totalRuns 个子任务都未成功。"
            activeCount > 0 ->
                buildString {
                    append("编排进行中，已结束 $terminalCount/$totalRuns，仍有 $activeCount 个子任务在处理。")
                    if (activeSlots.isNotEmpty()) {
                        append(" 当前占用槽位 ")
                        append(activeSlots.joinToString("/"))
                        append("。")
                    }
                    if (queuePosition != null) {
                        append(" 队列最前位置 ")
                        append(queuePosition)
                        append("。")
                    }
                }
            queuePosition != null ->
                "编排排队中，最前子任务位于队列第 $queuePosition 位。"
            queuedRuns > 0 ->
                "编排已创建，等待子任务依次启动。"
            runningRuns > 0 && activeSlots.isNotEmpty() ->
                "编排运行中，当前占用槽位 ${activeSlots.joinToString("/")}。"
            queuedRuns == 0 && runningRuns == 0 && terminalCount < totalRuns ->
                "编排等待中，尚未进入后台执行。"
            else ->
                "编排已创建，等待子任务依次启动。"
        }
        val summary = if (terminalCount >= totalRuns) {
            buildSubagentBatchSummary(batch = batch, runs = runs)
        } else {
            batch.summary
        }
        val mergedRunIds = (batch.runIds + runs.map { it.runId }).distinct()
        val finishedAt = if (terminalCount >= totalRuns) {
            runs.mapNotNull { it.finishedAt }.maxOrNull() ?: System.currentTimeMillis()
        } else {
            null
        }

        upsertSubagentBatch(batchId = batchId) { existing ->
            (existing ?: batch).copy(
                runIds = mergedRunIds,
                status = status,
                statusMessage = statusMessage,
                summary = summary,
                queuePosition = queuePosition,
                activeSlots = activeSlots,
                queuedRuns = queuedRuns,
                runningRuns = runningRuns,
                concurrencyLimit = maxConcurrentSubagentExecutions,
                firstRunStartedAt = firstRunStartedAt,
                lastRunFinishedAt = lastRunFinishedAt,
                finishedAt = finishedAt
            )
        }
        _state.value = applyParallelBatchWorkflowProgressTransition(_state.value)

        val updatedBatch = _state.value.subagentBatches.firstOrNull { it.batchId == batchId } ?: return
        updateSubagentBatchMessage(
            batchId = batchId,
            content = buildSubagentBatchMessage(batch = updatedBatch, runs = runs)
        )
    }

    private fun buildSubagentBatchQueuedMessage(
        label: String,
        parentGoal: String,
        runIds: List<String>,
        splitStrategyLabel: String,
        splitStrategyDetail: String
    ): String {
        return buildString {
            append("## 子代理编排已创建\n")
            append("- 批次: ").append(label).append("\n")
            append("- 主目标: ").append(parentGoal).append("\n")
            append("- 子任务数: ").append(runIds.size).append("\n")
            append("- 拆分策略: ").append(splitStrategyLabel).append("\n")
            append("- 拆分说明: ").append(splitStrategyDetail).append("\n")
            append("- 状态: 已进入后台队列")
        }
    }

    private fun buildSubagentBatchApprovalMessage(
        label: String,
        parentGoal: String,
        runIds: List<String>,
        splitStrategyLabel: String,
        splitStrategyDetail: String
    ): String {
        return buildString {
            append("## 子代理编排等待审批\n")
            append("- 批次: ").append(label).append("\n")
            append("- 主目标: ").append(parentGoal).append("\n")
            append("- 计划子任务数: ").append(runIds.size).append("\n")
            append("- 拆分策略: ").append(splitStrategyLabel).append("\n")
            append("- 拆分说明: ").append(splitStrategyDetail).append("\n")
            append("- 状态: 等待一次性提权审批")
        }
    }

    private fun buildSubagentBatchRejectedMessage(
        label: String,
        parentGoal: String,
        runIds: List<String>,
        reason: String,
        splitStrategyLabel: String,
        splitStrategyDetail: String
    ): String {
        return buildString {
            append("## 子代理编排已拒绝\n")
            append("- 批次: ").append(label).append("\n")
            append("- 主目标: ").append(parentGoal).append("\n")
            append("- 计划子任务数: ").append(runIds.size).append("\n")
            append("- 拆分策略: ").append(splitStrategyLabel).append("\n")
            append("- 拆分说明: ").append(splitStrategyDetail).append("\n")
            append("- 原因: ").append(reason)
        }
    }

    private fun buildSubagentBatchSummary(
        batch: SubagentBatchUi,
        runs: List<SubagentRunUi>
    ): String {
        val completedCount = runs.count { it.status == "completed" }
        val failedRuns = runs.filter { it.status in setOf("failed", "cancelled", "rejected") }
        return buildString {
            append("编排批次 ").append(batch.label).append(" 已结束。")
            append("成功 ").append(completedCount).append(" 个")
            if (failedRuns.isNotEmpty()) {
                append("，未成功 ").append(failedRuns.size).append(" 个")
            }
            append("。\n\n")
            runs.forEachIndexed { index, run ->
                append(index + 1)
                append(". [")
                append(run.status)
                append("] ")
                append(run.goal.ifBlank { "未命名子任务" })
                append(": ")
                append(
                    when {
                        run.summary.isNotBlank() -> run.summary.lineSequence().firstOrNull().orEmpty()
                        !run.error.isNullOrBlank() -> run.error
                        run.statusMessage.isNotBlank() -> run.statusMessage
                        else -> "暂无摘要"
                    }
                )
                append("\n")
            }
        }.trim()
    }

    private fun buildSubagentBatchMessage(
        batch: SubagentBatchUi,
        runs: List<SubagentRunUi>
    ): String {
        val completedCount = runs.count { it.status == "completed" }
        val failedCount = runs.count { it.status in setOf("failed", "cancelled", "rejected") }
        val activeCount = runs.count { it.status in SUBAGENT_ACTIVE_STATUSES }
        val metricLine = buildSubagentBatchMetricLine(batch)
        return buildString {
            append("## 子代理编排\n")
            append("- 批次: ").append(batch.label).append("\n")
            append("- 主目标: ").append(batch.parentGoal).append("\n")
            if (batch.splitStrategyLabel.isNotBlank()) {
                append("- 拆分策略: ").append(batch.splitStrategyLabel).append("\n")
            }
            if (batch.splitStrategyDetail.isNotBlank()) {
                append("- 拆分说明: ").append(batch.splitStrategyDetail).append("\n")
            }
            append("- 状态: ").append(batch.statusMessage).append("\n")
            append("- 进度: 成功 ").append(completedCount)
                .append(" / 异常 ").append(failedCount)
                .append(" / 处理中 ").append(activeCount)
                .append(" / 总计 ").append(batch.runIds.size.coerceAtLeast(runs.size))
                .append("\n")
            batch.queuePosition?.let {
                append("- 队列位置: 第 ").append(it).append(" 位\n")
            }
            if (batch.activeSlots.isNotEmpty()) {
                append("- 并发槽位: ")
                    .append(batch.activeSlots.joinToString(", ") { slot ->
                        batch.concurrencyLimit?.let { limit -> "$slot/$limit" } ?: slot.toString()
                    })
                    .append("\n")
            }
            metricLine?.let {
                append("- 调度指标: ").append(it).append("\n")
            }
            if (batch.summary.isNotBlank()) {
                append("\n### 汇总结论\n")
                append(batch.summary)
            }
        }.trim()
    }

    private fun buildSubagentBatchMetricLine(
        batch: SubagentBatchUi,
        now: Long = System.currentTimeMillis()
    ): String? {
        val metrics = listOfNotNull(
            calculateBatchApprovalWaitMillis(batch, now)?.let { "审批 ${formatDurationShort(it)}" },
            calculateBatchQueueWaitMillis(batch, now)?.let { "排队 ${formatDurationShort(it)}" },
            calculateBatchExecutionMillis(batch, now)?.let { "执行 ${formatDurationShort(it)}" },
            calculateBatchTotalMillis(batch, now)?.let { "总计 ${formatDurationShort(it)}" }
        )
        return metrics.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun calculateRunApprovalWaitMillis(
        run: SubagentRunUi,
        now: Long = System.currentTimeMillis()
    ): Long? {
        val startedAt = run.approvalRequestedAt ?: return null
        val endAt = run.queuedAt
            ?: if (run.status == "pending_approval") now else run.finishedAt
            ?: return null
        return (endAt - startedAt).coerceAtLeast(0L)
    }

    private fun calculateRunQueueWaitMillis(
        run: SubagentRunUi,
        now: Long = System.currentTimeMillis()
    ): Long? {
        val queuedAt = run.queuedAt ?: return null
        val endAt = run.executionStartedAt ?: if (run.status == "queued") now else return null
        return (endAt - queuedAt).coerceAtLeast(0L)
    }

    private fun calculateRunExecutionMillis(
        run: SubagentRunUi,
        now: Long = System.currentTimeMillis()
    ): Long? {
        val startedAt = run.executionStartedAt ?: return null
        val endAt = run.finishedAt ?: now
        return (endAt - startedAt).coerceAtLeast(0L)
    }

    private fun calculateRunTotalMillis(
        run: SubagentRunUi,
        now: Long = System.currentTimeMillis()
    ): Long? {
        val endAt = run.finishedAt ?: now
        return (endAt - run.startedAt).coerceAtLeast(0L)
    }

    private fun calculateBatchApprovalWaitMillis(
        batch: SubagentBatchUi,
        now: Long = System.currentTimeMillis()
    ): Long? {
        val startedAt = batch.approvalRequestedAt ?: return null
        val endAt = batch.queuedAt
            ?: if (batch.status == "pending_approval") now else batch.finishedAt
            ?: return null
        return (endAt - startedAt).coerceAtLeast(0L)
    }

    private fun calculateBatchQueueWaitMillis(
        batch: SubagentBatchUi,
        now: Long = System.currentTimeMillis()
    ): Long? {
        val queuedAt = batch.queuedAt ?: return null
        val endAt = batch.firstRunStartedAt ?: if (batch.status == "queued") now else return null
        return (endAt - queuedAt).coerceAtLeast(0L)
    }

    private fun calculateBatchExecutionMillis(
        batch: SubagentBatchUi,
        now: Long = System.currentTimeMillis()
    ): Long? {
        val startedAt = batch.firstRunStartedAt ?: return null
        val endAt = batch.finishedAt ?: now
        return (endAt - startedAt).coerceAtLeast(0L)
    }

    private fun calculateBatchTotalMillis(
        batch: SubagentBatchUi,
        now: Long = System.currentTimeMillis()
    ): Long? {
        val endAt = batch.finishedAt ?: now
        return (endAt - batch.startedAt).coerceAtLeast(0L)
    }

    private fun formatDurationShort(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).coerceAtLeast(0L)
        return when {
            totalSeconds < 60 -> "${totalSeconds}s"
            totalSeconds < 3600 -> {
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                if (seconds == 0L) "${minutes}m" else "${minutes}m ${seconds}s"
            }
            else -> {
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
            }
        }
    }

    private fun updateSubagentBatchMessage(batchId: String, content: String) {
        val targetMessage = _state.value.messages.lastOrNull { it.subagentBatchId == batchId } ?: return
        updateMessage(targetMessage.id) { message ->
            message.copy(content = content)
        }
    }

    private fun ensureSubagentBatchMessage(batchId: String, content: String, timestamp: Long) {
        val existing = _state.value.messages.lastOrNull { it.subagentBatchId == batchId }
        if (existing == null) {
            appendMessage(
                ChatMessageUi(
                    id = nextId(),
                    role = "subagent",
                    content = content,
                    subagentBatchId = batchId,
                    timestamp = timestamp
                )
            )
        } else {
            updateSubagentBatchMessage(batchId, content)
        }
    }

    private fun updateSubagentMessage(runId: String, fallbackContent: String) {
        val targetMessage = _state.value.messages.lastOrNull { it.subagentRunId == runId } ?: return
        updateMessage(targetMessage.id) { message ->
            message.copy(content = fallbackContent)
        }
    }

    private fun ensureSubagentMessage(runId: String, content: String, timestamp: Long) {
        val existing = _state.value.messages.lastOrNull { it.subagentRunId == runId }
        if (existing == null) {
            appendMessage(
                ChatMessageUi(
                    id = nextId(),
                    role = "subagent",
                    content = content,
                    subagentRunId = runId,
                    timestamp = timestamp
                )
            )
        } else {
            updateSubagentMessage(runId, content)
        }
    }

    private fun upsertSubagentRun(
        runId: String,
        timestamp: Long = System.currentTimeMillis(),
        transform: (SubagentRunUi?) -> SubagentRunUi
    ) {
        val existing = _state.value.subagentRuns.firstOrNull { it.runId == runId }
        val updatedRun = transform(existing).copy(
            startedAt = existing?.startedAt ?: timestamp
        )
        val updatedRuns = if (existing == null) {
            _state.value.subagentRuns + updatedRun
        } else {
            _state.value.subagentRuns.map { run ->
                if (run.runId == runId) updatedRun else run
            }
        }
        _state.value = _state.value.copy(subagentRuns = updatedRuns)
    }

    fun cancelSubagentRun(runId: String): Boolean {
        val target = _state.value.subagentRuns.firstOrNull { it.runId == runId } ?: return false
        if (target.status !in setOf("pending_approval", "queued", "running", "summarizing")) return false
        if (subagentBackgroundJobsManager.cancelQueued(runId)) {
            synchronized(cancelledSubagentRunIds) {
                cancelledSubagentRunIds.add(runId)
            }
            val finishedAt = System.currentTimeMillis()
            upsertSubagentRun(runId) { run ->
                (run ?: target).copy(
                    status = "cancelled",
                    statusMessage = "已在启动前取消后台子代理。",
                    finishedAt = finishedAt
                )
            }
            updateSubagentMessage(runId, "子代理已终止")
            target.batchId?.let(::refreshSubagentBatchState)
            saveCurrentSession()
            return true
        }
        synchronized(cancelledSubagentRunIds) {
            cancelledSubagentRunIds.add(runId)
        }
        upsertSubagentRun(runId) { run ->
            run?.copy(
                status = "cancelling",
                statusMessage = "已请求终止，等待当前步骤结束。"
            ) ?: target.copy(
                status = "cancelling",
                statusMessage = "已请求终止，等待当前步骤结束。"
            )
        }
        updateSubagentMessage(runId, "子代理终止中")
        target.batchId?.let(::refreshSubagentBatchState)
        saveCurrentSession()
        return true
    }

    suspend fun retrySubagentRun(runId: String): Boolean {
        val originalRun = _state.value.subagentRuns.firstOrNull { it.runId == runId } ?: return false
        if (_state.value.isProcessing) return false
        if (originalRun.status !in setOf("failed", "cancelled", "completed", "rejected")) return false

        val globalConfig = configRepository.getConfig()
        val config = resolveEffectiveSessionConfig(globalConfig = globalConfig)
        lastSessionConfig = config
        val apiKey = config.getActiveApiKey().trim()
        if (apiKey.isBlank()) {
            _state.value = _state.value.copy(error = "⚠️ 未配置 API Key。请先到设置页完成模型配置。")
            return false
        }
        val provider = ProviderRegistry.getActiveProvider(config.activeProviderId)
        val tool = createSubagentTool(provider, config)
        val args = buildJsonObject {
            put("goal", originalRun.goal)
            put("model", originalRun.model)
            originalRun.templateId?.let { put("templateId", it) }
            originalRun.reasoningEffort?.takeIf { it.isNotBlank() }?.let {
                put("reasoningEffort", it)
            }
            put("enableWebSearch", originalRun.allowedTools.any { it == "web_search" || it == "web_fetch" })
            put("allowWriteAccess", originalRun.allowedTools.any { it.startsWith("file(") && "write" in it })
            put("allowCodeEdits", originalRun.allowedTools.any { it == "code_edit" })
            put("allowShell", originalRun.allowedTools.any { it == "shell" })
            put("background", true)
            put("retryCount", originalRun.retryCount + 1)
            put("sourceRunId", originalRun.sourceRunId ?: originalRun.runId)
        }.toString()
        _state.value = _state.value.copy(error = null)
        tool.execute(args)
        saveCurrentSession(config)
        return true
    }

    private fun resolveExecutionConfig(
        baseConfig: ProviderConfig,
        goal: String,
        mentionedFiles: List<FileMentionUi> = emptyList()
    ): ProviderConfig {
        return ExecutionProfileDecider.resolveExecutionConfig(
            baseConfig = baseConfig,
            goal = goal,
            mentionedFileCount = mentionedFiles.size,
            matchedSkillPreferredModel = null
        )
    }

    private fun shouldPreferLatestOpenAiProfile(config: ProviderConfig): Boolean {
        return ExecutionProfileDecider.shouldPreferLatestOpenAiProfile(config)
    }

    private fun buildExecutionProfileUserContext(
        goal: String,
        baseConfig: ProviderConfig,
        executionConfig: ProviderConfig
    ): String? {
        val discoveryRequest = isAutoDiscoveryRequest(goal)
        val modelChanged = executionConfig.getActiveModel() != baseConfig.getActiveModel()
        val reasoningChanged = executionConfig.getActiveReasoningEffort() != baseConfig.getActiveReasoningEffort()
        val webEnabledForThisRun = setOf("web_search", "web_fetch")
            .all { executionConfig.isBuiltinToolEnabled(it) && !baseConfig.isBuiltinToolEnabled(it) }
        if (!modelChanged && !reasoningChanged && !discoveryRequest && !webEnabledForThisRun) {
            return null
        }
        return buildString {
            appendLine("本轮执行设置：")
            if (isComplexTask(goal)) {
                appendLine("Reason: complex task detected, prefer a stronger execution profile.")
            }
            if (webEnabledForThisRun) {
                appendLine("Web Tools: temporarily enabled for this request.")
            }
            appendLine("Model: ${executionConfig.getActiveModel()}")
            executionConfig.getActiveReasoningEffort()
                ?.takeIf { it.isNotBlank() }
                ?.let { appendLine("Reasoning Effort: $it") }
            if (discoveryRequest) {
                appendLine()
                appendLine("Discovery Mode:")
                appendLine("- The user is asking to install, import, connect, or load an external MCP or Skill.")
                appendLine("- If web tools are available, search official docs or the canonical repository first.")
                appendLine("- Prefer the official install/config format, not a paraphrased summary.")
                appendLine("- When you output an MCP draft, wrap it in ```murong-mcp-draft fenced block.")
                appendLine("- When you output a Skill draft, wrap it in ```murong-skill-draft fenced block.")
                appendLine("- Do not expect the app to auto-import or auto-connect drafts from normal prose; drafts are reviewed separately.")
                appendLine("- If the source requires manual secrets, explicitly leave placeholders instead of fabricating values.")
            }
        }.trim()
    }

    private fun buildExecutionProfileToast(
        baseConfig: ProviderConfig,
        executionConfig: ProviderConfig
    ): String? {
        return ExecutionProfileDecider.buildExecutionProfileToast(baseConfig, executionConfig)
    }

    private fun buildExecutionProfileLabel(
        providerId: String,
        model: String,
        reasoning: String?
    ): String {
        return ExecutionProfileDecider.buildExecutionProfileLabel(providerId, model, reasoning)
    }

    private fun isAutoDiscoveryRequest(goal: String): Boolean {
        return ExecutionProfileDecider.isAutoDiscoveryRequest(goal)
    }

    private fun isComplexTask(goal: String, mentionedFiles: List<FileMentionUi> = emptyList()): Boolean {
        return ExecutionProfileDecider.isComplexTask(goal, mentionedFiles.size)
    }

    private fun isSubagentCancellationRequested(runId: String): Boolean {
        return synchronized(cancelledSubagentRunIds) {
            runId in cancelledSubagentRunIds
        }
    }

    private fun clearSubagentCancellationRequest(runId: String) {
        synchronized(cancelledSubagentRunIds) {
            cancelledSubagentRunIds.remove(runId)
        }
    }

    private suspend fun scheduleBackgroundShellExecution(
        request: BackgroundJobRequest,
        executeNow: suspend () -> BackgroundJobCompletion
    ): String {
        return backgroundJobsManager.schedule(
            request = request,
            executeNow = executeNow
        )
    }

    private fun appendBackgroundJobCompletionNotice(job: BackgroundJobUi) {
        val statusLabel = when (job.status) {
            "completed" -> "已完成"
            "failed" -> "失败"
            else -> "已结束"
        }
        val preview = job.resultPreview
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { truncateBackgroundJobPreview(it, 240) }
        appendSystemMessage(
            content = buildString {
                append("后台任务$statusLabel：")
                append(job.title.ifBlank { job.toolName })
                job.summary.trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { summary ->
                        append(" · ")
                        append(summary)
                    }
                preview?.let {
                    append("\n")
                    append(it)
                }
            },
            source = "background_job_completion"
        )
    }

    private fun truncateBackgroundJobPreview(text: String, maxChars: Int): String {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.length <= maxChars) return normalized
        return normalized.take((maxChars - 3).coerceAtLeast(1)).trimEnd() + "..."
    }

    private suspend fun scheduleBackgroundSubagentExecution(
        runId: String,
        executeNow: suspend () -> String
    ): String {
        return subagentBackgroundJobsManager.schedule(
            request = BackgroundJobRequest(
                jobIdOverride = runId,
                toolName = "subagent",
                title = "子代理后台任务",
                summary = runId
            )
        ) {
            val output = executeNow()
            BackgroundJobCompletion(
                status = when {
                    output.startsWith("Subagent cancelled:", ignoreCase = true) -> "cancelled"
                    output.startsWith("Subagent failed:", ignoreCase = true) -> "failed"
                    output.startsWith("Subagent rejected:", ignoreCase = true) -> "rejected"
                    else -> "completed"
                },
                statusMessage = when {
                    output.startsWith("Subagent cancelled:", ignoreCase = true) -> "子代理后台任务已取消。"
                    output.startsWith("Subagent failed:", ignoreCase = true) -> "子代理后台任务执行失败。"
                    output.startsWith("Subagent rejected:", ignoreCase = true) -> "子代理后台任务被拒绝。"
                    else -> "子代理后台任务执行完成。"
                }
            )
        }
    }

    private fun buildCompressionContext(state: SessionState = _state.value): String? {
        val compression = state.compressionSnapshot?.takeIf { it.active } ?: return null
        return buildString {
            append("Compression Snapshot:\n")
            append(compression.summary.trim())
            append("\n\n")
            append("Use this with the recent uncompressed messages. If details are missing, ask the user instead of inventing history.")
        }.trim()
    }

    private fun buildCurrentProjectContext(): String? {
        val state = _state.value
        val projectPath = normalizeProjectPath(state.projectPath)
        val scopePath = activeProjectScopePath(
            activeScopePath = state.activeProjectScopePath,
            projectPath = state.projectPath
        )
        val remoteTaskRepositoryLabel = state.remoteTaskRepositoryLabel
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: if (
                !state.remoteTaskRepositoryOwner.isNullOrBlank() &&
                !state.remoteTaskRepositoryName.isNullOrBlank()
            ) {
                "${state.remoteTaskRepositoryOwner}/${state.remoteTaskRepositoryName}"
            } else {
                null
            }
        if (projectPath == null && remoteTaskRepositoryLabel == null) return null
        val mountedKnowledge = state.projectKnowledgePaths
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val activeProjectRules = state.projectRules
            .filter { it.enabled }
            .mapNotNull { rule ->
                val content = rule.content.trim()
                if (content.isBlank()) return@mapNotNull null
                rule.title.trim().ifBlank { "unnamed" } to content
            }
        val activeProjectMemories = state.projectMemories
            .filter { it.enabled }
            .count { memory -> memory.content.trim().isNotBlank() }
        return buildString {
            appendLine("Project Context:")
            scopePath?.let {
                appendLine("Active scope: $it")
            }
            if (remoteTaskRepositoryLabel != null) {
                appendLine("Primary target: remote task repository $remoteTaskRepositoryLabel")
                appendLine(
                    if (state.remoteTaskRepositoryEditable) {
                        "This repository is the current editable task repository in the Project page."
                    } else {
                        "This repository is visible in the Project page, but write actions may still need explicit confirmation."
                    }
                )
                appendLine("When the user asks about this repository, prefer enabled GitHub/MCP remote tools instead of assuming local file access.")
                appendLine("Do not substitute similarly named local directories for this remote repository unless the user explicitly switches back to a local project.")
                appendLine("While a remote task repository is active, some local tools have restricted availability:")
                appendLine("- shell: still available (risk-controlled via approval)")
                appendLine("- file(read/list/exists): still available for local project inspection")
                appendLine("- file(write/delete): restricted; use task_repo_* for remote repository file edits")
                appendLine("- code_search: still available for local code reference")
                appendLine("- code_edit: restricted; use task_repo_* for remote repository edits instead")
                appendLine("Prefer task_repo_* tool aliases for remote repository operations.")
                appendLine("For the current task repository, prefer tool aliases task_repo_list_dir, task_repo_list_branches, task_repo_create_branch, task_repo_create_pr, task_repo_close_pr, task_repo_delete_branch, task_repo_search_code, task_repo_read_file, task_repo_search_replace, task_repo_apply_patch, task_repo_update_file, task_repo_delete_file, and task_repo_commit_files before generic mcp_* tools.")
                appendLine("If the exact remote path is still unclear, use task_repo_list_dir first to browse the repository structure before reading or editing files.")
                appendLine("Remote task_repo_read_file may auto-window large files; if you need a later section, continue with explicit startLine/endLine instead of assuming the whole file is already in context.")
                appendLine("If the edit is a small, exact replacement in one remote file, prefer task_repo_search_replace before task_repo_update_file.")
                appendLine("If the edit needs several exact hunks or touches a large remote file without replacing the whole file, prefer task_repo_apply_patch; it can use structured hunks or patch_text, and when the same search text appears multiple times, include context_before/context_after and, if you know the rough location, line_hint to lock the intended hunk.")
                appendLine("If an existing remote file is very large, avoid whole-file task_repo_update_file or task_repo_commit_files overwrites; read the relevant window first and prefer task_repo_search_replace.")
                appendLine("If branch names are unclear before writing or batch committing, use task_repo_list_branches first.")
                appendLine("If the user wants to isolate changes before editing, use task_repo_create_branch first and then commit to that branch.")
                appendLine("If the user wants to open a Pull Request after remote changes are committed, use task_repo_create_pr.")
                appendLine("If a test PR or branch should be cleaned up after verification, use task_repo_close_pr and task_repo_delete_branch to restore the repository state.")
                appendLine("If the user asks to delete a remote file, use task_repo_delete_file. Do not simulate deletion by writing an empty file.")
                appendLine("If the user wants one commit containing multiple file edits, use task_repo_commit_files instead of multiple single-file submissions.")
                appendLine("Before claiming that a plan step, verification step, or critical execution step is complete, call complete_step with real tool evidence instead of only stating it in natural language.")
            }
            if (projectPath != null) {
                if (remoteTaskRepositoryLabel != null) {
                    appendLine()
                }
                appendLine("Local root: $projectPath")
                appendLine("Only use this as the working directory when the user is explicitly talking about the local project.")
                appendLine("Prefer paths relative to this root for local-project work only.")
            }
            if (mountedKnowledge.isNotEmpty()) {
                appendLine()
                appendLine("Mounted knowledge:")
                mountedKnowledge.forEach { path ->
                    appendLine("- $path")
                }
            }
            if (activeProjectRules.isNotEmpty()) {
                appendLine()
                appendLine("Active Project Rules:")
                activeProjectRules.forEach { (title, content) ->
                    appendLine("- Rule: $title")
                    appendLine("  $content")
                }
            }
            if (activeProjectMemories > 0) {
                appendLine()
                appendLine("Legacy Project Memories Detected:")
                appendLine("- $activeProjectMemories legacy config memories remain in this project scope.")
                appendLine("- Their contents are not injected into the project context anymore.")
                appendLine("- Use memory_list / memory_search / memory_read when you need durable or bridged memory content.")
                appendLine("- If the user explicitly wants to retire old config memories, use migrate_legacy_memories.")
            }
            if (scopePath != null) {
                appendLine()
                appendLine("Project rule/memory/skill management:")
                appendLine("- If the user explicitly asks to save a reusable project rule, use create_project_rule.")
                appendLine("- If the user explicitly asks to save a reusable project memory, prefer remember_memory with scope=project; create_project_memory remains a compatibility path.")
                appendLine("- If the user explicitly asks to delete a durable project memory, use forget_memory.")
                appendLine("- If the user explicitly asks to migrate old legacy project memories into durable memory, use migrate_legacy_memories.")
                appendLine("- If the user explicitly asks to save a reusable project skill, use create_project_skill.")
                appendLine("- Do not auto-create or auto-extract project rules, memories, or skills from ordinary conversation.")
            }
        }.trim()
    }

    private fun currentProjectScopeLabel(): String {
        return activeProjectScopePath(
            activeScopePath = _state.value.activeProjectScopePath,
            projectPath = _state.value.projectPath
        ) ?: "当前项目作用域"
    }

    private fun currentRemoteTaskRepositoryTarget(): RemoteTaskRepositoryTarget? {
        val state = _state.value
        val owner = state.remoteTaskRepositoryOwner?.trim().orEmpty()
        val repo = state.remoteTaskRepositoryName?.trim().orEmpty()
        if (owner.isBlank() || repo.isBlank()) return null
        return RemoteTaskRepositoryTarget(
            owner = owner,
            repo = repo,
            label = state.remoteTaskRepositoryLabel?.trim().takeUnless { it.isNullOrBlank() } ?: "$owner/$repo"
        )
    }

    private fun shouldExposeLocalProjectTools(): Boolean {
        return shouldExposeLocalProjectTools(_state.value)
    }

    private fun shouldExposeLocalShellTool(): Boolean {
        return shouldExposeLocalShellTool(_state.value)
    }

    private fun shouldExposeLocalFileReadTool(): Boolean {
        return shouldExposeLocalFileReadTool(_state.value)
    }

    private fun shouldExposeLocalFileWriteTool(): Boolean {
        return shouldExposeLocalFileWriteTool(_state.value)
    }

    private fun shouldExposeLocalCodeSearchTool(): Boolean {
        return shouldExposeLocalCodeSearchTool(_state.value)
    }

    private fun shouldExposeLocalCodeEditTool(): Boolean {
        return shouldExposeLocalCodeEditTool(_state.value)
    }

    private fun buildRecentToolExecutionReceipts(): List<ToolExecutionReceipt> {
        return _state.value.recentToolCalls.map { record ->
            ToolExecutionReceipt(
                toolName = record.toolName,
                args = record.args,
                result = record.result,
                isSuccess = record.isSuccess,
                structuredPayload = record.structuredPayload,
                timestamp = record.timestamp
            )
        }
    }

    private fun buildPersistentFinalReadinessReceipt(
        state: SessionState,
        executionGoal: String
    ): FinalReadinessReceipt? {
        return buildCanonicalWorkflowReadinessReceipt(
            canonicalPlan = state.canonicalWorkflowPlan,
            executionGoal = executionGoal
        )
    }

    private fun buildWorkflowExecutionSnapshot(): WorkflowExecutionSnapshot? {
        val plan = _state.value.pendingWorkflowPlan ?: _state.value.canonicalWorkflowPlan ?: return null
        return WorkflowExecutionSnapshot(
            steps = plan.steps,
            currentStepIndex = plan.currentStepIndex,
            status = plan.status.name
        )
    }

    private fun markWorkflowStepCompleted(completion: WorkflowStepCompletion) {
        val currentPlan = _state.value.pendingWorkflowPlan ?: _state.value.canonicalWorkflowPlan ?: return
        if (currentPlan.steps.isEmpty()) return
        val updatedPlan = normalizeWorkflowPlan(
            resolveWorkflowPlanAfterStepCompletion(
                currentPlan = currentPlan,
                completion = completion
            )
        )
        _state.value = applyWorkflowPlanProgressTransition(
            state = _state.value,
            updatedPlan = updatedPlan
        )
        saveCurrentSession()
    }

    private fun buildReadOnlyPlanModeToolBlockMessage(
        toolName: String,
        args: String
    ): String? {
        return when {
            toolName == "shell" ->
                "Blocked by planning mode: shell is disabled until the pending plan/clarification is resolved."
            toolName == "code_edit" ->
                "Blocked by planning mode: code_edit is disabled until the pending plan/clarification is resolved."
            toolName == "file" && isWriteLikeFileToolOperation(args) ->
                "Blocked by planning mode: file write/delete/chmod operations are disabled until the pending plan/clarification is resolved."
            toolName in READ_ONLY_PLAN_MODE_BLOCKED_TOOL_NAMES ->
                "Blocked by planning mode: `$toolName` is a write-capable tool and cannot run until the pending plan/clarification is resolved."
            else -> null
        }
    }

    private fun isWriteLikeFileToolOperation(args: String): Boolean {
        return try {
            val operation = Json.parseToJsonElement(args)
                .jsonObject["operation"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.lowercase()
            operation in WRITE_LIKE_FILE_OPERATIONS
        } catch (_: Exception) {
            false
        }
    }

    private fun buildSessionGoalContext(): String? {
        val sessionGoal = _state.value.sessionGoal?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return buildString {
            appendLine("Current Session Goal:")
            appendLine(sessionGoal)
            append("Keep replies and execution aligned with this goal unless the user clearly changes or clears it.")
        }.trim()
    }

    private fun buildProjectSkillsContext(): String? {
        return lastSessionConfig.buildSkillsInstruction(
            skills = _state.value.projectSkills,
            heading = "Active Project Skills"
        )
    }

    private fun buildEnabledMcpToolsContext(): String? {
        val availableTools = mcpRegistry?.getMcpTools()
            .orEmpty()
            .filter { lastSessionConfig.isMcpToolEnabled(it.name) }
            .sortedBy { it.name }
        if (availableTools.isEmpty()) return null
        val githubTools = availableTools.filter(::isGitHubMcpTool)
        return buildString {
            appendLine("Enabled MCP Tools:")
            appendLine("Main agent can use these tools. Subagents may inherit tools referenced by a Skill's Allowed Tools list.")
            appendLine("Use exact tool names when delegating.")
            if (githubTools.isNotEmpty()) {
                appendLine()
                appendLine("GitHub MCP:")
                appendLine("- Prefer GitHub MCP for repository, issue, pull request, and remote file operations.")
                appendLine("- Write actions still require explicit user approval.")
            }
            appendLine()
            availableTools.forEach { tool ->
                append("- ")
                append(tool.name)
                mcpToolCapabilityHint(tool)?.let {
                    append(" [")
                    append(it)
                    append("]")
                }
                val description = tool.description
                    .substringAfter("] ", tool.description)
                    .trim()
                    .takeIf { it.isNotBlank() }
                if (description != null) {
                    append(" - ")
                    append(description)
                }
                appendLine()
            }
        }.trim()
    }

    private fun isGitHubMcpTool(tool: Tool): Boolean {
        val normalizedName = tool.name.lowercase(Locale.ROOT)
        val normalizedDescription = tool.description.lowercase(Locale.ROOT)
        return normalizedName.contains("github") || normalizedDescription.contains("github")
    }

    private fun mcpToolCapabilityHint(tool: Tool): String? {
        if (!isGitHubMcpTool(tool)) return null
        val normalizedName = tool.name.removePrefix("mcp_").lowercase(Locale.ROOT)
        return if (isGitHubWriteToolName(normalizedName)) {
            "GitHub 写操作，需审批"
        } else {
            "GitHub 只读"
        }
    }

    private fun isGitHubWriteToolName(toolName: String): Boolean {
        val writePrefixes = listOf(
            "create_",
            "update_",
            "delete_",
            "merge_",
            "push_",
            "fork_",
            "add_"
        )
        if (writePrefixes.any { toolName.startsWith(it) }) return true
        return toolName in setOf(
            "create_branch",
            "create_repository",
            "create_issue",
            "create_pull_request",
            "create_pull_request_review",
            "create_or_update_file",
            "push_files",
            "merge_pull_request",
            "update_pull_request_branch",
            "update_issue",
            "add_issue_comment",
            "fork_repository"
        )
    }

    private fun buildStableAuxiliarySystemMessages(
        instruction: String,
        compressionContext: String?
    ): List<ChatMessage> {
        return buildList {
            add(ChatMessage(role = "system", content = lastSessionConfig.buildEffectiveSystemPrompt()))
            compressionContext?.takeIf { it.isNotBlank() }?.let {
                add(ChatMessage(role = "system", content = it))
            }
            buildCurrentProjectContext()?.let {
                add(ChatMessage(role = "system", content = it))
            }
            buildProjectSkillsContext()?.let {
                add(ChatMessage(role = "system", content = it))
            }
            buildEnabledMcpToolsContext()?.let {
                add(ChatMessage(role = "system", content = it))
            }
            add(ChatMessage(role = "system", content = instruction))
        }
    }

    private fun buildTurnScopedAuxiliaryUserContext(
        mentionedFiles: List<FileMentionUi>,
        extraContext: String? = null
    ): String? {
        return buildTurnScopedAuxiliaryUserContext(
            recentSessionHistoryClue = _state.value.recentSessionHistoryClue,
            mentionedFilesContext = buildMentionedFilesContext(mentionedFiles),
            extraContext = extraContext
        )
    }

    private fun buildWorkflowPlanPrompt(
        goal: String,
        history: List<ChatMessage>,
        compressionContext: String?,
        mentionedFiles: List<FileMentionUi>
    ): List<ChatMessage> {
        val planInstruction = """
            You are preparing an execution plan for a coding assistant.
            Return plain text in Chinese with this exact structure:
            SUMMARY: 一句话总结
            STEPS:
            1. 第一步
            2. 第二步
            3. 第三步

            Rules:
            - Keep the plan concise and actionable.
            - Prefer one continuous workflow run when possible.
            - Mention user interruption points only when truly necessary.
            - Do not include markdown code fences.
        """.trimIndent()

        return buildList {
            addAll(buildStableAuxiliarySystemMessages(planInstruction, compressionContext))
            addAll(history.takeLast(12))
            add(
                ChatMessage(
                    role = "user",
                    content = buildString {
                        buildTurnScopedAuxiliaryUserContext(mentionedFiles)?.let {
                            appendLine(it)
                            appendLine()
                        }
                        append("请先为这个任务生成一份简短执行计划：\n$goal")
                    }
                )
            )
        }
    }

    private fun buildAutoRoutePrompt(
        goal: String,
        history: List<ChatMessage>,
        compressionContext: String?,
        mentionedFiles: List<FileMentionUi>
    ): List<ChatMessage> {
        val routerInstruction = """
            You are choosing the best next interaction mode for a coding assistant.
            Return plain text with exactly this structure:
            ACTION: DIRECT|PLAN|CLARIFY
            REASON: one short Chinese sentence

            Decision rules:
            - Choose DIRECT when the task is clear and can be executed immediately.
            - Choose PLAN when the task is long, complex, multi-step, or benefits from an explicit execution plan before running.
            - Choose CLARIFY when key information is missing and execution would be risky or likely wrong without one user answer.
            - Prefer PLAN over DIRECT for complex work, and prefer CLARIFY only when information is truly blocking.
            - Do not add any extra text.
        """.trimIndent()

        return buildList {
            addAll(buildStableAuxiliarySystemMessages(routerInstruction, compressionContext))
            addAll(history.takeLast(10))
            add(
                ChatMessage(
                    role = "user",
                    content = buildString {
                        buildTurnScopedAuxiliaryUserContext(mentionedFiles)?.let {
                            appendLine(it)
                            appendLine()
                        }
                        append("请判断这个输入最适合直接执行、先出计划，还是先提一个澄清问题：\n$goal")
                    }
                )
            )
        }
    }

    private fun parseAutoRouteDecision(raw: String): AutoRouteDecisionUi {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val actionText = lines.firstOrNull { it.startsWith("ACTION:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.uppercase()
            ?: throw IllegalArgumentException("自动分流返回格式不完整")
        val reason = lines.firstOrNull { it.startsWith("REASON:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "按当前任务特征自动选择最合适的执行路径。"
        val action = when (actionText) {
            "PLAN" -> AutoRouteAction.PLAN
            "CLARIFY" -> AutoRouteAction.CLARIFY
            "DIRECT" -> AutoRouteAction.DIRECT
            else -> throw IllegalArgumentException("自动分流返回了未知动作: $actionText")
        }
        return AutoRouteDecisionUi(
            action = action,
            reason = reason
        )
    }

    private fun parseWorkflowPlan(
        goal: String,
        rawPlan: String,
        mentionedFiles: List<FileMentionUi>,
        recentSessionHistoryClue: SessionHistoryReferenceClueUi?
    ): WorkflowPlanUi {
        val normalized = rawPlan.trim()
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotBlank() }
        val summary = lines.firstOrNull { it.startsWith("SUMMARY:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: lines.firstOrNull { !it.startsWith("STEPS", ignoreCase = true) && !it.matches(Regex("""^\d+[.)].*""")) }
            ?: "按当前目标生成执行计划"
        val steps = lines.mapNotNull { line ->
            when {
                line.matches(Regex("""^\d+[.)]\s*.+""")) -> line.replace(Regex("""^\d+[.)]\s*"""), "").trim()
                line.startsWith("- ") -> line.removePrefix("- ").trim()
                line.startsWith("* ") -> line.removePrefix("* ").trim()
                else -> null
            }
        }.filter { it.isNotBlank() }
            .distinct()
            .take(6)

        val normalizedSteps = if (steps.isNotEmpty()) steps else buildDefaultWorkflowPlanSteps()
        return normalizeWorkflowPlan(
            WorkflowPlanUi(
                goal = goal,
                summary = summary,
                steps = normalizedSteps,
                stageLabels = buildDefaultWorkflowStages(normalizedSteps),
                nextStepHint = normalizedSteps.firstOrNull().orEmpty(),
                status = WorkflowPlanStatusUi.READY,
                mentionedFiles = mentionedFiles.distinctBy { it.path },
                recentSessionHistoryClue = recentSessionHistoryClue,
                rawPlan = normalized
            )
        )
    }

    private fun buildWorkflowExecutionPrompt(
        state: SessionState,
        plan: WorkflowPlanUi
    ): String {
        val activeParallelBatchHint = buildActiveParallelBatchExecutionHint(state)
        val settledParallelBatchHint = if (activeParallelBatchHint == null) {
            buildSettledParallelBatchExecutionHint(state)
        } else {
            null
        }
        return buildString {
            appendLine("请按这份已确认的执行计划继续完成任务。")
            appendLine()
            appendLine("用户原始目标:")
            appendLine(plan.goal)
            appendLine()
            appendLine("计划摘要:")
            appendLine(plan.summary)
            appendLine()
            appendLine("当前计划状态:")
            appendLine(workflowPlanStatusLabel(plan.status))
            appendLine()
            appendLine("阶段安排:")
            plan.stageLabels.forEachIndexed { index, stage ->
                appendLine("${index + 1}. $stage")
            }
            appendLine()
            appendLine("执行步骤:")
            plan.steps.forEachIndexed { index, step ->
                appendLine("${index + 1}. $step")
            }
            appendLine()
            appendLine("下一步提示:")
            appendLine(
                plan.nextStepHint.ifBlank {
                    plan.steps.firstOrNull() ?: "先确认目标与边界，再继续执行。"
                }
            )
            appendLine()
            buildControllerSessionHistoryContext(state, plan.recentSessionHistoryClue)?.let {
                appendLine(it)
                appendLine()
            }
            appendLine("步骤签收要求:")
            appendLine("每当完成一个计划步骤、验证步骤或关键执行步骤时，先调用 complete_step，并引用本轮真实工具证据，再在自然语言里声称这一步已经完成。")
            activeParallelBatchHint?.let { hint ->
                appendLine()
                appendLine("并行批次约束:")
                appendLine(hint)
            }
            settledParallelBatchHint?.let { hint ->
                appendLine()
                appendLine("并行批次收敛提示:")
                appendLine(hint)
            }
            appendLine()
            append("要求：优先在一次连续工作流中完成整条链路，减少中间输出和上下文污染；")
            append("只有在缺少信息、需要审批或必须用户决策时才中途打断。")
        }.trim()
    }

    private fun normalizeWorkflowPlan(plan: WorkflowPlanUi): WorkflowPlanUi {
        val normalizedSteps = plan.steps.filter { it.isNotBlank() }.ifEmpty { buildDefaultWorkflowPlanSteps() }
        val normalizedStages = plan.stageLabels.filter { it.isNotBlank() }
            .ifEmpty { buildDefaultWorkflowStages(normalizedSteps) }
        val boundedStepIndex = plan.currentStepIndex.coerceIn(0, normalizedSteps.size)
        val normalizedSignOffs = plan.stepSignOffs
            .filter { it.stepIndex in normalizedSteps.indices }
            .groupBy { it.stepIndex }
            .mapNotNull { (_, entries) -> entries.maxByOrNull(WorkflowStepSignOffUi::signedOffAt) }
            .sortedBy { it.stepIndex }
        val boundedStageIndex = deriveWorkflowStageIndex(
            status = plan.status,
            currentStepIndex = boundedStepIndex,
            stepCount = normalizedSteps.size,
            stageCount = normalizedStages.size
        )
        return plan.copy(
            steps = normalizedSteps,
            stageLabels = normalizedStages,
            currentStepIndex = boundedStepIndex,
            currentStageIndex = boundedStageIndex,
            stepSignOffs = normalizedSignOffs,
            nextStepHint = plan.nextStepHint.ifBlank {
                defaultWorkflowNextStepHint(plan.status, normalizedSteps, boundedStepIndex)
            }
        )
    }

    private fun deriveWorkflowStageIndex(
        status: WorkflowPlanStatusUi,
        currentStepIndex: Int,
        stepCount: Int,
        stageCount: Int
    ): Int {
        if (stageCount <= 1 || stepCount <= 0) return 0
        if (status == WorkflowPlanStatusUi.COMPLETED) {
            return stageCount - 1
        }
        val normalizedProgress = when (status) {
            WorkflowPlanStatusUi.READY -> 0.0
            WorkflowPlanStatusUi.EXECUTING,
            WorkflowPlanStatusUi.BLOCKED -> currentStepIndex.coerceAtLeast(1).toDouble() / stepCount.toDouble()
            WorkflowPlanStatusUi.COMPLETED -> 1.0
        }
        return (normalizedProgress * stageCount).toInt().coerceIn(0, stageCount - 1)
    }

    private fun defaultWorkflowNextStepHint(
        status: WorkflowPlanStatusUi,
        steps: List<String>,
        currentStepIndex: Int
    ): String {
        return when (status) {
            WorkflowPlanStatusUi.READY ->
                steps.getOrNull(currentStepIndex) ?: "先确认目标与边界，再开始执行。"
            WorkflowPlanStatusUi.EXECUTING ->
                steps.getOrNull(currentStepIndex) ?: steps.lastOrNull() ?: "继续执行当前计划。"
            WorkflowPlanStatusUi.BLOCKED ->
                "先处理当前阻塞项，再继续推进这份计划。"
            WorkflowPlanStatusUi.COMPLETED ->
                "当前计划已完成，可继续发起下一轮任务或重做计划。"
        }
    }

    private fun buildDefaultWorkflowPlanSteps(): List<String> {
        return listOf(
            "先梳理任务目标与当前上下文",
            "再按最小风险顺序完成分析和执行",
            "最后汇总结果、风险与后续建议"
        )
    }

    private fun buildDefaultWorkflowStages(steps: List<String>): List<String> {
        return when {
            steps.size <= 1 -> listOf("确认目标", "执行处理", "整理交付")
            steps.size == 2 -> listOf("确认目标", "执行处理", "整理交付")
            else -> listOf("确认目标", "执行处理", "整理交付")
        }
    }

    private fun buildClarificationPrompt(
        goal: String,
        history: List<ChatMessage>,
        compressionContext: String?,
        mentionedFiles: List<FileMentionUi>,
        previousAnswers: List<ClarificationAnswerUi>,
        turnIndex: Int,
        maxTurns: Int
    ): List<ChatMessage> {
        val clarifyInstruction = """
            You are deciding whether one clarification question would materially reduce execution risk.
            Return exactly one concise clarification question in Chinese.
            Rules:
            - Ask only one question.
            - Focus on the most blocking ambiguity.
            - Do not answer the question yourself.
            - Do not include bullet points, numbering, code fences, or extra explanation.
        """.trimIndent()

        return buildList {
            addAll(buildStableAuxiliarySystemMessages(clarifyInstruction, compressionContext))
            addAll(history.takeLast(12))
            add(
                ChatMessage(
                    role = "user",
                    content = buildString {
                        buildTurnScopedAuxiliaryUserContext(
                            mentionedFiles = mentionedFiles,
                            extraContext = buildClarificationAnswersContext(previousAnswers)
                        )?.let {
                            appendLine(it)
                            appendLine()
                        }
                        appendLine("请针对这个任务生成一个最需要先确认的澄清问题：")
                        appendLine(goal)
                        if (previousAnswers.isNotEmpty()) {
                            appendLine()
                            appendLine("当前准备进入第 $turnIndex 轮澄清，最多 $maxTurns 轮。")
                            appendLine("请避免重复追问已经确认过的信息。")
                        }
                    }.trim()
                )
            )
        }
    }

    private fun parseClarificationQuestion(
        goal: String,
        rawQuestion: String,
        mentionedFiles: List<FileMentionUi>,
        previousAnswers: List<ClarificationAnswerUi>,
        turnIndex: Int,
        maxTurns: Int,
        source: ClarificationSource,
        recentSessionHistoryClue: SessionHistoryReferenceClueUi?
    ): ClarificationRequestUi {
        val normalized = rawQuestion
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.removePrefix("- ")
            ?.removePrefix("* ")
            ?.removePrefix("1. ")
            ?.trim()
            .orEmpty()
        require(normalized.isNotBlank()) { "澄清问题返回为空" }

        return ClarificationRequestUi(
            goal = goal,
            question = normalized,
            mentionedFiles = mentionedFiles.distinctBy { it.path },
            previousAnswers = previousAnswers,
            turnIndex = turnIndex,
            maxTurns = maxTurns,
            source = source,
            recentSessionHistoryClue = recentSessionHistoryClue
        )
    }

    private fun buildClarificationFollowUpPrompt(
        state: SessionState,
        goal: String,
        history: List<ChatMessage>,
        compressionContext: String?,
        mentionedFiles: List<FileMentionUi>,
        answers: List<ClarificationAnswerUi>,
        recentSessionHistoryClue: SessionHistoryReferenceClueUi?,
        nextTurnIndex: Int,
        maxTurns: Int
    ): List<ChatMessage> {
        val followUpInstruction = """
            You are deciding whether the assistant already has enough information to continue executing a task.
            Return plain text with exactly this structure:
            ACTION: EXECUTE|CLARIFY
            QUESTION: one concise Chinese question, or leave blank when ACTION is EXECUTE

            Rules:
            - Choose EXECUTE when the collected clarification answers are sufficient to continue with acceptable risk.
            - Choose CLARIFY only when one more user answer is still truly blocking.
            - If you choose CLARIFY, ask a new question and do not repeat earlier questions.
            - Do not add any extra text.
        """.trimIndent()

        return buildList {
            addAll(buildStableAuxiliarySystemMessages(followUpInstruction, compressionContext))
            addAll(history.takeLast(12))
            add(
                ChatMessage(
                    role = "user",
                    content = buildString {
                        buildTurnScopedAuxiliaryUserContext(mentionedFiles)?.let {
                            appendLine(it)
                            appendLine()
                        }
                        buildControllerSessionHistoryContext(state, recentSessionHistoryClue)?.let {
                            appendLine(it)
                            appendLine()
                        }
                        appendLine("请判断在当前澄清信息下，是否可以继续执行任务，或还需要再问一个新的关键问题。")
                        appendLine()
                        appendLine("原始任务:")
                        appendLine(goal)
                        appendLine()
                        appendLine("已确认的澄清信息:")
                        appendLine(formatClarificationAnswers(answers))
                        appendLine()
                        append("如果还需要继续澄清，接下来将进入第 $nextTurnIndex 轮，总上限 $maxTurns 轮。")
                    }.trim()
                )
            )
        }
    }

    private fun parseClarificationFollowUpDecision(raw: String): ClarificationFollowUpDecision {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val actionText = lines.firstOrNull { it.startsWith("ACTION:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.uppercase()
            ?: throw IllegalArgumentException("澄清续问判断返回格式不完整")
        val question = lines.firstOrNull { it.startsWith("QUESTION:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val action = when (actionText) {
            "CLARIFY" -> AutoRouteAction.CLARIFY
            "EXECUTE" -> AutoRouteAction.DIRECT
            else -> throw IllegalArgumentException("澄清续问判断返回了未知动作: $actionText")
        }
        if (action == AutoRouteAction.CLARIFY && question == null) {
            throw IllegalArgumentException("澄清续问判断缺少问题")
        }
        return ClarificationFollowUpDecision(
            action = action,
            question = question
        )
    }

    private fun buildClarificationExecutionPrompt(
        goal: String,
        answers: List<ClarificationAnswerUi>
    ): String {
        return buildClarificationExecutionPrompt(
            goal = goal,
            answers = answers,
            state = SessionState(),
            recentSessionHistoryClue = null
        )
    }

    private fun buildClarificationExecutionPrompt(
        goal: String,
        answers: List<ClarificationAnswerUi>,
        state: SessionState,
        recentSessionHistoryClue: SessionHistoryReferenceClueUi?
    ): String {
        return buildString {
            appendLine("用户已经完成当前阶段的澄清，请基于这些信息继续完成任务。")
            appendLine()
            appendLine("原始任务:")
            appendLine(goal)
            appendLine()
            appendLine("已确认的澄清信息:")
            appendLine(formatClarificationAnswers(answers))
            appendLine()
            buildControllerSessionHistoryContext(state, recentSessionHistoryClue)?.let {
                appendLine(it)
                appendLine()
            }
            append("要求：吸收这些澄清信息后继续执行，不要重复追问已经确认过的内容；")
            append("只有在仍然存在新的关键阻塞信息时，才提出新的澄清问题。")
        }.trim()
    }

    private fun buildClarificationAnswersContext(
        answers: List<ClarificationAnswerUi>
    ): String? {
        if (answers.isEmpty()) return null
        return buildString {
            appendLine("Clarification History For Current Task:")
            append(formatClarificationAnswers(answers))
        }.trim()
    }

    private fun formatClarificationAnswers(
        answers: List<ClarificationAnswerUi>
    ): String {
        return answers.joinToString("\n\n") { item ->
            buildString {
                appendLine("问题: ${item.question}")
                append("回答: ${item.answer}")
            }.trim()
        }
    }

    private fun buildExecutionInterruptionContext(
        existingClarificationAnswers: List<ClarificationAnswerUi>
    ): String {
        return buildString {
            appendLine("Execution Interruption Rule:")
            appendLine("If you discover during execution that one missing user answer is truly blocking and guessing would be risky, stop and return exactly this plain-text structure:")
            appendLine("WORKFLOW_INTERRUPT: CLARIFY")
            appendLine("QUESTION: one concise Chinese question")
            appendLine("REASON: one short Chinese sentence")
            appendLine()
            appendLine("Do not include markdown, bullet points, code fences, or any extra text when using that structure.")
            appendLine("Do not use it for minor uncertainty; only use it when execution should pause and ask the user.")
            if (existingClarificationAnswers.isNotEmpty()) {
                appendLine()
                appendLine("Already confirmed clarification answers for this task:")
                append(formatClarificationAnswers(existingClarificationAnswers))
            }
        }.trim()
    }

    private fun parseExecutionInterruptionDecision(raw: String): ExecutionInterruptionDecision? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val header = lines.firstOrNull() ?: return null
        if (!header.equals("WORKFLOW_INTERRUPT: CLARIFY", ignoreCase = true)) return null
        val question = lines.firstOrNull { it.startsWith("QUESTION:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val reason = lines.firstOrNull { it.startsWith("REASON:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return ExecutionInterruptionDecision(
            question = question ?: buildLocalFallbackClarificationQuestion(
                goal = "",
                previousAnswers = emptyList()
            ),
            reason = reason,
            usedFallbackQuestion = question == null
        )
    }

    private suspend fun handleExecutionInterruptionIfNeeded(
        assistantMessageId: Long,
        executionGoal: String,
        mentionedFiles: List<FileMentionUi>,
        existingClarificationAnswers: List<ClarificationAnswerUi>,
        config: ProviderConfig
    ): Boolean {
        val assistantMessage = _state.value.messages.firstOrNull { it.id == assistantMessageId } ?: return false
        val interruption = parseExecutionInterruptionDecision(assistantMessage.content) ?: return false
        val nextTurnIndex = existingClarificationAnswers.size + 1
        if (interruption.usedFallbackQuestion) {
            when (
                resolveWorkflowFailureFallbackMode(
                    config = config,
                    failureType = WorkflowFailureType.EXECUTION_INTERRUPT_FORMAT_FAILURE,
                    hasMentionedFiles = mentionedFiles.isNotEmpty(),
                    source = WorkflowFailureFallbackSource.AUTO_INTERRUPT,
                    clarificationTurnIndex = nextTurnIndex,
                    projectType = currentProjectWorkflowType(),
                    providerId = config.activeProviderId,
                    projectRiskLevel = currentProjectWorkflowRiskLevel()
                )
            ) {
                WorkflowFailureFallbackMode.DIRECT_EXECUTION -> {
                    val stateBeforeResume = _state.value
                    val resumeExecutionPayload = buildResumeExecutionPayload(
                        executionGoal = executionGoal,
                        existingClarificationAnswers = existingClarificationAnswers,
                        state = stateBeforeResume,
                        clarificationExecutionPromptBuilder = ::buildClarificationExecutionPrompt
                    )
                    updateMessage(assistantMessageId) { message ->
                        message.copy(
                            content = "执行中自动打断格式不完整，已按配置回退为直接继续执行。",
                            reasoning = null,
                            isStreaming = false
                        )
                    }
                    _state.value = applyDirectExecutionResumeTransition(
                        state = _state.value,
                        clearPendingWorkflowPlan = true
                    )
                    sendMessageInternal(
                        userVisibleText = resumeExecutionPayload.userVisibleText,
                        modelInput = resumeExecutionPayload.modelInput,
                        mentionedFiles = mentionedFiles,
                        executionGoal = resumeExecutionPayload.executionGoal,
                        existingClarificationAnswers = resumeExecutionPayload.existingClarificationAnswers,
                        forceWritableTools = true
                    )
                    applyWorkflowFallback(
                        message = "执行中自动打断格式不完整，已按配置回退为直接继续执行。",
                        config = config
                    )
                    return true
                }
                WorkflowFailureFallbackMode.LOCAL_CLARIFICATION -> Unit
                WorkflowFailureFallbackMode.FOLLOW_SCENARIO_DEFAULT -> Unit
            }
        }
        updateMessage(assistantMessageId) { message ->
            message.copy(
                content = interruption.reason
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "已暂停执行，等待补充信息：$it" }
                    ?: "已暂停执行，等待你补充一个关键条件。",
                reasoning = null,
                isStreaming = false
            )
        }
        _state.value = applyExecutionInterruptionClarificationTransition(
            state = _state.value,
            request = buildExecutionInterruptionClarificationRequest(
                executionGoal = executionGoal,
                question = interruption.question,
                mentionedFiles = mentionedFiles,
                existingClarificationAnswers = existingClarificationAnswers,
                state = _state.value
            )
        )
        _state.value.pendingClarificationRequest?.let { request ->
            recordClarificationPromptCheckpoint(
                request = request,
                summary = "交互状态: 执行中等待澄清回答"
            )
        }
        if (interruption.usedFallbackQuestion) {
            applyWorkflowFallback(
                message = "执行中自动打断格式不完整，已按配置回退为本地通用澄清问题。",
                config = config
            )
        }
        saveCurrentSession(config)
        return true
    }

    private fun buildLocalFallbackClarificationQuestion(
        goal: String,
        previousAnswers: List<ClarificationAnswerUi>
    ): String {
        return buildDefaultLocalFallbackClarificationQuestion(
            goal = goal,
            previousAnswers = previousAnswers
        )
    }

    private fun buildLocalFallbackClarificationRequest(
        goal: String,
        mentionedFiles: List<FileMentionUi>,
        previousAnswers: List<ClarificationAnswerUi>,
        turnIndex: Int,
        maxTurns: Int,
        source: ClarificationSource,
        recentSessionHistoryClue: SessionHistoryReferenceClueUi?
    ): ClarificationRequestUi {
        return ClarificationRequestUi(
            goal = goal,
            question = buildLocalFallbackClarificationQuestion(
                goal = goal,
                previousAnswers = previousAnswers
            ),
            mentionedFiles = mentionedFiles.distinctBy { it.path },
            previousAnswers = previousAnswers,
            turnIndex = turnIndex,
            maxTurns = maxTurns,
            source = source,
            recentSessionHistoryClue = recentSessionHistoryClue
        )
    }

    private fun resolveWorkflowFailureFallbackMode(
        config: ProviderConfig,
        failureType: WorkflowFailureType,
        hasMentionedFiles: Boolean = false,
        source: WorkflowFailureFallbackSource? = null,
        clarificationTurnIndex: Int? = null,
        projectType: ProjectWorkflowType? = null,
        providerId: String? = null,
        projectRiskLevel: ProjectWorkflowRiskLevel? = null
    ): WorkflowFailureFallbackMode {
        val resolvedMode = config.getWorkflowFailureFallbackMode(
            failureType = failureType,
            context = WorkflowFailureFallbackContext(
                hasMentionedFiles = hasMentionedFiles,
                source = source,
                clarificationTurnIndex = clarificationTurnIndex,
                projectType = projectType,
                providerId = providerId,
                projectRiskLevel = projectRiskLevel
            )
        )
        return when (resolvedMode) {
            WorkflowFailureFallbackMode.FOLLOW_SCENARIO_DEFAULT -> when (failureType) {
                WorkflowFailureType.AUTO_ROUTE_FAILURE,
                WorkflowFailureType.CLARIFICATION_FOLLOW_UP_FAILURE ->
                    WorkflowFailureFallbackMode.DIRECT_EXECUTION
                WorkflowFailureType.CLARIFICATION_GENERATION_FAILURE,
                WorkflowFailureType.EXECUTION_INTERRUPT_FORMAT_FAILURE ->
                    WorkflowFailureFallbackMode.LOCAL_CLARIFICATION
            }
            else -> resolvedMode
        }
    }

    private fun currentProjectWorkflowType(): ProjectWorkflowType? {
        return null
    }

    private fun currentProjectWorkflowRiskLevel(): ProjectWorkflowRiskLevel? {
        return null
    }

    private fun ClarificationSource.toWorkflowFailureFallbackSource(): WorkflowFailureFallbackSource {
        return when (this) {
            ClarificationSource.MANUAL -> WorkflowFailureFallbackSource.MANUAL_CLARIFICATION
            ClarificationSource.AUTO_ROUTE -> WorkflowFailureFallbackSource.AUTO_ROUTE
            ClarificationSource.AUTO_INTERRUPT -> WorkflowFailureFallbackSource.AUTO_INTERRUPT
        }
    }

    private fun applyWorkflowFallback(
        message: String,
        config: ProviderConfig? = null
    ) {
        _state.value = _state.value.copy(
            lastWorkflowFallback = WorkflowFallbackUi(message = message)
        )
        saveCurrentSession(config)
    }

    private fun buildMentionedFilesContext(mentionedFiles: List<FileMentionUi>): String? {
        if (mentionedFiles.isEmpty()) return null
        val sections = mutableListOf<String>()
        var totalChars = 0
        mentionedFiles
            .distinctBy { it.path }
            .take(MAX_MENTIONED_FILES_PER_REQUEST)
            .forEach { mention ->
                val content = mention.inlineContent ?: readCurrentFileContent(mention.path)
                val snippet = when {
                    content == null -> "(文件不存在或读取失败)"
                    content.length > MAX_MENTION_FILE_CHARS ->
                        content.take(MAX_MENTION_FILE_CHARS) + "\n...(已截断)"
                    else -> content
                }
                if (totalChars >= MAX_MENTION_TOTAL_CHARS) return@forEach
                val limitedSnippet = if (totalChars + snippet.length > MAX_MENTION_TOTAL_CHARS) {
                    snippet.take(MAX_MENTION_TOTAL_CHARS - totalChars) + "\n...(已截断)"
                } else {
                    snippet
                }
                totalChars += limitedSnippet.length
                sections += buildString {
                    appendLine("文件: ${mention.displayPath}")
                    appendLine(limitedSnippet)
                }.trim()
            }
        if (sections.isEmpty()) return null
        return buildString {
            appendLine("Referenced Files For This Request:")
            appendLine("Use these files as direct context for the current request. They are not part of the visible chat transcript.")
            appendLine()
            append(sections.joinToString("\n\n"))
        }.trim()
    }

    private fun collectProjectFiles(
        rootPath: String,
        currentDir: String,
        depth: Int,
        out: MutableList<FileMentionUi>,
        maxFiles: Int
    ) {
        if (depth > MAX_MENTION_FILE_SCAN_DEPTH || out.size >= maxFiles || !RootFile.dirExists(currentDir)) return
        RootFile.ls(currentDir)
            .sorted()
            .take(MAX_MENTION_DIRECTORY_ENTRIES)
            .forEach { childName ->
                if (out.size >= maxFiles) return
                val fullPath = joinPath(currentDir, childName)
                when {
                    RootFile.dirExists(fullPath) -> collectProjectFiles(
                        rootPath = rootPath,
                        currentDir = fullPath,
                        depth = depth + 1,
                        out = out,
                        maxFiles = maxFiles
                    )
                    RootFile.fileExists(fullPath) -> out += FileMentionUi(
                        path = fullPath,
                        displayPath = toRelativePath(rootPath, fullPath)
                    )
                }
            }
    }

    private fun resolveMentionFromQuery(rootPath: String, query: String): FileMentionUi? {
        if (query.isBlank()) return null
        val normalizedRoot = rootPath.trim().trimEnd('/', '\\')
        val rawPath = if (query.startsWith("/") || query.contains(":\\")) {
            query.replace('/', '\\')
        } else {
            joinPath(normalizedRoot, query)
        }
        return if (RootFile.fileExists(rawPath)) {
            FileMentionUi(
                path = rawPath,
                displayPath = toRelativePath(normalizedRoot, rawPath)
            )
        } else {
            null
        }
    }

    private fun joinPath(base: String, child: String): String {
        val separator = if (base.contains("\\")) "\\" else "/"
        return base.trimEnd('/', '\\') + separator + child.trimStart('/', '\\')
    }

    private fun toRelativePath(rootPath: String, fullPath: String): String {
        val normalizedRoot = rootPath.trim().replace('\\', '/').trimEnd('/')
        val normalizedFull = fullPath.trim().replace('\\', '/')
        return normalizedFull.removePrefix("$normalizedRoot/").removePrefix(normalizedRoot)
            .ifBlank { normalizedFull.substringAfterLast('/') }
    }

    private fun matchRank(displayPath: String, query: String): Int {
        if (query.isBlank()) return 2
        val normalizedDisplay = displayPath.lowercase()
        val normalizedQuery = query.lowercase()
        return when {
            normalizedDisplay == normalizedQuery -> 0
            normalizedDisplay.startsWith(normalizedQuery) -> 1
            normalizedDisplay.contains(normalizedQuery) -> 2
            else -> 3
        }
    }

    private fun buildPersistedSession(
        config: ProviderConfig? = null,
        state: SessionState = _state.value,
        sessionId: String = currentSessionId,
        approvedScopes: List<PersistedApprovedApprovalScope> = approvedApprovalScopes.toList(),
        cachedSession: PersistedSession? = cachedCurrentPersistedSession?.takeIf { it.id == sessionId }
    ): PersistedSession {
        val savedSession = cachedSession
        val resolvedConfig = config ?: lastSessionConfig
        val normalizedProjectPath = normalizeProjectPath(state.projectPath)
        val normalizedActiveScopePath = activeProjectScopePath(
            state.activeProjectScopePath,
            state.projectPath
        )
        val persistedProjectConfig = preparePersistedProjectConfig(
            activeScopePath = normalizedActiveScopePath,
            currentProjectConfig = state.toSessionProjectConfig(),
            repoScopedConfigs = state.normalizedRepoScopedProjectConfigMap()
        )
        val persistedProjectProjection = persistedProjectConfig.toPersistedSessionProjectConfigProjection()
        val providerId = when {
            config != null -> resolvedConfig.activeProviderId
            savedSession != null -> savedSession.providerId
            else -> resolvedConfig.activeProviderId
        }
        val modelName = when {
            config != null -> resolvedConfig.getActiveModel()
            savedSession != null -> savedSession.modelName
            else -> resolvedConfig.getActiveModel()
        }

        return PersistedSession(
            id = sessionId,
            title = state.sessionTitle.ifBlank { "新对话" },
            createdAt = savedSession?.createdAt
                ?: state.messages.firstOrNull()?.timestamp
                ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            providerId = providerId,
            modelName = modelName,
            sessionGoal = state.sessionGoal,
            projectPath = state.projectPath,
            remoteTaskRepositoryOwner = state.remoteTaskRepositoryOwner,
            remoteTaskRepositoryName = state.remoteTaskRepositoryName,
            remoteTaskRepositoryLabel = state.remoteTaskRepositoryLabel,
            remoteTaskRepositoryEditable = state.remoteTaskRepositoryEditable,
            activeProjectScopePath = normalizedActiveScopePath,
            projectRules = persistedProjectProjection.legacyProjectConfig.projectRules,
            projectMemories = persistedProjectProjection.legacyProjectConfig.projectMemories,
            projectSkills = persistedProjectProjection.legacyProjectConfig.projectSkills,
            projectKnowledgePaths = state.projectKnowledgePaths,
            projectKnowledgeSnapshots = state.projectKnowledgeSnapshots.map { snapshot ->
                PersistedProjectKnowledgeSnapshot(
                    id = snapshot.id,
                    name = snapshot.name,
                    paths = snapshot.paths,
                    createdAt = snapshot.createdAt,
                    updatedAt = snapshot.updatedAt,
                    lastAppliedAt = snapshot.lastAppliedAt
                )
            },
            projectToolPreferences = persistedProjectProjection.legacyProjectConfig.projectToolPreferences,
            repoScopedConfigs = persistedProjectProjection.repoScopedConfigs,
            usageSummary = state.usageSummary,
            compressionSnapshot = conversationStore.persistCompressionSnapshot(state.compressionSnapshot),
            compressionSnapshots = conversationStore.persistCompressionSnapshots(state.compressionSnapshots),
            checkpoints = conversationStore.persistConversationCheckpoints(state.checkpoints),
            fileChanges = conversationStore.persistFileChanges(state.fileChanges),
            subagentRuns = conversationStore.persistSubagentRuns(state.subagentRuns),
            subagentBatches = conversationStore.persistSubagentBatches(state.subagentBatches),
            backgroundJobs = conversationStore.persistBackgroundJobs(state.backgroundJobs),
            recentErrors = conversationStore.persistErrorRecords(state.recentErrors),
            recentToolCalls = conversationStore.persistToolCallRecords(state.recentToolCalls),
            recentMemoryUpdateSuggestions = conversationStore.persistMemoryUpdateSuggestions(
                state.recentMemoryUpdateSuggestions
            ),
            recentFinalReadinessAudits = conversationStore.persistFinalReadinessAuditRecords(
                state.recentFinalReadinessAudits
            ),
            recentApprovals = conversationStore.persistApprovalRecords(state.recentApprovals),
            recentRecoveryRecords = conversationStore.persistCheckpointRecoveryRecords(
                state.recentRecoveryRecords
            ),
            recentApprovalInvalidations = conversationStore.persistApprovalInvalidationRecords(
                state.recentApprovalInvalidations
            ),
            approvedApprovalScopes = approvedScopes.map { it.tokens },
            approvedApprovalScopeEntries = conversationStore.persistApprovedApprovalScopes(approvedScopes),
            pendingApproval = conversationStore.persistPendingApproval(
                state.pendingApproval?.takeUnless { it.isReplayOnly }
            ),
            pendingAskRequest = conversationStore.persistPendingAskRequest(
                state.pendingAskRequest?.takeUnless { it.isReplayOnly }
            ),
            pendingWorkflowPlan = conversationStore.persistWorkflowPlan(state.pendingWorkflowPlan),
            canonicalWorkflowPlan = conversationStore.persistWorkflowPlan(state.canonicalWorkflowPlan),
            pendingClarificationRequest = conversationStore.persistClarificationRequest(
                state.pendingClarificationRequest
            ),
            lastAutoRouteDecision = state.lastAutoRouteDecision?.let { decision ->
                PersistedAutoRouteDecision(
                    action = decision.action.name,
                    reason = decision.reason,
                    createdAt = decision.createdAt
                )
            },
            lastWorkflowFallback = state.lastWorkflowFallback?.let { fallback ->
                PersistedWorkflowFallback(
                    message = fallback.message,
                    createdAt = fallback.createdAt
                )
            },
            lastFinalReadinessReceipt = conversationStore.persistFinalReadinessReceipt(
                state.lastFinalReadinessReceipt
            ),
            messages = conversationStore.persistMessages(state.messages)
        )
    }

    private fun recordFileChanges(
        toolName: String,
        fileChanges: List<ToolFileChange>
    ): List<FileChangeRecordUi> {
        if (fileChanges.isEmpty()) return emptyList()
        val currentState = _state.value
        val captureUpdate = captureTurnCheckpointFileChanges(
            captureState = currentTurnCheckpointCaptureState,
            toolName = toolName,
            fileChanges = fileChanges,
            existingCheckpoint = currentTurnCheckpointCaptureState.checkpointId?.let { checkpointId ->
                currentState.checkpoints.firstOrNull { it.id == checkpointId }
            },
            messageIndex = currentState.messages.lastIndex,
            checkpointIdFactory = { "chk-${System.currentTimeMillis()}" }
        ) ?: return emptyList()
        currentTurnCheckpointCaptureState = captureUpdate.nextState
        _state.value = _state.value.copy(
            fileChanges = (captureUpdate.records + currentState.fileChanges).take(MAX_FILE_CHANGE_HISTORY),
            checkpoints = upsertCheckpointHistory(
                checkpoints = currentState.checkpoints,
                checkpoint = captureUpdate.checkpoint,
                maxSize = MAX_CHECKPOINT_HISTORY
            )
        )
        return captureUpdate.records
    }

    private fun buildToolResultMessage(
        toolName: String,
        args: String? = null,
        result: String,
        fileChanges: List<FileChangeRecordUi>
    ): String {
        if (toolName == "web_fetch" && result.startsWith("{")) {
            return "$WEB_FETCH_RESULT_PREFIX$result"
        }
        val changeSummary = if (fileChanges.isEmpty()) {
            ""
        } else {
            buildString {
                append("\n\n本次文件变更:\n")
                fileChanges.take(6).forEach { change ->
                    append("- ${change.path} (${formatOperationLabel(change.operation)})\n")
                }
                if (fileChanges.size > 6) {
                    append("- ...(还有 ${fileChanges.size - 6} 个文件)\n")
                }
            }
        }
        val previewLimit = when (toolName) {
            "file" -> 12_000
            "shell" -> 8_000
            else -> 4_000
        }
        val shellCommandPreview = if (toolName == "shell") {
            extractShellCommandPreview(args)
        } else {
            null
        }
        return buildString {
            append("📦 **")
            append(toolName)
            append("** 执行结果:")
            shellCommandPreview?.let {
                append("\n命令: ")
                append(it)
            }
            append("\n```\n")
            append(result.take(previewLimit))
            if (result.length > previewLimit) {
                append("\n...(截断)")
            }
            append("\n```")
            append(changeSummary)
        }
    }

    private fun buildToolExecutionMessage(toolName: String, args: String?): String {
        val trimmedArgs = args?.trim().orEmpty()
        return buildString {
            append("🔧 正在执行: **")
            append(toolName)
            append("**")
            if (trimmedArgs.isNotBlank()) {
                append("\n```json\n")
                append(trimmedArgs)
                append("\n```")
            }
        }
    }

    private fun summarizeToolResultForHistory(message: String): String? {
        if (message.isBlank()) return null
        if (message.startsWith(WEB_FETCH_RESULT_PREFIX)) {
            return summarizeWebFetchHistoryResult(message)
        }
        val toolName = Regex("""^📦 \*\*(.+?)\*\* 执行结果:""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?: return message.lineSequence().firstOrNull { it.isNotBlank() }?.take(240)
        val payload = extractToolResultPayload(message)
        val summary = when (toolName) {
            "file" -> summarizeFileToolPayload(payload)
            "shell" -> summarizeShellToolPayload(message, payload)
            "code_search" -> summarizeCodeSearchToolPayload(payload)
            "web_search" -> summarizeWebSearchToolPayload(payload)
            else -> summarizeGenericToolPayload(payload)
        }
        val changeSummary = summarizeToolChangeBlock(message)
        return buildString {
            append("工具结果摘要($toolName): ")
            append(summary)
            if (changeSummary != null) {
                append(" ")
                append(changeSummary)
            }
        }
    }

    private fun extractToolResultPayload(message: String): String {
        return Regex("""```(?:\w+)?\n([\s\S]*?)\n```""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun summarizeFileToolPayload(payload: String): String {
        val lines = payload.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val firstMeaningfulLine = lines.firstOrNull()
            ?: return "已执行文件工具，原始输出为空。"
        if (firstMeaningfulLine.startsWith("Error", ignoreCase = true)) {
            return firstMeaningfulLine.take(240)
        }
        val filePath = lines.firstOrNull { it.startsWith("File: ") }?.removePrefix("File: ")?.trim()
        if (filePath != null) {
            val range = lines.firstOrNull { it.startsWith("Lines: ") }
                ?.removePrefix("Lines: ")
                ?.trim()
                ?: "未知行范围"
            val contentPreview = buildHistoryContentPreview(
                text = payload.substringAfter("\n\n", ""),
                maxLines = 10,
                maxChars = 560
            )
            return if (contentPreview != null) {
                "已读取文件 $filePath，范围 $range；内容片段: $contentPreview"
            } else {
                "已读取文件 $filePath，范围 $range；文件内容为空。"
            }
        }
        val directoryPath = lines.firstOrNull { it.startsWith("Directory: ") }
            ?.removePrefix("Directory: ")
            ?.trim()
        if (directoryPath != null) {
            val entries = lines.firstOrNull { it.startsWith("Entries: ") }
                ?.removePrefix("Entries: ")
                ?.trim()
                ?: "未知条目范围"
            val entryPreview = buildHistoryContentPreview(
                text = payload.substringAfter("\n\n", ""),
                maxLines = 8,
                maxChars = 260
            )
            return if (entryPreview != null) {
                "已列出目录 $directoryPath，范围 $entries；条目片段: $entryPreview"
            } else {
                "已列出目录 $directoryPath，范围 $entries。"
            }
        }
        if (isFileStateLine(firstMeaningfulLine)) {
            return firstMeaningfulLine.take(240)
        }
        return summarizeGenericToolPayload(payload)
    }

    private fun isFileStateLine(line: String): Boolean {
        return listOf(
            "File exists:",
            "File does not exist:",
            "Permission changed:",
            "Deleted:",
            "File written successfully:",
            "(empty directory)"
        ).any { prefix ->
            line.startsWith(prefix, ignoreCase = true)
        }
    }

    private fun summarizeShellToolPayload(message: String, payload: String): String {
        val firstMeaningfulLine = payload.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return "已执行 shell 命令，原始输出已省略。"
        if (firstMeaningfulLine.startsWith("Error", ignoreCase = true)) {
            return firstMeaningfulLine.take(240)
        }
        val shellCommand = extractShellCommandFromResultMessage(message)
        if (shellCommand != null &&
            isShellInspectionCommand(shellCommand) &&
            !isShellMutationCommand(shellCommand)
        ) {
            val preview = buildShellInspectionPreview(
                command = shellCommand,
                payload = payload
            )
            return if (preview != null) {
                "shell读取结果(${shellCommand.take(80)}): $preview"
            } else {
                "已执行 shell 读取命令(${shellCommand.take(80)})，但输出为空。"
            }
        }
        if (firstMeaningfulLine.contains("timeout", ignoreCase = true) || firstMeaningfulLine.contains("超时")) {
            return "shell 命令执行超时，原始输出已省略。"
        }
        if (firstMeaningfulLine == "(command completed, no output)") {
            return "shell 命令已执行完成，无输出。"
        }
        return "已执行 shell 命令并获得输出，原始输出已省略。"
    }

    private fun summarizeCodeSearchToolPayload(payload: String): String {
        val lines = payload.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val firstMeaningfulLine = lines.firstOrNull()
            ?: return "已执行代码搜索，但结果为空。"
        if (firstMeaningfulLine.startsWith("Error", ignoreCase = true)) {
            return firstMeaningfulLine.take(240)
        }
        if (firstMeaningfulLine.startsWith("未找到匹配结果")) {
            val pattern = lines.firstOrNull { it.startsWith("Pattern: ") }
                ?.removePrefix("Pattern: ")
                ?.trim()
            val path = lines.firstOrNull { it.startsWith("Path: ") }
                ?.removePrefix("Path: ")
                ?.trim()
            return buildString {
                append("代码搜索未命中")
                pattern?.takeIf { it.isNotBlank() }?.let { append("，Pattern: $it") }
                path?.takeIf { it.isNotBlank() }?.let { append("，Path: $it") }
                append("。")
            }
        }
        val returnedCount = lines.firstOrNull { it.startsWith("Matches returned: ") }
            ?.removePrefix("Matches returned: ")
            ?.trim()
            ?.toIntOrNull()
        val pattern = lines.firstOrNull { it.startsWith("Pattern: ") }
            ?.removePrefix("Pattern: ")
            ?.trim()
        val path = lines.firstOrNull { it.startsWith("Path: ") }
            ?.removePrefix("Path: ")
            ?.trim()
        val topHits = payload.split(Regex("""\n(?=\d+\.\sFile:\s)"""))
            .mapNotNull { block ->
                val file = Regex("""(?m)^\d+\.\sFile:\s(.+)$""").find(block)?.groupValues?.getOrNull(1)?.trim()
                val line = Regex("""(?m)^Line:\s(\d+)$""").find(block)?.groupValues?.getOrNull(1)?.trim()
                val match = Regex("""(?m)^Match:\s(.+)$""").find(block)?.groupValues?.getOrNull(1)?.trim()
                if (file.isNullOrBlank() || line.isNullOrBlank() || match.isNullOrBlank()) null
                else "$file:$line -> ${match.take(140)}"
            }
            .take(3)
        return buildString {
            append("代码搜索已返回")
            returnedCount?.let { append(" $it 条命中") } ?: append("若干命中")
            pattern?.takeIf { it.isNotBlank() }?.let { append("，Pattern: $it") }
            path?.takeIf { it.isNotBlank() }?.let { append("，Path: $it") }
            if (topHits.isNotEmpty()) {
                append("；前几条: ")
                append(topHits.joinToString(" | "))
            }
            append("。")
        }
    }

    private fun summarizeWebSearchToolPayload(payload: String): String {
        val lines = payload.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val firstMeaningfulLine = lines.firstOrNull()
            ?: return "已执行网页搜索，但结果为空。"
        if (firstMeaningfulLine.startsWith("Error", ignoreCase = true) ||
            firstMeaningfulLine.startsWith("网页搜索暂时不可用")
        ) {
            return firstMeaningfulLine.take(240)
        }
        val source = lines.firstOrNull { it.startsWith("搜索源: ") }
            ?.removePrefix("搜索源: ")
            ?.trim()
        val entries = payload.split(Regex("""\n(?=\d+\.\s)"""))
            .mapNotNull { block ->
                val title = Regex("""(?m)^\d+\.\s(.+)$""").find(block)?.groupValues?.getOrNull(1)?.trim()
                val snippet = Regex("""(?m)^URL:\s.+$\n\s*(.+)$""").find(block)?.groupValues?.getOrNull(1)?.trim()
                title?.takeIf { it.isNotBlank() }?.let {
                    if (snippet.isNullOrBlank()) it else "$it - ${snippet.take(120)}"
                }
            }
            .take(3)
        return buildString {
            append("网页搜索已返回")
            source?.takeIf { it.isNotBlank() }?.let { append("，来源: $it") }
            if (entries.isNotEmpty()) {
                append("；前几条: ")
                append(entries.joinToString(" | "))
            }
            append("。")
        }
    }

    private fun summarizeWebFetchHistoryResult(message: String): String {
        val raw = message.removePrefix(WEB_FETCH_RESULT_PREFIX).trim()
        if (raw.isBlank()) {
            return "工具结果摘要: web_fetch 已抓取网页内容，但结果为空。"
        }
        return runCatching {
            val root = Json.parseToJsonElement(raw).jsonObject
            val title = root["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val url = root["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val excerpt = root["excerpt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = root["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val truncated = root["truncated"]?.jsonPrimitive?.booleanOrNull == true
            val preview = buildHistoryContentPreview(
                text = excerpt.ifBlank { content },
                maxLines = 6,
                maxChars = 360
            )
            buildString {
                append("网页抓取结果")
                title.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                url.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
                preview?.let { append("；摘要: ").append(it) }
                if (truncated) {
                    append("；正文已截断。")
                } else {
                    append("。")
                }
            }
        }.getOrElse {
            "工具结果摘要: web_fetch 已抓取网页内容，原始正文已省略以保持上下文稳定。"
        }
    }

    private fun extractShellCommandPreview(args: String?): String? {
        if (args.isNullOrBlank()) return null
        return runCatching {
            Json.parseToJsonElement(args)
                .jsonObject["command"]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()
            ?.trim()
            ?.replace(Regex("""\s+"""), " ")
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > 180) it.take(180) + "..." else it }
    }

    private fun extractShellCommandFromResultMessage(message: String): String? {
        return message.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("命令: ") }
            ?.removePrefix("命令: ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun isShellInspectionCommand(command: String): Boolean {
        val inspectionPrefixes = listOf(
            "cat ",
            "sed -n",
            "sed ",
            "awk ",
            "head ",
            "tail ",
            "more ",
            "less ",
            "nl ",
            "cut ",
            "grep ",
            "rg ",
            "find ",
            "ls",
            "pwd",
            "stat ",
            "wc ",
            "du ",
            "tree",
            "readlink ",
            "realpath ",
            "git status",
            "git diff",
            "git log",
            "git branch",
            "git ls-files",
            "git grep",
            "git show",
            "git rev-parse",
            "git remote -v"
        )
        return splitShellCommandSegments(command).any { segment ->
            val normalized = normalizeShellSegment(segment)
            inspectionPrefixes.any { token ->
                normalized == token || normalized.startsWith(token)
            }
        }
    }

    private fun isShellMutationCommand(command: String): Boolean {
        val mutationPrefixes = listOf(
            "rm ",
            "mv ",
            "cp ",
            "chmod ",
            "chown ",
            "chgrp ",
            "mkdir ",
            "rmdir ",
            "touch ",
            "truncate ",
            "echo ",
            "printf ",
            "tee ",
            "dd ",
            "install ",
            "ln ",
            "unlink ",
            "make ",
            "cmake ",
            "gradle ",
            "./gradlew",
            "npm ",
            "pnpm ",
            "yarn ",
            "pip ",
            "poetry ",
            "cargo ",
            "go build",
            "go test",
            "javac ",
            "kotlinc ",
            "git apply",
            "git am",
            "git cherry-pick",
            "git checkout ",
            "git clean",
            "git commit",
            "git merge",
            "git pull",
            "git push",
            "git rebase",
            "git reset",
            "git restore"
        )
        return splitShellCommandSegments(command).any { segment ->
            val normalized = normalizeShellSegment(segment)
            containsShellWriteRedirection(normalized) ||
                mutationPrefixes.any { token ->
                    normalized == token || normalized.startsWith(token)
                }
        }
    }

    private fun splitShellCommandSegments(command: String): List<String> {
        return command
            .lowercase()
            .replace(Regex("""\s+"""), " ")
            .split(Regex("""\s*(?:&&|\|\||;|\|)\s*"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun normalizeShellSegment(segment: String): String {
        return segment
            .removePrefix("sudo ")
            .removePrefix("command ")
            .trim()
    }

    private fun containsShellWriteRedirection(segment: String): Boolean {
        return Regex("""(^|[^<])>>?($|\s)""").containsMatchIn(segment) ||
            Regex("""\d>>?""").containsMatchIn(segment) ||
            " | tee" in segment ||
            segment.startsWith("tee ")
    }

    private fun buildShellInspectionPreview(
        command: String,
        payload: String
    ): String? {
        val normalizedSegments = splitShellCommandSegments(command)
            .map(::normalizeShellSegment)
        val profile = when {
            normalizedSegments.any { segment ->
                segment.startsWith("cat ") ||
                    segment.startsWith("sed ") ||
                    segment.startsWith("sed -n") ||
                    segment.startsWith("awk ") ||
                    segment.startsWith("grep ") ||
                    segment.startsWith("rg ") ||
                    segment.startsWith("git show")
            } -> PreviewProfile(maxLines = 12, maxChars = 640)
            normalizedSegments.any { segment ->
                segment == "ls" ||
                    segment.startsWith("ls ") ||
                    segment.startsWith("find ") ||
                    segment.startsWith("tree") ||
                    segment.startsWith("du ") ||
                    segment.startsWith("wc ")
            } -> PreviewProfile(maxLines = 8, maxChars = 320)
            normalizedSegments.any { segment ->
                segment.startsWith("git status") ||
                    segment.startsWith("git diff") ||
                    segment.startsWith("git log") ||
                    segment.startsWith("git branch") ||
                    segment.startsWith("git ls-files") ||
                    segment.startsWith("git grep") ||
                    segment.startsWith("git rev-parse") ||
                    segment.startsWith("git remote -v")
            } -> PreviewProfile(maxLines = 8, maxChars = 360)
            else -> PreviewProfile(maxLines = 10, maxChars = 520)
        }
        return buildHistoryContentPreview(
            text = payload,
            maxLines = profile.maxLines,
            maxChars = profile.maxChars
        )
    }

    private fun buildHistoryContentPreview(
        text: String,
        maxLines: Int,
        maxChars: Int
    ): String? {
        val normalized = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(maxLines)
            .joinToString(" | ")
            .trim()
        if (normalized.isBlank()) return null
        return if (normalized.length > maxChars) {
            normalized.take(maxChars) + "..."
        } else {
            normalized
        }
    }

    private data class PreviewProfile(
        val maxLines: Int,
        val maxChars: Int
    )

    private fun summarizeGenericToolPayload(payload: String): String {
        val firstMeaningfulLine = payload.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return "已返回结果，原始输出已省略。"
        return if (firstMeaningfulLine.length > 240) {
            firstMeaningfulLine.take(240) + "..."
        } else {
            firstMeaningfulLine
        }
    }

    private fun summarizeToolChangeBlock(message: String): String? {
        val block = message.substringAfter("\n\n本次文件变更:\n", "")
        if (block.isBlank()) return null
        val changes = block.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .toList()
        if (changes.isEmpty()) return null
        return "文件变更: " + changes.take(3).joinToString("；") { it.removePrefix("- ").trim() }
    }

    private fun isToolResultHistoryMessage(message: ChatMessageUi): Boolean {
        return message.role == "tool_exec" && !message.content.startsWith("🔧 正在执行:")
    }

    private fun buildCheckpointSummary(
        toolName: String,
        records: List<FileChangeRecordUi>
    ): String {
        val paths = records.map { it.path.substringAfterLast('/').substringAfterLast('\\') }
        return "$toolName 修改了 ${records.size} 个文件: ${paths.take(3).joinToString("、")}" +
            if (paths.size > 3) " 等" else ""
    }

    private fun formatOperationLabel(operation: String): String {
        return when (operation) {
            "create" -> "新建"
            "delete" -> "删除"
            "rollback" -> "回滚"
            "search_replace" -> "替换"
            "write" -> "写入"
            else -> operation
        }
    }

    private fun buildExportFileName(title: String, format: ConversationExportFormat): String {
        val sanitizedTitle = title
            .ifBlank { "conversation" }
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_')
            .ifBlank { "conversation" }
            .take(40)
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
            .format(Date())
        return "${sanitizedTitle}_${timestamp}.${format.extension}"
    }

    private fun buildMarkdownExport(session: PersistedSession): String {
        val finalReadinessAuditLines = buildFinalReadinessAuditExportLines(
            conversationStore.restoreFinalReadinessAuditRecords(session.recentFinalReadinessAudits)
        )
        val header = buildList {
            add("# ${session.title.ifBlank { "新对话" }}")
            add("")
            add("- 会话 ID: `${session.id}`")
            add("- 导出时间: ${formatTimestamp(System.currentTimeMillis())}")
            add("- Provider: `${session.providerId}`")
            add("- 模型: `${session.modelName}`")
            session.projectPath?.takeIf { it.isNotBlank() }?.let {
                add("- 项目路径: `$it`")
            }
            if (session.usageSummary.totalTokens > 0) {
                add("- Token: ${session.usageSummary.totalTokens}")
                add(
                    "- 预估成本: ${
                        formatCurrencyAmount(
                            session.usageSummary.resolvedEstimatedCostAmount(),
                            session.usageSummary.resolvedEstimatedCostCurrency()
                        )
                    }"
                )
            }
            if (session.compressionSnapshots.isNotEmpty()) {
                add("- 压缩摘要版本数: ${session.compressionSnapshots.size}")
            }
            session.compressionSnapshot?.takeIf { it.active }?.let {
                add("- 已启用上下文压缩: 是")
                add("- 压缩消息数: ${it.sourceMessageCount}")
                add("- 当前摘要版本: V${it.version}")
            }
            addAll(finalReadinessAuditLines)
            add("")
            add("---")
            add("")
        }

        val messageBlocks = session.messages.map { message ->
            buildString {
                append("## ")
                append(messageRoleLabel(message.role))
                append('\n')
                append('\n')
                if (!message.reasoning.isNullOrBlank()) {
                    append("### 思考过程\n\n")
                    append(message.reasoning.trim())
                    append('\n')
                    append('\n')
                }
                if (message.content.isNotBlank()) {
                    append(message.content.trim())
                    append('\n')
                    append('\n')
                }
                append("_时间: ")
                append(formatTimestamp(message.timestamp))
                append("_")
            }.trim()
        }

        return SensitiveDataSanitizer.sanitizeText(
            (header + messageBlocks).joinToString("\n\n").trim()
        )
    }

    private suspend fun generateCompressionSummary(
        state: SessionState,
        messages: List<ChatMessageUi>
    ): String {
        val previousSummary = state.compressionSnapshot
            ?.summary
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
        val localSummary = mergeCompressionSummaries(
            previousSummary = previousSummary,
            latestSummary = buildCompressionSummary(state, messages)
        )
        val config = runCatching { configRepository.getConfig() }
            .getOrElse { lastSessionConfig }
        val apiKey = config.getActiveApiKey().trim()
        if (apiKey.isBlank()) return localSummary

        val provider = ProviderRegistry.getActiveProvider(config.activeProviderId)
        val response = runCatching {
            callProviderWithConfiguredStreaming(
                provider = provider,
                config = config,
                request = ChatRequest(
                    messages = buildCompressionSummaryPrompt(state, messages, localSummary),
                    model = config.getActiveModel(),
                    temperature = 0.2,
                    maxTokens = minOf(config.maxTokens, COMPRESSION_SUMMARY_MAX_TOKENS),
                    stream = config.isStreamingResponsesEnabled(),
                    reasoningEffort = config.getActiveReasoningEffort(),
                    thinkingMode = config.getActiveThinkingMode(),
                    tools = null
                )
            )
        }.getOrNull()

        val content = response?.content?.trim().orEmpty()
        return if (content.isNotBlank() && content.length >= MIN_MODEL_SUMMARY_LENGTH) {
            content
        } else {
            localSummary
        }
    }

    private suspend fun callProviderWithConfiguredStreaming(
        provider: com.murong.agent.core.provider.ModelProvider,
        config: ProviderConfig,
        request: ChatRequest
    ) = if (request.stream) {
        provider.chatStream(
            request = request,
            apiKey = config.getActiveApiKey(),
            baseUrl = config.getActiveBaseUrl(),
            onDelta = {}
        )
    } else {
        provider.chat(
            request = request,
            apiKey = config.getActiveApiKey(),
            baseUrl = config.getActiveBaseUrl()
        )
    }

    private fun buildCompressionSummary(
        state: SessionState,
        messages: List<ChatMessageUi>
    ): String {
        val userMessages = messages.filter { it.role == "user" }
        val resultMessages = messages.filter { it.role == "assistant" || it.role == "tool_exec" || it.role == "subagent" }
        val systemMessages = messages.filter { it.role == "system" }

        val goal = userMessages.firstOrNull { it.content.isNotBlank() }
            ?.content
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        val latestUserNeed = userMessages.lastOrNull { it.content.isNotBlank() }
            ?.content
            ?.normalizeForSummary()
            .orEmpty()

        val userNeeds = userMessages
            .map { it.content.normalizeForSummary() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)

        val findings = resultMessages
            .mapNotNull(::summarizeMessageForCompression)
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
        val latestFinalReadinessAuditSummary = buildLatestFinalReadinessAuditSummary(
            state.recentFinalReadinessAudits
        )
        val risks = buildList {
            addAll(
                systemMessages
                    .map { it.content.normalizeForSummary() }
                    .filter { it.contains("失败") || it.contains("错误") || it.contains("超时") || it.contains("拒绝") }
                    .take(3)
            )
            if (state.pendingApproval != null) {
                add("当前会话仍可能遇到审批确认，执行高风险操作前需要用户明确批准。")
            }
            latestFinalReadinessAuditSummary?.let { auditSummary ->
                add("最近一次最终收口状态：$auditSummary。")
            }
        }.distinct()
        val nextSteps = buildList {
            if (latestUserNeed.isNotBlank()) {
                add("优先继续处理用户最近要求: $latestUserNeed")
            }
            if (state.projectPath.isNullOrBlank().not()) {
                add("继续结合当前项目路径和最近未压缩消息推进实现或排查。")
            }
            add("如果历史细节不足，先向用户确认再继续执行。")
        }.distinct()
        val currentStage = when {
            resultMessages.isNotEmpty() && userMessages.size >= 2 -> "已完成多轮分析与执行，进入阶段性收束和继续推进"
            resultMessages.isNotEmpty() -> "已完成首轮分析或执行，准备继续推进后续问题"
            else -> "主要处于需求梳理与方案确认阶段"
        }

        val summary = buildString {
            appendLine("【上下文压缩摘要】")
            appendLine("会话标题: ${state.sessionTitle.ifBlank { "新对话" }}")
            state.projectPath?.takeIf { it.isNotBlank() }?.let {
                appendLine("项目路径: $it")
            }
            appendLine("当前阶段: $currentStage")
            appendLine("压缩消息数: ${messages.size}")
            if (goal.isNotBlank()) {
                appendLine("初始任务: ${goal.take(160)}")
            }
            if (userNeeds.isNotEmpty()) {
                appendLine("关键用户需求:")
                userNeeds.forEach { appendLine("- $it") }
            }
            if (findings.isNotEmpty()) {
                appendLine("已完成工作与重要结论:")
                findings.forEach { appendLine("- $it") }
            }
            if (risks.isNotEmpty()) {
                appendLine("风险与注意事项:")
                risks.forEach { appendLine("- $it") }
            }
            if (nextSteps.isNotEmpty()) {
                appendLine("待继续事项:")
                nextSteps.forEach { appendLine("- $it") }
            }
            appendLine("续聊要求:")
            appendLine("- 继续遵守现有规则、记忆、Skills、审批限制。")
            appendLine("- 结合这个摘要和最近未压缩消息继续工作。")
            appendLine("- 如果历史细节不足，优先询问用户，不要编造。")
        }.trim()
        return applyPreCompactHookToSummary(
            sessionId = state.sessionId,
            messageCount = messages.size,
            summary = summary,
            hookBus = hookBus
        )
    }

    private fun buildCompressionSummaryPrompt(
        state: SessionState,
        messages: List<ChatMessageUi>,
        localSummary: String
    ): List<ChatMessage> {
        return listOf(
            ChatMessage(
                role = "system",
                content = buildCompressionSystemPrompt()
            ),
            ChatMessage(
                role = "user",
                content = buildCompressionSourceMaterial(state, messages, localSummary)
            )
        )
    }

    private fun buildCompressionSystemPrompt(): String {
        return """
你是一名会话压缩摘要助手。你的任务是根据给定会话材料，输出一份高质量的中文续聊摘要，供后续编码代理继续工作。

必须遵守：
1. 只根据提供材料总结，不要编造不存在的历史。
2. 如果存在上一版摘要，要继承其中仍然有效的结论，并把本轮新增进展合并进去，删除重复项和明显过时内容。
3. 保留任务目标、已完成工作、关键结论、风险、待继续事项。
4. 如果材料里出现项目路径、关键文件、规则、审批偏好、记忆或 Skills，要明确写入。
5. 输出尽量结构化、简洁、可续聊，适合直接作为系统上下文。
6. 不要写寒暄，不要解释你在做摘要。

输出格式固定为：
【上下文压缩摘要】
会话标题: ...
项目路径: ...
当前阶段: ...
关键用户需求:
- ...
已完成工作与重要结论:
- ...
风险与注意事项:
- ...
待继续事项:
- ...
续聊要求:
- ...
        """.trim()
    }

    private fun buildCompressionSourceMaterial(
        state: SessionState,
        messages: List<ChatMessageUi>,
        localSummary: String
    ): String {
        val transcript = messages.joinToString("\n\n") { message ->
            val summarizedContent = summarizeMessageForCompressionSource(message)
            buildString {
                append("[")
                append(messageRoleLabel(message.role))
                append("] ")
                append(summarizedContent)
                if (!message.reasoning.isNullOrBlank()) {
                    append("\n思考补充: ")
                    append(message.reasoning.normalizeForSummary(MODEL_SUMMARY_REASONING_MAX_LENGTH))
                }
            }
        }

        val currentSummary = state.compressionSnapshot
            ?.takeIf { it.summary.isNotBlank() }
            ?.summary
            ?.take(MAX_PREVIOUS_SUMMARY_LENGTH)
            .orEmpty()
        val latestFinalReadinessAuditSummary = buildLatestFinalReadinessAuditSummary(
            state.recentFinalReadinessAudits
        )

        return buildString {
            appendLine("会话标题: ${state.sessionTitle.ifBlank { "新对话" }}")
            state.projectPath?.takeIf { it.isNotBlank() }?.let {
                appendLine("项目路径: $it")
            }
            appendLine("累计消息数: ${state.messages.size}")
            appendLine("本次待压缩消息数: ${messages.size}")
            appendLine("本会话累计 Token: ${state.usageSummary.totalTokens}")
            latestFinalReadinessAuditSummary?.let { auditSummary ->
                appendLine("最近一次最终收口状态: $auditSummary")
            }
            if (currentSummary.isNotBlank()) {
                appendLine()
                appendLine("上一版压缩摘要（需要合并仍然有效的结论）:")
                appendLine(currentSummary)
            }
            appendLine()
            appendLine("本地合并摘要草稿:")
            appendLine(localSummary.take(MAX_LOCAL_SUMMARY_DRAFT_LENGTH))
            appendLine()
            appendLine("待压缩历史消息摘录:")
            appendLine(transcript.take(MAX_COMPRESSION_SOURCE_LENGTH))
        }.trim()
    }

    private fun mergeCompressionSummaries(
        previousSummary: String,
        latestSummary: String
    ): String {
        if (previousSummary.isBlank()) return latestSummary

        val previous = parseCompressionSummary(previousSummary)
        val latest = parseCompressionSummary(latestSummary)
        val merged = CompressionSummaryParts(
            sessionTitle = latest.sessionTitle.ifBlank { previous.sessionTitle },
            projectPath = latest.projectPath.ifBlank { previous.projectPath },
            currentStage = mergeSummaryValue(previous.currentStage, latest.currentStage),
            initialTask = mergeSummaryValue(previous.initialTask, latest.initialTask),
            keyUserNeeds = mergeSummaryItems(previous.keyUserNeeds, latest.keyUserNeeds),
            findings = mergeSummaryItems(previous.findings, latest.findings),
            risks = mergeSummaryItems(previous.risks, latest.risks),
            nextSteps = mergeSummaryItems(previous.nextSteps, latest.nextSteps),
            continueRequirements = mergeSummaryItems(
                previous.continueRequirements,
                latest.continueRequirements
            )
        )
        return formatCompressionSummary(merged)
    }

    private fun parseCompressionSummary(summary: String): CompressionSummaryParts {
        var currentSection: String? = null
        var sessionTitle = ""
        var projectPath = ""
        var currentStage = ""
        var initialTask = ""
        val keyUserNeeds = mutableListOf<String>()
        val findings = mutableListOf<String>()
        val risks = mutableListOf<String>()
        val nextSteps = mutableListOf<String>()
        val continueRequirements = mutableListOf<String>()

        summary.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line == "【上下文压缩摘要】") return@forEach
            when {
                line.startsWith("会话标题:") -> {
                    sessionTitle = line.substringAfter("会话标题:").trim()
                    currentSection = null
                }
                line.startsWith("项目路径:") -> {
                    projectPath = line.substringAfter("项目路径:").trim()
                    currentSection = null
                }
                line.startsWith("当前阶段:") -> {
                    currentStage = line.substringAfter("当前阶段:").trim()
                    currentSection = null
                }
                line.startsWith("初始任务:") -> {
                    initialTask = line.substringAfter("初始任务:").trim()
                    currentSection = null
                }
                line.startsWith("关键用户需求:") -> currentSection = "needs"
                line.startsWith("已完成工作与重要结论:") -> currentSection = "findings"
                line.startsWith("风险与注意事项:") -> currentSection = "risks"
                line.startsWith("待继续事项:") -> currentSection = "next"
                line.startsWith("续聊要求:") -> currentSection = "continue"
                line.startsWith("- ") -> {
                    val item = line.removePrefix("- ").trim()
                    if (item.isBlank()) return@forEach
                    when (currentSection) {
                        "needs" -> keyUserNeeds.add(item)
                        "findings" -> findings.add(item)
                        "risks" -> risks.add(item)
                        "next" -> nextSteps.add(item)
                        "continue" -> continueRequirements.add(item)
                    }
                }
            }
        }

        return CompressionSummaryParts(
            sessionTitle = sessionTitle,
            projectPath = projectPath,
            currentStage = currentStage,
            initialTask = initialTask,
            keyUserNeeds = keyUserNeeds,
            findings = findings,
            risks = risks,
            nextSteps = nextSteps,
            continueRequirements = continueRequirements
        )
    }

    private fun formatCompressionSummary(parts: CompressionSummaryParts): String {
        val mergedNeeds = mergeSummaryItems(
            if (parts.initialTask.isBlank()) emptyList() else listOf(parts.initialTask),
            parts.keyUserNeeds
        )
        return buildString {
            appendLine("【上下文压缩摘要】")
            appendLine("会话标题: ${parts.sessionTitle.ifBlank { "新对话" }}")
            if (parts.projectPath.isNotBlank()) {
                appendLine("项目路径: ${parts.projectPath}")
            }
            if (parts.currentStage.isNotBlank()) {
                appendLine("当前阶段: ${parts.currentStage}")
            }
            if (parts.initialTask.isNotBlank()) {
                appendLine("初始任务: ${parts.initialTask}")
            }
            if (mergedNeeds.isNotEmpty()) {
                appendLine("关键用户需求:")
                mergedNeeds.forEach { appendLine("- $it") }
            }
            if (parts.findings.isNotEmpty()) {
                appendLine("已完成工作与重要结论:")
                parts.findings.forEach { appendLine("- $it") }
            }
            if (parts.risks.isNotEmpty()) {
                appendLine("风险与注意事项:")
                parts.risks.forEach { appendLine("- $it") }
            }
            if (parts.nextSteps.isNotEmpty()) {
                appendLine("待继续事项:")
                parts.nextSteps.forEach { appendLine("- $it") }
            }
            appendLine("续聊要求:")
            mergeSummaryItems(
                parts.continueRequirements,
                DEFAULT_COMPRESSION_CONTINUE_REQUIREMENTS
            ).forEach { appendLine("- $it") }
        }.trim()
    }

    private fun mergeSummaryItems(
        first: List<String>,
        second: List<String>
    ): List<String> {
        return (first + second)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeSummaryKey(it) }
    }

    private fun mergeSummaryValue(previous: String, latest: String): String {
        val prev = previous.trim()
        val next = latest.trim()
        return when {
            next.isBlank() -> prev
            prev.isBlank() -> next
            normalizeSummaryKey(prev) == normalizeSummaryKey(next) -> next
            else -> "$prev；$next"
        }
    }

    private fun normalizeSummaryKey(value: String): String {
        return value.lowercase()
            .replace(Regex("\\s+"), "")
            .replace("，", ",")
            .replace("。", "")
            .replace("；", ";")
    }

    private fun String?.toClarificationSource(): ClarificationSource {
        return when (this?.trim()?.uppercase()) {
            "AUTO_ROUTE" -> ClarificationSource.AUTO_ROUTE
            "AUTO_INTERRUPT" -> ClarificationSource.AUTO_INTERRUPT
            else -> ClarificationSource.MANUAL
        }
    }

    private fun String.normalizeForSummary(maxLength: Int = 180): String {
        return replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)
    }

    private fun summarizeMessageForCompression(message: ChatMessageUi): String? {
        return when (message.role) {
            "tool_exec" -> summarizeToolResultForHistory(message.content)
                ?.normalizeForSummary(220)
            "subagent" -> summarizeSubagentMessageForCompression(message.content)
            "assistant" -> message.content
                .ifBlank { message.reasoning.orEmpty() }
                .normalizeForSummary()
                .takeIf { it.isNotBlank() }
            else -> message.content.normalizeForSummary().takeIf { it.isNotBlank() }
        }
    }

    private fun summarizeMessageForCompressionSource(message: ChatMessageUi): String {
        return when (message.role) {
            "tool_exec" -> summarizeToolResultForHistory(message.content)
                ?.normalizeForSummary(MODEL_SUMMARY_MESSAGE_MAX_LENGTH)
                ?: message.content.normalizeForSummary(MODEL_SUMMARY_MESSAGE_MAX_LENGTH)
            "subagent" -> summarizeSubagentMessageForCompression(message.content)
                ?.normalizeForSummary(MODEL_SUMMARY_MESSAGE_MAX_LENGTH)
                ?: message.content.normalizeForSummary(MODEL_SUMMARY_MESSAGE_MAX_LENGTH)
            "assistant" -> message.content
                .ifBlank { message.reasoning.orEmpty() }
                .normalizeForSummary(MODEL_SUMMARY_MESSAGE_MAX_LENGTH)
            else -> message.content.normalizeForSummary(MODEL_SUMMARY_MESSAGE_MAX_LENGTH)
        }
    }

    private fun summarizeSubagentMessageForCompression(content: String): String? {
        if (content.isBlank()) return null
        val summaryBlock = content.substringAfter("### 汇总结论", "")
            .takeIf { it.isNotBlank() }
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() }
        if (!summaryBlock.isNullOrBlank()) {
            return "子代理结论: ${summaryBlock.normalizeForSummary(220)}"
        }
        val firstMeaningfulLine = content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return firstMeaningfulLine.normalizeForSummary(220)
    }

    private fun replaceCompressionSnapshot(
        snapshots: List<ContextCompressionUi>,
        updatedSnapshot: ContextCompressionUi
    ): List<ContextCompressionUi> {
        return snapshots.map { snapshot ->
            if (snapshot.id == updatedSnapshot.id) {
                updatedSnapshot
            } else {
                snapshot
            }
        }
    }

    private fun messageRoleLabel(role: String): String = when (role) {
        "user" -> "用户"
        "assistant" -> "助手"
        "tool_exec" -> "工具"
        "subagent" -> "子代理"
        "system" -> "系统"
        else -> role
    }

    private fun formatTimestamp(timestamp: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }
}

private fun String?.toWorkflowPlanStatusUi(): WorkflowPlanStatusUi {
    return when (this?.uppercase(Locale.ROOT)) {
        WorkflowPlanStatusUi.EXECUTING.name -> WorkflowPlanStatusUi.EXECUTING
        WorkflowPlanStatusUi.BLOCKED.name -> WorkflowPlanStatusUi.BLOCKED
        WorkflowPlanStatusUi.COMPLETED.name -> WorkflowPlanStatusUi.COMPLETED
        else -> WorkflowPlanStatusUi.READY
    }
}

private fun workflowPlanStatusLabel(status: WorkflowPlanStatusUi): String {
    return when (status) {
        WorkflowPlanStatusUi.READY -> "待执行"
        WorkflowPlanStatusUi.EXECUTING -> "执行中"
        WorkflowPlanStatusUi.BLOCKED -> "已阻塞"
        WorkflowPlanStatusUi.COMPLETED -> "已完成"
    }
}

const val RECENT_MESSAGES_TO_KEEP = 8
const val MIN_MESSAGES_TO_COMPRESS = 6
const val MIN_MESSAGES_FOR_COMPRESSION = 12

private const val SUMMARY_MIN_TOKENS = 120
private const val SUMMARY_MAX_TOKENS = 960
private const val COMPRESSION_SUMMARY_MAX_TOKENS = 1600
private const val MIN_MODEL_SUMMARY_LENGTH = 120
private const val MODEL_SUMMARY_MESSAGE_MAX_LENGTH = 260
private const val MODEL_SUMMARY_REASONING_MAX_LENGTH = 140
private const val MAX_PREVIOUS_SUMMARY_LENGTH = 1800
private const val MAX_LOCAL_SUMMARY_DRAFT_LENGTH = 2200
private const val MAX_COMPRESSION_SOURCE_LENGTH = 8000
private const val MAX_FILE_CHANGE_HISTORY = 60
private const val MAX_CHECKPOINT_HISTORY = 24
private const val MAX_MENTIONED_FILES_PER_REQUEST = 4
private const val MAX_MENTION_FILE_CHARS = 2500
private const val MAX_MENTION_TOTAL_CHARS = 7000
private const val MAX_MENTION_FILE_SCAN_DEPTH = 3
private const val MAX_MENTION_DIRECTORY_ENTRIES = 80
internal const val MAX_CLARIFICATION_TURNS = 3
private const val MAX_TOOL_RESULTS_IN_HISTORY = 4
private const val TOOL_RESULT_RECENT_MESSAGE_WINDOW = 10
private const val MAX_COMBINED_TOOL_SUMMARY_ITEMS = 6
private const val AUTO_COMPRESSION_TRIGGER_TOKENS = 6000
private const val AUTO_COMPRESSION_MIN_COMPRESSIBLE_MESSAGES = 14
private const val AUTO_COMPRESSION_MIN_SAVED_TOKENS = 900
private const val AUTO_COMPRESSION_MIN_REDUCTION_PERCENT = 22
private const val AUTO_COMPRESSION_NEW_MESSAGES_THRESHOLD = 8
private const val AUTO_COMPRESSION_ENABLE_EXISTING_MAX_NEW_MESSAGES = 4
private const val STREAMING_FLUSH_INTERVAL_MS = 40L
private val DEFAULT_COMPRESSION_CONTINUE_REQUIREMENTS = listOf(
    "继续遵守现有规则、记忆、Skills、审批限制。",
    "结合这个摘要和最近未压缩消息继续工作。",
    "如果历史细节不足，优先询问用户，不要编造。"
)

private fun compressionEligibleMessages(state: SessionState): List<ChatMessageUi> {
    return state.messages.filter { message ->
        (message.role == "user" || message.role == "assistant") &&
            (message.content.isNotBlank() || !message.reasoning.isNullOrBlank())
    }
}

private fun estimateMessagesTokenCount(messages: List<ChatMessageUi>): Int {
    val chars = messages.sumOf { message ->
        message.content.length + (message.reasoning?.length ?: 0)
    }
    val tokenEstimateFromChars = (chars / 4.0).roundToInt()
    val tokenEstimateFromMessages = messages.size * 18
    return max(tokenEstimateFromChars, tokenEstimateFromMessages)
}

private fun estimateSummaryTokenCount(messages: List<ChatMessageUi>): Int {
    val sourceTokens = estimateMessagesTokenCount(messages)
    val ratioEstimate = (sourceTokens * 0.18f).roundToInt()
    val structureEstimate = messages.size * 18
    return max(ratioEstimate, structureEstimate)
        .coerceIn(SUMMARY_MIN_TOKENS, SUMMARY_MAX_TOKENS)
}
