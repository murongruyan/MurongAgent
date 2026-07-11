package com.murong.agent.core.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderConfigPricingTest {

    @Test
    fun officialOpenAiModel_defaultsUnsetMultiplierToOne() {
        val config = ProviderConfig(
            activeProviderId = "openai-compatible",
            openaiBaseUrl = "https://api.openai.com/v1",
            openaiModel = "gpt-5.6-sol",
            openaiPromptPricePer1M = 0.0,
            openaiCompletionPricePer1M = 0.0
        )

        assertEquals(5.0, config.getPromptPricePer1M("openai-compatible"))
        assertEquals(30.0, config.getCompletionPricePer1M("openai-compatible"))
    }

    @Test
    fun officialOpenAiModel_treatsConfiguredValuesAsMultipliers() {
        val config = ProviderConfig(
            activeProviderId = "openai-compatible",
            openaiBaseUrl = "https://api.openai.com/v1",
            openaiModel = "gpt-5.6-terra",
            openaiPromptPricePer1M = 1.6,
            openaiCompletionPricePer1M = 2.0
        )

        assertEquals(4.0, config.getPromptPricePer1M("openai-compatible"))
        assertEquals(30.0, config.getCompletionPricePer1M("openai-compatible"))
    }

    @Test
    fun proxyOpenAiModel_alsoTreatsConfiguredValuesAsMultipliers() {
        val config = ProviderConfig(
            activeProviderId = "openai-compatible",
            openaiBaseUrl = "https://example-proxy.invalid/v1",
            openaiModel = "gpt-5.6-terra",
            openaiPromptPricePer1M = 1.6,
            openaiCompletionPricePer1M = 2.0
        )

        assertEquals(4.0, config.getPromptPricePer1M("openai-compatible"))
        assertEquals(30.0, config.getCompletionPricePer1M("openai-compatible"))
    }

    @Test
    fun proxyClaudeModel_defaultsUnsetMultiplierToOne() {
        val config = ProviderConfig(
            activeProviderId = "claude",
            claudeBaseUrl = "https://relay.example.com",
            claudeModel = "claude-fable-5",
            claudePromptPricePer1M = 0.0,
            claudeCompletionPricePer1M = 0.0
        )

        assertEquals(10.0, config.getPromptPricePer1M("claude"))
        assertEquals(50.0, config.getCompletionPricePer1M("claude"))
    }
}
