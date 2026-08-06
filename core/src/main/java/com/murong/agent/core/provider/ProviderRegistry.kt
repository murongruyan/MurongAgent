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
        register(OfficialOpenAICompatibleProvider(
            id = "kimi", name = "Kimi (Moonshot)",
            defaultBaseUrl = "https://api.moonshot.cn/v1", defaultModel = "kimi-k3",
            supportedReasoningEfforts = listOf("on", "off")
        ))
        register(OfficialOpenAICompatibleProvider(
            id = "glm", name = "智谱 GLM",
            defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4", defaultModel = "glm-5.2",
            supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max")
        ))
        register(OfficialOpenAICompatibleProvider(
            id = "qwen", name = "通义千问 Qwen",
            defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen3.8-max-preview",
            supportedReasoningEfforts = listOf("low", "medium", "xhigh")
        ))
        register(OfficialOpenAICompatibleProvider(
            id = "modelscope-vision", name = "魔搭 ModelScope Vision",
            defaultBaseUrl = "https://api-inference.modelscope.cn/v1",
            defaultModel = "Qwen/Qwen3-VL-30B-A3B-Instruct"
        ))
        register(OfficialOpenAICompatibleProvider(
            id = "minimax", name = "MiniMax",
            defaultBaseUrl = "https://api.minimaxi.com/v1", defaultModel = "MiniMax-M3"
        ))
        register(OfficialOpenAICompatibleProvider(
            id = "grok", name = "xAI Grok",
            defaultBaseUrl = "https://api.x.ai/v1/responses", defaultModel = "grok-4.5",
            supportedReasoningEfforts = listOf("low", "medium", "high")
        ))
        register(OfficialOpenAICompatibleProvider(
            id = "mimo", name = "小米 MiMo",
            defaultBaseUrl = "https://api.xiaomimimo.com/v1/responses", defaultModel = "mimo-v2.5-pro"
        ))
        register(OfficialOpenAICompatibleProvider(
            id = "hy3", name = "腾讯混元 Hy3",
            defaultBaseUrl = "https://tokenhub.tencentmaas.com/v1", defaultModel = "hy3-preview"
        ))
        register(GeminiProvider())
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
