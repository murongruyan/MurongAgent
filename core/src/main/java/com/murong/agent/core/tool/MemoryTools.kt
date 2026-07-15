package com.murong.agent.core.tool

import com.murong.agent.core.memory.MemoryDeleteResult
import com.murong.agent.core.memory.MemoryDraft
import com.murong.agent.core.memory.MemoryOrigin
import com.murong.agent.core.memory.MemoryScope
import com.murong.agent.core.memory.MemoryStore
import com.murong.agent.core.memory.MutableMemoryStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class MemoryListTool(
    private val memoryStore: MemoryStore
) : Tool {
    override val name: String = "memory_list"
    override val description: String =
        "列出当前可用的记忆条目，可按 global/project 过滤。结果会同时包含 durable memory 和尚未迁移的 legacy bridge 条目。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "scope" to mapOf(
                "type" to "string",
                "enum" to listOf("any", "global", "project"),
                "description" to "可选。记忆范围，默认 any。"
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "可选。返回条目上限，默认 10，最大 30。"
            )
        )
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = json.parseObject(args)
            val scope = MemoryScope.fromRaw(obj["scope"]?.jsonPrimitive?.contentOrNull)
            val limit = obj["limit"]?.jsonPrimitive?.intOrNull ?: 10
            val memories = memoryStore.list(scope = scope, limit = limit)
            if (memories.isEmpty()) {
                return@runCatching "当前没有可用记忆。"
            }
            buildString {
                appendLine("可用记忆 ${memories.size} 条：")
                memories.forEach { memory ->
                    appendLine(
                        "- [${memory.scope.wireName()}] ${memory.title} " +
                            "(id=${memory.id}, source=${memory.origin.name.lowercase()})"
                    )
                }
                val legacyCount = memories.count { it.origin == MemoryOrigin.LEGACY_CONFIG }
                if (legacyCount > 0) {
                    appendLine("检测到 $legacyCount 条 legacy config 记忆，可用 `migrate_legacy_memories` 批量迁移。")
                }
                append("可继续用 `memory_read` 读取全文，或用 `memory_search` 按关键词检索。")
            }.trim()
        }.getOrElse { error ->
            "Error: 列出记忆失败: ${error.message}"
        }
    }
}

internal class MemorySearchTool(
    private val memoryStore: MemoryStore
) : Tool {
    override val name: String = "memory_search"
    override val description: String =
        "按关键词检索记忆仓库，返回最相关的全局/项目事实记忆摘要。结果可能包含尚未迁移的 legacy bridge 条目。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf(
                "type" to "string",
                "description" to "必填。检索关键词，会匹配记忆标题和正文。"
            ),
            "scope" to mapOf(
                "type" to "string",
                "enum" to listOf("any", "global", "project"),
                "description" to "可选。记忆范围，默认 any。"
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "可选。返回条目上限，默认 5，最大 20。"
            )
        ),
        "required" to listOf("query")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = json.parseObject(args)
            val query = obj["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(query.isNotBlank()) { "'query' parameter required" }
            val scope = MemoryScope.fromRaw(obj["scope"]?.jsonPrimitive?.contentOrNull)
            val limit = obj["limit"]?.jsonPrimitive?.intOrNull ?: 5
            val hits = memoryStore.search(query = query, scope = scope, limit = limit)
            if (hits.isEmpty()) {
                return@runCatching "没有找到与“$query”相关的记忆。"
            }
            buildString {
                appendLine("记忆命中 ${hits.size} 条：")
                hits.forEach { hit ->
                    appendLine(
                        "- [${hit.memory.scope.wireName()}] ${hit.memory.title} " +
                            "(id=${hit.memory.id}, score=${hit.score}, source=${hit.memory.origin.name.lowercase()})"
                    )
                    appendLine("  摘要: ${hit.snippet}")
                }
                if (hits.any { it.memory.origin == MemoryOrigin.LEGACY_CONFIG }) {
                    appendLine("命中中包含 legacy config 记忆，可用 `migrate_legacy_memories` 批量迁移。")
                }
                append("可继续用 `memory_read` 读取某条记忆全文。")
            }.trim()
        }.getOrElse { error ->
            "Error: 检索记忆失败: ${error.message}"
        }
    }
}

internal class MemoryReadTool(
    private val memoryStore: MemoryStore
) : Tool {
    override val name: String = "memory_read"
    override val description: String =
        "读取一条记忆的完整正文。通常先通过 memory_list 或 memory_search 获得 memory_id，再调用这个工具。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "memory_id" to mapOf(
                "type" to "string",
                "description" to "必填。记忆条目 id。"
            ),
            "scope" to mapOf(
                "type" to "string",
                "enum" to listOf("any", "global", "project"),
                "description" to "可选。记忆范围，默认 any。"
            )
        ),
        "required" to listOf("memory_id")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = json.parseObject(args)
            val memoryId = obj["memory_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(memoryId.isNotBlank()) { "'memory_id' parameter required" }
            val scope = MemoryScope.fromRaw(obj["scope"]?.jsonPrimitive?.contentOrNull)
            val memory = memoryStore.read(memoryId = memoryId, scope = scope)
                ?: return@runCatching "没有找到 id 为 $memoryId 的记忆。"
            buildString {
                appendLine("标题: ${memory.title}")
                appendLine("范围: ${memory.scope.wireName()}")
                appendLine("来源: ${memory.origin.name.lowercase()}")
                memory.scopePath?.takeIf { it.isNotBlank() }?.let { path ->
                    appendLine("作用域路径: $path")
                }
                appendLine("ID: ${memory.id}")
                if (memory.origin == MemoryOrigin.LEGACY_CONFIG) {
                    appendLine("迁移建议: 这是一条 legacy config 记忆，可用 `migrate_legacy_memories` 批量迁移，或用 `remember_memory` 搭配 source_memory_id 单条迁移。")
                }
                appendLine()
                append(memory.content)
            }.trim()
        }.getOrElse { error ->
            "Error: 读取记忆失败: ${error.message}"
        }
    }
}

internal class ForgetMemoryTool(
    private val memoryStore: MutableMemoryStore
) : Tool {
    override val name: String = "forget_memory"
    override val description: String =
        "删除一条 durable memory。通常先通过 memory_list 或 memory_search 获得 memory_id，再按 global/project 范围忘记该条事实记忆。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "memory_id" to mapOf(
                "type" to "string",
                "description" to "必填。要删除的记忆条目 id。"
            ),
            "scope" to mapOf(
                "type" to "string",
                "enum" to listOf("any", "global", "project"),
                "description" to "可选。记忆范围，默认 any。"
            )
        ),
        "required" to listOf("memory_id")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        return runCatching {
            val obj = json.parseObject(args)
            val memoryId = obj["memory_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (memoryId.isBlank()) return null
            ToolApprovalRequest(
                toolName = name,
                summary = "删除记忆",
                detail = memoryId,
                riskLevel = ApprovalRiskLevel.MEDIUM,
                rawArgs = args
            )
        }.getOrNull()
    }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = json.parseObject(args)
            val memoryId = obj["memory_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(memoryId.isNotBlank()) { "'memory_id' parameter required" }
            val scope = MemoryScope.fromRaw(obj["scope"]?.jsonPrimitive?.contentOrNull)
            when (val result = memoryStore.delete(memoryId = memoryId, scope = scope)) {
                is MemoryDeleteResult.Deleted -> buildString {
                    appendLine("已删除记忆。")
                    appendLine("标题: ${result.deletedMemory.title}")
                    appendLine("范围: ${result.deletedMemory.scope.wireName()}")
                    appendLine("memory_id: ${result.deletedMemory.id}")
                    append("当前剩余记忆数: ${result.remainingCount}")
                }.trim()

                is MemoryDeleteResult.LegacyConfigMemory ->
                    "无法直接删除 legacy config 记忆：${result.memory.title}。请先用 `migrate_legacy_memories` 批量迁移，或用 `remember_memory` 搭配 source_memory_id 单条迁移，再删除 durable 副本。"

                MemoryDeleteResult.NotFound -> "没有找到 id 为 $memoryId 的记忆。"
            }
        }.getOrElse { error ->
            "Error: 删除记忆失败: ${error.message}"
        }
    }
}

internal class MigrateLegacyMemoriesTool(
    private val memoryStore: MutableMemoryStore,
    private val currentProjectScopePathProvider: () -> String?
) : Tool {
    override val name: String = "migrate_legacy_memories"
    override val description: String =
        "把当前可见的 legacy config 记忆批量迁移到 durable memory store。适合收口旧的 global/project memory 配置写路径。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "scope" to mapOf(
                "type" to "string",
                "enum" to listOf("any", "global", "project"),
                "description" to "可选。迁移范围，默认 any；若为 project 需要当前存在激活项目。"
            )
        )
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val scope = runCatching {
            json.parseObject(args)["scope"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        }.getOrDefault("")
        return ToolApprovalRequest(
            toolName = name,
            summary = "迁移 legacy 记忆",
            detail = scope.ifBlank { "any" },
            riskLevel = ApprovalRiskLevel.MEDIUM,
            rawArgs = args
        )
    }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = json.parseObject(args)
            val scope = MemoryScope.fromRaw(obj["scope"]?.jsonPrimitive?.contentOrNull)
            require(scope != MemoryScope.PROJECT || currentProjectScopePathProvider()?.isNotBlank() == true) {
                "当前没有激活的本地项目作用域，无法迁移 project legacy memory"
            }
            val migrated = memoryStore.migrateLegacy(scope = scope)
            if (migrated.migratedMemories.isEmpty()) {
                return@runCatching "当前范围内没有需要迁移的 legacy config 记忆。"
            }
            buildString {
                appendLine("已完成 legacy config 记忆迁移。")
                appendLine("迁移条数: ${migrated.migratedMemories.size}")
                if (migrated.skippedDuplicateCount > 0) {
                    appendLine("跳过重复条数: ${migrated.skippedDuplicateCount}")
                }
                migrated.migratedMemories.take(5).forEach { memory ->
                    appendLine("- [${memory.scope.wireName()}] ${memory.title} (memory_id=${memory.id})")
                }
                if (migrated.migratedMemories.size > 5) {
                    appendLine("其余 ${migrated.migratedMemories.size - 5} 条已一并写入 durable store。")
                }
                append("后续可用 `forget_memory` 删除 durable 副本；legacy bridge 会因 durable-over-legacy dedupe 自动淡出。")
            }.trim()
        }.getOrElse { error ->
            "Error: 迁移 legacy 记忆失败: ${error.message}"
        }
    }
}

internal class RememberMemoryTool(
    private val memoryStore: MutableMemoryStore,
    private val currentProjectScopePathProvider: () -> String?,
    private val suggestionProvider: (String) -> RememberMemorySuggestion? = { null },
    private val onSuggestionApplied: (String, String) -> Unit = { _, _ -> }
) : Tool {
    override val name: String = "remember_memory"
    override val description: String =
        "把一条事实保存到 durable memory，或把已有 legacy memory 升级到 durable store。可用 scope 指定 global/project；未指定时优先写入当前项目。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf(
                "type" to "string",
                "description" to "可选。要记住的事实标题。与 content 一起使用。"
            ),
            "content" to mapOf(
                "type" to "string",
                "description" to "可选。要记住的事实正文。与 title 一起使用。"
            ),
            "source_memory_id" to mapOf(
                "type" to "string",
                "description" to "可选。已有 memory id；传入后会把该条记忆升级/复制到 durable store。"
            ),
            "suggestion_id" to mapOf(
                "type" to "string",
                "description" to "可选。最近 memory update suggestion 的 id；传入后会按建议内容写入 durable store。"
            ),
            "scope" to mapOf(
                "type" to "string",
                "enum" to listOf("global", "project"),
                "description" to "可选。记忆保存范围；默认优先 project，其次 global。"
            ),
            "enabled" to mapOf(
                "type" to "boolean",
                "description" to "是否启用，默认 true。"
            )
        )
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        return runCatching {
            val obj = json.parseObject(args)
            val sourceMemoryId = obj["source_memory_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val suggestionId = obj["suggestion_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val detail = sourceMemoryId.ifBlank { suggestionId.ifBlank { title } }
            if (detail.isBlank()) return null
            ToolApprovalRequest(
                toolName = name,
                summary = "记住事实",
                detail = detail,
                riskLevel = ApprovalRiskLevel.MEDIUM,
                rawArgs = args
            )
        }.getOrNull()
    }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = json.parseObject(args)
            val sourceMemoryId = obj["source_memory_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val suggestionId = obj["suggestion_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val explicitScope = obj["scope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            val enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true

            if (suggestionId.isNotBlank()) {
                val suggestion = suggestionProvider(suggestionId)
                    ?: return@runCatching "没有找到 id 为 $suggestionId 的记忆建议。"
                val targetScope = resolveRememberScope(explicitScope, suggestion.scope)
                val saved = rememberMemory(
                    title = suggestion.title,
                    content = suggestion.content,
                    enabled = enabled,
                    scope = targetScope
                )
                onSuggestionApplied(suggestionId, saved.savedMemory.id)
                return@runCatching buildString {
                    appendLine("已按建议写入 durable memory。")
                    appendLine("suggestion_id: $suggestionId")
                    appendLine("标题: ${saved.savedMemory.title}")
                    appendLine("范围: ${saved.savedMemory.scope.wireName()}")
                    append("memory_id: ${saved.savedMemory.id}")
                }.trim()
            }

            if (sourceMemoryId.isNotBlank()) {
                val source = memoryStore.read(memoryId = sourceMemoryId, scope = MemoryScope.ANY)
                    ?: return@runCatching "没有找到 id 为 $sourceMemoryId 的记忆。"
                if (source.origin == MemoryOrigin.DURABLE &&
                    (explicitScope == null || MemoryScope.fromRaw(explicitScope) == source.scope)
                ) {
                    return@runCatching "该记忆已经在 durable store 中，无需再次迁移。memory_id=${source.id}"
                }
                val targetScope = resolveRememberScope(explicitScope, source.scope)
                val saved = rememberMemory(
                    title = source.title,
                    content = source.content,
                    enabled = enabled,
                    scope = targetScope
                )
                return@runCatching buildString {
                    appendLine("已将记忆写入 durable store。")
                    appendLine("来源 memory_id: $sourceMemoryId")
                    appendLine("标题: ${saved.savedMemory.title}")
                    appendLine("范围: ${saved.savedMemory.scope.wireName()}")
                    append("memory_id: ${saved.savedMemory.id}")
                }.trim()
            }

            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(title.isNotBlank()) { "'title' parameter required" }
            require(content.isNotBlank()) { "'content' parameter required" }
            val targetScope = resolveRememberScope(explicitScope, null)
            val saved = rememberMemory(
                title = title,
                content = content,
                enabled = enabled,
                scope = targetScope
            )
            buildString {
                appendLine("已记住一条事实。")
                appendLine("标题: ${saved.savedMemory.title}")
                appendLine("范围: ${saved.savedMemory.scope.wireName()}")
                append("memory_id: ${saved.savedMemory.id}")
            }.trim()
        }.getOrElse { error ->
            "Error: 保存记忆失败: ${error.message}"
        }
    }

    private suspend fun rememberMemory(
        title: String,
        content: String,
        enabled: Boolean,
        scope: MemoryScope
    ) = when (scope) {
        MemoryScope.PROJECT -> memoryStore.createProjectMemory(
            MemoryDraft(
                title = title,
                content = content,
                enabled = enabled
            )
        )

        else -> memoryStore.createGlobalMemory(
            MemoryDraft(
                title = title,
                content = content,
                enabled = enabled
            )
        )
    }

    private fun resolveRememberScope(explicitScope: String?, sourceScope: MemoryScope?): MemoryScope {
        if (!explicitScope.isNullOrBlank()) {
            return when (MemoryScope.fromRaw(explicitScope)) {
                MemoryScope.PROJECT -> {
                    require(currentProjectScopePathProvider()?.isNotBlank() == true) {
                        "当前没有激活的本地项目作用域，无法保存 project memory"
                    }
                    MemoryScope.PROJECT
                }

                else -> MemoryScope.GLOBAL
            }
        }
        if (sourceScope == MemoryScope.PROJECT && currentProjectScopePathProvider()?.isNotBlank() == true) {
            return MemoryScope.PROJECT
        }
        return if (currentProjectScopePathProvider()?.isNotBlank() == true) {
            MemoryScope.PROJECT
        } else {
            MemoryScope.GLOBAL
        }
    }
}

internal data class RememberMemorySuggestion(
    val id: String,
    val title: String,
    val content: String,
    val scope: MemoryScope
)

private fun Json.parseObject(args: String): JsonObject {
    return parseToJsonElement(args).jsonObject
}
