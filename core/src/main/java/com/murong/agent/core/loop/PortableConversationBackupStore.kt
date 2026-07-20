package com.murong.agent.core.loop

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A single session carried by the cross-platform section of a complete backup. */
data class PortableConversationBackupRecord(
    val sourceSessionId: String,
    val portableJson: String
)

data class PortableConversationMergeResult(
    val importedSessions: Int = 0,
    val conflictCopies: Int = 0,
    val skippedSessions: Int = 0
)

/**
 * Converts the native Android conversation store to and from the already versioned
 * `murong-portable-session` format without involving the live Agent loop.
 */
class PortableConversationBackupStore private constructor(
    private val conversationDir: File
) {
    constructor(context: Context) : this(File(context.filesDir, "conversations"))

    fun exportAll(): List<PortableConversationBackupRecord> {
        val store = ConversationStore(conversationDir)
        return store.listSessions()
            .asSequence()
            .mapNotNull { summary -> store.loadSession(summary.id) }
            .map { session ->
                PortableConversationBackupRecord(
                    sourceSessionId = session.id,
                    portableJson = PortableConversationCodec.encode(session)
                )
            }
            .toList()
    }

    /**
     * Builds a complete replacement directory containing the current native store
     * plus imported portable sessions. The caller can atomically swap it into place.
     */
    fun prepareMergedDirectory(
        sourcePlatform: String,
        records: List<PortableConversationBackupRecord>,
        destination: File
    ): PortableConversationMergeResult {
        validateRecords(sourcePlatform, records)
        destination.deleteRecursively()
        require(destination.mkdirs() || destination.isDirectory) { "无法创建跨端会话恢复目录" }
        copyCurrentStore(destination)
        val targetStore = ConversationStore(destination)
        var imported = 0
        var conflicts = 0
        var skipped = 0

        records.forEach { record ->
            val envelope = PortableConversationCodec.decode(record.portableJson)
            val incoming = envelope.toPersistedSession("pending")
            val primaryId = portableSessionId(sourcePlatform, record.sourceSessionId)
            val primary = targetStore.loadSession(primaryId)
            val targetId = when {
                primary == null -> primaryId
                sessionsEquivalent(primary, envelope.session) -> {
                    skipped++
                    return@forEach
                }
                else -> {
                    val conflictId = "$primaryId-${portableSessionFingerprint(envelope.session).take(12)}"
                    val conflict = targetStore.loadSession(conflictId)
                    if (conflict != null && sessionsEquivalent(conflict, envelope.session)) {
                        skipped++
                        return@forEach
                    }
                    require(conflict == null) { "跨端会话冲突副本 ID 碰撞" }
                    conflicts++
                    conflictId
                }
            }
            if (targetStore.saveSession(incoming.copy(id = targetId))) {
                imported++
            } else {
                // A local tombstone intentionally prevents a deleted imported task from being resurrected.
                skipped++
            }
        }
        return PortableConversationMergeResult(imported, conflicts, skipped)
    }

    fun validateRecords(sourcePlatform: String, records: List<PortableConversationBackupRecord>) {
        require(sourcePlatform in SUPPORTED_SOURCE_PLATFORMS) { "跨端备份会话来源平台无效" }
        require(records.size <= MAX_PORTABLE_BACKUP_SESSIONS) { "跨端备份会话数量超过上限" }
        require(records.map { it.sourceSessionId }.distinct().size == records.size) { "跨端备份会话 ID 重复" }
        var totalBytes = 0L
        records.forEach { record ->
            validateSourceSessionId(record.sourceSessionId)
            val bytes = record.portableJson.toByteArray(Charsets.UTF_8)
            totalBytes = Math.addExact(totalBytes, bytes.size.toLong())
            require(totalBytes <= MAX_PORTABLE_BACKUP_TOTAL_BYTES) { "跨端备份会话总大小超过上限" }
            val envelope = PortableConversationCodec.decode(record.portableJson)
            require(envelope.sourcePlatform == sourcePlatform) { "跨端会话来源与备份来源不一致" }
        }
    }

    private fun copyCurrentStore(destination: File) {
        if (!conversationDir.isDirectory) return
        val sourceRoot = conversationDir.canonicalFile
        val sourcePrefix = sourceRoot.path.trimEnd(File.separatorChar) + File.separator
        conversationDir.walkTopDown().forEach { source ->
            val canonicalSource = source.canonicalFile
            require(canonicalSource == sourceRoot || canonicalSource.path.startsWith(sourcePrefix)) {
                "会话存储包含越界符号链接"
            }
            val relative = canonicalSource.relativeTo(sourceRoot).invariantSeparatorsPath
            if (relative.isBlank()) return@forEach
            val target = File(destination, relative)
            if (source.isDirectory) {
                require(target.mkdirs() || target.isDirectory) { "无法创建会话恢复子目录" }
            } else if (source.isFile && !source.name.endsWith(".tmp")) {
                target.parentFile?.let { require(it.mkdirs() || it.isDirectory) }
                source.inputStream().use { input -> target.outputStream().use(input::copyTo) }
            }
        }
    }

    private fun PortableConversationEnvelope.toPersistedSession(id: String): PersistedSession {
        val convertedMessages = session.messages.mapIndexed { index, message ->
            PersistedMessage(
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
        val compression = session.compression?.let { value ->
            PersistedCompressionSnapshot(
                id = "portable-compression-${value.version}",
                version = value.version,
                summary = value.summary,
                sourceMessageCount = value.sourceMessageCount,
                sourceEndMessageId = convertedMessages[value.sourceMessageCount - 1].id,
                sourceEndMessageIndex = value.sourceMessageCount - 1,
                createdAt = value.createdAtEpochMillis,
                active = value.active
            )
        }
        return PersistedSession(
            id = id,
            title = session.title.trim(),
            createdAt = session.createdAtEpochMillis,
            updatedAt = session.updatedAtEpochMillis,
            providerId = session.providerId.orEmpty(),
            modelName = session.modelName.orEmpty(),
            sessionGoal = session.goal,
            usageSummary = UsageSummarySnapshot(
                promptTokens = session.usage.inputTokens.toInt(),
                completionTokens = session.usage.outputTokens.toInt(),
                totalTokens = session.usage.totalTokens.toInt(),
                promptCacheHitTokens = session.usage.cachedInputTokens.toInt()
            ),
            compressionSnapshot = compression,
            compressionSnapshots = listOfNotNull(compression),
            messages = convertedMessages
        )
    }

    private fun sessionsEquivalent(existing: PersistedSession, incoming: PortableConversationSession): Boolean {
        return PortableConversationCodec.decode(PortableConversationCodec.encode(existing)).session == incoming
    }

    private fun portableSessionFingerprint(session: PortableConversationSession): String {
        val bytes = CANONICAL_JSON.encodeToString(PortableConversationSession.serializer(), session)
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    private fun portableSessionId(sourcePlatform: String, sourceSessionId: String): String {
        val bytes = "$sourcePlatform\u0000$sourceSessionId".toByteArray(Charsets.UTF_8)
        return "portable-${sourcePlatform}-${MessageDigest.getInstance("SHA-256").digest(bytes).toHex().take(24)}"
    }

    private fun validateSourceSessionId(value: String) {
        require(value.isNotBlank() && value.length <= 500 && value.none(Char::isISOControl)) {
            "跨端备份会话 ID 无效"
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    internal companion object {
        private val CANONICAL_JSON = Json { encodeDefaults = true; explicitNulls = false }
        private val SUPPORTED_SOURCE_PLATFORMS = setOf("android", "windows", "darwin", "linux", "desktop")
        private const val MAX_PORTABLE_BACKUP_SESSIONS = 10_000
        private const val MAX_PORTABLE_BACKUP_TOTAL_BYTES = 512L * 1024L * 1024L

        fun forDirectory(directory: File): PortableConversationBackupStore = PortableConversationBackupStore(directory)
    }
}
