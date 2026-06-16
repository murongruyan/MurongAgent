package com.murong.agent.core.loop

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.provider.*
import com.murong.agent.core.tool.ToolFileChange
import com.murong.agent.core.tool.ToolApprovalRequest
import com.murong.agent.core.tool.ToolRegistry
import kotlinx.coroutines.delay

/**
 * Agent 事件——流式输出给 UI
 */
sealed class AgentEvent {
    data class ContentDelta(val text: String) : AgentEvent()
    data class ReasoningDelta(val text: String) : AgentEvent()
    data class ToolExecution(
        val toolName: String,
        val args: String? = null,
        val callId: String? = null,
        val isPartial: Boolean = false
    ) : AgentEvent()
    data class ToolResult(
        val toolName: String,
        val args: String,
        val result: String,
        val fileChanges: List<ToolFileChange> = emptyList()
    ) : AgentEvent()
    data class UsageUpdate(val usage: Usage) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data object Done : AgentEvent()
}

/**
 * Agent 运行时状态
 */
enum class AgentState {
    IDLE,
    THINKING,
    EXECUTING_TOOLS,
    ERROR
}

/**
 * Agent 循环——核心推理引擎
 *
 * 工作流程：
 * 1. 构建消息（System + History + User Input）
 * 2. 流式调用 Provider（带重试+指数退避）
 * 3. 如果有 Tool Calls → 执行工具 → 返回结果 → 第二轮请求
 * 4. 输出最终结果
 * 5. 最多 N 轮 Tool Call 迭代
 *
 * 错误处理策略：
 * - 网络/5xx: 重试 3 次，指数退避
 * - 401/403: 立即报错（API Key 无效）
 * - 429: 重试 2 次（可能限流）
 * - 工具错误: 把错误消息返回给模型继续
 */
class AgentLoop(
    private val provider: ModelProvider,
    private val toolRegistry: ToolRegistry,
    private val config: ProviderConfig
) {
    private val maxToolIterations = 999
    private val maxRetries = 3
    private var state = AgentState.IDLE

    fun getState(): AgentState = state

    /**
     * 处理用户消息，流式返回事件
     */
    suspend fun processMessage(
        userMessage: ChatMessage,
        history: List<ChatMessage>,
        stableSystemContext: String? = null,
        onEvent: (AgentEvent) -> Unit,
        requestApproval: suspend (ToolApprovalRequest) -> Boolean = { true }
    ) {
        state = AgentState.THINKING

        val messages = buildMessages(
            userMessage = userMessage,
            history = history,
            stableSystemContext = stableSystemContext
        )
        val currentMessages = messages.toMutableList()
        var toolIteration = 0
        var hasExecutedTools = false
        var hasRetriedForPostToolSummary = false
        val completedToolRuns = mutableListOf<CompletedToolRun>()
        val preferredSummaryLanguage = inferPreferredSummaryLanguage(
            userMessage = userMessage,
            history = history
        )

        while (toolIteration < maxToolIterations) {
            toolIteration++
            var streamedContentReceived = false
            var streamedReasoningReceived = false

            // ── 调用 Provider（含重试） ──────────────
            val response = callWithRetry(
                request = ChatRequest(
                    messages = currentMessages,
                    model = config.getActiveModel(),
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    stream = config.isStreamingResponsesEnabled(),
                    reasoningEffort = config.getActiveReasoningEffort(),
                    thinkingMode = config.getActiveThinkingMode(),
                    tools = toolRegistry.buildToolsJson()
                ),
                onDelta = { delta ->
                    when (delta) {
                        is StreamDelta.Content -> {
                            streamedContentReceived = true
                            onEvent(AgentEvent.ContentDelta(delta.text))
                        }
                        is StreamDelta.Reasoning -> {
                            streamedReasoningReceived = true
                            onEvent(AgentEvent.ReasoningDelta(delta.text))
                        }
                        is StreamDelta.ToolCallStart -> onEvent(
                            AgentEvent.ToolExecution(
                                toolName = delta.name,
                                callId = delta.id,
                                isPartial = true
                            )
                        )
                        is StreamDelta.ToolCallDelta -> { /* ignore partial args */ }
                        is StreamDelta.Error -> onEvent(AgentEvent.Error(delta.message))
                        is StreamDelta.Done -> { }
                    }
                },
                onEvent = onEvent
            )

            if (response == null) {
                // 所有重试失败，错误已通过 onEvent 发出
                onEvent(AgentEvent.Done)
                state = AgentState.ERROR
                return
            }

            val toolCalls = response.toolCalls
            val hasTextualResponse = !response.content.isNullOrBlank()
            if (hasTextualResponse || !toolCalls.isNullOrEmpty()) {
                currentMessages.add(
                    ChatMessage(
                        role = "assistant",
                        content = response.content,
                        toolCalls = toolCalls
                    )
                )
            }

            response.usage?.let { usage ->
                onEvent(AgentEvent.UsageUpdate(usage))
            }

            // ── 检查是否有 Tool Call ────────────────
            if (toolCalls.isNullOrEmpty()) {
                // 工具执行后如果模型没给任何自然语言结论，强制再补一轮总结请求。
                if (!hasTextualResponse && hasExecutedTools) {
                    if (!hasRetriedForPostToolSummary) {
                        hasRetriedForPostToolSummary = true
                        currentMessages.add(
                            ChatMessage(
                                role = "system",
                                content = buildPostToolSummaryReminder(
                                    completedToolRuns = completedToolRuns,
                                    language = preferredSummaryLanguage
                                )
                            )
                        )
                        state = AgentState.THINKING
                        continue
                    }

                    val fallbackSummary = buildSyntheticToolSummary(
                        completedToolRuns = completedToolRuns,
                        language = preferredSummaryLanguage
                    )
                    if (fallbackSummary.isNotBlank()) {
                        onEvent(AgentEvent.ContentDelta(fallbackSummary))
                    }
                }

                // 如果 response 有内容但流式事件没发出（例如非流式回包），现在补发
                val respContent = response.content
                if (!respContent.isNullOrBlank() && !streamedContentReceived) {
                    onEvent(AgentEvent.ContentDelta(respContent))
                }
                onEvent(AgentEvent.Done)
                state = AgentState.IDLE
                return
            }

            // ── 执行 Tool Calls ─────────────────────
            state = AgentState.EXECUTING_TOOLS
            for (tc in toolCalls) {
                hasExecutedTools = true
                hasRetriedForPostToolSummary = false
                onEvent(
                    AgentEvent.ToolExecution(
                        toolName = tc.function.name,
                        args = tc.function.arguments,
                        callId = tc.id,
                        isPartial = false
                    )
                )

                val tool = toolRegistry.getTool(tc.function.name)
                val result = if (tool != null) {
                    try {
                        val approvalRequest = tool.buildApprovalRequest(tc.function.arguments)
                        if (approvalRequest != null && !requestApproval(approvalRequest)) {
                            com.murong.agent.core.tool.ToolExecutionResult(
                                output = "Rejected by user: ${approvalRequest.summary}"
                            )
                        } else {
                            tool.executeWithResult(tc.function.arguments)
                        }
                    } catch (e: Exception) {
                        com.murong.agent.core.tool.ToolExecutionResult(
                            output = "Error executing `${tc.function.name}`: ${e.message}"
                        )
                    }
                } else {
                    com.murong.agent.core.tool.ToolExecutionResult(
                        output = "Error: Unknown tool `${tc.function.name}`. " +
                            "Available tools: ${toolRegistry.getAllTools().joinToString(", ") { it.name }}"
                    )
                }

                onEvent(
                    AgentEvent.ToolResult(
                        toolName = tc.function.name,
                        args = tc.function.arguments,
                        result = result.output,
                        fileChanges = result.fileChanges
                    )
                )
                completedToolRuns += CompletedToolRun(
                    toolName = tc.function.name,
                    arguments = tc.function.arguments,
                    result = result.output
                )

                currentMessages.add(
                    ChatMessage(
                        role = "tool",
                        toolCallId = tc.id,
                        content = result.output,
                        name = tc.function.name
                    )
                )
            }

            state = AgentState.THINKING
        }

        // ── 超过最大迭代次数 ──────────────────────
        onEvent(AgentEvent.Error(
            "⚠️ 已达到最大工具迭代次数 ($maxToolIterations)。" +
            "你的需求太多步骤了，建议拆分为多个请求。"
        ))
        onEvent(AgentEvent.Done)
        state = AgentState.IDLE
    }

    /**
     * 调用 Provider 并实现重试逻辑
     */
    private suspend fun callWithRetry(
        request: ChatRequest,
        onDelta: (StreamDelta) -> Unit,
        onEvent: (AgentEvent) -> Unit
    ): ChatResponse? {
        var lastError: String? = null

        for (attempt in 1..maxRetries) {
            try {
                return if (request.stream) {
                    provider.chatStream(
                        request = request,
                        apiKey = config.getActiveApiKey(),
                        baseUrl = config.getActiveBaseUrl(),
                        onDelta = onDelta
                    )
                } else {
                    provider.chat(
                        request = request,
                        apiKey = config.getActiveApiKey(),
                        baseUrl = config.getActiveBaseUrl()
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.net.UnknownHostException) {
                lastError = "🌐 网络不可达：${e.message}。请检查网络连接。"
                onEvent(AgentEvent.Error("⚠️ $lastError (重试 $attempt/$maxRetries)"))
            } catch (e: java.net.SocketTimeoutException) {
                lastError = "⏱️ 连接超时：${e.message}。中转站可能不稳定。"
                onEvent(AgentEvent.Error("⚠️ $lastError (重试 $attempt/$maxRetries)"))
            } catch (e: javax.net.ssl.SSLException) {
                lastError = "🔒 SSL 错误：${e.message}。中转站证书可能有问题。"
                onEvent(AgentEvent.Error("⚠️ $lastError (重试 $attempt/$maxRetries)"))
            } catch (e: java.io.IOException) {
                val msg = e.message ?: "未知网络错误"
                // 检查是否为 HTTP 错误码
                when {
                    msg.contains("401") || msg.contains("403") -> {
                        // 认证错误——不重试
                        onEvent(AgentEvent.Error(
                            "🔑 API Key 无效或被拒绝（$msg）。请在设置中检查 API Key。"
                        ))
                        return null
                    }
                    msg.contains("429") -> {
                        // 限流——重试
                        lastError = "⏳ 请求过于频繁（429），等待后重试…"
                        onEvent(AgentEvent.Error("⚠️ $lastError (重试 $attempt/$maxRetries)"))
                    }
                    msg.contains("5") && msg.length <= 4 -> {
                        // 服务端错误 5xx
                        lastError = "🔧 服务端错误（HTTP $msg）"
                        onEvent(AgentEvent.Error("⚠️ $lastError (重试 $attempt/$maxRetries)"))
                    }
                    else -> {
                        lastError = "⚠️ 网络错误：$msg"
                        onEvent(AgentEvent.Error("$lastError (重试 $attempt/$maxRetries)"))
                    }
                }
            } catch (e: Exception) {
                // 兜底：捕获所有其他异常（JSON 解析错误、空指针等）
                val msg = e.message ?: e.javaClass.simpleName
                lastError = "⚠️ 请求异常：$msg"
                onEvent(AgentEvent.Error("⚠️ $lastError (重试 $attempt/$maxRetries)"))
            }

            // 指数退避：1s, 2s, 4s
            if (attempt < maxRetries) {
                delay(1000L * (1 shl (attempt - 1)))
            }
        }

        // 所有重试都失败
        onEvent(AgentEvent.Error(
            "❌ ${lastError ?: "请求失败"}。已重试 $maxRetries 次，请稍后再试。"
        ))
        return null
    }

    /**
     * 构建消息列表
     */
    private fun buildMessages(
        userMessage: ChatMessage,
        history: List<ChatMessage>,
        stableSystemContext: String?
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // System Prompt
        messages.add(
            ChatMessage(
                role = "system",
                content = config.buildEffectiveSystemPrompt()
            )
        )

        messages.add(
            ChatMessage(
                role = "system",
                content = buildString {
                    append("Runtime model: provider=")
                    append(config.activeProviderId)
                    append(", model=")
                    append(config.getActiveModel())
                    append(". If asked what model you are, answer with this runtime model.")
                    append(" Do not claim another model family unless it matches the runtime model above.")
                }
            )
        )

        stableSystemContext?.takeIf { it.isNotBlank() }?.let {
            messages.add(
                ChatMessage(
                    role = "system",
                    content = it
                )
            )
        }

        // Conversation History（保留更长一点的窗口，避免多轮工具调用把关键上下文挤掉）
        val recentHistory = history.takeLast(32)
        messages.addAll(recentHistory)

        // 当前用户输入
        messages.add(
            userMessage
        )

        return messages
    }

    private fun buildPostToolSummaryReminder(
        completedToolRuns: List<CompletedToolRun>,
        language: SummaryLanguage
    ): String {
        val intro = when (language) {
            SummaryLanguage.CHINESE -> {
                "你刚刚已经执行了工具。不要静默结束，请基于工具结果直接对用户给出自然语言总结，说明做了什么、结果是什么、下一步建议是什么。"
            }
            SummaryLanguage.ENGLISH -> {
                "You just executed tools. Do not end silently. Reply to the user with a natural-language summary that explains what was done, what the results mean, and what should happen next."
            }
        }
        val toolLines = completedToolRuns
            .takeLast(6)
            .joinToString("\n") { toolRun ->
                when (language) {
                    SummaryLanguage.CHINESE -> {
                        "- ${toolRun.toolName}: ${toolRun.result.take(280)}"
                    }
                    SummaryLanguage.ENGLISH -> {
                        "- ${toolRun.toolName}: ${toolRun.result.take(280)}"
                    }
                }
            }
        return buildString {
            append(intro)
            if (toolLines.isNotBlank()) {
                append("\n\n")
                append(
                    when (language) {
                        SummaryLanguage.CHINESE -> "最近的工具结果："
                        SummaryLanguage.ENGLISH -> "Recent tool results:"
                    }
                )
                append('\n')
                append(toolLines)
            }
        }
    }

    private fun buildSyntheticToolSummary(
        completedToolRuns: List<CompletedToolRun>,
        language: SummaryLanguage
    ): String {
        if (completedToolRuns.isEmpty()) return ""
        val recentRuns = completedToolRuns.takeLast(4)
        return when (language) {
            SummaryLanguage.CHINESE -> buildString {
                append("工具已执行完成，结果如下：\n")
                recentRuns.forEachIndexed { index, toolRun ->
                    append(index + 1)
                    append(". `")
                    append(toolRun.toolName)
                    append("`：")
                    append(toolRun.result.take(220).ifBlank { "(无输出)" })
                    if (index != recentRuns.lastIndex) append('\n')
                }
                append("\n\n如果你愿意，我可以继续基于这些结果往下分析或执行下一步。")
            }
            SummaryLanguage.ENGLISH -> buildString {
                append("Tools finished running. Summary:\n")
                recentRuns.forEachIndexed { index, toolRun ->
                    append(index + 1)
                    append(". `")
                    append(toolRun.toolName)
                    append("`: ")
                    append(toolRun.result.take(220).ifBlank { "(no output)" })
                    if (index != recentRuns.lastIndex) append('\n')
                }
                append("\n\nI can continue from these results if you want me to.")
            }
        }
    }

    private fun inferPreferredSummaryLanguage(
        userMessage: ChatMessage,
        history: List<ChatMessage>
    ): SummaryLanguage {
        val combinedText = buildString {
            append(userMessage.content.orEmpty())
            history.asReversed()
                .asSequence()
                .filter { it.role == "user" }
                .take(4)
                .forEach {
                    append('\n')
                    append(it.content.orEmpty())
                }
        }
        return if (combinedText.any(::isChineseCharacter)) {
            SummaryLanguage.CHINESE
        } else {
            SummaryLanguage.ENGLISH
        }
    }

    private fun isChineseCharacter(char: Char): Boolean {
        return Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN
    }

    private data class CompletedToolRun(
        val toolName: String,
        val arguments: String,
        val result: String
    )

    private enum class SummaryLanguage {
        CHINESE,
        ENGLISH
    }
}
