package com.murong.agent.lan

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class LanWebPairingAuthState(
    val schemaVersion: Int = 1,
    val securityPassword: LanWebScramVerifierRecord? = null,
)

@Serializable
private data class LanWebScramVerifierRecord(
    val salt: String,
    val iterations: Int,
    val storedKey: String,
    val serverKey: String,
    val updatedAt: Long,
)

private data class ActiveTemporaryCode(
    val verifier: LanWebScramVerifier,
    val expiresAt: Long,
    val failedAttempts: Int = 0,
)

private data class ActiveScramSession(
    val request: LanWebPairChallengeRequest,
    val response: LanWebPairChallengeResponse,
    val verifier: LanWebScramVerifier,
    val authMessage: ByteArray,
)

data class LanWebPairingAuthentication(
    val trustSource: String,
    val serverProof: String,
)

data class LanWebPairingAuthSnapshot(
    val temporaryCodeAvailable: Boolean,
    val temporaryCodeExpiresAt: Long? = null,
    val cooldownUntil: Long? = null,
    val securityPasswordConfigured: Boolean = false,
)

class LanWebPairingAuthenticator internal constructor(
    private val stateFile: File,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, STATE_FILE_NAME))

    private val lock = Any()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val challengeLimiter = LanWebRateLimiter(limit = MAX_CHALLENGES_PER_WINDOW, windowMillis = RATE_WINDOW_MILLIS)
    private val sessions = linkedMapOf<String, ActiveScramSession>()
    private var temporaryCode: ActiveTemporaryCode? = null
    private var temporaryCooldownUntil: Long = 0L

    fun beginTemporaryCode(now: Long = System.currentTimeMillis()): LanWebPairingCode = synchronized(lock) {
        cleanupLocked(now)
        require(now >= temporaryCooldownUntil) {
            "临时验证码因连续失败已冷却，请稍后再生成"
        }
        clearTemporaryCodeLocked()
        val normalizedCode = buildString(LanWebContract.PAIRING_CODE_LENGTH) {
            repeat(LanWebContract.PAIRING_CODE_LENGTH) {
                append(PAIRING_ALPHABET[secureRandom.nextInt(PAIRING_ALPHABET.length)])
            }
        }
        val salt = ByteArray(LanWebScramCrypto.SALT_BYTES).also(secureRandom::nextBytes)
        val verifier = try {
            LanWebScramCrypto.createVerifier(normalizedCode, salt)
        } finally {
            salt.fill(0)
        }
        val expiresAt = now + TEMPORARY_CODE_TTL_MILLIS
        temporaryCode = ActiveTemporaryCode(verifier = verifier, expiresAt = expiresAt)
        LanWebPairingCode(value = normalizedCode.chunked(4).joinToString("-"), expiresAt = expiresAt)
    }

    fun cancelTemporaryCode() = synchronized(lock) {
        clearTemporaryCodeLocked()
    }

    fun setSecurityPassword(rawPassword: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val password = normalizeSecurityPassword(rawPassword)
        val salt = ByteArray(LanWebScramCrypto.SALT_BYTES).also(secureRandom::nextBytes)
        val verifier = try {
            LanWebScramCrypto.createVerifier(password, salt)
        } finally {
            salt.fill(0)
        }
        try {
            val state = loadStateForMutation()
            writeState(state.copy(securityPassword = verifier.toRecord(now)))
        } finally {
            verifier.clear()
        }
    }

    fun clearSecurityPassword() = synchronized(lock) {
        val state = loadStateForMutation()
        if (state.securityPassword != null) writeState(state.copy(securityPassword = null))
        clearSessionsLocked(LanWebTrustSource.SECURITY_PASSWORD)
    }

    fun snapshot(now: Long = System.currentTimeMillis()): LanWebPairingAuthSnapshot = synchronized(lock) {
        cleanupLocked(now)
        val active = temporaryCode
        LanWebPairingAuthSnapshot(
            temporaryCodeAvailable = active != null,
            temporaryCodeExpiresAt = active?.expiresAt,
            cooldownUntil = temporaryCooldownUntil.takeIf { it > now },
            securityPasswordConfigured = loadStateOrNull()?.securityPassword?.toVerifierOrNull()?.also { it.clear() } != null,
        )
    }

    fun beginChallenge(
        request: LanWebPairChallengeRequest,
        remoteAddress: String,
        now: Long = System.currentTimeMillis(),
    ): Result<LanWebPairChallengeResponse> = synchronized(lock) {
        runCatching {
            cleanupLocked(now)
            validateChallengeRequest(request, now)
            require(challengeLimiter.tryAcquire("$remoteAddress:${request.deviceId}", now)) {
                "密码认证尝试过于频繁，请稍后再试"
            }
            require(sessions.values.none { it.request.requestId == request.requestId }) {
                "该连接申请已有密码认证会话"
            }
            val verifier = when (request.authMethod) {
                LanWebTrustSource.TEMPORARY_CODE -> {
                    require(now >= temporaryCooldownUntil) { "临时验证码处于冷却期" }
                    val active = temporaryCode ?: error("当前没有可用临时验证码")
                    require(active.expiresAt > now) { "临时验证码已过期" }
                    active.verifier.copySecure()
                }
                LanWebTrustSource.SECURITY_PASSWORD ->
                    loadStateOrNull()?.securityPassword?.toVerifierOrNull()
                        ?: error("手机尚未设置安全密码")
                else -> error("当前密码认证方式不受支持")
            }
            try {
                val sessionId = randomBase64(18)
                val response = LanWebPairChallengeResponse(
                    sessionId = sessionId,
                    serverNonce = request.clientNonce + randomBase64(18),
                    salt = LanWebScramCrypto.encode(verifier.salt),
                    iterations = verifier.iterations,
                    expiresAt = now + CHALLENGE_TTL_MILLIS,
                )
                val authMessage = LanWebScramCrypto.authMessage(request, response)
                sessions[sessionId] = ActiveScramSession(
                    request = request,
                    response = response,
                    verifier = verifier,
                    authMessage = authMessage,
                )
                while (sessions.size > MAX_ACTIVE_CHALLENGES) {
                    sessions.remove(sessions.keys.first())?.clear()
                }
                response
            } catch (error: Throwable) {
                verifier.clear()
                throw error
            }
        }
    }

    fun authenticate(
        request: LanWebConnectionRequest,
        now: Long = System.currentTimeMillis(),
    ): LanWebPairingAuthentication? = synchronized(lock) {
        cleanupLocked(now)
        val separator = request.authProof.indexOf('.')
        if (separator !in 1 until request.authProof.lastIndex) return@synchronized null
        val sessionId = request.authProof.substring(0, separator)
        val proof = request.authProof.substring(separator + 1)
        val session = sessions.remove(sessionId) ?: return@synchronized null
        try {
            if (session.response.expiresAt <= now || !matches(session.request, request)) {
                recordFailureLocked(session.request.authMethod, now)
                return@synchronized null
            }
            val serverProof = LanWebScramCrypto.verifyClientProof(session.verifier, session.authMessage, proof)
            if (serverProof == null) {
                recordFailureLocked(session.request.authMethod, now)
                return@synchronized null
            }
            if (session.request.authMethod == LanWebTrustSource.TEMPORARY_CODE) {
                clearTemporaryCodeLocked()
                temporaryCooldownUntil = 0L
            }
            return@synchronized LanWebPairingAuthentication(
                trustSource = session.request.authMethod,
                serverProof = serverProof,
            )
        } finally {
            session.clear()
        }
    }

    internal fun persistedTextForTest(): String? = stateFile.takeIf(File::exists)?.readText()

    private fun validateChallengeRequest(request: LanWebPairChallengeRequest, now: Long) {
        require(LanWebContract.requestIdPattern.matches(request.requestId)) { "连接申请 ID 无效" }
        require(request.issuedAt in (now - SIGNATURE_CLOCK_WINDOW_MILLIS)..(now + 30_000L)) { "密码认证签名时间无效" }
        require(request.authMethod == LanWebTrustSource.TEMPORARY_CODE || request.authMethod == LanWebTrustSource.SECURITY_PASSWORD) {
            "当前密码认证方式不受支持"
        }
        require(request.clientNonce.length in 24..64 && request.clientNonce.all(::isBase64UrlCharacter)) { "客户端随机数无效" }
        require(request.platform.length in 1..24 && request.platform.none(Char::isISOControl)) { "设备平台无效" }
        requireNotNull(LanWebSecurity.normalizeClientName(request.clientName)) { "客户端名称无效" }
        require(LanWebDeviceIdentity.normalizeDeviceId(request.deviceId) == request.deviceId) { "设备 ID 无效" }
        require(LanWebDeviceIdentity.deviceIdForPublicKey(request.devicePublicKey) == request.deviceId) { "设备 ID 与公钥不匹配" }
        require(LanWebDeviceIdentity.fingerprintForPublicKey(request.devicePublicKey) == request.deviceFingerprint) { "设备指纹与公钥不匹配" }
        require(LanWebDeviceIdentity.deviceIdForPublicKey(request.ephemeralPublicKey) != null) { "临时设备公钥无效" }
        require(
            LanWebDeviceIdentity.verify(
                request.devicePublicKey,
                pairChallengeSignaturePayload(request),
                request.signature,
            )
        ) { "密码认证申请签名无效" }
    }

    private fun matches(challenge: LanWebPairChallengeRequest, request: LanWebConnectionRequest): Boolean =
        challenge.requestId == request.requestId &&
            challenge.clientName.trim() == request.clientName.trim() &&
            challenge.deviceId == request.deviceId &&
            challenge.devicePublicKey == request.devicePublicKey &&
            challenge.deviceFingerprint == request.deviceFingerprint &&
            challenge.ephemeralPublicKey == request.ephemeralPublicKey &&
            challenge.platform == request.platform &&
            challenge.issuedAt == request.issuedAt &&
            challenge.authMethod == request.authMethod

    private fun recordFailureLocked(method: String, now: Long) {
        if (method != LanWebTrustSource.TEMPORARY_CODE) return
        val active = temporaryCode ?: return
        val failures = active.failedAttempts + 1
        if (failures >= MAX_TEMPORARY_CODE_FAILURES) {
            clearTemporaryCodeLocked()
            temporaryCooldownUntil = now + TEMPORARY_CODE_COOLDOWN_MILLIS
        } else {
            temporaryCode = active.copy(failedAttempts = failures)
        }
    }

    private fun cleanupLocked(now: Long) {
        sessions.entries.removeAll { (_, session) ->
            (session.response.expiresAt <= now).also { expired -> if (expired) session.clear() }
        }
        val active = temporaryCode
        if (active != null && active.expiresAt <= now) clearTemporaryCodeLocked()
        if (temporaryCooldownUntil <= now) temporaryCooldownUntil = 0L
    }

    private fun clearTemporaryCodeLocked() {
        temporaryCode?.verifier?.clear()
        temporaryCode = null
        clearSessionsLocked(LanWebTrustSource.TEMPORARY_CODE)
    }

    private fun clearSessionsLocked(method: String) {
        sessions.entries.removeAll { (_, session) ->
            (session.request.authMethod == method).also { matches -> if (matches) session.clear() }
        }
    }

    private fun ActiveScramSession.clear() {
        verifier.clear()
        authMessage.fill(0)
    }

    private fun LanWebScramVerifier.toRecord(now: Long) = LanWebScramVerifierRecord(
        salt = LanWebScramCrypto.encode(salt),
        iterations = iterations,
        storedKey = LanWebScramCrypto.encode(storedKey),
        serverKey = LanWebScramCrypto.encode(serverKey),
        updatedAt = now,
    )

    private fun LanWebScramVerifierRecord.toVerifierOrNull(): LanWebScramVerifier? {
        if (iterations !in 100_000..1_000_000) return null
        val decodedSalt = LanWebScramCrypto.decode(salt, LanWebScramCrypto.SALT_BYTES) ?: return null
        val decodedStored = LanWebScramCrypto.decode(storedKey, LanWebScramCrypto.PROOF_BYTES)
        val decodedServer = LanWebScramCrypto.decode(serverKey, LanWebScramCrypto.PROOF_BYTES)
        if (decodedStored == null || decodedServer == null) {
            decodedSalt.fill(0)
            decodedStored?.fill(0)
            decodedServer?.fill(0)
            return null
        }
        return LanWebScramVerifier(decodedSalt, iterations, decodedStored, decodedServer)
    }

    private fun loadStateOrNull(): LanWebPairingAuthState? {
        if (!stateFile.exists()) return LanWebPairingAuthState()
        return runCatching { json.decodeFromString<LanWebPairingAuthState>(stateFile.readText()) }
            .getOrNull()
            ?.takeIf { it.schemaVersion == 1 }
    }

    private fun loadStateForMutation(): LanWebPairingAuthState {
        if (!stateFile.exists()) return LanWebPairingAuthState()
        return loadStateOrNull() ?: error("密码认证配置文件损坏；已安全关闭密码连接")
    }

    private fun writeState(state: LanWebPairingAuthState) {
        stateFile.parentFile?.mkdirs()
        val temp = File(stateFile.parentFile, "${stateFile.name}.tmp")
        temp.writeText(json.encodeToString(state))
        if (!temp.renameTo(stateFile)) {
            temp.copyTo(stateFile, overwrite = true)
            check(temp.delete()) { "无法清理密码认证临时文件" }
        }
    }

    private fun randomBase64(bytes: Int): String {
        val value = ByteArray(bytes).also(secureRandom::nextBytes)
        return try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        } finally {
            value.fill(0)
        }
    }

    private fun normalizeSecurityPassword(value: String): String {
        val normalized = value.trim()
        val codePoints = normalized.codePointCount(0, normalized.length)
        require(codePoints in MIN_SECURITY_PASSWORD_CODE_POINTS..MAX_SECURITY_PASSWORD_CODE_POINTS) {
            "安全密码需为 8–128 个字符"
        }
        require(normalized.none(Char::isISOControl)) { "安全密码不能包含控制字符" }
        return normalized
    }

    companion object {
        private const val STATE_FILE_NAME = "lan_web_pairing_auth.json"
        private const val PAIRING_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val TEMPORARY_CODE_TTL_MILLIS = 5 * 60 * 1000L
        private const val CHALLENGE_TTL_MILLIS = 60 * 1000L
        private const val SIGNATURE_CLOCK_WINDOW_MILLIS = 2 * 60 * 1000L
        private const val RATE_WINDOW_MILLIS = 5 * 60 * 1000L
        private const val TEMPORARY_CODE_COOLDOWN_MILLIS = 60 * 1000L
        private const val MAX_TEMPORARY_CODE_FAILURES = 5
        private const val MAX_CHALLENGES_PER_WINDOW = 12
        private const val MAX_ACTIVE_CHALLENGES = 64
        private const val MIN_SECURITY_PASSWORD_CODE_POINTS = 8
        private const val MAX_SECURITY_PASSWORD_CODE_POINTS = 128

        fun pairChallengeSignaturePayload(request: LanWebPairChallengeRequest): ByteArray = buildString {
            append("murong-pair-challenge-v1")
            append('\n').append(request.requestId)
            append('\n').append(request.clientName.trim())
            append('\n').append(request.deviceId)
            append('\n').append(request.devicePublicKey)
            append('\n').append(request.deviceFingerprint)
            append('\n').append(request.ephemeralPublicKey)
            append('\n').append(request.platform)
            append('\n').append(request.issuedAt)
            append('\n').append(request.authMethod)
            append('\n').append(request.clientNonce)
        }.toByteArray(Charsets.UTF_8)

        private fun isBase64UrlCharacter(value: Char): Boolean =
            value in 'A'..'Z' || value in 'a'..'z' || value in '0'..'9' || value == '-' || value == '_'
    }
}
