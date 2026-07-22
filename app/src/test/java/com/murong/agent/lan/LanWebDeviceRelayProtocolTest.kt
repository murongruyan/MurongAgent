package com.murong.agent.lan

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanWebDeviceRelayProtocolTest {
	@Test
	fun `connection request binds account proof ephemeral key and ticket`() {
		val phone = identity()
		val desktop = identity()
		val ephemeral = identity()
		val request = LanWebDeviceRelayProtocol.connectionRequest(
			identity = phone,
			targetDeviceId = desktop.snapshot.deviceId,
			deviceName = "Test phone",
			authMethod = LanWebTrustSource.GITHUB_ACCOUNT,
			authProof = encoder.encodeToString(ByteArray(32) { it.toByte() }),
			ephemeralPublicKey = ephemeral.snapshot.publicKey,
			requestId = "connect-account-0001",
			now = 123_456L,
		)
		assertTrue(
			LanWebDeviceIdentity.verify(
				phone.snapshot.publicKey,
				LanWebDeviceRelayProtocol.connectRequestPayload(request),
				request.signature,
			)
		)
		assertFalse(
			LanWebDeviceIdentity.verify(
				phone.snapshot.publicKey,
				LanWebDeviceRelayProtocol.connectRequestPayload(request.copy(ephemeralPublicKey = desktop.snapshot.publicKey)),
				request.signature,
			)
		)
	}

    @Test
    fun `signed desktop SCRAM challenge produces bound client and server proofs`() {
        val phone = identity()
        val desktop = identity()
        val now = System.currentTimeMillis()
        val authentication = LanWebDeviceRelayProtocol.authBegin(
            identity = phone,
            targetDeviceId = desktop.snapshot.deviceId,
            deviceName = "Test phone",
            authMethod = LanWebTrustSource.SECURITY_PASSWORD,
            clientNonce = encoder.encodeToString(ByteArray(18) { (it + 1).toByte() }),
            requestId = "auth-request-0001",
            now = now,
        )
        val unsigned = LanWebDeviceRelayMessage(
            kind = "auth_challenge",
            requestId = authentication.requestId,
            sourceDeviceId = desktop.snapshot.deviceId,
            targetDeviceId = phone.snapshot.deviceId,
            authMethod = authentication.authMethod,
            clientNonce = authentication.clientNonce,
            sessionId = encoder.encodeToString(ByteArray(18) { (it + 2).toByte() }),
            serverNonce = authentication.clientNonce + encoder.encodeToString(ByteArray(18) { (it + 3).toByte() }),
            salt = encoder.encodeToString(ByteArray(16) { (it + 4).toByte() }),
            iterations = LanWebScramCrypto.ITERATIONS,
            expiresAt = now + 60_000L,
            issuedAt = now + 1,
        )
        val challenge = unsigned.copy(signature = desktop.sign(LanWebDeviceRelayProtocol.authChallengePayload(unsigned)))
        LanWebDeviceRelayProtocol.validateAuthChallenge(challenge, authentication, desktop.snapshot.publicKey, now)
        val proof = LanWebDeviceRelayProtocol.scramClientProof(
            secret = "Desktop-安全密码-123",
            request = authentication,
            response = challenge,
            fingerprint = phone.snapshot.publicKeyFingerprint,
        )
        assertEquals(43, proof.proof.length)
        assertEquals(43, proof.expectedServerProof.length)
        assertFails {
            LanWebDeviceRelayProtocol.validateAuthChallenge(
                challenge.copy(iterations = challenge.iterations + 1),
                authentication,
                desktop.snapshot.publicKey,
                now,
            )
        }
    }

    @Test
    fun `desktop compatible signed ECDH invitation decrypts on Android`() {
        val phone = identity()
        val desktop = identity()
        val ephemeral = identity()
        val requestId = "invite-request-0001"
        val issuedAt = System.currentTimeMillis()
        val context = LanWebDeviceRelayProtocol.inviteContext(
            requestId,
            desktop.snapshot.deviceId,
            phone.snapshot.deviceId,
        )
        val linkSecret = ephemeral.deriveLinkSecret(phone.snapshot.publicKey, context)
        val roomId = encoder.encodeToString(ByteArray(16) { it.toByte() })
        val tunnelSecret = ByteArray(32) { (it + 1).toByte() }
        val expiresAt = issuedAt + 90_000L
        val plain = """{"version":2,"roomId":"$roomId","secret":"${encoder.encodeToString(tunnelSecret)}","expiresAt":$expiresAt}"""
            .toByteArray()
        val nonce = ByteArray(12) { (it + 2).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(linkSecret, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(
            LanWebDeviceRelayProtocol.inviteAad(
                requestId,
                desktop.snapshot.deviceId,
                phone.snapshot.deviceId,
                ephemeral.snapshot.publicKey,
            )
        )
        val ciphertext = cipher.doFinal(plain)
        plain.fill(0)
        linkSecret.fill(0)
        val unsigned = LanWebDeviceRelayMessage(
            kind = "invite",
            requestId = requestId,
            sourceDeviceId = desktop.snapshot.deviceId,
            sourcePublicKey = desktop.snapshot.publicKey,
            targetDeviceId = phone.snapshot.deviceId,
            ephemeralPublicKey = ephemeral.snapshot.publicKey,
            issuedAt = issuedAt,
            nonce = encoder.encodeToString(nonce),
            ciphertext = encoder.encodeToString(ciphertext),
        )
        val message = unsigned.copy(signature = desktop.sign(LanWebDeviceRelayProtocol.invitePayload(unsigned)))

        LanWebDeviceRelayProtocol.validateInvite(message, phone.snapshot, issuedAt)
        val room = LanWebDeviceRelayProtocol.decryptInvitation(phone, message, issuedAt)
        try {
            assertEquals(roomId, room.roomId)
            assertContentEquals(tunnelSecret, room.secret)
            assertEquals(expiresAt, room.expiresAt)
        } finally {
            room.secret.fill(0)
            tunnelSecret.fill(0)
        }

        assertFails {
            LanWebDeviceRelayProtocol.decryptInvitation(
                phone,
                message.copy(ciphertext = message.ciphertext.dropLast(1) + "A"),
                issuedAt,
            )
        }
    }

    @Test
    fun `registration and acknowledgement bind every security field`() {
        val phone = identity()
        val desktop = identity()
        val registration = LanWebDeviceRelayProtocol.registration(
            phone,
            "android",
            now = 123_456L,
            secureRandom = SecureRandom(byteArrayOf(1, 2, 3, 4)),
        )
        assertTrue(
            LanWebDeviceIdentity.verify(
                registration.devicePublicKey,
                LanWebDeviceRelayProtocol.registrationPayload(registration),
                registration.signature,
            )
        )
        assertFalse(
            LanWebDeviceIdentity.verify(
                registration.devicePublicKey,
                LanWebDeviceRelayProtocol.registrationPayload(registration.copy(platform = "windows")),
                registration.signature,
            )
        )

        val invitation = LanWebDeviceRelayMessage(
            kind = "invite",
            requestId = "invite-request-0002",
            sourceDeviceId = desktop.snapshot.deviceId,
            sourcePublicKey = desktop.snapshot.publicKey,
            targetDeviceId = phone.snapshot.deviceId,
            ephemeralPublicKey = desktop.snapshot.publicKey,
            issuedAt = 123_456L,
        )
        val acknowledgement = LanWebDeviceRelayProtocol.acknowledgement(
            phone,
            invitation,
            accepted = false,
            error = "blocked",
            now = 123_457L,
        )
        assertTrue(
            LanWebDeviceIdentity.verify(
                phone.snapshot.publicKey,
                LanWebDeviceRelayProtocol.inviteAckPayload(acknowledgement),
                acknowledgement.signature,
            )
        )
        assertFalse(
            LanWebDeviceIdentity.verify(
                phone.snapshot.publicKey,
                LanWebDeviceRelayProtocol.inviteAckPayload(acknowledgement.copy(error = "tampered")),
                acknowledgement.signature,
            )
        )
    }

    @Test
    fun `device and tunnel URLs stay on their dedicated paths`() {
        assertEquals(
            "wss://murongagent.rl1.cc/relay/v2/tunnel",
            LanWebDeviceRelayProtocol.tunnelUrlForDeviceRelay("wss://murongagent.rl1.cc/relay/v2/device"),
        )
        assertEquals(
            "ws://127.0.0.1:8788/relay/v2/tunnel",
            LanWebDeviceRelayProtocol.tunnelUrlForDeviceRelay("ws://127.0.0.1:8788/relay/v2/device"),
        )
        assertFails { LanWebDeviceRelayProtocol.tunnelUrlForDeviceRelay("wss://murongagent.rl1.cc/not-device") }
    }

    private fun identity(): LanWebDeviceIdentity {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        return LanWebDeviceIdentity { generator.generateKeyPair() }
    }

    private companion object {
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
