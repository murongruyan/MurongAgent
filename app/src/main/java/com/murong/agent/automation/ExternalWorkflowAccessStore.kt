package com.murong.agent.automation

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ExternalWorkflowAccessStatus(
    val enabled: Boolean = false,
    val tokenHint: String? = null,
    val tokenCreatedAt: Long? = null,
    val lastRequestAt: Long? = null,
    val lastRequestStatus: String? = null,
    val lastRequestMessage: String? = null
)

data class ExternalWorkflowTokenResult(
    val token: String,
    val status: ExternalWorkflowAccessStatus
)

@Serializable
private data class ExternalWorkflowAccessState(
    val schemaVersion: Int = 1,
    val enabled: Boolean = false,
    val tokenHash: String = "",
    val tokenHint: String? = null,
    val tokenCreatedAt: Long? = null,
    val recentRequests: List<ExternalWorkflowRequestClaim> = emptyList(),
    val lastRequestAt: Long? = null,
    val lastRequestStatus: String? = null,
    val lastRequestMessage: String? = null
)

@Serializable
private data class ExternalWorkflowRequestClaim(
    val requestId: String,
    val receivedAt: Long
)

/** Stores only a token hash under noBackupFilesDir. Corrupt state fails closed. */
class ExternalWorkflowAccessStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, STATE_FILE_NAME))

    fun status(): ExternalWorkflowAccessStatus = synchronized(FILE_LOCK) { readState().toStatus() }

    fun enableWithNewToken(nowMillis: Long = System.currentTimeMillis()): ExternalWorkflowTokenResult =
        synchronized(FILE_LOCK) {
            val token = generateToken()
            val state = ExternalWorkflowAccessState(
                enabled = true,
                tokenHash = hashToken(token),
                tokenHint = tokenHint(token),
                tokenCreatedAt = nowMillis
            )
            writeState(state)
            ExternalWorkflowTokenResult(token, state.toStatus())
        }

    fun disable(): ExternalWorkflowAccessStatus = synchronized(FILE_LOCK) {
        val disabled = ExternalWorkflowAccessState()
        writeState(disabled)
        disabled.toStatus()
    }

    fun authenticate(token: String): Boolean = synchronized(FILE_LOCK) {
        val state = readState()
        if (!state.enabled || state.tokenHash.isBlank()) return@synchronized false
        val expected = runCatching { Base64.getDecoder().decode(state.tokenHash) }.getOrNull()
            ?: return@synchronized false
        MessageDigest.isEqual(expected, digestToken(token))
    }

    /** Atomically consumes a request id. Old entries are bounded by age and count. */
    fun claimRequest(requestId: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        synchronized(FILE_LOCK) {
            val state = readState()
            if (!state.enabled) return@synchronized false
            val retained = state.recentRequests
                .filter { nowMillis - it.receivedAt in 0..REQUEST_TTL_MILLIS }
                .takeLast(MAX_RECENT_REQUESTS - 1)
            if (retained.any { it.requestId == requestId }) {
                if (retained.size != state.recentRequests.size) writeState(state.copy(recentRequests = retained))
                return@synchronized false
            }
            writeState(
                state.copy(
                    recentRequests = retained + ExternalWorkflowRequestClaim(requestId, nowMillis),
                    lastRequestAt = nowMillis,
                    lastRequestStatus = ExternalWorkflowContract.STATUS_QUEUED,
                    lastRequestMessage = "外部请求已通过鉴权和重放检查。"
                )
            )
            true
        }

    fun recordResult(
        requestId: String,
        status: String,
        message: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ExternalWorkflowAccessStatus = synchronized(FILE_LOCK) {
        val current = readState()
        val safeMessage = message.replace(Regex("[\\r\\n]+"), " ").take(400)
        val updated = current.copy(
            lastRequestAt = nowMillis,
            lastRequestStatus = status,
            lastRequestMessage = "$requestId · $safeMessage"
        )
        writeState(updated)
        updated.toStatus()
    }

    private fun readState(): ExternalWorkflowAccessState {
        if (!file.isFile) return ExternalWorkflowAccessState()
        return runCatching { JSON.decodeFromString<ExternalWorkflowAccessState>(file.readText()) }
            .getOrElse { ExternalWorkflowAccessState() }
            .takeIf { it.schemaVersion == 1 }
            ?: ExternalWorkflowAccessState()
    }

    private fun writeState(state: ExternalWorkflowAccessState) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(JSON.encodeToString(state))
        check(temporary.renameTo(file) || runCatching {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
            true
        }.getOrDefault(false)) { "无法保存外部自动化安全状态" }
    }

    private fun ExternalWorkflowAccessState.toStatus() = ExternalWorkflowAccessStatus(
        enabled = enabled,
        tokenHint = tokenHint,
        tokenCreatedAt = tokenCreatedAt,
        lastRequestAt = lastRequestAt,
        lastRequestStatus = lastRequestStatus,
        lastRequestMessage = lastRequestMessage
    )

    private companion object {
        const val STATE_FILE_NAME = "external_workflow_access.json"
        const val REQUEST_TTL_MILLIS = 24L * 60 * 60 * 1_000
        const val MAX_RECENT_REQUESTS = 128
        val FILE_LOCK = Any()
        val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }
        val RANDOM = SecureRandom()

        fun generateToken(): String = ByteArray(32).also(RANDOM::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }

        fun digestToken(token: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

        fun hashToken(token: String): String = Base64.getEncoder().encodeToString(digestToken(token))

        fun tokenHint(token: String): String = "${token.take(4)}…${token.takeLast(4)}"
    }
}
