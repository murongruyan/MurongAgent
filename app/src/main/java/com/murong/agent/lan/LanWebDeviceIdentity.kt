package com.murong.agent.lan

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class LanWebDeviceIdentitySnapshot(
    val deviceId: String,
    val displayId: String,
    val publicKey: String,
    val publicKeyFingerprint: String,
)

/** Stable application identity. The private key remains inside Android Keystore. */
class LanWebDeviceIdentity private constructor(
    private val keyPair: KeyPair,
) {
    constructor(context: Context) : this(AndroidDeviceKeyStore.loadOrCreate(context.applicationContext))

    internal constructor(keyPairProvider: () -> KeyPair) : this(keyPairProvider())

    val snapshot: LanWebDeviceIdentitySnapshot by lazy {
        val publicDer = keyPair.public.encoded
        val deviceId = deviceId(publicDer)
        LanWebDeviceIdentitySnapshot(
            deviceId = deviceId,
            displayId = deviceId.chunked(4).joinToString("-"),
            publicKey = encode(publicDer),
            publicKeyFingerprint = encode(sha256(publicDer)),
        )
    }

    fun sign(payload: ByteArray): String {
        require(payload.isNotEmpty() && payload.size <= MAX_SIGNED_BYTES) { "设备签名内容大小无效" }
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(keyPair.private)
        signature.update(payload)
        return encode(signature.sign())
    }

    fun deriveLinkSecret(peerPublicKey: String, context: ByteArray): ByteArray {
        return deriveLinkSecret(keyPair, peerPublicKey, context)
    }

    companion object {
        internal fun publicKey(keyPair: KeyPair): String = encode(keyPair.public.encoded)

        internal fun deriveLinkSecret(keyPair: KeyPair, peerPublicKey: String, context: ByteArray): ByteArray {
        require(context.isNotEmpty() && context.size <= MAX_CONTEXT_BYTES) { "设备链路上下文无效" }
        val peer = decodePublicKey(peerPublicKey)
        val agreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM)
        agreement.init(keyPair.private)
        agreement.doPhase(peer, true)
        val sharedSecret = agreement.generateSecret()
        return try {
            hkdfSha256(sharedSecret, context, LINK_INFO, LINK_SECRET_BYTES)
        } finally {
            sharedSecret.fill(0)
        }
    }
        private const val DEVICE_ID_BYTES = 10
        private const val LINK_SECRET_BYTES = 32
        private const val MAX_SIGNED_BYTES = 64 * 1024
        private const val MAX_CONTEXT_BYTES = 1024
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val KEY_AGREEMENT_ALGORITHM = "ECDH"
        private const val BASE32_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private val LINK_INFO = "murong-device-link-v1".toByteArray(Charsets.US_ASCII)

        fun normalizeDeviceId(raw: String): String? = raw.uppercase()
            .filterNot { it == '-' || it.isWhitespace() }
            .takeIf { it.length == DEVICE_ID_BYTES * 8 / 5 && it.all { char -> char in BASE32_ALPHABET } }

        fun verify(publicKey: String, payload: ByteArray, signature: String): Boolean = runCatching {
            require(payload.isNotEmpty() && payload.size <= MAX_SIGNED_BYTES)
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(decodePublicKey(publicKey))
            verifier.update(payload)
            verifier.verify(decode(signature, MAX_SIGNATURE_BYTES))
        }.getOrDefault(false)

        fun deviceIdForPublicKey(publicKey: String): String? = runCatching {
            deviceId(decodePublicKey(publicKey).encoded)
        }.getOrNull()

        fun fingerprintForPublicKey(publicKey: String): String? = runCatching {
            encode(sha256(decodePublicKey(publicKey).encoded))
        }.getOrNull()

        private fun decodePublicKey(encoded: String): PublicKey {
            val der = decode(encoded, MAX_PUBLIC_KEY_BYTES)
            val key = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)
                .generatePublic(X509EncodedKeySpec(der))
            require(key.algorithm.equals(KeyProperties.KEY_ALGORITHM_EC, ignoreCase = true))
            return key
        }

        private fun deviceId(publicDer: ByteArray): String {
            val digest = sha256(publicDer)
            return try {
                base32(digest.copyOf(DEVICE_ID_BYTES))
            } finally {
                digest.fill(0)
            }
        }

        private fun base32(value: ByteArray): String {
            var buffer = 0
            var bits = 0
            return buildString((value.size * 8 + 4) / 5) {
                value.forEach { byte ->
                    buffer = (buffer shl 8) or (byte.toInt() and 0xff)
                    bits += 8
                    while (bits >= 5) {
                        bits -= 5
                        append(BASE32_ALPHABET[(buffer ushr bits) and 31])
                    }
                }
                if (bits > 0) append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 31])
            }
        }

        private fun hkdfSha256(
            inputKeyMaterial: ByteArray,
            salt: ByteArray,
            info: ByteArray,
            size: Int,
        ): ByteArray {
            val extract = Mac.getInstance("HmacSHA256")
            extract.init(SecretKeySpec(salt, "HmacSHA256"))
            val pseudoRandomKey = extract.doFinal(inputKeyMaterial)
            return try {
                val expand = Mac.getInstance("HmacSHA256")
                expand.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
                expand.update(info)
                expand.update(1.toByte())
                expand.doFinal().copyOf(size)
            } finally {
                pseudoRandomKey.fill(0)
            }
        }

        private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

        private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

        private fun decode(value: String, maxBytes: Int): ByteArray {
            require(value.isNotBlank() && value.length <= maxBytes * 2) { "设备密钥编码无效" }
            return Base64.getUrlDecoder().decode(value).also {
                require(it.isNotEmpty() && it.size <= maxBytes) { "设备密钥大小无效" }
            }
        }

        private const val MAX_PUBLIC_KEY_BYTES = 512
        private const val MAX_SIGNATURE_BYTES = 256
    }
}

private object AndroidDeviceKeyStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "murong_device_identity_p256_v1"

    fun loadOrCreate(@Suppress("UNUSED_PARAMETER") context: Context): KeyPair {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = store.getKey(KEY_ALIAS, null)
        val publicKey = store.getCertificate(KEY_ALIAS)?.publicKey
        if (privateKey != null && publicKey != null) return KeyPair(publicKey, privateKey as java.security.PrivateKey)

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKeyPair()
    }
}
