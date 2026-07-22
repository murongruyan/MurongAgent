package com.murong.agent.lan

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanWebScramCryptoTest {
    @Test
    fun crossPlatformUnicodePasswordVectorAndTamperDetection() {
        val request = LanWebPairChallengeRequest(
            requestId = "connect-vector",
            clientName = "Murong Desktop",
            deviceId = "DMB77YSEX4BLAFRU",
            devicePublicKey = PUBLIC_KEY,
            deviceFingerprint = "XNJS-wzokyQ2-vjM0QQJgbie5K1rn-niorfnGqyyfNM",
            ephemeralPublicKey = PUBLIC_KEY,
            platform = "windows",
            issuedAt = 1_784_700_000_000L,
            authMethod = LanWebTrustSource.SECURITY_PASSWORD,
            clientNonce = "AAECAwQFBgcICQoLDA0ODxAR",
            signature = "unused-in-crypto-vector",
        )
        val response = LanWebPairChallengeResponse(
            sessionId = "AQIDBAUGBwgJCgsMDQ4PEA",
            serverNonce = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwd",
            salt = "AAECAwQFBgcICQoLDA0ODw",
            iterations = 210_000,
            expiresAt = 1_784_700_060_000L,
        )
        val salt = ByteArray(16) { it.toByte() }
        val authMessage = LanWebScramCrypto.authMessage(request, response)
        assertEquals(
            "EWMO2uA8h5OqtOeGD1P8mgJj78sQBeBh2TXVvFwrhl8",
            LanWebScramCrypto.encode(MessageDigest.getInstance("SHA-256").digest(authMessage)),
        )
        val verifier = LanWebScramCrypto.createVerifier("安全密码-Secret123", salt)
        try {
            assertEquals("Ii0O2X8TnsFSHN66_3ExK8BxHkf8Z8DrYsIQ7QK-_gA", LanWebScramCrypto.encode(verifier.storedKey))
            assertEquals("gsnIuthtdGD69mU-KtK7AgkVW7RIvapuJpts2EeIICw", LanWebScramCrypto.encode(verifier.serverKey))
            val client = LanWebScramCrypto.clientProof("安全密码-Secret123", salt, 210_000, authMessage)
            assertEquals("K_STU2wDd0G4YjGThfQNElLzre25QTcoz7Qgf1fyrJI", client.proof)
            assertEquals("U45HJI_kHrwMNN5cuYao0dBersJCWLopSb6bcTExlN0", client.expectedServerProof)
            assertEquals(client.expectedServerProof, LanWebScramCrypto.verifyClientProof(verifier, authMessage, client.proof))
            assertNull(LanWebScramCrypto.verifyClientProof(verifier, authMessage + 0, client.proof))
        } finally {
            verifier.clear()
            salt.fill(0)
            authMessage.fill(0)
        }
    }

    private companion object {
        const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q"
    }
}
