package com.murong.agent.core.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class McpToolNamingTest {
    @Test
    fun `canonical tool name includes its server`() {
        assertEquals(
            "mcp__github_cloud__search_code",
            canonicalMcpToolName("GitHub Cloud", "search-code")
        )
    }

    @Test
    fun `same raw tool from different servers remains distinct`() {
        val github = McpToolDef("search", "", emptyMap(), "github").canonicalToolName()
        val docs = McpToolDef("search", "", emptyMap(), "docs").canonicalToolName()
        assertNotEquals(github, docs)
    }

    @Test
    fun `canonical name remains stable when supplied again`() {
        assertEquals(
            "mcp__github__search",
            canonicalMcpToolName("ignored", "mcp__github__search")
        )
    }
}
