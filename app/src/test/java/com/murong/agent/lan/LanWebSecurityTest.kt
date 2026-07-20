package com.murong.agent.lan

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanWebSecurityTest {
    @Test
    fun `only loopback and private network clients are allowed`() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.4.5", "192.168.2.9", "169.254.1.2", "100.64.0.1", "::1", "fd12::2")
            .forEach { assertTrue(LanWebSecurity.isAllowedRemoteAddress(it), it) }
        listOf("0.0.0.0", "8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", null)
            .forEach { assertFalse(LanWebSecurity.isAllowedRemoteAddress(it), it.orEmpty()) }
    }

    @Test
    fun `host and origin must match bound service`() {
        assertTrue(LanWebSecurity.isAllowedHost("192.168.2.4:8765", "192.168.2.4", 8765))
        assertFalse(LanWebSecurity.isAllowedHost("evil.example:8765", "192.168.2.4", 8765))
        assertFalse(LanWebSecurity.isAllowedHost("192.168.2.4:9999", "192.168.2.4", 8765))
        assertTrue(
            LanWebSecurity.isAllowedOrigin(
                "http://192.168.2.4:8765",
                "192.168.2.4:8765",
                8765
            )
        )
        assertFalse(
            LanWebSecurity.isAllowedOrigin(
                "https://192.168.2.4:8765",
                "192.168.2.4:8765",
                8765
            )
        )
        assertFalse(
            LanWebSecurity.isAllowedOrigin(
                "http://evil.example:8765",
                "192.168.2.4:8765",
                8765
            )
        )
    }

    @Test
    fun `messages and client names reject control characters and unsafe sizes`() {
        assertTrue(LanWebSecurity.normalizeClientName(" Work   PC ") == "Work PC")
        assertNull(LanWebSecurity.normalizeClientName("bad\u0000name"))
        assertNull(LanWebSecurity.normalizeMessage("bad\u0000message"))
        assertTrue(LanWebSecurity.normalizeMessage("line one\nline two") != null)
        assertNull(LanWebSecurity.normalizeMessage("x".repeat(LanWebContract.MAX_MESSAGE_CHARS + 1)))
    }

    @Test
    fun `rate limiter enforces a fixed rolling window`() {
        val limiter = LanWebRateLimiter(limit = 2, windowMillis = 1_000L)
        assertTrue(limiter.tryAcquire("client", now = 1_000L))
        assertTrue(limiter.tryAcquire("client", now = 1_100L))
        assertFalse(limiter.tryAcquire("client", now = 1_200L))
        assertTrue(limiter.tryAcquire("client", now = 2_001L))
    }
}
