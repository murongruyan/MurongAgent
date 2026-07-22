package com.murong.agent.lan

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanWebDiscoveryProtocolTest {
    @Test
    fun `discovery nonce is bounded and base64url encoded`() {
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString("0123456789abcdef".toByteArray())
        val request = (LanWebDiscoveryProtocol.REQUEST_PREFIX + nonce).toByteArray()
        assertEquals(nonce, LanWebDiscoveryProtocol.parseNonce(request, request.size))
        assertNull(LanWebDiscoveryProtocol.parseNonce("wrong".toByteArray(), 5))
        val malformed = (LanWebDiscoveryProtocol.REQUEST_PREFIX + "not-base64").toByteArray()
        assertNull(LanWebDiscoveryProtocol.parseNonce(malformed, malformed.size))
    }

    @Test
    fun `announcement signature binds all visible discovery fields`() {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val identity = LanWebDeviceIdentity { generator.generateKeyPair() }
        val unsigned = LanWebDiscoveryAnnouncement(
            nonce = Base64.getUrlEncoder().withoutPadding().encodeToString("0123456789abcdef".toByteArray()),
            deviceId = identity.snapshot.deviceId,
            deviceDisplayId = identity.snapshot.displayId,
            devicePublicKey = identity.snapshot.publicKey,
            deviceFingerprint = identity.snapshot.publicKeyFingerprint,
            name = "Murong Android",
            port = 8765,
            issuedAt = 1_000L,
            signature = "",
        )
        val signature = identity.sign(LanWebDiscoveryProtocol.signaturePayload(unsigned))
        assertTrue(
            LanWebDeviceIdentity.verify(
                unsigned.devicePublicKey,
                LanWebDiscoveryProtocol.signaturePayload(unsigned),
                signature,
            )
        )
        assertFalse(
            LanWebDeviceIdentity.verify(
                unsigned.devicePublicKey,
                LanWebDiscoveryProtocol.signaturePayload(unsigned.copy(port = 8766)),
                signature,
            )
        )
    }
}
