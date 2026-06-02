package dev.reasonix.mobile.core.loop

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.Base64
import dev.reasonix.mobile.common.utils.RootFile
import dev.reasonix.mobile.core.config.ConfigRepository
import dev.reasonix.mobile.core.config.GlobalMemory
import dev.reasonix.mobile.core.config.GlobalRule
import dev.reasonix.mobile.core.config.GlobalSkill
import dev.reasonix.mobile.core.config.ProjectWorkflowType
import dev.reasonix.mobile.core.config.ProjectWorkflowRiskLevel
import dev.reasonix.mobile.core.config.ProjectToolPreferences
import dev.reasonix.mobile.core.config.ProviderConfig
import dev.reasonix.mobile.core.config.SkillRunAs
import dev.reasonix.mobile.core.config.WorkflowFailureFallbackContext
import dev.reasonix.mobile.core.config.WorkflowFailureFallbackSource
import dev.reasonix.mobile.core.config.WorkflowFailureType
import dev.reasonix.mobile.core.config.WorkflowFailureFallbackMode
import dev.reasonix.mobile.core.mcp.McpRegistry
import dev.reasonix.mobile.core.mcp.McpServerConfig
import dev.reasonix.mobile.core.mcp.McpTransportType
import dev.reasonix.mobile.core.provider.ChatRequest
import dev.reasonix.mobile.core.provider.ChatMessage
import dev.reasonix.mobile.core.provider.Usage
import dev.reasonix.mobile.core.provider.ProviderRegistry
import dev.reasonix.mobile.core.tool.ApprovalRiskLevel
import dev.reasonix.mobile.core.tool.ToolFileChange
import dev.reasonix.mobile.core.tool.ToolApprovalRequest
import dev.reasonix.mobile.core.tool.*
import dev.reasonix.mobile.core.tool.buildDiffPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    val changedFiles: List<String> = emptyList()
)

data class FileMentionUi(
    val path: String,
    val displayPath: String
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

enum class WorkflowPlanStatusUi {
    READY,
    EXECUTING,
    BLOCKED,
    COMPLETED
}

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
    val createdAt: Long = System.currentTimeMillis()
)

data class ClarificationAnswerUi(
    val question: String,
    val answer: String,
    val answeredAt: Long = System.currentTimeMillis()
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
    val timestamp: Long = System.currentTimeMillis()
)

data class ToolCallRecordUi(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val args: String,
    val result: String? = null,
    val isSuccess: Boolean = true,
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
    val isProcessing: Boolean = false,
    val error: String? = null,
    val sessionId: String = "",
    val sessionTitle: String = "新对话",
    val projectPath: String? = null,
    val projectRules: List<GlobalRule> = emptyList(),
    val projectMemories: List<GlobalMemory> = emptyList(),
    val projectSkills: List<GlobalSkill> = emptyList(),
    val projectKnowledgePaths: List<String> = emptyList(),
    val projectKnowledgeSnapshots: List<ProjectKnowledgeSnapshotUi> = emptyList(),
    val projectToolPreferences: ProjectToolPreferences? = null,
    val usageSummary: UsageSummarySnapshot = UsageSummarySnapshot(),
    val compressionSnapshot: ContextCompressionUi? = null,
    val compressionSnapshots: List<ContextCompressionUi> = emptyList(),
    val checkpoints: List<ConversationCheckpointUi> = emptyList(),
    val fileChanges: List<FileChangeRecordUi> = emptyList(),
    val recentErrors: List<ErrorRecordUi> = emptyList(),
    val recentToolCalls: List<ToolCallRecordUi> = emptyList(),
    val recentApprovals: List<ApprovalRecordUi> = emptyList(),
    val recentApprovalInvalidations: List<ApprovalInvalidationRecordUi> = emptyList(),
    val approvedApprovalScopes: List<ApprovedApprovalScopeUi> = emptyList(),
    val projectApprovalHistory: ProjectApprovalHistoryUi? = null,
    val projectInheritedApprovalScopes: List<InheritedApprovalScopeUi> = emptyList(),
    val pendingApproval: PendingApprovalUi? = null,
    val pendingWorkflowPlan: WorkflowPlanUi? = null,
    val workflowPlanningInProgress: Boolean = false,
    val pendingClarificationRequest: ClarificationRequestUi? = null,
    val clarificationInProgress: Boolean = false,
    val autoRoutingInProgress: Boolean = false,
    val lastAutoRouteDecision: AutoRouteDecisionUi? = null,
    val lastWorkflowFallback: WorkflowFallbackUi? = null
)

data class PendingApprovalUi(
    val toolName: String,
    val summary: String,
    val detail: String,
    val rawArgs: String,
    val riskLevel: ApprovalRiskLevel,
    val explanationLabel: String? = null,
    val explanationDetail: String? = null
)

enum class ConversationExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String
) {
    MARKDOWN("md", "text/markdown", "Markdown"),
    JSON("json", "application/json", "JSON")
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

/**
 * 会话管理器——管理对话生命周期 + 持久化 + MCP
 */
class ChatSessionManager(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val mcpRegistry: McpRegistry? = null
) {
    private companion object {
        val SUBAGENT_TERMINAL_STATUSES = setOf("completed", "failed", "cancelled", "rejected")
        val SUBAGENT_ACTIVE_STATUSES = setOf("pending_approval", "queued", "running", "summarizing", "cancelling")
        val AUTO_COMPLEXITY_KEYWORDS = listOf(
            "自动", "下载", "接入", "集成", "重构", "架构", "规划", "计划", "迁移",
            "批量", "排查", "调试", "分析", "审查", "搜索", "联网", "修复", "实现",
            "mcp", "skill", "workflow", "subagent", "review", "debug", "refactor"
        )
        val AUTO_SKILL_HINT_KEYWORDS = listOf(
            "搜索", "检索", "分析", "审查", "review", "debug", "调试", "排查",
            "总结", "重构", "修复", "下载", "接入", "集成", "规划", "研究"
        )
        val AUTO_DISCOVERY_ACTION_KEYWORDS = listOf(
            "安装", "接入", "添加", "导入", "配置", "启用", "连接", "加载",
            "下载", "搜索", "查找", "接好", "接上", "install", "import",
            "add", "connect", "enable", "setup", "configure", "find"
        )
        val AUTO_DISCOVERY_TARGET_KEYWORDS = listOf(
            "mcp", "skill", "server", "工具", "插件", "tool", "prompt"
        )
        val DRAFT_FENCE_REGEX = Regex("```(?:[a-zA-Z0-9_-]+)?\\s*([\\s\\S]*?)```")
    }

    private data class PendingSubagentExecution(
        val runId: String,
        val execute: suspend () -> Unit
    )

    private data class AutoMatchedSkill(
        val skill: GlobalSkill,
        val score: Int
    )

    private data class DraftAutoAttachOutcome(
        val importedSkillCount: Int = 0,
        val importedMcpCount: Int = 0,
        val connectedMcpCount: Int = 0,
        val summary: String = ""
    ) {
        val importedAnything: Boolean
            get() = importedSkillCount > 0 || importedMcpCount > 0
    }

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val conversationStore = ConversationStore(context)
    private var messageCounter = 0L
    private var agentLoop: AgentLoop? = null
    private var currentStreamingId: Long? = null
    private var currentSessionId: String = ""
    private var lastSessionConfig: ProviderConfig = ProviderConfig()
    private var pendingApprovalDecision: CompletableDeferred<Boolean>? = null
    private var processingCancelledByUser: Boolean = false
    private val approvedApprovalScopes = mutableListOf<PersistedApprovedApprovalScope>()
    private val cancelledSubagentRunIds = mutableSetOf<String>()
    private val subagentExecutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subagentExecutionLock = Any()
    private val runningSubagentExecutionSlots = mutableMapOf<Int, String>()
    private val pendingSubagentExecutions = mutableListOf<PendingSubagentExecution>()
    private val maxConcurrentSubagentExecutions = 2

    private fun recordError(message: String) {
        val record = ErrorRecordUi(message = message)
        _state.value = _state.value.copy(
            recentErrors = (listOf(record) + _state.value.recentErrors).take(30)
        )
    }

    private fun recordToolCall(toolName: String, args: String, result: String? = null, isSuccess: Boolean = true) {
        val record = ToolCallRecordUi(toolName = toolName, args = args, result = result, isSuccess = isSuccess)
        _state.value = _state.value.copy(
            recentToolCalls = (listOf(record) + _state.value.recentToolCalls).take(50)
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
        val record = ApprovalRecordUi(
            toolName = toolName,
            summary = summary,
            decision = decision,
            scopeSummary = scopeSummary,
            explanationLabel = explanationLabel,
            explanationDetail = explanationDetail
        )
        _state.value = _state.value.copy(
            recentApprovals = (listOf(record) + _state.value.recentApprovals).take(30)
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
                    "subagent:cap:shell", "subagent:cap:shell_exec" -> add("subagent:cap:shell_exec")
                }
            }
        }.sorted()
    }

    private fun approvalScopeLabel(token: String): String? = when (token) {
        "subagent:cap:file_write_general" -> "通用文件写入"
        "subagent:cap:file_write_code" -> "代码文件写入"
        "subagent:cap:code_edit_apply" -> "代码编辑"
        "subagent:cap:shell_exec" -> "Shell 执行"
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
    }

    /**
     * 加载已有会话
     */
    fun loadSession(sessionId: String): Boolean {
        val session = conversationStore.loadSession(sessionId) ?: return false
        val restoredCompressionSnapshots = conversationStore.restoreCompressionSnapshots(
            snapshots = session.compressionSnapshots,
            fallbackSnapshot = session.compressionSnapshot
        )
        val currentCompressionSnapshot = restoredCompressionSnapshots
            .lastOrNull { it.active }
            ?: restoredCompressionSnapshots.lastOrNull()
        currentSessionId = session.id
        restoreApprovedApprovalScopes(
            conversationStore.restoreApprovedApprovalScopes(
                session.approvedApprovalScopeEntries,
                session.approvedApprovalScopes
            )
        )
        messageCounter = session.messages.maxOfOrNull { it.id } ?: 0
        _state.value = SessionState(
            messages = conversationStore.restoreMessages(session.messages),
            subagentRuns = conversationStore.restoreSubagentRuns(session.subagentRuns),
            subagentBatches = conversationStore.restoreSubagentBatches(session.subagentBatches),
            sessionId = session.id,
            sessionTitle = session.title,
            projectPath = session.projectPath,
            projectRules = session.projectRules,
            projectMemories = session.projectMemories,
            projectSkills = session.projectSkills,
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
            projectToolPreferences = session.projectToolPreferences,
            usageSummary = session.usageSummary,
            compressionSnapshot = currentCompressionSnapshot,
            compressionSnapshots = restoredCompressionSnapshots,
            checkpoints = conversationStore.restoreConversationCheckpoints(session.checkpoints),
            fileChanges = conversationStore.restoreFileChanges(session.fileChanges),
            recentErrors = conversationStore.restoreErrorRecords(session.recentErrors),
            recentToolCalls = conversationStore.restoreToolCallRecords(session.recentToolCalls),
            recentApprovals = conversationStore.restoreApprovalRecords(session.recentApprovals),
            recentApprovalInvalidations = conversationStore.restoreApprovalInvalidationRecords(
                session.recentApprovalInvalidations
            ),
            approvedApprovalScopes = approvedApprovalScopes
                .map(::buildApprovedApprovalScopeUi)
                .sortedBy { it.capabilities.size },
            pendingWorkflowPlan = session.pendingWorkflowPlan?.let { persisted ->
                normalizeWorkflowPlan(
                    WorkflowPlanUi(
                        id = persisted.id,
                        goal = persisted.goal,
                        summary = persisted.summary,
                        steps = persisted.steps,
                        stageLabels = persisted.stageLabels,
                        currentStageIndex = persisted.currentStageIndex,
                        currentStepIndex = persisted.currentStepIndex,
                        nextStepHint = persisted.nextStepHint,
                        status = persisted.status.toWorkflowPlanStatusUi(),
                        mentionedFiles = persisted.mentionedFiles.map { mention ->
                            FileMentionUi(
                                path = mention.path,
                                displayPath = mention.displayPath
                            )
                        },
                        rawPlan = persisted.rawPlan,
                        createdAt = persisted.createdAt
                    )
                )
            },
            pendingClarificationRequest = session.pendingClarificationRequest?.let { persisted ->
                ClarificationRequestUi(
                    id = persisted.id,
                    goal = persisted.goal,
                    question = persisted.question,
                    mentionedFiles = persisted.mentionedFiles.map { mention ->
                        FileMentionUi(
                            path = mention.path,
                            displayPath = mention.displayPath
                        )
                    },
                    previousAnswers = persisted.previousAnswers.map { answer ->
                        ClarificationAnswerUi(
                            question = answer.question,
                            answer = answer.answer,
                            answeredAt = answer.answeredAt
                        )
                    },
                    turnIndex = persisted.turnIndex,
                    maxTurns = persisted.maxTurns,
                    source = persisted.source.toClarificationSource(),
                    createdAt = persisted.createdAt
                )
            },
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
            }
        )
        syncApprovedApprovalScopesState()
        return true
    }

    /**
     * 新建会话
     */
    fun newSession() {
        // 先保存当前会话
        saveCurrentSession()
        currentSessionId = UUID.randomUUID().toString().take(8)
        clearApprovedApprovalScopes()
        messageCounter = 0
        _state.value = SessionState(sessionId = currentSessionId)
        syncApprovedApprovalScopesState()
    }

    fun startTask(projectPath: String) {
        val normalizedPath = projectPath.trim().removeSuffix("/")
        val inheritedProjectSession = findLatestProjectSession(normalizedPath)
        saveCurrentSession()
        currentSessionId = UUID.randomUUID().toString().take(8)
        clearApprovedApprovalScopes()
        messageCounter = 0
        _state.value = SessionState(
            sessionId = currentSessionId,
            sessionTitle = buildTaskTitle(normalizedPath),
            projectPath = normalizedPath,
            projectRules = inheritedProjectSession?.projectRules.orEmpty(),
            projectMemories = inheritedProjectSession?.projectMemories.orEmpty(),
            projectSkills = inheritedProjectSession?.projectSkills.orEmpty(),
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
            },
            projectToolPreferences = inheritedProjectSession?.projectToolPreferences
        )
        syncApprovedApprovalScopesState()
    }

    /**
     * 列出所有会话
     */
    fun listSessions(): List<SessionSummary> = conversationStore.listSessions()

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String) {
        conversationStore.deleteSession(sessionId)
        if (sessionId == currentSessionId) {
            newSession()
        } else {
            syncApprovedApprovalScopesState()
        }
    }

    fun updateProjectConfig(
        rules: List<GlobalRule>? = null,
        memories: List<GlobalMemory>? = null,
        skills: List<GlobalSkill>? = null
    ) {
        val current = _state.value
        _state.value = current.copy(
            projectRules = rules ?: current.projectRules,
            projectMemories = memories ?: current.projectMemories,
            projectSkills = skills ?: current.projectSkills
        )
        saveCurrentSession()
    }

    fun updateProjectToolPreferences(preferences: ProjectToolPreferences?) {
        _state.value = _state.value.copy(projectToolPreferences = preferences)
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
        _state.value = _state.value.copy(
            autoRoutingInProgress = false,
            lastAutoRouteDecision = null
        )
        saveCurrentSession()
    }

    fun clearLastWorkflowFallback() {
        if (_state.value.lastWorkflowFallback == null) return
        _state.value = _state.value.copy(lastWorkflowFallback = null)
        saveCurrentSession()
    }

    fun dismissPendingWorkflowPlan() {
        if (_state.value.pendingWorkflowPlan == null) return
        _state.value = _state.value.copy(
            pendingWorkflowPlan = null,
            workflowPlanningInProgress = false
        )
        saveCurrentSession()
    }

    fun dismissPendingClarification() {
        if (_state.value.pendingClarificationRequest == null) return
        _state.value = _state.value.copy(
            pendingClarificationRequest = null,
            clarificationInProgress = false
        )
        saveCurrentSession()
    }

    fun searchProjectFiles(query: String, limit: Int = 20): List<FileMentionUi> {
        val projectPath = _state.value.projectPath?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
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
            require(state.messages.isNotEmpty()) { "当前会话还没有可导出的内容" }

            val session = buildPersistedSession(config)
            val exportName = buildExportFileName(session.title, format)
            val content = when (format) {
                ConversationExportFormat.MARKDOWN -> buildMarkdownExport(session)
                ConversationExportFormat.JSON -> conversationStore.encodeSession(session)
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

    /**
     * 发送消息
     */
    suspend fun sendMessage(
        text: String,
        mentionedFiles: List<FileMentionUi> = emptyList(),
        pendingImages: List<PendingImageAttachmentUi> = emptyList()
    ): String? {
        val normalizedText = text.trim()
        if ((normalizedText.isBlank() && pendingImages.isEmpty()) || _state.value.isProcessing) return null
        val globalConfig = configRepository.getConfig()
        val config = globalConfig.applyProjectToolPreferences(_state.value.projectToolPreferences)
        val matchedSkill = if (pendingImages.isEmpty()) {
            matchAutoSkill(normalizedText, config)
        } else {
            null
        }
        if (matchedSkill != null) {
            return executeAutoMatchedSkill(
                goal = normalizedText,
                mentionedFiles = mentionedFiles,
                skill = matchedSkill,
                baseConfig = config
            )
        }
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
            extraSystemContext = buildExecutionProfileContext(
                goal = normalizedText,
                baseConfig = config,
                executionConfig = executionConfig
            )
        )
        return executionToast
    }

    suspend fun autoRouteMessage(
        text: String,
        mentionedFiles: List<FileMentionUi> = emptyList()
    ): String? {
        val normalizedText = text.trim()
        if (normalizedText.isBlank() || _state.value.isProcessing) return null

        val globalConfig = configRepository.getConfig()
        val config = globalConfig.applyProjectToolPreferences(_state.value.projectToolPreferences)
        val matchedSkill = matchAutoSkill(normalizedText, config)
        if (matchedSkill != null) {
            return executeAutoMatchedSkill(
                goal = normalizedText,
                mentionedFiles = mentionedFiles,
                skill = matchedSkill,
                baseConfig = config
            )
        }
        val executionConfig = resolveExecutionConfig(
            baseConfig = config,
            goal = normalizedText,
            mentionedFiles = mentionedFiles
        )
        lastSessionConfig = executionConfig
        val apiKey = executionConfig.getActiveApiKey().trim()
        if (apiKey.isBlank()) {
            _state.value = _state.value.copy(
                error = "⚠️ 未配置 API Key。请先到设置页完成模型配置。"
            )
            return null
        }

        val provider = ProviderRegistry.getActiveProvider(executionConfig.activeProviderId)
        val history = buildHistory()
        val compressionContext = buildCompressionContext()
        _state.value = _state.value.copy(
            error = null,
            autoRoutingInProgress = true,
            lastAutoRouteDecision = null,
            lastWorkflowFallback = null
        )
        val decision: AutoRouteDecisionUi = try {
            val rawText = provider.chat(
                request = ChatRequest(
                    messages = buildAutoRoutePrompt(
                        goal = normalizedText,
                        history = history,
                        compressionContext = compressionContext,
                        mentionedFiles = mentionedFiles
                    ),
                    model = executionConfig.getActiveModel(),
                    temperature = 0.1,
                    maxTokens = minOf(executionConfig.maxTokens, 400),
                    stream = false,
                    reasoningEffort = executionConfig.getActiveReasoningEffort(),
                    thinkingMode = executionConfig.getActiveThinkingMode(),
                    tools = null
                ),
                apiKey = apiKey,
                baseUrl = executionConfig.getActiveBaseUrl()
            ).content?.trim().orEmpty()
            parseAutoRouteDecision(rawText)
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                error = null,
                autoRoutingInProgress = false,
                lastAutoRouteDecision = null
            )
            when (
                resolveWorkflowFailureFallbackMode(
                    config = executionConfig,
                    failureType = WorkflowFailureType.AUTO_ROUTE_FAILURE,
                    hasMentionedFiles = mentionedFiles.isNotEmpty(),
                    source = WorkflowFailureFallbackSource.AUTO_ROUTE,
                    projectType = currentProjectWorkflowType(),
                    providerId = executionConfig.activeProviderId,
                    projectRiskLevel = currentProjectWorkflowRiskLevel()
                )
            ) {
                WorkflowFailureFallbackMode.DIRECT_EXECUTION -> {
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
                        extraSystemContext = buildExecutionProfileContext(
                            goal = normalizedText,
                            baseConfig = config,
                            executionConfig = executionConfig
                        )
                    )
                    applyWorkflowFallback(
                        message = "发送前自动分流失败，已按配置回退为直接执行。",
                        config = executionConfig
                    )
                    return executionToast
                }
                WorkflowFailureFallbackMode.LOCAL_CLARIFICATION -> {
                    _state.value = _state.value.copy(
                        pendingClarificationRequest = buildLocalFallbackClarificationRequest(
                            goal = normalizedText,
                            mentionedFiles = mentionedFiles,
                            previousAnswers = emptyList(),
                            turnIndex = 1,
                            maxTurns = MAX_CLARIFICATION_TURNS,
                            source = ClarificationSource.AUTO_ROUTE
                        ),
                        clarificationInProgress = false
                    )
                    applyWorkflowFallback(
                        message = "发送前自动分流失败，已按配置回退为本地通用澄清问题。",
                        config = executionConfig
                    )
                    return null
                }
                WorkflowFailureFallbackMode.FOLLOW_SCENARIO_DEFAULT -> return null
            }
        }

        _state.value = _state.value.copy(
            autoRoutingInProgress = false,
            lastAutoRouteDecision = decision
        )
        saveCurrentSession(executionConfig)
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
        val config = globalConfig.applyProjectToolPreferences(_state.value.projectToolPreferences)
        lastSessionConfig = config
        val apiKey = config.getActiveApiKey().trim()
        if (apiKey.isBlank()) {
            _state.value = _state.value.copy(
                error = "⚠️ 未配置 API Key。请先到设置页完成模型配置。"
            )
            return
        }

        val provider = ProviderRegistry.getActiveProvider(config.activeProviderId)
        val history = buildHistory()
        val compressionContext = buildCompressionContext()
        _state.value = _state.value.copy(
            isProcessing = true,
            error = null,
            workflowPlanningInProgress = true,
            pendingWorkflowPlan = null,
            lastWorkflowFallback = null
        )
        try {
            val response = provider.chat(
                request = ChatRequest(
                    messages = buildWorkflowPlanPrompt(
                        goal = normalizedGoal,
                        history = history,
                        compressionContext = compressionContext,
                        mentionedFiles = mentionedFiles
                    ),
                    model = config.getActiveModel(),
                    temperature = 0.2,
                    maxTokens = minOf(config.maxTokens, 1200),
                    stream = false,
                    reasoningEffort = config.getActiveReasoningEffort(),
                    thinkingMode = config.getActiveThinkingMode(),
                    tools = null
                ),
                apiKey = apiKey,
                baseUrl = config.getActiveBaseUrl()
            )
            val rawPlan = response.content?.trim().orEmpty()
            _state.value = _state.value.copy(
                pendingWorkflowPlan = parseWorkflowPlan(
                    goal = normalizedGoal,
                    rawPlan = rawPlan,
                    mentionedFiles = mentionedFiles
                ),
                workflowPlanningInProgress = false
            )
            saveCurrentSession(config)
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                error = "⚠️ ${e.message ?: "生成执行计划失败"}",
                workflowPlanningInProgress = false
            )
        } finally {
            _state.value = _state.value.copy(isProcessing = false)
        }
    }

    suspend fun executePendingWorkflowPlan() {
        val plan = _state.value.pendingWorkflowPlan ?: return
        val executingPlan = normalizeWorkflowPlan(
            plan.copy(
                status = WorkflowPlanStatusUi.EXECUTING,
                currentStepIndex = if (plan.steps.isEmpty()) 0 else 1
            )
        )
        _state.value = _state.value.copy(
            pendingWorkflowPlan = executingPlan,
            workflowPlanningInProgress = false
        )
        saveCurrentSession()
        sendMessageInternal(
            userVisibleText = "按计划执行: ${plan.goal}",
            modelInput = buildWorkflowExecutionPrompt(executingPlan),
            mentionedFiles = plan.mentionedFiles,
            executionGoal = plan.goal
        )
        val finalStatus = when {
            _state.value.pendingClarificationRequest != null -> WorkflowPlanStatusUi.BLOCKED
            _state.value.pendingApproval != null -> WorkflowPlanStatusUi.BLOCKED
            !_state.value.error.isNullOrBlank() -> WorkflowPlanStatusUi.BLOCKED
            else -> WorkflowPlanStatusUi.COMPLETED
        }
        val finalPlan = normalizeWorkflowPlan(
            plan.copy(
                status = finalStatus,
                currentStepIndex = when (finalStatus) {
                    WorkflowPlanStatusUi.COMPLETED -> plan.steps.size
                    WorkflowPlanStatusUi.BLOCKED -> minOf(maxOf(executingPlan.currentStepIndex, 1), plan.steps.size)
                    WorkflowPlanStatusUi.EXECUTING -> executingPlan.currentStepIndex
                    WorkflowPlanStatusUi.READY -> 0
                },
                nextStepHint = when (finalStatus) {
                    WorkflowPlanStatusUi.BLOCKED -> buildBlockedWorkflowHint(_state.value)
                    WorkflowPlanStatusUi.COMPLETED -> "当前计划已完成，可继续发起下一轮任务或重做计划。"
                    WorkflowPlanStatusUi.EXECUTING -> executingPlan.nextStepHint
                    WorkflowPlanStatusUi.READY -> plan.nextStepHint
                }
            )
        )
        _state.value = _state.value.copy(pendingWorkflowPlan = finalPlan)
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
        val config = globalConfig.applyProjectToolPreferences(_state.value.projectToolPreferences)
        lastSessionConfig = config
        val apiKey = config.getActiveApiKey().trim()
        if (apiKey.isBlank()) {
            _state.value = _state.value.copy(
                error = "⚠️ 未配置 API Key。请先到设置页完成模型配置。"
            )
            return
        }

        val provider = ProviderRegistry.getActiveProvider(config.activeProviderId)
        val history = buildHistory()
        val compressionContext = buildCompressionContext()
        _state.value = _state.value.copy(
            isProcessing = true,
            error = null,
            clarificationInProgress = true,
            pendingClarificationRequest = null,
            lastWorkflowFallback = null
        )
        try {
            val response = provider.chat(
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
                    model = config.getActiveModel(),
                    temperature = 0.2,
                    maxTokens = minOf(config.maxTokens, 600),
                    stream = false,
                    reasoningEffort = config.getActiveReasoningEffort(),
                    thinkingMode = config.getActiveThinkingMode(),
                    tools = null
                ),
                apiKey = apiKey,
                baseUrl = config.getActiveBaseUrl()
            )
            _state.value = _state.value.copy(
                pendingClarificationRequest = parseClarificationQuestion(
                    goal = normalizedGoal,
                    rawQuestion = response.content?.trim().orEmpty(),
                    mentionedFiles = mentionedFiles,
                    previousAnswers = previousAnswers,
                    turnIndex = turnIndex,
                    maxTurns = maxTurns,
                    source = source
                ),
                clarificationInProgress = false
            )
            saveCurrentSession(config)
        } catch (e: Exception) {
            when (
                resolveWorkflowFailureFallbackMode(
                    config = config,
                    failureType = WorkflowFailureType.CLARIFICATION_GENERATION_FAILURE,
                    hasMentionedFiles = mentionedFiles.isNotEmpty(),
                    source = source.toWorkflowFailureFallbackSource(),
                    clarificationTurnIndex = turnIndex,
                    projectType = currentProjectWorkflowType(),
                    providerId = config.activeProviderId,
                    projectRiskLevel = currentProjectWorkflowRiskLevel()
                )
            ) {
                WorkflowFailureFallbackMode.DIRECT_EXECUTION -> {
                    _state.value = _state.value.copy(
                        error = null,
                        isProcessing = false,
                        clarificationInProgress = false,
                        pendingClarificationRequest = null
                    )
                    val fallbackModelInput = if (previousAnswers.isNotEmpty()) {
                        buildClarificationExecutionPrompt(
                            goal = normalizedGoal,
                            answers = previousAnswers
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
                        existingClarificationAnswers = previousAnswers
                    )
                    applyWorkflowFallback(
                        message = "澄清问题生成失败，已按配置回退为直接执行。",
                        config = config
                    )
                }
                WorkflowFailureFallbackMode.LOCAL_CLARIFICATION -> {
                    _state.value = _state.value.copy(
                        error = null,
                        clarificationInProgress = false,
                        pendingClarificationRequest = buildLocalFallbackClarificationRequest(
                            goal = normalizedGoal,
                            mentionedFiles = mentionedFiles,
                            previousAnswers = previousAnswers,
                            turnIndex = turnIndex,
                            maxTurns = maxTurns,
                            source = source
                        )
                    )
                    applyWorkflowFallback(
                        message = "澄清问题生成失败，已按配置回退为本地通用澄清问题。",
                        config = config
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
        val accumulatedAnswers = request.previousAnswers + ClarificationAnswerUi(
            question = request.question,
            answer = normalizedAnswer
        )
        _state.value = _state.value.copy(
            isProcessing = true,
            error = null,
            clarificationInProgress = true,
            lastWorkflowFallback = null
        )
        if (request.turnIndex >= request.maxTurns) {
            _state.value = _state.value.copy(
                isProcessing = false,
                pendingClarificationRequest = null,
                clarificationInProgress = false
            )
            sendMessageInternal(
                userVisibleText = "补充回答: $normalizedAnswer",
                modelInput = buildClarificationExecutionPrompt(
                    goal = request.goal,
                    answers = accumulatedAnswers
                ),
                mentionedFiles = request.mentionedFiles,
                executionGoal = request.goal,
                existingClarificationAnswers = accumulatedAnswers
            )
            return
        }

        val globalConfig = configRepository.getConfig()
        val config = globalConfig.applyProjectToolPreferences(_state.value.projectToolPreferences)
        lastSessionConfig = config
        val apiKey = config.getActiveApiKey().trim()
        if (apiKey.isBlank()) {
            _state.value = _state.value.copy(
                isProcessing = false,
                clarificationInProgress = false,
                error = "⚠️ 未配置 API Key。请先到设置页完成模型配置。"
            )
            return
        }

        val provider = ProviderRegistry.getActiveProvider(config.activeProviderId)
        val history = buildHistory()
        val compressionContext = buildCompressionContext()
        val followUpDecision = try {
            val rawText = provider.chat(
                request = ChatRequest(
                    messages = buildClarificationFollowUpPrompt(
                        goal = request.goal,
                        history = history,
                        compressionContext = compressionContext,
                        mentionedFiles = request.mentionedFiles,
                        answers = accumulatedAnswers,
                        nextTurnIndex = request.turnIndex + 1,
                        maxTurns = request.maxTurns
                    ),
                    model = config.getActiveModel(),
                    temperature = 0.1,
                    maxTokens = minOf(config.maxTokens, 500),
                    stream = false,
                    reasoningEffort = config.getActiveReasoningEffort(),
                    thinkingMode = config.getActiveThinkingMode(),
                    tools = null
                ),
                apiKey = apiKey,
                baseUrl = config.getActiveBaseUrl()
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
                    providerId = config.activeProviderId,
                    projectRiskLevel = currentProjectWorkflowRiskLevel()
                )
            ) {
                WorkflowFailureFallbackMode.DIRECT_EXECUTION -> {
                    _state.value = _state.value.copy(
                        isProcessing = false,
                        clarificationInProgress = false,
                        error = null,
                        pendingClarificationRequest = null
                    )
                    sendMessageInternal(
                        userVisibleText = "补充回答: $normalizedAnswer",
                        modelInput = buildClarificationExecutionPrompt(
                            goal = request.goal,
                            answers = accumulatedAnswers
                        ),
                        mentionedFiles = request.mentionedFiles,
                        executionGoal = request.goal,
                        existingClarificationAnswers = accumulatedAnswers
                    )
                    applyWorkflowFallback(
                        message = "判断是否继续澄清失败，已按配置回退为直接继续执行。",
                        config = config
                    )
                }
                WorkflowFailureFallbackMode.LOCAL_CLARIFICATION -> {
                    _state.value = _state.value.copy(
                        isProcessing = false,
                        clarificationInProgress = false,
                        error = null,
                        pendingClarificationRequest = buildLocalFallbackClarificationRequest(
                            goal = request.goal,
                            mentionedFiles = request.mentionedFiles,
                            previousAnswers = accumulatedAnswers,
                            turnIndex = request.turnIndex + 1,
                            maxTurns = request.maxTurns,
                            source = request.source
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

        when (followUpDecision.action) {
            AutoRouteAction.CLARIFY -> {
                _state.value = _state.value.copy(
                    isProcessing = false,
                    clarificationInProgress = false,
                    pendingClarificationRequest = ClarificationRequestUi(
                        goal = request.goal,
                        question = followUpDecision.question
                            ?: "基于你刚补充的信息，我还需要再确认一个关键点：你最希望我优先按哪种方式继续？",
                        mentionedFiles = request.mentionedFiles,
                        previousAnswers = accumulatedAnswers,
                        turnIndex = request.turnIndex + 1,
                        maxTurns = request.maxTurns,
                        source = request.source
                    )
                )
                saveCurrentSession(config)
            }

            else -> {
                _state.value = _state.value.copy(
                    isProcessing = false,
                    pendingClarificationRequest = null,
                    clarificationInProgress = false
                )
                sendMessageInternal(
                    userVisibleText = "补充回答: $normalizedAnswer",
                    modelInput = buildClarificationExecutionPrompt(
                        goal = request.goal,
                        answers = accumulatedAnswers
                    ),
                    mentionedFiles = request.mentionedFiles,
                    executionGoal = request.goal,
                    existingClarificationAnswers = accumulatedAnswers
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
        extraSystemContext: String? = null
    ) {
        if ((modelInput.isBlank() && pendingImages.isEmpty()) || _state.value.isProcessing) return
        val config = configOverride ?: configRepository.getConfig()
            .applyProjectToolPreferences(_state.value.projectToolPreferences)
        lastSessionConfig = config
        val apiKey = config.getActiveApiKey()
        if (apiKey.isBlank()) {
            appendMessage(ChatMessageUi(
                id = nextId(), role = "system",
                content = "⚠️ 未配置 API Key。请点击底部「设置」→ 选择 AI 提供商 → 填入你的 API Key。"
            ))
            _state.value = _state.value.copy(
                isProcessing = false,
                lastWorkflowFallback = null
            )
            return
        }

        val provider = ProviderRegistry.getActiveProvider(config.activeProviderId)

        // 创建 ToolRegistry（包含 MCP 工具）
        val toolRegistry = createToolRegistry(provider, config)
        mcpRegistry?.let { mcp ->
            mcp.getMcpTools()
                .filter { config.isMcpToolEnabled(it.name) }
                .forEach { toolRegistry.register(it) }
        }

        agentLoop = AgentLoop(provider, toolRegistry, config)
        val history = buildHistory()
        val compressionContext = buildCompressionContext()
        val projectContext = buildCurrentProjectContext()
        val projectSkillsContext = buildProjectSkillsContext()
        val mcpToolsContext = buildEnabledMcpToolsContext()
        val fileMentionContext = buildMentionedFilesContext(mentionedFiles)
        val executionInterruptContext = buildExecutionInterruptionContext(existingClarificationAnswers)
        val importedImages = importPendingImageAttachments(pendingImages)
        if (pendingImages.isNotEmpty() && importedImages.isEmpty()) {
            appendMessage(
                ChatMessageUi(
                    id = nextId(),
                    role = "system",
                    content = "⚠️ 当前图片读取失败，请重新选择图片后再试。"
                )
            )
            return
        }
        val multimodalContext = if (importedImages.isNotEmpty()) {
            buildMultimodalSystemContext(importedImages.size)
        } else {
            null
        }
        val additionalContext = listOfNotNull(
            compressionContext,
            projectContext,
            projectSkillsContext,
            mcpToolsContext,
            fileMentionContext,
            executionInterruptContext,
            multimodalContext,
            extraSystemContext?.trim()?.takeIf { it.isNotBlank() }
        )
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
        val resolvedModelInput = modelInput.ifBlank {
            if (importedImages.isNotEmpty()) {
                "请分析这${if (importedImages.size > 1) "${importedImages.size}张" else "张"}图片，并提取其中的关键信息。"
            } else {
                ""
            }
        }

        // 添加用户消息
        val userMsg = ChatMessageUi(
            id = nextId(),
            role = "user",
            content = userVisibleText,
            imageAttachments = importedImages
        )
        appendMessage(userMsg)

        // 创建空的助手消息
        val assistantMsg = ChatMessageUi(
            id = nextId(), role = "assistant", content = "",
            isStreaming = true
        )
        appendMessage(assistantMsg)
        currentStreamingId = assistantMsg.id
        _state.value = _state.value.copy(
            isProcessing = true,
            error = null,
            lastWorkflowFallback = null
        )
        processingCancelledByUser = false

        try {
            val userModelMessage = ChatMessage(
                role = "user",
                content = resolvedModelInput.ifBlank { null },
                images = importedImages.mapNotNull(::buildChatImageAttachment)
            )
            agentLoop?.processMessage(
                userMessage = userModelMessage,
                history = history,
                additionalSystemContext = additionalContext,
                onEvent = { event ->
                    when (event) {
                        is AgentEvent.ContentDelta -> appendToStreaming(event.text)
                        is AgentEvent.ReasoningDelta -> appendToStreamingReasoning(event.text)
                        is AgentEvent.ToolExecution -> {
                            if (event.isPartial && !config.showDebugToolDetails) {
                                return@processMessage
                            }
                            finalizeStreaming()
                            val toolMsg = ChatMessageUi(
                                id = nextId(), role = "tool_exec",
                                content = buildToolExecutionMessage(
                                    toolName = event.toolName,
                                    args = event.args,
                                    callId = event.callId,
                                    showDebugDetails = config.showDebugToolDetails
                                )
                            )
                            appendMessage(toolMsg)
                        }
                        is AgentEvent.ToolResult -> {
                            recordToolCall(
                                toolName = event.toolName,
                                args = event.args,
                                result = event.result,
                                isSuccess = !event.result.startsWith("Error")
                            )
                            val fileChanges = recordFileChanges(
                                toolName = event.toolName,
                                fileChanges = event.fileChanges
                            )
                            val resultMsg = ChatMessageUi(
                                id = nextId(), role = "tool_exec",
                                content = buildToolResultMessage(
                                    toolName = event.toolName,
                                    result = event.result,
                                    fileChanges = fileChanges
                                )
                            )
                            appendMessage(resultMsg)
                        }
                        is AgentEvent.UsageUpdate -> {
                            appendUsage(event.usage, config)
                        }
                        is AgentEvent.Error -> {
                            finalizeStreaming()
                            recordError(event.message)
                            appendMessage(ChatMessageUi(
                                id = nextId(), role = "system",
                                content = "⚠️ ${event.message}"
                            ))
                        }
                        is AgentEvent.Done -> finalizeStreaming()
                    }
                },
                requestApproval = { request ->
                    waitForApproval(request)
                }
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

            val autoAttachOutcome = autoAttachDraftsFromAssistantMessage(
                assistantMessageId = assistantMsg.id,
                config = config
            )
            if (autoAttachOutcome.importedAnything && autoAttachOutcome.summary.isNotBlank()) {
                appendMessage(
                    ChatMessageUi(
                        id = nextId(),
                        role = "system",
                        content = autoAttachOutcome.summary
                    )
                )
            }

            _state.value = _state.value.copy(
                pendingWorkflowPlan = null,
                workflowPlanningInProgress = false,
                pendingClarificationRequest = null,
                clarificationInProgress = false,
                autoRoutingInProgress = false
            )
            // 自动保存
            saveCurrentSession(config)
        } catch (e: CancellationException) {
            finalizeStreaming()
            if (!processingCancelledByUser) {
                appendMessage(
                    ChatMessageUi(
                        id = nextId(),
                        role = "system",
                        content = "⏹️ 当前处理已取消。"
                    )
                )
            }
        } catch (e: Exception) {
            finalizeStreaming()
            _state.value = _state.value.copy(
                error = "❌ ${e.message ?: e.javaClass.simpleName}"
            )
        } finally {
            _state.value = _state.value.copy(isProcessing = false)
            processingCancelledByUser = false
        }
    }

    /**
     * 清空当前对话
     */
    fun clear() {
        _state.value = SessionState(sessionId = currentSessionId)
        messageCounter = 0
        currentStreamingId = null
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

    fun rollbackFileCheckpoint(checkpointId: String): Result<Int> {
        return runCatching {
            val state = _state.value
            require(!state.isProcessing) { "处理中暂时不能回滚文件修改" }

            val checkpoint = state.checkpoints.firstOrNull { it.id == checkpointId }
                ?: error("未找到对应的修改批次")
            val records = state.fileChanges
                .filter { it.checkpointId == checkpointId }
                .sortedBy { it.changedAt }
            require(records.isNotEmpty()) { "当前批次没有可回滚的文件记录" }

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

            val rollbackCheckpoint = ConversationCheckpointUi(
                id = rollbackCheckpointId,
                messageIndex = state.messages.lastIndex,
                createdAt = rollbackRecords.maxOfOrNull { it.changedAt } ?: System.currentTimeMillis(),
                summary = "回滚 ${records.size} 个文件: ${checkpoint.changedFiles.take(3).joinToString("、")}" +
                    if (records.size > 3) " 等" else "",
                changedFiles = rollbackRecords.map { it.path }
            )

            _state.value = state.copy(
                fileChanges = (rollbackRecords + state.fileChanges).take(MAX_FILE_CHANGE_HISTORY),
                checkpoints = (listOf(rollbackCheckpoint) + state.checkpoints).take(MAX_CHECKPOINT_HISTORY)
            )
            appendMessage(
                ChatMessageUi(
                    id = nextId(),
                    role = "system",
                    content = "已回滚修改批次：${checkpoint.summary}"
                )
            )
            saveCurrentSession()
            rollbackRecords.size
        }
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

        val keptMessages = state.messages.take(targetExclusiveIndex)
        val keptCheckpoints = state.checkpoints
            .filter { it.messageIndex < targetExclusiveIndex }
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

        _state.value = state.copy(
            messages = keptMessages,
            subagentRuns = state.subagentRuns.filter { it.runId in keptSubagentRunIds },
            subagentBatches = state.subagentBatches.filter { it.batchId in keptSubagentBatchIds },
            isProcessing = false,
            error = null,
            compressionSnapshot = currentCompressionSnapshot,
            compressionSnapshots = keptCompressionSnapshots,
            checkpoints = keptCheckpoints,
            fileChanges = keptFileChanges,
            pendingApproval = null
        )
        currentStreamingId = null
        clearPendingApproval()
        saveCurrentSession()
        return true
    }

    private fun rollbackFutureFileChangesForMessageIndex(
        state: SessionState,
        targetExclusiveIndex: Int
    ): Result<Unit> {
        return runCatching {
            val checkpointsToRollback = state.checkpoints
                .filter { it.messageIndex >= targetExclusiveIndex }
                .sortedByDescending { it.createdAt }

            checkpointsToRollback.forEach { checkpoint ->
                state.fileChanges
                    .filter { it.checkpointId == checkpoint.id }
                    .sortedByDescending { it.changedAt }
                    .forEach { record ->
                        restoreFileRecord(record).getOrThrow()
                    }
            }
        }
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
        val state = _state.value
        if (!shouldPersistSession(state)) return
        conversationStore.saveSession(buildPersistedSession(config))
    }

    private fun shouldPersistSession(state: SessionState): Boolean {
        return state.messages.isNotEmpty() ||
            !state.projectPath.isNullOrBlank() ||
            state.projectRules.isNotEmpty() ||
            state.projectMemories.isNotEmpty() ||
            state.projectSkills.isNotEmpty() ||
            state.projectKnowledgePaths.isNotEmpty() ||
            state.projectToolPreferences != null ||
            state.pendingWorkflowPlan != null ||
            state.pendingClarificationRequest != null ||
            state.lastAutoRouteDecision != null ||
            state.lastWorkflowFallback != null ||
            state.subagentRuns.isNotEmpty() ||
            state.compressionSnapshot != null ||
            state.compressionSnapshots.isNotEmpty() ||
            state.checkpoints.isNotEmpty() ||
            state.fileChanges.isNotEmpty() ||
            state.recentErrors.isNotEmpty() ||
            state.recentToolCalls.isNotEmpty() ||
            state.recentApprovals.isNotEmpty()
    }

    fun approvePendingTool(): Boolean {
        // region debug-point approval-confirm-crash-approve-start
        val debugStartAt = System.currentTimeMillis()
        Log.i(
            "ReasonixDebug",
            "approvePendingTool:start pending=${_state.value.pendingApproval?.toolName} thread=${Thread.currentThread().name}"
        )
        // endregion debug-point approval-confirm-crash-approve-start
        val deferred = pendingApprovalDecision ?: return false
        if (!deferred.isCompleted) {
            // region debug-point approval-confirm-crash-approve-complete
            Log.i(
                "ReasonixDebug",
                "approvePendingTool:complete pending=${_state.value.pendingApproval?.toolName} thread=${Thread.currentThread().name}"
            )
            // endregion debug-point approval-confirm-crash-approve-complete
            deferred.complete(true)
        }
        clearPendingApproval()
        // region debug-point approval-confirm-crash-approve-finish
        Log.i(
            "ReasonixDebug",
            "approvePendingTool:finish pendingCleared=true costMs=${System.currentTimeMillis() - debugStartAt} thread=${Thread.currentThread().name}"
        )
        // endregion debug-point approval-confirm-crash-approve-finish
        return true
    }

    fun rejectPendingTool(): Boolean {
        val deferred = pendingApprovalDecision ?: return false
        if (!deferred.isCompleted) {
            deferred.complete(false)
        }
        clearPendingApproval()
        return true
    }

    fun cancelCurrentProcessing(): Boolean {
        val hasActiveProcessing = _state.value.isProcessing || pendingApprovalDecision != null
        if (!hasActiveProcessing) return false
        processingCancelledByUser = true
        agentLoop = null
        val deferred = pendingApprovalDecision
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(false)
        }
        clearPendingApproval()
        finalizeStreaming()
        trimDanglingStreamingAssistant()
        _state.value = _state.value.copy(
            isProcessing = false,
            error = null,
            autoRoutingInProgress = false,
            workflowPlanningInProgress = false,
            clarificationInProgress = false
        )
        appendMessage(
            ChatMessageUi(
                id = nextId(),
                role = "system",
                content = "⏹️ 已终止当前处理，可直接修改提示词后重新发送。"
            )
        )
        saveCurrentSession()
        return true
    }

    private fun nextId() = ++messageCounter

    private fun appendMessage(msg: ChatMessageUi) {
        _state.value = _state.value.copy(
            messages = _state.value.messages + msg
        )
    }

    private fun updateMessage(messageId: Long, transform: (ChatMessageUi) -> ChatMessageUi) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { msg ->
                if (msg.id == messageId) transform(msg) else msg
            }
        )
    }

    private fun appendToStreaming(text: String) {
        val id = currentStreamingId ?: return
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { msg ->
                if (msg.id == id) msg.copy(content = msg.content + text) else msg
            }
        )
    }

    private fun appendToStreamingReasoning(text: String) {
        val id = currentStreamingId ?: return
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { msg ->
                if (msg.id == id) msg.copy(reasoning = (msg.reasoning ?: "") + text) else msg
            }
        )
    }

    private fun finalizeStreaming() {
        val id = currentStreamingId ?: return
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { msg ->
                if (msg.id == id) msg.copy(isStreaming = false) else msg
            }
        )
        currentStreamingId = null
    }

    private fun trimDanglingStreamingAssistant() {
        val messages = _state.value.messages
        val trailingAssistant = messages.lastOrNull() ?: return
        if (trailingAssistant.role != "assistant") return
        if (trailingAssistant.content.isNotBlank() || !trailingAssistant.reasoning.isNullOrBlank()) return
        _state.value = _state.value.copy(messages = messages.dropLast(1))
    }

    private fun buildHistory(): List<ChatMessage> {
        val state = _state.value
        val compression = state.compressionSnapshot?.takeIf { it.active }
        val baseMessages = if (compression != null) {
            state.messages.filter { it.id > compression.sourceEndMessageId }
        } else {
            state.messages
        }

        return baseMessages
            .filter { it.role == "user" || it.role == "assistant" || isToolResultHistoryMessage(it) }
            .map { uiMsg ->
                ChatMessage(
                    role = if (uiMsg.role == "tool_exec") "assistant" else uiMsg.role,
                    content = when (uiMsg.role) {
                        "tool_exec" -> summarizeToolResultForHistory(uiMsg.content)
                        else -> uiMsg.content.ifBlank { null }
                    },
                    images = uiMsg.imageAttachments.mapNotNull(::buildChatImageAttachment)
                )
            }
            .filter { it.content != null || it.images.isNotEmpty() }
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
    ): dev.reasonix.mobile.core.provider.ChatImageAttachment? {
        val file = File(attachment.localCachePath)
        if (!file.exists()) return null
        val encoded = runCatching {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }.getOrNull() ?: return null
        return dev.reasonix.mobile.core.provider.ChatImageAttachment(
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

    private fun createToolRegistry(provider: dev.reasonix.mobile.core.provider.ModelProvider, config: ProviderConfig): ToolRegistry {
        val registry = ToolRegistry()
        if (config.isBuiltinToolEnabled("shell")) {
            registry.register(ShellTool())
        }
        if (config.isBuiltinToolEnabled("file")) {
            registry.register(FileTool(config.getEnabledFileToolOperations()))
        }
        if (config.isBuiltinToolEnabled("code_edit")) {
            registry.register(CodeEditTool())
        }
        if (config.isBuiltinToolEnabled("web_search")) {
            registry.register(WebSearchTool())
        }
        if (config.isBuiltinToolEnabled("web_fetch")) {
            registry.register(WebFetchTool())
        }
        if (config.isBuiltinToolEnabled("subagent")) {
            registry.register(
                createSubagentTool(provider, config)
            )
        }
        return registry
    }

    private fun createSubagentTool(
        provider: dev.reasonix.mobile.core.provider.ModelProvider,
        config: ProviderConfig
    ): SubagentTool {
        return SubagentTool(
            provider = provider,
            baseConfig = config,
            projectTemplates = _state.value.projectToolPreferences?.subagentTemplates.orEmpty(),
            allowedFileOperations = config.getEnabledSubagentFileOperations(),
            writableFileOperations = config.getEnabledFileToolOperations().filter { it == "write" }.toSet(),
            allowWebSearchTool = config.isBuiltinToolEnabled("web_search"),
            allowCodeEditTool = config.isBuiltinToolEnabled("code_edit"),
            allowShellTool = config.isBuiltinToolEnabled("shell"),
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

    private suspend fun waitForApproval(request: ToolApprovalRequest): Boolean {
        val scopeSummary = approvalScopeSummaryOrNull(request.approvalScopeTokens)
        val requestSubject = approvalRequestSubjectLabel(request)
        val approvalDecision = lastSessionConfig.evaluateApprovalRequirement(
            riskLevel = request.riskLevel,
            toolName = request.toolName,
            approvalScopeTokens = request.approvalScopeTokens,
            commandBoundaryValue = request.commandBoundaryValue,
            pathBoundaryValue = request.pathBoundaryValue
        )
        if (isApprovalScopeAlreadyGranted(request)) {
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
        findAutoInheritedApprovalCandidate(request)?.let { candidate ->
            val inherited = mergeApprovedApprovalScope(
                buildAutoInheritedApprovalScope(
                    scope = candidate.scope,
                    sourceSessionId = candidate.sourceSessionId,
                    sourceSessionTitle = candidate.sourceSessionTitle,
                    sourceUpdatedAt = candidate.sourceUpdatedAt
                )
            )
            if (inherited) {
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
        }
        if (!approvalDecision.requiresApproval) {
            registerApprovedApprovalScope(request)
            recordApproval(
                toolName = request.toolName,
                summary = request.summary,
                decision = "自动批准",
                scopeSummary = scopeSummary,
                explanationLabel = approvalDecision.explanationLabel,
                explanationDetail = buildString {
                    append(approvalDecision.explanationDetail)
                    scopeSummary?.let { append("，并记录范围：$it") }
                    append("。")
                }
            )
            return true
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingApprovalDecision = deferred
        _state.value = _state.value.copy(
            pendingApproval = PendingApprovalUi(
                toolName = request.toolName,
                summary = request.summary,
                detail = request.detail,
                rawArgs = request.rawArgs,
                riskLevel = request.riskLevel,
                explanationLabel = approvalDecision.explanationLabel,
                explanationDetail = approvalDecision.explanationDetail
            )
        )
        return try {
            val decision = deferred.await()
            if (decision) {
                registerApprovedApprovalScope(request)
            }
            recordApproval(
                toolName = request.toolName,
                summary = request.summary,
                decision = if (decision) "Approved" else "Rejected",
                scopeSummary = scopeSummary,
                explanationLabel = if (decision) "用户确认放行" else "用户拒绝",
                explanationDetail = buildString {
                    append(approvalDecision.explanationDetail)
                    append("\n")
                    append("本次")
                    append(requestSubject)
                    append("未命中会话复用、项目自动继承或当前审批放行规则，因此进入人工审批；用户已")
                    append(if (decision) "批准" else "拒绝")
                    append("该请求")
                    scopeSummary?.let { append("，申请范围：$it") }
                    append("。")
                }
            )
            decision
        } finally {
            clearPendingApproval()
        }
    }

    private fun clearPendingApproval() {
        pendingApprovalDecision = null
        _state.value = _state.value.copy(pendingApproval = null)
    }

    private fun appendUsage(usage: Usage, config: ProviderConfig) {
        val current = _state.value.usageSummary
        val updatedPromptTokens = current.promptTokens + usage.promptTokens
        val updatedCompletionTokens = current.completionTokens + usage.completionTokens
        val updatedTotalTokens = current.totalTokens + usage.totalTokens
        val updatedCacheHit = current.promptCacheHitTokens + (usage.promptCacheHitTokens ?: 0)
        val updatedCacheMiss = current.promptCacheMissTokens + (usage.promptCacheMissTokens ?: 0)
        val updatedEstimatedCost = current.estimatedCostUsd + config.estimateCostUsd(
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
                estimatedCostUsd = updatedEstimatedCost
            )
        )
    }

    private fun appendSubagentEvent(event: SubagentUiEvent) {
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

    private fun refreshBackgroundExecutionState() {
        val (slotAssignments, queuedRunIds) = synchronized(subagentExecutionLock) {
            runningSubagentExecutionSlots.toMap() to pendingSubagentExecutions.map { it.runId }
        }
        val runningByRunId = slotAssignments.entries.associate { (slot, runId) -> runId to slot }
        val currentRuns = _state.value.subagentRuns
        val updatedRuns = currentRuns.map { run ->
            val assignedSlot = runningByRunId[run.runId]
            val queuePosition = queuedRunIds.indexOf(run.runId)
                .takeIf { it >= 0 }
                ?.plus(1)
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
        saveCurrentSession()
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
        val endAt = run.executionStartedAt
            ?: if (run.status == "queued") now else null
            ?: return null
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
        val endAt = batch.firstRunStartedAt
            ?: if (batch.status == "queued") now else null
            ?: return null
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
        if (removePendingSubagentExecution(runId)) {
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
        val config = globalConfig.applyProjectToolPreferences(_state.value.projectToolPreferences)
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

    private suspend fun executeAutoMatchedSkill(
        goal: String,
        mentionedFiles: List<FileMentionUi>,
        skill: GlobalSkill,
        baseConfig: ProviderConfig
    ): String? {
        val executionConfig = resolveExecutionConfig(
            baseConfig = baseConfig,
            goal = goal,
            mentionedFiles = mentionedFiles,
            matchedSkill = skill
        )
        val executionToast = buildExecutionProfileToast(
            baseConfig = baseConfig,
            executionConfig = executionConfig
        )
        val skillLabel = skill.title.ifBlank { "未命名 Skill" }
        appendMessage(
            ChatMessageUi(
                id = nextId(),
                role = "system",
                content = buildString {
                    append("已自动匹配 Skill「")
                    append(skillLabel)
                    append("」，")
                    append(if (skill.runAs == SkillRunAs.SUBAGENT) "按子代理模式执行。" else "按行内模式执行。")
                }
            )
        )
        if (_state.value.sessionTitle == "新对话") {
            _state.value = _state.value.copy(
                sessionTitle = conversationStore.generateTitle(_state.value.messages)
            )
        }
        if (skill.runAs == SkillRunAs.SUBAGENT) {
            val apiKey = executionConfig.getActiveApiKey().trim()
            if (apiKey.isBlank()) {
                _state.value = _state.value.copy(error = "⚠️ 未配置 API Key。请先到设置页完成模型配置。")
                return null
            }
            lastSessionConfig = executionConfig
            val provider = ProviderRegistry.getActiveProvider(executionConfig.activeProviderId)
            val tool = createSubagentTool(provider, executionConfig)
            val allowedTools = skill.allowedTools.distinct()
            val args = buildJsonObject {
                put("goal", goal)
                put("model", executionConfig.getActiveModel())
                executionConfig.getActiveReasoningEffort()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("reasoningEffort", it) }
                if (allowedTools.isNotEmpty()) {
                    putJsonArray("allowedTools") {
                        allowedTools.forEach { token ->
                            add(JsonPrimitive(token))
                        }
                    }
                }
                put("enableWebSearch", allowedTools.isEmpty() || allowedTools.any { it == "web_search" || it == "web_fetch" })
                put("allowWriteAccess", allowedTools.any { it.startsWith("file(") && "write" in it })
                put("allowCodeEdits", allowedTools.any { it == "code_edit" })
                put("allowShell", allowedTools.any { it == "shell" })
                put("background", true)
            }.toString()
            _state.value = _state.value.copy(error = null)
            tool.execute(args)
            saveCurrentSession(executionConfig)
            return executionToast
        }
        sendMessageInternal(
            userVisibleText = goal,
            modelInput = goal,
            mentionedFiles = mentionedFiles,
            executionGoal = goal,
            configOverride = executionConfig,
            extraSystemContext = buildString {
                appendLine("Auto Matched Skill:")
                appendLine("Title: $skillLabel")
                skill.description.trim().takeIf { it.isNotBlank() }?.let {
                    appendLine("Description: $it")
                }
                if (skill.allowedTools.isNotEmpty()) {
                    appendLine("Allowed Tools: ${skill.allowedTools.joinToString(", ")}")
                }
                appendLine()
                appendLine("Apply the following skill instruction to this request:")
                append(skill.content.trim())
                buildExecutionProfileContext(
                    goal = goal,
                    baseConfig = baseConfig,
                    executionConfig = executionConfig,
                    matchedSkill = skill
                )?.takeIf { it.isNotBlank() }?.let {
                    appendLine()
                    appendLine()
                    append(it)
                }
            }
        )
        return executionToast
    }

    private fun matchAutoSkill(goal: String, config: ProviderConfig): GlobalSkill? {
        val normalizedGoal = normalizeAutoMatchingText(goal)
        if (normalizedGoal.isBlank()) return null
        return (config.globalSkills + _state.value.projectSkills)
            .asSequence()
            .filter { it.enabled }
            .mapNotNull { skill ->
                val score = computeAutoSkillScore(skill, normalizedGoal)
                if (score <= 0) null else AutoMatchedSkill(skill, score)
            }
            .sortedWith(compareByDescending<AutoMatchedSkill> { it.score }.thenBy { it.skill.title.length })
            .firstOrNull()
            ?.takeIf { it.score >= 12 }
            ?.skill
    }

    private fun computeAutoSkillScore(skill: GlobalSkill, normalizedGoal: String): Int {
        val title = skill.title.trim()
        val normalizedTitle = normalizeAutoMatchingText(title)
        val titleTokens = extractAutoMatchTokens(title)
        val descriptionTokens = extractAutoMatchTokens(skill.description)
        val directTitleHit = normalizedTitle.isNotBlank() && normalizedGoal.contains(normalizedTitle)
        val titleHits = titleTokens.count { token -> normalizedGoal.contains(token) }
        val descriptionHits = descriptionTokens.count { token -> normalizedGoal.contains(token) }
        val hintHit = AUTO_SKILL_HINT_KEYWORDS.any { hint ->
            hint in normalizedGoal && hint in normalizeAutoMatchingText("${skill.title} ${skill.description} ${skill.content.take(160)}")
        }
        return buildList {
            if (directTitleHit) add(20)
            if (titleHits > 0) add(titleHits * 6)
            if (descriptionHits > 0) add(descriptionHits * 3)
            if (hintHit) add(4)
            if (skill.runAs == SkillRunAs.SUBAGENT && (titleHits > 0 || descriptionHits > 0)) add(2)
        }.sum()
    }

    private fun resolveExecutionConfig(
        baseConfig: ProviderConfig,
        goal: String,
        mentionedFiles: List<FileMentionUi> = emptyList(),
        matchedSkill: GlobalSkill? = null
    ): ProviderConfig {
        val complexTask = baseConfig.autoUpgradeExecutionProfile && isComplexTask(goal, mentionedFiles)
        val discoveryRequest = isAutoDiscoveryRequest(goal)
        val requestedModel = matchedSkill?.preferredModel?.trim().orEmpty().ifBlank {
            when (baseConfig.activeProviderId) {
                "deepseek" -> if (complexTask) "deepseek-v4-pro" else ""
                "claude" -> if (complexTask) "claude-opus-4-8" else ""
                "openai-compatible" -> if (complexTask && shouldPreferLatestOpenAiProfile(baseConfig)) "gpt-5.5" else ""
                else -> ""
            }
        }
        val requestedReasoning = when {
            !complexTask -> null
            baseConfig.activeProviderId == "deepseek" -> "max"
            baseConfig.activeProviderId == "claude" -> "xhigh"
            baseConfig.activeProviderId == "openai-compatible" -> "xhigh"
            else -> "high"
        }
        val targetBuiltinTools = if (discoveryRequest) {
            (baseConfig.enabledBuiltinTools + listOf("web_search", "web_fetch")).distinct()
        } else {
            baseConfig.enabledBuiltinTools
        }
        val resolvedConfig = when (baseConfig.activeProviderId) {
            "deepseek" -> {
                val targetModel = requestedModel.ifBlank { baseConfig.getActiveModel() }
                val preset = when (targetModel) {
                    "deepseek-v4-flash" -> "flash"
                    "deepseek-v4-pro" -> "pro"
                    else -> "custom"
                }
                baseConfig.copy(
                    deepseekModelPreset = preset,
                    deepseekModel = targetModel,
                    deepseekReasoningEffort = requestedReasoning ?: baseConfig.deepseekReasoningEffort
                )
            }

            "openai-compatible" -> baseConfig.copy(
                openaiModel = requestedModel.ifBlank { baseConfig.openaiModel },
                openaiReasoningEffort = requestedReasoning ?: baseConfig.openaiReasoningEffort
            )

            "claude" -> baseConfig.copy(
                claudeModel = requestedModel.ifBlank { baseConfig.claudeModel },
                claudeReasoningEffort = requestedReasoning ?: baseConfig.claudeReasoningEffort
            )

            else -> baseConfig
        }
        return resolvedConfig.copy(enabledBuiltinTools = targetBuiltinTools)
    }

    private fun shouldPreferLatestOpenAiProfile(config: ProviderConfig): Boolean {
        val baseUrl = config.getActiveBaseUrl()?.trimEnd('/')?.lowercase().orEmpty()
        if (baseUrl.isBlank()) return true
        return baseUrl == "https://api.openai.com" ||
            baseUrl.startsWith("https://api.openai.com/") ||
            baseUrl.contains(".openai.azure.com")
    }

    private fun buildExecutionProfileContext(
        goal: String,
        baseConfig: ProviderConfig,
        executionConfig: ProviderConfig,
        matchedSkill: GlobalSkill? = null
    ): String? {
        val discoveryRequest = isAutoDiscoveryRequest(goal)
        val modelChanged = executionConfig.getActiveModel() != baseConfig.getActiveModel()
        val reasoningChanged = executionConfig.getActiveReasoningEffort() != baseConfig.getActiveReasoningEffort()
        val webEnabledForThisRun = setOf("web_search", "web_fetch")
            .all { executionConfig.isBuiltinToolEnabled(it) && !baseConfig.isBuiltinToolEnabled(it) }
        if (!modelChanged && !reasoningChanged && matchedSkill == null && !discoveryRequest && !webEnabledForThisRun) {
            return null
        }
        return buildString {
            appendLine("Execution Profile:")
            if (matchedSkill != null) {
                appendLine("Matched skill: ${matchedSkill.title.ifBlank { "未命名 Skill" }}")
            }
            if (isComplexTask(goal)) {
                appendLine("Reason: complex task detected, prefer stronger execution profile.")
            }
            if (webEnabledForThisRun) {
                appendLine("Web tools: temporarily enabled for this request.")
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
                appendLine("- When you find a valid MCP config, output a machine-readable draft that is directly importable.")
                appendLine("- When you find a valid Skill definition, output a compatible Skill markdown or JSON draft.")
                appendLine("- Keep surrounding prose short because compatible drafts will be auto-detected and auto-imported after the reply.")
                appendLine("- If the source requires manual secrets, explicitly leave placeholders instead of fabricating values.")
            }
        }.trim()
    }

    private fun buildExecutionProfileToast(
        baseConfig: ProviderConfig,
        executionConfig: ProviderConfig
    ): String? {
        val modelChanged = executionConfig.getActiveModel() != baseConfig.getActiveModel()
        val reasoningChanged = executionConfig.getActiveReasoningEffort() != baseConfig.getActiveReasoningEffort()
        if (!modelChanged && !reasoningChanged) return null
        val profileLabel = buildExecutionProfileLabel(
            model = executionConfig.getActiveModel(),
            reasoning = executionConfig.getActiveReasoningEffort()
        )
        return when {
            modelChanged && reasoningChanged ->
                "复杂任务，已自动升档到 $profileLabel"
            modelChanged ->
                "复杂任务，已自动切换到 $profileLabel"
            else ->
                "复杂任务，已自动提升到 $profileLabel"
        }
    }

    private fun buildExecutionProfileLabel(model: String, reasoning: String?): String {
        val modelLabel = when (model.trim()) {
            "claude-opus-4-8" -> "Claude 4.8"
            "gpt-5.5" -> "GPT-5.5"
            "deepseek-v4-pro" -> "DeepSeek V4 Pro"
            "deepseek-v4-flash" -> "DeepSeek V4 Flash"
            else -> model.trim().ifBlank { "当前模型" }
        }
        val reasoningLabel = when (reasoning?.trim()?.lowercase()) {
            "low" -> "低推理"
            "medium" -> "中推理"
            "high" -> "高推理"
            "xhigh" -> "超高推理"
            "max" -> "最大推理"
            else -> null
        }
        return if (reasoningLabel == null) modelLabel else "$modelLabel $reasoningLabel"
    }

    private fun isAutoDiscoveryRequest(goal: String): Boolean {
        val normalized = goal.trim().lowercase()
        if (normalized.isBlank()) return false
        val actionHit = AUTO_DISCOVERY_ACTION_KEYWORDS.any { keyword -> keyword in normalized }
        val targetHit = AUTO_DISCOVERY_TARGET_KEYWORDS.any { keyword -> keyword in normalized }
        return actionHit && targetHit
    }

    private fun isComplexTask(goal: String, mentionedFiles: List<FileMentionUi> = emptyList()): Boolean {
        val normalized = goal.trim().lowercase()
        if (normalized.isBlank()) return false
        val keywordHits = AUTO_COMPLEXITY_KEYWORDS.count { keyword -> keyword in normalized }
        val lineCount = goal.lines().count { it.isNotBlank() }
        return goal.length >= 90 ||
            lineCount >= 3 ||
            mentionedFiles.size >= 2 ||
            keywordHits >= 2
    }

    private fun normalizeAutoMatchingText(value: String): String {
        return value.lowercase()
            .replace(Regex("""[`"'“”‘’]"""), " ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
    }

    private fun extractAutoMatchTokens(value: String): List<String> {
        val normalized = normalizeAutoMatchingText(value)
        if (normalized.isBlank()) return emptyList()
        return normalized
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { token -> token.length >= 2 }
            .distinct()
            .take(12)
    }

    private suspend fun autoAttachDraftsFromAssistantMessage(
        assistantMessageId: Long,
        config: ProviderConfig
    ): DraftAutoAttachOutcome {
        val content = _state.value.messages
            .firstOrNull { it.id == assistantMessageId }
            ?.content
            ?.trim()
            .orEmpty()
        if (content.isBlank()) return DraftAutoAttachOutcome()

        var latestConfig = configRepository.getConfig()
        val skillDrafts = parseSkillDraftsCompat(content)
        val newSkills = skillDrafts.filter { candidate ->
            latestConfig.globalSkills.none { existing ->
                buildSkillIdentity(existing) == buildSkillIdentity(candidate)
            }
        }
        if (newSkills.isNotEmpty()) {
            latestConfig = latestConfig.copy(globalSkills = latestConfig.globalSkills + newSkills)
            configRepository.saveConfig(latestConfig)
        }

        val registry = mcpRegistry
        var importedMcpCount = 0
        var connectedMcpCount = 0
        if (registry != null) {
            val draftConfigs = parseMcpServerDraftsCompat(content)
            if (draftConfigs.isNotEmpty()) {
                val existingConfigs = registry.loadConfigs().toMutableList()
                val touchedNames = linkedSetOf<String>()
                draftConfigs
                    .distinctBy { it.name }
                    .forEach { draft ->
                        val existingIndex = existingConfigs.indexOfFirst { it.name == draft.name }
                        if (existingIndex >= 0) {
                            if (existingConfigs[existingIndex] != draft) {
                                registry.disconnect(draft.name)
                                existingConfigs[existingIndex] = draft
                                importedMcpCount += 1
                                touchedNames += draft.name
                            }
                        } else {
                            existingConfigs += draft
                            importedMcpCount += 1
                            touchedNames += draft.name
                        }
                    }
                if (touchedNames.isNotEmpty()) {
                    registry.saveConfigs(existingConfigs)
                    registry.connectAll(existingConfigs)
                    val touchedStatuses = registry.getServerStatuses()
                        .filter { it.name in touchedNames }
                    connectedMcpCount = touchedStatuses.count { it.connected }
                    if (!latestConfig.allowAllMcpTools) {
                        val importedToolNames = touchedStatuses
                            .flatMap { status -> status.toolNames.map { toolName -> "mcp_$toolName" } }
                            .distinct()
                        if (importedToolNames.isNotEmpty()) {
                            latestConfig = latestConfig.copy(
                                allowedMcpTools = (latestConfig.allowedMcpTools + importedToolNames).distinct()
                            )
                            configRepository.saveConfig(latestConfig)
                        }
                    }
                }
            }
        }

        val summaryParts = buildList {
            if (newSkills.isNotEmpty()) {
                add("已自动导入 ${newSkills.size} 个 Skill")
            }
            if (importedMcpCount > 0) {
                add("已自动接入 $connectedMcpCount/$importedMcpCount 个 MCP")
            }
        }
        return DraftAutoAttachOutcome(
            importedSkillCount = newSkills.size,
            importedMcpCount = importedMcpCount,
            connectedMcpCount = connectedMcpCount,
            summary = summaryParts.joinToString("，").takeIf { it.isNotBlank() }?.plus("。").orEmpty()
        )
    }

    private fun buildSkillIdentity(skill: GlobalSkill): String {
        return listOf(
            skill.title.trim(),
            skill.description.trim(),
            skill.content.trim(),
            skill.runAs.name,
            skill.preferredModel.trim(),
            skill.allowedTools.joinToString(",")
        ).joinToString("|")
    }

    private fun parseSkillDraftsCompat(raw: String): List<GlobalSkill> {
        extractDraftCandidates(raw).forEach { candidate ->
            parseDesktopSkillMarkdownCompat(candidate)?.let { return listOf(it) }
            parseLooseSkillDraftCompat(candidate)?.let { return listOf(it) }
            parseJsonDraftRootCompat(candidate)?.let { root ->
                return unwrapDraftEntriesCompat(root, "skills", "items", "drafts")
                    .mapNotNull(::parseSkillDraftCompat)
                    .distinctBy(::buildSkillIdentity)
            }
        }
        return emptyList()
    }

    private fun parseMcpServerDraftsCompat(raw: String): List<McpServerConfig> {
        extractDraftCandidates(raw).forEach { candidate ->
            val specItems = parseMcpSpecLinesCompat(candidate)
            if (specItems.isNotEmpty()) {
                return specItems.distinctBy { it.name }
            }
            parseJsonDraftRootCompat(candidate)?.let { root ->
                return unwrapDraftEntriesCompat(root, "servers", "mcpServers", "items", "drafts")
                    .mapNotNull(::parseMcpServerDraftCompat)
                    .distinctBy { it.name }
            }
        }
        return emptyList()
    }

    private fun extractDraftCandidates(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        val fenced = DRAFT_FENCE_REGEX.findAll(trimmed)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
            .toList()
        return buildList {
            add(trimmed)
            addAll(fenced)
            addAll(extractInlineBacktickBlocks(trimmed))
        }.distinct()
    }

    private fun extractInlineBacktickBlocks(raw: String): List<String> {
        return Regex("`([^`\\n]{6,})`")
            .findAll(raw)
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }
            .toList()
    }

    private fun parseJsonDraftRootCompat(raw: String): JsonElement? {
        val candidate = raw.trim()
        if (candidate.isBlank()) return null
        return runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(candidate) }.getOrNull()
    }

    private fun unwrapDraftEntriesCompat(root: JsonElement, vararg collectionKeys: String): List<JsonElement> {
        return when (root) {
            is JsonArray -> root
            is JsonObject -> {
                collectionKeys.asSequence()
                    .mapNotNull { key -> root[key] }
                    .firstNotNullOfOrNull { node ->
                        when (node) {
                            is JsonArray -> node.toList()
                            is JsonObject -> listOf(node)
                            else -> null
                        }
                    } ?: listOf(root)
            }

            else -> emptyList()
        }
    }

    private fun parseSkillDraftCompat(element: JsonElement): GlobalSkill? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val title = obj.stringValue("title").orEmpty()
        val description = obj.stringValue("description").orEmpty()
        val content = obj.stringValue("content")
            ?: obj.stringValue("prompt")
            ?: obj.stringValue("template")
            ?: return null
        return GlobalSkill(
            id = obj.stringValue("id").orEmpty().ifBlank { UUID.randomUUID().toString().take(8) },
            title = title.ifBlank { "导入 Skill" },
            description = description,
            content = content,
            runAs = if (obj.stringValue("runAs")?.equals("subagent", ignoreCase = true) == true) {
                SkillRunAs.SUBAGENT
            } else {
                SkillRunAs.INLINE
            },
            allowedTools = parseAllowedToolsCompat(obj.stringListValue("allowedTools"), obj.stringValue("allowed-tools")),
            preferredModel = obj.stringValue("preferredModel") ?: obj.stringValue("model").orEmpty(),
            enabled = obj.booleanValue("enabled") ?: true
        )
    }

    private fun parseDesktopSkillMarkdownCompat(raw: String): GlobalSkill? {
        val trimmed = raw.trimStart('\uFEFF').trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return null
        if (!trimmed.startsWith("---")) return null
        val frontmatter = parseFrontmatterCompat(trimmed)
        val title = frontmatter.first["title"]
            ?.takeIf { it.isNotBlank() }
            ?: frontmatter.first["name"]?.takeIf { it.isNotBlank() }
        val body = frontmatter.second.trim()
        if (title == null && body.isBlank()) return null
        return GlobalSkill(
            id = UUID.randomUUID().toString().take(8),
            title = title ?: "导入 Skill",
            description = frontmatter.first["description"].orEmpty(),
            content = body,
            runAs = if (parseRunAsCompat(frontmatter.first).equals("subagent", ignoreCase = true)) {
                SkillRunAs.SUBAGENT
            } else {
                SkillRunAs.INLINE
            },
            allowedTools = parseAllowedToolsCompat(
                emptyList(),
                frontmatter.first["allowed-tools"] ?: frontmatter.first["allowedTools"]
            ),
            preferredModel = frontmatter.first["model"].orEmpty(),
            enabled = true
        )
    }

    private fun parseLooseSkillDraftCompat(raw: String): GlobalSkill? {
        val trimmed = raw.trimStart('\uFEFF').trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("---")) return null

        val lines = trimmed.lines()
        val fields = linkedMapOf<String, String>()
        val bodyLines = mutableListOf<String>()
        var bodyStarted = false
        var fieldCount = 0
        var currentMultilineKey: String? = null
        val fieldRegex = Regex(
            """^(title|name|description|model|runAs|context|agent|allowed-tools|allowedTools|allowed_tools|prompt|content|instruction|body|标题|名称|描述|模型|运行方式|模式|允许工具|工具|提示词|内容|正文|指令)\s*[:：]\s*(.*)$""",
            RegexOption.IGNORE_CASE
        )

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd()
            if (!bodyStarted) {
                val headingTitle = if (index == 0) {
                    Regex("""^(?:#+\s*)?Skill\s*[:：-]\s*(.+)$""", RegexOption.IGNORE_CASE)
                        .find(line)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: if (line.startsWith("# ")) line.removePrefix("# ").trim().takeIf { it.isNotBlank() } else null
                } else null
                if (headingTitle != null && fields["title"].isNullOrBlank()) {
                    fields["title"] = headingTitle
                    return@forEachIndexed
                }

                val fieldMatch = fieldRegex.find(line)
                if (fieldMatch != null) {
                    val key = normalizeLooseSkillFieldKey(fieldMatch.groupValues[1])
                    val value = fieldMatch.groupValues[2].trim()
                    fieldCount += 1
                    currentMultilineKey = null
                    when (key) {
                        "content" -> {
                            bodyStarted = true
                            value.takeIf { it.isNotBlank() }?.let(bodyLines::add)
                        }

                        "description" -> {
                            fields[key] = value
                            currentMultilineKey = key.takeIf { value.isBlank() }
                        }

                        else -> {
                            fields[key] = value
                        }
                    }
                    return@forEachIndexed
                }

                if (currentMultilineKey != null && line.isNotBlank()) {
                    fields[currentMultilineKey!!] = buildString {
                        append(fields[currentMultilineKey!!].orEmpty())
                        if (isNotBlank()) append('\n')
                        append(line.trim())
                    }.trim()
                    return@forEachIndexed
                }

                if (fieldCount > 0 && line.isNotBlank()) {
                    bodyStarted = true
                    bodyLines += rawLine
                    return@forEachIndexed
                }
            } else {
                bodyLines += rawLine
            }
        }

        val body = bodyLines.joinToString("\n").trim().removeSurrounding("```").trim()
        val title = fields["title"].orEmpty().ifBlank { fields["name"].orEmpty() }
        val description = fields["description"].orEmpty()
        if (fieldCount < 2 && title.isBlank() && body.isBlank()) return null
        if (title.isBlank() && description.isBlank() && body.isBlank()) return null

        return GlobalSkill(
            id = UUID.randomUUID().toString().take(8),
            title = title.ifBlank { "导入 Skill" },
            description = description,
            content = body.ifBlank {
                fields["content"].orEmpty().takeIf { it.isNotBlank() }
                    ?: return null
            },
            runAs = if (parseRunAsCompat(fields).equals("subagent", ignoreCase = true)) {
                SkillRunAs.SUBAGENT
            } else {
                SkillRunAs.INLINE
            },
            allowedTools = parseAllowedToolsCompat(
                emptyList(),
                fields["allowed-tools"] ?: fields["allowedTools"]
            ),
            preferredModel = fields["model"].orEmpty(),
            enabled = true
        )
    }

    private fun parseFrontmatterCompat(raw: String): Pair<Map<String, String>, String> {
        val lines = raw.split(Regex("\\r?\\n"))
        if (lines.firstOrNull() != "---") return emptyMap<String, String>() to raw
        val endIndex = lines.drop(1).indexOfFirst { it == "---" }
        if (endIndex < 0) return emptyMap<String, String>() to raw
        val data = linkedMapOf<String, String>()
        val bodyStart = endIndex + 2
        val keyRegex = Regex("^([a-zA-Z_][a-zA-Z0-9_-]*):\\s*(.*)$")
        for (line in lines.subList(1, endIndex + 1)) {
            val match = keyRegex.find(line) ?: continue
            data[match.groupValues[1]] = match.groupValues[2].trim().trim('"', '\'')
        }
        return data to lines.drop(bodyStart).joinToString("\n")
    }

    private fun normalizeLooseSkillFieldKey(key: String): String {
        return when (key.trim().lowercase()) {
            "title", "标题" -> "title"
            "name", "名称" -> "name"
            "description", "描述" -> "description"
            "model", "模型" -> "model"
            "runas", "运行方式", "模式" -> "runAs"
            "context" -> "context"
            "agent" -> "agent"
            "allowed-tools", "allowedtools", "allowed_tools", "允许工具", "工具" -> "allowed-tools"
            "prompt", "content", "instruction", "body", "提示词", "内容", "正文", "指令" -> "content"
            else -> key
        }
    }

    private fun parseRunAsCompat(data: Map<String, String>): String? {
        val runAs = data["runAs"]?.trim()?.lowercase()
        if (runAs == "subagent") return "subagent"
        if (data["context"]?.trim()?.equals("fork", ignoreCase = true) == true) return "subagent"
        if (!data["agent"].isNullOrBlank()) return "subagent"
        return null
    }

    private fun parseAllowedToolsCompat(listValue: List<String>, stringValue: String?): List<String> {
        val raw = (listValue + stringValue.orEmpty()
            .split(',', ';', '\n')
            .map { it.trim() })
            .map { it.trim().trim('"', '\'', '-', '*') }
            .filter { it.isNotBlank() }
        return raw.distinct()
    }

    private fun parseMcpServerDraftCompat(element: JsonElement): McpServerConfig? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val name = obj.stringValue("name") ?: obj.stringValue("serverName") ?: return null
        val transport = when (obj.stringValue("transport")?.lowercase()) {
            null, "", "stdio" -> McpTransportType.STDIO
            "sse" -> McpTransportType.SSE
            "streamable-http", "http" -> McpTransportType.STREAMABLE_HTTP
            else -> return null
        }
        val command = obj.stringValue("command").orEmpty()
        val args = obj.stringListValue("args")
        val url = obj.stringValue("url")
            ?: obj.stringValue("sseUrl")
            ?: obj.stringValue("endpoint")
            ?: ""
        if (transport == McpTransportType.STDIO && command.isBlank()) return null
        if (transport != McpTransportType.STDIO && url.isBlank()) return null
        return McpServerConfig(
            name = name,
            transport = transport,
            command = command,
            args = args,
            cwd = obj.stringValue("cwd").orEmpty(),
            env = obj.stringMapValue("env"),
            url = url,
            headers = obj.stringMapValue("headers"),
            requestTimeoutMs = obj.stringValue("requestTimeoutMs")?.toLongOrNull(),
            enabled = obj.booleanValue("enabled") ?: true
        )
    }

    private fun parseMcpSpecLinesCompat(raw: String): List<McpServerConfig> {
        val lines = raw
            .split(Regex("\\r?\\n"))
            .map(::normalizePotentialMcpSpecLine)
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        if (lines.any { it.startsWith("{") || it.startsWith("[") || it.startsWith("---") }) return emptyList()
        return lines.mapNotNull(::parseDesktopMcpSpecCompat)
    }

    private fun parseDesktopMcpSpecCompat(input: String): McpServerConfig? {
        val trimmed = normalizePotentialMcpSpecLine(input)
        if (trimmed.isBlank()) return null
        val nameMatch = Regex("^([a-zA-Z_][a-zA-Z0-9_-]*)=(.*)$").find(trimmed)
        val name = nameMatch?.groupValues?.getOrNull(1)
        val body = normalizePotentialMcpSpecLine(nameMatch?.groupValues?.getOrNull(2) ?: trimmed)
        if (body.isBlank()) return null
        val streamableMatch = Regex("^streamable\\+(https?://.+)$", RegexOption.IGNORE_CASE).find(body)
        return when {
            streamableMatch != null -> McpServerConfig(
                name = name ?: inferMcpNameFromUrlCompat(streamableMatch.groupValues[1]) ?: "imported-mcp",
                transport = McpTransportType.STREAMABLE_HTTP,
                url = streamableMatch.groupValues[1]
            )

            Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(body) -> McpServerConfig(
                name = name ?: inferMcpNameFromUrlCompat(body) ?: "imported-mcp",
                transport = McpTransportType.SSE,
                url = body
            )

            else -> {
                val argv = splitCommandLineCompat(body)
                if (argv.isEmpty()) return null
                McpServerConfig(
                    name = name ?: inferMcpNameFromCommandCompat(argv) ?: argv.first(),
                    transport = McpTransportType.STDIO,
                    command = argv.first(),
                    args = argv.drop(1)
                )
            }
        }
    }

    private fun normalizePotentialMcpSpecLine(line: String): String {
        return line.trim()
            .removePrefix("-")
            .removePrefix("*")
            .trim()
            .removeSurrounding("`")
            .replace(Regex("""^(命令|command|cmd|run|启动命令|stdio)\s*[:：]\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(url|sse|streamable-http|streamable)\s*[:：]\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(mcp\s*(server|配置|config)?|server)\s*[:：]\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun inferMcpNameFromCommandCompat(argv: List<String>): String? {
        if (argv.isEmpty()) return null
        val normalized = argv.map { it.trim() }.filter { it.isNotBlank() }
        if (normalized.isEmpty()) return null
        val launcher = normalized.first().lowercase()
        val packageToken = when (launcher) {
            "npx", "bunx" -> normalized.drop(1).firstOrNull { token ->
                !token.startsWith("-") && token != "--yes" && token != "-y"
            }

            "uvx" -> normalized.drop(1).firstOrNull { token -> !token.startsWith("-") }
            "python", "python3" -> {
                val moduleIndex = normalized.indexOf("-m")
                if (moduleIndex >= 0) normalized.getOrNull(moduleIndex + 1) else null
            }

            "node" -> normalized.getOrNull(1)
            else -> normalized.firstOrNull()
        } ?: return null
        return packageToken
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast('@')
            .substringAfterLast(':')
            .trim()
            .removeSuffix(".js")
            .replace(Regex("""[^a-zA-Z0-9._-]+"""), "-")
            .trim('-')
            .takeIf { it.isNotBlank() }
    }

    private fun inferMcpNameFromUrlCompat(url: String): String? {
        return url.substringAfter("://", "")
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore(":")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun splitCommandLineCompat(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuotes = false
        var inDoubleQuotes = false
        input.forEach { ch ->
            when {
                ch == '"' && !inSingleQuotes -> inDoubleQuotes = !inDoubleQuotes
                ch == '\'' && !inDoubleQuotes -> inSingleQuotes = !inSingleQuotes
                ch.isWhitespace() && !inSingleQuotes && !inDoubleQuotes -> {
                    current.toString().trim().takeIf { it.isNotBlank() }?.let(result::add)
                    current.clear()
                }

                else -> current.append(ch)
            }
        }
        current.toString().trim().takeIf { it.isNotBlank() }?.let(result::add)
        return result
    }

    private fun JsonObject.stringValue(key: String): String? {
        return runCatching { get(key)?.jsonPrimitive?.contentOrNull?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.booleanValue(key: String): Boolean? {
        return runCatching { get(key)?.jsonPrimitive?.booleanOrNull }.getOrNull()
    }

    private fun JsonObject.stringListValue(key: String): List<String> {
        return runCatching {
            get(key)?.jsonArray?.mapNotNull { item ->
                item.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull().orEmpty()
    }

    private fun JsonObject.stringMapValue(key: String): Map<String, String> {
        return runCatching {
            get(key)?.jsonObject?.mapNotNull { (mapKey, value) ->
                value.jsonPrimitive.contentOrNull?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { mapKey to it }
            }?.toMap()
        }.getOrNull().orEmpty()
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

    private suspend fun scheduleBackgroundSubagentExecution(
        runId: String,
        executeNow: suspend () -> String
    ): String {
        val execution = PendingSubagentExecution(runId = runId) {
            executeNow()
        }
        val (startNow, queuePosition, assignedSlot) = synchronized(subagentExecutionLock) {
            if (runningSubagentExecutionSlots.size < maxConcurrentSubagentExecutions) {
                val slot = findNextAvailableSubagentSlot()
                runningSubagentExecutionSlots[slot] = runId
                Triple(true, 0, slot)
            } else {
                pendingSubagentExecutions.add(execution)
                Triple(false, pendingSubagentExecutions.size, null)
            }
        }
        refreshBackgroundExecutionState()
        if (startNow) {
            launchBackgroundSubagentExecution(execution)
            return "Subagent queued in background: $runId (slot $assignedSlot/$maxConcurrentSubagentExecutions)"
        }
        return "Subagent queued in background: $runId (position $queuePosition)"
    }

    private fun launchBackgroundSubagentExecution(execution: PendingSubagentExecution) {
        subagentExecutionScope.launch {
            try {
                execution.execute()
            } finally {
                onBackgroundSubagentExecutionFinished(execution.runId)
            }
        }
    }

    private fun onBackgroundSubagentExecutionFinished(runId: String) {
        val next = synchronized(subagentExecutionLock) {
            val freedSlot = runningSubagentExecutionSlots.entries.firstOrNull { it.value == runId }?.key
            if (freedSlot != null) {
                runningSubagentExecutionSlots.remove(freedSlot)
            }
            if (pendingSubagentExecutions.isEmpty()) {
                null
            } else {
                val pending = pendingSubagentExecutions.removeAt(0)
                val slot = freedSlot ?: findNextAvailableSubagentSlot()
                runningSubagentExecutionSlots[slot] = pending.runId
                pending
            }
        }
        refreshBackgroundExecutionState()
        next?.let(::launchBackgroundSubagentExecution)
    }

    private fun removePendingSubagentExecution(runId: String): Boolean {
        val removed = synchronized(subagentExecutionLock) {
            val index = pendingSubagentExecutions.indexOfFirst { it.runId == runId }
            if (index == -1) {
                false
            } else {
                pendingSubagentExecutions.removeAt(index)
                true
            }
        }
        if (removed) {
            refreshBackgroundExecutionState()
        }
        return removed
    }

    private fun findNextAvailableSubagentSlot(): Int {
        for (slot in 1..maxConcurrentSubagentExecutions) {
            if (slot !in runningSubagentExecutionSlots) {
                return slot
            }
        }
        return maxConcurrentSubagentExecutions
    }

    private fun buildCompressionContext(): String? {
        val state = _state.value
        val compression = state.compressionSnapshot?.takeIf { it.active } ?: return null
        return buildString {
            append("Active Conversation Compression Snapshot:\n")
            append(compression.summary.trim())
            append("\n\n")
            append("Continue working from this summary together with the recent uncompressed messages. ")
            append("If details are missing, ask the user or rely on the visible recent messages instead of fabricating history.")
        }.trim()
    }

    private fun buildCurrentProjectContext(): String? {
        val state = _state.value
        val projectPath = normalizeProjectPath(state.projectPath) ?: return null
        val mountedKnowledge = state.projectKnowledgePaths
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return buildString {
            appendLine("Current Project Context:")
            appendLine("Project root: $projectPath")
            appendLine("Treat this path as the current working directory for the current task unless the user explicitly changes it.")
            appendLine("When you mention files under this project, prefer paths relative to this root.")
            if (mountedKnowledge.isNotEmpty()) {
                appendLine()
                appendLine("Mounted project knowledge files:")
                mountedKnowledge.forEach { path ->
                    appendLine("- $path")
                }
            }
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
        return buildString {
            appendLine("Enabled MCP Tools:")
            appendLine("These MCP tools are currently available to the main agent, and subagents can inherit matching tools when a Skill's Allowed Tools list references them.")
            appendLine("Prefer exact tool names when delegating Skill-based subagent work.")
            appendLine()
            availableTools.forEach { tool ->
                append("- ")
                append(tool.name)
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
            buildMentionedFilesContext(mentionedFiles)?.let {
                add(ChatMessage(role = "system", content = it))
            }
            add(ChatMessage(role = "system", content = planInstruction))
            addAll(history.takeLast(12))
            add(
                ChatMessage(
                    role = "user",
                    content = "请先为这个任务生成一份简短执行计划：\n$goal"
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
            buildMentionedFilesContext(mentionedFiles)?.let {
                add(ChatMessage(role = "system", content = it))
            }
            add(ChatMessage(role = "system", content = routerInstruction))
            addAll(history.takeLast(10))
            add(
                ChatMessage(
                    role = "user",
                    content = "请判断这个输入最适合直接执行、先出计划，还是先提一个澄清问题：\n$goal"
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
        mentionedFiles: List<FileMentionUi>
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
                rawPlan = normalized
            )
        )
    }

    private fun buildWorkflowExecutionPrompt(plan: WorkflowPlanUi): String {
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
            append("要求：优先在一次连续工作流中完成整条链路，减少中间输出和上下文污染；")
            append("只有在缺少信息、需要审批或必须用户决策时才中途打断。")
        }.trim()
    }

    private fun normalizeWorkflowPlan(plan: WorkflowPlanUi): WorkflowPlanUi {
        val normalizedSteps = plan.steps.filter { it.isNotBlank() }.ifEmpty { buildDefaultWorkflowPlanSteps() }
        val normalizedStages = plan.stageLabels.filter { it.isNotBlank() }
            .ifEmpty { buildDefaultWorkflowStages(normalizedSteps) }
        val boundedStepIndex = plan.currentStepIndex.coerceIn(0, normalizedSteps.size)
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

    private fun buildBlockedWorkflowHint(state: SessionState): String {
        return when {
            state.pendingApproval != null -> "先处理审批请求，再继续按计划执行。"
            state.pendingClarificationRequest != null -> "先回答澄清问题，再继续按计划执行。"
            !state.error.isNullOrBlank() -> "先处理执行错误，再决定是重试还是重规划。"
            else -> "先补齐阻塞条件，再继续推进当前计划。"
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
            buildMentionedFilesContext(mentionedFiles)?.let {
                add(ChatMessage(role = "system", content = it))
            }
            add(ChatMessage(role = "system", content = clarifyInstruction))
            addAll(history.takeLast(12))
            buildClarificationAnswersContext(previousAnswers)?.let {
                add(ChatMessage(role = "system", content = it))
            }
            add(
                ChatMessage(
                    role = "user",
                    content = buildString {
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
        source: ClarificationSource
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
            source = source
        )
    }

    private fun buildClarificationFollowUpPrompt(
        goal: String,
        history: List<ChatMessage>,
        compressionContext: String?,
        mentionedFiles: List<FileMentionUi>,
        answers: List<ClarificationAnswerUi>,
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
            buildMentionedFilesContext(mentionedFiles)?.let {
                add(ChatMessage(role = "system", content = it))
            }
            add(ChatMessage(role = "system", content = followUpInstruction))
            addAll(history.takeLast(12))
            add(
                ChatMessage(
                    role = "user",
                    content = buildString {
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
        return buildString {
            appendLine("用户已经完成当前阶段的澄清，请基于这些信息继续完成任务。")
            appendLine()
            appendLine("原始任务:")
            appendLine(goal)
            appendLine()
            appendLine("已确认的澄清信息:")
            appendLine(formatClarificationAnswers(answers))
            appendLine()
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
        val resolvedMaxTurns = max(MAX_CLARIFICATION_TURNS, nextTurnIndex)
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
                    updateMessage(assistantMessageId) { message ->
                        message.copy(
                            content = "执行中自动打断格式不完整，已按配置回退为直接继续执行。",
                            reasoning = null,
                            isStreaming = false
                        )
                    }
                    _state.value = _state.value.copy(
                        isProcessing = false,
                        pendingWorkflowPlan = null,
                        workflowPlanningInProgress = false,
                        pendingClarificationRequest = null,
                        clarificationInProgress = false,
                        autoRoutingInProgress = false
                    )
                    sendMessageInternal(
                        userVisibleText = "继续执行: $executionGoal",
                        modelInput = if (existingClarificationAnswers.isNotEmpty()) {
                            buildClarificationExecutionPrompt(
                                goal = executionGoal,
                                answers = existingClarificationAnswers
                            )
                        } else {
                            executionGoal
                        },
                        mentionedFiles = mentionedFiles,
                        executionGoal = executionGoal,
                        existingClarificationAnswers = existingClarificationAnswers
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
        _state.value = _state.value.copy(
            pendingWorkflowPlan = null,
            workflowPlanningInProgress = false,
            pendingClarificationRequest = ClarificationRequestUi(
                goal = executionGoal,
                question = interruption.question,
                mentionedFiles = mentionedFiles.distinctBy { it.path },
                previousAnswers = existingClarificationAnswers,
                turnIndex = nextTurnIndex,
                maxTurns = resolvedMaxTurns,
                source = ClarificationSource.AUTO_INTERRUPT
            ),
            clarificationInProgress = false,
            autoRoutingInProgress = false
        )
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
        return when {
            previousAnswers.isNotEmpty() ->
                "为了继续准确执行，请再补充目前最关键的限制条件、优先级或预期结果。"
            goal.isNotBlank() ->
                "为了避免做错，请先补充这个任务最关键的限制条件、目标范围或预期结果。"
            else ->
                "为了继续准确执行，请先补充当前任务最关键的限制条件或预期结果。"
        }
    }

    private fun buildLocalFallbackClarificationRequest(
        goal: String,
        mentionedFiles: List<FileMentionUi>,
        previousAnswers: List<ClarificationAnswerUi>,
        turnIndex: Int,
        maxTurns: Int,
        source: ClarificationSource
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
            source = source
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
                val content = readCurrentFileContent(mention.path)
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

    private fun buildPersistedSession(config: ProviderConfig? = null): PersistedSession {
        val state = _state.value
        val savedSession = conversationStore.loadSession(currentSessionId)
        val resolvedConfig = config ?: lastSessionConfig
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
            id = currentSessionId,
            title = state.sessionTitle.ifBlank { "新对话" },
            createdAt = savedSession?.createdAt
                ?: state.messages.firstOrNull()?.timestamp
                ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            providerId = providerId,
            modelName = modelName,
            projectPath = state.projectPath,
            projectRules = state.projectRules,
            projectMemories = state.projectMemories,
            projectSkills = state.projectSkills,
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
            projectToolPreferences = state.projectToolPreferences,
            usageSummary = state.usageSummary,
            compressionSnapshot = conversationStore.persistCompressionSnapshot(state.compressionSnapshot),
            compressionSnapshots = conversationStore.persistCompressionSnapshots(state.compressionSnapshots),
            checkpoints = conversationStore.persistConversationCheckpoints(state.checkpoints),
            fileChanges = conversationStore.persistFileChanges(state.fileChanges),
            subagentRuns = conversationStore.persistSubagentRuns(state.subagentRuns),
            subagentBatches = conversationStore.persistSubagentBatches(state.subagentBatches),
            recentErrors = conversationStore.persistErrorRecords(state.recentErrors),
            recentToolCalls = conversationStore.persistToolCallRecords(state.recentToolCalls),
            recentApprovals = conversationStore.persistApprovalRecords(state.recentApprovals),
            recentApprovalInvalidations = conversationStore.persistApprovalInvalidationRecords(
                state.recentApprovalInvalidations
            ),
            approvedApprovalScopes = approvedApprovalScopes.map { it.tokens },
            approvedApprovalScopeEntries = conversationStore.persistApprovedApprovalScopes(approvedApprovalScopes),
            pendingWorkflowPlan = state.pendingWorkflowPlan?.let { plan ->
                PersistedWorkflowPlan(
                    id = plan.id,
                    goal = plan.goal,
                    summary = plan.summary,
                    steps = plan.steps,
                    stageLabels = plan.stageLabels,
                    currentStageIndex = plan.currentStageIndex,
                    currentStepIndex = plan.currentStepIndex,
                    nextStepHint = plan.nextStepHint,
                    status = plan.status.name,
                    mentionedFiles = plan.mentionedFiles.map { mention ->
                        PersistedFileMention(
                            path = mention.path,
                            displayPath = mention.displayPath
                        )
                    },
                    rawPlan = plan.rawPlan,
                    createdAt = plan.createdAt
                )
            },
            pendingClarificationRequest = state.pendingClarificationRequest?.let { request ->
                PersistedClarificationRequest(
                    id = request.id,
                    goal = request.goal,
                    question = request.question,
                    mentionedFiles = request.mentionedFiles.map { mention ->
                        PersistedFileMention(
                            path = mention.path,
                            displayPath = mention.displayPath
                        )
                    },
                    previousAnswers = request.previousAnswers.map { answer ->
                        PersistedClarificationAnswer(
                            question = answer.question,
                            answer = answer.answer,
                            answeredAt = answer.answeredAt
                        )
                    },
                    turnIndex = request.turnIndex,
                    maxTurns = request.maxTurns,
                    source = request.source.name,
                    createdAt = request.createdAt
                )
            },
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
            messages = conversationStore.persistMessages(state.messages)
        )
    }

    private fun recordFileChanges(
        toolName: String,
        fileChanges: List<ToolFileChange>
    ): List<FileChangeRecordUi> {
        if (fileChanges.isEmpty()) return emptyList()
        val checkpointId = "chk-${System.currentTimeMillis()}"
        val records = fileChanges.mapIndexed { index, change ->
            FileChangeRecordUi(
                id = "$checkpointId-$index",
                path = change.path,
                operation = change.operation,
                beforeContent = change.beforeContent,
                afterContent = change.afterContent,
                diffPreview = change.diffPreview,
                changedAt = change.changedAt,
                checkpointId = checkpointId
            )
        }
        val checkpoint = ConversationCheckpointUi(
            id = checkpointId,
            messageIndex = _state.value.messages.lastIndex,
            createdAt = records.maxOfOrNull { it.changedAt } ?: System.currentTimeMillis(),
            summary = buildCheckpointSummary(toolName, records),
            changedFiles = records.map { it.path }
        )
        _state.value = _state.value.copy(
            fileChanges = (records + _state.value.fileChanges).take(MAX_FILE_CHANGE_HISTORY),
            checkpoints = (listOf(checkpoint) + _state.value.checkpoints).take(MAX_CHECKPOINT_HISTORY)
        )
        return records
    }

    private fun buildToolResultMessage(
        toolName: String,
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
        return "📦 **$toolName** 执行结果:\n```\n${result.take(previewLimit)}${if (result.length > previewLimit) "\n...(截断)" else ""}\n```$changeSummary"
    }

    private fun buildToolExecutionMessage(
        toolName: String,
        args: String?,
        callId: String?,
        showDebugDetails: Boolean
    ): String {
        val trimmedArgs = args?.trim().orEmpty()
        return buildString {
            append("🔧 正在执行: **")
            append(toolName)
            append("**")
            if (showDebugDetails && !callId.isNullOrBlank()) {
                append("\n调用 ID: `")
                append(callId)
                append("`")
            }
            if (trimmedArgs.isNotBlank()) {
                append("\n```json\n")
                append(trimmedArgs)
                append("\n```")
            } else if (showDebugDetails) {
                append("\n等待工具参数返回…")
            }
        }
    }

    private fun summarizeToolResultForHistory(message: String): String? {
        if (message.isBlank()) return null
        if (message.startsWith(WEB_FETCH_RESULT_PREFIX)) {
            return "工具结果摘要: web_fetch 已抓取网页内容，原始正文已省略以保持上下文稳定。"
        }
        val toolName = Regex("""^📦 \*\*(.+?)\*\* 执行结果:""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?: return message.lineSequence().firstOrNull { it.isNotBlank() }?.take(240)
        val payload = Regex("""```(?:\w+)?\n([\s\S]*?)\n```""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val summary = when (toolName) {
            "file" -> summarizeFileToolPayload(payload)
            "shell" -> summarizeShellToolPayload(payload)
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

    private fun summarizeFileToolPayload(payload: String): String {
        val lines = payload.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val filePath = lines.firstOrNull { it.startsWith("File: ") }?.removePrefix("File: ")?.trim()
        if (filePath != null) {
            val range = lines.firstOrNull { it.startsWith("Lines: ") }
                ?.removePrefix("Lines: ")
                ?.trim()
                ?: "未知行范围"
            return "已读取文件 $filePath，范围 $range；文件正文已从后续历史中省略。"
        }
        val directoryPath = lines.firstOrNull { it.startsWith("Directory: ") }
            ?.removePrefix("Directory: ")
            ?.trim()
        if (directoryPath != null) {
            val entries = lines.firstOrNull { it.startsWith("Entries: ") }
                ?.removePrefix("Entries: ")
                ?.trim()
                ?: "未知条目范围"
            return "已列出目录 $directoryPath，范围 $entries；目录详情已从后续历史中省略。"
        }
        return summarizeGenericToolPayload(payload)
    }

    private fun summarizeShellToolPayload(payload: String): String {
        val firstMeaningfulLine = payload.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return "已执行 shell 命令，原始输出已省略。"
        if (firstMeaningfulLine.startsWith("Error", ignoreCase = true)) {
            return firstMeaningfulLine.take(240)
        }
        return "${firstMeaningfulLine.take(160)}${if (payload.length > firstMeaningfulLine.length) "（其余输出已省略）" else ""}"
    }

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
                add("- 预估成本: \$${"%.6f".format(session.usageSummary.estimatedCostUsd)}")
            }
            if (session.compressionSnapshots.isNotEmpty()) {
                add("- 压缩摘要版本数: ${session.compressionSnapshots.size}")
            }
            session.compressionSnapshot?.takeIf { it.active }?.let {
                add("- 已启用上下文压缩: 是")
                add("- 压缩消息数: ${it.sourceMessageCount}")
                add("- 当前摘要版本: V${it.version}")
            }
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

        return (header + messageBlocks).joinToString("\n\n").trim()
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
            provider.chat(
                request = ChatRequest(
                    messages = buildCompressionSummaryPrompt(state, messages, localSummary),
                    model = config.getActiveModel(),
                    temperature = 0.2,
                    maxTokens = minOf(config.maxTokens, COMPRESSION_SUMMARY_MAX_TOKENS),
                    stream = false,
                    reasoningEffort = config.getActiveReasoningEffort(),
                    thinkingMode = config.getActiveThinkingMode(),
                    tools = null
                ),
                apiKey = apiKey,
                baseUrl = config.getActiveBaseUrl()
            )
        }.getOrNull()

        val content = response?.content?.trim().orEmpty()
        return if (content.isNotBlank() && content.length >= MIN_MODEL_SUMMARY_LENGTH) {
            content
        } else {
            localSummary
        }
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
            .map { it.content.ifBlank { it.reasoning.orEmpty() }.normalizeForSummary() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
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

        return buildString {
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
            buildString {
                append("[")
                append(messageRoleLabel(message.role))
                append("] ")
                append(message.content.normalizeForSummary(MODEL_SUMMARY_MESSAGE_MAX_LENGTH))
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

        return buildString {
            appendLine("会话标题: ${state.sessionTitle.ifBlank { "新对话" }}")
            state.projectPath?.takeIf { it.isNotBlank() }?.let {
                appendLine("项目路径: $it")
            }
            appendLine("累计消息数: ${state.messages.size}")
            appendLine("本次待压缩消息数: ${messages.size}")
            appendLine("本会话累计 Token: ${state.usageSummary.totalTokens}")
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

    private fun resolveCompressionSnapshotsAfterRollback(
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
private const val MAX_CLARIFICATION_TURNS = 3
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
