package com.murong.agent.lan

import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanWebPairingAuthenticatorTest {
    @Test
    fun temporaryCodeUsesNonceBoundProofAndRotatesAfterSuccess() {
        val directory = createTempDirectory("murong-scram-temp-").toFile()
        try {
            val store = LanWebPairingAuthenticator(File(directory, "pairing.json"))
            val now = 1_784_700_000_000L
            val code = store.beginTemporaryCode(now)
            val identity = testIdentity()
            val challengeRequest = signedChallenge(identity, "connect-temp", LanWebTrustSource.TEMPORARY_CODE, now)
            val challenge = store.beginChallenge(challengeRequest, "192.168.1.10", now).getOrThrow()
            val authMessage = LanWebScramCrypto.authMessage(challengeRequest, challenge)
            val salt = requireNotNull(LanWebScramCrypto.decode(challenge.salt, LanWebScramCrypto.SALT_BYTES))
            val client = LanWebScramCrypto.clientProof(
                code.value.replace("-", ""),
                salt,
                challenge.iterations,
                authMessage,
            )
            salt.fill(0)
            authMessage.fill(0)
            val finalRequest = finalRequest(challengeRequest, "${challenge.sessionId}.${client.proof}")
            val authenticated = store.authenticate(finalRequest, now + 1_000L)
            assertEquals(LanWebTrustSource.TEMPORARY_CODE, authenticated?.trustSource)
            assertEquals(client.expectedServerProof, authenticated?.serverProof)
            assertFalse(store.snapshot(now + 1_000L).temporaryCodeAvailable)
            assertNull(store.authenticate(finalRequest, now + 1_001L), "SCRAM proof must be one-time")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun repeatedTemporaryCodeFailuresInvalidateCodeAndStartCooldown() {
        val directory = createTempDirectory("murong-scram-cooldown-").toFile()
        try {
            val store = LanWebPairingAuthenticator(File(directory, "pairing.json"))
            val now = 1_784_700_000_000L
            store.beginTemporaryCode(now)
            val identity = testIdentity()
            repeat(5) { index ->
                val request = signedChallenge(identity, "connect-failure-$index", LanWebTrustSource.TEMPORARY_CODE, now + index)
                val challenge = store.beginChallenge(request, "192.168.1.20", now + index).getOrThrow()
                val wrongProof = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { index.toByte() })
                assertNull(store.authenticate(finalRequest(request, "${challenge.sessionId}.$wrongProof"), now + index))
            }
            val snapshot = store.snapshot(now + 10L)
            assertFalse(snapshot.temporaryCodeAvailable)
            assertNotNull(snapshot.cooldownUntil)
            val expected = assertFailsWith<IllegalArgumentException> { store.beginTemporaryCode(now + 10L) }
            assertTrue(expected.message.orEmpty().contains("冷却"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun securityPasswordPersistsOnlyVerifierAndAuthenticatesAfterRestart() {
        val directory = createTempDirectory("murong-scram-password-").toFile()
        try {
            val file = File(directory, "pairing.json")
            val now = 1_784_700_000_000L
            LanWebPairingAuthenticator(file).setSecurityPassword("安全密码-Secret123", now)
            val persisted = file.readText()
            assertFalse(persisted.contains("安全密码"))
            assertTrue(persisted.contains("storedKey"))
            assertTrue(persisted.contains("serverKey"))
            val restarted = LanWebPairingAuthenticator(file)
            assertTrue(restarted.snapshot(now).securityPasswordConfigured)
            val identity = testIdentity()
            val request = signedChallenge(identity, "connect-password", LanWebTrustSource.SECURITY_PASSWORD, now)
            val challenge = restarted.beginChallenge(request, "192.168.1.30", now).getOrThrow()
            val salt = requireNotNull(LanWebScramCrypto.decode(challenge.salt, LanWebScramCrypto.SALT_BYTES))
            val authMessage = LanWebScramCrypto.authMessage(request, challenge)
            val client = LanWebScramCrypto.clientProof("安全密码-Secret123", salt, challenge.iterations, authMessage)
            salt.fill(0)
            authMessage.fill(0)
            val authenticated = restarted.authenticate(
                finalRequest(request, "${challenge.sessionId}.${client.proof}"),
                now + 1_000L,
            )
            assertEquals(LanWebTrustSource.SECURITY_PASSWORD, authenticated?.trustSource)
            assertEquals(client.expectedServerProof, authenticated?.serverProof)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun signedChallenge(
        identity: LanWebDeviceIdentity,
        requestId: String,
        method: String,
        issuedAt: Long,
    ): LanWebPairChallengeRequest {
        val snapshot = identity.snapshot
        val unsigned = LanWebPairChallengeRequest(
            requestId = requestId.padEnd(8, '0'),
            clientName = "Murong Desktop",
            deviceId = snapshot.deviceId,
            devicePublicKey = snapshot.publicKey,
            deviceFingerprint = snapshot.publicKeyFingerprint,
            ephemeralPublicKey = LanWebDeviceIdentity.publicKey(newKeyPair()),
            platform = "windows",
            issuedAt = issuedAt,
            authMethod = method,
            clientNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18) { (it + requestId.length).toByte() }),
            signature = "",
        )
        return unsigned.copy(signature = identity.sign(LanWebPairingAuthenticator.pairChallengeSignaturePayload(unsigned)))
    }

    private fun finalRequest(challenge: LanWebPairChallengeRequest, proof: String) = LanWebConnectionRequest(
        requestId = challenge.requestId,
        clientName = challenge.clientName,
        deviceId = challenge.deviceId,
        devicePublicKey = challenge.devicePublicKey,
        deviceFingerprint = challenge.deviceFingerprint,
        ephemeralPublicKey = challenge.ephemeralPublicKey,
        platform = challenge.platform,
        issuedAt = challenge.issuedAt,
        authMethod = challenge.authMethod,
        authProof = proof,
        signature = "verified-by-connection-coordinator",
    )

    private fun testIdentity() = LanWebDeviceIdentity { newKeyPair() }

    private fun newKeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
}
