package com.murong.agent.lan

import kotlin.test.Test
import kotlin.test.assertEquals

class LanWebAdbPairingProofTest {
    @Test
    fun `ADB challenge proof matches desktop fixed vector`() {
        val challenge = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        val request = LanWebConnectionRequest(
            requestId = "connect-test",
            clientName = "Murong Desktop",
            deviceId = "DMB77YSEX4BLAFRU",
            devicePublicKey = PUBLIC_KEY,
            deviceFingerprint = "XNJS-wzokyQ2-vjM0QQJgbie5K1rn-niorfnGqyyfNM",
            ephemeralPublicKey = PUBLIC_KEY,
            platform = "windows",
            issuedAt = 1_000L,
            authMethod = LanWebTrustSource.ADB,
            signature = "",
        )

        assertEquals(
            "5_YI_6D-HJLBm7sJ1ysDfz0lm2dk_t6RNrbkPJetvgk",
            LanWebAdbPairingStore.proofForChallenge(challenge, request),
        )
    }

    private companion object {
        const val PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q"
    }
}
