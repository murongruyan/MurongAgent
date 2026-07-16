package com.murong.agent.core.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodexNetworkProxyUrlTest {
    @Test
    fun numericProxyHosts_areConvertedToHttpProxyUrls() {
        assertEquals(
            "http://127.0.0.1:7890",
            CodexNetworkProxyUrl.fromNumericHost("127.0.0.1", 7890),
        )
        assertEquals(
            "http://[2001:db8::1]:8080",
            CodexNetworkProxyUrl.fromNumericHost("[2001:db8::1]", 8080),
        )
    }

    @Test
    fun missingInvalidOrDnsOnlyProxyHosts_fallBackToTheBridge() {
        assertNull(CodexNetworkProxyUrl.fromNumericHost(null, 7890))
        assertNull(CodexNetworkProxyUrl.fromNumericHost("proxy.example", 7890))
        assertNull(CodexNetworkProxyUrl.fromNumericHost("127.0.0.1", 0))
        assertNull(CodexNetworkProxyUrl.fromNumericHost("127.0.0.999", 7890))
    }
}
