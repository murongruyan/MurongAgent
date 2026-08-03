package com.murong.agent.ui.settings

import com.murong.agent.core.config.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderModelCatalogTest {
    @Test
    fun fetchedCatalog_replacesStaticRecommendationsInsteadOfMixingThemIn() {
        val models = mergeProviderModelCandidates(
            providerId = "openai-compatible",
            currentModel = "custom-production-model",
            fetchedModels = listOf("gpt-live-a", "gpt-live-b")
        )

        assertEquals(listOf("custom-production-model", "gpt-live-a", "gpt-live-b"), models)
        assertFalse(models.contains("gpt-5.6-sol"))
    }

    @Test
    fun officialCatalog_includesEveryBuiltInOfficialProvider() {
        val providers = listOf("kimi", "glm", "qwen", "minimax", "grok", "mimo", "hy3", "gemini")

        providers.forEach { providerId ->
            assertTrue(builtinProviderModels(providerId).isNotEmpty(), "missing $providerId recommendation")
        }
        assertTrue(builtinProviderModels("gemini").contains("gemini-3.6-flash"))
        assertTrue(builtinProviderModels("mimo").contains("mimo-v2.5-pro"))
    }

    @Test
    fun chatModelSelection_writesIntoActiveRelayAfterLegacyMigration() {
        val migrated = ProviderConfig(
            activeProviderId = "openai-compatible",
            openaiApiKey = "openai-key",
            openaiModel = "gpt-5.6-sol"
        ).withLegacyRelayConfigurations()

        val switched = migrated.withProviderModelSelection("openai-compatible", "gpt-5.5")

        assertEquals("gpt-5.5", switched.getResolvedModel("openai-compatible"))
        assertFalse(switched.isModelAutoSelectionEnabled("openai-compatible"))
    }

    @Test
    fun chatModelSelection_forDeepseekWritesIntoActiveRelayAndPreset() {
        val migrated = ProviderConfig(
            activeProviderId = "deepseek",
            deepseekApiKey = "deepseek-key",
            deepseekModel = "deepseek-v4-flash"
        ).withLegacyRelayConfigurations()

        val switched = migrated.withProviderModelSelection("deepseek", "deepseek-v4-pro")

        assertEquals("deepseek-v4-pro", switched.getResolvedModel("deepseek"))
        assertEquals("pro", switched.deepseekModelPreset)
        assertFalse(switched.isModelAutoSelectionEnabled("deepseek"))
    }

    @Test
    fun chatModelSelection_forClaudeWritesIntoActiveRelay() {
        val migrated = ProviderConfig(
            activeProviderId = "claude",
            claudeApiKey = "claude-key",
            claudeModel = "claude-fable-5"
        ).withLegacyRelayConfigurations()

        val switched = migrated.withProviderModelSelection("claude", "claude-opus-4-8")

        assertEquals("claude-opus-4-8", switched.getResolvedModel("claude"))
        assertFalse(switched.isModelAutoSelectionEnabled("claude"))
    }
}
