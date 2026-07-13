package com.murong.agent.core.loop

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.provider.*
import com.murong.agent.core.tool.ToolFileChange
import com.murong.agent.core.tool.ToolApprovalRequest
import com.murong.agent.core.tool.ToolExecutionReceipt
import com.murong.agent.core.tool.ToolRegistry
import com.murong.agent.core.tool.ToolRuntimeContext
import com.murong.agent.core.tool.ToolStructuredPayload
import com.murong.agent.core.tool.StepSignOffReceipt
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
        val modelContextResult: String = result,
        val fileChanges: List<ToolFileChange> = emptyList(),
        val stepSignOffReceipt: StepSignOffReceipt? = null,
        val structuredPayload: ToolStructuredPayload? = null
    ) : AgentEvent()
    data class UsageUpdate(val usage: Usage) : AgentEvent()
    data class ReadinessAudit(val audit: FinalReadinessAuditRecord) : AgentEvent()
    data class Error(
        val message: String,
        val finalReadinessReceipt: FinalReadinessReceipt? = null,
        val userVisibleMessage: String? = message
    ) : AgentEvent()
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
    private val config: ProviderConfig,
    private val hookBus: HookBusRunner = HookBusRunner()
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
        requestApproval: suspend (ToolApprovalRequest) -> Boolean = { true },
        toolExecutionGuard: (suspend (toolName: String, args: String) -> String?)? = null,
        finalReadinessGuard: (() -> FinalReadinessReceipt?)? = null,
        enforceFinalReadinessWithoutCurrentToolRuns: Boolean = false
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
        var hasRetriedForFinalReadiness = false
        var hasRetriedAfterHighRiskRemoteWriteFailure = false
        var blockHighRiskRemoteWritesForCurrentTurn = false
        var lastAuditedFinalReadinessReceipt: FinalReadinessReceipt? = null
        val completedToolRuns = mutableListOf<CompletedToolRun>()
        val preferredSummaryLanguage = inferPreferredSummaryLanguage(
            userMessage = userMessage,
            history = history
        )

        toolLoop@ while (toolIteration < maxToolIterations) {
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
                val localFinalReadinessReceipt = buildFinalReadinessReceipt(
                    completedToolRuns = completedToolRuns,
                    language = preferredSummaryLanguage
                )
                val finalReadinessReceipt = localFinalReadinessReceipt
                    ?: finalReadinessGuard?.invoke()
                if (finalReadinessReceipt != null &&
                    (hasExecutedTools || enforceFinalReadinessWithoutCurrentToolRuns)
                ) {
                    if (lastAuditedFinalReadinessReceipt != finalReadinessReceipt) {
                        onEvent(
                            AgentEvent.ReadinessAudit(
                                buildFinalReadinessAuditRecord(
                                    receipt = finalReadinessReceipt,
                                    result = FinalReadinessAuditResult.BLOCKED,
                                    recovered = false
                                )
                            )
                        )
                        lastAuditedFinalReadinessReceipt = finalReadinessReceipt
                    }
                    if (!hasRetriedForFinalReadiness) {
                        hasRetriedForFinalReadiness = true
                        currentMessages.add(
                            ChatMessage(
                                role = "system",
                                content = buildFinalReadinessRetryReminder(
                                    receipt = finalReadinessReceipt,
                                    language = preferredSummaryLanguage
                                )
                            )
                        )
                        state = AgentState.THINKING
                        continue
                    }

                    onEvent(
                        AgentEvent.Error(
                            buildFinalReadinessBlockedMessage(
                                receipt = finalReadinessReceipt,
                                language = preferredSummaryLanguage
                            ),
                            finalReadinessReceipt = finalReadinessReceipt,
                            userVisibleMessage = buildFinalReadinessBlockedUserVisibleMessage(
                                preferredSummaryLanguage.toFinalReadinessLanguage()
                            )
                        )
                    )
                    onEvent(AgentEvent.Done)
                    state = AgentState.ERROR
                    return
                }
                if (hasRetriedForFinalReadiness && lastAuditedFinalReadinessReceipt != null) {
                    onEvent(
                        AgentEvent.ReadinessAudit(
                            buildFinalReadinessAuditRecord(
                                receipt = lastAuditedFinalReadinessReceipt,
                                result = FinalReadinessAuditResult.ALLOWED,
                                recovered = true
                            )
                        )
                    )
                    lastAuditedFinalReadinessReceipt = null
                }

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
                val preToolContext = hookBus.dispatchPreToolUse(
                    PreToolUseHookContext(
                        toolName = tc.function.name,
                        args = tc.function.arguments,
                        callId = tc.id
                    )
                )
                val toolName = preToolContext.toolName
                val toolArgs = preToolContext.args
                onEvent(
                    AgentEvent.ToolExecution(
                        toolName = toolName,
                        args = toolArgs,
                        callId = tc.id,
                        isPartial = false
                    )
                )

                val tool = toolRegistry.getTool(toolName)
                val result = if (tool != null) {
                    try {
                        val guardMessage = toolExecutionGuard?.invoke(toolName, toolArgs)
                            ?: if (blockHighRiskRemoteWritesForCurrentTurn &&
                                isHighRiskRemoteWriteToolName(toolName)
                            ) {
                                buildHighRiskRemoteWriteRetryBlockMessage(preferredSummaryLanguage)
                            } else {
                                null
                            }
                        if (guardMessage != null) {
                            com.murong.agent.core.tool.ToolExecutionResult(
                                output = guardMessage
                            )
                        } else {
                            val repeatGuardMessage = buildRepeatWriteToolGuardMessage(
                                toolName = toolName,
                                args = toolArgs,
                                completedToolRuns = completedToolRuns,
                                language = preferredSummaryLanguage
                            )
                            if (repeatGuardMessage != null) {
                                com.murong.agent.core.tool.ToolExecutionResult(
                                    output = repeatGuardMessage
                                )
                            } else {
                                val approvalRequest = tool.buildApprovalRequest(toolArgs)
                                if (approvalRequest != null && !requestApproval(approvalRequest)) {
                                    com.murong.agent.core.tool.ToolExecutionResult(
                                        output = "Rejected by user: ${approvalRequest.summary}"
                                    )
                                } else {
                                    tool.executeWithContext(
                                        toolArgs,
                                        ToolRuntimeContext(
                                            currentTurnToolReceipts = completedToolRuns.map { toolRun ->
                                                ToolExecutionReceipt(
                                                    toolName = toolRun.toolName,
                                                    args = toolRun.arguments,
                                                    result = toolRun.result,
                                                    isSuccess = toolRun.isSuccess,
                                                    structuredPayload = toolRun.structuredPayload
                                                )
                                            }
                                    )
                                        )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        com.murong.agent.core.tool.ToolExecutionResult(
                            output = "Error executing `$toolName`: ${e.message}"
                        )
                    }
                } else if (toolRegistry.isPromptExposed(toolName)) {
                    val guardMessage = toolExecutionGuard?.invoke(toolName, toolArgs)
                    com.murong.agent.core.tool.ToolExecutionResult(
                        output = guardMessage ?: "Error: Tool `$toolName` is currently unavailable in this mode."
                    )
                } else {
                    com.murong.agent.core.tool.ToolExecutionResult(
                        output = "Error: Unknown tool `$toolName`. " +
                            "Available tools: ${toolRegistry.getPromptVisibleTools().joinToString(", ") { it.name }}"
                    )
                }
                val modelContextResult = buildToolModelContextResult(
                    toolName = toolName,
                    output = result.output
                )
                val executionSucceeded = isSuccessfulToolResult(result.output)
                val postToolContext = hookBus.dispatchPostToolUse(
                    PostToolUseHookContext(
                        toolName = toolName,
                        args = toolArgs,
                        result = result.output,
                        modelContextResult = modelContextResult,
                        isSuccess = executionSucceeded,
                        fileChanges = result.fileChanges,
                        stepSignOffReceipt = result.stepSignOffReceipt,
                        structuredPayload = result.structuredPayload
                    )
                )

                onEvent(
                    AgentEvent.ToolResult(
                        toolName = postToolContext.toolName,
                        args = postToolContext.args,
                        result = postToolContext.result,
                        modelContextResult = postToolContext.modelContextResult,
                        fileChanges = postToolContext.fileChanges,
                        stepSignOffReceipt = postToolContext.stepSignOffReceipt,
                        structuredPayload = postToolContext.structuredPayload
                    )
                )
                completedToolRuns += CompletedToolRun(
                    toolName = postToolContext.toolName,
                    arguments = postToolContext.args,
                    result = postToolContext.result,
                    isSuccess = postToolContext.isSuccess,
                    stepSignOffReceipt = postToolContext.stepSignOffReceipt,
                    structuredPayload = postToolContext.structuredPayload
                )

                currentMessages.add(
                    ChatMessage(
                        role = "tool",
                        toolCallId = tc.id,
                        content = postToolContext.modelContextResult,
                        name = postToolContext.toolName
                    )
                )

                if (shouldStopAfterHighRiskRemoteWriteFailure(postToolContext.toolName, postToolContext.result)) {
                    if (!hasRetriedAfterHighRiskRemoteWriteFailure) {
                        hasRetriedAfterHighRiskRemoteWriteFailure = true
                        blockHighRiskRemoteWritesForCurrentTurn = true
                        currentMessages.add(
                            ChatMessage(
                                role = "system",
                                content = buildHighRiskRemoteWriteRetryReminder(
                                    toolName = postToolContext.toolName,
                                    language = preferredSummaryLanguage
                                )
                            )
                        )
                        state = AgentState.THINKING
                        continue@toolLoop
                    }
                    onEvent(
                        AgentEvent.Error(
                            buildHighRiskRemoteWriteStopMessage(postToolContext.toolName, preferredSummaryLanguage),
                            userVisibleMessage = buildHighRiskRemoteWriteUserVisibleMessage(
                                preferredSummaryLanguage
                            )
                        )
                    )
                    onEvent(AgentEvent.Done)
                    state = AgentState.ERROR
                    return
                }
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
        var retryAfterMillis: Long? = null

        for (attempt in 1..maxRetries) {
            retryAfterMillis = null
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
            } catch (e: java.io.IOException) {
                val msg = e.message ?: "未知网络错误"
                if (e is ProviderHttpException && e.statusCode in setOf(401, 403)) {
                    onEvent(AgentEvent.Error("🔑 API Key 无效或被拒绝（$msg）。请在设置中检查 API Key。"))
                    return null
                }
                if (!isRetryableProviderFailure(e)) {
                    onEvent(AgentEvent.Error("⚠️ 请求失败：$msg"))
                    return null
                }
                retryAfterMillis = (e as? ProviderHttpException)?.retryAfterMillis
                lastError = when ((e as? ProviderHttpException)?.statusCode) {
                    429 -> "⏳ 请求过于频繁（429），等待后重试…"
                    in 500..599 -> "🔧 服务端错误（$msg）"
                    else -> "⚠️ 网络或流式连接错误：$msg"
                }
                onEvent(AgentEvent.Error("$lastError (重试 $attempt/$maxRetries)"))
            } catch (e: Exception) {
                // 兜底：捕获所有其他异常（JSON 解析错误、空指针等）
                val msg = e.message ?: e.javaClass.simpleName
                lastError = "⚠️ 请求异常：$msg"
                onEvent(AgentEvent.Error("⚠️ $lastError (重试 $attempt/$maxRetries)"))
            }

            if (attempt < maxRetries) {
                delay(providerRetryDelayMillis(attempt, retryAfterMillis))
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

    private fun buildFinalReadinessReceipt(
        completedToolRuns: List<CompletedToolRun>,
        language: SummaryLanguage
    ): FinalReadinessReceipt? {
        return buildWriteSignOffReadinessReceipt(
            completedToolRuns = completedToolRuns.map { toolRun ->
                FinalReadinessToolRunSnapshot(
                    toolName = toolRun.toolName,
                    result = toolRun.result,
                    isSuccess = toolRun.isSuccess,
                    stepSignOffReceipt = toolRun.stepSignOffReceipt
                )
            },
            language = language.toFinalReadinessLanguage(),
            isWriteTool = ::isFinalReadinessWriteTool
        )
    }

    private fun buildFinalReadinessRetryReminder(
        receipt: FinalReadinessReceipt,
        language: SummaryLanguage
    ): String {
        return com.murong.agent.core.loop.buildFinalReadinessRetryReminder(
            receipt = receipt,
            language = language.toFinalReadinessLanguage()
        )
    }

    private fun buildFinalReadinessBlockedMessage(
        receipt: FinalReadinessReceipt,
        language: SummaryLanguage
    ): String {
        return com.murong.agent.core.loop.buildFinalReadinessBlockedMessage(
            receipt = receipt,
            language = language.toFinalReadinessLanguage()
        )
    }

    private fun buildToolModelContextResult(
        toolName: String,
        output: String
    ): String {
        if (output.length <= MAX_TOOL_RESULT_CONTEXT_CHARS) {
            return output
        }
        val head = output.take(TOOL_RESULT_CONTEXT_HEAD_CHARS)
        val tail = output.takeLast(TOOL_RESULT_CONTEXT_TAIL_CHARS)
        return buildString {
            append("[tool output truncated for model context]\n")
            append("tool=")
            append(toolName)
            append(", original_chars=")
            append(output.length)
            append(", kept_head_chars=")
            append(head.length)
            append(", kept_tail_chars=")
            append(tail.length)
            append("\n\n[head]\n")
            append(head)
            append("\n\n[middle omitted]\n\n[tail]\n")
            append(tail)
            append("\n\nIf you need more details, call a narrower tool or re-read a smaller window instead of assuming the omitted middle content.")
        }
    }

    private fun isSuccessfulToolResult(output: String): Boolean {
        return !output.startsWith("Error:", ignoreCase = true) &&
            !output.startsWith("Blocked by", ignoreCase = true) &&
            !output.startsWith("Rejected by user:", ignoreCase = true)
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

    private fun shouldStopAfterHighRiskRemoteWriteFailure(
        toolName: String,
        output: String
    ): Boolean {
        if (toolName !in HIGH_RISK_REMOTE_WRITE_TOOL_NAMES) return false
        if (!output.contains("Error", ignoreCase = true) && !output.contains("失败")) return false
        return HIGH_RISK_REMOTE_WRITE_FAILURE_MARKERS.any { marker ->
            output.contains(marker, ignoreCase = true)
        }
    }

    private fun isHighRiskRemoteWriteToolName(toolName: String): Boolean {
        return toolName in HIGH_RISK_REMOTE_WRITE_TOOL_NAMES
    }

    private fun buildHighRiskRemoteWriteRetryReminder(
        toolName: String,
        language: SummaryLanguage
    ): String {
        return when (language) {
            SummaryLanguage.CHINESE -> buildString {
                appendLine("Remote Write Guard:")
                appendLine("高风险远端写入工具 `$toolName` 刚刚出现写后校验异常。")
                append("本轮不要继续调用高风险远端写入工具；先改用 task_repo_read_file、")
                append("task_repo_search_code、task_repo_list_dir 等只读工具检查远端最新状态，")
                append("再向用户汇报发现或询问下一步。")
            }
            SummaryLanguage.ENGLISH -> buildString {
                appendLine("Remote Write Guard:")
                appendLine("High-risk remote write tool `$toolName` just failed post-write verification.")
                append("Do not call more high-risk remote write tools in this turn. ")
                append("Inspect the latest remote state with read/search/list tools first, then report the findings or ask the user how to proceed.")
            }
        }
    }

    private fun buildHighRiskRemoteWriteRetryBlockMessage(language: SummaryLanguage): String {
        return when (language) {
            SummaryLanguage.CHINESE ->
                "Blocked by remote write guard: 上一个高风险远端写入已经出现写后校验异常。" +
                    "本轮剩余时间不要继续调用高风险远端写入工具；请先改用 task_repo_read_file / " +
                    "task_repo_search_code / task_repo_list_dir 检查现状。"
            SummaryLanguage.ENGLISH ->
                "Blocked by remote write guard: a previous high-risk remote write already failed post-write verification. " +
                    "Do not call more high-risk remote write tools in this turn; inspect the current state with read/search/list tools first."
        }
    }

    private fun buildHighRiskRemoteWriteStopMessage(
        toolName: String,
        language: SummaryLanguage
    ): String {
        return when (language) {
            SummaryLanguage.CHINESE ->
                "检测到高风险远端写入工具 `$toolName` 出现写后校验异常，已停止继续自动重试，避免把远端文件越写越坏。请先检查工具结果和目标文件状态，再决定是否继续。"
            SummaryLanguage.ENGLISH ->
                "A high-risk remote write tool `$toolName` failed post-write verification. Automatic retries have been stopped to avoid further corrupting the remote file. Please inspect the tool result and remote file state before continuing."
        }
    }

    private fun buildHighRiskRemoteWriteUserVisibleMessage(language: SummaryLanguage): String {
        return when (language) {
            SummaryLanguage.CHINESE ->
                "当前执行已暂停：检测到远端写入校验异常，已停止自动重试。请先确认目标文件状态后再继续。"
            SummaryLanguage.ENGLISH ->
                "Execution paused: a remote write verification error was detected and automatic retries were stopped. Confirm the target file state before continuing."
        }
    }

    private fun buildRepeatWriteToolGuardMessage(
        toolName: String,
        args: String,
        completedToolRuns: List<CompletedToolRun>,
        language: SummaryLanguage
    ): String? {
        if (!isRepeatGuardTargetTool(toolName)) return null
        val repeatedSuccessCount = completedToolRuns.count { completedRun ->
            completedRun.isSuccess &&
                completedRun.toolName == toolName &&
                completedRun.arguments == args
        }
        if (repeatedSuccessCount < MAX_REPEATED_SUCCESSFUL_WRITE_TOOL_CALLS) return null
        return when (language) {
            SummaryLanguage.CHINESE ->
                "Blocked by repeat guard: 检测到写工具 `$toolName` 已经用完全相同的参数成功执行 $repeatedSuccessCount 次。" +
                    " 为避免陷入重复修改循环，本次已拦截。请先改用读取/搜索类工具检查最新状态，或改成不同的精确修改参数后再继续。"
            SummaryLanguage.ENGLISH ->
                "Blocked by repeat guard: write tool `$toolName` has already succeeded $repeatedSuccessCount times " +
                    "with the exact same arguments. This call was blocked to avoid a repeated edit loop. " +
                    "Inspect the latest state with a read/search tool or change the edit arguments before continuing."
        }
    }

    private fun isRepeatGuardTargetTool(toolName: String): Boolean {
        if (toolName in REPEAT_GUARD_WRITE_TOOL_NAMES) return true
        val normalized = toolName.lowercase()
        return REPEAT_GUARD_WRITE_TOOL_KEYWORDS.any { keyword ->
            normalized.contains(keyword)
        }
    }

    private fun isFinalReadinessWriteTool(toolName: String): Boolean {
        return isFinalReadinessWriteToolName(toolName)
    }

    private data class CompletedToolRun(
        val toolName: String,
        val arguments: String,
        val result: String,
        val isSuccess: Boolean,
        val stepSignOffReceipt: StepSignOffReceipt? = null,
        val structuredPayload: ToolStructuredPayload? = null
    )

    private enum class SummaryLanguage {
        CHINESE,
        ENGLISH
    }

    private fun SummaryLanguage.toFinalReadinessLanguage(): FinalReadinessLanguage {
        return when (this) {
            SummaryLanguage.CHINESE -> FinalReadinessLanguage.CHINESE
            SummaryLanguage.ENGLISH -> FinalReadinessLanguage.ENGLISH
        }
    }

    private companion object {
        const val MAX_TOOL_RESULT_CONTEXT_CHARS = 32 * 1024
        const val TOOL_RESULT_CONTEXT_HEAD_CHARS = 12 * 1024
        const val TOOL_RESULT_CONTEXT_TAIL_CHARS = 12 * 1024

        val HIGH_RISK_REMOTE_WRITE_TOOL_NAMES = setOf(
            "task_repo_search_replace",
            "task_repo_apply_patch",
            "task_repo_update_file",
            "task_repo_commit_files"
        )

        val HIGH_RISK_REMOTE_WRITE_FAILURE_MARKERS = listOf(
            "写后校验失败",
            "未通过校验",
            "内容与预期不一致",
            "无法重新读取远端文件",
            "expectedLength=",
            "actualLength="
        )

        const val MAX_REPEATED_SUCCESSFUL_WRITE_TOOL_CALLS = 2

        val REPEAT_GUARD_WRITE_TOOL_NAMES = setOf(
            "task_repo_search_replace",
            "task_repo_apply_patch",
            "task_repo_update_file",
            "task_repo_commit_files",
            "task_repo_delete_file",
            "task_repo_create_branch",
            "task_repo_delete_branch",
            "task_repo_create_pr",
            "task_repo_close_pr",
            "code_edit"
        )

        val REPEAT_GUARD_WRITE_TOOL_KEYWORDS = listOf(
            "write",
            "edit",
            "update",
            "patch",
            "replace",
            "delete",
            "commit",
            "mutating"
        )

    }
}
