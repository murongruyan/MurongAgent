package com.murong.agent.core.github

import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubAccountPolicyTest {
    @Test
    fun `account label prefers GitHub login`() {
        assertEquals("@murong", accountLabel(" murong ", 2))
        assertEquals("GitHub 账号 2", accountLabel("", 2))
    }

    @Test
    fun `api base url requires https and removes trailing slash`() {
        assertEquals("https://github.example/api/v3", normalizeApiBaseUrl(" https://github.example/api/v3/ "))
        assertEquals("https://api.github.com", normalizeApiBaseUrl("http://unsafe.example"))
        assertEquals("https://api.github.com", normalizeApiBaseUrl(""))
    }
}
