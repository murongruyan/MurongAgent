package com.murong.agent.lan

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.util.Base64 as JvmBase64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class LanWebAdbPairingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun putChallenge(challenge: ByteArray, expiresAt: Long = System.currentTimeMillis() + TTL_MILLIS) {
        require(challenge.size == CHALLENGE_BYTES) { "ADB 挑战长度无效" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(challenge)
        check(
            prefs.edit()
                .putString(VALUE_KEY, listOf(ENCODING_VERSION, encode(cipher.iv), encode(ciphertext)).joinToString(":"))
                .putLong(EXPIRES_KEY, expiresAt)
                .commit()
        ) { "无法保存 ADB 配对挑战" }
    }

    fun consumeProof(request: LanWebConnectionRequest, now: Long = System.currentTimeMillis()): Boolean {
        val challenge = readChallenge(now) ?: return false
        return try {
            val expected = proofForChallenge(challenge, request).toByteArray(Charsets.US_ASCII)
            val actual = request.authProof.toByteArray(Charsets.US_ASCII)
            val matched = actual.size == expected.size && MessageDigest.isEqual(actual, expected)
            expected.fill(0)
            if (matched) clear()
            matched
        } finally {
            challenge.fill(0)
        }
    }

    fun hasPending(now: Long = System.currentTimeMillis()): Boolean =
        readChallenge(now)?.let { challenge ->
            challenge.fill(0)
            true
        } ?: false

    fun clear() {
        prefs.edit().remove(VALUE_KEY).remove(EXPIRES_KEY).commit()
    }

    private fun readChallenge(now: Long): ByteArray? {
        val expiresAt = prefs.getLong(EXPIRES_KEY, 0L)
        if (expiresAt <= now) {
            clear()
            return null
        }
        val encoded = prefs.getString(VALUE_KEY, null) ?: return null
        val challenge = runCatching {
            val parts = encoded.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == ENCODING_VERSION)
            val nonce = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ciphertext).also { require(it.size == CHALLENGE_BYTES) }
        }.getOrNull() ?: run {
            clear()
            return null
        }
        return challenge
    }

    companion object {
        private const val PREFS_NAME = "murong_lan_adb_pairing"
        private const val VALUE_KEY = "challenge"
        private const val EXPIRES_KEY = "expiresAt"
        private const val KEY_ALIAS = "murong_lan_adb_pairing_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENCODING_VERSION = "v1"
        private const val GCM_TAG_BITS = 128
        private const val CHALLENGE_BYTES = 32
        private const val TTL_MILLIS = 2 * 60 * 1000L
        private const val PROOF_CONTEXT = "murong-adb-device-link-v1"

        internal fun proofForChallenge(challenge: ByteArray, request: LanWebConnectionRequest): String {
            require(challenge.size == CHALLENGE_BYTES) { "ADB 挑战长度无效" }
            val payload = buildString {
                append(PROOF_CONTEXT)
                append('\n').append(request.requestId)
                append('\n').append(request.deviceId)
                append('\n').append(request.ephemeralPublicKey)
            }.toByteArray(Charsets.UTF_8)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(challenge, "HmacSHA256"))
            val proof = mac.doFinal(payload)
            return try {
                JvmBase64.getUrlEncoder().withoutPadding().encodeToString(proof)
            } finally {
                proof.fill(0)
            }
        }

        private fun key(): SecretKey {
            val store = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
            generator.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        }

        private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    }
}
