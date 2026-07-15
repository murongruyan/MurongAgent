package com.murong.agent.core.tool

import com.murong.agent.core.config.GlobalRule
import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.SkillRunAs
import com.murong.agent.core.memory.MemoryDraft
import com.murong.agent.core.memory.MutableMemoryStore
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpConfigSource
import com.murong.agent.core.mcp.McpTransportType
import com.murong.agent.core.skill.MutableSkillStore
import com.murong.agent.core.skill.SkillCatalogEntry
import com.murong.agent.core.skill.SkillDraft
import com.murong.agent.core.skill.SkillSource
import com.murong.agent.core.skill.SkillStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

internal class CreateGlobalRuleTool(
    private val configProvider: suspend () -> ProviderConfig,
    private val saveConfig: suspend (ProviderConfig) -> Unit
) : Tool {
    override val name: String = "create_global_rule"
    override val description: String =
        "手动创建一条全局规则。仅当用户明确要求“保存为全局规则/添加到全局规则”时才调用，不要自动识别普通对话。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf("type" to "string", "description" to "规则标题"),
            "content" to mapOf("type" to "string", "description" to "规则正文，支持普通文本或 Markdown"),
            "enabled" to mapOf("type" to "boolean", "description" to "是否立即启用，默认 true")
        ),
        "required" to listOf("title", "content")
    )

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val title = parseObject(args)["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isBlank()) return null
        return ToolApprovalRequest(
            toolName = name,
            summary = "创建全局规则",
            detail = title,
            riskLevel = ApprovalRiskLevel.MEDIUM,
            rawArgs = args
        )
    }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = parseObject(args)
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            require(title.isNotBlank()) { "'title' parameter required" }
            require(content.isNotBlank()) { "'content' parameter required" }
            val config = configProvider()
            val updated = config.globalRules + GlobalRule(
                id = UUID.randomUUID().toString().take(8),
                title = title,
                content = content,
                enabled = enabled
            )
            saveConfig(config.copy(globalRules = updated))
            "已创建全局规则：$title（${if (enabled) "已启用" else "已停用"}），当前共 ${updated.size} 条。"
        }.getOrElse { error ->
            "Error: 创建全局规则失败: ${error.message}"
        }
    }
}

internal class CreateGlobalMemoryTool(
    private val memoryStore: MutableMemoryStore
) : Tool {
    override val name: String = "create_global_memory"
    override val description: String =
        "手动创建一条全局记忆。仅当用户明确要求“保存为全局记忆/添加到全局记忆”时才调用，不要自动识别普通对话。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf("type" to "string", "description" to "记忆标题"),
            "content" to mapOf("type" to "string", "description" to "记忆正文，支持普通文本或 Markdown"),
            "enabled" to mapOf("type" to "boolean", "description" to "是否立即启用，默认 true")
        ),
        "required" to listOf("title", "content")
    )

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val title = parseObject(args)["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isBlank()) return null
        return ToolApprovalRequest(
            toolName = name,
            summary = "创建全局记忆",
            detail = title,
            riskLevel = ApprovalRiskLevel.MEDIUM,
            rawArgs = args
        )
    }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = parseObject(args)
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            require(title.isNotBlank()) { "'title' parameter required" }
            require(content.isNotBlank()) { "'content' parameter required" }
            val saved = memoryStore.createGlobalMemory(
                MemoryDraft(
                    title = title,
                    content = content,
                    enabled = enabled
                )
            )
            "已创建全局记忆：$title（${if (enabled) "已启用" else "已停用"}），" +
                "当前共 ${saved.totalCount} 条。memory_id=${saved.savedMemory.id}"
        }.getOrElse { error ->
            "Error: 创建全局记忆失败: ${error.message}"
        }
    }
}

internal class CreateGlobalSkillTool(
    private val skillStore: MutableSkillStore
) : Tool {
    override val name: String = "create_global_skill"
    override val description: String =
        "手动导入一条全局 Skill。仅当用户明确要求“导入 Skill/保存为全局 Skill”时才调用，不要自动识别普通对话。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf("type" to "string", "description" to "Skill 标题"),
            "description" to mapOf("type" to "string", "description" to "Skill 描述"),
            "content" to mapOf("type" to "string", "description" to "Skill 内容，支持 Markdown"),
            "runAs" to mapOf("type" to "string", "enum" to listOf("inline", "subagent"), "description" to "执行方式"),
            "allowedTools" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "允许工具列表"),
            "preferredModel" to mapOf("type" to "string", "description" to "首选模型，可选"),
            "enabled" to mapOf("type" to "boolean", "description" to "是否立即启用，默认 true")
        ),
        "required" to listOf("title", "content")
    )

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val title = parseObject(args)["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isBlank()) return null
        return ToolApprovalRequest(
            toolName = name,
            summary = "导入全局 Skill",
            detail = title,
            riskLevel = ApprovalRiskLevel.MEDIUM,
            rawArgs = args
        )
    }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = parseObject(args)
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(title.isNotBlank()) { "'title' parameter required" }
            require(content.isNotBlank()) { "'content' parameter required" }
            val description = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val runAs = when (obj["runAs"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()) {
                "subagent" -> SkillRunAs.SUBAGENT
                else -> SkillRunAs.INLINE
            }
            val allowedTools = normalizeAllowedTools(obj.stringList("allowedTools"))
            val preferredModel = obj["preferredModel"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            val saved = skillStore.createGlobalSkill(
                SkillDraft(
                    title = title,
                    description = description,
                    content = content,
                    runAs = runAs,
                    allowedTools = allowedTools,
                    preferredModel = preferredModel,
                    enabled = enabled
                )
            )
            "已导入全局 Skill：$title（${if (enabled) "已启用" else "已停用"}），当前共 ${saved.totalCount} 条。"
        }.getOrElse { error ->
            "Error: 导入全局 Skill 失败: ${error.message}"
        }
    }
}

internal class ReadSkillTool(
    private val skillStore: SkillStore
) : Tool {
    override val name: String = "read_skill"
    override val description: String =
        "读取当前可用的全局或项目 Skill；可用于列出 Skills、查看某条 Skill 的完整说明，再决定是否执行。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "skill" to mapOf("type" to "string", "description" to "Skill 的 id、标题或关键词；留空时列出当前可用 Skills"),
            "source" to mapOf(
                "type" to "string",
                "enum" to listOf("any", "project", "global"),
                "description" to "限定 Skill 来源，默认 any"
            ),
            "includeContent" to mapOf(
                "type" to "boolean",
                "description" to "查看单条 Skill 时是否返回完整正文，默认 true"
            )
        )
    )

    override suspend fun execute(args: String): String = executeWithResult(args).output

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        return runCatching {
            val obj = parseObject(args)
            val skillQuery = obj["skill"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val source = SkillSource.fromRaw(obj["source"]?.jsonPrimitive?.contentOrNull)
            val includeContent = obj["includeContent"]?.jsonPrimitive?.booleanOrNull ?: true
            val entries = skillStore.list()
            if (entries.isEmpty()) {
                return@runCatching ToolExecutionResult(
                    output = "当前没有启用中的 Skill。",
                    structuredPayload = ToolStructuredPayload(
                        skill = SkillToolPayload(
                            kind = "empty_catalog",
                            source = source.wireName()
                        )
                    )
                )
            }
            val scopedEntries = skillStore.list(source)
            if (skillQuery.isBlank()) {
                return@runCatching ToolExecutionResult(
                    output = buildSkillCatalogSummary(entries, source),
                    structuredPayload = ToolStructuredPayload(
                        skill = SkillToolPayload(
                            kind = "catalog",
                            source = source.wireName(),
                            matchedSkillIds = scopedEntries.map { it.skill.id },
                            matchedSkillTitles = scopedEntries.map { it.skill.title.ifBlank { it.skill.id } }
                        )
                    )
                )
            }
            val matched = skillStore.match(skillQuery, source)
            when {
                matched.isEmpty() -> ToolExecutionResult(
                    output = "未找到匹配的 Skill：$skillQuery。可先调用 `read_skill` 不带参数查看列表。",
                    structuredPayload = ToolStructuredPayload(
                        skill = SkillToolPayload(
                            kind = "not_found",
                            query = skillQuery,
                            source = source.wireName()
                        )
                    )
                )
                matched.size > 1 -> ToolExecutionResult(
                    output = buildAmbiguousSkillMessage(skillQuery, matched),
                    structuredPayload = ToolStructuredPayload(
                        skill = SkillToolPayload(
                            kind = "ambiguous",
                            query = skillQuery,
                            source = source.wireName(),
                            matchedSkillIds = matched.map { it.skill.id },
                            matchedSkillTitles = matched.map { it.skill.title.ifBlank { it.skill.id } }
                        )
                    )
                )
                else -> ToolExecutionResult(
                    output = buildSkillDetail(matched.first(), includeContent = includeContent),
                    structuredPayload = ToolStructuredPayload(
                        skill = buildSkillToolPayload(
                            kind = "detail",
                            query = skillQuery,
                            entry = matched.first(),
                            task = null,
                            background = false,
                            delegatedToSubagent = false
                        )
                    )
                )
            }
        }.getOrElse { error ->
            ToolExecutionResult(output = "Error: 读取 Skill 失败: ${error.message}")
        }
    }
}

internal class RunSkillTool(
    private val skillStore: SkillStore,
    private val subagentExecutor: (suspend (String) -> ToolExecutionResult)? = null
) : Tool {
    override val name: String = "run_skill"
    override val description: String =
        "执行当前可用的全局或项目 Skill。inline Skill 会把指令注入当前回合；subagent Skill 会按 Skill 配置直接派发子代理。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "skill" to mapOf("type" to "string", "description" to "要执行的 Skill 的 id、标题或关键词"),
            "task" to mapOf("type" to "string", "description" to "本次套用 Skill 的具体任务；对 subagent Skill 强烈建议提供"),
            "source" to mapOf(
                "type" to "string",
                "enum" to listOf("any", "project", "global"),
                "description" to "限定 Skill 来源，默认 any"
            ),
            "preferRunAs" to mapOf(
                "type" to "string",
                "enum" to listOf("skill-default", "inline", "subagent"),
                "description" to "可选。覆盖 Skill 默认执行方式，默认 skill-default"
            ),
            "background" to mapOf(
                "type" to "boolean",
                "description" to "仅在 subagent 执行时生效，是否后台运行，默认 false"
            )
        ),
        "required" to listOf("skill")
    )

    override suspend fun execute(args: String): String = executeWithResult(args).output

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        return runCatching {
            val obj = parseObject(args)
            val skillQuery = obj["skill"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(skillQuery.isNotBlank()) { "'skill' parameter required" }
            val source = SkillSource.fromRaw(obj["source"]?.jsonPrimitive?.contentOrNull)
            val task = obj["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val preferRunAs = obj["preferRunAs"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.lowercase()
            val background = obj["background"]?.jsonPrimitive?.booleanOrNull ?: false
            val entries = skillStore.list()
            require(entries.isNotEmpty()) { "当前没有启用中的 Skill" }
            val matched = skillStore.match(skillQuery, source)
            when {
                matched.isEmpty() -> ToolExecutionResult(
                    output = "未找到匹配的 Skill：$skillQuery。可先调用 `read_skill` 查看当前 Skill 列表。",
                    structuredPayload = ToolStructuredPayload(
                        skill = SkillToolPayload(
                            kind = "not_found",
                            query = skillQuery,
                            source = source.wireName()
                        )
                    )
                )
                matched.size > 1 -> ToolExecutionResult(
                    output = buildAmbiguousSkillMessage(skillQuery, matched),
                    structuredPayload = ToolStructuredPayload(
                        skill = SkillToolPayload(
                            kind = "ambiguous",
                            query = skillQuery,
                            source = source.wireName(),
                            matchedSkillIds = matched.map { it.skill.id },
                            matchedSkillTitles = matched.map { it.skill.title.ifBlank { it.skill.id } }
                        )
                    )
                )
                else -> {
                    val entry = matched.first()
                    val runAs = when (preferRunAs) {
                        "inline" -> SkillRunAs.INLINE
                        "subagent" -> SkillRunAs.SUBAGENT
                        else -> entry.skill.runAs
                    }
                    when (runAs) {
                        SkillRunAs.INLINE -> ToolExecutionResult(
                            output = buildInlineSkillExecutionResult(entry, task),
                            structuredPayload = ToolStructuredPayload(
                                skill = buildSkillToolPayload(
                                    kind = "execution",
                                    query = skillQuery,
                                    entry = entry,
                                    task = task.ifBlank { null },
                                    background = false,
                                    delegatedToSubagent = false,
                                    runAs = SkillRunAs.INLINE
                                )
                            )
                        )
                        SkillRunAs.SUBAGENT -> {
                            val concreteTask = task.ifBlank {
                                return ToolExecutionResult(
                                    output = buildString {
                                        append("Skill `")
                                        append(entry.skill.title.ifBlank { entry.skill.id })
                                        append("` 默认以 subagent 方式执行，但缺少 `task` 参数。")
                                        append(" 请补一个明确的子任务目标，例如“按这个 Skill 审查当前项目的配置合并逻辑”。")
                                    },
                                    structuredPayload = ToolStructuredPayload(
                                        skill = buildSkillToolPayload(
                                            kind = "missing_task",
                                            query = skillQuery,
                                            entry = entry,
                                            task = null,
                                            background = background,
                                            delegatedToSubagent = false,
                                            runAs = SkillRunAs.SUBAGENT
                                        )
                                    )
                                )
                            }
                            val executor = subagentExecutor ?: return ToolExecutionResult(
                                output = "当前未启用 subagent，无法执行 subagent 型 Skill：${entry.skill.title.ifBlank { entry.skill.id }}。",
                                structuredPayload = ToolStructuredPayload(
                                    skill = buildSkillToolPayload(
                                        kind = "subagent_unavailable",
                                        query = skillQuery,
                                        entry = entry,
                                        task = concreteTask,
                                        background = background,
                                        delegatedToSubagent = false,
                                        runAs = SkillRunAs.SUBAGENT
                                    )
                                )
                            )
                            val subagentArgs = buildJsonObject {
                                put("goal", concreteTask)
                                if (entry.skill.allowedTools.isNotEmpty()) {
                                    putJsonArray("allowedTools") {
                                        normalizeAllowedTools(entry.skill.allowedTools)
                                            .forEach { add(JsonPrimitive(it)) }
                                    }
                                }
                                entry.skill.preferredModel
                                    .trim()
                                    .takeIf { it.isNotBlank() }
                                    ?.let { put("model", it) }
                                if (background) {
                                    put("background", true)
                                }
                            }.toString()
                            val result = executor(subagentArgs)
                            result.copy(
                                output = buildString {
                                    appendLine("已按 Skill `${entry.skill.title.ifBlank { entry.skill.id }}` 派发子代理。")
                                    appendLine("Skill Source: ${entry.source.wireName()}")
                                    if (entry.skill.description.isNotBlank()) {
                                        appendLine("Skill Description: ${entry.skill.description.trim()}")
                                    }
                                    appendLine("Delegated Task: $concreteTask")
                                    appendLine()
                                    append(result.output.trim())
                                }.trim(),
                                structuredPayload = mergeToolStructuredPayload(
                                    base = result.structuredPayload,
                                    skill = buildSkillToolPayload(
                                        kind = "execution",
                                        query = skillQuery,
                                        entry = entry,
                                        task = concreteTask,
                                        background = background,
                                        delegatedToSubagent = true,
                                        runAs = SkillRunAs.SUBAGENT
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }.getOrElse { error ->
            ToolExecutionResult(output = "Error: 执行 Skill 失败: ${error.message}")
        }
    }
}

internal class CreateMcpServerTool(
    private val configsProvider: () -> List<McpServerConfig>,
    private val saveConfigs: (List<McpServerConfig>) -> Unit,
    private val connectAll: (suspend (List<McpServerConfig>) -> Unit)? = null
) : Tool {
    override val name: String = "create_mcp_server"
    override val description: String =
        "手动导入一条 MCP 服务器配置。仅当用户明确要求“导入 MCP/添加 MCP 服务器”时才调用，不要自动识别普通对话。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string", "description" to "服务器名称"),
            "transport" to mapOf("type" to "string", "enum" to listOf("stdio", "sse", "streamable-http"), "description" to "传输类型"),
            "command" to mapOf("type" to "string", "description" to "stdio 模式下的命令"),
            "args" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "stdio 模式下的参数"),
            "cwd" to mapOf("type" to "string", "description" to "工作目录，可选"),
            "env" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string"), "description" to "环境变量映射"),
            "url" to mapOf("type" to "string", "description" to "远端模式的 URL"),
            "headers" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string"), "description" to "请求头映射"),
            "requestTimeoutMs" to mapOf("type" to "number", "description" to "超时毫秒数，可选"),
            "enabled" to mapOf("type" to "boolean", "description" to "是否启用，默认 true"),
            "connectAfterImport" to mapOf("type" to "boolean", "description" to "导入后是否立即尝试连接，默认 false")
        ),
        "required" to listOf("name", "transport")
    )

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val obj = parseObject(args)
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isBlank()) return null
        return ToolApprovalRequest(
            toolName = this.name,
            summary = "导入 MCP 服务器",
            detail = name,
            riskLevel = ApprovalRiskLevel.HIGH,
            rawArgs = args
        )
    }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = parseObject(args)
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(name.isNotBlank()) { "'name' parameter required" }
            val transport = when (obj["transport"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()) {
                "stdio", null, "" -> McpTransportType.STDIO
                "sse" -> McpTransportType.SSE
                "streamable-http", "streamable_http", "http" -> McpTransportType.STREAMABLE_HTTP
                else -> error("unknown transport")
            }
            val command = obj["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val argsList = obj.stringList("args")
            val cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val env = obj.stringMap("env")
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val headers = obj.stringMap("headers")
            val requestTimeoutMs = obj["requestTimeoutMs"]?.jsonPrimitive?.contentOrNull?.trim()?.toLongOrNull()
            val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            val connectAfterImport = obj["connectAfterImport"]?.jsonPrimitive?.booleanOrNull ?: false
            val trustedReadOnlyTools = obj.stringList("trustedReadOnlyTools")
            val autoStart = obj["autoStart"]?.jsonPrimitive?.booleanOrNull ?: true
            when (transport) {
                McpTransportType.STDIO -> require(command.isNotBlank()) { "'command' parameter required for stdio transport" }
                McpTransportType.SSE,
                McpTransportType.STREAMABLE_HTTP -> require(url.isNotBlank()) { "'url' parameter required for remote transport" }
            }
            val imported = McpServerConfig(
                name = name,
                transport = transport,
                command = command,
                args = argsList,
                cwd = cwd,
                env = env,
                url = url,
                headers = headers,
                requestTimeoutMs = requestTimeoutMs,
                source = McpConfigSource.MANUAL,
                trustedReadOnlyTools = trustedReadOnlyTools,
                autoStart = autoStart,
                enabled = enabled
            )
            val updated = configsProvider()
                .filterNot { it.name.equals(name, ignoreCase = true) } + imported
            saveConfigs(updated)
            if (connectAfterImport && enabled) {
                connectAll?.invoke(updated)
            }
            buildString {
                append("已导入 MCP 服务器：")
                append(name)
                append("（")
                append(transport.name.lowercase())
                append("，")
                append(if (enabled) "已启用" else "已停用")
                append("）")
                if (connectAfterImport && enabled && connectAll != null) {
                    append("，已尝试连接。")
                } else {
                    append("。")
                }
                append(" 当前共 ")
                append(updated.size)
                append(" 条配置。")
            }
        }.getOrElse { error ->
            "Error: 导入 MCP 服务器失败: ${error.message}"
        }
    }
}

private val toolJson = Json { ignoreUnknownKeys = true }

private fun parseObject(args: String): JsonObject {
    return toolJson.parseToJsonElement(args).jsonObject
}

private fun JsonObject.stringList(key: String): List<String> {
    val element = get(key) ?: return emptyList()
    return runCatching {
        element.jsonArray.mapNotNull { item ->
            item.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}

private fun JsonObject.stringMap(key: String): Map<String, String> {
    return runCatching {
        get(key)?.jsonObject?.mapNotNull { (mapKey, value) ->
            value.jsonPrimitive.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { mapKey to it }
        }?.toMap()
    }.getOrNull().orEmpty()
}

private fun normalizeAllowedTools(rawTokens: List<String>): List<String> {
    if (rawTokens.isEmpty()) return emptyList()
    return rawTokens
        .flatMap { token ->
            token.split(',', ';', '\n')
        }
        .mapNotNull { raw ->
            val token = raw.trim().removePrefix("-").removePrefix("*").trim().lowercase()
            when {
                token.isBlank() -> null
                token in setOf("edit", "patch", "code-edit", "codeedit") -> "code_edit"
                token in setOf("bash", "sh", "zsh", "pwsh", "powershell", "terminal", "command", "cmd") -> "shell"
                token in setOf("web", "browser", "browse", "search", "fetch", "web-search", "websearch") -> "web_search"
                token in setOf("web-fetch", "webfetch") -> "web_fetch"
                else -> token
            }
        }
        .distinct()
}

private fun buildSkillCatalogSummary(entries: List<SkillCatalogEntry>, source: SkillSource): String {
    val scoped = when (source) {
        SkillSource.ANY -> entries
        else -> entries.filter { it.source == source }
    }
    if (scoped.isEmpty()) {
        return when (source) {
            SkillSource.PROJECT -> "当前没有启用中的项目 Skill。"
            SkillSource.GLOBAL -> "当前没有启用中的全局 Skill。"
            else -> "当前没有启用中的 Skill。"
        }
    }
    return buildString {
        appendLine("当前可用 Skills（共 ${scoped.size} 条）：")
        scoped.forEach { entry ->
            append("- [")
            append(entry.source.wireName())
            append("] ")
            append(entry.skill.title.ifBlank { "未命名 Skill" })
            append(" (id=")
            append(entry.skill.id)
            append(", runAs=")
            append(entry.skill.runAs.name.lowercase())
            append(")")
            entry.skill.description.trim().takeIf { it.isNotBlank() }?.let {
                append(" - ")
                append(it)
            }
            if (entry.skill.allowedTools.isNotEmpty()) {
                append(" | tools=")
                append(entry.skill.allowedTools.joinToString(", "))
            }
            appendLine()
        }
        appendLine()
        append("可继续用 `read_skill` 指定某条 Skill 查看完整内容，或用 `run_skill` 执行。")
    }.trim()
}

private fun buildAmbiguousSkillMessage(
    query: String,
    entries: List<SkillCatalogEntry>
): String {
    return buildString {
        appendLine("匹配到多条 Skill，请改用更精确的 id 或 title：")
        entries.forEach { entry ->
            append("- [")
            append(entry.source.wireName())
            append("] ")
            append(entry.skill.title.ifBlank { "未命名 Skill" })
            append(" (id=")
            append(entry.skill.id)
            append(", runAs=")
            append(entry.skill.runAs.name.lowercase())
            append(")")
            appendLine()
        }
        appendLine()
        append("原始查询：")
        append(query)
    }.trim()
}

private fun buildSkillDetail(
    entry: SkillCatalogEntry,
    includeContent: Boolean
): String {
    return buildString {
        appendLine("Skill: ${entry.skill.title.ifBlank { "未命名 Skill" }}")
        appendLine("ID: ${entry.skill.id}")
        appendLine("Source: ${entry.source.wireName()}")
        appendLine("Run As: ${entry.skill.runAs.name.lowercase()}")
        if (entry.skill.allowedTools.isNotEmpty()) {
            appendLine("Allowed Tools: ${entry.skill.allowedTools.joinToString(", ")}")
        }
        entry.skill.preferredModel.trim().takeIf { it.isNotBlank() }?.let {
            appendLine("Preferred Model: $it")
        }
        entry.skill.description.trim().takeIf { it.isNotBlank() }?.let {
            appendLine("Description: $it")
        }
        if (includeContent) {
            appendLine()
            appendLine("Instruction:")
            append(entry.skill.content.trim())
        }
    }.trim()
}

private fun buildInlineSkillExecutionResult(
    entry: SkillCatalogEntry,
    task: String
): String {
    return buildString {
        appendLine("已激活 Skill：${entry.skill.title.ifBlank { entry.skill.id }}")
        appendLine("Skill Source: ${entry.source.wireName()}")
        appendLine("Execution Mode: inline")
        if (task.isNotBlank()) {
            appendLine("Concrete Task: $task")
        }
        if (entry.skill.allowedTools.isNotEmpty()) {
            appendLine("Allowed Tools: ${entry.skill.allowedTools.joinToString(", ")}")
        }
        entry.skill.preferredModel.trim().takeIf { it.isNotBlank() }?.let {
            appendLine("Preferred Model: $it")
        }
        if (entry.skill.description.isNotBlank()) {
            appendLine("Description: ${entry.skill.description.trim()}")
        }
        appendLine()
        appendLine("请在当前回合继续处理用户请求时，显式遵守以下 Skill 指令：")
        appendLine(entry.skill.content.trim())
        if (task.isNotBlank()) {
            appendLine()
            append("并把这条具体任务视为本次 Skill 的执行目标：")
            append(task)
        }
    }.trim()
}

private fun buildSkillToolPayload(
    kind: String,
    query: String?,
    entry: SkillCatalogEntry,
    task: String?,
    background: Boolean,
    delegatedToSubagent: Boolean,
    runAs: SkillRunAs = entry.skill.runAs
): SkillToolPayload {
    return SkillToolPayload(
        kind = kind,
        query = query,
        source = entry.source.wireName(),
        skillId = entry.skill.id,
        skillTitle = entry.skill.title.ifBlank { entry.skill.id },
        matchedSkillIds = listOf(entry.skill.id),
        matchedSkillTitles = listOf(entry.skill.title.ifBlank { entry.skill.id }),
        runAs = runAs.name.lowercase(),
        task = task,
        allowedTools = normalizeAllowedTools(entry.skill.allowedTools),
        background = background,
        delegatedToSubagent = delegatedToSubagent
    )
}

private fun mergeToolStructuredPayload(
    base: ToolStructuredPayload?,
    skill: SkillToolPayload
): ToolStructuredPayload {
    return ToolStructuredPayload(
        sessionHistory = base?.sessionHistory,
        skill = skill
    )
}
