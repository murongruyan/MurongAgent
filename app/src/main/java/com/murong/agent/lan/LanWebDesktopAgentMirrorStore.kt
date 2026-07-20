package com.murong.agent.lan

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Android-side durable mirror of computer-owned Agent sessions.
 *
 * The source desktop remains the only execution authority. This store deliberately does not write into
 * [com.murong.agent.core.loop.ConversationStore], because a mirrored task must never be resumed
 * by the Android Agent with computer-only project paths, tool state or approvals.
 */
internal class LanWebDesktopAgentMirrorStore internal constructor(
    private val stateFile: File
) {
    constructor(context: Context) : this(File(context.filesDir, STATE_FILE_NAME))

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun loadLatest(): LanWebDesktopAgentMirror? = synchronized(lock) {
        readStateLocked()?.toMirror()
    }

    fun saveSnapshot(
        clientId: String,
        snapshot: LanWebDesktopAgentSnapshotRequest
    ): LanWebDesktopAgentMirror = synchronized(lock) {
        val previous = readStateLocked()?.takeIf { it.clientId == clientId }
        val summaryIds = snapshot.sessions.mapTo(linkedSetOf()) { it.id }
        val details = previous?.details.orEmpty()
            .asSequence()
            .filter { it.id in summaryIds }
            .associateByTo(linkedMapOf()) { it.id }
        snapshot.sessionUpdates.forEach { update -> details[update.id] = update }
        snapshot.activeSession?.let { active -> details[active.id] = active }

        val selectedSessionId = snapshot.activeSession?.id
            ?: previous?.selectedSessionId?.takeIf { it in summaryIds }
            ?: snapshot.sessions.firstOrNull()?.id
        val retainedDetails = pruneDetails(details.values, selectedSessionId)
        val active = selectedSessionId?.let { selected -> retainedDetails.firstOrNull { it.id == selected } }
        val normalizedSnapshot = snapshot.copy(
            activeSession = active,
            sessionUpdates = emptyList()
        )
        val mirror = LanWebDesktopAgentMirror(
            clientId = clientId,
            savedAt = System.currentTimeMillis(),
            selectedSessionId = selectedSessionId,
            snapshot = normalizedSnapshot,
            details = retainedDetails
        )
        writeMirrorLocked(mirror)
        mirror
    }

    fun saveSession(
        clientId: String,
        nodeSessionId: String,
        detail: LanWebDesktopAgentTaskDetail
    ): LanWebDesktopAgentMirror? = synchronized(lock) {
        val previous = readStateLocked()?.takeIf { it.clientId == clientId } ?: return@synchronized null
        if (previous.snapshot.nodeSessionId != nodeSessionId) return@synchronized null
        val details = previous.details.associateByTo(linkedMapOf()) { it.id }
        details[detail.id] = detail
        val summaries = previous.snapshot.sessions.map { summary ->
            if (summary.id == detail.id) {
                summary.copy(
                    title = detail.title,
                    updatedAt = detail.updatedAt,
                    messageCount = detail.messageCount,
                    running = detail.running,
                    pendingApproval = detail.pendingApproval != null,
                    pendingQuestion = detail.pendingAsk != null
                )
            } else {
                summary
            }
        }
        val retainedDetails = pruneDetails(details.values, detail.id)
        val normalizedSnapshot = previous.snapshot.copy(
            sessions = summaries,
            activeSession = retainedDetails.firstOrNull { it.id == detail.id },
            sessionUpdates = emptyList()
        )
        val mirror = LanWebDesktopAgentMirror(
            clientId = clientId,
            savedAt = System.currentTimeMillis(),
            selectedSessionId = detail.id,
            snapshot = normalizedSnapshot,
            details = retainedDetails
        )
        writeMirrorLocked(mirror)
        mirror
    }

    fun selectSession(clientId: String, sessionId: String): LanWebDesktopAgentMirror? = synchronized(lock) {
        val previous = readStateLocked()?.takeIf { it.clientId == clientId } ?: return@synchronized null
        val summary = previous.snapshot.sessions.firstOrNull { it.id == sessionId } ?: return@synchronized null
        val detail = previous.details.firstOrNull { it.id == sessionId }
            ?: LanWebDesktopAgentTaskDetail(
                id = summary.id,
                title = summary.title,
                updatedAt = summary.updatedAt,
                running = false,
                messages = emptyList(),
                messageCount = summary.messageCount
            )
        val details = previous.details
            .filterNot { it.id == sessionId }
            .plus(detail)
        val mirror = LanWebDesktopAgentMirror(
            clientId = clientId,
            savedAt = System.currentTimeMillis(),
            selectedSessionId = sessionId,
            snapshot = previous.snapshot.copy(activeSession = detail, sessionUpdates = emptyList()),
            details = pruneDetails(details, sessionId)
        )
        writeMirrorLocked(mirror)
        mirror
    }

    fun clear(clientId: String? = null): Boolean = synchronized(lock) {
        if (clientId == null) {
            return@synchronized !stateFile.exists() || stateFile.delete()
        }
        val existing = readStateLocked() ?: return@synchronized false
        if (existing.clientId != clientId) return@synchronized false
        !stateFile.exists() || stateFile.delete()
    }

    private fun pruneDetails(
        candidates: Collection<LanWebDesktopAgentTaskDetail>,
        selectedSessionId: String?
    ): List<LanWebDesktopAgentTaskDetail> {
        val ordered = candidates
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<LanWebDesktopAgentTaskDetail> { it.id == selectedSessionId }
                    .thenByDescending { it.updatedAt }
            )
        val retained = ArrayList<LanWebDesktopAgentTaskDetail>()
        var retainedBytes = 0L
        for (detail in ordered) {
            if (retained.size >= MAX_CACHED_SESSION_DETAILS) break
            val bytes = json.encodeToString(detail).toByteArray(Charsets.UTF_8).size.toLong()
            val selected = detail.id == selectedSessionId
            if (!selected && retainedBytes + bytes > MAX_CACHED_DETAIL_BYTES) continue
            retained += detail
            retainedBytes += bytes
        }
        return retained
    }

    private fun readStateLocked(): PersistedDesktopAgentMirror? {
        if (!stateFile.exists() || !stateFile.isFile || stateFile.length() !in 1..MAX_STATE_FILE_BYTES) {
            return null
        }
        return runCatching {
            json.decodeFromString<PersistedDesktopAgentMirror>(stateFile.readText())
        }.getOrNull()?.takeIf { state ->
            state.schemaVersion == SCHEMA_VERSION &&
                state.clientId.length in 1..MAX_CLIENT_ID_CHARS &&
                state.details.size <= MAX_CACHED_SESSION_DETAILS
        }
    }

    private fun writeMirrorLocked(mirror: LanWebDesktopAgentMirror) {
        val offlineDetails = mirror.details.map(::withoutTransientRuntimeState)
        val offlineSnapshot = mirror.snapshot.copy(
            sessions = mirror.snapshot.sessions.map { it.copy(running = false, pendingApproval = false, pendingQuestion = false) },
            activeSession = mirror.snapshot.activeSession?.let(::withoutTransientRuntimeState),
            sessionUpdates = emptyList()
        )
        val state = PersistedDesktopAgentMirror(
            clientId = mirror.clientId,
            savedAt = mirror.savedAt,
            selectedSessionId = mirror.selectedSessionId,
            snapshot = offlineSnapshot,
            details = offlineDetails
        )
        val encoded = json.encodeToString(state)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_STATE_FILE_BYTES) {
            "桌面任务镜像超过本机持久化上限"
        }
        stateFile.parentFile?.mkdirs()
        val temporary = File(stateFile.parentFile, ".${stateFile.name}.tmp")
        temporary.writeText(encoded)
        if (!temporary.renameTo(stateFile)) {
            temporary.copyTo(stateFile, overwrite = true)
            check(temporary.delete()) { "无法清理桌面任务镜像临时文件" }
        }
    }

    private fun PersistedDesktopAgentMirror.toMirror() = LanWebDesktopAgentMirror(
        clientId = clientId,
        savedAt = savedAt,
        selectedSessionId = selectedSessionId,
        snapshot = snapshot.copy(sessionUpdates = emptyList()),
        details = details.map(::withoutTransientRuntimeState)
    )

    private fun withoutTransientRuntimeState(detail: LanWebDesktopAgentTaskDetail) = detail.copy(
        running = false,
        pendingApproval = null,
        pendingAsk = null
    )

    private companion object {
        const val SCHEMA_VERSION = 1
        const val STATE_FILE_NAME = "desktop_agent_mirror_v1.json"
        const val MAX_CLIENT_ID_CHARS = 128
        const val MAX_CACHED_SESSION_DETAILS = 64
        const val MAX_CACHED_DETAIL_BYTES = 24L * 1024L * 1024L
        const val MAX_STATE_FILE_BYTES = 32L * 1024L * 1024L
    }
}

internal data class LanWebDesktopAgentMirror(
    val clientId: String,
    val savedAt: Long,
    val selectedSessionId: String?,
    val snapshot: LanWebDesktopAgentSnapshotRequest,
    val details: List<LanWebDesktopAgentTaskDetail>
)

@Serializable
private data class PersistedDesktopAgentMirror(
    val schemaVersion: Int = 1,
    val clientId: String,
    val savedAt: Long,
    val selectedSessionId: String? = null,
    val snapshot: LanWebDesktopAgentSnapshotRequest,
    val details: List<LanWebDesktopAgentTaskDetail> = emptyList()
)
