package com.murong.agent.core.loop

import android.content.Context
import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.GlobalRule
import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.ProjectToolPreferences
import com.murong.agent.core.tool.ApprovalRiskLevel
import com.murong.agent.core.tool.StepSignOffReceipt
import com.murong.agent.core.tool.ToolStructuredPayload
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt

/**
 * 可序列化的消息（用于持久化）
 */
@Serializable
data class UsageSummarySnapshot(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val promptCacheHitTokens: Int = 0,
    val promptCacheMissTokens: Int = 0,
    val lastTurnPromptCacheHitTokens: Int = 0,
    val lastTurnPromptCacheMissTokens: Int = 0,
    val lastCachePrefixHash: String? = null,
    val lastCacheStableSystemHash: String? = null,
    val lastCacheToolsHash: String? = null,
    val lastCacheCompressionHash: String? = null,
    val lastCacheProjectContextHash: String? = null,
    val lastCacheSessionGoalHash: String? = null,
    val lastCacheProjectSkillsHash: String? = null,
    val lastCacheMcpToolsHash: String? = null,
    val lastCachePlanModeHash: String? = null,
    val lastCachePrefixChanged: Boolean = false,
    val lastCachePrefixChangeReasons: List<String> = emptyList(),
    val estimatedCostAmount: Double = 0.0,
    val estimatedCostCurrency: String = "USD",
    val estimatedCostUsd: Double = 0.0
) {
    fun resolvedEstimatedCostAmount(): Double {
        return if (estimatedCostAmount != 0.0 || estimatedCostCurrency.uppercase() != "USD") {
            estimatedCostAmount
        } else {
            estimatedCostUsd
        }
    }

    fun resolvedEstimatedCostCurrency(): String {
        return estimatedCostCurrency.ifBlank { "USD" }.uppercase()
    }
}

@Serializable
data class PersistedMessage(
    val id: Long,
    val role: String,
    val content: String,
    val imageAttachments: List<PersistedMessageImageAttachment> = emptyList(),
    val reasoning: String? = null,
    val subagentRunId: String? = null,
    val subagentBatchId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class PersistedMessageImageAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val localCachePath: String,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null
)

@Serializable
data class PersistedSubagentRun(
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

@Serializable
data class PersistedSubagentBatch(
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
    val timeline: List<PersistedSubagentTimelineEntry> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)

@Serializable
data class PersistedBackgroundJob(
    val jobId: String,
    val toolName: String,
    val title: String,
    val summary: String,
    val detail: String = "",
    val status: String,
    val statusMessage: String = "",
    val resultPreview: String? = null,
    val queuePosition: Int? = null,
    val assignedSlot: Int? = null,
    val concurrencyLimit: Int? = null,
    val timeoutSeconds: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null
)

@Serializable
data class PersistedSubagentTimelineEntry(
    val id: String,
    val timestamp: Long,
    val type: String,
    val title: String,
    val detail: String = "",
    val relatedRunId: String? = null
)

@Serializable
data class PersistedCompressionSnapshot(
    val id: String,
    val version: Int = 1,
    val summary: String,
    val sourceMessageCount: Int,
    val sourceEndMessageId: Long,
    val sourceEndMessageIndex: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val active: Boolean = true
)

@Serializable
data class PersistedFileChangeRecord(
    val id: String,
    val path: String,
    val operation: String,
    val beforeContent: String? = null,
    val afterContent: String? = null,
    val diffPreview: String = "",
    val changedAt: Long = System.currentTimeMillis(),
    val checkpointId: String? = null
)

@Serializable
data class PersistedErrorRecord(
    val id: String,
    val message: String,
    val kind: String = ErrorRecordKind.GENERAL.name,
    val timestamp: Long
)

@Serializable
data class PersistedStepSignOffReceipt(
    val reportedStep: String,
    val resultSummary: String,
    val matchedEvidenceCount: Int,
    val totalEvidenceCount: Int,
    val matchedToolNames: List<String> = emptyList(),
    val matchedSessionHistorySessionIds: List<String> = emptyList(),
    val matchedSessionHistoryMessageReferences: List<String> = emptyList(),
    val signOffTimestamp: Long,
    val workflowStepIndex: Int? = null,
    val workflowStep: String? = null,
    val workflowTotalSteps: Int? = null
)

@Serializable
data class PersistedToolCallRecord(
    val id: String,
    val toolName: String,
    val args: String,
    val result: String? = null,
    val isSuccess: Boolean = true,
    val stepSignOffReceipt: PersistedStepSignOffReceipt? = null,
    val structuredPayload: ToolStructuredPayload? = null,
    val timestamp: Long
)

@Serializable
data class PersistedCheckpointRecoveryRecord(
    val id: String,
    val checkpointId: String,
    val checkpointSummary: String,
    val scope: String,
    val restoredFileCount: Int = 0,
    val targetMessageIndex: Int,
    val timestamp: Long
)

@Serializable
data class PersistedFinalReadinessAuditRecord(
    val result: String,
    val recovered: Boolean = false,
    val receiptKind: String,
    val requiredAction: String,
    val latestSuccessfulWriteToolName: String? = null,
    val remainingUnsignedSteps: Int? = null,
    val nextRequiredStep: String? = null,
    val latestSignedOffStep: String? = null,
    val latestSignedOffMatchedTools: List<String> = emptyList(),
    val latestSignedOffSessionHistorySessionIds: List<String> = emptyList(),
    val latestSignedOffSessionHistoryMessageReferences: List<String> = emptyList()
)

@Serializable
data class PersistedApprovalRecord(
    val id: String,
    val toolName: String,
    val summary: String,
    val decision: String,
    val scopeSummary: String? = null,
    val explanationLabel: String? = null,
    val explanationDetail: String? = null,
    val timestamp: Long
)

@Serializable
data class PersistedApprovalInvalidationRecord(
    val id: String,
    val summary: String,
    val sourceLabel: String,
    val sourceDetail: String? = null,
    val reasonLabel: String,
    val reasonDetail: String,
    val timestamp: Long
)

@Serializable
data class PersistedConversationCheckpoint(
    val id: String,
    val messageIndex: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val summary: String,
    val changedFiles: List<String> = emptyList(),
    val kind: String = ConversationCheckpointKind.FILE_TURN.name,
    val scope: String = ConversationCheckpointScope.CODE.name,
    val source: String = ConversationCheckpointSource.TOOL_EXECUTION.name,
    val toolNames: List<String> = emptyList(),
    val promptSnapshot: PersistedConversationCheckpointPromptSnapshot? = null
)

@Serializable
data class PersistedConversationCheckpointPromptSnapshot(
    val pendingAskRequest: PersistedPendingAskRequest? = null,
    val pendingWorkflowPlan: PersistedWorkflowPlan? = null,
    val canonicalWorkflowPlan: PersistedWorkflowPlan? = null,
    val pendingClarificationRequest: PersistedClarificationRequest? = null
)

@Serializable
data class PersistedCheckpointStore(
    val checkpoints: List<PersistedConversationCheckpoint> = emptyList(),
    val fileChanges: List<PersistedFileChangeRecord> = emptyList()
)

@Serializable
data class PersistedWorkflowStepSignOff(
    val stepIndex: Int,
    val step: String,
    val reportedStep: String,
    val resultSummary: String,
    val matchedEvidenceCount: Int = 0,
    val totalEvidenceCount: Int = 0,
    val matchedToolNames: List<String> = emptyList(),
    val matchedSessionHistorySessionIds: List<String> = emptyList(),
    val matchedSessionHistoryMessageReferences: List<String> = emptyList(),
    val signedOffAt: Long = System.currentTimeMillis()
)

@Serializable
data class PersistedWorkflowPlan(
    val id: String,
    val goal: String,
    val summary: String,
    val steps: List<String> = emptyList(),
    val stageLabels: List<String> = emptyList(),
    val currentStageIndex: Int = 0,
    val currentStepIndex: Int = 0,
    val nextStepHint: String = "",
    val status: String = "READY",
    val mentionedFiles: List<PersistedFileMention> = emptyList(),
    val stepSignOffs: List<PersistedWorkflowStepSignOff> = emptyList(),
    val recentSessionHistoryClue: PersistedSessionHistoryReferenceClue? = null,
    val rawPlan: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class PersistedSessionHistoryReferenceClue(
    val queries: List<String> = emptyList(),
    val sessionIds: List<String> = emptyList(),
    val messageReferences: List<String> = emptyList(),
    val snippets: List<String> = emptyList(),
    val excerptWindows: List<String> = emptyList()
)

@Serializable
data class PersistedFileMention(
    val path: String,
    val displayPath: String,
    val inlineContent: String? = null
)

@Serializable
data class PersistedProjectKnowledgeSnapshot(
    val id: String,
    val name: String,
    val paths: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAppliedAt: Long? = null
)

@Serializable
data class PersistedMemoryUpdateSuggestion(
    val id: String,
    val title: String,
    val content: String,
    val scope: String,
    val reason: String,
    val sourceKind: String,
    val sourceUserMessageId: Long? = null,
    val sourceAssistantMessageId: Long? = null,
    val linkedMemoryId: String? = null,
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class PersistedRepoScopedProjectConfig(
    val projectRules: List<GlobalRule> = emptyList(),
    val projectMemories: List<GlobalMemory> = emptyList(),
    val projectSkills: List<GlobalSkill> = emptyList(),
    @Contextual val projectToolPreferences: ProjectToolPreferences? = null
)

@Serializable
data class PersistedClarificationAnswer(
    val question: String,
    val answer: String,
    val answeredAt: Long = System.currentTimeMillis()
)

@Serializable
data class PersistedClarificationRequest(
    val id: String,
    val goal: String,
    val question: String,
    val mentionedFiles: List<PersistedFileMention> = emptyList(),
    val previousAnswers: List<PersistedClarificationAnswer> = emptyList(),
    val turnIndex: Int = 1,
    val maxTurns: Int = 3,
    val source: String = "MANUAL",
    val recentSessionHistoryClue: PersistedSessionHistoryReferenceClue? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class PersistedAutoRouteDecision(
    val action: String,
    val reason: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class PersistedWorkflowFallback(
    val message: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class PersistedFinalReadinessReceipt(
    val kind: String,
    val requiredAction: String,
    val message: String,
    val latestSuccessfulWriteToolName: String? = null,
    val remainingUnsignedSteps: Int? = null,
    val nextRequiredStep: String? = null,
    val latestSignedOffStep: String? = null,
    val latestSignedOffResultSummary: String? = null,
    val latestSignedOffMatchedTools: List<String> = emptyList(),
    val latestSignedOffSessionHistorySessionIds: List<String> = emptyList(),
    val latestSignedOffSessionHistoryMessageReferences: List<String> = emptyList()
)

@Serializable
data class PersistedPendingApproval(
    val toolName: String,
    val summary: String,
    val detail: String,
    val rawArgs: String,
    val riskLevel: String,
    val explanationLabel: String? = null,
    val explanationDetail: String? = null
)

@Serializable
data class PersistedAskOption(
    val label: String,
    val description: String? = null
)

@Serializable
data class PersistedAskQuestion(
    val id: String,
    val header: String = "",
    val question: String,
    val options: List<PersistedAskOption> = emptyList(),
    val multiSelect: Boolean = false
)

@Serializable
data class PersistedPendingAskRequest(
    val id: String,
    val title: String = "需要确认",
    val questions: List<PersistedAskQuestion> = emptyList(),
    val recentSessionHistoryClue: PersistedSessionHistoryReferenceClue? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 可序列化的会话
 */
@Serializable
data class PersistedSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val providerId: String,
    val modelName: String,
    val sessionGoal: String? = null,
    val projectPath: String? = null,
    val remoteTaskRepositoryOwner: String? = null,
    val remoteTaskRepositoryName: String? = null,
    val remoteTaskRepositoryLabel: String? = null,
    val remoteTaskRepositoryEditable: Boolean = false,
    val workspaceMode: WorkspaceMode = WorkspaceMode.REMOTE_PREFERRED,
    val activeProjectScopePath: String? = null,
    val projectRules: List<GlobalRule> = emptyList(),
    val projectMemories: List<GlobalMemory> = emptyList(),
    val projectSkills: List<GlobalSkill> = emptyList(),
    val projectKnowledgePaths: List<String> = emptyList(),
    val projectKnowledgeSnapshots: List<PersistedProjectKnowledgeSnapshot> = emptyList(),
    @Contextual val projectToolPreferences: ProjectToolPreferences? = null,
    val repoScopedConfigs: Map<String, PersistedRepoScopedProjectConfig> = emptyMap(),
    val usageSummary: UsageSummarySnapshot = UsageSummarySnapshot(),
    val compressionSnapshot: PersistedCompressionSnapshot? = null,
    val compressionSnapshots: List<PersistedCompressionSnapshot> = emptyList(),
    val checkpoints: List<PersistedConversationCheckpoint> = emptyList(),
    val fileChanges: List<PersistedFileChangeRecord> = emptyList(),
    val subagentRuns: List<PersistedSubagentRun> = emptyList(),
    val subagentBatches: List<PersistedSubagentBatch> = emptyList(),
    val backgroundJobs: List<PersistedBackgroundJob> = emptyList(),
    val recentErrors: List<PersistedErrorRecord> = emptyList(),
    val recentToolCalls: List<PersistedToolCallRecord> = emptyList(),
    val recentMemoryUpdateSuggestions: List<PersistedMemoryUpdateSuggestion> = emptyList(),
    val recentRecoveryRecords: List<PersistedCheckpointRecoveryRecord> = emptyList(),
    val recentFinalReadinessAudits: List<PersistedFinalReadinessAuditRecord> = emptyList(),
    val recentApprovals: List<PersistedApprovalRecord> = emptyList(),
    val recentApprovalInvalidations: List<PersistedApprovalInvalidationRecord> = emptyList(),
    val approvedApprovalScopes: List<List<String>> = emptyList(),
    val approvedApprovalScopeEntries: List<PersistedApprovedApprovalScope> = emptyList(),
    val pendingApproval: PersistedPendingApproval? = null,
    val pendingAskRequest: PersistedPendingAskRequest? = null,
    val pendingWorkflowPlan: PersistedWorkflowPlan? = null,
    val canonicalWorkflowPlan: PersistedWorkflowPlan? = null,
    val pendingClarificationRequest: PersistedClarificationRequest? = null,
    val lastAutoRouteDecision: PersistedAutoRouteDecision? = null,
    val lastWorkflowFallback: PersistedWorkflowFallback? = null,
    val lastFinalReadinessReceipt: PersistedFinalReadinessReceipt? = null,
    val messages: List<PersistedMessage>
)

@Serializable
data class PersistedApprovedApprovalScope(
    val tokens: List<String> = emptyList(),
    val sourceKind: String = "current_session",
    val sourceSessionId: String? = null,
    val sourceSessionTitle: String? = null,
    val sourceSessionUpdatedAt: Long? = null,
    val importedAt: Long? = null
)

/**
 * 对话存储——将聊天历史持久化为本地 JSON 文件
 *
 * 存储位置: context.filesDir/conversations/<sessionId>.json
 * 索引文件: context.filesDir/conversations/index.json
 */
class ConversationStore internal constructor(
    private val conversationDir: File
) {
    constructor(context: Context) : this(File(context.filesDir, "conversations"))

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val sessionMutationLock = Any()
    private val deletedSessionIds = linkedSetOf<String>()
    private var deletedSessionIdsLoaded = false

    private val baseDir: File
        get() = conversationDir.also { it.mkdirs() }

    /**
     * 保存会话
     */
    fun saveSession(session: PersistedSession): Boolean = synchronized(sessionMutationLock) {
        if (session.id in deletedSessionIdsLocked()) {
            return false
        }
        val mainSession = session.copy(
            checkpoints = emptyList(),
            fileChanges = emptyList()
        )
        sessionFile(session.id).writeText(json.encodeToString(mainSession))
        checkpointStoreFile(session.id).writeText(
            json.encodeToString(
                PersistedCheckpointStore(
                    checkpoints = session.checkpoints,
                    fileChanges = session.fileChanges
                )
            )
        )
        updateIndex(session)
        true
    }

    /**
     * 加载会话
     */
    fun loadSession(sessionId: String): PersistedSession? = synchronized(sessionMutationLock) {
        if (sessionId in deletedSessionIdsLocked()) return null
        val file = sessionFile(sessionId)
        if (!file.exists()) return null
        try {
            val session = json.decodeFromString<PersistedSession>(file.readText())
            val checkpointStore = loadCheckpointStore(sessionId)
            if (checkpointStore != null) {
                session.copy(
                    checkpoints = checkpointStore.checkpoints,
                    fileChanges = checkpointStore.fileChanges
                )
            } else {
                session
            }
        } catch (e: Exception) {
            null
        }
    }

    fun renameSessionTitle(sessionId: String, newTitle: String): Boolean {
        val session = loadSession(sessionId) ?: return false
        saveSession(
            session.copy(
                title = newTitle,
                updatedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    fun encodeSession(session: PersistedSession): String {
        return json.encodeToString(session)
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String) = synchronized(sessionMutationLock) {
        loadSession(sessionId)?.let { session ->
            writeArchivedSessionsLocked(
                archivedSessionsLocked()
                    .filterNot { it.originalSessionId == sessionId }
                    .plus(buildArchivedSessionSummary(session))
            )
        }
        deletedSessionIdsLocked().add(sessionId)
        writeDeletedSessionIdsLocked()
        sessionFile(sessionId).delete()
        checkpointStoreFile(sessionId).delete()
        rebuildIndex()
    }

    fun listArchivedSessions(): List<ArchivedSessionSummary> = synchronized(sessionMutationLock) {
        archivedSessionsLocked().sortedByDescending { it.archivedAt }
    }

    fun loadArchivedSessionSummary(sessionId: String): ArchivedSessionSummary? = synchronized(sessionMutationLock) {
        archivedSessionsLocked().firstOrNull { it.originalSessionId == sessionId }
    }

    fun loadArchivedMemoryCandidate(sessionId: String): ArchivedMemoryCandidate? =
        synchronized(sessionMutationLock) {
            archivedSessionsLocked()
                .firstOrNull { it.originalSessionId == sessionId && it.isPendingMemoryCandidate() }
                ?.let(::buildArchivedMemoryCandidate)
        }

    fun listArchivedMemoryCandidates(limit: Int = 20): List<ArchivedMemoryCandidate> =
        synchronized(sessionMutationLock) {
            archivedSessionsLocked()
                .sortedByDescending { it.archivedAt }
                .filter { it.isPendingMemoryCandidate() }
                .map(::buildArchivedMemoryCandidate)
                .take(limit.coerceAtLeast(0))
        }

    fun markArchivedMemoryCandidateAccepted(
        sessionId: String,
        scope: ArchivedMemoryCandidateScope,
        memoryId: String,
        acceptedAt: Long = System.currentTimeMillis(),
        resolutionSource: ArchivedMemoryCandidateResolutionSource = ArchivedMemoryCandidateResolutionSource.USER_ACTION,
        resolutionSummary: String? = null
    ): Boolean = synchronized(sessionMutationLock) {
        if (memoryId.isBlank()) return false
        val normalizedSummary = resolutionSummary?.trim()?.takeIf { it.isNotBlank() }
            ?: when (scope) {
                ArchivedMemoryCandidateScope.PROJECT -> "已将归档候选保存为项目记忆。"
                ArchivedMemoryCandidateScope.GLOBAL -> "已将归档候选保存为全局记忆。"
            }
        val records = archivedSessionsLocked()
        var updated = false
        val rewritten = records.map { summary ->
            if (summary.originalSessionId != sessionId || !summary.isPendingMemoryCandidate()) {
                summary
            } else {
                updated = true
                summary.copy(
                    candidateStatus = ArchivedMemoryCandidateStatus.ACCEPTED,
                    acceptedMemoryScope = scope,
                    acceptedMemoryId = memoryId,
                    acceptedAt = acceptedAt,
                    dismissedReason = null,
                    dismissedAt = null,
                    consumedReason = null,
                    consumedAt = null,
                    resolutionSource = resolutionSource,
                    resolutionSummary = normalizedSummary
                )
            }
        }
        if (updated) {
            writeArchivedSessionsLocked(rewritten)
        }
        updated
    }

    fun markArchivedMemoryCandidateDismissed(
        sessionId: String,
        dismissedReason: String? = null,
        dismissedAt: Long = System.currentTimeMillis(),
        resolutionSource: ArchivedMemoryCandidateResolutionSource = ArchivedMemoryCandidateResolutionSource.USER_ACTION,
        resolutionSummary: String? = null
    ): Boolean = synchronized(sessionMutationLock) {
        val normalizedReason = dismissedReason?.trim()?.takeIf { it.isNotBlank() }
        val normalizedSummary = resolutionSummary?.trim()?.takeIf { it.isNotBlank() }
            ?: normalizedReason
            ?: "已关闭归档记忆候选。"
        val records = archivedSessionsLocked()
        var updated = false
        val rewritten = records.map { summary ->
            if (summary.originalSessionId != sessionId || !summary.isPendingMemoryCandidate()) {
                summary
            } else {
                updated = true
                summary.copy(
                    candidateStatus = ArchivedMemoryCandidateStatus.DISMISSED,
                    acceptedMemoryScope = null,
                    acceptedMemoryId = null,
                    acceptedAt = null,
                    dismissedReason = normalizedReason,
                    dismissedAt = dismissedAt,
                    consumedReason = null,
                    consumedAt = null,
                    resolutionSource = resolutionSource,
                    resolutionSummary = normalizedSummary
                )
            }
        }
        if (updated) {
            writeArchivedSessionsLocked(rewritten)
        }
        updated
    }

    fun markArchivedMemoryCandidateConsumed(
        sessionId: String,
        consumedReason: String? = null,
        consumedAt: Long = System.currentTimeMillis(),
        resolutionSource: ArchivedMemoryCandidateResolutionSource = ArchivedMemoryCandidateResolutionSource.USER_ACTION,
        resolutionSummary: String? = null
    ): Boolean = synchronized(sessionMutationLock) {
        val normalizedReason = consumedReason?.trim()?.takeIf { it.isNotBlank() }
        val normalizedSummary = resolutionSummary?.trim()?.takeIf { it.isNotBlank() }
            ?: normalizedReason
            ?: "已将归档记忆候选标记为已处理。"
        val records = archivedSessionsLocked()
        var updated = false
        val rewritten = records.map { summary ->
            if (summary.originalSessionId != sessionId || !summary.isPendingMemoryCandidate()) {
                summary
            } else {
                updated = true
                summary.copy(
                    candidateStatus = ArchivedMemoryCandidateStatus.CONSUMED,
                    acceptedMemoryScope = null,
                    acceptedMemoryId = null,
                    acceptedAt = null,
                    dismissedReason = null,
                    dismissedAt = null,
                    consumedReason = normalizedReason,
                    consumedAt = consumedAt,
                    resolutionSource = resolutionSource,
                    resolutionSummary = normalizedSummary
                )
            }
        }
        if (updated) {
            writeArchivedSessionsLocked(rewritten)
        }
        updated
    }

    /**
     * 列出所有会话摘要
     */
    fun listSessions(): List<SessionSummary> = synchronized(sessionMutationLock) {
        val deletedIds = deletedSessionIdsLocked()
        val indexFile = File(baseDir, "index.json")
        if (!indexFile.exists()) {
            // 无索引时扫描目录重建
            return scanAndRebuildIndex()
        }
        try {
            json.decodeFromString<List<SessionSummary>>(indexFile.readText())
                .filterNot { it.id in deletedIds }
                .sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            scanAndRebuildIndex()
        }
    }

    /**
     * 将 UI 消息转换为持久化消息
     */
    fun persistMessages(msgs: List<ChatMessageUi>): List<PersistedMessage> {
        return msgs.map { msg ->
            PersistedMessage(
                id = msg.id,
                role = msg.role,
                content = msg.content,
                imageAttachments = msg.imageAttachments.map { attachment ->
                    PersistedMessageImageAttachment(
                        id = attachment.id,
                        fileName = attachment.fileName,
                        mimeType = attachment.mimeType,
                        localCachePath = attachment.localCachePath,
                        width = attachment.width,
                        height = attachment.height,
                        sizeBytes = attachment.sizeBytes
                    )
                },
                reasoning = msg.reasoning,
                subagentRunId = msg.subagentRunId,
                subagentBatchId = msg.subagentBatchId,
                timestamp = msg.timestamp
            )
        }
    }

    /**
     * 将持久化消息还原为 UI 消息
     */
    fun restoreMessages(persisted: List<PersistedMessage>): List<ChatMessageUi> {
        return persisted.map { msg ->
            ChatMessageUi(
                id = msg.id,
                role = msg.role,
                content = msg.content,
                imageAttachments = msg.imageAttachments.map { attachment ->
                    MessageImageAttachmentUi(
                        id = attachment.id,
                        fileName = attachment.fileName,
                        mimeType = attachment.mimeType,
                        localCachePath = attachment.localCachePath,
                        width = attachment.width,
                        height = attachment.height,
                        sizeBytes = attachment.sizeBytes
                    )
                },
                reasoning = msg.reasoning,
                isStreaming = false,
                subagentRunId = msg.subagentRunId,
                subagentBatchId = msg.subagentBatchId,
                timestamp = msg.timestamp
            )
        }
    }

    fun persistSubagentRuns(runs: List<SubagentRunUi>): List<PersistedSubagentRun> {
        return runs.map { run ->
            PersistedSubagentRun(
                runId = run.runId,
                status = run.status,
                goal = run.goal,
                summary = run.summary,
                error = run.error,
                templateId = run.templateId,
                templateTitle = run.templateTitle,
                model = run.model,
                reasoningEffort = run.reasoningEffort,
                allowedTools = run.allowedTools,
                statusMessage = run.statusMessage,
                retryCount = run.retryCount,
                sourceRunId = run.sourceRunId,
                batchId = run.batchId,
                batchLabel = run.batchLabel,
                batchIndex = run.batchIndex,
                batchSize = run.batchSize,
                queuePosition = run.queuePosition,
                assignedSlot = run.assignedSlot,
                concurrencyLimit = run.concurrencyLimit,
                approvalRequestedAt = run.approvalRequestedAt,
                queuedAt = run.queuedAt,
                executionStartedAt = run.executionStartedAt,
                summarizingAt = run.summarizingAt,
                usageSummary = run.usageSummary,
                startedAt = run.startedAt,
                finishedAt = run.finishedAt
            )
        }
    }

    fun restoreSubagentRuns(runs: List<PersistedSubagentRun>): List<SubagentRunUi> {
        return runs.map { run ->
            SubagentRunUi(
                runId = run.runId,
                status = run.status,
                goal = run.goal,
                summary = run.summary,
                error = run.error,
                templateId = run.templateId,
                templateTitle = run.templateTitle,
                model = run.model,
                reasoningEffort = run.reasoningEffort,
                allowedTools = run.allowedTools,
                statusMessage = run.statusMessage,
                retryCount = run.retryCount,
                sourceRunId = run.sourceRunId,
                batchId = run.batchId,
                batchLabel = run.batchLabel,
                batchIndex = run.batchIndex,
                batchSize = run.batchSize,
                queuePosition = run.queuePosition,
                assignedSlot = run.assignedSlot,
                concurrencyLimit = run.concurrencyLimit,
                approvalRequestedAt = run.approvalRequestedAt,
                queuedAt = run.queuedAt,
                executionStartedAt = run.executionStartedAt,
                summarizingAt = run.summarizingAt,
                usageSummary = run.usageSummary,
                startedAt = run.startedAt,
                finishedAt = run.finishedAt
            )
        }
    }

    fun persistSubagentBatches(batches: List<SubagentBatchUi>): List<PersistedSubagentBatch> {
        return batches.map { batch ->
            PersistedSubagentBatch(
                batchId = batch.batchId,
                parentGoal = batch.parentGoal,
                label = batch.label,
                runIds = batch.runIds,
                splitStrategyLabel = batch.splitStrategyLabel,
                splitStrategyDetail = batch.splitStrategyDetail,
                status = batch.status,
                statusMessage = batch.statusMessage,
                summary = batch.summary,
                queuePosition = batch.queuePosition,
                activeSlots = batch.activeSlots,
                queuedRuns = batch.queuedRuns,
                runningRuns = batch.runningRuns,
                concurrencyLimit = batch.concurrencyLimit,
                approvalRequestedAt = batch.approvalRequestedAt,
                queuedAt = batch.queuedAt,
                firstRunStartedAt = batch.firstRunStartedAt,
                lastRunFinishedAt = batch.lastRunFinishedAt,
                timeline = batch.timeline.map { entry ->
                    PersistedSubagentTimelineEntry(
                        id = entry.id,
                        timestamp = entry.timestamp,
                        type = entry.type,
                        title = entry.title,
                        detail = entry.detail,
                        relatedRunId = entry.relatedRunId
                    )
                },
                startedAt = batch.startedAt,
                finishedAt = batch.finishedAt
            )
        }
    }

    fun restoreSubagentBatches(batches: List<PersistedSubagentBatch>): List<SubagentBatchUi> {
        return batches.map { batch ->
            SubagentBatchUi(
                batchId = batch.batchId,
                parentGoal = batch.parentGoal,
                label = batch.label,
                runIds = batch.runIds,
                splitStrategyLabel = batch.splitStrategyLabel,
                splitStrategyDetail = batch.splitStrategyDetail,
                status = batch.status,
                statusMessage = batch.statusMessage,
                summary = batch.summary,
                queuePosition = batch.queuePosition,
                activeSlots = batch.activeSlots,
                queuedRuns = batch.queuedRuns,
                runningRuns = batch.runningRuns,
                concurrencyLimit = batch.concurrencyLimit,
                approvalRequestedAt = batch.approvalRequestedAt,
                queuedAt = batch.queuedAt,
                firstRunStartedAt = batch.firstRunStartedAt,
                lastRunFinishedAt = batch.lastRunFinishedAt,
                timeline = batch.timeline.map { entry ->
                    SubagentTimelineEntryUi(
                        id = entry.id,
                        timestamp = entry.timestamp,
                        type = entry.type,
                        title = entry.title,
                        detail = entry.detail,
                        relatedRunId = entry.relatedRunId
                    )
                },
                startedAt = batch.startedAt,
                finishedAt = batch.finishedAt
            )
        }
    }

    fun persistBackgroundJobs(jobs: List<BackgroundJobUi>): List<PersistedBackgroundJob> {
        return jobs.map { job ->
            PersistedBackgroundJob(
                jobId = job.jobId,
                toolName = job.toolName,
                title = job.title,
                summary = job.summary,
                detail = job.detail,
                status = job.status,
                statusMessage = job.statusMessage,
                resultPreview = job.resultPreview,
                queuePosition = job.queuePosition,
                assignedSlot = job.assignedSlot,
                concurrencyLimit = job.concurrencyLimit,
                timeoutSeconds = job.timeoutSeconds,
                createdAt = job.createdAt,
                startedAt = job.startedAt,
                finishedAt = job.finishedAt
            )
        }
    }

    fun restoreBackgroundJobs(jobs: List<PersistedBackgroundJob>): List<BackgroundJobUi> {
        return jobs.map { job ->
            BackgroundJobUi(
                jobId = job.jobId,
                toolName = job.toolName,
                title = job.title,
                summary = job.summary,
                detail = job.detail,
                status = job.status,
                statusMessage = job.statusMessage,
                resultPreview = job.resultPreview,
                queuePosition = job.queuePosition,
                assignedSlot = job.assignedSlot,
                concurrencyLimit = job.concurrencyLimit,
                timeoutSeconds = job.timeoutSeconds,
                createdAt = job.createdAt,
                startedAt = job.startedAt,
                finishedAt = job.finishedAt
            )
        }
    }

    fun persistCompressionSnapshot(snapshot: ContextCompressionUi?): PersistedCompressionSnapshot? {
        return snapshot?.let {
            PersistedCompressionSnapshot(
                id = it.id,
                version = it.version,
                summary = it.summary,
                sourceMessageCount = it.sourceMessageCount,
                sourceEndMessageId = it.sourceEndMessageId,
                sourceEndMessageIndex = it.sourceEndMessageIndex,
                createdAt = it.createdAt,
                active = it.active
            )
        }
    }

    fun restoreCompressionSnapshot(snapshot: PersistedCompressionSnapshot?): ContextCompressionUi? {
        return snapshot?.let {
            ContextCompressionUi(
                id = it.id,
                version = it.version,
                summary = it.summary,
                sourceMessageCount = it.sourceMessageCount,
                sourceEndMessageId = it.sourceEndMessageId,
                sourceEndMessageIndex = it.sourceEndMessageIndex,
                createdAt = it.createdAt,
                active = it.active
            )
        }
    }

    fun persistCompressionSnapshots(
        snapshots: List<ContextCompressionUi>
    ): List<PersistedCompressionSnapshot> {
        return snapshots.mapNotNull(::persistCompressionSnapshot)
    }

    fun persistSessionHistoryReferenceClue(
        clue: SessionHistoryReferenceClueUi?
    ): PersistedSessionHistoryReferenceClue? {
        return clue?.let {
            PersistedSessionHistoryReferenceClue(
                queries = it.queries,
                sessionIds = it.sessionIds,
                messageReferences = it.messageReferences,
                snippets = it.snippets,
                excerptWindows = it.excerptWindows
            )
        }
    }

    fun restoreSessionHistoryReferenceClue(
        clue: PersistedSessionHistoryReferenceClue?
    ): SessionHistoryReferenceClueUi? {
        return clue?.let {
            SessionHistoryReferenceClueUi(
                queries = it.queries,
                sessionIds = it.sessionIds,
                messageReferences = it.messageReferences,
                snippets = it.snippets,
                excerptWindows = it.excerptWindows
            )
        }
    }

    fun persistWorkflowPlan(plan: WorkflowPlanUi?): PersistedWorkflowPlan? {
        return plan?.let {
            PersistedWorkflowPlan(
                id = it.id,
                goal = it.goal,
                summary = it.summary,
                steps = it.steps,
                stageLabels = it.stageLabels,
                currentStageIndex = it.currentStageIndex,
                currentStepIndex = it.currentStepIndex,
                nextStepHint = it.nextStepHint,
                status = it.status.name,
                mentionedFiles = it.mentionedFiles.map { mention ->
                    PersistedFileMention(
                        path = mention.path,
                        displayPath = mention.displayPath,
                        inlineContent = mention.inlineContent
                    )
                },
                stepSignOffs = it.stepSignOffs.map { signOff ->
                    PersistedWorkflowStepSignOff(
                        stepIndex = signOff.stepIndex,
                        step = signOff.step,
                        reportedStep = signOff.reportedStep,
                        resultSummary = signOff.resultSummary,
                        matchedEvidenceCount = signOff.matchedEvidenceCount,
                        totalEvidenceCount = signOff.totalEvidenceCount,
                        matchedToolNames = signOff.matchedToolNames,
                        matchedSessionHistorySessionIds = signOff.matchedSessionHistorySessionIds,
                        matchedSessionHistoryMessageReferences =
                            signOff.matchedSessionHistoryMessageReferences,
                        signedOffAt = signOff.signedOffAt
                    )
                },
                recentSessionHistoryClue = persistSessionHistoryReferenceClue(
                    it.recentSessionHistoryClue
                ),
                rawPlan = it.rawPlan,
                createdAt = it.createdAt
            )
        }
    }

    fun restoreWorkflowPlan(plan: PersistedWorkflowPlan?): WorkflowPlanUi? {
        return plan?.let {
            WorkflowPlanUi(
                id = it.id,
                goal = it.goal,
                summary = it.summary,
                steps = it.steps,
                stageLabels = it.stageLabels,
                currentStageIndex = it.currentStageIndex,
                currentStepIndex = it.currentStepIndex,
                nextStepHint = it.nextStepHint,
                status = it.status.toPersistedWorkflowPlanStatusUi(),
                mentionedFiles = it.mentionedFiles.map { mention ->
                    FileMentionUi(
                        path = mention.path,
                        displayPath = mention.displayPath,
                        inlineContent = mention.inlineContent
                    )
                },
                stepSignOffs = it.stepSignOffs.map { signOff ->
                    WorkflowStepSignOffUi(
                        stepIndex = signOff.stepIndex,
                        step = signOff.step,
                        reportedStep = signOff.reportedStep,
                        resultSummary = signOff.resultSummary,
                        matchedEvidenceCount = signOff.matchedEvidenceCount,
                        totalEvidenceCount = signOff.totalEvidenceCount,
                        matchedToolNames = signOff.matchedToolNames,
                        matchedSessionHistorySessionIds = signOff.matchedSessionHistorySessionIds,
                        matchedSessionHistoryMessageReferences =
                            signOff.matchedSessionHistoryMessageReferences,
                        signedOffAt = signOff.signedOffAt
                    )
                },
                recentSessionHistoryClue = restoreSessionHistoryReferenceClue(
                    it.recentSessionHistoryClue
                ),
                rawPlan = it.rawPlan,
                createdAt = it.createdAt
            )
        }
    }

    fun persistClarificationRequest(
        request: ClarificationRequestUi?
    ): PersistedClarificationRequest? {
        return request?.let {
            PersistedClarificationRequest(
                id = it.id,
                goal = it.goal,
                question = it.question,
                mentionedFiles = it.mentionedFiles.map { mention ->
                    PersistedFileMention(
                        path = mention.path,
                        displayPath = mention.displayPath,
                        inlineContent = mention.inlineContent
                    )
                },
                previousAnswers = it.previousAnswers.map { answer ->
                    PersistedClarificationAnswer(
                        question = answer.question,
                        answer = answer.answer,
                        answeredAt = answer.answeredAt
                    )
                },
                turnIndex = it.turnIndex,
                maxTurns = it.maxTurns,
                source = it.source.name,
                recentSessionHistoryClue = persistSessionHistoryReferenceClue(
                    it.recentSessionHistoryClue
                ),
                createdAt = it.createdAt
            )
        }
    }

    fun restoreClarificationRequest(
        persisted: PersistedClarificationRequest?
    ): ClarificationRequestUi? {
        return persisted?.let {
            ClarificationRequestUi(
                id = it.id,
                goal = it.goal,
                question = it.question,
                mentionedFiles = it.mentionedFiles.map { mention ->
                    FileMentionUi(
                        path = mention.path,
                        displayPath = mention.displayPath,
                        inlineContent = mention.inlineContent
                    )
                },
                previousAnswers = it.previousAnswers.map { answer ->
                    ClarificationAnswerUi(
                        question = answer.question,
                        answer = answer.answer,
                        answeredAt = answer.answeredAt
                    )
                },
                turnIndex = it.turnIndex,
                maxTurns = it.maxTurns,
                source = when (it.source.trim().uppercase()) {
                    "AUTO_ROUTE" -> ClarificationSource.AUTO_ROUTE
                    "AUTO_INTERRUPT" -> ClarificationSource.AUTO_INTERRUPT
                    else -> ClarificationSource.MANUAL
                },
                recentSessionHistoryClue = restoreSessionHistoryReferenceClue(
                    it.recentSessionHistoryClue
                ),
                createdAt = it.createdAt
            )
        }
    }

    fun persistFinalReadinessReceipt(
        receipt: FinalReadinessReceipt?
    ): PersistedFinalReadinessReceipt? {
        return receipt?.let {
            PersistedFinalReadinessReceipt(
                kind = it.kind.name,
                requiredAction = it.requiredAction.name,
                message = it.message,
                latestSuccessfulWriteToolName = it.latestSuccessfulWriteToolName,
                remainingUnsignedSteps = it.remainingUnsignedSteps,
                nextRequiredStep = it.nextRequiredStep,
                latestSignedOffStep = it.latestSignedOffStep,
                latestSignedOffResultSummary = it.latestSignedOffResultSummary,
                latestSignedOffMatchedTools = it.latestSignedOffMatchedTools,
                latestSignedOffSessionHistorySessionIds = it.latestSignedOffSessionHistorySessionIds,
                latestSignedOffSessionHistoryMessageReferences =
                    it.latestSignedOffSessionHistoryMessageReferences
            )
        }
    }

    fun restoreFinalReadinessReceipt(
        receipt: PersistedFinalReadinessReceipt?
    ): FinalReadinessReceipt? {
        return receipt?.let {
            FinalReadinessReceipt(
                kind = runCatching { FinalReadinessReceiptKind.valueOf(it.kind) }
                    .getOrDefault(FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE),
                requiredAction = runCatching {
                    FinalReadinessRequiredAction.valueOf(it.requiredAction)
                }.getOrDefault(FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE),
                message = it.message,
                latestSuccessfulWriteToolName = it.latestSuccessfulWriteToolName,
                remainingUnsignedSteps = it.remainingUnsignedSteps,
                nextRequiredStep = it.nextRequiredStep,
                latestSignedOffStep = it.latestSignedOffStep,
                latestSignedOffResultSummary = it.latestSignedOffResultSummary,
                latestSignedOffMatchedTools = it.latestSignedOffMatchedTools,
                latestSignedOffSessionHistorySessionIds = it.latestSignedOffSessionHistorySessionIds,
                latestSignedOffSessionHistoryMessageReferences =
                    it.latestSignedOffSessionHistoryMessageReferences
            )
        }
    }

    private fun String?.toPersistedWorkflowPlanStatusUi(): WorkflowPlanStatusUi {
        return when (this?.uppercase()) {
            WorkflowPlanStatusUi.EXECUTING.name -> WorkflowPlanStatusUi.EXECUTING
            WorkflowPlanStatusUi.BLOCKED.name -> WorkflowPlanStatusUi.BLOCKED
            WorkflowPlanStatusUi.COMPLETED.name -> WorkflowPlanStatusUi.COMPLETED
            else -> WorkflowPlanStatusUi.READY
        }
    }

    fun restoreCompressionSnapshots(
        snapshots: List<PersistedCompressionSnapshot>,
        fallbackSnapshot: PersistedCompressionSnapshot? = null
    ): List<ContextCompressionUi> {
        val restored = snapshots.mapNotNull(::restoreCompressionSnapshot)
        if (restored.isNotEmpty()) {
            return restored.sortedBy { it.version }
        }
        return listOfNotNull(restoreCompressionSnapshot(fallbackSnapshot))
    }

    fun persistConversationCheckpoints(
        checkpoints: List<ConversationCheckpointUi>
    ): List<PersistedConversationCheckpoint> {
        return checkpoints.map { checkpoint ->
            PersistedConversationCheckpoint(
                id = checkpoint.id,
                messageIndex = checkpoint.messageIndex,
                createdAt = checkpoint.createdAt,
                summary = checkpoint.summary,
                changedFiles = checkpoint.changedFiles,
                kind = checkpoint.kind.name,
                scope = checkpoint.scope.name,
                source = checkpoint.source.name,
                toolNames = checkpoint.toolNames,
                promptSnapshot = checkpoint.promptSnapshot?.let { snapshot ->
                    PersistedConversationCheckpointPromptSnapshot(
                        pendingAskRequest = persistPendingAskRequest(snapshot.pendingAskRequest),
                        pendingWorkflowPlan = persistWorkflowPlan(snapshot.pendingWorkflowPlan),
                        canonicalWorkflowPlan = persistWorkflowPlan(snapshot.canonicalWorkflowPlan),
                        pendingClarificationRequest = persistClarificationRequest(
                            snapshot.pendingClarificationRequest
                        )
                    )
                }
            )
        }
    }

    fun restoreConversationCheckpoints(
        checkpoints: List<PersistedConversationCheckpoint>
    ): List<ConversationCheckpointUi> {
        return checkpoints.map { checkpoint ->
            ConversationCheckpointUi(
                id = checkpoint.id,
                messageIndex = checkpoint.messageIndex,
                createdAt = checkpoint.createdAt,
                summary = checkpoint.summary,
                changedFiles = checkpoint.changedFiles,
                kind = runCatching {
                    ConversationCheckpointKind.valueOf(checkpoint.kind)
                }.getOrDefault(ConversationCheckpointKind.FILE_TURN),
                scope = runCatching {
                    ConversationCheckpointScope.valueOf(checkpoint.scope)
                }.getOrDefault(ConversationCheckpointScope.CODE),
                source = runCatching {
                    ConversationCheckpointSource.valueOf(checkpoint.source)
                }.getOrDefault(ConversationCheckpointSource.LEGACY_EMBEDDED),
                toolNames = checkpoint.toolNames,
                promptSnapshot = checkpoint.promptSnapshot?.let { snapshot ->
                    ConversationCheckpointPromptSnapshotUi(
                        pendingAskRequest = restorePendingAskRequest(snapshot.pendingAskRequest),
                        pendingWorkflowPlan = restoreWorkflowPlan(snapshot.pendingWorkflowPlan),
                        canonicalWorkflowPlan = restoreWorkflowPlan(snapshot.canonicalWorkflowPlan),
                        pendingClarificationRequest = restoreClarificationRequest(
                            snapshot.pendingClarificationRequest
                        )
                    )
                }
            )
        }
    }

    fun persistErrorRecords(records: List<ErrorRecordUi>): List<PersistedErrorRecord> {
        return records.map {
            PersistedErrorRecord(it.id, it.message, it.kind.name, it.timestamp)
        }
    }

    fun restoreErrorRecords(persisted: List<PersistedErrorRecord>): List<ErrorRecordUi> {
        return persisted.map {
            ErrorRecordUi(
                id = it.id,
                message = it.message,
                kind = runCatching { ErrorRecordKind.valueOf(it.kind) }
                    .getOrDefault(ErrorRecordKind.GENERAL),
                timestamp = it.timestamp
            )
        }
    }

    fun persistToolCallRecords(records: List<ToolCallRecordUi>): List<PersistedToolCallRecord> {
        return records.map {
            PersistedToolCallRecord(
                id = it.id,
                toolName = it.toolName,
                args = it.args,
                result = it.result,
                isSuccess = it.isSuccess,
                stepSignOffReceipt = it.stepSignOffReceipt?.let(::persistStepSignOffReceipt),
                structuredPayload = it.structuredPayload,
                timestamp = it.timestamp
            )
        }
    }

    fun restoreToolCallRecords(persisted: List<PersistedToolCallRecord>): List<ToolCallRecordUi> {
        return persisted.map {
            ToolCallRecordUi(
                id = it.id,
                toolName = it.toolName,
                args = it.args,
                result = it.result,
                isSuccess = it.isSuccess,
                stepSignOffReceipt = it.stepSignOffReceipt?.let(::restoreStepSignOffReceipt),
                structuredPayload = it.structuredPayload,
                timestamp = it.timestamp
            )
        }
    }

    fun persistMemoryUpdateSuggestions(
        suggestions: List<MemoryUpdateSuggestionUi>
    ): List<PersistedMemoryUpdateSuggestion> {
        return suggestions.map { suggestion ->
            PersistedMemoryUpdateSuggestion(
                id = suggestion.id,
                title = suggestion.title,
                content = suggestion.content,
                scope = suggestion.scope,
                reason = suggestion.reason,
                sourceKind = suggestion.sourceKind,
                sourceUserMessageId = suggestion.sourceUserMessageId,
                sourceAssistantMessageId = suggestion.sourceAssistantMessageId,
                linkedMemoryId = suggestion.linkedMemoryId,
                status = suggestion.status.name,
                createdAt = suggestion.createdAt
            )
        }
    }

    fun restoreMemoryUpdateSuggestions(
        persisted: List<PersistedMemoryUpdateSuggestion>
    ): List<MemoryUpdateSuggestionUi> {
        return persisted.map { suggestion ->
            MemoryUpdateSuggestionUi(
                id = suggestion.id,
                title = suggestion.title,
                content = suggestion.content,
                scope = suggestion.scope,
                reason = suggestion.reason,
                sourceKind = suggestion.sourceKind,
                sourceUserMessageId = suggestion.sourceUserMessageId,
                sourceAssistantMessageId = suggestion.sourceAssistantMessageId,
                linkedMemoryId = suggestion.linkedMemoryId,
                status = runCatching { MemoryUpdateSuggestionStatusUi.valueOf(suggestion.status) }
                    .getOrDefault(MemoryUpdateSuggestionStatusUi.PENDING),
                createdAt = suggestion.createdAt
            )
        }
    }

    fun persistCheckpointRecoveryRecords(
        records: List<CheckpointRecoveryRecordUi>
    ): List<PersistedCheckpointRecoveryRecord> {
        return records.map {
            PersistedCheckpointRecoveryRecord(
                id = it.id,
                checkpointId = it.checkpointId,
                checkpointSummary = it.checkpointSummary,
                scope = it.scope.name,
                restoredFileCount = it.restoredFileCount,
                targetMessageIndex = it.targetMessageIndex,
                timestamp = it.timestamp
            )
        }
    }

    fun restoreCheckpointRecoveryRecords(
        persisted: List<PersistedCheckpointRecoveryRecord>
    ): List<CheckpointRecoveryRecordUi> {
        return persisted.map {
            CheckpointRecoveryRecordUi(
                id = it.id,
                checkpointId = it.checkpointId,
                checkpointSummary = it.checkpointSummary,
                scope = runCatching { ConversationCheckpointScope.valueOf(it.scope) }
                    .getOrDefault(ConversationCheckpointScope.CODE),
                restoredFileCount = it.restoredFileCount,
                targetMessageIndex = it.targetMessageIndex,
                timestamp = it.timestamp
            )
        }
    }

    fun persistFinalReadinessAuditRecords(
        records: List<FinalReadinessAuditRecord>
    ): List<PersistedFinalReadinessAuditRecord> {
        return records.map {
            PersistedFinalReadinessAuditRecord(
                result = it.result.name,
                recovered = it.recovered,
                receiptKind = it.receiptKind.name,
                requiredAction = it.requiredAction.name,
                latestSuccessfulWriteToolName = it.latestSuccessfulWriteToolName,
                remainingUnsignedSteps = it.remainingUnsignedSteps,
                nextRequiredStep = it.nextRequiredStep,
                latestSignedOffStep = it.latestSignedOffStep,
                latestSignedOffMatchedTools = it.latestSignedOffMatchedTools,
                latestSignedOffSessionHistorySessionIds = it.latestSignedOffSessionHistorySessionIds,
                latestSignedOffSessionHistoryMessageReferences =
                    it.latestSignedOffSessionHistoryMessageReferences
            )
        }
    }

    fun restoreFinalReadinessAuditRecords(
        persisted: List<PersistedFinalReadinessAuditRecord>
    ): List<FinalReadinessAuditRecord> {
        return persisted.map {
            FinalReadinessAuditRecord(
                result = FinalReadinessAuditResult.valueOf(it.result),
                recovered = it.recovered,
                receiptKind = FinalReadinessReceiptKind.valueOf(it.receiptKind),
                requiredAction = FinalReadinessRequiredAction.valueOf(it.requiredAction),
                latestSuccessfulWriteToolName = it.latestSuccessfulWriteToolName,
                remainingUnsignedSteps = it.remainingUnsignedSteps,
                nextRequiredStep = it.nextRequiredStep,
                latestSignedOffStep = it.latestSignedOffStep,
                latestSignedOffMatchedTools = it.latestSignedOffMatchedTools,
                latestSignedOffSessionHistorySessionIds = it.latestSignedOffSessionHistorySessionIds,
                latestSignedOffSessionHistoryMessageReferences =
                    it.latestSignedOffSessionHistoryMessageReferences
            )
        }
    }

    private fun persistStepSignOffReceipt(receipt: StepSignOffReceipt): PersistedStepSignOffReceipt {
        return PersistedStepSignOffReceipt(
            reportedStep = receipt.reportedStep,
            resultSummary = receipt.resultSummary,
            matchedEvidenceCount = receipt.matchedEvidenceCount,
            totalEvidenceCount = receipt.totalEvidenceCount,
            matchedToolNames = receipt.matchedToolNames,
            matchedSessionHistorySessionIds = receipt.matchedSessionHistorySessionIds,
            matchedSessionHistoryMessageReferences = receipt.matchedSessionHistoryMessageReferences,
            signOffTimestamp = receipt.signOffTimestamp,
            workflowStepIndex = receipt.workflowStepIndex,
            workflowStep = receipt.workflowStep,
            workflowTotalSteps = receipt.workflowTotalSteps
        )
    }

    private fun restoreStepSignOffReceipt(receipt: PersistedStepSignOffReceipt): StepSignOffReceipt {
        return StepSignOffReceipt(
            reportedStep = receipt.reportedStep,
            resultSummary = receipt.resultSummary,
            matchedEvidenceCount = receipt.matchedEvidenceCount,
            totalEvidenceCount = receipt.totalEvidenceCount,
            matchedToolNames = receipt.matchedToolNames,
            matchedSessionHistorySessionIds = receipt.matchedSessionHistorySessionIds,
            matchedSessionHistoryMessageReferences = receipt.matchedSessionHistoryMessageReferences,
            signOffTimestamp = receipt.signOffTimestamp,
            workflowStepIndex = receipt.workflowStepIndex,
            workflowStep = receipt.workflowStep,
            workflowTotalSteps = receipt.workflowTotalSteps
        )
    }

    fun persistApprovalRecords(records: List<ApprovalRecordUi>): List<PersistedApprovalRecord> {
        return records.map {
            PersistedApprovalRecord(
                id = it.id,
                toolName = it.toolName,
                summary = it.summary,
                decision = it.decision,
                scopeSummary = it.scopeSummary,
                explanationLabel = it.explanationLabel,
                explanationDetail = it.explanationDetail,
                timestamp = it.timestamp
            )
        }
    }

    fun restoreApprovalRecords(persisted: List<PersistedApprovalRecord>): List<ApprovalRecordUi> {
        return persisted.map {
            ApprovalRecordUi(
                id = it.id,
                toolName = it.toolName,
                summary = it.summary,
                decision = it.decision,
                scopeSummary = it.scopeSummary,
                explanationLabel = it.explanationLabel,
                explanationDetail = it.explanationDetail,
                timestamp = it.timestamp
            )
        }
    }

    fun persistApprovalInvalidationRecords(
        records: List<ApprovalInvalidationRecordUi>
    ): List<PersistedApprovalInvalidationRecord> {
        return records.map {
            PersistedApprovalInvalidationRecord(
                id = it.id,
                summary = it.summary,
                sourceLabel = it.sourceLabel,
                sourceDetail = it.sourceDetail,
                reasonLabel = it.reasonLabel,
                reasonDetail = it.reasonDetail,
                timestamp = it.timestamp
            )
        }
    }

    fun restoreApprovalInvalidationRecords(
        persisted: List<PersistedApprovalInvalidationRecord>
    ): List<ApprovalInvalidationRecordUi> {
        return persisted.map {
            ApprovalInvalidationRecordUi(
                id = it.id,
                summary = it.summary,
                sourceLabel = it.sourceLabel,
                sourceDetail = it.sourceDetail,
                reasonLabel = it.reasonLabel,
                reasonDetail = it.reasonDetail,
                timestamp = it.timestamp
            )
        }
    }

    fun persistApprovedApprovalScopes(scopes: List<PersistedApprovedApprovalScope>): List<PersistedApprovedApprovalScope> {
        return scopes.map { scope ->
            scope.copy(tokens = scope.tokens.distinct().sorted())
        }
    }

    fun restoreApprovedApprovalScopes(
        entries: List<PersistedApprovedApprovalScope>,
        legacyScopes: List<List<String>> = emptyList()
    ): List<PersistedApprovedApprovalScope> {
        if (entries.isNotEmpty()) {
            return entries.map { scope ->
                scope.copy(tokens = scope.tokens.distinct().sorted())
            }
        }
        return legacyScopes.map { scope ->
            PersistedApprovedApprovalScope(tokens = scope.distinct().sorted())
        }
    }

    fun persistPendingApproval(pendingApproval: PendingApprovalUi?): PersistedPendingApproval? {
        return pendingApproval?.let {
            PersistedPendingApproval(
                toolName = it.toolName,
                summary = it.summary,
                detail = it.detail,
                rawArgs = it.rawArgs,
                riskLevel = it.riskLevel.name,
                explanationLabel = it.explanationLabel,
                explanationDetail = it.explanationDetail
            )
        }
    }

    fun restorePendingApproval(persisted: PersistedPendingApproval?): PendingApprovalUi? {
        return persisted?.let {
            PendingApprovalUi(
                toolName = it.toolName,
                summary = it.summary,
                detail = it.detail,
                rawArgs = it.rawArgs,
                riskLevel = runCatching { ApprovalRiskLevel.valueOf(it.riskLevel) }
                    .getOrDefault(ApprovalRiskLevel.MEDIUM),
                explanationLabel = it.explanationLabel,
                explanationDetail = it.explanationDetail
            )
        }
    }

    fun persistPendingAskRequest(request: PendingAskRequestUi?): PersistedPendingAskRequest? {
        return request?.let {
            PersistedPendingAskRequest(
                id = it.id,
                title = it.title,
                questions = it.questions.map { question ->
                    PersistedAskQuestion(
                        id = question.id,
                        header = question.header,
                        question = question.question,
                        options = question.options.map { option ->
                            PersistedAskOption(
                                label = option.label,
                                description = option.description
                            )
                        },
                        multiSelect = question.multiSelect
                    )
                },
                recentSessionHistoryClue = persistSessionHistoryReferenceClue(
                    it.recentSessionHistoryClue
                ),
                createdAt = it.createdAt
            )
        }
    }

    fun restorePendingAskRequest(persisted: PersistedPendingAskRequest?): PendingAskRequestUi? {
        return persisted?.let {
            PendingAskRequestUi(
                id = it.id,
                title = it.title,
                questions = it.questions.map { question ->
                    AskQuestionUi(
                        id = question.id,
                        header = question.header,
                        question = question.question,
                        options = question.options.map { option ->
                            AskOptionUi(
                                label = option.label,
                                description = option.description
                            )
                        },
                        multiSelect = question.multiSelect
                    )
                },
                recentSessionHistoryClue = restoreSessionHistoryReferenceClue(
                    it.recentSessionHistoryClue
                ),
                createdAt = it.createdAt
            )
        }
    }

    fun persistFileChanges(records: List<FileChangeRecordUi>): List<PersistedFileChangeRecord> {
        return records.map { record ->
            PersistedFileChangeRecord(
                id = record.id,
                path = record.path,
                operation = record.operation,
                beforeContent = record.beforeContent,
                afterContent = record.afterContent,
                diffPreview = record.diffPreview,
                changedAt = record.changedAt,
                checkpointId = record.checkpointId
            )
        }
    }

    fun restoreFileChanges(records: List<PersistedFileChangeRecord>): List<FileChangeRecordUi> {
        return records.map { record ->
            FileChangeRecordUi(
                id = record.id,
                path = record.path,
                operation = record.operation,
                beforeContent = record.beforeContent,
                afterContent = record.afterContent,
                diffPreview = record.diffPreview,
                changedAt = record.changedAt,
                checkpointId = record.checkpointId
            )
        }
    }

    /**
     * 从消息生成会话标题
     */
    fun generateTitle(messages: List<ChatMessageUi>): String {
        // 取用户的第一条非空消息的前 40 字
        val firstUserMsg = messages
            .firstOrNull { it.role == "user" && it.content.isNotBlank() }
            ?.content ?: "新对话"
        return firstUserMsg.take(40).replace("\n", " ").ifBlank { "新对话" }
    }

    // ─── Index 管理 ────────────────────────────

    private fun updateIndex(session: PersistedSession) {
        val summaries = listSessions().toMutableList()
        val existing = summaries.indexOfFirst { it.id == session.id }
        val latestFinalReadinessTelemetry = buildLatestFinalReadinessSessionTelemetry(
            restoreFinalReadinessAuditRecords(session.recentFinalReadinessAudits)
        )
        val summary = SessionSummary(
            id = session.id,
            title = session.title,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            messageCount = session.messages.size,
            providerId = session.providerId,
            modelName = session.modelName,
            projectPath = session.projectPath,
            usageSummary = session.usageSummary,
            subagentSummary = buildSessionSubagentSummary(session),
            finalReadinessAuditCount = session.recentFinalReadinessAudits.size,
            latestFinalReadinessAuditSummary = latestFinalReadinessTelemetry?.statusSummary,
            latestFinalReadinessStatusKind = latestFinalReadinessTelemetry?.statusKind
                ?: FinalReadinessSessionStatusKind.NONE,
            latestFinalReadinessReasonSummary = latestFinalReadinessTelemetry?.reasonSummary
        )
        if (existing >= 0) {
            summaries[existing] = summary
        } else {
            summaries.add(summary)
        }
        writeIndex(summaries)
    }

    private fun scanAndRebuildIndex(): List<SessionSummary> {
        val deletedIds = deletedSessionIdsLocked()
        val summaries = baseDir.listFiles()
            ?.filter {
                it.name.endsWith(".json") &&
                    it.name != INDEX_FILE_NAME &&
                    it.name != DELETED_SESSIONS_FILE_NAME &&
                    it.name != ARCHIVED_SESSIONS_FILE_NAME &&
                    !it.name.endsWith(CHECKPOINT_STORE_SUFFIX)
            }
            ?.mapNotNull { file ->
                try {
                    val session = json.decodeFromString<PersistedSession>(file.readText())
                    if (session.id in deletedIds) {
                        return@mapNotNull null
                    }
                    val latestFinalReadinessTelemetry = buildLatestFinalReadinessSessionTelemetry(
                        restoreFinalReadinessAuditRecords(session.recentFinalReadinessAudits)
                    )
                    SessionSummary(
                        id = session.id,
                        title = session.title,
                        createdAt = session.createdAt,
                        updatedAt = session.updatedAt,
                        messageCount = session.messages.size,
                        providerId = session.providerId,
                        modelName = session.modelName,
                        projectPath = session.projectPath,
                        usageSummary = session.usageSummary,
                        subagentSummary = buildSessionSubagentSummary(session),
                        finalReadinessAuditCount = session.recentFinalReadinessAudits.size,
                        latestFinalReadinessAuditSummary = latestFinalReadinessTelemetry?.statusSummary,
                        latestFinalReadinessStatusKind = latestFinalReadinessTelemetry?.statusKind
                            ?: FinalReadinessSessionStatusKind.NONE,
                        latestFinalReadinessReasonSummary = latestFinalReadinessTelemetry?.reasonSummary
                    )
                } catch (_: Exception) { null }
            }
            ?: emptyList()
        writeIndex(summaries)
        return summaries.sortedByDescending { it.updatedAt }
    }

    private fun buildSessionSubagentSummary(session: PersistedSession): SessionSubagentSummary {
        val sortedBatches = session.subagentBatches
            .sortedByDescending {
                maxOf(
                    it.lastRunFinishedAt ?: 0L,
                    it.finishedAt ?: 0L,
                    it.firstRunStartedAt ?: 0L,
                    it.queuedAt ?: 0L,
                    it.approvalRequestedAt ?: 0L,
                    it.startedAt
                )
            }
        val latestBatch = sortedBatches.firstOrNull()
        val slotUtilizationPercents = sortedBatches.mapNotNull { batch ->
            calculateSlotUtilizationPercent(batch.activeSlots.distinct().size, batch.concurrencyLimit)
        }
        return SessionSubagentSummary(
            runCount = session.subagentRuns.size,
            batchCount = sortedBatches.size,
            activeBatchCount = sortedBatches.count { it.status in setOf("pending_approval", "queued", "running") },
            completedBatchCount = sortedBatches.count { it.status == "completed" },
            partialBatchCount = sortedBatches.count { it.status == "completed_with_failures" },
            failedBatchCount = sortedBatches.count { it.status in setOf("failed", "rejected") },
            totalTokens = session.subagentRuns.sumOf { it.usageSummary.totalTokens },
            totalCostAmount = session.subagentRuns.sumOf { it.usageSummary.resolvedEstimatedCostAmount() },
            totalCostCurrency = session.subagentRuns.firstOrNull()?.usageSummary?.resolvedEstimatedCostCurrency() ?: "USD",
            totalCostUsd = session.subagentRuns.sumOf { it.usageSummary.estimatedCostUsd },
            averageSlotUtilizationPercent = slotUtilizationPercents
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.roundToInt()
                ?: 0,
            peakSlotUtilizationPercent = slotUtilizationPercents.maxOrNull() ?: 0,
            peakActiveSlotCount = sortedBatches.maxOfOrNull { it.activeSlots.distinct().size } ?: 0,
            peakQueuedRuns = sortedBatches.maxOfOrNull { it.queuedRuns } ?: 0,
            latestBatchLabel = latestBatch?.label,
            latestBatchStatus = latestBatch?.status,
            latestBatchUpdatedAt = latestBatch?.let {
                maxOf(
                    it.lastRunFinishedAt ?: 0L,
                    it.finishedAt ?: 0L,
                    it.firstRunStartedAt ?: 0L,
                    it.queuedAt ?: 0L,
                    it.approvalRequestedAt ?: 0L,
                    it.startedAt
                )
            }?.takeIf { it > 0L },
            recentBatches = sortedBatches.take(3).map { batch ->
                SessionSubagentBatchSnapshot(
                    batchId = batch.batchId,
                    label = batch.label,
                    parentGoal = batch.parentGoal,
                    splitStrategyLabel = batch.splitStrategyLabel,
                    splitStrategyDetail = batch.splitStrategyDetail,
                    status = batch.status,
                    runCount = batch.runIds.size,
                    queuePosition = batch.queuePosition,
                    activeSlots = batch.activeSlots,
                    queuedRuns = batch.queuedRuns,
                    runningRuns = batch.runningRuns,
                    concurrencyLimit = batch.concurrencyLimit,
                    summary = batch.summary,
                    statusMessage = batch.statusMessage,
                    approvalRequestedAt = batch.approvalRequestedAt,
                    queuedAt = batch.queuedAt,
                    firstRunStartedAt = batch.firstRunStartedAt,
                    lastRunFinishedAt = batch.lastRunFinishedAt,
                    startedAt = batch.startedAt,
                    finishedAt = batch.finishedAt,
                    updatedAt = maxOf(
                        batch.lastRunFinishedAt ?: 0L,
                        batch.finishedAt ?: 0L,
                        batch.firstRunStartedAt ?: 0L,
                        batch.queuedAt ?: 0L,
                        batch.approvalRequestedAt ?: 0L,
                        batch.startedAt
                    )
                )
            }
        )
    }

    private fun calculateSlotUtilizationPercent(activeSlotCount: Int, concurrencyLimit: Int?): Int? {
        val limit = concurrencyLimit?.takeIf { it > 0 } ?: return null
        return ((activeSlotCount.coerceAtLeast(0).toDouble() / limit.toDouble()) * 100)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun loadCheckpointStore(sessionId: String): PersistedCheckpointStore? {
        val file = checkpointStoreFile(sessionId)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<PersistedCheckpointStore>(file.readText())
        }.getOrNull()
    }

    private fun sessionFile(sessionId: String): File = File(baseDir, "$sessionId.json")

    private fun checkpointStoreFile(sessionId: String): File =
        File(baseDir, "$sessionId$CHECKPOINT_STORE_SUFFIX")

    private fun writeIndex(summaries: List<SessionSummary>) {
        val indexFile = File(baseDir, INDEX_FILE_NAME)
        indexFile.writeText(json.encodeToString(summaries))
    }

    private fun rebuildIndex() {
        scanAndRebuildIndex()
    }

    private fun deletedSessionIdsLocked(): MutableSet<String> {
        if (deletedSessionIdsLoaded) return deletedSessionIds
        val file = File(baseDir, DELETED_SESSIONS_FILE_NAME)
        val restored = if (!file.exists()) {
            emptyList()
        } else {
            try {
                json.decodeFromString<List<String>>(file.readText())
            } catch (_: Exception) {
                emptyList()
            }
        }
        deletedSessionIds.clear()
        deletedSessionIds.addAll(restored)
        deletedSessionIdsLoaded = true
        return deletedSessionIds
    }

    private fun writeDeletedSessionIdsLocked() {
        val file = File(baseDir, DELETED_SESSIONS_FILE_NAME)
        file.writeText(json.encodeToString(deletedSessionIds.toList()))
    }

    private fun archivedSessionsLocked(): List<ArchivedSessionSummary> {
        val file = File(baseDir, ARCHIVED_SESSIONS_FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<ArchivedSessionSummary>>(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeArchivedSessionsLocked(records: List<ArchivedSessionSummary>) {
        val file = File(baseDir, ARCHIVED_SESSIONS_FILE_NAME)
        file.writeText(
            json.encodeToString(
                records.sortedByDescending { it.archivedAt }
            )
        )
    }

    private fun buildArchivedSessionSummary(
        session: PersistedSession,
        archivedAt: Long = System.currentTimeMillis()
    ): ArchivedSessionSummary {
        val latestFinalReadinessTelemetry = buildLatestFinalReadinessSessionTelemetry(
            restoreFinalReadinessAuditRecords(session.recentFinalReadinessAudits)
        )
        val latestAssistantMessage = session.messages
            .asReversed()
            .firstOrNull { it.role == "assistant" && it.content.isNotBlank() }
        val latestUserMessage = session.messages
            .asReversed()
            .firstOrNull { it.role == "user" && it.content.isNotBlank() }
        val latestVisibleMessage = latestAssistantMessage ?: latestUserMessage ?: session.messages
            .asReversed()
            .firstOrNull { it.content.isNotBlank() }
        val retainedSummary = listOfNotNull(
            session.sessionGoal?.trim()?.takeIf { it.isNotBlank() },
            latestAssistantMessage?.content?.trim()?.takeIf { it.isNotBlank() },
            latestUserMessage?.content?.trim()?.takeIf { it.isNotBlank() },
            latestFinalReadinessTelemetry?.statusSummary?.trim()?.takeIf { it.isNotBlank() },
            session.title.trim().takeIf { it.isNotBlank() }
        ).firstOrNull()?.replace("\r\n", "\n")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() }
            ?.take(240)
            .orEmpty()
        return ArchivedSessionSummary(
            originalSessionId = session.id,
            title = session.title,
            sessionGoal = session.sessionGoal,
            projectPath = session.projectPath,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            archivedAt = archivedAt,
            messageCount = session.messages.size,
            retainedSummary = retainedSummary,
            anchorMessageReference = latestVisibleMessage?.let { "${session.id}#${it.id}" },
            latestFinalReadinessSummary = latestFinalReadinessTelemetry?.statusSummary,
            latestFinalReadinessStatusKind = latestFinalReadinessTelemetry?.statusKind
                ?: FinalReadinessSessionStatusKind.NONE
        )
    }

    private fun buildArchivedMemoryCandidate(
        summary: ArchivedSessionSummary
    ): ArchivedMemoryCandidate {
        val titleSource = listOfNotNull(
            summary.sessionGoal?.trim()?.takeIf { it.isNotBlank() },
            summary.title.trim().takeIf { it.isNotBlank() },
            summary.retainedSummary.trim().takeIf { it.isNotBlank() }
        ).first()
        val normalizedTitle = titleSource
            .replace("\r\n", "\n")
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(48)
            .orEmpty()
        return ArchivedMemoryCandidate(
            sourceSessionId = summary.originalSessionId,
            sourceSessionTitle = summary.title,
            sourceProjectPath = summary.projectPath,
            suggestedScope = if (summary.projectPath.isNullOrBlank()) {
                ArchivedMemoryCandidateScope.GLOBAL
            } else {
                ArchivedMemoryCandidateScope.PROJECT
            },
            suggestedTitle = normalizedTitle.ifBlank { "归档记忆" },
            suggestedContent = summary.retainedSummary.ifBlank { summary.title },
            sourceAnchorMessageReference = summary.anchorMessageReference,
            sourceArchivedAt = summary.archivedAt,
            sourceUpdatedAt = summary.updatedAt,
            sourceFinalReadinessSummary = summary.latestFinalReadinessSummary
        )
    }

    private fun ArchivedSessionSummary.isPendingMemoryCandidate(): Boolean {
        return resolvedCandidateStatus() == ArchivedMemoryCandidateStatus.PENDING
    }

    private fun ArchivedSessionSummary.resolvedCandidateStatus(): ArchivedMemoryCandidateStatus {
        return when {
            candidateStatus == ArchivedMemoryCandidateStatus.CONSUMED ||
                consumedAt != null -> ArchivedMemoryCandidateStatus.CONSUMED
            candidateStatus == ArchivedMemoryCandidateStatus.DISMISSED ||
                dismissedAt != null -> ArchivedMemoryCandidateStatus.DISMISSED
            candidateStatus == ArchivedMemoryCandidateStatus.ACCEPTED ||
                acceptedAt != null ||
                !acceptedMemoryId.isNullOrBlank() -> ArchivedMemoryCandidateStatus.ACCEPTED
            else -> ArchivedMemoryCandidateStatus.PENDING
        }
    }

    private companion object {
        const val INDEX_FILE_NAME = "index.json"
        const val DELETED_SESSIONS_FILE_NAME = "deleted_sessions.json"
        const val ARCHIVED_SESSIONS_FILE_NAME = "archived_sessions.json"
        const val CHECKPOINT_STORE_SUFFIX = ".checkpoints.json"
    }
}

/**
 * 会话摘要（用于列表展示）
 */
@Serializable
data class SessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val providerId: String,
    val modelName: String,
    val projectPath: String? = null,
    val usageSummary: UsageSummarySnapshot = UsageSummarySnapshot(),
    val subagentSummary: SessionSubagentSummary = SessionSubagentSummary(),
    val finalReadinessAuditCount: Int = 0,
    val latestFinalReadinessAuditSummary: String? = null,
    val latestFinalReadinessStatusKind: FinalReadinessSessionStatusKind = FinalReadinessSessionStatusKind.NONE,
    val latestFinalReadinessReasonSummary: String? = null
)

@Serializable
data class ArchivedSessionSummary(
    val originalSessionId: String,
    val title: String,
    val sessionGoal: String? = null,
    val projectPath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long,
    val messageCount: Int,
    val retainedSummary: String,
    val anchorMessageReference: String? = null,
    val latestFinalReadinessSummary: String? = null,
    val latestFinalReadinessStatusKind: FinalReadinessSessionStatusKind = FinalReadinessSessionStatusKind.NONE,
    val candidateStatus: ArchivedMemoryCandidateStatus = ArchivedMemoryCandidateStatus.PENDING,
    val acceptedMemoryScope: ArchivedMemoryCandidateScope? = null,
    val acceptedMemoryId: String? = null,
    val acceptedAt: Long? = null,
    val dismissedReason: String? = null,
    val dismissedAt: Long? = null,
    val consumedReason: String? = null,
    val consumedAt: Long? = null,
    val resolutionSource: ArchivedMemoryCandidateResolutionSource? = null,
    val resolutionSummary: String? = null
)

@Serializable
enum class ArchivedMemoryCandidateStatus {
    PENDING,
    ACCEPTED,
    DISMISSED,
    CONSUMED
}

@Serializable
enum class ArchivedMemoryCandidateResolutionSource {
    USER_ACTION,
    AUTO_DEDUPLICATED_REUSE
}

@Serializable
enum class ArchivedMemoryCandidateScope {
    PROJECT,
    GLOBAL
}

@Serializable
data class ArchivedMemoryCandidate(
    val sourceSessionId: String,
    val sourceSessionTitle: String,
    val sourceProjectPath: String? = null,
    val suggestedScope: ArchivedMemoryCandidateScope,
    val suggestedTitle: String,
    val suggestedContent: String,
    val sourceAnchorMessageReference: String? = null,
    val sourceArchivedAt: Long,
    val sourceUpdatedAt: Long,
    val sourceFinalReadinessSummary: String? = null
)

@Serializable
data class SessionSubagentSummary(
    val runCount: Int = 0,
    val batchCount: Int = 0,
    val activeBatchCount: Int = 0,
    val completedBatchCount: Int = 0,
    val partialBatchCount: Int = 0,
    val failedBatchCount: Int = 0,
    val totalTokens: Int = 0,
    val totalCostAmount: Double = 0.0,
    val totalCostCurrency: String = "USD",
    val totalCostUsd: Double = 0.0,
    val averageSlotUtilizationPercent: Int = 0,
    val peakSlotUtilizationPercent: Int = 0,
    val peakActiveSlotCount: Int = 0,
    val peakQueuedRuns: Int = 0,
    val latestBatchLabel: String? = null,
    val latestBatchStatus: String? = null,
    val latestBatchUpdatedAt: Long? = null,
    val recentBatches: List<SessionSubagentBatchSnapshot> = emptyList()
)

fun formatCurrencyAmount(amount: Double, currency: String): String {
    return when (currency.uppercase()) {
        "USD" -> "$${"%.6f".format(amount)}"
        "CNY", "RMB", "CNH" -> "￥${"%.6f".format(amount)}"
        else -> "${"%.6f".format(amount)} ${currency.uppercase()}"
    }
}

@Serializable
data class SessionSubagentBatchSnapshot(
    val batchId: String,
    val label: String,
    val parentGoal: String = "",
    val splitStrategyLabel: String = "",
    val splitStrategyDetail: String = "",
    val status: String,
    val runCount: Int = 0,
    val queuePosition: Int? = null,
    val activeSlots: List<Int> = emptyList(),
    val queuedRuns: Int = 0,
    val runningRuns: Int = 0,
    val concurrencyLimit: Int? = null,
    val summary: String = "",
    val statusMessage: String = "",
    val approvalRequestedAt: Long? = null,
    val queuedAt: Long? = null,
    val firstRunStartedAt: Long? = null,
    val lastRunFinishedAt: Long? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val updatedAt: Long
)
