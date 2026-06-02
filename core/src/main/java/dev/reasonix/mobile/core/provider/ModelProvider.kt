package dev.reasonix.mobile.core.provider

/**
 * Model Provider 统一接口
 *
 * 所有 AI 提供商都实现此接口，Agent Loop 只依赖它。
 */
interface ModelProvider {
    /** 显示名称，如 "DeepSeek", "OpenAI Compatible", "Claude" */
    val name: String

    /** 配置页显示的标识 */
    val id: String

    /** 默认 Base URL（用户可覆盖） */
    val defaultBaseUrl: String

    /** 推荐的默认模型 */
    val defaultModel: String

    /** 是否支持 Reasoning/Thinking */
    val supportsReasoning: Boolean

    /** 当前 Provider 支持的推理档位 */
    val supportedReasoningEfforts: List<String>
        get() = emptyList()

    /** 把模型 ID 转成更适合给用户展示的名称 */
    fun formatModelDisplayName(modelId: String): String = modelId.trim().ifBlank { defaultModel }

    /** 把推理档位转成更适合给用户展示的名称 */
    fun formatReasoningDisplayName(reasoningEffort: String?): String? = when (reasoningEffort?.trim()?.lowercase()) {
        "low" -> "低推理"
        "medium" -> "中推理"
        "high" -> "高推理"
        "xhigh" -> "超高推理"
        "max" -> "最大推理"
        else -> null
    }

    /** 统一生成“模型 + 推理档位”的展示标签 */
    fun buildExecutionProfileLabel(modelId: String, reasoningEffort: String?): String {
        val modelLabel = formatModelDisplayName(modelId)
        val reasoningLabel = formatReasoningDisplayName(reasoningEffort)
        return if (reasoningLabel == null) modelLabel else "$modelLabel $reasoningLabel"
    }

    /** 设置页里用于解释当前请求配置的说明文本 */
    fun buildReasoningHint(modelId: String, reasoningEffort: String?): String? = null

    /** 流式聊天 */
    suspend fun chatStream(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String? = null,
        onDelta: (StreamDelta) -> Unit
    ): ChatResponse

    /** 非流式聊天（兜底） */
    suspend fun chat(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String? = null
    ): ChatResponse
}
