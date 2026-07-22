package com.murong.agent.lan

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps the first access token and long-lived device-sync key with the
 * out-of-band pairing code. New clients therefore never receive either secret
 * as plaintext over the phone's HTTP listener.
 */
internal object LanWebPairingCrypto {
    private const val PBKDF2_ITERATIONS = 210_000
    private const val DERIVED_KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val SYNC_KEY_BYTES = 32
    private const val MAX_ACCESS_TOKEN_BYTES = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun newSyncKey(random: SecureRandom): ByteArray = ByteArray(SYNC_KEY_BYTES).also(random::nextBytes)

    internal fun codeProof(rawPairingCode: String): String {
        val normalized = rawPairingCode.uppercase().filterNot { it == '-' || it.isWhitespace() }
        require(normalized.length == LanWebContract.PAIRING_CODE_LENGTH) { "配对码长度无效" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.US_ASCII))
        return try {
            encode(digest)
        } finally {
            digest.fill(0)
        }
    }

    internal fun matchesCodeProof(pairingSecret: ByteArray, rawProof: String?): Boolean {
        if (rawProof.isNullOrBlank()) return false
        val expected = runCatching {
            codeProof(String(pairingSecret, StandardCharsets.US_ASCII))
        }.getOrNull() ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            rawProof.toByteArray(StandardCharsets.US_ASCII),
        )
    }

    fun encryptBootstrap(
        pairingSecret: ByteArray,
        summary: LanWebClientSummary,
        accessToken: String,
        syncKey: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): LanWebSecurePairingEnvelope {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return encryptBootstrapWithParameters(
            pairingSecret = pairingSecret,
            summary = summary,
            accessToken = accessToken,
            syncKey = syncKey,
            salt = salt,
            nonce = nonce,
        )
    }

    internal fun encryptBootstrapWithParameters(
        pairingSecret: ByteArray,
        summary: LanWebClientSummary,
        accessToken: String,
        syncKey: ByteArray,
        salt: ByteArray,
        nonce: ByteArray,
    ): LanWebSecurePairingEnvelope {
        require(syncKey.size == SYNC_KEY_BYTES) { "设备同步密钥长度无效" }
        require(salt.size == SALT_BYTES && nonce.size == NONCE_BYTES) { "安全配对参数无效" }
        val token = accessToken.toByteArray(StandardCharsets.UTF_8)
        require(token.isNotEmpty() && token.size <= MAX_ACCESS_TOKEN_BYTES) { "访问凭据长度无效" }
        val plain = ByteBuffer.allocate(2 + token.size + syncKey.size)
            .putShort(token.size.toShort())
            .put(token)
            .put(syncKey)
            .array()
        val key = deriveKey(pairingSecret, salt)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(aad(summary))
            val ciphertext = cipher.doFinal(plain)
            return LanWebSecurePairingEnvelope(
                version = LanWebContract.SECURE_PAIRING_VERSION,
                salt = encode(salt),
                nonce = encode(nonce),
                ciphertext = encode(ciphertext),
            )
        } finally {
            plain.fill(0)
            key.fill(0)
        }
    }

    private fun deriveKey(pairingSecret: ByteArray, salt: ByteArray): ByteArray {
        val normalizedCode = String(pairingSecret, StandardCharsets.US_ASCII)
        require(normalizedCode.length == LanWebContract.PAIRING_CODE_LENGTH) {
            "配对码长度无效"
        }
        val specification = PBEKeySpec(
            normalizedCode.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            DERIVED_KEY_BITS,
        )
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(specification)
                .encoded
        } finally {
            specification.clearPassword()
        }
    }

    private fun aad(summary: LanWebClientSummary): ByteArray = buildString {
        append(LanWebContract.SECURE_PAIRING_VERSION)
        append('\n')
        append(summary.id)
        append('\n')
        append(summary.name)
        append('\n')
        append(summary.createdAt)
    }.toByteArray(StandardCharsets.UTF_8)

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
}
