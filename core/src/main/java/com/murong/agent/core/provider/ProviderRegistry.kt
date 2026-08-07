package com.murong.agent.core.provider

/**
 * Provider 注册中心
 *
 * 通过 ID 查找 Provider 实例。
 */
object ProviderRegistry {

    private val providers = mutableMapOf<String, ModelProvider>()

    init {
        register(DeepSeekProvider())
        register(OpenAIProvider())
        register(ClaudeProvider())
        register(GeminiProvider())
        ProviderPresetCatalog.all().forEach { preset ->
            if (providers[preset.providerId] == null) {
                register(
                    OfficialOpenAICompatibleProvider(
                        id = preset.providerId,
                        name = preset.displayName,
                        defaultBaseUrl = preset.defaultBaseUrl,
                        defaultModel = preset.defaultModel,
                        supportedReasoningEfforts = preset.supportedReasoningEfforts,
                    )
                )
            }
        }
        register(BuiltinLocalProvider())
    }

    fun register(provider: ModelProvider) {
        providers[provider.id] = provider
    }

    fun getProvider(id: String): ModelProvider? = providers[id]

    /** Providers that expose editable remote/API connection profiles. */
    fun getAllProviders(): List<ModelProvider> =
        providers.values.filterNot { it.id == BuiltinLocalProvider.ID }

    fun getActiveProvider(configId: String): ModelProvider {
        return getProvider(configId) ?: providers["deepseek"]!!
    }
}
