package dev.reasonix.mobile.core.provider

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
    }

    fun register(provider: ModelProvider) {
        providers[provider.id] = provider
    }

    fun getProvider(id: String): ModelProvider? = providers[id]

    fun getAllProviders(): List<ModelProvider> = providers.values.toList()

    fun getActiveProvider(configId: String): ModelProvider {
        return getProvider(configId) ?: providers["deepseek"]!!
    }
}
