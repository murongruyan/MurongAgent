package com.murong.agent.lan

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class LanWebAccessState(
    val schemaVersion: Int = 2,
    val clients: List<LanWebClientRecord> = emptyList(),
    val recentRequests: List<LanWebRequestRecord> = emptyList(),
    val blockedPeers: List<LanWebBlockedPeerRecord> = emptyList(),
    val doNotDisturb: Boolean = false,
)

@Serializable
private data class LanWebClientRecord(
    val id: String,
    val name: String,
    val tokenHash: String,
    val createdAt: Long,
    val lastSeenAt: Long? = null,
    val secureSync: Boolean = false,
    val deviceId: String = "",
    val publicKeyFingerprint: String = "",
    val trustSource: String = LanWebTrustSource.LEGACY_CODE,
)

@Serializable
private data class LanWebBlockedPeerRecord(
    val deviceId: String,
    val name: String,
    val publicKeyFingerprint: String,
    val blockedAt: Long,
)

@Serializable
private data class LanWebRequestRecord(
    val clientId: String,
    val requestId: String,
    val claimedAt: Long
)

private data class ActivePairingSession(
    val codeHash: String,
    val codeSecret: ByteArray,
    val expiresAt: Long,
    val failedAttempts: Int = 0
)

class LanWebAccessStore internal constructor(
    private val stateFile: File,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val syncKeyStore: LanWebSyncKeyStore? = null,
) {
    constructor(context: Context) : this(
        stateFile = File(context.noBackupFilesDir, STATE_FILE_NAME),
        syncKeyStore = AndroidLanWebSyncKeyStore(context),
    )

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var activePairing: ActivePairingSession? = null
    private val pairingAttempts = LanWebRateLimiter(limit = 8, windowMillis = PAIR_ATTEMPT_WINDOW_MILLIS)

    fun beginPairing(
        now: Long = System.currentTimeMillis(),
        rawCode: String? = null,
    ): LanWebPairingCode = synchronized(globalLock) {
        loadStateForMutation()
        clearActivePairing()
        val normalizedCode = rawCode?.uppercase()
            ?.filterNot { it == '-' || it.isWhitespace() }
            ?.takeIf {
                it.length == LanWebContract.PAIRING_CODE_LENGTH && it.all { char -> char in PAIRING_ALPHABET }
            }
            ?: if (rawCode == null) {
                buildString(LanWebContract.PAIRING_CODE_LENGTH) {
                    repeat(LanWebContract.PAIRING_CODE_LENGTH) {
                        append(PAIRING_ALPHABET[secureRandom.nextInt(PAIRING_ALPHABET.length)])
                    }
                }
            } else {
                error("临时验证码格式无效")
            }
        val expiresAt = now + PAIRING_TTL_MILLIS
        activePairing = ActivePairingSession(
            codeHash = hash(normalizedCode),
            codeSecret = normalizedCode.toByteArray(Charsets.US_ASCII),
            expiresAt = expiresAt
        )
        LanWebPairingCode(
            value = normalizedCode.chunked(4).joinToString("-"),
            expiresAt = expiresAt
        )
    }

    fun cancelPairing() = synchronized(globalLock) {
        clearActivePairing()
    }

    fun isPairingAvailable(now: Long = System.currentTimeMillis()): Boolean = synchronized(globalLock) {
        val session = activePairing ?: return@synchronized false
        if (session.expiresAt <= now) {
            clearActivePairing()
            false
        } else {
            true
        }
    }

    fun pair(
        rawCode: String,
        rawClientName: String,
        remoteAddress: String,
        secureSync: Boolean = false,
        rawCodeProof: String? = null,
        deviceId: String = "",
        publicKeyFingerprint: String = "",
        trustSource: String = LanWebTrustSource.LEGACY_CODE,
        now: Long = System.currentTimeMillis()
    ): Result<LanWebIssuedClient> = synchronized(globalLock) {
        runCatching {
            require(pairingAttempts.tryAcquire(remoteAddress, now)) { "配对尝试过于频繁，请稍后再试" }
            val clientName = LanWebSecurity.normalizeClientName(rawClientName)
                ?: error("客户端名称无效")
            val normalizedDeviceId = normalizeOptionalDeviceId(deviceId)
            val normalizedFingerprint = normalizeOptionalFingerprint(publicKeyFingerprint)
            require(trustSource in LanWebTrustSource.values) { "设备信任来源无效" }
            require(!isBlockedLocked(normalizedDeviceId, normalizedFingerprint)) { "该设备已被拉黑" }
            val session = activePairing ?: error("当前没有可用配对码")
            if (session.expiresAt <= now) {
                clearActivePairing()
                error("配对码已过期")
            }
            if (secureSync) {
                val proof = rawCodeProof
                    ?.takeIf { it.length == SHA256_BASE64URL_LENGTH && it.all(::isBase64UrlCharacter) }
                if (proof == null || !constantTimeEquals(proof, session.codeHash)) {
                    recordPairFailure(session, "安全配对证明无效")
                    error("安全配对证明无效")
                }
            } else {
                val normalizedCode = rawCode.uppercase()
                    .filterNot { it == '-' || it.isWhitespace() }
                    .takeIf {
                        it.length == LanWebContract.PAIRING_CODE_LENGTH &&
                            it.all { char -> char in PAIRING_ALPHABET }
                    }
                if (normalizedCode == null || !constantTimeEquals(hash(normalizedCode), session.codeHash)) {
                    recordPairFailure(session, "配对码无效")
                    error("配对码无效")
                }
            }

            val state = loadStateForMutation()
            require(state.clients.size < MAX_CLIENTS) { "已配对客户端达到上限，请先撤销旧客户端" }
            val token = randomToken()
            val syncKey = if (secureSync) {
                requireNotNull(syncKeyStore) { "当前运行环境不支持安全设备同步" }
                LanWebPairingCrypto.newSyncKey(secureRandom)
            } else {
                null
            }
            val record = LanWebClientRecord(
                id = UUID.randomUUID().toString(),
                name = clientName,
                tokenHash = hash(token),
                createdAt = now,
                lastSeenAt = now,
                secureSync = syncKey != null,
                deviceId = normalizedDeviceId,
                publicKeyFingerprint = normalizedFingerprint,
                trustSource = trustSource,
            )
            if (syncKey != null) syncKeyStore?.put(record.id, syncKey)
            try {
                writeState(state.copy(clients = state.clients + record))
            } catch (error: Throwable) {
                syncKeyStore?.remove(record.id)
                syncKey?.fill(0)
                throw error
            }
            val pairingSecret = if (secureSync) session.codeSecret.copyOf() else null
            clearActivePairing()
            LanWebIssuedClient(record.toSummary(), token, syncKey, pairingSecret)
        }
    }

    fun syncKey(clientId: String): ByteArray? = synchronized(globalLock) {
        val state = loadStateOrNull() ?: return@synchronized null
        val record = state.clients.firstOrNull { it.id == clientId && it.secureSync }
            ?: return@synchronized null
        syncKeyStore?.read(record.id)
    }

    fun trustedClient(deviceId: String, publicKeyFingerprint: String): LanWebClientSummary? = synchronized(globalLock) {
        val normalizedDeviceId = normalizeOptionalDeviceId(deviceId)
        val normalizedFingerprint = normalizeOptionalFingerprint(publicKeyFingerprint)
        if (normalizedDeviceId.isBlank() || normalizedFingerprint.isBlank()) return@synchronized null
        loadStateOrNull()?.clients?.firstOrNull {
            it.deviceId == normalizedDeviceId && it.publicKeyFingerprint == normalizedFingerprint
        }?.toSummary()
    }

    fun issueTrustedClient(
        rawClientName: String,
        deviceId: String,
        publicKeyFingerprint: String,
        trustSource: String,
        now: Long = System.currentTimeMillis(),
    ): LanWebIssuedClient = synchronized(globalLock) {
        val clientName = LanWebSecurity.normalizeClientName(rawClientName) ?: error("客户端名称无效")
        val normalizedDeviceId = normalizeOptionalDeviceId(deviceId)
        val normalizedFingerprint = normalizeOptionalFingerprint(publicKeyFingerprint)
        require(normalizedDeviceId.isNotBlank() && normalizedFingerprint.isNotBlank()) { "设备身份不完整" }
        require(trustSource in LanWebTrustSource.values) { "设备信任来源无效" }
        require(!isBlockedLocked(normalizedDeviceId, normalizedFingerprint)) { "该设备已被拉黑" }
        val state = loadStateForMutation()
        val replaced = state.clients.filter {
            it.deviceId == normalizedDeviceId || it.publicKeyFingerprint == normalizedFingerprint
        }
        val remaining = state.clients - replaced.toSet()
        require(remaining.size < MAX_CLIENTS) { "已配对客户端达到上限，请先撤销旧客户端" }
        val token = randomToken()
        val syncKey = requireNotNull(syncKeyStore) { "当前运行环境不支持安全设备同步" }
            .let { LanWebPairingCrypto.newSyncKey(secureRandom) }
        val record = LanWebClientRecord(
            id = UUID.randomUUID().toString(),
            name = clientName,
            tokenHash = hash(token),
            createdAt = now,
            lastSeenAt = now,
            secureSync = true,
            deviceId = normalizedDeviceId,
            publicKeyFingerprint = normalizedFingerprint,
            trustSource = trustSource,
        )
        replaced.forEach { syncKeyStore?.remove(it.id) }
        syncKeyStore?.put(record.id, syncKey)
        try {
            writeState(
                state.copy(
                    clients = remaining + record,
                    recentRequests = state.recentRequests.filterNot { request -> replaced.any { it.id == request.clientId } },
                )
            )
        } catch (error: Throwable) {
            syncKeyStore?.remove(record.id)
            syncKey.fill(0)
            throw error
        }
        LanWebIssuedClient(record.toSummary(), token, syncKey)
    }

    fun authenticate(token: String, now: Long = System.currentTimeMillis()): LanWebClientSummary? =
        synchronized(globalLock) {
            if (token.length !in 32..128 || token.any { it.code < 0x21 || it.code > 0x7e }) {
                return@synchronized null
            }
            val state = loadStateOrNull() ?: return@synchronized null
            val tokenHash = hash(token)
            val record = state.clients.firstOrNull { constantTimeEquals(it.tokenHash, tokenHash) }
                ?: return@synchronized null
            if (record.lastSeenAt == null || now - record.lastSeenAt >= LAST_SEEN_WRITE_INTERVAL_MILLIS) {
                val updated = record.copy(lastSeenAt = now)
                runCatching {
                    writeState(
                        state.copy(clients = state.clients.map { if (it.id == record.id) updated else it })
                    )
                }
                updated.toSummary()
            } else {
                record.toSummary()
            }
        }

    fun claimRequest(
        clientId: String,
        requestId: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = synchronized(globalLock) {
        if (!LanWebContract.requestIdPattern.matches(requestId)) return@synchronized false
        val state = runCatching { loadStateForMutation() }.getOrNull() ?: return@synchronized false
        if (state.clients.none { it.id == clientId }) return@synchronized false
        val cutoff = now - REQUEST_REPLAY_TTL_MILLIS
        val kept = state.recentRequests
            .filter { it.claimedAt > cutoff }
            .takeLast(MAX_RECENT_REQUESTS - 1)
        if (kept.any { it.clientId == clientId && it.requestId == requestId }) return@synchronized false
        writeState(
            state.copy(
                recentRequests = kept + LanWebRequestRecord(clientId, requestId, now)
            )
        )
        true
    }

    fun clients(): List<LanWebClientSummary> = synchronized(globalLock) {
        loadStateOrNull()?.clients?.map { it.toSummary() }.orEmpty()
    }

    fun blockedPeers(): List<LanWebBlockedPeerSummary> = synchronized(globalLock) {
        loadStateOrNull()?.blockedPeers?.map { it.toSummary() }.orEmpty()
    }

    fun doNotDisturb(): Boolean = synchronized(globalLock) {
        loadStateOrNull()?.doNotDisturb == true
    }

    fun setDoNotDisturb(enabled: Boolean) = synchronized(globalLock) {
        val state = loadStateForMutation()
        writeState(state.copy(doNotDisturb = enabled))
    }

    fun isBlocked(deviceId: String, publicKeyFingerprint: String): Boolean = synchronized(globalLock) {
        isBlockedLocked(normalizeOptionalDeviceId(deviceId), normalizeOptionalFingerprint(publicKeyFingerprint))
    }

    fun blockPeer(
        deviceId: String,
        rawName: String,
        publicKeyFingerprint: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(globalLock) {
        val normalizedDeviceId = normalizeOptionalDeviceId(deviceId)
        val normalizedFingerprint = normalizeOptionalFingerprint(publicKeyFingerprint)
        require(normalizedDeviceId.isNotBlank() && normalizedFingerprint.isNotBlank()) { "拉黑设备身份不完整" }
        val name = LanWebSecurity.normalizeClientName(rawName) ?: "未知设备"
        val state = loadStateForMutation()
        val revoked = state.clients.filter {
            it.deviceId == normalizedDeviceId || it.publicKeyFingerprint == normalizedFingerprint
        }
        revoked.forEach { syncKeyStore?.remove(it.id) }
        val nextBlocked = state.blockedPeers
            .filterNot { it.deviceId == normalizedDeviceId || it.publicKeyFingerprint == normalizedFingerprint }
            .takeLast(MAX_BLOCKED_PEERS - 1) + LanWebBlockedPeerRecord(
                deviceId = normalizedDeviceId,
                name = name,
                publicKeyFingerprint = normalizedFingerprint,
                blockedAt = now,
            )
        writeState(
            state.copy(
                clients = state.clients - revoked.toSet(),
                recentRequests = state.recentRequests.filterNot { request -> revoked.any { it.id == request.clientId } },
                blockedPeers = nextBlocked,
            )
        )
        true
    }

    fun unblockPeer(deviceId: String): Boolean = synchronized(globalLock) {
        val normalized = normalizeOptionalDeviceId(deviceId)
        val state = loadStateForMutation()
        if (state.blockedPeers.none { it.deviceId == normalized }) return@synchronized false
        writeState(state.copy(blockedPeers = state.blockedPeers.filterNot { it.deviceId == normalized }))
        true
    }

    fun revokeClient(clientId: String): Boolean = synchronized(globalLock) {
        val state = runCatching { loadStateForMutation() }.getOrNull() ?: return@synchronized false
        if (state.clients.none { it.id == clientId }) return@synchronized false
        writeState(
            state.copy(
                clients = state.clients.filterNot { it.id == clientId },
                recentRequests = state.recentRequests.filterNot { it.clientId == clientId }
            )
        )
        syncKeyStore?.remove(clientId)
        true
    }

    fun revokeAll() = synchronized(globalLock) {
        val state = loadStateForMutation()
        writeState(state.copy(clients = emptyList(), recentRequests = emptyList()))
        syncKeyStore?.clear()
        clearActivePairing()
    }

    internal fun persistedTextForTest(): String? = stateFile.takeIf(File::exists)?.readText()

    private fun recordPairFailure(session: ActivePairingSession, message: String): Nothing {
        val failedAttempts = session.failedAttempts + 1
        activePairing = if (failedAttempts >= MAX_PAIRING_FAILURES) {
            session.codeSecret.fill(0)
            null
        } else {
            session.copy(failedAttempts = failedAttempts)
        }
        error(message)
    }

    private fun clearActivePairing() {
        activePairing?.codeSecret?.fill(0)
        activePairing = null
    }

    private fun isBase64UrlCharacter(value: Char): Boolean =
        value in 'A'..'Z' || value in 'a'..'z' || value in '0'..'9' || value == '-' || value == '_'

    private fun normalizeOptionalDeviceId(value: String): String {
        if (value.isBlank()) return ""
        return LanWebDeviceIdentity.normalizeDeviceId(value) ?: error("设备 ID 无效")
    }

    private fun normalizeOptionalFingerprint(value: String): String {
        if (value.isBlank()) return ""
        require(value.length == SHA256_BASE64URL_LENGTH && value.all(::isBase64UrlCharacter)) { "设备公钥指纹无效" }
        return value
    }

    private fun isBlockedLocked(deviceId: String, fingerprint: String): Boolean {
        if (deviceId.isBlank() && fingerprint.isBlank()) return false
        val state = loadStateOrNull() ?: return true
        return state.blockedPeers.any {
            deviceId.isNotBlank() && it.deviceId == deviceId ||
                fingerprint.isNotBlank() && it.publicKeyFingerprint == fingerprint
        }
    }

    private fun loadStateOrNull(): LanWebAccessState? {
        if (!stateFile.exists()) return LanWebAccessState()
        return runCatching { json.decodeFromString<LanWebAccessState>(stateFile.readText()) }
            .getOrNull()
            ?.let { state ->
                when (state.schemaVersion) {
                    1 -> state.copy(schemaVersion = 2)
                    2 -> state
                    else -> null
                }
            }
    }

    private fun loadStateForMutation(): LanWebAccessState {
        if (!stateFile.exists()) return LanWebAccessState()
        return loadStateOrNull() ?: error("局域网客户端凭据文件损坏；已安全关闭访问")
    }

    private fun writeState(state: LanWebAccessState) {
        stateFile.parentFile?.mkdirs()
        val temp = File(stateFile.parentFile, "${stateFile.name}.tmp")
        temp.writeText(json.encodeToString(state))
        if (!temp.renameTo(stateFile)) {
            temp.copyTo(stateFile, overwrite = true)
            check(temp.delete()) { "无法清理局域网凭据临时文件" }
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32).also(secureRandom::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(Charsets.UTF_8),
        right.toByteArray(Charsets.UTF_8)
    )

    private fun LanWebClientRecord.toSummary() = LanWebClientSummary(
        id = id,
        name = name,
        createdAt = createdAt,
        lastSeenAt = lastSeenAt,
        secureSync = secureSync,
        deviceId = deviceId,
        publicKeyFingerprint = publicKeyFingerprint,
        trustSource = trustSource,
    )

    private fun LanWebBlockedPeerRecord.toSummary() = LanWebBlockedPeerSummary(
        deviceId = deviceId,
        name = name,
        publicKeyFingerprint = publicKeyFingerprint,
        blockedAt = blockedAt,
    )

    private companion object {
        val globalLock = Any()
        const val STATE_FILE_NAME = "lan_web_access.json"
        const val MAX_CLIENTS = 8
        const val MAX_RECENT_REQUESTS = 256
        const val MAX_BLOCKED_PEERS = 128
        const val PAIRING_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val MAX_PAIRING_FAILURES = 8
        const val PAIRING_TTL_MILLIS = 5 * 60 * 1000L
        const val PAIR_ATTEMPT_WINDOW_MILLIS = 5 * 60 * 1000L
        const val SHA256_BASE64URL_LENGTH = 43
        const val REQUEST_REPLAY_TTL_MILLIS = 24 * 60 * 60 * 1000L
        const val LAST_SEEN_WRITE_INTERVAL_MILLIS = 60 * 1000L
    }
}
