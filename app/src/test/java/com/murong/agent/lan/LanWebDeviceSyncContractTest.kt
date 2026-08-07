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
    fun versionEightBundleKeepsAccountPoolsSessionsCredentialsExecutionProfilesAndMediaRoutes() {
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
            codexAccounts = listOf(
                LanWebSyncedCodexAccount(
                    id = "codex-account-one",
                    label = "账号 1",
                    email = "one@example.com",
                    authJson = "{\"auth_mode\":\"chatgpt\",\"tokens\":{}}",
                ),
            ),
            activeCodexAccountId = "codex-account-one",
            codexAccountSettings = LanWebSyncedCodexAccountSettings(reservePercent = 12.0),
            github = LanWebSyncedGitHubCredential(
                apiBaseUrl = "https://github.example/api/v3",
                token = "github-token-secret",
                viewerLogin = "murong-user",
            ),
            githubAccounts = listOf(
                LanWebSyncedGitHubAccount(
                    id = "github-account-one",
                    label = "@murong-user",
                    login = "murong-user",
                    token = "github-token-secret",
                ),
            ),
            activeGitHubAccountId = "github-account-one",
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
            mediaSettings = LanWebSyncedMediaSettings(
                visionRoutingEnabled = true,
                visionProviderId = "openai-compatible",
                visionProfileId = "vision-profile",
                visionModel = "gpt-5.2",
                visionCustomBaseUrl = "https://vision.example.test/v1",
                imageGenerationProviderId = "openai-compatible",
                imageGenerationProfileId = "image-profile",
                imageGenerationModel = "gpt-image-2",
                imageGenerationCustomBaseUrl = "https://images.example.test/v1",
                imageGenerationSize = "1536x1024",
                imageGenerationQuality = "high",
                imageGenerationFormat = "png",
                imageGenerationCompression = 90,
                imageGenerationPartialImages = 2,
            ),
            mediaCredentials = LanWebSyncedMediaCredentials(
                visionCustomApiKey = "vision-key-secret",
                imageGenerationCustomApiKey = "image-key-secret",
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

        assertEquals(8, restored.schemaVersion)
        assertEquals("windows-session-1", restored.sessions.single().sourceSessionId)
        assertEquals("windows", restored.sessions.single().originPlatform)
        assertEquals("windows-session-1", restored.sessions.single().originSessionId)
        assertEquals("murong-portable-session", restored.sessions.single().document["format"]?.toString()?.trim('"'))
        assertEquals("api-key-secret", restored.providers.single().apiKey)
        assertTrue(restored.codexAuthJson!!.contains("tokens"))
        assertEquals("one@example.com", restored.codexAccounts.single().email)
        assertEquals("codex-account-one", restored.activeCodexAccountId)
        assertEquals(12.0, restored.codexAccountSettings?.reservePercent)
        assertEquals("github-token-secret", restored.github?.token)
        assertEquals("murong-user", restored.github?.viewerLogin)
        assertEquals("github-token-secret", restored.githubAccounts.single().token)
        assertEquals("github-account-one", restored.activeGitHubAccountId)
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
        assertTrue(restored.mediaSettings?.visionRoutingEnabled == true)
        assertEquals("vision-profile", restored.mediaSettings?.visionProfileId)
        assertEquals("1536x1024", restored.mediaSettings?.imageGenerationSize)
        assertEquals("image-key-secret", restored.mediaCredentials?.imageGenerationCustomApiKey)
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
