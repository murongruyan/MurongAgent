package com.murong.agent.lan

import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object LanWebCloudRelayProtocol {
    const val VERSION = 1
    const val SUBPROTOCOL = "murong-cloud-relay-v1"
    const val ROLE_PHONE = "phone"
    const val ROLE_DESKTOP = "desktop"
    const val SHARE_PREFIX = "MR1"
    const val OFFICIAL_RELAY_URL = "wss://murongagent.rl1.cc/relay/v1/connect"
    const val LOOPBACK_PORT = 8766
    const val CHUNK_BYTES = 48 * 1024
    const val MAX_BODY_BYTES = 9 * 1024 * 1024
    const val MAX_FRAME_BYTES = 256 * 1024

    private const val ROOM_BYTES = 16
    private const val SECRET_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val CLOCK_SKEW_MILLIS = 5 * 60 * 1_000L
    private val base64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val base64Decoder = Base64.getUrlDecoder()
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

    fun generateShare(secureRandom: SecureRandom = SecureRandom()): LanWebCloudRelayShare {
        val room = ByteArray(ROOM_BYTES).also(secureRandom::nextBytes)
        val secret = ByteArray(SECRET_BYTES).also(secureRandom::nextBytes)
        val roomId = base64Encoder.encodeToString(room)
        return LanWebCloudRelayShare(
            roomId = roomId,
            secret = secret,
            code = formatShareCode(roomId, secret),
        )
    }

    fun formatShareCode(roomId: String, secret: ByteArray): String {
        require(validRoomId(roomId)) { "云中继房间 ID 无效" }
        require(secret.size == SECRET_BYTES) { "云中继端到端密钥长度无效" }
        return "$SHARE_PREFIX.$roomId.${base64Encoder.encodeToString(secret)}"
    }

    fun parseShareCode(raw: String): LanWebCloudRelayShare {
        val parts = raw.trim().split('.')
        require(parts.size == 3 && parts[0] == SHARE_PREFIX) { "云中继连接码格式无效" }
        val room = runCatching { base64Decoder.decode(parts[1]) }.getOrNull()
        require(room?.size == ROOM_BYTES) { "云中继房间 ID 无效" }
        val secret = runCatching { base64Decoder.decode(parts[2]) }.getOrNull()
        require(secret?.size == SECRET_BYTES) { "云中继端到端密钥无效" }
        return LanWebCloudRelayShare(parts[1], secret, raw.trim())
    }

    fun normalizeRelayUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: error("云中继地址无效")
        require(uri.userInfo == null && !uri.host.isNullOrBlank() && uri.query == null && uri.fragment == null) {
            "云中继地址不能包含账号、查询参数或片段"
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "wss" || scheme == "ws" && isLoopbackHost(uri.host)) {
            "公网云中继必须使用 wss://；ws:// 仅允许本机测试"
        }
        val normalizedPath = uri.path?.takeIf { it.isNotBlank() } ?: "/v1/connect"
        return URI(scheme, null, uri.host, uri.port, normalizedPath, null, null).toString()
    }

    fun encrypt(
        secret: ByteArray,
        roomId: String,
        senderRole: String,
        message: LanWebCloudRelayTunnelMessage,
        secureRandom: SecureRandom = SecureRandom(),
        now: Long = System.currentTimeMillis(),
    ): ByteArray {
        require(secret.size == SECRET_BYTES) { "云中继端到端密钥长度无效" }
        validateMessage(message, now)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad(roomId, senderRole))
        val ciphertext = cipher.doFinal(json.encodeToString(message).toByteArray(Charsets.UTF_8))
        val encoded = json.encodeToString(
            LanWebCloudRelayCipherEnvelope(
                version = VERSION,
                nonce = base64Encoder.encodeToString(nonce),
                ciphertext = base64Encoder.encodeToString(ciphertext),
            )
        ).toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_FRAME_BYTES) { "云中继加密帧超过大小限制" }
        return encoded
    }

    fun decrypt(
        secret: ByteArray,
        roomId: String,
        senderRole: String,
        encoded: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): LanWebCloudRelayTunnelMessage {
        require(secret.size == SECRET_BYTES) { "云中继端到端密钥长度无效" }
        require(encoded.isNotEmpty() && encoded.size <= MAX_FRAME_BYTES) { "云中继加密帧大小无效" }
        val envelope = runCatching {
            json.decodeFromString<LanWebCloudRelayCipherEnvelope>(encoded.toString(Charsets.UTF_8))
        }.getOrElse { error("云中继加密帧格式无效") }
        require(envelope.version == VERSION) { "云中继加密帧版本不受支持" }
        val nonce = runCatching { base64Decoder.decode(envelope.nonce) }.getOrNull()
        require(nonce?.size == NONCE_BYTES) { "云中继随机数无效" }
        val ciphertext = runCatching { base64Decoder.decode(envelope.ciphertext) }.getOrNull()
        require(ciphertext != null && ciphertext.size >= 16) { "云中继密文无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad(roomId, senderRole))
        val plain = runCatching { cipher.doFinal(ciphertext) }
            .getOrElse { error("云中继密文认证失败") }
        val message = runCatching {
            json.decodeFromString<LanWebCloudRelayTunnelMessage>(plain.toString(Charsets.UTF_8))
        }.getOrElse { error("云中继消息格式无效") }
        validateMessage(message, now)
        return message
    }

    fun newMessage(requestId: String, kind: String, now: Long = System.currentTimeMillis()) =
        LanWebCloudRelayTunnelMessage(
            version = VERSION,
            messageId = "relaymsg-${UUID.randomUUID()}",
            requestId = requestId,
            kind = kind,
            issuedAt = now,
        )

    fun validateMessage(message: LanWebCloudRelayTunnelMessage, now: Long = System.currentTimeMillis()) {
        require(message.version == VERSION) { "云中继消息版本不受支持" }
        require(LanWebContract.requestIdPattern.matches(message.messageId)) { "云中继消息 ID 无效" }
        require(LanWebContract.requestIdPattern.matches(message.requestId)) { "云中继请求 ID 无效" }
        require(message.issuedAt in (now - CLOCK_SKEW_MILLIS)..(now + CLOCK_SKEW_MILLIS)) {
            "云中继消息时间无效"
        }
        when (message.kind) {
            "request_start" -> {
                require(message.method == "GET" || message.method == "POST") { "云中继 HTTP 方法无效" }
                require(
                    message.path.startsWith("/api/v1/") && message.path.length <= 256 &&
                        message.path.none { it == '?' || it == '#' || it == '\u0000' }
                ) { "云中继请求路径无效" }
                validateHeaders(message.headers, request = true)
            }
            "request_chunk", "response_chunk" -> {
                val chunk = runCatching { base64Decoder.decode(message.chunk) }.getOrNull()
                require(chunk != null && chunk.isNotEmpty() && chunk.size <= CHUNK_BYTES) {
                    "云中继数据块无效"
                }
            }
            "request_end", "response_end", "cancel" -> Unit
            "response_start" -> {
                require(message.status in 100..599) { "云中继响应状态无效" }
                validateHeaders(message.headers, request = false)
            }
            "error" -> require(message.error.isNotBlank() && message.error.length <= 500) {
                "云中继错误消息无效"
            }
            else -> error("云中继消息类型无效")
        }
    }

    fun filterHeaders(headers: Map<String, List<String>>, request: Boolean): Map<String, List<String>> {
        return headers.filter { (name, values) ->
            runCatching { validateHeaders(mapOf(name to values), request) }.isSuccess
        }
    }

    private fun validateHeaders(headers: Map<String, List<String>>, request: Boolean) {
        require(headers.size <= 12) { "云中继请求头数量过多" }
        val allowed = if (request) {
            setOf("accept", "authorization", "content-type", "last-event-id")
        } else {
            setOf("cache-control", "content-type", "set-cookie")
        }
        headers.forEach { (name, values) ->
            require(name.trim().lowercase() in allowed && values.isNotEmpty() && values.size <= 4) {
                "云中继请求头无效"
            }
            require(values.all { it.length <= 8192 && it.none { char -> char == '\r' || char == '\n' || char == '\u0000' } }) {
                "云中继请求头值无效"
            }
        }
    }

    private fun aad(roomId: String, senderRole: String): ByteArray {
        require(validRoomId(roomId)) { "云中继房间 ID 无效" }
        require(senderRole == ROLE_PHONE || senderRole == ROLE_DESKTOP) { "云中继发送角色无效" }
        return "$SUBPROTOCOL|$VERSION|$roomId|$senderRole".toByteArray(Charsets.UTF_8)
    }

    private fun validRoomId(roomId: String): Boolean =
        runCatching { base64Decoder.decode(roomId.trim()).size == ROOM_BYTES }.getOrDefault(false)

    private fun isLoopbackHost(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("[").removeSuffix("]")
        return normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1"
    }
}

internal data class LanWebCloudRelayShare(
    val roomId: String,
    val secret: ByteArray,
    val code: String,
)

@Serializable
internal data class LanWebCloudRelayCipherEnvelope(
    val version: Int,
    val nonce: String,
    val ciphertext: String,
)

@Serializable
internal data class LanWebCloudRelayTunnelMessage(
    val version: Int = LanWebCloudRelayProtocol.VERSION,
    val messageId: String,
    val requestId: String,
    val kind: String,
    val issuedAt: Long,
    val method: String = "",
    val path: String = "",
    val headers: Map<String, List<String>> = emptyMap(),
    val status: Int = 0,
    val chunk: String = "",
    val error: String = "",
)

internal class LanWebCloudRelayReplayCache(
    private val maxEntries: Int = 4_096,
    private val windowMillis: Long = 10 * 60 * 1_000L,
) {
    private val entries = linkedMapOf<String, Long>()

    @Synchronized
    fun claim(messageId: String, issuedAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        val cutoff = now - windowMillis
        entries.entries.removeAll { it.value <= cutoff }
        while (entries.size >= maxEntries) entries.remove(entries.keys.first())
        if (messageId in entries) return false
        entries[messageId] = issuedAt
        return true
    }
}
