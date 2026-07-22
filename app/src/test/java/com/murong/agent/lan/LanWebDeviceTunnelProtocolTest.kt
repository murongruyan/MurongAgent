package com.murong.agent.lan

import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanWebDeviceTunnelProtocolTest {
    @Test
    fun `encrypted frame hides request metadata and authenticates sender role`() {
        val (roomId, secret) = credentials(0x41)
        val now = 1_900_000_000_000L
        val message = LanWebDeviceTunnelProtocol.newMessage("request-123", "request_start", now).copy(
            method = "POST",
            path = "/api/v1/pair",
            headers = mapOf("Authorization" to listOf("Bearer do-not-leak")),
        )
        val encoded = LanWebDeviceTunnelProtocol.encrypt(
            secret = secret,
            roomId = roomId,
            senderRole = LanWebDeviceTunnelProtocol.ROLE_DESKTOP,
            message = message,
            secureRandom = FixedSecureRandom(0x51),
            now = now,
        )
        val visible = encoded.toString(Charsets.UTF_8)

        assertFalse(visible.contains("/api/v1/pair"))
        assertFalse(visible.contains("do-not-leak"))
        assertEquals(
            message,
            LanWebDeviceTunnelProtocol.decrypt(
                secret,
                roomId,
                LanWebDeviceTunnelProtocol.ROLE_DESKTOP,
                encoded,
                now,
            )
        )
        assertFails {
            LanWebDeviceTunnelProtocol.decrypt(
                secret,
                roomId,
                LanWebDeviceTunnelProtocol.ROLE_PHONE,
                encoded,
                now,
            )
        }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val (roomId, secret) = credentials(0x61)
        val now = 1_900_000_000_000L
        val message = LanWebDeviceTunnelProtocol.newMessage("request-456", "request_end", now)
        val encoded = LanWebDeviceTunnelProtocol.encrypt(
            secret,
            roomId,
            LanWebDeviceTunnelProtocol.ROLE_DESKTOP,
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
            LanWebDeviceTunnelProtocol.decrypt(
                secret,
                roomId,
                LanWebDeviceTunnelProtocol.ROLE_DESKTOP,
                tampered,
                now,
            )
        }
    }

    @Test
    fun `replay cache accepts a message only once`() {
        val cache = LanWebDeviceTunnelReplayCache()
        assertTrue(cache.claim("relaymsg-123", 10_000L, 10_000L))
        assertFalse(cache.claim("relaymsg-123", 10_000L, 10_001L))
    }

    @Test
    fun `public relay requires tls while loopback permits ws`() {
        assertEquals(
            "wss://murongagent.rl1.cc/relay/v2/tunnel",
            LanWebDeviceTunnelProtocol.OFFICIAL_TUNNEL_URL
        )
        assertEquals(
            "wss://relay.example.com/v2/tunnel",
            LanWebDeviceTunnelProtocol.normalizeRelayUrl("wss://relay.example.com")
        )
        assertEquals(
            "ws://127.0.0.1:8787/v2/tunnel",
            LanWebDeviceTunnelProtocol.normalizeRelayUrl("ws://127.0.0.1:8787")
        )
        assertFails { LanWebDeviceTunnelProtocol.normalizeRelayUrl("ws://relay.example.com") }
        assertFails { LanWebDeviceTunnelProtocol.normalizeRelayUrl("wss://user@relay.example.com") }
        assertFails { LanWebDeviceTunnelProtocol.normalizeRelayUrl("wss://relay.example.com?v=2") }
    }

    private class FixedSecureRandom(private val value: Int) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { index -> bytes[index] = (value + index).toByte() }
        }
    }

    private fun credentials(seed: Int): Pair<String, ByteArray> {
        val room = ByteArray(16) { (seed + it).toByte() }
        val secret = ByteArray(32) { (seed + 16 + it).toByte() }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(room) to secret
    }
}
