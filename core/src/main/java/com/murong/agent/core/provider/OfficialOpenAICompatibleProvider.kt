package com.murong.agent.core.provider

/**
 * Metadata wrapper for vendors whose documented endpoint is OpenAI-compatible.
 *
 * The actual wire-format deviations (Qwen/GLM thinking fields, Responses
 * endpoints, and vendor-specific reasoning ranges) are handled by
 * [OpenAIProvider] from the configured official endpoint.  A distinct provider
 * ID is still useful to the settings UI: it gives every official credential its
 * own model list, default endpoint and reasoning choices instead of pretending
 * that all vendors are generic OpenAI relays.
 */
internal class OfficialOpenAICompatibleProvider(
    override val id: String,
    override val name: String,
    override val defaultBaseUrl: String,
    override val defaultModel: String,
    override val supportedReasoningEfforts: List<String> = emptyList()
) : ModelProvider {
    private val delegate = OpenAIProvider()

    override val supportsReasoning: Boolean
        get() = supportedReasoningEfforts.isNotEmpty()

    override fun formatModelDisplayName(modelId: String): String = modelId.trim().ifBlank { defaultModel }

    override fun buildReasoningHint(modelId: String, reasoningEffort: String?): String? = when (id) {
        "kimi" -> "Kimi 使用官方 thinking 开关；“开启思考”会发送 thinking.type=enabled。"
        "qwen" -> "Qwen3.8-Max-Preview 支持 low / medium / xhigh；会原样保留每轮 reasoning_content。"
        "glm" -> "GLM-5.2 的 low / medium 会映射为 high，xhigh / max 会映射为 max。"
        "grok" -> "Grok 4.5 只接受 low、medium、high；更高档位会安全收敛到 high。"
        "minimax" -> "MiniMax 官方推荐 Anthropic 协议，也提供 OpenAI 兼容接口；本连接使用官方 OpenAI 兼容端点。"
        else -> null
    }

    override fun formatReasoningDisplayName(reasoningEffort: String?): String? = when (reasoningEffort?.trim()?.lowercase()) {
        "on" -> "开启思考"
        "off" -> "关闭思考"
        "low" -> "低推理"
        "medium" -> "中推理"
        "high" -> "高推理"
        "xhigh" -> "超高推理"
        "max" -> "最大推理"
        else -> null
    }

    override suspend fun chatStream(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String?,
        onDelta: (StreamDelta) -> Unit
    ): ChatResponse = delegate.chatStream(request, apiKey, baseUrl ?: defaultBaseUrl, onDelta)

    override suspend fun chat(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String?
    ): ChatResponse = delegate.chat(request, apiKey, baseUrl ?: defaultBaseUrl)
}
