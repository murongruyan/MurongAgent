package com.murong.agent.core.loop

import com.murong.agent.core.config.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ContextCompressionPolicyTest {

    @Test
    fun compressionPreview_countsToolResultsAsConversationEvidence() {
        val messages = (1L..14L).map { id ->
            ChatMessageUi(
                id = id,
                role = if (id % 2L == 0L) "tool_exec" else "user",
                content = if (id % 2L == 0L) {
                    "Tool result: shell\nexit=0\nverified item $id"
                } else {
                    "Please inspect item $id"
                }
            )
        }

        val preview = assertNotNull(estimateContextCompressionPreview(SessionState(messages = messages)))

        assertEquals(6, preview.compressibleMessageCount)
        assertEquals(8, preview.recentMessageCount)
    }

    @Test
    fun autoCompressionThreshold_respectsConfiguredModelContextClass() {
        assertEquals(
            96_000,
            resolveAutoCompressionTriggerTokens(
                ProviderConfig(activeProviderId = "openai-compatible", openaiModel = "gpt-5.6-sol")
            )
        )
        assertEquals(
            48_000,
            resolveAutoCompressionTriggerTokens(
                ProviderConfig(activeProviderId = "claude", claudeModel = "claude-fable-5")
            )
        )
        assertEquals(
            24_000,
            resolveAutoCompressionTriggerTokens(
                ProviderConfig(activeProviderId = "deepseek", deepseekModel = "deepseek-v4-flash")
            )
        )
    }

    @Test
    fun autoCompressionThreshold_usesActiveRelaysCustomContextWindow() {
        val relay = com.murong.agent.core.config.RelayConfig(
            id = "custom",
            model = "my-private-model",
            contextWindowTokens = 32_768
        )
        val config = ProviderConfig(
            activeProviderId = "openai-compatible",
            openaiRelays = listOf(relay),
            activeOpenaiRelayId = relay.id,
            maxTokens = 8_192
        )

        assertEquals(13_107, resolveAutoCompressionTriggerTokens(config))
    }
}
