package com.murong.agent.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderConfigRelayTest {

    @Test
    fun legacyConfiguration_isMigratedIntoOneSelectedRelayPerProvider() {
        val migrated = ProviderConfig(
            deepseekApiKey = "deepseek-key",
            deepseekBaseUrl = "deepseek-relay.example.com/v1",
            deepseekModel = "deepseek-custom",
            openaiApiKey = "openai-key",
            openaiBaseUrl = "https://openai-relay.example.com/v1",
            openaiModel = "gpt-custom",
            claudeApiKey = "claude-key",
            claudeBaseUrl = "https://claude-relay.example.com",
            claudeModel = "claude-custom"
        ).withLegacyRelayConfigurations()

        listOf("deepseek", "openai-compatible", "claude").forEach { providerId ->
            val relay = assertNotNull(migrated.getActiveRelay(providerId))
            assertEquals(relay.id, migrated.getActiveRelayId(providerId))
            assertTrue(migrated.getRelayConfigs(providerId).any { it.id.startsWith("legacy-") })
        }
        assertEquals("https://deepseek-relay.example.com/v1", migrated.getBaseUrl("deepseek"))
        assertEquals("deepseek-key", migrated.getApiKey("deepseek"))
        assertEquals("gpt-custom", migrated.getResolvedModel("openai-compatible"))
    }

    @Test
    fun migration_doesNotCreateExtraOfficialPresets() {
        val custom = RelayConfig(id = "custom", baseUrl = "https://relay.example.com", kind = RelayKind.CUSTOM)
        val migrated = ProviderConfig(openaiRelays = listOf(custom)).withLegacyRelayConfigurations()

        assertEquals(listOf(custom), migrated.getRelayConfigs("openai-compatible"))
        assertEquals(1, migrated.getRelayConfigs("deepseek").size)
        assertEquals(1, migrated.getRelayConfigs("claude").size)
    }

    @Test
    fun removeRelay_allowsOfficialRelayAndClearsActiveProviderWhenLastEntry() {
        val official = RelayConfig(id = "official", name = "官方 DeepSeek", kind = RelayKind.OFFICIAL)
        val config = ProviderConfig(activeProviderId = "deepseek").withRelayConfigs(
            providerId = "deepseek",
            relays = listOf(official),
            activeRelayId = official.id
        )

        val updated = config.removeRelay("deepseek", official.id)

        assertTrue(updated.getRelayConfigs("deepseek").isEmpty())
        assertEquals("", updated.activeProviderId)
        assertEquals("", updated.getActiveRelayId("deepseek"))
    }

    @Test
    fun deletedProviderRelayList_staysEmptyAfterMigration() {
        val config = ProviderConfig(
            deepseekRelays = emptyList(),
            activeDeepseekRelayId = ""
        ).withLegacyRelayConfigurations()

        assertTrue(config.getRelayConfigs("deepseek").isEmpty())
        assertEquals("", config.getActiveRelayId("deepseek"))
    }

    @Test
    fun legacyRelayWithBaseUrl_remainsCustomAndUsesGeneratedCustomName() {
        val migrated = ProviderConfig(
            openaiBaseUrl = "https://existing-relay.example.com/v1",
            openaiApiKey = "existing-key"
        ).withLegacyRelayConfigurations()
        val legacy = assertNotNull(
            migrated.getRelayConfigs("openai-compatible")
                .firstOrNull { it.id == "legacy-openai-compatible" }
        )

        assertEquals(RelayKind.CUSTOM, legacy.kind)
        assertEquals("自定义 1", migrated.configuredConnectionLabel("openai-compatible", legacy))
        assertTrue(migrated.isRelayConfigured("openai-compatible", legacy))
    }

    @Test
    fun officialPresetWithoutApiKey_isNotConfigured() {
        val migrated = ProviderConfig().withLegacyRelayConfigurations()
        val official = assertNotNull(
            migrated.getRelayConfigs("claude").firstOrNull { it.kind == RelayKind.OFFICIAL }
        )

        assertTrue(!migrated.isRelayConfigured("claude", official))
    }

    @Test
    fun migration_preservesCustomRelayNamesWithoutAddingOfficialPreset() {
        val custom = RelayConfig(
            id = "custom",
            name = "我的备用站",
            baseUrl = "https://relay.example.com/v1",
            kind = RelayKind.CUSTOM
        )
        val migrated = ProviderConfig(openaiRelays = listOf(custom))
            .withLegacyRelayConfigurations()

        assertEquals("我的备用站", migrated.configuredConnectionLabel("openai-compatible", custom))
        assertEquals(listOf(custom), migrated.getRelayConfigs("openai-compatible"))
    }

    @Test
    fun selectConfiguration_switchesProviderAndRelayTogether() {
        val relay = RelayConfig(id = "custom", name = "自定义 1", kind = RelayKind.CUSTOM)
        val config = ProviderConfig().withRelayConfigs(
            providerId = "claude",
            relays = listOf(relay),
            activeRelayId = relay.id
        )

        val selected = config.selectConfiguration("claude", relay.id)

        assertEquals("claude", selected.activeProviderId)
        assertEquals(relay.id, selected.getActiveRelayId("claude"))
    }

    @Test
    fun legacyDefaultRelay_fallsBackToTheExistingProviderApiKey() {
        val config = ProviderConfig(
            activeProviderId = "openai-compatible",
            openaiApiKey = "existing-openai-key",
            openaiRelays = listOf(
                RelayConfig(id = "legacy-openai-compatible", apiKey = "", model = "gpt-5.6-sol")
            ),
            activeOpenaiRelayId = "legacy-openai-compatible"
        )

        assertEquals("existing-openai-key", config.getActiveApiKey())
    }

    @Test
    fun selectingAnotherRelay_changesActiveValuesWithoutDiscardingTheFirstRelay() {
        val first = RelayConfig(
            id = "first",
            name = "站点 A",
            baseUrl = "https://relay-a.example.com/v1",
            apiKey = "key-a",
            model = "model-a",
            reasoningEffort = "low",
            promptPricePer1M = 1.2,
            balanceAmount = 30.0
        )
        val second = RelayConfig(
            id = "second",
            name = "站点 B",
            baseUrl = "https://relay-b.example.com/v1",
            apiKey = "key-b",
            model = "model-b",
            reasoningEffort = "high",
            promptPricePer1M = 2.4,
            balanceAmount = 75.0
        )
        val config = ProviderConfig().withRelayConfigs(
            providerId = "openai-compatible",
            relays = listOf(first, second),
            activeRelayId = first.id
        )

        val switched = config.withRelayConfigs(
            providerId = "openai-compatible",
            relays = config.getRelayConfigs("openai-compatible"),
            activeRelayId = second.id
        )

        assertEquals(2, switched.getRelayConfigs("openai-compatible").size)
        assertEquals(first, switched.getRelayConfigs("openai-compatible").first())
        assertEquals("key-b", switched.getApiKey("openai-compatible"))
        assertEquals("https://relay-b.example.com/v1", switched.getBaseUrl("openai-compatible"))
        assertEquals("model-b", switched.getResolvedModel("openai-compatible"))
        assertEquals("reasoning/high", switched.getActiveThinkingMode())
        assertEquals(2.4, switched.getConfiguredPromptPricePer1M("openai-compatible"))
        assertEquals(75.0, switched.getBalanceAmount("openai-compatible"))
    }

    @Test
    fun removeRelay_removesCurrentEntryAndFallsBackToRemainingEntry() {
        val official = RelayConfig(id = "official", name = "官方 OpenAI", kind = RelayKind.OFFICIAL)
        val custom = RelayConfig(id = "custom", name = "自定义 1", kind = RelayKind.CUSTOM)
        val config = ProviderConfig(activeProviderId = "openai-compatible").withRelayConfigs(
            providerId = "openai-compatible",
            relays = listOf(official, custom),
            activeRelayId = custom.id
        )

        val updated = config.removeRelay("openai-compatible", custom.id)

        assertEquals(listOf(official), updated.getRelayConfigs("openai-compatible"))
        assertEquals(official.id, updated.getActiveRelayId("openai-compatible"))
        assertEquals("openai-compatible", updated.activeProviderId)
        assertEquals(listOf(custom), config.removeRelay("openai-compatible", official.id).getRelayConfigs("openai-compatible"))
    }

    @Test
    fun updateActiveRelay_persistsFractionalPriceMultipliers() {
        val config = ProviderConfig().withRelayConfigs(
            providerId = "openai-compatible",
            relays = listOf(RelayConfig(id = "relay", model = "gpt-5.6-sol")),
            activeRelayId = "relay"
        )

        val updated = config.updateActiveRelay("openai-compatible") {
            it.copy(promptPricePer1M = 0.2, completionPricePer1M = 0.35)
        }

        assertEquals(0.2, updated.getConfiguredPromptPricePer1M("openai-compatible"))
        assertEquals(0.35, updated.getConfiguredCompletionPricePer1M("openai-compatible"))
        assertEquals(1.0, updated.getPromptPricePer1M("openai-compatible"))
        assertEquals(10.5, updated.getCompletionPricePer1M("openai-compatible"))
    }

    @Test
    fun updatingCurrentRelay_doesNotModifyOtherSavedRelays() {
        val config = ProviderConfig().withRelayConfigs(
            providerId = "claude",
            relays = listOf(
                RelayConfig(id = "first", baseUrl = "https://first.example.com", apiKey = "first-key", model = "first-model"),
                RelayConfig(id = "second", baseUrl = "https://second.example.com", apiKey = "second-key", model = "second-model")
            ),
            activeRelayId = "second"
        )

        val updated = config.updateActiveRelay("claude") {
            it.copy(baseUrl = "https://changed.example.com", model = "changed-model")
        }

        assertEquals("https://first.example.com", updated.getRelayConfigs("claude")[0].baseUrl)
        assertEquals("first-model", updated.getRelayConfigs("claude")[0].model)
        assertEquals("https://changed.example.com", updated.getBaseUrl("claude"))
        assertEquals("changed-model", updated.getResolvedModel("claude"))
        assertTrue(updated.getRelayConfigs("claude").any { it.id == "first" && it.apiKey == "first-key" })
    }
}
