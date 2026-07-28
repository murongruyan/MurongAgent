package com.murong.agent.core.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodexNetworkProxyUrlTest {
    @Test
    fun numericProxyHosts_areConvertedToBridgeEndpoints() {
        assertEquals(
            "http://127.0.0.1:7890",
            CodexNetworkProxyEndpoint.fromNumericHost("127.0.0.1", 7890)?.proxyUrl,
        )
        assertEquals(
            "http://[2001:db8::1]:8080",
            CodexNetworkProxyEndpoint.fromNumericHost("[2001:db8::1]", 8080)?.proxyUrl,
        )
    }

    @Test
    fun missingInvalidOrDnsOnlyProxyHosts_fallBackToTheBridge() {
        assertNull(CodexNetworkProxyEndpoint.fromNumericHost(null, 7890))
        assertNull(CodexNetworkProxyEndpoint.fromNumericHost("proxy.example", 7890))
        assertNull(CodexNetworkProxyEndpoint.fromNumericHost("127.0.0.1", 0))
        assertNull(CodexNetworkProxyEndpoint.fromNumericHost("127.0.0.999", 7890))
    }
}
