package com.murong.agent.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json

class MurongPortableBackupStateTest {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    @Test
    fun desktopJson_decodesWithSharedFieldNamesAndNoSecrets() {
        val state = json.decodeFromString(
            MurongPortableBackupState.serializer(),
            desktopPortableState()
        )
        validatePortableBackupEnvelope(state)

        assertEquals("windows", state.sourcePlatform)
        assertEquals("desktop-provider", state.deviceState.providers.single().profileId)
        assertEquals("desktop-session-1", state.sessions.single().sourceSessionId)
        assertEquals(true, state.sessions.single().document.toString().contains("桌面任务"))
    }

    @Test
    fun portableEnvelope_rejectsEverySecretChannelAndDuplicateSessionIds() {
        val base = json.decodeFromString(MurongPortableBackupState.serializer(), desktopPortableState())
        assertFailsWith<IllegalArgumentException> {
            validatePortableBackupEnvelope(
                base.copy(
                    deviceState = base.deviceState.copy(
                        providers = base.deviceState.providers.map { it.copy(apiKey = "SECRET") }
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validatePortableBackupEnvelope(
                base.copy(sessions = base.sessions + base.sessions.single())
            )
        }
    }

    private fun desktopPortableState(): String = """
        {
          "schemaVersion":1,
          "sourcePlatform":"windows",
          "generatedAt":1700000000000,
          "deviceState":{
            "schemaVersion":4,
            "sourcePlatform":"windows",
            "generatedAt":1700000000000,
            "activeProviderId":"openai-compatible",
            "activeProfileId":"desktop-provider",
            "providers":[{
              "profileId":"desktop-provider",
              "providerId":"openai-compatible",
              "name":"Desktop Provider",
              "baseUrl":"https://example.test/v1",
              "model":"desktop-model",
              "reasoningEffort":"high"
            }],
            "agentSettings":{
              "approvalMode":"ask",
              "systemPrompt":"portable prompt",
              "responseVerbosity":"balanced"
            },
            "knowledge":{"rules":[],"memories":[],"skills":[]},
            "mcpServers":[],
            "mcpCredentialsIncluded":false,
            "savedWorkflows":[]
          },
          "sessions":[{
            "sourceSessionId":"desktop-session-1",
            "document":{
              "format":"murong-portable-session",
              "formatVersion":1,
              "exportedAtEpochMillis":1700000000000,
              "sourcePlatform":"windows",
              "session":{
                "title":"桌面任务",
                "createdAtEpochMillis":10,
                "updatedAtEpochMillis":20,
                "messages":[{"role":"user","content":"继续","createdAtEpochMillis":11}]
              }
            }
          }]
        }
    """.trimIndent()
}
