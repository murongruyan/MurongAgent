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
