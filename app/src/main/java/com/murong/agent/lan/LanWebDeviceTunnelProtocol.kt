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

internal object LanWebDeviceTunnelProtocol {
    const val VERSION = 2
    const val SUBPROTOCOL = "murong-device-tunnel-v2"
    const val ROLE_PHONE = "phone"
    const val ROLE_DESKTOP = "desktop"
    const val OFFICIAL_TUNNEL_URL = "wss://murongagent.rl1.cc/relay/v2/tunnel"
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

    fun requireRoomCredentials(roomId: String, secret: ByteArray) {
        require(validRoomId(roomId)) { "加密隧道房间 ID 无效" }
        require(secret.size == SECRET_BYTES) { "加密隧道端到端密钥长度无效" }
    }

    fun normalizeRelayUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: error("加密隧道地址无效")
        require(uri.userInfo == null && !uri.host.isNullOrBlank() && uri.query == null && uri.fragment == null) {
            "加密隧道地址不能包含账号、查询参数或片段"
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "wss" || scheme == "ws" && isLoopbackHost(uri.host)) {
            "公网加密隧道必须使用 wss://；ws:// 仅允许本机测试"
        }
        val normalizedPath = uri.path?.takeIf { it.isNotBlank() } ?: "/v2/tunnel"
        return URI(scheme, null, uri.host, uri.port, normalizedPath, null, null).toString()
    }

    fun encrypt(
        secret: ByteArray,
        roomId: String,
        senderRole: String,
        message: LanWebDeviceTunnelMessage,
        secureRandom: SecureRandom = SecureRandom(),
        now: Long = System.currentTimeMillis(),
    ): ByteArray {
        requireRoomCredentials(roomId, secret)
        validateMessage(message, now)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad(roomId, senderRole))
        val ciphertext = cipher.doFinal(json.encodeToString(message).toByteArray(Charsets.UTF_8))
        val encoded = json.encodeToString(
            LanWebDeviceTunnelCipherEnvelope(
                version = VERSION,
                nonce = base64Encoder.encodeToString(nonce),
                ciphertext = base64Encoder.encodeToString(ciphertext),
            )
        ).toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_FRAME_BYTES) { "加密隧道帧超过大小限制" }
        return encoded
    }

    fun decrypt(
        secret: ByteArray,
        roomId: String,
        senderRole: String,
        encoded: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): LanWebDeviceTunnelMessage {
        requireRoomCredentials(roomId, secret)
        require(encoded.isNotEmpty() && encoded.size <= MAX_FRAME_BYTES) { "加密隧道帧大小无效" }
        val envelope = runCatching {
            json.decodeFromString<LanWebDeviceTunnelCipherEnvelope>(encoded.toString(Charsets.UTF_8))
        }.getOrElse { error("加密隧道帧格式无效") }
        require(envelope.version == VERSION) { "加密隧道帧版本不受支持" }
        val nonce = runCatching { base64Decoder.decode(envelope.nonce) }.getOrNull()
        require(nonce?.size == NONCE_BYTES) { "加密隧道随机数无效" }
        val ciphertext = runCatching { base64Decoder.decode(envelope.ciphertext) }.getOrNull()
        require(ciphertext != null && ciphertext.size >= 16) { "加密隧道密文无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad(roomId, senderRole))
        val plain = runCatching { cipher.doFinal(ciphertext) }
            .getOrElse { error("加密隧道密文认证失败") }
        val message = runCatching {
            json.decodeFromString<LanWebDeviceTunnelMessage>(plain.toString(Charsets.UTF_8))
        }.getOrElse { error("加密隧道消息格式无效") }
        validateMessage(message, now)
        return message
    }

    fun newMessage(requestId: String, kind: String, now: Long = System.currentTimeMillis()) =
        LanWebDeviceTunnelMessage(
            version = VERSION,
            messageId = "relaymsg-${UUID.randomUUID()}",
            requestId = requestId,
            kind = kind,
            issuedAt = now,
        )

    fun validateMessage(message: LanWebDeviceTunnelMessage, now: Long = System.currentTimeMillis()) {
        require(message.version == VERSION) { "加密隧道消息版本不受支持" }
        require(LanWebContract.requestIdPattern.matches(message.messageId)) { "加密隧道消息 ID 无效" }
        require(LanWebContract.requestIdPattern.matches(message.requestId)) { "加密隧道请求 ID 无效" }
        require(message.issuedAt in (now - CLOCK_SKEW_MILLIS)..(now + CLOCK_SKEW_MILLIS)) {
            "加密隧道消息时间无效"
        }
        when (message.kind) {
            "request_start" -> {
                require(message.method == "GET" || message.method == "POST") { "加密隧道 HTTP 方法无效" }
                require(
                    message.path.startsWith("/api/v1/") && message.path.length <= 256 &&
                        message.path.none { it == '?' || it == '#' || it == '\u0000' }
                ) { "加密隧道请求路径无效" }
                validateHeaders(message.headers, request = true)
            }
            "request_chunk", "response_chunk" -> {
                val chunk = runCatching { base64Decoder.decode(message.chunk) }.getOrNull()
                require(chunk != null && chunk.isNotEmpty() && chunk.size <= CHUNK_BYTES) {
                    "加密隧道数据块无效"
                }
            }
            "request_end", "response_end", "cancel" -> Unit
            "response_start" -> {
                require(message.status in 100..599) { "加密隧道响应状态无效" }
                validateHeaders(message.headers, request = false)
            }
            "error" -> require(message.error.isNotBlank() && message.error.length <= 500) {
                "加密隧道错误消息无效"
            }
            else -> error("加密隧道消息类型无效")
        }
    }

    fun filterHeaders(headers: Map<String, List<String>>, request: Boolean): Map<String, List<String>> {
        return headers.filter { (name, values) ->
            runCatching { validateHeaders(mapOf(name to values), request) }.isSuccess
        }
    }

    private fun validateHeaders(headers: Map<String, List<String>>, request: Boolean) {
        require(headers.size <= 12) { "加密隧道请求头数量过多" }
        val allowed = if (request) {
            setOf("accept", "authorization", "content-type", "last-event-id")
        } else {
            setOf("cache-control", "content-type", "set-cookie")
        }
        headers.forEach { (name, values) ->
            require(name.trim().lowercase() in allowed && values.isNotEmpty() && values.size <= 4) {
                "加密隧道请求头无效"
            }
            require(values.all { it.length <= 8192 && it.none { char -> char == '\r' || char == '\n' || char == '\u0000' } }) {
                "加密隧道请求头值无效"
            }
        }
    }

    private fun aad(roomId: String, senderRole: String): ByteArray {
        require(validRoomId(roomId)) { "加密隧道房间 ID 无效" }
        require(senderRole == ROLE_PHONE || senderRole == ROLE_DESKTOP) { "加密隧道发送角色无效" }
        return "$SUBPROTOCOL|$VERSION|$roomId|$senderRole".toByteArray(Charsets.UTF_8)
    }

    private fun validRoomId(roomId: String): Boolean =
        runCatching { base64Decoder.decode(roomId.trim()).size == ROOM_BYTES }.getOrDefault(false)

    private fun isLoopbackHost(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("[").removeSuffix("]")
        return normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1"
    }
}

@Serializable
internal data class LanWebDeviceTunnelCipherEnvelope(
    val version: Int,
    val nonce: String,
    val ciphertext: String,
)

@Serializable
internal data class LanWebDeviceTunnelMessage(
    val version: Int = LanWebDeviceTunnelProtocol.VERSION,
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

internal class LanWebDeviceTunnelReplayCache(
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
