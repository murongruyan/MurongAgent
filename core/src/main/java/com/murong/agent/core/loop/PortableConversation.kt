package com.murong.agent.core.loop

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val PORTABLE_CONVERSATION_FORMAT = "murong-portable-session"
internal const val PORTABLE_CONVERSATION_VERSION = 1
private const val PORTABLE_CONVERSATION_MAX_BYTES = 32 * 1024 * 1024
private const val PORTABLE_CONVERSATION_MAX_MESSAGES = 50_000
private const val PORTABLE_CONVERSATION_MAX_MESSAGE_BYTES = 4 * 1024 * 1024
private const val PORTABLE_CONVERSATION_MAX_SUMMARY_BYTES = 1024 * 1024

@Serializable
internal data class PortableConversationEnvelope(
    val format: String,
    val formatVersion: Int,
    val exportedAtEpochMillis: Long,
    val sourcePlatform: String,
    val session: PortableConversationSession
)

@Serializable
internal data class PortableConversationSession(
    val title: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val providerId: String? = null,
    val modelName: String? = null,
    val goal: String? = null,
    val messages: List<PortableConversationMessage>,
    val usage: PortableConversationUsage = PortableConversationUsage(),
    val compression: PortableConversationCompression? = null
)

@Serializable
internal data class PortableConversationMessage(
    val role: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val kind: String? = null,
    val toolName: String? = null
)

@Serializable
internal data class PortableConversationUsage(
    val modelRequests: Int = 0,
    val reportedUsageRequests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val reasoningOutputTokens: Long = 0
)

@Serializable
internal data class PortableConversationCompression(
    val version: Int,
    val summary: String,
    val sourceMessageCount: Int,
    val createdAtEpochMillis: Long,
    val active: Boolean
)

internal object PortableConversationCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
    }

    fun looksLikePortableDocument(rawText: String): Boolean {
        return runCatching {
            json.parseToJsonElement(rawText)
                .jsonObject["format"]
                ?.jsonPrimitive
                ?.content == PORTABLE_CONVERSATION_FORMAT
        }.getOrDefault(false)
    }

    fun encode(session: PersistedSession): String {
        val originalMessages = session.messages
        val portableMessages = originalMessages.mapNotNull(::toPortableMessage)
        val compression = session.compressionSnapshot?.let { snapshot ->
            val sourceEndIndex = originalMessages.indexOfFirst { it.id == snapshot.sourceEndMessageId }
            val portableSourceCount = if (sourceEndIndex >= 0) {
                originalMessages.take(sourceEndIndex + 1).count { toPortableMessage(it) != null }
            } else {
                0
            }
            if (portableSourceCount > 0 && snapshot.summary.isNotBlank()) {
                PortableConversationCompression(
                    version = snapshot.version,
                    summary = snapshot.summary,
                    sourceMessageCount = portableSourceCount,
                    createdAtEpochMillis = snapshot.createdAt,
                    active = snapshot.active
                )
            } else {
                null
            }
        }
        val envelope = PortableConversationEnvelope(
            format = PORTABLE_CONVERSATION_FORMAT,
            formatVersion = PORTABLE_CONVERSATION_VERSION,
            exportedAtEpochMillis = System.currentTimeMillis(),
            sourcePlatform = "android",
            session = PortableConversationSession(
                title = session.title,
                createdAtEpochMillis = session.createdAt,
                updatedAtEpochMillis = session.updatedAt,
                providerId = session.providerId.takeIf { it.isNotBlank() },
                modelName = session.modelName.takeIf { it.isNotBlank() },
                goal = session.sessionGoal?.takeIf { it.isNotBlank() },
                messages = portableMessages,
                usage = PortableConversationUsage(
                    inputTokens = session.usageSummary.promptTokens.toLong(),
                    outputTokens = session.usageSummary.completionTokens.toLong(),
                    totalTokens = session.usageSummary.totalTokens.toLong(),
                    cachedInputTokens = session.usageSummary.promptCacheHitTokens.toLong()
                ),
                compression = compression
            )
        )
        validate(envelope)
        return json.encodeToString(envelope)
    }

    fun decode(rawText: String): PortableConversationEnvelope {
        require(rawText.toByteArray(Charsets.UTF_8).size in 1..PORTABLE_CONVERSATION_MAX_BYTES) {
            "跨端会话文件为空或超过 32 MiB"
        }
        val envelope = runCatching {
            json.decodeFromString<PortableConversationEnvelope>(rawText)
        }.getOrElse { error ->
            throw IllegalArgumentException("跨端会话 JSON 无效：${error.message}", error)
        }
        validate(envelope)
        return envelope
    }

    fun decodeImportedConversation(rawText: String): ImportedConversation {
        val envelope = decode(rawText)
        val messages = envelope.session.messages.mapIndexed { index, message ->
            ChatMessageUi(
                id = (index + 1).toLong(),
                role = when {
                    message.role == "tool" -> "tool_exec"
                    message.role == "assistant" && message.kind == "subagent" -> "subagent"
                    else -> message.role
                },
                content = message.content,
                timestamp = message.createdAtEpochMillis
            )
        }
        val usage = envelope.session.usage
        return ImportedConversation(
            messages = messages,
            titleHint = envelope.session.title,
            sessionGoal = envelope.session.goal,
            usageSummary = UsageSummarySnapshot(
                promptTokens = usage.inputTokens.toInt(),
                completionTokens = usage.outputTokens.toInt(),
                totalTokens = usage.totalTokens.toInt(),
                promptCacheHitTokens = usage.cachedInputTokens.toInt()
            ),
            compression = envelope.session.compression?.let { compression ->
                ImportedConversationCompression(
                    version = compression.version,
                    summary = compression.summary,
                    sourceMessageCount = compression.sourceMessageCount,
                    createdAt = compression.createdAtEpochMillis,
                    active = compression.active
                )
            }
        )
    }

    private fun toPortableMessage(message: PersistedMessage): PortableConversationMessage? {
        val role = when (message.role) {
            "user" -> "user"
            "assistant" -> "assistant"
            "tool_exec" -> "tool"
            "subagent" -> "assistant"
            else -> return null
        }
        val content = if (message.imageAttachments.isEmpty()) {
            message.content
        } else {
            buildString {
                append(message.content)
                if (message.content.isNotBlank()) append("\n\n")
                append("[跨端图片附件：")
                append(message.imageAttachments.joinToString("、") { it.fileName })
                append("。图片文件未随会话传输。]")
            }
        }
        return PortableConversationMessage(
            role = role,
            content = content,
            createdAtEpochMillis = message.timestamp,
            kind = if (message.role == "subagent") "subagent" else null
        )
    }

    private fun validate(envelope: PortableConversationEnvelope) {
        require(envelope.format == PORTABLE_CONVERSATION_FORMAT && envelope.formatVersion == PORTABLE_CONVERSATION_VERSION) {
            "不是受支持的 Murong 跨端会话文件"
        }
        require(envelope.sourcePlatform in setOf("android", "windows", "darwin", "linux", "desktop")) {
            "跨端会话来源平台无效"
        }
        require(envelope.exportedAtEpochMillis > 0) { "跨端会话导出时间无效" }
        val session = envelope.session
        require(session.title.isNotBlank() && session.title.length <= 500 && (session.goal?.length ?: 0) <= 20_000) {
            "跨端会话标题或目标无效"
        }
        require(session.createdAtEpochMillis >= 0 && session.updatedAtEpochMillis >= 0) { "跨端会话时间无效" }
        require((session.providerId?.length ?: 0) <= 1_000 && (session.modelName?.length ?: 0) <= 1_000) {
            "跨端会话模型元数据无效"
        }
        require(session.messages.size <= PORTABLE_CONVERSATION_MAX_MESSAGES) { "跨端会话消息数量超过 50000 条" }
        session.messages.forEach { message ->
            require(message.role == "user" || message.role == "assistant" || message.role == "tool") {
                "跨端会话包含未知消息角色"
            }
            require(message.content.toByteArray(Charsets.UTF_8).size <= PORTABLE_CONVERSATION_MAX_MESSAGE_BYTES) {
                "跨端会话包含过大消息"
            }
            require(message.createdAtEpochMillis >= 0 && (message.kind?.length ?: 0) <= 1_000 && (message.toolName?.length ?: 0) <= 1_000) {
                "跨端会话消息元数据无效"
            }
        }
        val usage = session.usage
        require(
            usage.modelRequests >= 0 && usage.reportedUsageRequests in 0..usage.modelRequests &&
                usage.inputTokens in 0..Int.MAX_VALUE.toLong() &&
                usage.outputTokens in 0..Int.MAX_VALUE.toLong() &&
                usage.totalTokens in 0..Int.MAX_VALUE.toLong() &&
                usage.cachedInputTokens in 0..Int.MAX_VALUE.toLong() &&
                usage.reasoningOutputTokens in 0..Int.MAX_VALUE.toLong()
        ) { "跨端会话用量无效" }
        session.compression?.let { compression ->
            require(
                compression.version > 0 && compression.summary.isNotBlank() &&
                    compression.summary.toByteArray(Charsets.UTF_8).size <= PORTABLE_CONVERSATION_MAX_SUMMARY_BYTES &&
                    compression.sourceMessageCount in 1..session.messages.size && compression.createdAtEpochMillis >= 0
            ) { "跨端会话摘要无效" }
        }
    }
}
