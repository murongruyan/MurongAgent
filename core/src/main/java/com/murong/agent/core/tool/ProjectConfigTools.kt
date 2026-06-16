package com.murong.agent.core.tool

import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.GlobalRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

internal class CreateProjectRuleTool(
    private val scopePathProvider: () -> String?,
    private val scopeLabelProvider: () -> String,
    private val rulesProvider: () -> List<GlobalRule>,
    private val updateRules: (List<GlobalRule>) -> Unit
) : Tool {
    override val name: String = "create_project_rule"
    override val description: String =
        "在当前项目作用域里手动创建一条项目规则。仅当用户明确要求“保存为项目规则/添加项目规则”时才调用，不要自动识别或自动提取普通对话内容。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf(
                "type" to "string",
                "description" to "规则标题，例如 始终先跑诊断"
            ),
            "content" to mapOf(
                "type" to "string",
                "description" to "规则正文，会在当前项目作用域内作为可复用规则保存"
            ),
            "enabled" to mapOf(
                "type" to "boolean",
                "description" to "是否立即启用，默认 true"
            )
        ),
        "required" to listOf("title", "content")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        if (scopePathProvider() == null) return null
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isBlank()) return null
            ToolApprovalRequest(
                toolName = name,
                summary = "创建项目规则",
                detail = "${scopeLabelProvider()}: $title",
                riskLevel = ApprovalRiskLevel.MEDIUM,
                rawArgs = args
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String {
        if (scopePathProvider() == null) {
            return "Error: 当前没有激活的本地项目作用域，无法创建项目规则。请先选择一个项目。"
        }
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            if (title.isBlank()) return "Error: 'title' parameter required"
            if (content.isBlank()) return "Error: 'content' parameter required"
            val updated = rulesProvider() + GlobalRule(
                id = UUID.randomUUID().toString().take(8),
                title = title,
                content = content,
                enabled = enabled
            )
            updateRules(updated)
            buildString {
                appendLine("已创建项目规则。")
                appendLine("作用域: ${scopeLabelProvider()}")
                appendLine("标题: $title")
                appendLine("状态: ${if (enabled) "已启用" else "已停用"}")
                append("当前项目规则数: ${updated.size}")
            }
        } catch (e: Exception) {
            "Error: 创建项目规则失败: ${e.message}"
        }
    }
}

internal class CreateProjectMemoryTool(
    private val scopePathProvider: () -> String?,
    private val scopeLabelProvider: () -> String,
    private val memoriesProvider: () -> List<GlobalMemory>,
    private val updateMemories: (List<GlobalMemory>) -> Unit
) : Tool {
    override val name: String = "create_project_memory"
    override val description: String =
        "在当前项目作用域里手动创建一条项目记忆。仅当用户明确要求“保存为项目记忆/添加项目记忆”时才调用，不要自动识别或自动提取普通对话内容。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf(
                "type" to "string",
                "description" to "记忆标题，例如 当前仓库发布分支约定"
            ),
            "content" to mapOf(
                "type" to "string",
                "description" to "记忆正文，会在当前项目作用域内作为可复用记忆保存"
            ),
            "enabled" to mapOf(
                "type" to "boolean",
                "description" to "是否立即启用，默认 true"
            )
        ),
        "required" to listOf("title", "content")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        if (scopePathProvider() == null) return null
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isBlank()) return null
            ToolApprovalRequest(
                toolName = name,
                summary = "创建项目记忆",
                detail = "${scopeLabelProvider()}: $title",
                riskLevel = ApprovalRiskLevel.MEDIUM,
                rawArgs = args
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String {
        if (scopePathProvider() == null) {
            return "Error: 当前没有激活的本地项目作用域，无法创建项目记忆。请先选择一个项目。"
        }
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            if (title.isBlank()) return "Error: 'title' parameter required"
            if (content.isBlank()) return "Error: 'content' parameter required"
            val updated = memoriesProvider() + GlobalMemory(
                id = UUID.randomUUID().toString().take(8),
                title = title,
                content = content,
                enabled = enabled
            )
            updateMemories(updated)
            buildString {
                appendLine("已创建项目记忆。")
                appendLine("作用域: ${scopeLabelProvider()}")
                appendLine("标题: $title")
                appendLine("状态: ${if (enabled) "已启用" else "已停用"}")
                append("当前项目记忆数: ${updated.size}")
            }
        } catch (e: Exception) {
            "Error: 创建项目记忆失败: ${e.message}"
        }
    }
}
