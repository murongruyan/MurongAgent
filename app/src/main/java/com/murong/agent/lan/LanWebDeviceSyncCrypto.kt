package com.murong.agent.lan

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object LanWebDeviceSyncCrypto {
    const val WINDOWS_TO_ANDROID = "windows_to_android"
    const val ANDROID_TO_WINDOWS = "android_to_windows"
    const val DESKTOP_HANDOFF_TO_ANDROID = "desktop_handoff_to_android"
    const val DESKTOP_HANDOFF_TO_DESKTOP = "desktop_handoff_to_desktop"
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(
        key: ByteArray,
        requestId: String,
        issuedAt: Long,
        direction: String,
        plaintext: String,
        random: SecureRandom = SecureRandom(),
    ): LanWebDeviceSyncEnvelope {
        require(key.size == KEY_BYTES) { "设备同步密钥长度无效" }
        validateMetadata(requestId, issuedAt, direction)
        val plain = plaintext.toByteArray(StandardCharsets.UTF_8)
        require(plain.size <= LanWebContract.MAX_DEVICE_SYNC_BODY_BYTES) { "设备同步内容过大" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad(requestId, issuedAt, direction))
        val ciphertext = cipher.doFinal(plain)
        plain.fill(0)
        return LanWebDeviceSyncEnvelope(
            requestId = requestId,
            issuedAt = issuedAt,
            direction = direction,
            nonce = encode(nonce),
            ciphertext = encode(ciphertext),
        )
    }

    fun decrypt(key: ByteArray, envelope: LanWebDeviceSyncEnvelope): String {
        require(key.size == KEY_BYTES) { "设备同步密钥长度无效" }
        require(envelope.version == LanWebContract.DEVICE_SYNC_ENVELOPE_VERSION) { "设备同步协议版本不受支持" }
        validateMetadata(envelope.requestId, envelope.issuedAt, envelope.direction)
        val nonce = decode(envelope.nonce, NONCE_BYTES, NONCE_BYTES)
        val ciphertext = decode(
            envelope.ciphertext,
            16,
            LanWebContract.MAX_DEVICE_SYNC_BODY_BYTES + 16,
        )
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad(envelope.requestId, envelope.issuedAt, envelope.direction))
        val plain = cipher.doFinal(ciphertext)
        return try {
            String(plain, StandardCharsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    private fun validateMetadata(requestId: String, issuedAt: Long, direction: String) {
        require(LanWebContract.requestIdPattern.matches(requestId)) { "设备同步 request_id 无效" }
        require(issuedAt > 0L) { "设备同步时间无效" }
        require(
            direction == WINDOWS_TO_ANDROID ||
                direction == ANDROID_TO_WINDOWS ||
                direction == DESKTOP_HANDOFF_TO_ANDROID ||
                direction == DESKTOP_HANDOFF_TO_DESKTOP
        ) { "设备同步方向无效" }
    }

    private fun aad(requestId: String, issuedAt: Long, direction: String): ByteArray =
        "${LanWebContract.DEVICE_SYNC_ENVELOPE_VERSION}\n$requestId\n$issuedAt\n$direction"
            .toByteArray(StandardCharsets.UTF_8)

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decode(value: String, minimum: Int, maximum: Int): ByteArray {
        require(value.isNotBlank() && value.length <= maximum * 2) { "设备同步密文编码长度无效" }
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrElse { error("设备同步密文编码无效") }
        require(decoded.size in minimum..maximum) { "设备同步密文长度无效" }
        return decoded
    }
}
