package com.murong.agent.core.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpBackupSanitizationTest {
    @Test
    fun sanitizedForBackup_removesCredentialChannelsButKeepsOrdinaryConfig() {
        val sanitized = McpServerConfig(
            name = "example",
            command = "node",
            args = listOf(
                "server.js",
                "--api-key=secret-a",
                "--token",
                "secret-b",
                "-H",
                "Authorization: Bearer secret-c",
                "--header",
                "Cookie: session=secret-cookie",
                "--mode=readonly"
            ),
            env = mapOf("API_TOKEN" to "secret-d", "MCP_AUTH" to "secret-auth", "LOG_LEVEL" to "info"),
            url = "https://user:secret-pass@example.test/mcp?token=secret-e&mode=read",
            headers = mapOf(
                "Authorization" to "secret-f",
                "Cookie" to "secret-cookie",
                "X-Auth" to "secret-auth",
                "Accept" to "application/json"
            ),
            authHeaderSecretReferences = mapOf("Authorization" to "secure-reference")
        ).sanitizedForBackup()

        assertEquals("node", sanitized.command)
        assertEquals("info", sanitized.env["LOG_LEVEL"])
        assertFalse("API_TOKEN" in sanitized.env)
        assertFalse("MCP_AUTH" in sanitized.env)
        assertEquals("application/json", sanitized.headers["Accept"])
        assertFalse("Authorization" in sanitized.headers)
        assertFalse("Cookie" in sanitized.headers)
        assertFalse("X-Auth" in sanitized.headers)
        assertTrue(sanitized.authHeaderSecretReferences.isEmpty())
        assertEquals("--api-key=", sanitized.args[1])
        assertEquals("", sanitized.args[3])
        assertEquals("Authorization:", sanitized.args[5])
        assertEquals("Cookie:", sanitized.args[7])
        assertEquals("https://example.test/mcp?token=&mode=read", sanitized.url)
        assertFalse(sanitized.toString().contains("secret-"))
    }
}
