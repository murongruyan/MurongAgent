package com.murong.agent.lan

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class LanWebScramVerifier(
    val salt: ByteArray,
    val iterations: Int,
    val storedKey: ByteArray,
    val serverKey: ByteArray,
) {
    fun copySecure(): LanWebScramVerifier = LanWebScramVerifier(
        salt = salt.copyOf(),
        iterations = iterations,
        storedKey = storedKey.copyOf(),
        serverKey = serverKey.copyOf(),
    )

    fun clear() {
        salt.fill(0)
        storedKey.fill(0)
        serverKey.fill(0)
    }
}

internal data class LanWebScramClientProof(
    val proof: String,
    val expectedServerProof: String,
)

internal object LanWebScramCrypto {
    const val ITERATIONS = 210_000
    const val SALT_BYTES = 16
    const val PROOF_BYTES = 32

    private val CLIENT_KEY_LABEL = "Client Key".toByteArray(Charsets.US_ASCII)
    private val SERVER_KEY_LABEL = "Server Key".toByteArray(Charsets.US_ASCII)

    fun createVerifier(secret: String, salt: ByteArray, iterations: Int = ITERATIONS): LanWebScramVerifier {
        require(salt.size == SALT_BYTES) { "SCRAM 盐长度无效" }
        require(iterations in 100_000..1_000_000) { "SCRAM 迭代次数无效" }
        val password = secret.toByteArray(Charsets.UTF_8)
        val saltedPassword = pbkdf2Sha256(password, salt, iterations)
        password.fill(0)
        return try {
            val clientKey = hmacSha256(saltedPassword, CLIENT_KEY_LABEL)
            val storedKey = sha256(clientKey)
            clientKey.fill(0)
            LanWebScramVerifier(
                salt = salt.copyOf(),
                iterations = iterations,
                storedKey = storedKey,
                serverKey = hmacSha256(saltedPassword, SERVER_KEY_LABEL),
            )
        } finally {
            saltedPassword.fill(0)
        }
    }

    fun clientProof(
        secret: String,
        salt: ByteArray,
        iterations: Int,
        authMessage: ByteArray,
    ): LanWebScramClientProof {
        require(authMessage.isNotEmpty() && authMessage.size <= 16 * 1024) { "SCRAM 认证上下文无效" }
        val verifier = createVerifier(secret, salt, iterations)
        val password = secret.toByteArray(Charsets.UTF_8)
        val saltedPassword = pbkdf2Sha256(password, salt, iterations)
        password.fill(0)
        return try {
            val clientKey = hmacSha256(saltedPassword, CLIENT_KEY_LABEL)
            val clientSignature = hmacSha256(verifier.storedKey, authMessage)
            val proof = xor(clientKey, clientSignature)
            clientKey.fill(0)
            clientSignature.fill(0)
            val serverProof = hmacSha256(verifier.serverKey, authMessage)
            try {
                LanWebScramClientProof(encode(proof), encode(serverProof))
            } finally {
                proof.fill(0)
                serverProof.fill(0)
            }
        } finally {
            saltedPassword.fill(0)
            verifier.clear()
        }
    }

    fun verifyClientProof(
        verifier: LanWebScramVerifier,
        authMessage: ByteArray,
        rawProof: String,
    ): String? {
        if (authMessage.isEmpty() || authMessage.size > 16 * 1024) return null
        val proof = decode(rawProof, PROOF_BYTES) ?: return null
        return try {
            val clientSignature = hmacSha256(verifier.storedKey, authMessage)
            val clientKey = xor(proof, clientSignature)
            clientSignature.fill(0)
            val recoveredStoredKey = sha256(clientKey)
            clientKey.fill(0)
            val valid = MessageDigest.isEqual(recoveredStoredKey, verifier.storedKey)
            recoveredStoredKey.fill(0)
            if (!valid) {
                null
            } else {
                val serverProof = hmacSha256(verifier.serverKey, authMessage)
                try {
                    encode(serverProof)
                } finally {
                    serverProof.fill(0)
                }
            }
        } finally {
            proof.fill(0)
        }
    }

    fun authMessage(
        request: LanWebPairChallengeRequest,
        response: LanWebPairChallengeResponse,
    ): ByteArray = buildString {
        append(LanWebContract.SCRAM_PAIRING_VERSION)
        append('\n').append(response.sessionId)
        append('\n').append(request.requestId)
        append('\n').append(request.authMethod)
        append('\n').append(request.clientName.trim())
        append('\n').append(request.deviceId)
        append('\n').append(request.devicePublicKey)
        append('\n').append(request.deviceFingerprint)
        append('\n').append(request.ephemeralPublicKey)
        append('\n').append(request.platform)
        append('\n').append(request.issuedAt)
        append('\n').append(request.clientNonce)
        append('\n').append(response.serverNonce)
        append('\n').append(response.salt)
        append('\n').append(response.iterations)
    }.toByteArray(Charsets.UTF_8)

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val firstBlock = ByteBuffer.allocate(salt.size + 4)
            .put(salt)
            .putInt(1)
            .array()
        var current = mac.doFinal(firstBlock)
        firstBlock.fill(0)
        val output = current.copyOf()
        repeat(iterations - 1) {
            val next = mac.doFinal(current)
            current.fill(0)
            current = next
            for (index in output.indices) output[index] = (output[index].toInt() xor current[index].toInt()).toByte()
        }
        current.fill(0)
        return output
    }

    private fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value)
    }

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private fun xor(left: ByteArray, right: ByteArray): ByteArray {
        require(left.size == right.size)
        return ByteArray(left.size) { index -> (left[index].toInt() xor right[index].toInt()).toByte() }
    }

    fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    fun decode(value: String, expectedBytes: Int): ByteArray? = runCatching {
        require(value.length <= expectedBytes * 2)
        Base64.getUrlDecoder().decode(value).takeIf { it.size == expectedBytes }
    }.getOrNull()
}
