package com.murong.agent.lan

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanWebCloudRelayProtocolTest {
    @Test
    fun `share code round trips exact room and secret`() {
        val generated = LanWebCloudRelayProtocol.generateShare(FixedSecureRandom(0x31))
        val parsed = LanWebCloudRelayProtocol.parseShareCode(generated.code)

        assertEquals(generated.roomId, parsed.roomId)
        assertTrue(generated.secret.contentEquals(parsed.secret))
        assertEquals(generated.code, parsed.code)
    }

    @Test
    fun `encrypted frame hides request metadata and authenticates sender role`() {
        val share = LanWebCloudRelayProtocol.generateShare(FixedSecureRandom(0x41))
        val now = 1_900_000_000_000L
        val message = LanWebCloudRelayProtocol.newMessage("request-123", "request_start", now).copy(
            method = "POST",
            path = "/api/v1/pair",
            headers = mapOf("Authorization" to listOf("Bearer do-not-leak")),
        )
        val encoded = LanWebCloudRelayProtocol.encrypt(
            secret = share.secret,
            roomId = share.roomId,
            senderRole = LanWebCloudRelayProtocol.ROLE_DESKTOP,
            message = message,
            secureRandom = FixedSecureRandom(0x51),
            now = now,
        )
        val visible = encoded.toString(Charsets.UTF_8)

        assertFalse(visible.contains("/api/v1/pair"))
        assertFalse(visible.contains("do-not-leak"))
        assertEquals(
            message,
            LanWebCloudRelayProtocol.decrypt(
                share.secret,
                share.roomId,
                LanWebCloudRelayProtocol.ROLE_DESKTOP,
                encoded,
                now,
            )
        )
        assertFails {
            LanWebCloudRelayProtocol.decrypt(
                share.secret,
                share.roomId,
                LanWebCloudRelayProtocol.ROLE_PHONE,
                encoded,
                now,
            )
        }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val share = LanWebCloudRelayProtocol.generateShare(FixedSecureRandom(0x61))
        val now = 1_900_000_000_000L
        val message = LanWebCloudRelayProtocol.newMessage("request-456", "request_end", now)
        val encoded = LanWebCloudRelayProtocol.encrypt(
            share.secret,
            share.roomId,
            LanWebCloudRelayProtocol.ROLE_DESKTOP,
            message,
            FixedSecureRandom(0x71),
            now,
        )
        val tampered = encoded.toString(Charsets.UTF_8).let { json ->
            val marker = "\"ciphertext\":\""
            val index = json.indexOf(marker) + marker.length
            json.replaceRange(index, index + 1, if (json[index] == 'A') "B" else "A")
        }.toByteArray(Charsets.UTF_8)

        assertFails {
            LanWebCloudRelayProtocol.decrypt(
                share.secret,
                share.roomId,
                LanWebCloudRelayProtocol.ROLE_DESKTOP,
                tampered,
                now,
            )
        }
    }

    @Test
    fun `replay cache accepts a message only once`() {
        val cache = LanWebCloudRelayReplayCache()
        assertTrue(cache.claim("relaymsg-123", 10_000L, 10_000L))
        assertFalse(cache.claim("relaymsg-123", 10_000L, 10_001L))
    }

    @Test
    fun `public relay requires tls while loopback permits ws`() {
        assertEquals(
            "wss://murongagent.rl1.cc/relay/v1/connect",
            LanWebCloudRelayProtocol.OFFICIAL_RELAY_URL
        )
        assertEquals(
            "wss://relay.example.com/v1/connect",
            LanWebCloudRelayProtocol.normalizeRelayUrl("wss://relay.example.com")
        )
        assertEquals(
            "ws://127.0.0.1:8787/v1/connect",
            LanWebCloudRelayProtocol.normalizeRelayUrl("ws://127.0.0.1:8787")
        )
        assertFails { LanWebCloudRelayProtocol.normalizeRelayUrl("ws://relay.example.com") }
        assertFails { LanWebCloudRelayProtocol.normalizeRelayUrl("wss://user@relay.example.com") }
        assertFails { LanWebCloudRelayProtocol.normalizeRelayUrl("wss://relay.example.com?v=1") }
    }

    private class FixedSecureRandom(private val value: Int) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { index -> bytes[index] = (value + index).toByte() }
        }
    }
}
