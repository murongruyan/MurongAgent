package com.murong.agent.core.tool

import com.murong.agent.core.config.ProjectSubagentTemplate
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.loop.AgentEvent
import com.murong.agent.core.loop.AgentLoop
import com.murong.agent.core.loop.UsageSummarySnapshot
import com.murong.agent.core.mcp.McpRegistry
import com.murong.agent.core.provider.ChatMessage
import com.murong.agent.core.provider.ModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

sealed class SubagentUiEvent {
    data class BatchApprovalRequested(
        val batchId: String,
        val parentGoal: String,
        val label: String,
        val runIds: List<String>,
        val statusMessage: String,
        val splitStrategyLabel: String,
        val splitStrategyDetail: String
    ) : SubagentUiEvent()

    data class BatchRejected(
        val batchId: String,
        val parentGoal: String,
        val label: String,
        val runIds: List<String>,
        val reason: String,
        val splitStrategyLabel: String,
        val splitStrategyDetail: String
    ) : SubagentUiEvent()

    data class BatchQueued(
        val batchId: String,
        val parentGoal: String,
        val label: String,
        val runIds: List<String>,
        val splitStrategyLabel: String,
        val splitStrategyDetail: String
    ) : SubagentUiEvent()

    data class ApprovalRequested(
        val runId: String,
        val goal: String,
        val templateId: String? = null,
        val templateTitle: String? = null,
        val model: String,
        val reasoningEffort: String?,
        val allowedTools: List<String>,
        val statusMessage: String,
        val retryCount: Int = 0,
        val sourceRunId: String? = null,
        val batchId: String? = null,
        val batchLabel: String? = null,
        val batchIndex: Int? = null,
        val batchSize: Int? = null
    ) : SubagentUiEvent()

    data class Queued(
        val runId: String,
        val goal: String,
        val templateId: String? = null,
        val templateTitle: String? = null,
        val model: String,
        val reasoningEffort: String?,
        val allowedTools: List<String>,
        val retryCount: Int = 0,
        val sourceRunId: String? = null,
        val batchId: String? = null,
        val batchLabel: String? = null,
        val batchIndex: Int? = null,
        val batchSize: Int? = null
    ) : SubagentUiEvent()

    data class Started(
        val runId: String,
        val goal: String,
        val templateId: String? = null,
        val templateTitle: String? = null,
        val model: String,
        val reasoningEffort: String?,
        val allowedTools: List<String>,
        val retryCount: Int = 0,
        val sourceRunId: String? = null,
        val batchId: String? = null,
        val batchLabel: String? = null,
        val batchIndex: Int? = null,
        val batchSize: Int? = null
    ) : SubagentUiEvent()

    data class Summarizing(
        val runId: String
    ) : SubagentUiEvent()

    data class Completed(
        val runId: String,
        val goal: String,
        val summary: String,
        val templateId: String? = null,
        val templateTitle: String? = null,
        val model: String,
        val reasoningEffort: String?,
        val allowedTools: List<String>,
        val usageSummary: UsageSummarySnapshot,
        val batchId: String? = null,
        val batchLabel: String? = null,
        val batchIndex: Int? = null,
        val batchSize: Int? = null
    ) : SubagentUiEvent()

    data class Failed(
        val runId: String,
        val goal: String,
        val error: String,
        val templateId: String? = null,
        val templateTitle: String? = null,
        val model: String,
        val reasoningEffort: String?,
        val usageSummary: UsageSummarySnapshot,
        val allowedTools: List<String>,
        val batchId: String? = null,
        val batchLabel: String? = null,
        val batchIndex: Int? = null,
        val batchSize: Int? = null
    ) : SubagentUiEvent()

    data class Cancelled(
        val runId: String,
        val goal: String,
        val templateId: String? = null,
        val templateTitle: String? = null,
        val model: String,
        val reasoningEffort: String?,
        val usageSummary: UsageSummarySnapshot,
        val allowedTools: List<String>,
        val batchId: String? = null,
        val batchLabel: String? = null,
        val batchIndex: Int? = null,
        val batchSize: Int? = null
    ) : SubagentUiEvent()

    data class Rejected(
        val runId: String,
        val goal: String,
        val templateId: String? = null,
        val templateTitle: String? = null,
        val model: String,
        val reasoningEffort: String?,
        val allowedTools: List<String>,
        val reason: String,
        val retryCount: Int = 0,
        val sourceRunId: String? = null,
        val batchId: String? = null,
        val batchLabel: String? = null,
        val batchIndex: Int? = null,
        val batchSize: Int? = null
    ) : SubagentUiEvent()
}

/**
 * 子代理工具——让主代理派发一个受限的只读子代理任务。
 */
class SubagentTool(
    private val provider: ModelProvider,
    private val baseConfig: ProviderConfig,
    private val projectTemplates: List<ProjectSubagentTemplate> = emptyList(),
    private val allowedFileOperations: Set<String> = setOf("read", "list", "exists"),
    private val writableFileOperations: Set<String> = emptySet(),
    private val allowWebSearchTool: Boolean = true,
    private val allowCodeEditTool: Boolean = false,
    private val allowShellTool: Boolean = false,
    private val remoteTaskRepositoryTargetProvider: (() -> RemoteTaskRepositoryTarget?)? = null,
    private val remoteTaskRepositoryEditableProvider: () -> Boolean = { false },
    private val githubTokenProvider: () -> String = { "" },
    private val githubApiBaseUrlProvider: () -> String = { "" },
    private val mcpRegistry: McpRegistry? = null,
    private val isCancellationRequested: (String) -> Boolean = { false },
    private val requestApproval: suspend (ToolApprovalRequest) -> Boolean = { false },
    private val scheduleBackgroundExecution: suspend (String, suspend () -> String) -> String =
        { _, executeNow -> executeNow() },
    private val onUiEvent: (SubagentUiEvent) -> Unit = {}
) : Tool {
    private companion object {
        const val MAX_BATCH_GOALS = 6
    }

    override val name = "subagent"
    override val description =
        "派发一个或多个受限子代理去做搜索、审查、代码定位或总结。默认只开放只读工具；如明确请求更高权限，可进入审批后临时开放写文件、代码编辑、远端任务仓库写入或 shell。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "goal" to mapOf(
                "type" to "string",
                "description" to "子代理或子代理编排的总目标，例如：审查项目中的会话切换逻辑是否有 bug"
            ),
            "templateId" to mapOf(
                "type" to "string",
                "description" to "可选。显式指定项目级子代理模板 ID；不填时会按模板关键词自动匹配。"
            ),
            "batchGoals" to mapOf(
                "type" to "array",
                "description" to "可选。一次并行编排多个子目标；提供 2 个及以上条目后，会自动为每个目标创建独立后台子代理并汇总结果。",
                "items" to mapOf("type" to "string")
            ),
            "parallelTasks" to mapOf(
                "type" to "array",
                "description" to "可选。结构化并行任务编排；每项可声明 label/goal/dependsOn，当前仅支持最多 6 项、静态依赖和成功后解锁后继任务。",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "label" to mapOf(
                            "type" to "string",
                            "description" to "可选。任务标签，例如 discover / review / summarize。"
                        ),
                        "goal" to mapOf(
                            "type" to "string",
                            "description" to "任务目标。"
                        ),
                        "dependsOn" to mapOf(
                            "type" to "array",
                            "description" to "可选。依赖的任务序号，使用 1-based 索引，例如 [1,2] 表示依赖前两个任务成功完成后再启动。",
                            "items" to mapOf("type" to "integer")
                        )
                    ),
                    "required" to listOf("goal")
                )
            ),
            "batchLabel" to mapOf(
                "type" to "string",
                "description" to "可选。批次显示名称；仅在 batchGoals 生效时使用。"
            ),
            "model" to mapOf(
                "type" to "string",
                "description" to "可选。指定子代理使用的模型 ID，不填则使用当前模型"
            ),
            "reasoningEffort" to mapOf(
                "type" to "string",
                "enum" to listOf("low", "medium", "high", "xhigh", "max"),
                "description" to "可选。指定子代理推理深度"
            ),
            "allowedTools" to mapOf(
                "type" to "array",
                "description" to "可选。显式收紧这次子代理可用的工具上限，例如 file(read/list/exists)、web_search、code_edit、shell、task_repo_read_file、task_repo_search_replace、task_repo_apply_patch；也可填写已启用的 MCP 工具名，支持带或不带 mcp_ 前缀。",
                "items" to mapOf("type" to "string")
            ),
            "enableWebSearch" to mapOf(
                "type" to "boolean",
                "description" to "是否允许子代理使用联网搜索，默认 true",
                "default" to true
            ),
            "allowWriteAccess" to mapOf(
                "type" to "boolean",
                "description" to "是否允许子代理申请写文件权限。默认 false，若开启会先进入审批。",
                "default" to false
            ),
            "allowCodeEdits" to mapOf(
                "type" to "boolean",
                "description" to "是否允许子代理申请代码编辑工具。默认 false，若开启会先进入审批。",
                "default" to false
            ),
            "allowShell" to mapOf(
                "type" to "boolean",
                "description" to "是否允许子代理申请 shell 工具。默认 false，若开启会先进入审批。",
                "default" to false
            ),
            "background" to mapOf(
                "type" to "boolean",
                "description" to "是否改为后台排队执行。默认 false；开启后会先返回任务已排队，可与其他后台子代理并发。",
                "default" to false
            ),
            "retryCount" to mapOf(
                "type" to "integer",
                "description" to "内部字段。当前子代理是第几次重试，默认 0。",
                "default" to 0
            ),
            "sourceRunId" to mapOf(
                "type" to "string",
                "description" to "内部字段。若来自重试，记录原始子代理运行 ID。"
            )
        ),
        "required" to listOf("goal")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: String): String {
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val goal = obj["goal"]?.jsonPrimitive?.content?.trim()
                ?: return "Error: 'goal' parameter required"
            if (goal.isBlank()) {
                return "Error: 'goal' cannot be blank"
            }

            val requestedModel = obj["model"]?.jsonPrimitive?.content?.trim().orEmpty()
            val templateId = obj["templateId"]?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotBlank() }
            val batchGoals = obj["batchGoals"]
                ?.let(::parseBatchGoals)
                .orEmpty()
            val parallelTasks = obj["parallelTasks"]
                ?.let(::parseParallelTasks)
                .orEmpty()
            val requestedEffort = obj["reasoningEffort"]?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotBlank() }
            val batchLabel = obj["batchLabel"]?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotBlank() }
            val requestedAllowedTools = obj["allowedTools"]
                ?.let(::parseAllowedTools)
                .orEmpty()
            val matchedTemplate = resolveProjectTemplate(goal = goal, explicitTemplateId = templateId)
            val enableWebSearch = (
                parseOptionalBoolean(obj, "enableWebSearch")
                    ?: matchedTemplate?.enableWebSearch
                    ?: true
                ) && allowWebSearchTool
            val allowWriteAccess = (
                parseOptionalBoolean(obj, "allowWriteAccess")
                    ?: matchedTemplate?.allowWriteAccess
                    ?: false
                ) && writableFileOperations.isNotEmpty()
            val allowCodeEdits = (
                parseOptionalBoolean(obj, "allowCodeEdits")
                    ?: matchedTemplate?.allowCodeEdits
                    ?: false
                ) && allowCodeEditTool
            val allowShell = (
                parseOptionalBoolean(obj, "allowShell")
                    ?: matchedTemplate?.allowShell
                    ?: false
                ) && allowShellTool
            val runInBackground =
                obj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val retryCount = obj["retryCount"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
            val sourceRunId = obj["sourceRunId"]?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotBlank() }

            if (parallelTasks.size > 1) {
                return executeParallelTaskRequests(
                    parentGoal = goal,
                    parallelTasks = parallelTasks,
                    batchLabel = batchLabel,
                    requestedModel = requestedModel,
                    requestedEffort = requestedEffort,
                    explicitTemplateId = templateId,
                    requestedAllowedTools = requestedAllowedTools,
                    enableWebSearch = enableWebSearch,
                    allowWriteAccess = allowWriteAccess,
                    allowCodeEdits = allowCodeEdits,
                    allowShell = allowShell
                )
            }

            if (batchGoals.size > 1) {
                return executeBatchRequests(
                    parentGoal = goal,
                    batchGoals = batchGoals,
                    batchLabel = batchLabel,
                    requestedModel = requestedModel,
                    requestedEffort = requestedEffort,
                    explicitTemplateId = templateId,
                    requestedAllowedTools = requestedAllowedTools,
                    enableWebSearch = enableWebSearch,
                    allowWriteAccess = allowWriteAccess,
                    allowCodeEdits = allowCodeEdits,
                    allowShell = allowShell
                )
            }

            val resolvedRequest = buildResolvedRequest(
                goal = goal,
                requestedModel = requestedModel,
                requestedEffort = requestedEffort,
                explicitTemplateId = templateId,
                requestedAllowedTools = requestedAllowedTools,
                enableWebSearch = enableWebSearch,
                allowWriteAccess = allowWriteAccess,
                allowCodeEdits = allowCodeEdits,
                allowShell = allowShell,
                retryCount = retryCount,
                sourceRunId = sourceRunId,
                elevatedPermissionsPreApproved = false,
                batchId = null,
                batchLabel = null,
                batchIndex = null,
                batchSize = null
            )

            if (runInBackground) {
                onUiEvent(
                    SubagentUiEvent.Queued(
                        runId = resolvedRequest.runId,
                        goal = resolvedRequest.goal,
                        templateId = resolvedRequest.templateId,
                        templateTitle = resolvedRequest.templateTitle,
                        model = resolvedRequest.subConfig.getActiveModel(),
                        reasoningEffort = resolvedRequest.subConfig.getActiveReasoningEffort(),
                        allowedTools = resolvedRequest.allowedTools,
                        retryCount = resolvedRequest.retryCount,
                        sourceRunId = resolvedRequest.sourceRunId
                    )
                )
                scheduleBackgroundExecution(resolvedRequest.runId) {
                    executeResolvedRequest(
                        request = resolvedRequest,
                        emitQueuedEvent = false
                    )
                }
            } else {
                executeResolvedRequest(
                    request = resolvedRequest,
                    emitQueuedEvent = true
                )
            }
        } catch (e: CancellationException) {
            val error = e.message ?: "用户已终止子代理"
            val runId = Regex("""run ([A-Za-z0-9]+) cancelled""")
                .find(error)
                ?.groupValues
                ?.getOrNull(1)
            if (runId != null) {
                onUiEvent(
                    SubagentUiEvent.Cancelled(
                        runId = runId,
                        goal = "",
                        model = baseConfig.getActiveModel(),
                        reasoningEffort = baseConfig.getActiveReasoningEffort(),
                        usageSummary = UsageSummarySnapshot(),
                        allowedTools = emptyList()
                    )
                )
            }
            "Subagent cancelled: $error"
        } catch (e: Exception) {
            "Subagent failed: ${e.message}"
        }
    }

    private fun ensureNotCancelled(runId: String) {
        if (isCancellationRequested(runId)) {
            throw CancellationException("run $runId cancelled by user")
        }
    }

    private data class ResolvedSubagentRequest(
        val runId: String,
        val goal: String,
        val templateId: String?,
        val templateTitle: String?,
        val subConfig: ProviderConfig,
        val allowedTools: List<String>,
        val retryCount: Int,
        val sourceRunId: String?,
        val toolRegistry: ToolRegistry,
        val allowWriteAccess: Boolean,
        val allowCodeEdits: Boolean,
        val allowRemoteTaskRepoWrites: Boolean,
        val allowShell: Boolean,
        val elevatedPermissionsPreApproved: Boolean,
        val batchId: String?,
        val batchLabel: String?,
        val batchIndex: Int?,
        val batchSize: Int?
    )

    private data class RequestedToolBudget(
        val effectiveFileOperations: Set<String>,
        val enableWebSearch: Boolean,
        val allowCodeEdits: Boolean,
        val remoteTaskRepoReadTools: List<String>,
        val remoteTaskRepoWriteTools: List<String>,
        val allowShell: Boolean,
        val inheritedMcpTools: List<Tool>,
        val inheritedMcpToolNames: List<String>
    )

    private val remoteTaskRepoReadToolNames = setOf(
        "task_repo_search_code",
        "task_repo_list_dir",
        "task_repo_list_branches",
        "task_repo_read_file"
    )

    private val remoteTaskRepoWriteToolNames = setOf(
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

    private data class BatchSplitPlan(
        val goals: List<String>,
        val strategyLabel: String,
        val strategyDetail: String
    )

    private data class ParallelTaskSpec(
        val label: String?,
        val goal: String,
        val dependsOn: List<Int>
    )

    private data class ParallelTaskPlan(
        val tasks: List<ParallelTaskSpec>,
        val strategyLabel: String,
        val strategyDetail: String
    )

    private data class PlannedParallelTask(
        val request: ResolvedSubagentRequest,
        val label: String?,
        val dependsOnIndexes: Set<Int>
    )

    private enum class BatchGoalCategory(
        val label: String,
        val priority: Int
    ) {
        DISCOVER("信息收集", 1),
        REVIEW("分析审查", 2),
        IMPLEMENT("实现修改", 3),
        VERIFY("验证回归", 4),
        SUMMARIZE("汇总结论", 5),
        GENERAL("通用任务", 6)
    }

    private fun parseBatchGoals(element: kotlinx.serialization.json.JsonElement): List<String> {
        return runCatching {
            element.jsonArray.mapNotNull { item ->
                runCatching { item.jsonPrimitive.content.trim() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseParallelTasks(element: kotlinx.serialization.json.JsonElement): List<ParallelTaskSpec> {
        return runCatching {
            element.jsonArray.mapNotNull { item ->
                val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
                val goal = obj["goal"]?.jsonPrimitive?.content?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val label = obj["label"]?.jsonPrimitive?.content?.trim()
                    ?.takeIf { it.isNotBlank() }
                val dependsOn = obj["dependsOn"]
                    ?.let { depElement ->
                        runCatching {
                            depElement.jsonArray.mapNotNull { dep ->
                                dep.jsonPrimitive.intOrNull
                            }
                        }.getOrDefault(emptyList())
                    }
                    .orEmpty()
                ParallelTaskSpec(
                    label = label,
                    goal = goal,
                    dependsOn = dependsOn
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseAllowedTools(element: kotlinx.serialization.json.JsonElement): List<String> {
        return runCatching {
            when {
                element is kotlinx.serialization.json.JsonArray -> element.mapNotNull { item ->
                    runCatching { item.jsonPrimitive.content.trim() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                }
                else -> element.jsonPrimitive.content
                    .split(',', '\n', ';', '；')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseOptionalBoolean(
        obj: Map<String, kotlinx.serialization.json.JsonElement>,
        key: String
    ): Boolean? {
        return obj[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
    }

    private fun resolveRequestedToolBudget(
        requestedAllowedTools: List<String>,
        defaultEnableWebSearch: Boolean,
        defaultAllowWriteAccess: Boolean,
        defaultAllowCodeEdits: Boolean,
        defaultAllowShell: Boolean
    ): RequestedToolBudget {
        val defaultFileBudget = allowedFileOperations +
            if (defaultAllowWriteAccess) writableFileOperations else emptySet()
        val normalizedTokens = requestedAllowedTools
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (normalizedTokens.isEmpty()) {
            return RequestedToolBudget(
                effectiveFileOperations = defaultFileBudget,
                enableWebSearch = defaultEnableWebSearch,
                allowCodeEdits = defaultAllowCodeEdits,
                remoteTaskRepoReadTools = emptyList(),
                remoteTaskRepoWriteTools = emptyList(),
                allowShell = defaultAllowShell,
                inheritedMcpTools = emptyList(),
                inheritedMcpToolNames = emptyList()
            )
        }

        val sawFileToken = normalizedTokens.any { token -> isFileToolToken(token) || isFileOperationAlias(token) }
        val requestedFileOperations = normalizedTokens
            .flatMap(::parseRequestedFileOperations)
            .toSet()
        val effectiveFileOperations = if (sawFileToken) {
            (if (requestedFileOperations.isEmpty()) defaultFileBudget else requestedFileOperations)
                .intersect(defaultFileBudget)
        } else {
            emptySet()
        }
        val enableWebSearch = defaultEnableWebSearch && normalizedTokens.any(::isWebToolToken)
        val allowCodeEdits = defaultAllowCodeEdits && normalizedTokens.any(::isCodeEditToolToken)
        val (remoteTaskRepoReadTools, remoteTaskRepoWriteTools) =
            resolveRequestedRemoteTaskRepoTools(normalizedTokens)
        val allowShell = defaultAllowShell && normalizedTokens.any(::isShellToolToken)
        val inheritedMcpTools = resolveRequestedMcpTools(normalizedTokens)

        return RequestedToolBudget(
            effectiveFileOperations = effectiveFileOperations,
            enableWebSearch = enableWebSearch,
            allowCodeEdits = allowCodeEdits,
            remoteTaskRepoReadTools = remoteTaskRepoReadTools,
            remoteTaskRepoWriteTools = remoteTaskRepoWriteTools,
            allowShell = allowShell,
            inheritedMcpTools = inheritedMcpTools,
            inheritedMcpToolNames = inheritedMcpTools.map { it.name }
        )
    }

    private fun resolveRequestedRemoteTaskRepoTools(tokens: List<String>): Pair<List<String>, List<String>> {
        val repoTarget = remoteTaskRepositoryTargetProvider?.invoke() ?: return emptyList<String>() to emptyList()
        val allowWriteTools = remoteTaskRepositoryEditableProvider()
        val readTools = linkedSetOf<String>()
        val writeTools = linkedSetOf<String>()
        tokens.forEach { rawToken ->
            val normalized = rawToken.trim().lowercase()
            if (normalized.isBlank()) return@forEach
            if (normalized in setOf("task_repo", "task-repo", "remote_task_repo", "remote-repo")) {
                readTools.addAll(remoteTaskRepoReadToolNames)
                return@forEach
            }
            val canonical = canonicalRemoteTaskRepoToolName(normalized) ?: return@forEach
            if (canonical in remoteTaskRepoReadToolNames) {
                readTools += canonical
            } else if (allowWriteTools && canonical in remoteTaskRepoWriteToolNames) {
                writeTools += canonical
            }
        }
        if (repoTarget.label.isBlank()) {
            return emptyList<String>() to emptyList()
        }
        return readTools.toList() to writeTools.toList()
    }

    private fun resolveRequestedMcpTools(tokens: List<String>): List<Tool> {
        val availableTools = mcpRegistry?.getMcpTools().orEmpty()
        if (availableTools.isEmpty()) return emptyList()
        val indexedByName = availableTools.associateBy { it.name.lowercase() }
        val resolved = linkedMapOf<String, Tool>()
        tokens.forEach { rawToken ->
            val normalized = rawToken.trim().lowercase()
            if (normalized.isBlank()) return@forEach
            if (
                isFileToolToken(normalized) ||
                isFileOperationAlias(normalized) ||
                isWebToolToken(normalized) ||
                isCodeEditToolToken(normalized) ||
                isRemoteTaskRepoToolToken(normalized) ||
                isShellToolToken(normalized) ||
                isAndroidToolToken(normalized)
            ) {
                return@forEach
            }
            val candidates = buildList {
                add(normalized)
                if (!normalized.startsWith("mcp_")) {
                    add("mcp_$normalized")
                }
            }
            val matchedTool = candidates
                .mapNotNull(indexedByName::get)
                .firstOrNull { tool -> baseConfig.isMcpToolEnabled(tool.name) }
                ?: availableTools
                    .filter { tool ->
                        tool.name.substringAfterLast("__").lowercase() == normalized.removePrefix("mcp_") &&
                            baseConfig.isMcpToolEnabled(tool.name)
                    }
                    .singleOrNull()
            if (matchedTool != null) {
                resolved.putIfAbsent(matchedTool.name, matchedTool)
            }
        }
        return resolved.values.toList()
    }

    private fun isFileToolToken(token: String): Boolean {
        val normalized = token.trim().lowercase()
        return normalized in setOf("file", "files", "filesystem", "fs", "local-files") ||
            normalized.startsWith("file(")
    }

    private fun isFileOperationAlias(token: String): Boolean {
        return token.trim().lowercase() in setOf(
            "read",
            "cat",
            "view",
            "open",
            "list",
            "ls",
            "dir",
            "exists",
            "exist",
            "stat",
            "write",
            "create",
            "append",
            "delete",
            "remove",
            "rm",
            "chmod"
        )
    }

    private fun parseRequestedFileOperations(token: String): List<String> {
        val normalized = token.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        val canonicalOperation = when (normalized) {
            "read", "cat", "view", "open" -> "read"
            "list", "ls", "dir" -> "list"
            "exists", "exist", "stat" -> "exists"
            "write", "create", "append" -> "write"
            "delete", "remove", "rm" -> "delete"
            "chmod" -> "chmod"
            else -> null
        }
        if (canonicalOperation != null) return listOf(canonicalOperation)
        if (normalized in setOf("file", "files", "filesystem", "fs", "local-files")) return emptyList()
        if (!normalized.startsWith("file(") || !normalized.endsWith(")")) return emptyList()
        return normalized
            .removePrefix("file(")
            .removeSuffix(")")
            .split('/', ',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .mapNotNull { segment ->
                when (segment.lowercase()) {
                    "read", "cat", "view", "open" -> "read"
                    "list", "ls", "dir" -> "list"
                    "exists", "exist", "stat" -> "exists"
                    "write", "create", "append" -> "write"
                    "delete", "remove", "rm" -> "delete"
                    "chmod" -> "chmod"
                    else -> null
                }
            }
            .distinct()
    }

    private fun isWebToolToken(token: String): Boolean {
        val normalized = token.trim().lowercase()
        return normalized in setOf(
            "web",
            "browser",
            "browse",
            "search",
            "fetch",
            "web_search",
            "web-search",
            "websearch",
            "web_fetch",
            "web-fetch",
            "webfetch"
        )
    }

    private fun isCodeEditToolToken(token: String): Boolean {
        val normalized = token.trim().lowercase()
        return normalized in setOf("code_edit", "code-edit", "codeedit", "edit", "patch", "apply_patch")
    }

    private fun isShellToolToken(token: String): Boolean {
        val normalized = token.trim().lowercase()
        return normalized in setOf("shell", "bash", "sh", "zsh", "pwsh", "powershell", "terminal", "command", "cmd")
    }

    private fun isAndroidToolToken(token: String): Boolean {
        val normalized = token.trim().lowercase()
        return normalized in setOf("android", "android_tool", "android-tool", "android_device", "android-device")
    }

    private fun isRemoteTaskRepoToolToken(token: String): Boolean {
        val normalized = token.trim().lowercase()
        return normalized in setOf("task_repo", "task-repo", "remote_task_repo", "remote-repo") ||
            canonicalRemoteTaskRepoToolName(normalized) != null
    }

    private fun canonicalRemoteTaskRepoToolName(token: String): String? {
        val normalized = token.trim().lowercase().replace('-', '_')
        return when (normalized) {
            "task_repo_search_code", "taskrepo_search_code", "taskrepo_readonly_search" -> "task_repo_search_code"
            "task_repo_list_dir", "taskrepo_list_dir" -> "task_repo_list_dir"
            "task_repo_list_branches", "taskrepo_list_branches" -> "task_repo_list_branches"
            "task_repo_read_file", "taskrepo_read_file" -> "task_repo_read_file"
            "task_repo_create_branch", "taskrepo_create_branch" -> "task_repo_create_branch"
            "task_repo_create_pr", "taskrepo_create_pr" -> "task_repo_create_pr"
            "task_repo_close_pr", "taskrepo_close_pr" -> "task_repo_close_pr"
            "task_repo_delete_branch", "taskrepo_delete_branch" -> "task_repo_delete_branch"
            "task_repo_search_replace", "taskrepo_search_replace" -> "task_repo_search_replace"
            "task_repo_apply_patch", "taskrepo_apply_patch" -> "task_repo_apply_patch"
            "task_repo_update_file", "taskrepo_update_file" -> "task_repo_update_file"
            "task_repo_delete_file", "taskrepo_delete_file" -> "task_repo_delete_file"
            "task_repo_commit_files", "taskrepo_commit_files" -> "task_repo_commit_files"
            else -> null
        }
    }

    private fun resolveProjectTemplate(
        goal: String,
        explicitTemplateId: String?
    ): ProjectSubagentTemplate? {
        val enabledTemplates = projectTemplates.filter { it.enabled }
        explicitTemplateId?.let { targetId ->
            enabledTemplates.firstOrNull { it.id == targetId }?.let { return it }
        }
        val normalizedGoal = goal.lowercase()
        return enabledTemplates
            .mapNotNull { template ->
                val matchCount = template.goalMatchers.count { matcher ->
                    matcher.isNotBlank() && matcher.lowercase() in normalizedGoal
                }
                if (matchCount > 0) template to matchCount else null
            }
            .sortedWith(
                compareByDescending<Pair<ProjectSubagentTemplate, Int>> { it.second }
                    .thenByDescending { it.first.goalMatchers.size }
                    .thenBy { it.first.title.length }
            )
            .firstOrNull()
            ?.first
    }

    private fun buildBatchSplitPlan(batchGoals: List<String>): BatchSplitPlan {
        val expandedGoals = batchGoals
            .flatMap(::expandBatchGoal)
            .map(::cleanBatchGoal)
            .filter { it.isNotBlank() }
        val deduplicatedGoals = linkedMapOf<String, String>()
        expandedGoals.forEach { goal ->
            deduplicatedGoals.putIfAbsent(normalizeBatchGoalKey(goal), goal)
        }
        val sortedGoals = deduplicatedGoals.values
            .sortedWith(
                compareBy<String> { categorizeBatchGoal(it).priority }
                    .thenBy { it.length }
            )
            .take(MAX_BATCH_GOALS)
        val categoryCounts = sortedGoals
            .groupingBy(::categorizeBatchGoal)
            .eachCount()
            .toList()
            .sortedBy { it.first.priority }
        val expandedCount = expandedGoals.size
        val deduplicatedCount = (expandedGoals.size - deduplicatedGoals.size).coerceAtLeast(0)
        val label = buildString {
            append("细粒度拆分")
            if (categoryCounts.isNotEmpty()) {
                append(" · ")
                append(categoryCounts.joinToString(" / ") { (category, count) -> "${category.label}${count}" })
            }
        }
        val detail = buildString {
            append("原始子目标 ")
            append(batchGoals.size)
            append(" 项，展开后 ")
            append(expandedCount)
            append(" 项")
            if (deduplicatedCount > 0) {
                append("，去重 ")
                append(deduplicatedCount)
                append(" 项")
            }
            append("，最终按任务类型细排为 ")
            append(sortedGoals.size)
            append(" 项。")
        }
        return BatchSplitPlan(
            goals = sortedGoals,
            strategyLabel = label,
            strategyDetail = detail
        )
    }

    private fun expandBatchGoal(goal: String): List<String> {
        val normalized = goal.replace("\r\n", "\n")
        val byLine = normalized
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (byLine.size > 1) {
            return byLine.flatMap(::splitInlineBatchGoal)
        }
        return splitInlineBatchGoal(normalized)
    }

    private fun splitInlineBatchGoal(goal: String): List<String> {
        val normalized = goal.trim()
        val numberedPattern = Regex("""(?:^|[\s])(?:\d+[.)]|[（(]\d+[）)]|[-•])\s+""")
        val numberedMatches = numberedPattern.findAll(normalized).count()
        if (numberedMatches >= 2) {
            return numberedPattern.split(normalized)
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
        if (normalized.contains(';') || normalized.contains('；')) {
            return normalized.split(';', '；')
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
        return listOf(normalized)
    }

    private fun cleanBatchGoal(goal: String): String {
        return goal.trim()
            .replace(Regex("""^(?:\d+[.)]|[（(]\d+[）)]|[-•])\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '；', ';', '。')
    }

    private fun normalizeBatchGoalKey(goal: String): String {
        return cleanBatchGoal(goal).lowercase()
    }

    private fun categorizeBatchGoal(goal: String): BatchGoalCategory {
        val normalized = goal.lowercase()
        return when {
            listOf("搜索", "查找", "定位", "扫描", "收集", "检索", "browse", "search", "find", "locate")
                .any { it in normalized } -> BatchGoalCategory.DISCOVER
            listOf("审查", "分析", "检查", "review", "analy", "inspect", "audit")
                .any { it in normalized } -> BatchGoalCategory.REVIEW
            listOf("修改", "实现", "修复", "重构", "编写", "edit", "fix", "implement", "refactor")
                .any { it in normalized } -> BatchGoalCategory.IMPLEMENT
            listOf("验证", "测试", "回归", "确认", "test", "verify", "validate", "regression")
                .any { it in normalized } -> BatchGoalCategory.VERIFY
            listOf("总结", "汇总", "整理", "结论", "summary", "summarize")
                .any { it in normalized } -> BatchGoalCategory.SUMMARIZE
            else -> BatchGoalCategory.GENERAL
        }
    }

    private suspend fun executeBatchRequests(
        parentGoal: String,
        batchGoals: List<String>,
        batchLabel: String?,
        requestedModel: String,
        requestedEffort: String?,
        explicitTemplateId: String?,
        requestedAllowedTools: List<String>,
        enableWebSearch: Boolean,
        allowWriteAccess: Boolean,
        allowCodeEdits: Boolean,
        allowShell: Boolean
    ): String {
        val splitPlan = buildBatchSplitPlan(batchGoals)
        val normalizedGoals = splitPlan.goals
        if (normalizedGoals.size <= 1) {
            return "Subagent batch ignored: need at least 2 valid goals"
        }

        val resolvedBatchLabel = batchLabel ?: "${splitPlan.strategyLabel} ${normalizedGoals.size} 项"
        val batchId = UUID.randomUUID().toString().take(8)
        val requests = normalizedGoals.mapIndexed { index, childGoal ->
            buildResolvedRequest(
                goal = childGoal,
                requestedModel = requestedModel,
                requestedEffort = requestedEffort,
                explicitTemplateId = explicitTemplateId,
                requestedAllowedTools = requestedAllowedTools,
                enableWebSearch = enableWebSearch,
                allowWriteAccess = allowWriteAccess,
                allowCodeEdits = allowCodeEdits,
                allowShell = allowShell,
                retryCount = 0,
                sourceRunId = null,
                elevatedPermissionsPreApproved = false,
                batchId = batchId,
                batchLabel = resolvedBatchLabel,
                batchIndex = index + 1,
                batchSize = normalizedGoals.size
            )
        }

        val batchApprovalRequest = buildBatchApprovalRequest(
            parentGoal = parentGoal,
            batchLabel = resolvedBatchLabel,
            batchGoals = normalizedGoals,
            allowWriteAccess = allowWriteAccess,
            allowCodeEdits = allowCodeEdits,
            allowRemoteTaskRepoWrites = requests.any { it.allowRemoteTaskRepoWrites },
            allowShell = allowShell,
            actualAllowedTools = requests.firstOrNull()?.allowedTools ?: emptyList()
        )
        val batchApproved = if (batchApprovalRequest != null) {
            onUiEvent(
                SubagentUiEvent.BatchApprovalRequested(
                    batchId = batchId,
                    parentGoal = parentGoal,
                    label = resolvedBatchLabel,
                    runIds = requests.map { it.runId },
                    statusMessage = batchApprovalRequest.summary,
                    splitStrategyLabel = splitPlan.strategyLabel,
                    splitStrategyDetail = splitPlan.strategyDetail
                )
            )
            val approved = requestApproval(batchApprovalRequest)
            if (!approved) {
                onUiEvent(
                    SubagentUiEvent.BatchRejected(
                        batchId = batchId,
                        parentGoal = parentGoal,
                        label = resolvedBatchLabel,
                        runIds = requests.map { it.runId },
                        reason = "子代理编排的权限提升请求被拒绝。",
                        splitStrategyLabel = splitPlan.strategyLabel,
                        splitStrategyDetail = splitPlan.strategyDetail
                    )
                )
                return "Subagent batch rejected: elevated permissions were denied by user"
            }
            true
        } else {
            false
        }
        val approvedRequests = requests.map { request ->
            request.copy(elevatedPermissionsPreApproved = batchApproved)
        }

        onUiEvent(
            SubagentUiEvent.BatchQueued(
                batchId = batchId,
                parentGoal = parentGoal,
                label = resolvedBatchLabel,
                runIds = approvedRequests.map { it.runId },
                splitStrategyLabel = splitPlan.strategyLabel,
                splitStrategyDetail = splitPlan.strategyDetail
            )
        )

        approvedRequests.forEach { request ->
            onUiEvent(
                SubagentUiEvent.Queued(
                    runId = request.runId,
                    goal = request.goal,
                    templateId = request.templateId,
                    templateTitle = request.templateTitle,
                    model = request.subConfig.getActiveModel(),
                    reasoningEffort = request.subConfig.getActiveReasoningEffort(),
                    allowedTools = request.allowedTools,
                    retryCount = request.retryCount,
                    sourceRunId = request.sourceRunId,
                    batchId = request.batchId,
                    batchLabel = request.batchLabel,
                    batchIndex = request.batchIndex,
                    batchSize = request.batchSize
                )
            )
            scheduleBackgroundExecution(request.runId) {
                executeResolvedRequest(
                    request = request,
                    emitQueuedEvent = false
                )
            }
        }

        return buildString {
            append("Subagent batch queued:\n")
            append("Batch: ").append(resolvedBatchLabel).append("\n")
            append("Parent goal: ").append(parentGoal).append("\n")
            append("Split strategy: ").append(splitPlan.strategyDetail).append("\n")
            append("Tasks: ").append(approvedRequests.size)
            approvedRequests.forEachIndexed { index, request ->
                append("\n")
                append(index + 1).append(". ")
                append(request.runId).append(" - ").append(request.goal)
            }
        }
    }

    private fun normalizeParallelTaskPlan(tasks: List<ParallelTaskSpec>): ParallelTaskPlan {
        val normalizedTasks = tasks
            .asSequence()
            .map { task ->
                task.copy(
                    label = task.label?.trim()?.takeIf { it.isNotBlank() },
                    goal = task.goal.trim(),
                    dependsOn = task.dependsOn
                        .map { it.coerceAtLeast(1) }
                        .distinct()
                        .sorted()
                )
            }
            .filter { it.goal.isNotBlank() }
            .take(MAX_BATCH_GOALS)
            .toList()
        val dependencyCount = normalizedTasks.sumOf { it.dependsOn.size }
        return ParallelTaskPlan(
            tasks = normalizedTasks,
            strategyLabel = if (dependencyCount > 0) {
                "依赖并行编排"
            } else {
                "并行批次"
            },
            strategyDetail = if (dependencyCount > 0) {
                "共 ${normalizedTasks.size} 个子任务，包含 $dependencyCount 条静态依赖，前置任务成功后解锁后继任务。"
            } else {
                "共 ${normalizedTasks.size} 个独立子任务，可直接并行排队执行。"
            }
        )
    }

    private fun validateParallelTaskPlan(tasks: List<ParallelTaskSpec>): String? {
        if (tasks.size <= 1) return "need at least 2 valid tasks"
        tasks.forEachIndexed { index, task ->
            val currentIndex = index + 1
            if (task.dependsOn.any { it !in 1..tasks.size }) {
                return "task $currentIndex has out-of-range dependsOn indexes"
            }
            if (currentIndex in task.dependsOn) {
                return "task $currentIndex cannot depend on itself"
            }
        }
        val visiting = mutableSetOf<Int>()
        val visited = mutableSetOf<Int>()
        fun dfs(index: Int): Boolean {
            if (index in visiting) return true
            if (!visited.add(index)) return false
            visiting += index
            val hasCycle = tasks[index].dependsOn.any { dep ->
                dfs(dep - 1)
            }
            visiting -= index
            return hasCycle
        }
        if (tasks.indices.any(::dfs)) {
            return "parallelTasks contains a dependency cycle"
        }
        return null
    }

    private suspend fun executeParallelTaskRequests(
        parentGoal: String,
        parallelTasks: List<ParallelTaskSpec>,
        batchLabel: String?,
        requestedModel: String,
        requestedEffort: String?,
        explicitTemplateId: String?,
        requestedAllowedTools: List<String>,
        enableWebSearch: Boolean,
        allowWriteAccess: Boolean,
        allowCodeEdits: Boolean,
        allowShell: Boolean
    ): String {
        val plan = normalizeParallelTaskPlan(parallelTasks)
        val normalizedTasks = plan.tasks
        val validationError = validateParallelTaskPlan(normalizedTasks)
        if (validationError != null) {
            return "Subagent parallel batch ignored: $validationError"
        }

        val resolvedBatchLabel = batchLabel ?: "${plan.strategyLabel} ${normalizedTasks.size} 项"
        val batchId = UUID.randomUUID().toString().take(8)
        val plannedTasks = normalizedTasks.mapIndexed { index, task ->
            val runId = UUID.randomUUID().toString().take(8)
            PlannedParallelTask(
                request = buildResolvedRequest(
                    goal = task.goal,
                    requestedModel = requestedModel,
                    requestedEffort = requestedEffort,
                    explicitTemplateId = explicitTemplateId,
                    requestedAllowedTools = requestedAllowedTools,
                    enableWebSearch = enableWebSearch,
                    allowWriteAccess = allowWriteAccess,
                    allowCodeEdits = allowCodeEdits,
                    allowShell = allowShell,
                    retryCount = 0,
                    sourceRunId = null,
                    elevatedPermissionsPreApproved = false,
                    batchId = batchId,
                    batchLabel = resolvedBatchLabel,
                    batchIndex = index + 1,
                    batchSize = normalizedTasks.size,
                    runIdOverride = runId
                ),
                label = task.label,
                dependsOnIndexes = task.dependsOn.map { it - 1 }.toSet()
            )
        }

        val batchApprovalRequest = buildBatchApprovalRequest(
            parentGoal = parentGoal,
            batchLabel = resolvedBatchLabel,
            batchGoals = plannedTasks.map { planned ->
                planned.label?.let { "[$it] ${planned.request.goal}" } ?: planned.request.goal
            },
            allowWriteAccess = allowWriteAccess,
            allowCodeEdits = allowCodeEdits,
            allowRemoteTaskRepoWrites = plannedTasks.any { it.request.allowRemoteTaskRepoWrites },
            allowShell = allowShell,
            actualAllowedTools = plannedTasks.firstOrNull()?.request?.allowedTools ?: emptyList()
        )
        val batchApproved = if (batchApprovalRequest != null) {
            onUiEvent(
                SubagentUiEvent.BatchApprovalRequested(
                    batchId = batchId,
                    parentGoal = parentGoal,
                    label = resolvedBatchLabel,
                    runIds = plannedTasks.map { it.request.runId },
                    statusMessage = batchApprovalRequest.summary,
                    splitStrategyLabel = plan.strategyLabel,
                    splitStrategyDetail = plan.strategyDetail
                )
            )
            val approved = requestApproval(batchApprovalRequest)
            if (!approved) {
                onUiEvent(
                    SubagentUiEvent.BatchRejected(
                        batchId = batchId,
                        parentGoal = parentGoal,
                        label = resolvedBatchLabel,
                        runIds = plannedTasks.map { it.request.runId },
                        reason = "并行子任务编排的权限提升请求被拒绝。",
                        splitStrategyLabel = plan.strategyLabel,
                        splitStrategyDetail = plan.strategyDetail
                    )
                )
                return "Subagent parallel batch rejected: elevated permissions were denied by user"
            }
            true
        } else {
            false
        }
        val approvedTasks = plannedTasks.map { planned ->
            planned.copy(
                request = planned.request.copy(
                    elevatedPermissionsPreApproved = batchApproved
                )
            )
        }

        onUiEvent(
            SubagentUiEvent.BatchQueued(
                batchId = batchId,
                parentGoal = parentGoal,
                label = resolvedBatchLabel,
                runIds = approvedTasks.map { it.request.runId },
                splitStrategyLabel = plan.strategyLabel,
                splitStrategyDetail = plan.strategyDetail
            )
        )

        approvedTasks.forEach { planned ->
            val request = planned.request
            onUiEvent(
                SubagentUiEvent.Queued(
                    runId = request.runId,
                    goal = request.goal,
                    templateId = request.templateId,
                    templateTitle = request.templateTitle,
                    model = request.subConfig.getActiveModel(),
                    reasoningEffort = request.subConfig.getActiveReasoningEffort(),
                    allowedTools = request.allowedTools,
                    retryCount = request.retryCount,
                    sourceRunId = request.sourceRunId,
                    batchId = request.batchId,
                    batchLabel = request.batchLabel,
                    batchIndex = request.batchIndex,
                    batchSize = request.batchSize
                )
            )
        }

        data class ParallelTransitions(
            val readyIndexes: List<Int>,
            val skippedIndexes: List<Int>
        )

        val lock = Any()
        val terminalSuccess = mutableMapOf<Int, Boolean>()
        val scheduledIndexes = mutableSetOf<Int>()

        fun resolveTransitionsAfterTerminalUpdate(): ParallelTransitions {
            val ready = mutableListOf<Int>()
            val skipped = mutableListOf<Int>()
            var changed = true
            while (changed) {
                changed = false
                approvedTasks.indices.forEach { index ->
                    if (index in scheduledIndexes || index in terminalSuccess) return@forEach
                    val deps = approvedTasks[index].dependsOnIndexes
                    if (deps.any { dep -> terminalSuccess[dep] == false }) {
                        terminalSuccess[index] = false
                        skipped += index
                        changed = true
                    } else if (deps.all { dep -> terminalSuccess[dep] == true }) {
                        scheduledIndexes += index
                        ready += index
                        changed = true
                    }
                }
            }
            return ParallelTransitions(
                readyIndexes = ready,
                skippedIndexes = skipped
            )
        }

        fun emitDependencySkippedFailure(index: Int) {
            val request = approvedTasks[index].request
            val failedDeps = approvedTasks[index].dependsOnIndexes
                .map { dep -> approvedTasks[dep] }
                .filter { dep -> terminalSuccess[approvedTasks.indexOf(dep)] == false }
                .joinToString("、") { dep ->
                    dep.label ?: "任务${dep.request.batchIndex}"
                }
                .ifBlank { "前置任务" }
            onUiEvent(
                SubagentUiEvent.Failed(
                    runId = request.runId,
                    goal = request.goal,
                    error = "依赖任务未成功完成，当前并行子任务未启动：$failedDeps",
                    templateId = request.templateId,
                    templateTitle = request.templateTitle,
                    model = request.subConfig.getActiveModel(),
                    reasoningEffort = request.subConfig.getActiveReasoningEffort(),
                    usageSummary = UsageSummarySnapshot(),
                    allowedTools = request.allowedTools,
                    batchId = request.batchId,
                    batchLabel = request.batchLabel,
                    batchIndex = request.batchIndex,
                    batchSize = request.batchSize
                )
            )
        }

        suspend fun scheduleTask(index: Int) {
            val request = approvedTasks[index].request
            scheduleBackgroundExecution(request.runId) {
                try {
                    val result = executeResolvedRequest(
                        request = request,
                        emitQueuedEvent = false
                    )
                    val transitions = synchronized(lock) {
                        terminalSuccess[index] = result.startsWith("Subagent summary:")
                        resolveTransitionsAfterTerminalUpdate()
                    }
                    transitions.skippedIndexes.forEach(::emitDependencySkippedFailure)
                    transitions.readyIndexes.forEach { nextIndex ->
                        scheduleTask(nextIndex)
                    }
                    result
                } catch (e: CancellationException) {
                    val transitions = synchronized(lock) {
                        terminalSuccess[index] = false
                        resolveTransitionsAfterTerminalUpdate()
                    }
                    transitions.skippedIndexes.forEach(::emitDependencySkippedFailure)
                    transitions.readyIndexes.forEach { nextIndex ->
                        scheduleTask(nextIndex)
                    }
                    throw e
                } catch (e: Exception) {
                    val transitions = synchronized(lock) {
                        terminalSuccess[index] = false
                        resolveTransitionsAfterTerminalUpdate()
                    }
                    transitions.skippedIndexes.forEach(::emitDependencySkippedFailure)
                    transitions.readyIndexes.forEach { nextIndex ->
                        scheduleTask(nextIndex)
                    }
                    throw e
                }
            }
        }

        val initialTransitions = synchronized(lock) {
            resolveTransitionsAfterTerminalUpdate()
        }
        initialTransitions.skippedIndexes.forEach(::emitDependencySkippedFailure)
        initialTransitions.readyIndexes.forEach { index ->
            scheduleTask(index)
        }

        return buildString {
            append("Subagent parallel batch queued:\n")
            append("Batch: ").append(resolvedBatchLabel).append("\n")
            append("Parent goal: ").append(parentGoal).append("\n")
            append("Strategy: ").append(plan.strategyDetail).append("\n")
            append("Tasks: ").append(approvedTasks.size)
            approvedTasks.forEachIndexed { index, planned ->
                append("\n")
                append(index + 1).append(". ")
                planned.label?.let {
                    append("[").append(it).append("] ")
                }
                append(planned.request.runId).append(" - ").append(planned.request.goal)
                if (planned.dependsOnIndexes.isNotEmpty()) {
                    append(" (dependsOn=")
                    append(
                        planned.dependsOnIndexes
                            .map { it + 1 }
                            .sorted()
                            .joinToString(",")
                    )
                    append(")")
                }
            }
        }
    }

    private fun buildResolvedRequest(
        goal: String,
        requestedModel: String,
        requestedEffort: String?,
        explicitTemplateId: String?,
        requestedAllowedTools: List<String>,
        enableWebSearch: Boolean,
        allowWriteAccess: Boolean,
        allowCodeEdits: Boolean,
        allowShell: Boolean,
        retryCount: Int,
        sourceRunId: String?,
        elevatedPermissionsPreApproved: Boolean,
        batchId: String?,
        batchLabel: String?,
        batchIndex: Int?,
        batchSize: Int?,
        runIdOverride: String? = null
    ): ResolvedSubagentRequest {
        val matchedTemplate = resolveProjectTemplate(goal = goal, explicitTemplateId = explicitTemplateId)
        val subConfig = resolveSubagentConfig(
            modelOverride = requestedModel.ifBlank {
                matchedTemplate?.preferredModel?.trim()?.takeIf { it.isNotBlank() }
            },
            reasoningEffortOverride = requestedEffort
                ?: matchedTemplate?.preferredReasoningEffort?.trim()?.takeIf { it.isNotBlank() }
        )
        val runId = runIdOverride ?: UUID.randomUUID().toString().take(8)
        val requestedToolBudget = resolveRequestedToolBudget(
            requestedAllowedTools = requestedAllowedTools,
            defaultEnableWebSearch = enableWebSearch,
            defaultAllowWriteAccess = allowWriteAccess,
            defaultAllowCodeEdits = allowCodeEdits,
            defaultAllowShell = allowShell
        )
        val effectiveFileOperations = requestedToolBudget.effectiveFileOperations.sorted()
        val effectiveAllowWriteAccess = effectiveFileOperations.any { it in writableFileOperations }
        val effectiveAllowCodeEdits = requestedToolBudget.allowCodeEdits
        val effectiveRemoteTaskRepoReadTools = requestedToolBudget.remoteTaskRepoReadTools
        val effectiveRemoteTaskRepoWriteTools = requestedToolBudget.remoteTaskRepoWriteTools
        val effectiveAllowRemoteTaskRepoWrites = effectiveRemoteTaskRepoWriteTools.isNotEmpty()
        val effectiveAllowShell = requestedToolBudget.allowShell
        val allowedTools = buildList {
            if (effectiveFileOperations.isNotEmpty()) {
                add("file(${effectiveFileOperations.joinToString("/")})")
            }
            if (requestedToolBudget.enableWebSearch) {
                add("web_search")
                add("web_fetch")
            }
            if (effectiveAllowCodeEdits) {
                add("code_edit")
                add("code_search")
            }
            addAll(effectiveRemoteTaskRepoReadTools)
            addAll(effectiveRemoteTaskRepoWriteTools)
            if (effectiveAllowShell) {
                add("shell")
                add("android")
            }
            addAll(requestedToolBudget.inheritedMcpToolNames)
        }
        val toolRegistry = ToolRegistry().apply {
            if (effectiveFileOperations.isNotEmpty()) {
                register(FileTool(effectiveFileOperations.toSet()))
            }
            if (requestedToolBudget.enableWebSearch) {
                register(WebSearchTool(subConfig))
                register(WebFetchTool())
            }
            if (effectiveAllowCodeEdits) {
                register(CodeEditTool())
                register(CodeSearchTool())
            }
            val remoteRepositoryProvider = remoteTaskRepositoryTargetProvider
            if (remoteRepositoryProvider != null) {
                (effectiveRemoteTaskRepoReadTools + effectiveRemoteTaskRepoWriteTools)
                    .distinct()
                    .mapNotNull { toolName ->
                        createRemoteTaskRepositoryTool(toolName, remoteRepositoryProvider)
                    }
                    .forEach(::register)
            }
            if (effectiveAllowShell) {
                register(ShellTool())
                register(AndroidTool())
            }
            requestedToolBudget.inheritedMcpTools.forEach(::register)
        }
        return ResolvedSubagentRequest(
            runId = runId,
            goal = goal,
            templateId = matchedTemplate?.id,
            templateTitle = matchedTemplate?.title?.takeIf { it.isNotBlank() },
            subConfig = subConfig,
            allowedTools = allowedTools,
            retryCount = retryCount,
            sourceRunId = sourceRunId,
            toolRegistry = toolRegistry,
            allowWriteAccess = effectiveAllowWriteAccess,
            allowCodeEdits = effectiveAllowCodeEdits,
            allowRemoteTaskRepoWrites = effectiveAllowRemoteTaskRepoWrites,
            allowShell = effectiveAllowShell,
            elevatedPermissionsPreApproved = elevatedPermissionsPreApproved,
            batchId = batchId,
            batchLabel = batchLabel,
            batchIndex = batchIndex,
            batchSize = batchSize
        )
    }

    private suspend fun executeResolvedRequest(
        request: ResolvedSubagentRequest,
        emitQueuedEvent: Boolean
    ): String {
        ensureNotCancelled(request.runId)
        if (emitQueuedEvent) {
            onUiEvent(
                SubagentUiEvent.Queued(
                    runId = request.runId,
                    goal = request.goal,
                    templateId = request.templateId,
                    templateTitle = request.templateTitle,
                    model = request.subConfig.getActiveModel(),
                    reasoningEffort = request.subConfig.getActiveReasoningEffort(),
                    allowedTools = request.allowedTools,
                    retryCount = request.retryCount,
                    sourceRunId = request.sourceRunId,
                    batchId = request.batchId,
                    batchLabel = request.batchLabel,
                    batchIndex = request.batchIndex,
                    batchSize = request.batchSize
                )
            )
        }

        val elevatedToolRequest = if (request.elevatedPermissionsPreApproved) {
            null
        } else {
            buildSubagentApprovalRequest(
                goal = request.goal,
                allowWriteAccess = request.allowWriteAccess,
                allowCodeEdits = request.allowCodeEdits,
                allowRemoteTaskRepoWrites = request.allowRemoteTaskRepoWrites,
                allowShell = request.allowShell,
                actualAllowedTools = request.allowedTools
            )
        }

        if (elevatedToolRequest != null) {
            onUiEvent(
                SubagentUiEvent.ApprovalRequested(
                    runId = request.runId,
                    goal = request.goal,
                    templateId = request.templateId,
                    templateTitle = request.templateTitle,
                    model = request.subConfig.getActiveModel(),
                    reasoningEffort = request.subConfig.getActiveReasoningEffort(),
                    allowedTools = request.allowedTools,
                    statusMessage = elevatedToolRequest.summary,
                    retryCount = request.retryCount,
                    sourceRunId = request.sourceRunId,
                    batchId = request.batchId,
                    batchLabel = request.batchLabel,
                    batchIndex = request.batchIndex,
                    batchSize = request.batchSize
                )
            )
            val approved = requestApproval(elevatedToolRequest)
            ensureNotCancelled(request.runId)
            if (!approved) {
                onUiEvent(
                    SubagentUiEvent.Rejected(
                        runId = request.runId,
                        goal = request.goal,
                        templateId = request.templateId,
                        templateTitle = request.templateTitle,
                        model = request.subConfig.getActiveModel(),
                        reasoningEffort = request.subConfig.getActiveReasoningEffort(),
                        allowedTools = request.allowedTools,
                        reason = "子代理权限提升请求被拒绝。",
                        retryCount = request.retryCount,
                        sourceRunId = request.sourceRunId,
                        batchId = request.batchId,
                        batchLabel = request.batchLabel,
                        batchIndex = request.batchIndex,
                        batchSize = request.batchSize
                    )
                )
                return "Subagent rejected: elevated permissions were denied by user"
            }
        }

        onUiEvent(
            SubagentUiEvent.Started(
                runId = request.runId,
                goal = request.goal,
                templateId = request.templateId,
                templateTitle = request.templateTitle,
                model = request.subConfig.getActiveModel(),
                reasoningEffort = request.subConfig.getActiveReasoningEffort(),
                allowedTools = request.allowedTools,
                retryCount = request.retryCount,
                sourceRunId = request.sourceRunId,
                batchId = request.batchId,
                batchLabel = request.batchLabel,
                batchIndex = request.batchIndex,
                batchSize = request.batchSize
            )
        )

        val subLoop = AgentLoop(provider, request.toolRegistry, request.subConfig)
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var lastError: String? = null
        var usageSummary = UsageSummarySnapshot()

        subLoop.processMessage(
            userMessage = ChatMessage(
                role = "user",
                content = request.goal
            ),
            history = emptyList(),
            onEvent = { event ->
                ensureNotCancelled(request.runId)
                when (event) {
                    is AgentEvent.ContentDelta -> content.append(event.text)
                    is AgentEvent.ReasoningDelta -> reasoning.append(event.text)
                    is AgentEvent.Error -> lastError = event.message
                    is AgentEvent.ReadinessAudit -> Unit
                    is AgentEvent.UsageUpdate -> {
                        usageSummary = UsageSummarySnapshot(
                            promptTokens = usageSummary.promptTokens + event.usage.promptTokens,
                            completionTokens = usageSummary.completionTokens + event.usage.completionTokens,
                            totalTokens = usageSummary.totalTokens + event.usage.totalTokens,
                            promptCacheHitTokens = usageSummary.promptCacheHitTokens + (event.usage.promptCacheHitTokens ?: 0),
                            promptCacheMissTokens = usageSummary.promptCacheMissTokens + (event.usage.promptCacheMissTokens ?: 0),
                            estimatedCostAmount = usageSummary.resolvedEstimatedCostAmount() + request.subConfig.estimateCostAmount(
                                promptTokens = event.usage.promptTokens,
                                completionTokens = event.usage.completionTokens
                            ),
                            estimatedCostCurrency = request.subConfig.estimateCostCurrency(),
                            estimatedCostUsd = usageSummary.estimatedCostUsd + request.subConfig.estimateCostUsd(
                                promptTokens = event.usage.promptTokens,
                                completionTokens = event.usage.completionTokens
                            )
                        )
                    }
                    else -> Unit
                }
                ensureNotCancelled(request.runId)
            },
            requestApproval = requestApproval
        )

        ensureNotCancelled(request.runId)
        onUiEvent(SubagentUiEvent.Summarizing(request.runId))
        val summary = content.toString().trim().ifBlank {
            reasoning.toString().trim()
        }

        return if (summary.isBlank()) {
            val error = lastError ?: "子代理没有返回有效结果"
            onUiEvent(
                SubagentUiEvent.Failed(
                    runId = request.runId,
                    goal = request.goal,
                    error = error,
                    templateId = request.templateId,
                    templateTitle = request.templateTitle,
                    model = request.subConfig.getActiveModel(),
                    reasoningEffort = request.subConfig.getActiveReasoningEffort(),
                    usageSummary = usageSummary,
                    allowedTools = request.allowedTools,
                    batchId = request.batchId,
                    batchLabel = request.batchLabel,
                    batchIndex = request.batchIndex,
                    batchSize = request.batchSize
                )
            )
            "Subagent failed: $error"
        } else {
            ensureNotCancelled(request.runId)
            onUiEvent(
                SubagentUiEvent.Completed(
                    runId = request.runId,
                    goal = request.goal,
                    summary = summary,
                    templateId = request.templateId,
                    templateTitle = request.templateTitle,
                    model = request.subConfig.getActiveModel(),
                    reasoningEffort = request.subConfig.getActiveReasoningEffort(),
                    allowedTools = request.allowedTools,
                    usageSummary = usageSummary,
                    batchId = request.batchId,
                    batchLabel = request.batchLabel,
                    batchIndex = request.batchIndex,
                    batchSize = request.batchSize
                )
            )
            buildString {
                append("Subagent summary:\n")
                append(summary)
            }
        }
    }

    private fun buildSubagentApprovalRequest(
        goal: String,
        allowWriteAccess: Boolean,
        allowCodeEdits: Boolean,
        allowRemoteTaskRepoWrites: Boolean,
        allowShell: Boolean,
        actualAllowedTools: List<String>
    ): ToolApprovalRequest? {
        if (!allowWriteAccess && !allowCodeEdits && !allowRemoteTaskRepoWrites && !allowShell) return null

        val requestedCapabilities = buildRequestedCapabilities(
            allowWriteAccess = allowWriteAccess,
            allowCodeEdits = allowCodeEdits,
            allowRemoteTaskRepoWrites = allowRemoteTaskRepoWrites,
            allowShell = allowShell
        )
        val approvalScopeTokens = buildApprovalScopeTokens(
            allowWriteAccess = allowWriteAccess,
            allowCodeEdits = allowCodeEdits,
            allowRemoteTaskRepoWrites = allowRemoteTaskRepoWrites,
            allowShell = allowShell
        )
        val detail = buildString {
            append("目标: ")
            append(goal)
            append("\n")
            append("申请能力: ")
            append(requestedCapabilities.joinToString("、"))
            append("\n")
            append("授权范围: ")
            append(requestedCapabilities.joinToString("、"))
            if (actualAllowedTools.isNotEmpty()) {
                append("\n")
                append("启用工具: ")
                append(actualAllowedTools.joinToString(", "))
            }
            append("\n")
            append("说明: 审批通过后，子代理仍会沿用宿主会话的工具审批规则。")
        }
        return ToolApprovalRequest(
            toolName = name,
            summary = "子代理请求提权: ${requestedCapabilities.joinToString("、")}",
            detail = detail,
            riskLevel = ApprovalRiskLevel.HIGH,
            rawArgs = buildString {
                append("goal=")
                append(goal)
                append("; tools=")
                append(actualAllowedTools.joinToString(","))
            },
            approvalScopeTokens = approvalScopeTokens
        )
    }

    private fun buildBatchApprovalRequest(
        parentGoal: String,
        batchLabel: String,
        batchGoals: List<String>,
        allowWriteAccess: Boolean,
        allowCodeEdits: Boolean,
        allowRemoteTaskRepoWrites: Boolean,
        allowShell: Boolean,
        actualAllowedTools: List<String>
    ): ToolApprovalRequest? {
        if (!allowWriteAccess && !allowCodeEdits && !allowRemoteTaskRepoWrites && !allowShell) return null

        val requestedCapabilities = buildRequestedCapabilities(
            allowWriteAccess = allowWriteAccess,
            allowCodeEdits = allowCodeEdits,
            allowRemoteTaskRepoWrites = allowRemoteTaskRepoWrites,
            allowShell = allowShell
        )
        val approvalScopeTokens = buildApprovalScopeTokens(
            allowWriteAccess = allowWriteAccess,
            allowCodeEdits = allowCodeEdits,
            allowRemoteTaskRepoWrites = allowRemoteTaskRepoWrites,
            allowShell = allowShell
        )
        val detail = buildString {
            append("批次: ")
            append(batchLabel)
            append("\n")
            append("主目标: ")
            append(parentGoal)
            append("\n")
            append("子任务数: ")
            append(batchGoals.size)
            append("\n")
            append("申请能力: ")
            append(requestedCapabilities.joinToString("、"))
            append("\n")
            append("授权范围: ")
            append(requestedCapabilities.joinToString("、"))
            if (actualAllowedTools.isNotEmpty()) {
                append("\n")
                append("启用工具: ")
                append(actualAllowedTools.joinToString(", "))
            }
            append("\n")
            append("子任务:\n")
            batchGoals.forEachIndexed { index, childGoal ->
                append(index + 1)
                append(". ")
                append(childGoal)
                append("\n")
            }
            append("说明: 审批通过后，这一批子代理会沿用宿主会话的工具审批规则，但不会再逐个重复申请同一批提权。")
        }.trim()
        return ToolApprovalRequest(
            toolName = name,
            summary = "子代理编排请求提权: ${requestedCapabilities.joinToString("、")}",
            detail = detail,
            riskLevel = ApprovalRiskLevel.HIGH,
            rawArgs = buildString {
                append("batch=")
                append(batchLabel)
                append("; goal=")
                append(parentGoal)
                append("; tools=")
                append(actualAllowedTools.joinToString(","))
            },
            approvalScopeTokens = approvalScopeTokens
        )
    }

    private fun buildRequestedCapabilities(
        allowWriteAccess: Boolean,
        allowCodeEdits: Boolean,
        allowRemoteTaskRepoWrites: Boolean,
        allowShell: Boolean
    ): List<String> {
        return buildList {
            if (allowWriteAccess) {
                add(if (allowCodeEdits) "代码文件写入" else "通用文件写入")
            }
            if (allowCodeEdits) add("代码编辑")
            if (allowRemoteTaskRepoWrites) add("远端任务仓库写入")
            if (allowShell) add("Shell")
        }
    }

    private fun buildApprovalScopeTokens(
        allowWriteAccess: Boolean,
        allowCodeEdits: Boolean,
        allowRemoteTaskRepoWrites: Boolean,
        allowShell: Boolean
    ): Set<String> {
        return buildSet {
            if (allowWriteAccess) {
                add(
                    if (allowCodeEdits) {
                        "subagent:cap:file_write_code"
                    } else {
                        "subagent:cap:file_write_general"
                    }
                )
            }
            if (allowCodeEdits) add("subagent:cap:code_edit_apply")
            if (allowRemoteTaskRepoWrites) add("subagent:cap:task_repo_write")
            if (allowShell) add("subagent:cap:shell_exec")
        }
    }

    private fun createRemoteTaskRepositoryTool(
        toolName: String,
        repositoryProvider: () -> RemoteTaskRepositoryTarget?
    ): Tool? {
        return when (toolName) {
            "task_repo_search_code" -> TaskRepoSearchCodeTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            "task_repo_list_dir" -> TaskRepoListDirTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            "task_repo_list_branches" -> TaskRepoListBranchesTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            "task_repo_read_file" -> TaskRepoReadFileTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            "task_repo_create_branch" -> TaskRepoCreateBranchTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            "task_repo_create_pr" -> TaskRepoCreatePrTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            "task_repo_close_pr" -> TaskRepoClosePrTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            "task_repo_delete_branch" -> TaskRepoDeleteBranchTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            "task_repo_search_replace" -> TaskRepoSearchReplaceTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            "task_repo_apply_patch" -> TaskRepoApplyPatchTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            "task_repo_update_file" -> TaskRepoUpdateFileTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            "task_repo_delete_file" -> TaskRepoDeleteFileTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            "task_repo_commit_files" -> TaskRepoCommitFilesTool(
                repositoryProvider = repositoryProvider,
                githubTokenProvider = githubTokenProvider,
                githubApiBaseUrlProvider = githubApiBaseUrlProvider
            )
            else -> null
        }
    }

    private fun resolveSubagentConfig(
        modelOverride: String?,
        reasoningEffortOverride: String?
    ): ProviderConfig {
        val defaultConfig = baseConfig.getSubagentDefaultResolvedConfig()
        val resolvedEffort = reasoningEffortOverride ?: defaultConfig.getActiveReasoningEffort()
        return when (defaultConfig.activeProviderId) {
            "deepseek" -> {
                val targetModel = modelOverride ?: defaultConfig.getActiveModel()
                val preset = when (targetModel) {
                    "deepseek-v4-flash" -> "flash"
                    "deepseek-v4-pro" -> "pro"
                    else -> "custom"
                }
                defaultConfig.copy(
                    deepseekModelPreset = preset,
                    deepseekModel = targetModel,
                    deepseekReasoningEffort = resolvedEffort ?: defaultConfig.deepseekReasoningEffort
                )
            }

            "openai-compatible" -> defaultConfig.copy(
                openaiModel = modelOverride ?: defaultConfig.getActiveModel(),
                openaiReasoningEffort = resolvedEffort ?: defaultConfig.openaiReasoningEffort
            )

            "claude" -> defaultConfig.copy(
                claudeModel = modelOverride ?: defaultConfig.getActiveModel(),
                claudeReasoningEffort = resolvedEffort ?: defaultConfig.claudeReasoningEffort
            )

            else -> defaultConfig
        }
    }
}
