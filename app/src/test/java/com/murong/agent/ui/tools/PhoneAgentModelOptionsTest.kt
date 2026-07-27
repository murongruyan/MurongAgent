package com.murong.agent.ui.tools

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.provider.BuiltinLocalProvider
import com.murong.agent.core.tool.BuiltinVisionModels
import com.murong.agent.core.tool.BuiltinVisionTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneAgentModelOptionsTest {
    @Test
    fun pickerListsEveryInstalledModelAndOnlyEnablesVisionModels() {
        val installed = linkedSetOf(
            BuiltinVisionTier.PRO_4B,
            BuiltinVisionTier.GLM_EDGE_V_2B,
            BuiltinVisionTier.DEEPSEEK_R1_QWEN_1_5B
        )

        val localOptions = buildPhoneAgentModelOptions(ProviderConfig(), installed)
            .filter { it.providerId == BuiltinLocalProvider.ID }

        assertEquals(installed.size, localOptions.size)
        assertEquals(
            installed.map { BuiltinVisionModels.descriptor(it).id }.toSet(),
            localOptions.map { it.relayId }.toSet()
        )
        assertTrue(localOptions.single { it.relayId == "qwen3.5-4b-pro" }.enabled)
        assertTrue(localOptions.single { it.relayId == "glm-edge-v-2b" }.enabled)
        assertFalse(
            localOptions.single { it.relayId == "deepseek-r1-distill-qwen-1.5b" }.enabled
        )
    }

    @Test
    fun textOnlyCurrentLocalModelCannotBeUsedByPhoneAgentButRemainsAvailableForCode() {
        val config = ProviderConfig(
            activeProviderId = BuiltinLocalProvider.ID,
            builtinLocalModelOverride = BuiltinVisionModels.GLM_EDGE_1_5B_CHAT.id,
        )
        val installed = setOf(BuiltinVisionTier.GLM_EDGE_1_5B_CHAT)

        val phoneFollow = buildPhoneAgentModelOptions(config, installed).first()
        val codeOptions = buildAssistantCodeModelOptions(config, installed)

        assertFalse(phoneFollow.enabled)
        assertTrue(phoneFollow.subtitle.contains("纯文本模型"))
        assertTrue(
            codeOptions.single {
                it.providerId == BuiltinLocalProvider.ID &&
                    it.relayId == BuiltinVisionModels.GLM_EDGE_1_5B_CHAT.id
            }.enabled,
        )
    }
}
