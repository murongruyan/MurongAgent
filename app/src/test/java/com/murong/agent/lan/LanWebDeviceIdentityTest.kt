package com.murong.agent.lan

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanWebDeviceIdentityTest {
    @Test
    fun `stable P-256 public key has the same device id as desktop`() {
        val identity = LanWebDeviceIdentity { scalarOneKeyPair() }
        assertEquals(EXPECTED_PUBLIC_KEY, identity.snapshot.publicKey)
        assertEquals("DMB77YSEX4BLAFRU", identity.snapshot.deviceId)
        assertEquals("DMB7-7YSE-X4BL-AFRU", identity.snapshot.displayId)
        assertEquals("XNJS-wzokyQ2-vjM0QQJgbie5K1rn-niorfnGqyyfNM", identity.snapshot.publicKeyFingerprint)
        assertEquals(identity.snapshot.deviceId, LanWebDeviceIdentity.deviceIdForPublicKey(EXPECTED_PUBLIC_KEY))
        assertEquals(identity.snapshot.deviceId, LanWebDeviceIdentity.normalizeDeviceId(identity.snapshot.displayId))
        assertNull(LanWebDeviceIdentity.normalizeDeviceId("not-a-device"))
    }

    @Test
    fun `identity signs and derives the same ECDH link secret`() {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val left = LanWebDeviceIdentity { generator.generateKeyPair() }
        val right = LanWebDeviceIdentity { generator.generateKeyPair() }
        val payload = "murong-device-link-register-v1".toByteArray()
        val signature = left.sign(payload)
        assertTrue(LanWebDeviceIdentity.verify(left.snapshot.publicKey, payload, signature))
        assertFalse(LanWebDeviceIdentity.verify(left.snapshot.publicKey, payload + byteArrayOf(0), signature))

        val context = "request-12345678".toByteArray()
        val leftSecret = left.deriveLinkSecret(right.snapshot.publicKey, context)
        val rightSecret = right.deriveLinkSecret(left.snapshot.publicKey, context)
        assertContentEquals(leftSecret, rightSecret)
        leftSecret.fill(0)
        rightSecret.fill(0)
    }

    private fun scalarOneKeyPair(): KeyPair {
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val factory = KeyFactory.getInstance("EC")
        return KeyPair(
            factory.generatePublic(ECPublicKeySpec(parameters.generator, parameters)),
            factory.generatePrivate(ECPrivateKeySpec(BigInteger.ONE, parameters)),
        )
    }

    private companion object {
        const val EXPECTED_PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q"
    }
}
