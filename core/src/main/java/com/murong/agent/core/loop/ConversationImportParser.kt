package com.murong.agent.core.loop

import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ImportedConversation(
    val messages: List<ChatMessageUi>,
    val titleHint: String? = null
)

object ConversationImportParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawText: String, sourceName: String? = null): ImportedConversation {
        val trimmed = rawText.trim()
        require(trimmed.isNotBlank()) { "导入内容为空" }

        val messages = when {
            trimmed.startsWith("[") || trimmed.startsWith("{") -> {
                parseJsonMessages(trimmed)
            }

            else -> parseTaggedTextMessages(trimmed)
        }

        require(messages.isNotEmpty()) { "未识别到可导入的对话消息" }

        val normalizedMessages = messages.mapIndexed { index, message ->
            message.copy(
                id = (index + 1).toLong(),
                timestamp = System.currentTimeMillis() + index
            )
        }

        return ImportedConversation(
            messages = normalizedMessages,
            titleHint = sourceName?.substringBeforeLast(".")?.ifBlank { null }
        )
    }

    private fun parseJsonMessages(rawText: String): List<ChatMessageUi> {
        return try {
            val root = json.parseToJsonElement(rawText)
            val messageArray = when (root) {
                is JsonArray -> root
                is JsonObject -> root["messages"]?.jsonArray
                else -> null
            } ?: emptyList()

            messageArray.mapIndexedNotNull { index, element ->
                parseJsonMessage(element, index)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseJsonMessage(
        element: JsonElement,
        index: Int
    ): ChatMessageUi? {
        val obj = element as? JsonObject ?: return null
        val role = obj["role"]?.jsonPrimitive?.contentOrNull
            ?: obj["author"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val content = extractContent(obj) ?: return null

        return ChatMessageUi(
            id = (index + 1).toLong(),
            role = normalizeRole(role),
            content = content.trim()
        )
    }

    private fun extractContent(obj: JsonObject): String? {
        val direct = obj["content"]?.jsonPrimitive?.contentOrNull
        if (!direct.isNullOrBlank()) return direct

        val text = obj["text"]?.jsonPrimitive?.contentOrNull
        if (!text.isNullOrBlank()) return text

        val parts = obj["parts"] as? JsonArray
        if (parts != null) {
            return parts.mapNotNull { it.jsonPrimitive.contentOrNull }
                .joinToString("\n")
                .ifBlank { null }
        }

        return null
    }

    private fun parseTaggedTextMessages(rawText: String): List<ChatMessageUi> {
        val blockMatches = BLOCK_HEADER.findAll(rawText).toList()
        if (blockMatches.isNotEmpty()) {
            return parseBlockStyle(rawText, blockMatches)
        }
        return parsePrefixStyle(rawText)
    }

    private fun parseBlockStyle(
        rawText: String,
        headers: List<MatchResult>
    ): List<ChatMessageUi> {
        val messages = mutableListOf<ChatMessageUi>()

        headers.forEachIndexed { index, match ->
            val start = match.range.last + 1
            val end = headers.getOrNull(index + 1)?.range?.first ?: rawText.length
            val content = rawText.substring(start, end).trim()
            if (content.isNotBlank()) {
                messages += ChatMessageUi(
                    id = messages.size.toLong() + 1,
                    role = normalizeRole(match.groupValues[1]),
                    content = content
                )
            }
        }

        return messages
    }

    private fun parsePrefixStyle(rawText: String): List<ChatMessageUi> {
        val messages = mutableListOf<ChatMessageUi>()
        var currentRole: String? = null
        val buffer = StringBuilder()

        fun flush() {
            val role = currentRole ?: return
            val content = buffer.toString().trim()
            if (content.isNotBlank()) {
                messages += ChatMessageUi(
                    id = messages.size.toLong() + 1,
                    role = normalizeRole(role),
                    content = content
                )
            }
            buffer.clear()
        }

        rawText.lineSequence().forEach { line ->
            val match = PREFIX_HEADER.matchEntire(line.trim())
            if (match != null) {
                flush()
                currentRole = match.groupValues[1]
                val firstLine = match.groupValues[2].trim()
                if (firstLine.isNotBlank()) {
                    buffer.append(firstLine)
                }
            } else if (currentRole != null) {
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(line)
            }
        }
        flush()

        return messages
    }

    private fun normalizeRole(rawRole: String): String {
        return when (rawRole.trim().lowercase()) {
            "user", "human", "用户" -> "user"
            "assistant", "ai", "助手" -> "assistant"
            "system", "系统" -> "system"
            "tool", "tool_exec", "工具" -> "tool_exec"
            else -> "assistant"
        }
    }

    private val BLOCK_HEADER = Regex(
        pattern = "^\\[(用户|助手|系统|工具|user|assistant|system|tool|tool_exec)]\\s*$",
        option = RegexOption.MULTILINE
    )

    private val PREFIX_HEADER = Regex(
        pattern = "^(用户|助手|系统|工具|user|assistant|system|tool|tool_exec)\\s*[:：]\\s*(.*)$",
        option = RegexOption.IGNORE_CASE
    )
}
