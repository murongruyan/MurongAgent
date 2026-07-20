package com.murong.agent.lan

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LanWebDeviceSyncCryptoTest {
    @Test
    fun `round trips and rejects authenticated metadata tampering`() {
        val key = ByteArray(32) { 0x5a }
        val envelope = LanWebDeviceSyncCrypto.encrypt(
            key = key,
            requestId = "device-sync-12345678",
            issuedAt = 123456789L,
            direction = LanWebDeviceSyncCrypto.WINDOWS_TO_ANDROID,
            plaintext = "{\"schemaVersion\":1}",
        )

        assertEquals("{\"schemaVersion\":1}", LanWebDeviceSyncCrypto.decrypt(key, envelope))
        assertFails {
            LanWebDeviceSyncCrypto.decrypt(
                key,
                envelope.copy(direction = LanWebDeviceSyncCrypto.ANDROID_TO_WINDOWS),
            )
        }
    }

    @Test
    fun `rejects ciphertext tampering`() {
        val key = ByteArray(32) { it.toByte() }
        val envelope = LanWebDeviceSyncCrypto.encrypt(
            key = key,
            requestId = "device-sync-abcdefgh",
            issuedAt = 987654321L,
            direction = LanWebDeviceSyncCrypto.WINDOWS_TO_ANDROID,
            plaintext = "secret",
        )
        val bytes = Base64.getUrlDecoder().decode(envelope.ciphertext)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = envelope.copy(
            ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
        )

        assertFails { LanWebDeviceSyncCrypto.decrypt(key, tampered) }
    }

    @Test
    fun `handoff directions are authenticated and separate from credential sync`() {
        val key = ByteArray(32) { 0x33 }
        val envelope = LanWebDeviceSyncCrypto.encrypt(
            key = key,
            requestId = "desktop-command-12345678",
            issuedAt = 987654321L,
            direction = LanWebDeviceSyncCrypto.DESKTOP_HANDOFF_TO_ANDROID,
            plaintext = "{\"handoffToken\":\"secret\"}",
        )

        assertEquals(
            "{\"handoffToken\":\"secret\"}",
            LanWebDeviceSyncCrypto.decrypt(key, envelope),
        )
        assertFails {
            LanWebDeviceSyncCrypto.decrypt(
                key,
                envelope.copy(direction = LanWebDeviceSyncCrypto.WINDOWS_TO_ANDROID),
            )
        }
    }
}
