package dev.reasonix.mobile.core.loop

import android.content.Context
import dev.reasonix.mobile.core.config.GlobalMemory
import dev.reasonix.mobile.core.config.GlobalRule
import dev.reasonix.mobile.core.config.GlobalSkill
import dev.reasonix.mobile.core.config.ProjectToolPreferences
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
    val estimatedCostUsd: Double = 0.0
)

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
    val timestamp: Long
)

@Serializable
data class PersistedToolCallRecord(
    val id: String,
    val toolName: String,
    val args: String,
    val result: String? = null,
    val isSuccess: Boolean = true,
    val timestamp: Long
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
    val changedFiles: List<String> = emptyList()
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
    val rawPlan: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class PersistedFileMention(
    val path: String,
    val displayPath: String
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
    val projectPath: String? = null,
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
    val recentErrors: List<PersistedErrorRecord> = emptyList(),
    val recentToolCalls: List<PersistedToolCallRecord> = emptyList(),
    val recentApprovals: List<PersistedApprovalRecord> = emptyList(),
    val recentApprovalInvalidations: List<PersistedApprovalInvalidationRecord> = emptyList(),
    val approvedApprovalScopes: List<List<String>> = emptyList(),
    val approvedApprovalScopeEntries: List<PersistedApprovedApprovalScope> = emptyList(),
    val pendingWorkflowPlan: PersistedWorkflowPlan? = null,
    val pendingClarificationRequest: PersistedClarificationRequest? = null,
    val lastAutoRouteDecision: PersistedAutoRouteDecision? = null,
    val lastWorkflowFallback: PersistedWorkflowFallback? = null,
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
class ConversationStore(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val baseDir: File
        get() = File(context.filesDir, "conversations").also { it.mkdirs() }

    /**
     * 保存会话
     */
    fun saveSession(session: PersistedSession) {
        val file = File(baseDir, "${session.id}.json")
        file.writeText(json.encodeToString(session))
        updateIndex(session)
    }

    /**
     * 加载会话
     */
    fun loadSession(sessionId: String): PersistedSession? {
        val file = File(baseDir, "${sessionId}.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<PersistedSession>(file.readText())
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
    fun deleteSession(sessionId: String) {
        File(baseDir, "${sessionId}.json").delete()
        rebuildIndex()
    }

    /**
     * 列出所有会话摘要
     */
    fun listSessions(): List<SessionSummary> {
        val indexFile = File(baseDir, "index.json")
        if (!indexFile.exists()) {
            // 无索引时扫描目录重建
            return scanAndRebuildIndex()
        }
        return try {
            json.decodeFromString<List<SessionSummary>>(indexFile.readText())
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
                changedFiles = checkpoint.changedFiles
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
                changedFiles = checkpoint.changedFiles
            )
        }
    }

    fun persistErrorRecords(records: List<ErrorRecordUi>): List<PersistedErrorRecord> {
        return records.map {
            PersistedErrorRecord(it.id, it.message, it.timestamp)
        }
    }

    fun restoreErrorRecords(persisted: List<PersistedErrorRecord>): List<ErrorRecordUi> {
        return persisted.map {
            ErrorRecordUi(it.id, it.message, it.timestamp)
        }
    }

    fun persistToolCallRecords(records: List<ToolCallRecordUi>): List<PersistedToolCallRecord> {
        return records.map {
            PersistedToolCallRecord(it.id, it.toolName, it.args, it.result, it.isSuccess, it.timestamp)
        }
    }

    fun restoreToolCallRecords(persisted: List<PersistedToolCallRecord>): List<ToolCallRecordUi> {
        return persisted.map {
            ToolCallRecordUi(it.id, it.toolName, it.args, it.result, it.isSuccess, it.timestamp)
        }
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
            subagentSummary = buildSessionSubagentSummary(session)
        )
        if (existing >= 0) {
            summaries[existing] = summary
        } else {
            summaries.add(summary)
        }
        writeIndex(summaries)
    }

    private fun scanAndRebuildIndex(): List<SessionSummary> {
        val summaries = baseDir.listFiles()
            ?.filter { it.name.endsWith(".json") && it.name != "index.json" }
            ?.mapNotNull { file ->
                try {
                    val session = json.decodeFromString<PersistedSession>(file.readText())
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
                        subagentSummary = buildSessionSubagentSummary(session)
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

    private fun writeIndex(summaries: List<SessionSummary>) {
        val indexFile = File(baseDir, "index.json")
        indexFile.writeText(json.encodeToString(summaries))
    }

    private fun rebuildIndex() {
        scanAndRebuildIndex()
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
    val subagentSummary: SessionSubagentSummary = SessionSubagentSummary()
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
