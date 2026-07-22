package com.murong.agent.lan

import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object LanWebDeviceLinkCrypto {
    private const val LINK_KEY_BYTES = 32
    private const val SYNC_KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val MAX_TOKEN_BYTES = 128

    fun ephemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        generateKeyPair()
    }

    fun encryptBootstrap(
        linkKey: ByteArray,
        requestId: String,
        requesterDeviceId: String,
        responderDeviceId: String,
        summary: LanWebClientSummary,
        accessToken: String,
        syncKey: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): LanWebDeviceLinkEnvelope {
        require(linkKey.size == LINK_KEY_BYTES && syncKey.size == SYNC_KEY_BYTES) { "设备链路密钥长度无效" }
        val token = accessToken.toByteArray(Charsets.UTF_8)
        require(token.isNotEmpty() && token.size <= MAX_TOKEN_BYTES) { "访问凭据长度无效" }
        val plain = ByteBuffer.allocate(2 + token.size + syncKey.size)
            .putShort(token.size.toShort())
            .put(token)
            .put(syncKey)
            .array()
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(linkKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(aad(requestId, requesterDeviceId, responderDeviceId, summary))
            LanWebDeviceLinkEnvelope(
                nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(plain)),
            )
        } finally {
            plain.fill(0)
        }
    }

    fun aad(
        requestId: String,
        requesterDeviceId: String,
        responderDeviceId: String,
        summary: LanWebClientSummary,
    ): ByteArray = buildString {
        append(LanWebContract.DEVICE_LINK_ENVELOPE_VERSION)
        append('\n').append(requestId)
        append('\n').append(requesterDeviceId)
        append('\n').append(responderDeviceId)
        append('\n').append(summary.id)
        append('\n').append(summary.name)
        append('\n').append(summary.createdAt)
    }.toByteArray(Charsets.UTF_8)
}
