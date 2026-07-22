package com.murong.agent.lan

import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object LanWebDeviceRelayProtocol {
    const val VERSION = 2
    const val SUBPROTOCOL = "murong-device-relay-v2"
    const val OFFICIAL_RELAY_URL = "wss://murongagent.rl1.cc/relay/v2/device"
    const val OFFICIAL_TUNNEL_URL = "wss://murongagent.rl1.cc/relay/v2/tunnel"
    const val MAX_FRAME_BYTES = 32 * 1024

    private const val CLOCK_WINDOW_MILLIS = 2 * 60 * 1_000L
    private const val FUTURE_WINDOW_MILLIS = 30 * 1_000L
    private const val MAX_INVITATION_LIFETIME_MILLIS = 2 * 60 * 1_000L
    private const val NONCE_BYTES = 12
    private const val SECRET_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

    fun registration(
        identity: LanWebDeviceIdentity,
        platform: String,
        now: Long = System.currentTimeMillis(),
        secureRandom: SecureRandom = SecureRandom(),
    ): LanWebDeviceRelayMessage {
        require(platform.isNotBlank() && platform.length <= 24 && platform.none { it == '\r' || it == '\n' || it == '\u0000' })
        val nonce = ByteArray(16).also(secureRandom::nextBytes)
        val snapshot = identity.snapshot
        return LanWebDeviceRelayMessage(
            version = VERSION,
            kind = "register",
            deviceId = snapshot.deviceId,
            devicePublicKey = snapshot.publicKey,
            platform = platform,
            role = "listener",
            issuedAt = now,
            nonce = encoder.encodeToString(nonce),
        ).let { it.copy(signature = identity.sign(registrationPayload(it))) }
    }

    fun acknowledgement(
        identity: LanWebDeviceIdentity,
        invitation: LanWebDeviceRelayMessage,
        accepted: Boolean,
        error: String = "",
        now: Long = System.currentTimeMillis(),
    ): LanWebDeviceRelayMessage {
        val safeError = error.replace(Regex("[\\r\\n\\u0000]+"), " ").take(300)
        return LanWebDeviceRelayMessage(
            version = VERSION,
            kind = "invite_ack",
            requestId = invitation.requestId,
            sourceDeviceId = identity.snapshot.deviceId,
            targetDeviceId = invitation.sourceDeviceId,
            status = if (accepted) "accepted" else "rejected",
            error = safeError,
            issuedAt = now,
        ).let { it.copy(signature = identity.sign(inviteAckPayload(it))) }
    }

    fun lookup(
        identity: LanWebDeviceIdentity,
        targetDeviceId: String,
        requestId: String = requestId("lookup"),
        now: Long = System.currentTimeMillis(),
    ): LanWebDeviceRelayMessage {
        val target = requireNotNull(LanWebDeviceIdentity.normalizeDeviceId(targetDeviceId)) { "目标本机 ID 无效" }
        require(target != identity.snapshot.deviceId) { "不能连接当前手机自己的本机 ID" }
        return LanWebDeviceRelayMessage(
            version = VERSION,
            kind = "lookup",
            requestId = requestId,
            sourceDeviceId = identity.snapshot.deviceId,
            targetDeviceId = target,
            issuedAt = now,
        ).let { it.copy(signature = identity.sign(lookupPayload(it))) }
    }

    fun connectionRequest(
        identity: LanWebDeviceIdentity,
        targetDeviceId: String,
        deviceName: String,
        platform: String = "android",
        authMethod: String = "",
        clientNonce: String = "",
        authProof: String = "",
		ephemeralPublicKey: String = identity.snapshot.publicKey,
        requestId: String = requestId("connect"),
        now: Long = System.currentTimeMillis(),
    ): LanWebDeviceRelayMessage {
        val target = requireNotNull(LanWebDeviceIdentity.normalizeDeviceId(targetDeviceId)) { "目标本机 ID 无效" }
        require(target != identity.snapshot.deviceId) { "不能连接当前手机自己的本机 ID" }
        val safeName = deviceName.trim().take(80)
        require(safeName.isNotBlank() && safeName.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "手机名称无效"
        }
        return LanWebDeviceRelayMessage(
            version = VERSION,
            kind = "connect_request",
            requestId = requestId,
            sourceDeviceId = identity.snapshot.deviceId,
            sourcePublicKey = identity.snapshot.publicKey,
            targetDeviceId = target,
			ephemeralPublicKey = ephemeralPublicKey,
            deviceName = safeName,
            platform = platform,
            authMethod = authMethod,
            clientNonce = clientNonce,
            authProof = authProof,
            issuedAt = now,
        ).let { it.copy(signature = identity.sign(connectRequestPayload(it))) }
    }

    fun validatePeer(message: LanWebDeviceRelayMessage, targetDeviceId: String, now: Long = System.currentTimeMillis()) {
        val target = requireNotNull(LanWebDeviceIdentity.normalizeDeviceId(targetDeviceId)) { "目标本机 ID 无效" }
        require(message.kind == "peer" && message.deviceId == target && validClock(message.issuedAt, now)) {
            "设备中继返回的目标身份无效"
        }
        require(LanWebDeviceIdentity.deviceIdForPublicKey(message.devicePublicKey) == target) {
            "设备中继返回的目标公钥无效"
        }
    }

    fun authBegin(
        identity: LanWebDeviceIdentity,
        targetDeviceId: String,
        deviceName: String,
        authMethod: String,
        clientNonce: String,
        requestId: String = requestId("auth"),
        now: Long = System.currentTimeMillis(),
    ): LanWebDeviceRelayMessage {
        val target = requireNotNull(LanWebDeviceIdentity.normalizeDeviceId(targetDeviceId)) { "目标本机 ID 无效" }
        require(authMethod == LanWebTrustSource.TEMPORARY_CODE || authMethod == LanWebTrustSource.SECURITY_PASSWORD) {
            "密码认证方式无效"
        }
        val safeName = deviceName.trim().take(80)
        require(safeName.isNotBlank() && safeName.none(Char::isISOControl)) { "手机名称无效" }
        require(LanWebScramCrypto.decode(clientNonce, 18) != null) { "客户端随机数无效" }
        return LanWebDeviceRelayMessage(
            version = VERSION,
            kind = "auth_begin",
            requestId = requestId,
            sourceDeviceId = identity.snapshot.deviceId,
            sourcePublicKey = identity.snapshot.publicKey,
            targetDeviceId = target,
			ephemeralPublicKey = identity.snapshot.publicKey,
            deviceName = safeName,
            platform = "android",
            authMethod = authMethod,
            clientNonce = clientNonce,
            issuedAt = now,
        ).let { it.copy(signature = identity.sign(authBeginPayload(it))) }
    }

    fun validateAuthChallenge(
        message: LanWebDeviceRelayMessage,
        request: LanWebDeviceRelayMessage,
        targetPublicKey: String,
        now: Long = System.currentTimeMillis(),
    ) {
        require(
            message.kind == "auth_challenge" && message.requestId == request.requestId &&
                message.sourceDeviceId == request.targetDeviceId && message.targetDeviceId == request.sourceDeviceId &&
                message.authMethod == request.authMethod && message.clientNonce == request.clientNonce &&
                message.expiresAt in now..(now + 2 * 60_000L) && validClock(message.issuedAt, now)
        ) { "设备密码认证挑战无效" }
        require(
            message.iterations in 100_000..1_000_000 &&
                LanWebScramCrypto.decode(message.sessionId, 18) != null &&
                message.serverNonce.startsWith(request.clientNonce) &&
                message.serverNonce.length > request.clientNonce.length &&
                LanWebScramCrypto.decode(message.salt, LanWebScramCrypto.SALT_BYTES) != null
        ) { "设备密码认证参数无效" }
        require(LanWebDeviceIdentity.verify(targetPublicKey, authChallengePayload(message), message.signature)) {
            "设备密码认证挑战签名无效"
        }
    }

    fun scramClientProof(
        secret: String,
        request: LanWebDeviceRelayMessage,
        response: LanWebDeviceRelayMessage,
        fingerprint: String,
    ): LanWebScramClientProof {
        val normalizedSecret = when (request.authMethod) {
            LanWebTrustSource.TEMPORARY_CODE -> secret.uppercase().filter(Char::isLetterOrDigit).also {
                require(it.length == LanWebContract.PAIRING_CODE_LENGTH) { "临时验证码格式无效" }
            }
            LanWebTrustSource.SECURITY_PASSWORD -> secret.trim().also {
                require(it.codePointCount(0, it.length) in 8..128 && it.none(Char::isISOControl)) {
                    "安全密码需为 8–128 个字符"
                }
            }
            else -> error("密码认证方式无效")
        }
        val challengeRequest = LanWebPairChallengeRequest(
            requestId = request.requestId,
            clientName = request.deviceName,
            deviceId = request.sourceDeviceId,
            devicePublicKey = request.sourcePublicKey,
            deviceFingerprint = fingerprint,
            ephemeralPublicKey = request.sourcePublicKey,
            platform = request.platform,
            issuedAt = request.issuedAt,
            authMethod = request.authMethod,
            clientNonce = request.clientNonce,
            signature = "",
        )
        val challengeResponse = LanWebPairChallengeResponse(
            sessionId = response.sessionId,
            serverNonce = response.serverNonce,
            salt = response.salt,
            iterations = response.iterations,
            expiresAt = response.expiresAt,
        )
        val salt = requireNotNull(LanWebScramCrypto.decode(response.salt, LanWebScramCrypto.SALT_BYTES))
        return try {
            LanWebScramCrypto.clientProof(
                secret = normalizedSecret,
                salt = salt,
                iterations = response.iterations,
                authMessage = LanWebScramCrypto.authMessage(challengeRequest, challengeResponse),
            )
        } finally {
            salt.fill(0)
        }
    }

    fun validateConnectionAcknowledgement(
        message: LanWebDeviceRelayMessage,
        requestId: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        targetPublicKey: String,
        now: Long = System.currentTimeMillis(),
    ) {
        require(
            message.kind == "connect_ack" && message.requestId == requestId &&
                message.sourceDeviceId == sourceDeviceId && message.targetDeviceId == targetDeviceId &&
                (message.status == "accepted" || message.status == "rejected") && validClock(message.issuedAt, now)
        ) { "设备返回的连接确认无效" }
        require(LanWebDeviceIdentity.verify(targetPublicKey, connectAckPayload(message), message.signature)) {
            "设备返回的连接确认签名无效"
        }
    }

    fun encode(message: LanWebDeviceRelayMessage): String = json.encodeToString(message).also {
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) { "公网设备中继消息过大" }
    }

    fun decode(encoded: String): LanWebDeviceRelayMessage {
        require(encoded.isNotBlank() && encoded.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) {
            "公网设备中继消息大小无效"
        }
        return json.decodeFromString<LanWebDeviceRelayMessage>(encoded).also {
            require(it.version == VERSION) { "公网设备中继协议版本不受支持" }
        }
    }

    fun validateInvite(
        message: LanWebDeviceRelayMessage,
        target: LanWebDeviceIdentitySnapshot,
        now: Long = System.currentTimeMillis(),
    ) {
        require(message.kind == "invite" && LanWebContract.requestIdPattern.matches(message.requestId)) {
            "公网设备邀请格式无效"
        }
        require(message.targetDeviceId == target.deviceId && validClock(message.issuedAt, now)) {
            "公网设备邀请目标或时间无效"
        }
        require(
            LanWebDeviceIdentity.normalizeDeviceId(message.sourceDeviceId) == message.sourceDeviceId &&
                LanWebDeviceIdentity.deviceIdForPublicKey(message.sourcePublicKey) == message.sourceDeviceId &&
                LanWebDeviceIdentity.deviceIdForPublicKey(message.ephemeralPublicKey) != null
        ) { "公网设备邀请身份无效" }
        require(decodeBase64(message.nonce, NONCE_BYTES).size == NONCE_BYTES) { "公网设备邀请随机数无效" }
        require(decodeBase64(message.ciphertext, 4_096).size >= 17) { "公网设备邀请密文无效" }
        require(LanWebDeviceIdentity.verify(message.sourcePublicKey, invitePayload(message), message.signature)) {
            "公网设备邀请签名无效"
        }
    }

    fun decryptInvitation(
        identity: LanWebDeviceIdentity,
        message: LanWebDeviceRelayMessage,
        now: Long = System.currentTimeMillis(),
    ): LanWebDeviceRelayRoom {
        validateInvite(message, identity.snapshot, now)
        val linkSecret = identity.deriveLinkSecret(
            message.ephemeralPublicKey,
            inviteContext(message.requestId, message.sourceDeviceId, message.targetDeviceId),
        )
        try {
            val nonce = decodeBase64(message.nonce, NONCE_BYTES)
            val ciphertext = decodeBase64(message.ciphertext, 4_096)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(linkSecret, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(
                inviteAad(
                    message.requestId,
                    message.sourceDeviceId,
                    message.targetDeviceId,
                    message.ephemeralPublicKey,
                )
            )
            val plain = runCatching { cipher.doFinal(ciphertext) }
                .getOrElse { error("公网设备邀请认证失败") }
            val invitation = try {
                json.decodeFromString<LanWebDeviceRelayInvitation>(plain.toString(Charsets.UTF_8))
            } finally {
                plain.fill(0)
            }
            require(invitation.version == 2) { "公网设备邀请版本不受支持" }
            require(invitation.expiresAt in now..(now + MAX_INVITATION_LIFETIME_MILLIS)) { "公网设备邀请已过期" }
            val secret = decodeBase64(invitation.secret, SECRET_BYTES)
            require(secret.size == SECRET_BYTES) { "公网设备邀请密钥长度无效" }
            try {
                LanWebDeviceTunnelProtocol.requireRoomCredentials(invitation.roomId, secret)
            } catch (error: Throwable) {
                secret.fill(0)
                throw error
            }
            return LanWebDeviceRelayRoom(invitation.roomId, secret, invitation.expiresAt)
        } finally {
            linkSecret.fill(0)
        }
    }

    fun registrationPayload(message: LanWebDeviceRelayMessage): ByteArray =
        "murong-relay-device-register-v2\n${message.deviceId}\n${message.devicePublicKey}\n${message.platform}\n${message.role}\n${message.issuedAt}\n${message.nonce}"
            .toByteArray(Charsets.UTF_8)

    fun lookupPayload(message: LanWebDeviceRelayMessage): ByteArray =
        "murong-relay-device-lookup-v2\n${message.requestId}\n${message.sourceDeviceId}\n${message.targetDeviceId}\n${message.issuedAt}"
            .toByteArray(Charsets.UTF_8)

    fun invitePayload(message: LanWebDeviceRelayMessage): ByteArray =
        "murong-relay-device-invite-v2\n${message.requestId}\n${message.sourceDeviceId}\n${message.sourcePublicKey}\n${message.targetDeviceId}\n${message.ephemeralPublicKey}\n${message.issuedAt}\n${message.nonce}\n${message.ciphertext}"
            .toByteArray(Charsets.UTF_8)

    fun inviteAckPayload(message: LanWebDeviceRelayMessage): ByteArray =
        "murong-relay-device-invite-ack-v2\n${message.requestId}\n${message.sourceDeviceId}\n${message.targetDeviceId}\n${message.status}\n${message.issuedAt}\n${message.error}"
            .toByteArray(Charsets.UTF_8)

    fun connectRequestPayload(message: LanWebDeviceRelayMessage): ByteArray =
		"murong-relay-device-connect-request-v2\n${message.requestId}\n${message.sourceDeviceId}\n${message.sourcePublicKey}\n${message.targetDeviceId}\n${message.ephemeralPublicKey}\n${message.deviceName}\n${message.platform}\n${message.issuedAt}\n${message.authMethod}\n${message.clientNonce}\n${message.authProof}"
            .toByteArray(Charsets.UTF_8)

    fun connectAckPayload(message: LanWebDeviceRelayMessage): ByteArray =
        "murong-relay-device-connect-ack-v2\n${message.requestId}\n${message.sourceDeviceId}\n${message.targetDeviceId}\n${message.status}\n${message.issuedAt}\n${message.error}\n${message.serverProof}"
            .toByteArray(Charsets.UTF_8)

    fun authBeginPayload(message: LanWebDeviceRelayMessage): ByteArray =
        "murong-relay-device-auth-begin-v2\n${message.requestId}\n${message.sourceDeviceId}\n${message.sourcePublicKey}\n${message.targetDeviceId}\n${message.deviceName}\n${message.platform}\n${message.issuedAt}\n${message.authMethod}\n${message.clientNonce}"
            .toByteArray(Charsets.UTF_8)

    fun authChallengePayload(message: LanWebDeviceRelayMessage): ByteArray =
        "murong-relay-device-auth-challenge-v2\n${message.requestId}\n${message.sourceDeviceId}\n${message.targetDeviceId}\n${message.authMethod}\n${message.clientNonce}\n${message.sessionId}\n${message.serverNonce}\n${message.iterations}\n${message.expiresAt}\n${message.salt}"
            .toByteArray(Charsets.UTF_8)

    fun inviteContext(requestId: String, sourceId: String, targetId: String): ByteArray =
        "murong-relay-v2-invite-context-v2\n$requestId\n$sourceId\n$targetId".toByteArray(Charsets.UTF_8)

    fun inviteAad(requestId: String, sourceId: String, targetId: String, ephemeralPublicKey: String): ByteArray =
        "murong-relay-v2-invite-aad-v2\n$requestId\n$sourceId\n$targetId\n$ephemeralPublicKey"
            .toByteArray(Charsets.UTF_8)

    fun tunnelUrlForDeviceRelay(deviceRelayUrl: String): String {
        val normalized = LanWebDeviceTunnelProtocol.normalizeRelayUrl(deviceRelayUrl)
        val uri = URI(normalized)
        val path = when {
            uri.path.endsWith("/relay/v2/device") -> uri.path.removeSuffix("/relay/v2/device") + "/relay/v2/tunnel"
            uri.path.endsWith("/v2/device") -> uri.path.removeSuffix("/v2/device") + "/v2/tunnel"
            else -> error("公网设备中继路径必须以 /relay/v2/device 结尾")
        }
        return URI(uri.scheme, null, uri.host, uri.port, path, null, null).toString()
    }

    fun requestId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    fun clientNonce(secureRandom: SecureRandom = SecureRandom()): String =
        ByteArray(18).also(secureRandom::nextBytes).let { value ->
            try {
                encoder.encodeToString(value)
            } finally {
                value.fill(0)
            }
        }

    fun validClock(value: Long, now: Long = System.currentTimeMillis()): Boolean =
        value >= now - CLOCK_WINDOW_MILLIS && value <= now + FUTURE_WINDOW_MILLIS

    private fun decodeBase64(value: String, maxBytes: Int): ByteArray {
        require(value.isNotBlank() && value.length <= maxBytes * 2) { "公网设备中继编码无效" }
        return runCatching { decoder.decode(value) }.getOrElse { error("公网设备中继编码无效") }.also {
            require(it.isNotEmpty() && it.size <= maxBytes) { "公网设备中继编码大小无效" }
        }
    }
}

@Serializable
internal data class LanWebDeviceRelayMessage(
    val version: Int = LanWebDeviceRelayProtocol.VERSION,
    val kind: String,
    val requestId: String = "",
    val deviceId: String = "",
    val devicePublicKey: String = "",
    val platform: String = "",
    val role: String = "",
    val deviceName: String = "",
    val authMethod: String = "",
    val clientNonce: String = "",
    val sessionId: String = "",
    val serverNonce: String = "",
    val salt: String = "",
    val iterations: Int = 0,
    val expiresAt: Long = 0,
    val authProof: String = "",
    val serverProof: String = "",
    val sourceDeviceId: String = "",
    val sourcePublicKey: String = "",
    val targetDeviceId: String = "",
    val ephemeralPublicKey: String = "",
    val issuedAt: Long,
    val nonce: String = "",
    val ciphertext: String = "",
    val status: String = "",
    val error: String = "",
    val signature: String = "",
)

@Serializable
internal data class LanWebDeviceRelayInvitation(
    val version: Int,
    val roomId: String,
    val secret: String,
    val expiresAt: Long,
)

internal data class LanWebDeviceRelayRoom(
    val roomId: String,
    val secret: ByteArray,
    val expiresAt: Long,
)
