package com.murong.agent.core.loop

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A single session carried by the cross-platform section of a complete backup. */
data class PortableConversationBackupRecord(
    val sourceSessionId: String,
    val portableJson: String,
    val originPlatform: String = "",
    val originSessionId: String = "",
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
                val origin = session.syncOrigin()
                PortableConversationBackupRecord(
                    sourceSessionId = session.id,
                    portableJson = PortableConversationCodec.encode(session),
                    originPlatform = origin.first,
                    originSessionId = origin.second,
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
            val origin = record.syncOrigin(sourcePlatform)
            val incoming = envelope.toPersistedSession("pending", origin.first, origin.second)
            val primaryId = if (origin.first == "android") {
                origin.second
            } else {
                portableSessionId(origin.first, origin.second)
            }
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

    /**
     * Merges portable sessions into the live store through a same-filesystem directory swap.
     * Existing local sessions are copied first, so an interrupted merge never exposes a
     * half-written conversation directory.
     */
    fun mergeIntoCurrentStore(
        sourcePlatform: String,
        records: List<PortableConversationBackupRecord>
    ): PortableConversationMergeResult {
        val parent = conversationDir.parentFile ?: error("会话存储目录无效")
        require(parent.mkdirs() || parent.isDirectory) { "无法创建会话存储父目录" }
        val token = UUID.randomUUID().toString()
        val staging = File(parent, ".${conversationDir.name}.device-sync-$token")
        val backup = File(parent, ".${conversationDir.name}.device-sync-backup-$token")
        val result = prepareMergedDirectory(sourcePlatform, records, staging)
        var previousMoved = false
        try {
            if (conversationDir.exists()) {
                require(conversationDir.renameTo(backup)) { "无法锁定当前会话存储" }
                previousMoved = true
            }
            require(staging.renameTo(conversationDir)) { "无法启用同步后的会话存储" }
            if (previousMoved) backup.deleteRecursively()
            return result
        } catch (error: Throwable) {
            if (conversationDir.exists()) conversationDir.deleteRecursively()
            if (previousMoved && backup.exists()) {
                check(backup.renameTo(conversationDir)) { "会话同步失败，且无法恢复原会话存储" }
            }
            throw error
        } finally {
            if (staging.exists()) staging.deleteRecursively()
            if (backup.exists() && conversationDir.exists()) backup.deleteRecursively()
        }
    }

    fun validateRecords(sourcePlatform: String, records: List<PortableConversationBackupRecord>) {
        require(sourcePlatform in SUPPORTED_SOURCE_PLATFORMS) { "跨端备份会话来源平台无效" }
        require(records.size <= MAX_PORTABLE_BACKUP_SESSIONS) { "跨端备份会话数量超过上限" }
        require(records.map { it.sourceSessionId }.distinct().size == records.size) { "跨端备份会话 ID 重复" }
        var totalBytes = 0L
        records.forEach { record ->
            validateSourceSessionId(record.sourceSessionId)
            val origin = record.syncOrigin(sourcePlatform)
            require(origin.first in SUPPORTED_SOURCE_PLATFORMS) { "跨端会话原始来源平台无效" }
            validateSourceSessionId(origin.second)
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

    private fun PortableConversationEnvelope.toPersistedSession(
        id: String,
        originPlatform: String,
        originSessionId: String
    ): PersistedSession {
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
            syncOriginPlatform = originPlatform,
            syncOriginSessionId = originSessionId,
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

    private fun PersistedSession.syncOrigin(): Pair<String, String> {
        val platform = syncOriginPlatform.trim()
        val sessionId = syncOriginSessionId.trim()
        return if (platform.isNotEmpty() && sessionId.isNotEmpty()) {
            platform to sessionId
        } else {
            "android" to id
        }
    }

    private fun PortableConversationBackupRecord.syncOrigin(sourcePlatform: String): Pair<String, String> {
        val platform = originPlatform.trim()
        val sessionId = originSessionId.trim()
        return if (platform.isNotEmpty() && sessionId.isNotEmpty()) {
            platform to sessionId
        } else {
            sourcePlatform.trim() to sourceSessionId.trim()
        }
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
