package com.murong.agent.core.tool

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.provider.BuiltinLocalProvider
import com.murong.agent.core.provider.ChatImageAttachment
import com.murong.agent.core.provider.ChatMessage
import com.murong.agent.core.provider.ChatRequest
import com.murong.agent.core.provider.ProviderRegistry
import com.murong.agent.core.provider.ToolCall
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal data class PhoneAgentConnection(
    val providerId: String,
    val baseUrl: String?,
    val apiKey: String,
    val model: String,
    val reasoningEffort: String? = null
)

internal data class PhoneAgentModelResponse(
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList()
) {
    fun protocolText(): String = buildString {
        content?.trim()?.takeIf { it.isNotBlank() }?.let { append(it) }
        toolCalls.forEach { call ->
            if (isNotEmpty()) append('\n')
            append(call.function.name)
            append('(')
            append(call.function.arguments)
            append(')')
        }
    }.trim()
}

internal fun interface PhoneAgentModelClient {
    suspend fun complete(
        messages: List<ChatMessage>,
        connection: PhoneAgentConnection
    ): PhoneAgentModelResponse
}

/**
 * Historical class name retained for source compatibility. The implementation
 * now dispatches through the selected ModelProvider rather than assuming an
 * OpenAI-compatible endpoint.
 */
internal class OpenAICompatiblePhoneAgentClient : PhoneAgentModelClient {
    override suspend fun complete(
        messages: List<ChatMessage>,
        connection: PhoneAgentConnection
    ): PhoneAgentModelResponse {
        val provider = ProviderRegistry.getActiveProvider(connection.providerId)
        val response = provider.chat(
            request = ChatRequest(
                messages = messages,
                model = connection.model,
                temperature = 0.0,
                maxTokens = 3_000,
                stream = false,
                reasoningEffort = connection.reasoningEffort,
                tools = PhoneAgentProtocol.toolsJson
            ),
            apiKey = connection.apiKey,
            baseUrl = connection.baseUrl
        )
        if (response.content.isNullOrBlank() && response.toolCalls.isNullOrEmpty()) {
            error("当前模型没有返回手机动作")
        }
        return PhoneAgentModelResponse(
            content = response.content?.trim(),
            toolCalls = response.toolCalls.orEmpty()
        )
    }
}

internal class PhoneAgentController(
    private val modelClient: PhoneAgentModelClient = OpenAICompatiblePhoneAgentClient()
) {
    suspend fun run(
        request: PhoneAgentTaskRequest,
        config: ProviderConfig,
        device: PhoneAgentDevice,
        onProgress: (String) -> Unit = {},
    ): PhoneAgentRunResult {
        val modelConfig = config.getPhoneAgentResolvedConfig()
        validate(request, modelConfig)?.let { failure ->
            onProgress("任务无法开始：${failure.message}")
            return failure
        }
        if (modelConfig.usesCodexChatGptBackend()) {
            return PhoneAgentRunResult(
                success = true,
                status = "delegated",
                message = "当前使用 ChatGPT/Codex 账号登录模型：请由当前对话继续逐步调用 gui 的 observe、tap、input、swipe、launch 等动作完成同一任务；不要再次调用 run_task。这样无需第二个登录入口。",
                stepsExecuted = 0
            )
        }
        val connection = PhoneAgentConnection(
            providerId = modelConfig.activeProviderId,
            baseUrl = modelConfig.getActiveBaseUrl(),
            apiKey = modelConfig.getActiveApiKey().trim(),
            model = modelConfig.getActiveModel().trim(),
            reasoningEffort = modelConfig.getActiveReasoningEffort()
        )
        val maxSteps = (request.maxSteps ?: modelConfig.phoneAgentMaxSteps).coerceIn(1, 100)
        val history = mutableListOf(
            ChatMessage(role = "system", content = PhoneAgentPrompts.systemPrompt()),
            ChatMessage(role = "user", content = PhoneAgentPrompts.taskPrompt(request))
        )
        val trace = mutableListOf<PhoneAgentStepRecord>()
        val notes = mutableListOf<String>()
        var lastApplication: String? = null
        var previousSignature: String? = null
        var repeatedSignatureCount = 0
        var consecutiveWaits = 0
        var consecutiveInvalidResponses = 0
        var totalActionFailures = 0

        for (step in 1..maxSteps) {
            onProgress("第 $step/$maxSteps 步：正在读取当前屏幕")
            val screen = try {
                device.observe()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return failed(
                    message = "无法观察手机界面：${safeError(error, connection.apiKey)}",
                    steps = step - 1,
                    application = lastApplication,
                    trace = trace
                )
            }
            lastApplication = screen.application ?: lastApplication
            val observationMessage = ChatMessage(
                role = "user",
                content = buildString {
                    append("第 $step 步。当前应用：")
                    append(PhoneAgentApps.labelForPackage(screen.application) ?: "未知")
                    append("（")
                    append(screen.application ?: "unknown")
                    append("）。请检查最新截图并只返回一个动作。")
                    if (notes.isNotEmpty()) {
                        append("\n已记录事实：")
                        append(notes.takeLast(8).joinToString("；"))
                    }
                },
                images = listOf(
                    ChatImageAttachment(
                        mimeType = screen.screenshot.mimeType,
                        base64Data = screen.screenshot.base64Data,
                        fileName = "phone-agent-step-$step.png",
                        width = screen.screenshot.width,
                        height = screen.screenshot.height,
                        sizeBytes = (screen.screenshot.base64Data.length * 3L) / 4L
                    )
                )
            )
            val modelResponse = try {
                onProgress("第 $step/$maxSteps 步：正在识别界面并规划下一步")
                modelClient.complete(trimHistory(history) + observationMessage, connection)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return failed(
                    message = "当前已配置模型调用失败：${safeError(error, connection.apiKey)}",
                    steps = step - 1,
                    application = lastApplication,
                    trace = trace
                )
            }
            val responseText = modelResponse.protocolText()
            history += observationMessage.copy(images = emptyList())
            history += ChatMessage(
                role = "assistant",
                content = responseText.take(MAX_HISTORY_MESSAGE_CHARS)
            )

            when (val decision = PhoneAgentProtocol.parse(modelResponse)) {
                is PhoneAgentDecision.Finish -> {
                    onProgress("任务已完成：${decision.message.take(160)}")
                    val parsed = FoodDeliveryComparisonParser.parse(decision.message)
                    val comparison = parsed?.copy(notes = (parsed.notes + notes).distinct())
                    trace += PhoneAgentStepRecord(
                        step = step,
                        application = screen.application,
                        action = "finish",
                        success = true,
                        detail = decision.message.take(MAX_TRACE_DETAIL_CHARS)
                    )
                    return PhoneAgentRunResult(
                        success = true,
                        status = "completed",
                        message = decision.message,
                        stepsExecuted = step,
                        currentApplication = screen.application,
                        trace = trace,
                        foodDeliveryComparison = comparison
                    )
                }

                is PhoneAgentDecision.Invalid -> {
                    onProgress("第 $step/$maxSteps 步：模型动作格式无效，正在纠正")
                    consecutiveInvalidResponses++
                    trace += PhoneAgentStepRecord(
                        step = step,
                        application = screen.application,
                        action = "invalid_response",
                        success = false,
                        detail = decision.reason
                    )
                    if (consecutiveInvalidResponses >= MAX_INVALID_RESPONSES) {
                        return failed(
                            message = "Phone Agent 连续返回无效动作，已停止：${decision.reason}",
                            steps = step,
                            application = screen.application,
                            trace = trace
                        )
                    }
                    history += ChatMessage(
                        role = "user",
                        content = "协议错误：${decision.reason}。请基于同一界面调用 phone_action/phone_finish，或返回一个合法 JSON、do(...)、finish(...)。"
                    )
                }

                is PhoneAgentDecision.Execute -> {
                    consecutiveInvalidResponses = 0
                    val command = decision.command
                    onProgress(
                        "第 $step/$maxSteps 步：${describeProgressAction(command)}",
                    )
                    val takeoverReason = PhoneAgentSafetyPolicy.takeoverReason(
                        command = command,
                        rawResponse = responseText,
                        safeMode = modelConfig.phoneAgentSafeMode
                    )
                    if (takeoverReason != null) {
                        trace += PhoneAgentStepRecord(
                            step = step,
                            application = screen.application,
                            action = command.action,
                            success = true,
                            detail = "人工接管：$takeoverReason"
                        )
                        return PhoneAgentRunResult(
                            success = true,
                            status = "takeover",
                            message = takeoverReason,
                            stepsExecuted = step,
                            requiresUserAction = true,
                            currentApplication = screen.application,
                            trace = trace
                        )
                    }

                    if (command.action.equals("Note", ignoreCase = true)) {
                        val note = command.message ?: command.text ?: "模型记录了当前界面"
                        notes += note.take(MAX_NOTE_CHARS)
                        trace += PhoneAgentStepRecord(
                            step = step,
                            application = screen.application,
                            action = "Note",
                            success = true,
                            detail = note.take(MAX_TRACE_DETAIL_CHARS)
                        )
                        history += ChatMessage(role = "user", content = "事实已记录。继续检查下一个平台或步骤。")
                        continue
                    }

                    if (!PhoneAgentSafetyPolicy.isSupported(command.action)) {
                        totalActionFailures++
                        trace += PhoneAgentStepRecord(
                            step = step,
                            application = screen.application,
                            action = command.action,
                            success = false,
                            detail = "不支持的动作"
                        )
                        history += ChatMessage(
                            role = "user",
                            content = "动作 ${command.action} 不受支持，请改用协议列出的基础动作。"
                        )
                        if (totalActionFailures >= MAX_ACTION_FAILURES) {
                            return failed(
                                message = "模型多次请求不受支持的动作，已停止",
                                steps = step,
                                application = screen.application,
                                trace = trace
                            )
                        }
                        continue
                    }

                    consecutiveWaits = if (command.action.equals("Wait", ignoreCase = true)) {
                        consecutiveWaits + 1
                    } else {
                        0
                    }
                    if (consecutiveWaits > MAX_CONSECUTIVE_WAITS) {
                        return failed(
                            message = "界面连续等待仍无进展，已停止以避免空转",
                            steps = step,
                            application = screen.application,
                            trace = trace
                        )
                    }

                    val signature = screenshotHash(screen.screenshot) + ":" + command.fingerprint()
                    repeatedSignatureCount = if (signature == previousSignature) {
                        repeatedSignatureCount + 1
                    } else {
                        0
                    }
                    previousSignature = signature
                    if (repeatedSignatureCount >= MAX_REPEATED_SIGNATURES) {
                        return failed(
                            message = "检测到同一界面反复执行同一动作，已停止并等待人工处理",
                            steps = step,
                            application = screen.application,
                            trace = trace
                        )
                    }
                    if (repeatedSignatureCount == 1) {
                        history += ChatMessage(
                            role = "user",
                            content = "恢复提示：界面没有变化且动作重复。下一步必须改用 Back、滑动、其他坐标或重新 Launch。"
                        )
                        trace += PhoneAgentStepRecord(
                            step = step,
                            application = screen.application,
                            action = command.action,
                            success = false,
                            detail = "已拦截重复动作并要求模型换策略"
                        )
                        continue
                    }

                    val actionResult = try {
                        device.execute(command, screen)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        PhoneAgentDeviceResult(
                            success = false,
                            detail = safeError(error, connection.apiKey)
                        )
                    }
                    trace += PhoneAgentStepRecord(
                        step = step,
                        application = screen.application,
                        action = command.action,
                        success = actionResult.success,
                        detail = actionResult.detail?.take(MAX_TRACE_DETAIL_CHARS)
                    )
                    if (actionResult.success) {
                        onProgress(
                            "第 $step/$maxSteps 步：动作已提交，正在检查界面是否变化",
                        )
                        history += ChatMessage(
                            role = "user",
                            content = "动作已提交。下一步必须检查新截图验证结果。"
                        )
                    } else {
                        totalActionFailures++
                        history += ChatMessage(
                            role = "user",
                            content = "动作执行失败：${actionResult.detail.orEmpty().take(300)}。请观察后换一种策略。"
                        )
                        if (totalActionFailures >= MAX_ACTION_FAILURES) {
                            return failed(
                                message = "GUI 动作连续失败，已停止：${actionResult.detail.orEmpty()}",
                                steps = step,
                                application = screen.application,
                                trace = trace
                            )
                        }
                    }
                }
            }
        }
        return failed(
            message = "已达到最大步骤数 $maxSteps，任务尚未可靠完成",
            steps = maxSteps,
            application = lastApplication,
            trace = trace
        )
    }

    private fun validate(
        request: PhoneAgentTaskRequest,
        config: ProviderConfig
    ): PhoneAgentRunResult? {
        val error = when {
            !config.phoneAgentEnabled -> "手机操作 Agent 未启用"
            request.task.isBlank() -> "Phone Agent 任务不能为空"
            config.usesCodexChatGptBackend() -> null
            config.activeProviderId == BuiltinLocalProvider.ID &&
                BuiltinVisionRuntime.model(config.getActiveModel())?.supportsVision != true ->
                "当前手机操作模型“${config.getActiveModel()}”是纯文本模型，不能读取截图；" +
                    "请在语音助手设置中选择已安装的视觉模型"
            !config.hasUsableActiveProviderCredentials() ->
                "Phone Agent 选中的模型尚未配置：请只在统一设置中填写 API，或选择已安装的本地模型"
            config.getActiveModel().isBlank() -> "Phone Agent 选中的模型名未配置"
            !config.isActiveProviderLocal() &&
                !config.phoneAgentAllowRemoteScreenshots ->
                "Phone Agent 选中的模型位于远程服务；请先明确允许向它发送截图"
            else -> null
        } ?: return null
        return failed(error, 0, null, emptyList())
    }

    private fun trimHistory(history: List<ChatMessage>): List<ChatMessage> {
        if (history.size <= MAX_HISTORY_MESSAGES) return history
        return history.take(2) + history.takeLast(MAX_HISTORY_MESSAGES - 2)
    }

    private fun failed(
        message: String,
        steps: Int,
        application: String?,
        trace: List<PhoneAgentStepRecord>
    ) = PhoneAgentRunResult(
        success = false,
        status = "failed",
        message = message,
        stepsExecuted = steps,
        currentApplication = application,
        trace = trace
    )

    private fun screenshotHash(screenshot: GuiScreenshot): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(screenshot.base64Data.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    private fun safeError(error: Throwable, apiKey: String): String {
        val raw = error.message ?: error.javaClass.simpleName
        val sanitized = if (apiKey.isNotBlank()) raw.replace(apiKey, "[REDACTED]") else raw
        return sanitized.take(MAX_ERROR_CHARS)
    }

    private fun describeProgressAction(command: PhoneAgentCommand): String = when (
        command.action.lowercase()
    ) {
        "launch" -> "正在打开${command.app?.take(40) ?: "应用"}"
        "tap" -> "正在点击屏幕"
        "long press" -> "正在长按屏幕"
        "double tap" -> "正在双击屏幕"
        "type", "type_name" -> "正在输入文字"
        "swipe" -> "正在滑动屏幕"
        "back" -> "正在返回上一页"
        "home" -> "正在返回桌面"
        "wait" -> "正在等待页面加载"
        "note" -> "正在记录当前结果"
        else -> "正在执行 ${command.action.take(40)}"
    }

    private companion object {
        const val MAX_INVALID_RESPONSES = 3
        const val MAX_ACTION_FAILURES = 5
        const val MAX_CONSECUTIVE_WAITS = 3
        const val MAX_REPEATED_SIGNATURES = 2
        const val MAX_HISTORY_MESSAGES = 20
        const val MAX_HISTORY_MESSAGE_CHARS = 8_000
        const val MAX_TRACE_DETAIL_CHARS = 600
        const val MAX_NOTE_CHARS = 1_000
        const val MAX_ERROR_CHARS = 800
    }
}

internal object PhoneAgentSafetyPolicy {
    fun isSupported(action: String): Boolean = action.lowercase() in setOf(
        "launch",
        "tap",
        "type",
        "type_name",
        "swipe",
        "back",
        "home",
        "long press",
        "double tap",
        "wait"
    )

    fun takeoverReason(
        command: PhoneAgentCommand,
        rawResponse: String,
        safeMode: Boolean
    ): String? {
        val action = command.action.lowercase()
        if (action in setOf("take_over", "interact", "call_api")) {
            return command.message ?: "模型请求人工接管当前界面"
        }
        if (safeMode && action == "tap" && !command.message.isNullOrBlank()) {
            return command.message
        }
        if (action !in setOf("tap", "type", "type_name", "long press", "double tap")) {
            return null
        }
        val explicitContext = listOfNotNull(command.message, command.text)
            .joinToString(" ")
            .lowercase()
        val explicitKeyword = SENSITIVE_KEYWORDS.firstOrNull { it in explicitContext }
        val responseContext = rawResponse.lowercase()
        val intentPattern = SENSITIVE_INTENT_PATTERNS.firstOrNull { it in responseContext }
        val reason = explicitKeyword ?: intentPattern ?: return null
        return "检测到敏感步骤“$reason”，已暂停自动操作，请你手动完成后再继续"
    }

    private val SENSITIVE_KEYWORDS = listOf(
        "支付",
        "付款",
        "提交订单",
        "确认下单",
        "立即下单",
        "免密",
        "密码",
        "验证码",
        "短信验证",
        "滑块",
        "人脸",
        "指纹",
        "授权确认",
        "login password",
        "verification code",
        "captcha",
        "place order",
        "pay now"
    )

    private val SENSITIVE_INTENT_PATTERNS = listOf(
        "点击支付",
        "点击立即支付",
        "点击付款",
        "点击提交订单",
        "点击确认下单",
        "点击立即下单",
        "输入密码",
        "填写密码",
        "输入验证码",
        "填写验证码",
        "完成滑块",
        "进行人脸",
        "进行指纹",
        "confirm payment",
        "tap pay",
        "enter password",
        "enter verification code",
        "solve captcha",
        "place the order"
    )
}
