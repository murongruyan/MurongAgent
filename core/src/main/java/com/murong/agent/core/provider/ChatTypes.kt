package com.murong.agent.core.provider

import kotlinx.serialization.Serializable

/**
 * 聊天请求
 */
@Serializable
data class ChatRequest(
    val messages: List<ChatMessage>,
    val model: String,
    val temperature: Double = 0.7,
    val maxTokens: Int = 8192,
    val stream: Boolean = true,
    /** reasoning_effort: low / medium / high / max */
    val reasoningEffort: String? = null,
    /** DeepSeek V4 thinking 开关：enabled / disabled */
    val thinkingMode: String? = null,
    /** Tool 定义（JSON 数组字符串） */
    val tools: String? = null
)

/**
 * 聊天消息
 */
@Serializable
data class ChatMessage(
    val role: String,       // "system" | "user" | "assistant" | "tool"
    val content: String? = null,
    val images: List<ChatImageAttachment> = emptyList(),
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
data class ChatImageAttachment(
    val mimeType: String,
    val base64Data: String,
    val fileName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null
)

/**
 * Tool Call（模型发起的工具调用请求）
 */
@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction
)

@Serializable
data class ToolCallFunction(
    val name: String,
    val arguments: String   // JSON string
)

/**
 * Tool 结果（工具执行完成后返回给模型）
 */
@Serializable
data class ToolResult(
    val role: String = "tool",
    val toolCallId: String,
    val content: String
)

/**
 * 工具定义（发给模型用的 function schema）
 */
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDef
)

data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

/**
 * 流式响应的增量片
 */
sealed class StreamDelta {
    data class Content(val text: String) : StreamDelta()
    data class Reasoning(val text: String) : StreamDelta()
    data class ToolCallStart(val id: String, val name: String) : StreamDelta()
    data class ToolCallDelta(val id: String, val argumentsDelta: String) : StreamDelta()
    data object Done : StreamDelta()
    data class Error(val message: String) : StreamDelta()
}

/**
 * 完整的聊天响应
 */
data class ChatResponse(
    val content: String?,
    val toolCalls: List<ToolCall>?,
    val usage: Usage? = null
)

data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    /** DeepSeek-specific */
    val promptCacheHitTokens: Int? = null,
    val promptCacheMissTokens: Int? = null
)
