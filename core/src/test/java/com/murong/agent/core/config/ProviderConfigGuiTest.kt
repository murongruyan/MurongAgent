package com.murong.agent.core.config

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderConfigGuiTest {
    @Test
    fun guiDefaultsAreLocalFirstAndPrivateByDefault() {
        val config = ProviderConfig()

        assertTrue("gui" in config.enabledBuiltinTools)
        assertTrue(config.guiInferenceMode == GuiInferenceMode.LOCAL_FIRST)
        assertFalse(config.guiAllowRemoteSemanticTree)
        assertFalse(config.guiAllowRemoteScreenshots)
        assertFalse(config.guiAllowRemoteFullScreen)
        assertTrue(config.phoneAgentEnabled)
        assertEquals("", config.phoneAgentProviderId)
        assertEquals("", config.phoneAgentRelayId)
        assertEquals("", config.assistantCodeProviderId)
        assertEquals("", config.assistantCodeRelayId)
        assertEquals("", config.builtinLocalModelOverride)
        assertFalse(config.phoneAgentAllowRemoteScreenshots)
        assertTrue(config.phoneAgentSafeMode)
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyDedicatedPhoneAgentConnectionIsCleared() {
        val migrated = ProviderConfig(
            phoneAgentBaseUrl = "https://legacy.example/v1",
            phoneAgentApiKey = "legacy-secret",
            phoneAgentModel = "autoglm-phone"
        ).withUnifiedPhoneAgentModelConfig()

        assertEquals("", migrated.phoneAgentBaseUrl)
        assertEquals("", migrated.phoneAgentApiKey)
        assertEquals("", migrated.phoneAgentModel)
    }

    @Test
    fun missingPhoneAgentRelaySafelyFallsBackToCurrentChatProfile() {
        val config = ProviderConfig(
            activeProviderId = "deepseek",
            phoneAgentProviderId = "openai-compatible",
            phoneAgentRelayId = "removed-relay"
        )

        val resolved = config.getPhoneAgentResolvedConfig()

        assertEquals("deepseek", resolved.activeProviderId)
        assertEquals(config, resolved)
    }

    @Test
    fun missingAssistantCodeRelaySafelyFallsBackToCurrentChatProfile() {
        val config = ProviderConfig(
            activeProviderId = "deepseek",
            assistantCodeProviderId = "openai-compatible",
            assistantCodeRelayId = "removed-relay",
        )

        val resolved = config.getAssistantCodeResolvedConfig()

        assertEquals("deepseek", resolved.activeProviderId)
        assertEquals(config, resolved)
    }

    @Test
    fun localModelOverrideIsRuntimeOnlyAndNeverPersisted() {
        val encoded = Json.encodeToString(
            ProviderConfig.serializer(),
            ProviderConfig(builtinLocalModelOverride = "temporary-local-model")
        )

        assertFalse(encoded.contains("builtinLocalModelOverride"))
        assertFalse(encoded.contains("temporary-local-model"))
    }

    @Test
    fun localModelAddressDetectionCoversLoopbackAndPrivateNetworks() {
        assertTrue(isLocalModelBaseUrl("http://127.0.0.1:11434/v1"))
        assertTrue(isLocalModelBaseUrl("http://localhost:1234/v1"))
        assertTrue(isLocalModelBaseUrl("http://10.0.2.2:8080/v1"))
        assertTrue(isLocalModelBaseUrl("http://192.168.50.20:8000/v1"))
        assertTrue(isLocalModelBaseUrl("http://desktop.local:8000/v1"))
        assertTrue(isLocalModelBaseUrl("http://[::1]:8000/v1"))
        assertFalse(isLocalModelBaseUrl("https://api.openai.com/v1"))
        assertFalse(isLocalModelBaseUrl("https://fd-public.example/v1"))
        assertFalse(isLocalModelBaseUrl("file:///tmp/model"))
    }

    @Test
    fun localCustomRelayDoesNotRequireApiKey() {
        val localRelay = RelayConfig(
            id = "local",
            name = "Local",
            baseUrl = "http://192.168.1.8:11434/v1",
            model = "qwen2.5-vl:7b",
            kind = RelayKind.CUSTOM
        )
        val config = ProviderConfig(
            activeProviderId = "openai-compatible",
            openaiRelays = listOf(localRelay),
            activeOpenaiRelayId = localRelay.id
        )

        assertTrue(config.isRelayConfigured("openai-compatible", localRelay))
        assertTrue(config.isActiveProviderLocal())
        assertTrue(config.hasUsableActiveProviderCredentials())
    }

    @Test
    fun builtinLocalProviderIsLocalButRequiresAnInstalledModel() {
        val config = ProviderConfig(activeProviderId = "murong-local")

        assertTrue(config.isActiveProviderLocal())
        assertFalse(config.hasUsableActiveProviderCredentials())
        assertTrue(config.getActiveModel().isNotBlank())
        assertFalse(config.isModelAutoSelectionEnabled())
        assertFalse(config.isReasoningAutoSelectionEnabled())
    }

    @Test
    fun legacyDefaultsGainGuiOnceButUserCanDisableItLater() {
        val legacyDefaults = DEFAULT_ENABLED_BUILTIN_TOOLS.filterNot { it == "gui" }
        val migrated = ProviderConfig(
            enabledBuiltinTools = legacyDefaults,
            guiAutomationDefaultsInitialized = false
        ).withGuiAutomationDefaults()

        assertTrue("gui" in migrated.enabledBuiltinTools)
        val disabled = migrated.copy(
            enabledBuiltinTools = migrated.enabledBuiltinTools.filterNot { it == "gui" }
        ).withGuiAutomationDefaults()
        assertFalse("gui" in disabled.enabledBuiltinTools)
    }
}
