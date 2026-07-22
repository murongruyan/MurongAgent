package com.murong.agent.lan

import com.murong.agent.core.loop.PortableConversationBackupRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LanWebDeviceSyncContractTest {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun versionSixBundleKeepsSessionsCredentialsPortableCategoriesAndExecutionProfiles() {
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
            sessions = listOf(
                LanWebSyncedSession(
                    sourceSessionId = "windows-session-1",
                    originPlatform = "windows",
                    originSessionId = "windows-session-1",
                    document = buildJsonObject {
                        put("format", "murong-portable-session")
                        put("sourcePlatform", "windows")
                    }
                )
            ),
        )

        val restored = json.decodeFromString<LanWebCredentialSyncBundle>(json.encodeToString(bundle))

        assertEquals(6, restored.schemaVersion)
        assertEquals("windows-session-1", restored.sessions.single().sourceSessionId)
        assertEquals("windows", restored.sessions.single().originPlatform)
        assertEquals("windows-session-1", restored.sessions.single().originSessionId)
        assertEquals("murong-portable-session", restored.sessions.single().document["format"]?.toString()?.trim('"'))
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
            includeSessions = true,
            includeProviderCredentials = true,
            includeCodexLogin = true,
            includeGitHubCredentials = true,
            includeAgentSettings = true,
            includeKnowledge = true,
            includeMcp = true,
            includeMcpCredentials = true,
            includeSavedWorkflows = true,
            sessionCursor = 23,
        )

        val restored = json.decodeFromString<LanWebDeviceSyncOptions>(json.encodeToString(options))

        assertTrue(restored.includeSessions)
        assertTrue(restored.includeProviderCredentials)
        assertTrue(restored.includeCodexLogin)
        assertTrue(restored.includeGitHubCredentials)
        assertTrue(restored.includeMcpCredentials)
        assertTrue(restored.includeSavedWorkflows)
        assertEquals(23, restored.sessionCursor)
    }

    @Test
    fun chatHistoryIsReturnedAcrossTransparentPagesWithoutDroppingSessions() {
        val payload = "x".repeat(4 * 1024 * 1024)
        val records = (0 until 3).map { index ->
            PortableConversationBackupRecord(
                sourceSessionId = "session-$index",
                portableJson = "{\"payload\":\"$payload\"}",
                originPlatform = "android",
                originSessionId = "session-$index",
            )
        }
        val received = mutableListOf<String>()
        var cursor = 0
        do {
            val page = buildDeviceSyncSessionPage(records, cursor, json)
            received += page.sessions.map(LanWebSyncedSession::sourceSessionId)
            cursor = page.nextCursor ?: records.size
        } while (cursor < records.size)

        assertEquals(records.map(PortableConversationBackupRecord::sourceSessionId), received)
    }
}
