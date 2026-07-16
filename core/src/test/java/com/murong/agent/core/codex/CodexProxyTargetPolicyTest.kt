package com.murong.agent.core.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexProxyTargetPolicyTest {

    @Test
    fun parsesNormalizedHttpsAuthority() {
        val target = CodexProxyTargetPolicy.parseAuthority("AUTH.OPENAI.COM.:443")

        assertEquals("auth.openai.com", target.host)
        assertEquals(443, target.port)
    }

    @Test
    fun allowsOnlyOpenAiAndChatGptHttpsTargets() {
        assertTrue(CodexProxyTargetPolicy.isAllowed("auth.openai.com", 443))
        assertTrue(CodexProxyTargetPolicy.isAllowed("api.openai.com", 443))
        assertTrue(CodexProxyTargetPolicy.isAllowed("chatgpt.com", 443))
        assertTrue(CodexProxyTargetPolicy.isAllowed("edge.chatgpt.com", 443))

        assertFalse(CodexProxyTargetPolicy.isAllowed("openai.com.attacker.test", 443))
        assertFalse(CodexProxyTargetPolicy.isAllowed("example.com", 443))
        assertFalse(CodexProxyTargetPolicy.isAllowed("auth.openai.com", 80))
    }
}
