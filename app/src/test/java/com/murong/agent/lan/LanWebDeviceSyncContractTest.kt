package com.murong.agent.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LanWebDeviceSyncContractTest {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun versionFourBundleKeepsCredentialsPortableCategoriesAndExecutionProfiles() {
        val bundle = LanWebCredentialSyncBundle(
            sourcePlatform = "windows",
            generatedAt = System.currentTimeMillis(),
            providers = listOf(
                LanWebSyncedProviderCredential(
                    profileId = "profile",
                    providerId = "openai-compatible",
                    name = "OpenAI",
                    baseUrl = "https://example.test/v1",
                    model = "model",
                    apiKey = "api-key-secret",
                )
            ),
            codexAuthJson = "{\"auth_mode\":\"chatgpt\",\"tokens\":{}}",
            github = LanWebSyncedGitHubCredential(
                apiBaseUrl = "https://github.example/api/v3",
                token = "github-token-secret",
                viewerLogin = "murong-user",
            ),
            agentSettings = LanWebSyncedAgentSettings(
                approvalMode = "yolo",
                systemPrompt = "prompt",
                responseVerbosity = "detailed",
                temperature = 0.45,
                maxTokens = 6789,
                enableMultimodalMessages = false,
                plannerProfileEnabled = true,
                plannerModel = "planner-model",
                plannerReasoningEffort = "xhigh",
                subagentDefaultProfileEnabled = true,
                subagentDefaultModel = "child-model",
                subagentDefaultReasoningEffort = "medium",
            ),
            knowledge = LanWebSyncedKnowledge(
                memories = listOf(LanWebSyncedMemory("memory", "title", "content", true))
            ),
            mcpServers = listOf(
                LanWebSyncedMcpServer(
                    id = "mcp",
                    name = "Remote MCP",
                    transport = "streamable_http",
                    url = "https://mcp.example.test/api",
                    headers = mapOf("Authorization" to "mcp-token"),
                )
            ),
            mcpCredentialsIncluded = true,
        )

        val restored = json.decodeFromString<LanWebCredentialSyncBundle>(json.encodeToString(bundle))

        assertEquals(4, restored.schemaVersion)
        assertEquals("api-key-secret", restored.providers.single().apiKey)
        assertTrue(restored.codexAuthJson!!.contains("tokens"))
        assertEquals("github-token-secret", restored.github?.token)
        assertEquals("murong-user", restored.github?.viewerLogin)
        assertEquals("yolo", restored.agentSettings?.approvalMode)
        assertEquals(0.45, restored.agentSettings?.temperature)
        assertEquals(6789, restored.agentSettings?.maxTokens)
        assertEquals(false, restored.agentSettings?.enableMultimodalMessages)
        assertEquals(true, restored.agentSettings?.plannerProfileEnabled)
        assertEquals("planner-model", restored.agentSettings?.plannerModel)
        assertEquals("xhigh", restored.agentSettings?.plannerReasoningEffort)
        assertEquals(true, restored.agentSettings?.subagentDefaultProfileEnabled)
        assertEquals("child-model", restored.agentSettings?.subagentDefaultModel)
        assertEquals("medium", restored.agentSettings?.subagentDefaultReasoningEffort)
        assertEquals("content", restored.knowledge?.memories?.single()?.content)
        assertEquals("mcp-token", restored.mcpServers.single().headers["Authorization"])
        assertTrue(restored.mcpCredentialsIncluded)
    }

    @Test
    fun syncOptionsKeepCredentialCategoriesExplicit() {
        val options = LanWebDeviceSyncOptions(
            includeProviderCredentials = true,
            includeCodexLogin = true,
            includeGitHubCredentials = true,
            includeAgentSettings = true,
            includeKnowledge = true,
            includeMcp = true,
            includeMcpCredentials = true,
            includeSavedWorkflows = true,
        )

        val restored = json.decodeFromString<LanWebDeviceSyncOptions>(json.encodeToString(options))

        assertTrue(restored.includeProviderCredentials)
        assertTrue(restored.includeCodexLogin)
        assertTrue(restored.includeGitHubCredentials)
        assertTrue(restored.includeMcpCredentials)
        assertTrue(restored.includeSavedWorkflows)
    }
}
