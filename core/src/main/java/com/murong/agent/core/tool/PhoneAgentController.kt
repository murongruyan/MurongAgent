package com.murong.agent.core.tool

import android.util.Log
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.provider.BuiltinLocalProvider
import com.murong.agent.core.provider.ChatImageAttachment
import com.murong.agent.core.provider.ChatMessage
import com.murong.agent.core.provider.ChatRequest
import com.murong.agent.core.provider.ProviderRegistry
import com.murong.agent.core.provider.StreamDelta
import com.murong.agent.core.provider.ToolCall
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// A single structured action is intentionally small. Keeping this bounded prevents a local model
// that ignores the protocol from spending minutes generating an essay before we can correct it.
private const val PHONE_ACTION_MAX_TOKENS = 160

private data class GuidedShareAction(
    val command: PhoneAgentCommand,
    val traceAction: String,
    val progress: String,
)

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
        connection: PhoneAgentConnection,
        onVisibleText: (String) -> Unit,
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
        connection: PhoneAgentConnection,
        onVisibleText: (String) -> Unit,
    ): PhoneAgentModelResponse {
        val provider = ProviderRegistry.getActiveProvider(connection.providerId)
        val response = provider.chatStream(
            request = ChatRequest(
                messages = messages,
                model = connection.model,
                temperature = 0.0,
                maxTokens = PHONE_ACTION_MAX_TOKENS,
                stream = true,
                reasoningEffort = connection.reasoningEffort,
                tools = PhoneAgentProtocol.toolsJsonForModel(connection.model)
            ),
            apiKey = connection.apiKey,
            baseUrl = connection.baseUrl,
            onDelta = { delta ->
                if (delta is StreamDelta.Content && delta.text.isNotEmpty()) {
                    onVisibleText(delta.text)
                }
            },
        )
        return PhoneAgentModelResponse(
            content = response.content?.trim(),
            toolCalls = response.toolCalls.orEmpty()
        )
    }
}

internal class PhoneAgentController(
    private val modelClient: PhoneAgentModelClient = OpenAICompatiblePhoneAgentClient(),
    private val modelStepTimeoutMillis: Long = MODEL_STEP_TIMEOUT_MILLIS,
) {
    suspend fun run(
        request: PhoneAgentTaskRequest,
        config: ProviderConfig,
        device: PhoneAgentDevice,
        onProgress: (String) -> Unit = {},
        messagePreparedByShare: Boolean = false,
        searchContextVerified: Boolean = true,
    ): PhoneAgentRunResult {
        val modelConfig = config.getPhoneAgentResolvedConfig()
        val usesLocalModel = modelConfig.isActiveProviderLocal()
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
        var connection = PhoneAgentConnection(
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
        var noProgressEvents = 0
        var previousObservedScreenSignature: String? = null
        var awaitingChangeAfterAction: PhoneAgentCommand? = null
        var lastSubmittedActionChangedScreen = false
        var strongerLocalModelActivated = false
        val messageIntent = PhoneAgentMessageIntent.parse(request.task)
        var typedMessageAtStep: Int? = if (messageIntent != null && messagePreparedByShare) 0 else null
        var sendTapAtStep: Int? = null
        var shareRecipientSelected = false
        var shareSendTapped = false
        val guidedTask = PhoneAgentGuidedTask.create(
            request.task,
            searchContextVerified = searchContextVerified,
        )
        if (typedMessageAtStep != null) {
            history += ChatMessage(
                role = "user",
                content = "Android 已通过目标应用的标准分享页预填消息正文。不要再次 Type；只需准确选择收件人“${messageIntent?.recipient}”，检查分享预览，然后点击发送。",
            )
        }

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
            val currentScreenSignature = screenSignature(screen)
            var forcedModelRecoveryReason: String? = null
            awaitingChangeAfterAction?.let { previousAction ->
                val actionMayLegitimatelyKeepScreen = previousAction.action.lowercase() in
                    setOf("wait", "note")
                lastSubmittedActionChangedScreen = actionMayLegitimatelyKeepScreen ||
                    currentScreenSignature != previousObservedScreenSignature
                if (lastSubmittedActionChangedScreen) {
                    noProgressEvents = 0
                    if (!actionMayLegitimatelyKeepScreen) {
                        onProgress(
                            "验证结果：${describeProgressAction(previousAction)}后界面已变化",
                        )
                    }
                } else {
                    noProgressEvents++
                    forcedModelRecoveryReason =
                        "动作 ${previousAction.action} 提交后界面没有变化；不要重复原坐标，" +
                            "请根据当前截图重新判断可操作目标"
                    onProgress("第 $step/$maxSteps 步：快路径动作未生效，正在交给模型重新判断")
                    history += ChatMessage(
                        role = "user",
                        content = "验证失败：动作 ${previousAction.action} 提交后界面没有变化。" +
                            "不要重复等待或点击原坐标，必须利用语义控件中心坐标换一种操作。"
                    )
                }
                awaitingChangeAfterAction = null
            }
            if (noProgressEvents >= MAX_NO_PROGRESS_EVENTS) {
                return failed(
                    message = "连续多次没有产生可验证的界面进展，已停止以避免长时间空转",
                    steps = step - 1,
                    application = lastApplication,
                    trace = trace,
                )
            }
            if (messagePreparedByShare && shareSendTapped && lastSubmittedActionChangedScreen) {
                val message = "已通过目标应用的标准分享流程向${messageIntent?.recipient}发送${messageIntent?.body}"
                onProgress("任务已完成：$message")
                trace += PhoneAgentStepRecord(
                    step = step,
                    application = screen.application,
                    action = "verified_share_finish",
                    success = true,
                    detail = message,
                )
                return PhoneAgentRunResult(
                    success = true,
                    status = "completed",
                    message = message,
                    stepsExecuted = step,
                    currentApplication = screen.application,
                    trace = trace,
                )
            }

            val guidedShareAction = if (messagePreparedByShare && messageIntent != null) {
                preparedMessageShareAction(
                    screen = screen,
                    intent = messageIntent,
                    recipientSelected = shareRecipientSelected,
                    sendTapped = shareSendTapped,
                )
            } else {
                null
            }
            if (guidedShareAction != null) {
                val command = guidedShareAction.command
                onProgress("快路径判断 · 第 $step/$maxSteps 步：${guidedShareAction.progress}")
                val actionResult = try {
                    device.execute(command, screen)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    PhoneAgentDeviceResult(false, safeError(error, connection.apiKey))
                }
                trace += PhoneAgentStepRecord(
                    step = step,
                    application = screen.application,
                    action = guidedShareAction.traceAction,
                    success = actionResult.success,
                    detail = actionResult.detail?.take(MAX_TRACE_DETAIL_CHARS),
                )
                onProgress(
                    "执行动作：${describeProgressAction(command)} · " +
                        if (actionResult.success) "已提交" else "失败：${actionResult.detail.orEmpty().take(160)}",
                )
                if (actionResult.success) {
                    if (guidedShareAction.traceAction == "select_share_recipient") {
                        shareRecipientSelected = true
                    } else if (guidedShareAction.traceAction == "confirm_share_send") {
                        shareSendTapped = true
                        sendTapAtStep = step
                    }
                    previousObservedScreenSignature = currentScreenSignature
                    awaitingChangeAfterAction = command
                    onProgress("第 $step/$maxSteps 步：动作已提交，正在检查界面是否变化")
                } else {
                    totalActionFailures++
                    noProgressEvents++
                }
                continue
            }
            when (
                val guidedDecision = forcedModelRecoveryReason?.let {
                    PhoneAgentGuidedDecision.RecoverWithModel(it)
                } ?: guidedTask?.next(screen)
            ) {
                is PhoneAgentGuidedDecision.NeedsUserAction -> {
                    Log.i(GUIDED_LOG_TAG, "needs_user_action step=$step")
                    onProgress("需要你的选择：${guidedDecision.message}")
                    trace += PhoneAgentStepRecord(
                        step = step,
                        application = screen.application,
                        action = "guided_needs_user_action",
                        success = true,
                        detail = guidedDecision.message.take(MAX_TRACE_DETAIL_CHARS),
                    )
                    return PhoneAgentRunResult(
                        success = true,
                        status = "takeover",
                        message = guidedDecision.message,
                        stepsExecuted = step,
                        requiresUserAction = true,
                        currentApplication = screen.application,
                        trace = trace,
                    )
                }
                is PhoneAgentGuidedDecision.Fail -> {
                    Log.w(GUIDED_LOG_TAG, "fail step=$step message=${guidedDecision.message}")
                    onProgress("任务失败：${guidedDecision.message}")
                    trace += PhoneAgentStepRecord(
                        step = step,
                        application = screen.application,
                        action = "guided_fail",
                        success = false,
                        detail = guidedDecision.message.take(MAX_TRACE_DETAIL_CHARS),
                    )
                    return failed(
                        message = guidedDecision.message,
                        steps = step,
                        application = screen.application,
                        trace = trace,
                    )
                }
                is PhoneAgentGuidedDecision.RecoverWithModel -> {
                    runCatching {
                        Log.i(
                            GUIDED_LOG_TAG,
                            "recover_with_model step=$step reason=${guidedDecision.reason}",
                        )
                    }
                    onProgress(
                        "第 $step/$maxSteps 步：快路径需要恢复，模型正在结合当前截图重新规划",
                    )
                    notes += "快路径恢复原因：${guidedDecision.reason.take(500)}"
                    trace += PhoneAgentStepRecord(
                        step = step,
                        application = screen.application,
                        action = "guided_model_recovery",
                        success = true,
                        detail = guidedDecision.reason.take(MAX_TRACE_DETAIL_CHARS),
                    )
                }
                is PhoneAgentGuidedDecision.Finish -> {
                    Log.i(GUIDED_LOG_TAG, "finish step=$step")
                    onProgress("任务已完成：${guidedDecision.message}")
                    trace += PhoneAgentStepRecord(
                        step = step,
                        application = screen.application,
                        action = "guided_finish",
                        success = true,
                        detail = guidedDecision.message.take(MAX_TRACE_DETAIL_CHARS),
                    )
                    return PhoneAgentRunResult(
                        success = true,
                        status = "completed",
                        message = guidedDecision.message,
                        stepsExecuted = step,
                        currentApplication = screen.application,
                        trace = trace,
                    )
                }
                is PhoneAgentGuidedDecision.Execute -> {
                    Log.i(
                        GUIDED_LOG_TAG,
                        "action step=$step name=${guidedDecision.traceAction} " +
                            "application=${screen.application ?: "unknown"} " +
                            "point=${guidedDecision.command.x ?: "-"},${guidedDecision.command.y ?: "-"}",
                    )
                    onProgress("快路径判断 · 第 $step/$maxSteps 步：${guidedDecision.progress}")
                    val actionResult = try {
                        device.execute(guidedDecision.command, screen)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        PhoneAgentDeviceResult(false, safeError(error, connection.apiKey))
                    }
                    guidedTask?.onActionResult(guidedDecision, actionResult.success)
                    trace += PhoneAgentStepRecord(
                        step = step,
                        application = screen.application,
                        action = guidedDecision.traceAction,
                        success = actionResult.success,
                        detail = actionResult.detail?.take(MAX_TRACE_DETAIL_CHARS),
                    )
                    onProgress(
                        "执行动作：${describeProgressAction(guidedDecision.command)} · " +
                            if (actionResult.success) "已提交" else {
                                "失败：${actionResult.detail.orEmpty().take(160)}"
                            },
                    )
                    if (actionResult.success) {
                        previousObservedScreenSignature = currentScreenSignature
                        awaitingChangeAfterAction = guidedDecision.command
                        onProgress("第 $step/$maxSteps 步：动作已提交，正在检查界面是否变化")
                    } else {
                        totalActionFailures++
                        noProgressEvents++
                    }
                    continue
                }
                null -> Unit
            }
            val observationMessage = ChatMessage(
                role = "user",
                content = buildString {
                    append("第 $step 步。当前应用：")
                    append(PhoneAgentApps.labelForPackage(screen.application) ?: "未知")
                    append("（")
                    append(screen.application ?: "unknown")
                    append("）。请检查最新截图并只返回一个动作。")
                    if (screen.semanticSummary.isNotBlank()) {
                        append("\n无障碍语义控件（center 坐标同样为 0..1000，优先使用）：\n")
                        append(screen.semanticSummary)
                    }
                    screen.topLevelNavigationEvidence()?.let { evidence ->
                        append("\n结构化界面锚点（优先级高于仅凭商品图片的猜测）：\n")
                        append(evidence)
                    }
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
            val streamedNaturalLanguage = StringBuilder()
            val hasVisibleModelText = AtomicBoolean(false)
            var lastPublishedStreamLength = 0
            val inferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val modelDeferred = inferenceScope.async {
                modelClient.complete(
                    trimHistory(history) + observationMessage,
                    connection,
                ) { text ->
                    streamedNaturalLanguage.append(text)
                    val visible = visibleNaturalLanguageText(
                        streamedNaturalLanguage.toString(),
                    )
                    if (
                        visible.length - lastPublishedStreamLength >= MODEL_STREAM_UPDATE_CHARS ||
                        text.any { it in MODEL_STREAM_SENTENCE_ENDINGS }
                    ) {
                        lastPublishedStreamLength = visible.length
                        if (visible.isNotBlank()) {
                            hasVisibleModelText.set(true)
                            onProgress("模型正在说：${visible.take(MAX_VISIBLE_MODEL_REPLY_CHARS)}")
                        }
                    }
                }
            }
            val modelResponse = try {
                onProgress("第 $step/$maxSteps 步：正在识别界面并规划下一步")
                coroutineScope {
                    val heartbeat = launch {
                        var waitedSeconds = 0
                        while (isActive) {
                            delay(MODEL_PROGRESS_HEARTBEAT_MILLIS)
                            waitedSeconds += (MODEL_PROGRESS_HEARTBEAT_MILLIS / 1_000L).toInt()
                            // Once the model starts speaking, keep its latest real text on top of
                            // the chat card and floating window instead of covering it every ten
                            // seconds with another generated wait-status line.
                            if (!hasVisibleModelText.get()) {
                                onProgress(
                                    if (usesLocalModel) {
                                        "第 $step/$maxSteps 步：本地模型仍在分析当前截图，已等待 ${waitedSeconds} 秒；" +
                                            "本地推理不会按固定 120 秒中止，可随时点击停止"
                                    } else {
                                        "第 $step/$maxSteps 步：模型仍在分析当前截图，已等待 ${waitedSeconds} 秒"
                                    },
                                )
                            }
                        }
                    }
                    try {
                        if (usesLocalModel) {
                            modelDeferred.await()
                        } else withTimeout(modelStepTimeoutMillis) {
                            // The inference runs in an independent scope so this deadline can return
                            // even while a native prefill call is temporarily non-cooperative.
                            modelDeferred.await()
                        }
                    } finally {
                        heartbeat.cancelAndJoin()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                BuiltinVisionRuntime.cancelActiveGeneration()
                return failed(
                    message = "模型单步识别超过 ${(modelStepTimeoutMillis / 1_000L).coerceAtLeast(1L)} 秒，已停止本轮推理，避免一直卡在规划下一步",
                    steps = step - 1,
                    application = lastApplication,
                    trace = trace,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return failed(
                    message = "当前已配置模型调用失败：${safeError(error, connection.apiKey)}",
                    steps = step - 1,
                    application = lastApplication,
                    trace = trace
                )
            } finally {
                if (!modelDeferred.isCompleted) {
                    BuiltinVisionRuntime.cancelActiveGeneration()
                    modelDeferred.cancel()
                }
                modelDeferred.invokeOnCompletion { inferenceScope.cancel() }
            }
            val responseText = modelResponse.protocolText()
            val naturalLanguageReply = visibleNaturalLanguageReply(modelResponse)
            if (naturalLanguageReply.isNotBlank()) {
                onProgress("模型回复：$naturalLanguageReply")
            }
            history += observationMessage
            history += ChatMessage(
                role = "assistant",
                content = responseText.take(MAX_HISTORY_MESSAGE_CHARS)
            )

            when (val decision = PhoneAgentProtocol.parse(modelResponse)) {
                is PhoneAgentDecision.Finish -> {
                    val completionProblem = messageCompletionProblem(
                        intent = messageIntent,
                        screen = screen,
                        typedMessageAtStep = typedMessageAtStep,
                        sendTapAtStep = sendTapAtStep,
                        lastSubmittedActionChangedScreen = lastSubmittedActionChangedScreen,
                    )
                    if (completionProblem != null) {
                        noProgressEvents++
                        trace += PhoneAgentStepRecord(
                            step = step,
                            application = screen.application,
                            action = "unverified_finish",
                            success = false,
                            detail = completionProblem,
                        )
                        history += ChatMessage(
                            role = "user",
                            content = "不能结束：$completionProblem。继续操作并从新截图验证，不能只用自然语言声称完成。",
                        )
                        continue
                    }
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
                    noProgressEvents++
                    val visibleReply = visibleModelReply(modelResponse)
                    if (visibleReply.isNotBlank()) {
                        onProgress("模型回复：$visibleReply")
                    }
                    trace += PhoneAgentStepRecord(
                        step = step,
                        application = screen.application,
                        action = "invalid_response",
                        success = false,
                        detail = buildString {
                            append(decision.reason)
                            if (visibleReply.isNotBlank()) {
                                append("；模型可见回复：")
                                append(visibleReply)
                            }
                        }.take(MAX_TRACE_DETAIL_CHARS)
                    )
                    val strongerConnection = strongerBuiltinLocalConnection(connection)
                    if (!strongerLocalModelActivated && strongerConnection != null) {
                        strongerLocalModelActivated = true
                        connection = strongerConnection
                        consecutiveInvalidResponses = 0
                        onProgress("本地模型动作格式不稳定，正在自动切换已安装的 9B 视觉模型")
                        history += ChatMessage(
                            role = "user",
                            content = "已切换到更强的本地视觉模型。基于同一界面只返回一个 phone_action/phone_finish 调用或一个严格 JSON。",
                        )
                        continue
                    }
                    if (consecutiveInvalidResponses >= MAX_INVALID_RESPONSES ||
                        noProgressEvents >= MAX_NO_PROGRESS_EVENTS
                    ) {
                        return failed(
                            message = buildString {
                                append("Phone Agent 连续返回无效动作，已停止：")
                                append(decision.reason)
                                if (visibleReply.isNotBlank()) {
                                    append("；模型可见回复：")
                                    append(visibleReply)
                                }
                            },
                            steps = step,
                            application = screen.application,
                            trace = trace
                        )
                    }
                    history += ChatMessage(
                        role = "user",
                        content = "协议错误：${decision.reason}。基于同一界面立即重试，只调用一次 " +
                            "phone_action/phone_finish。若无函数调用，只返回一个 JSON，不得附带解释：" +
                            "tap={\"action\":\"tap\",\"message\":\"看到目标按钮，准备点击\",\"x\":500,\"y\":500}；" +
                            "type={\"action\":\"type\",\"message\":\"输入框已就绪，准备填写\",\"text\":\"文字\"}；" +
                            "finish={\"type\":\"finish\",\"message\":\"结果\"}。"
                    )
                }

                is PhoneAgentDecision.Execute -> {
                    consecutiveInvalidResponses = 0
                    val command = decision.command
                    command.message?.trim()?.takeIf(String::isNotBlank)?.let { modelMessage ->
                        onProgress("模型回复：${modelMessage.take(MAX_VISIBLE_MODEL_REPLY_CHARS)}")
                    }
                    onProgress(
                        "第 $step/$maxSteps 步 · 模型动作：${describeProgressAction(command)}",
                    )
                    val navigationConflict = topLevelNavigationActionConflict(
                        task = request.task,
                        screen = screen,
                        command = command,
                    )
                    if (navigationConflict != null) {
                        noProgressEvents++
                        trace += PhoneAgentStepRecord(
                            step = step,
                            application = screen.application,
                            action = command.action,
                            success = false,
                            detail = navigationConflict,
                        )
                        onProgress("界面证据纠错：$navigationConflict")
                        history += ChatMessage(
                            role = "user",
                            content = "$navigationConflict。请基于同一截图重新判断；若用户只是询问当前页面，直接 finish，不要继续操作。",
                        )
                        continue
                    }
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
                        noProgressEvents++
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

                    val inputPreconditionProblem = inputPreconditionProblem(command, screen)
                    if (inputPreconditionProblem != null) {
                        noProgressEvents++
                        trace += PhoneAgentStepRecord(
                            step = step,
                            application = screen.application,
                            action = command.action,
                            success = false,
                            detail = inputPreconditionProblem,
                        )
                        history += ChatMessage(
                            role = "user",
                            content = "$inputPreconditionProblem。不要猜测输入区坐标；先导航到目标会话并让页面出现 editable 控件。",
                        )
                        val strongerConnection = strongerBuiltinLocalConnection(connection)
                        if (!strongerLocalModelActivated && strongerConnection != null) {
                            strongerLocalModelActivated = true
                            connection = strongerConnection
                            onProgress("本地模型过早请求输入，正在自动切换已安装的 9B 视觉模型")
                        } else {
                            onProgress("第 $step/$maxSteps 步：当前页面没有输入框，已拦截错误输入")
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
                    onProgress(
                        "执行动作：${describeProgressAction(command)} · " +
                            if (actionResult.success) "已提交" else {
                                "失败：${actionResult.detail.orEmpty().take(160)}"
                            },
                    )
                    if (actionResult.success) {
                        if (messageIntent != null &&
                            command.action.equals("type", ignoreCase = true) &&
                            command.text == messageIntent.body
                        ) {
                            typedMessageAtStep = step
                        }
                        val typedAt = typedMessageAtStep
                        if (typedAt != null &&
                            command.action.equals("tap", ignoreCase = true) &&
                            step > typedAt
                        ) {
                            sendTapAtStep = step
                        }
                        previousObservedScreenSignature = currentScreenSignature
                        awaitingChangeAfterAction = command
                        onProgress(
                            "第 $step/$maxSteps 步：动作已提交，正在检查界面是否变化",
                        )
                        history += ChatMessage(
                            role = "user",
                            content = "动作已提交。下一步必须检查新截图验证结果。"
                        )
                    } else {
                        totalActionFailures++
                        noProgressEvents++
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

    private fun inputPreconditionProblem(
        command: PhoneAgentCommand,
        screen: PhoneAgentScreen,
    ): String? {
        if (!command.action.equals("type", ignoreCase = true) &&
            !command.action.equals("type_name", ignoreCase = true)
        ) {
            return null
        }
        if (screen.semanticSummary.isBlank() || " editable" in screen.semanticSummary) return null
        return "输入动作已拦截：当前页面的无障碍语义树中没有可编辑控件"
    }

    private fun preparedMessageShareAction(
        screen: PhoneAgentScreen,
        intent: PhoneAgentMessageIntent,
        recipientSelected: Boolean,
        sendTapped: Boolean,
    ): GuidedShareAction? {
        if (sendTapped) return null
        if (!recipientSelected) {
            val recipient = screen.textElements
                .asSequence()
                .filter { it.text.trim() == intent.recipient }
                .filter { it.centerY >= 300 }
                .minByOrNull { it.centerY }
                ?: return null
            return GuidedShareAction(
                command = PhoneAgentCommand(
                    "Tap",
                    x = recipient.centerX,
                    y = recipient.centerY,
                    preferRoot = true,
                ),
                traceAction = "select_share_recipient",
                progress = "本地 OCR 已定位收件人${intent.recipient} [${recipient.centerX},${recipient.centerY}]，正在选择",
            )
        }
        val previewRecipientMatches = screen.textElements.any { it.text.trim() == intent.recipient }
        val previewBodyMatches = screen.textElements.any { it.text.trim() == intent.body }
        if (!previewRecipientMatches || !previewBodyMatches) return null
        val send = screen.textElements
            .asSequence()
            .filter { it.text.trim() == "发送" }
            .maxByOrNull { it.centerY }
            ?: return null
        return GuidedShareAction(
            command = PhoneAgentCommand(
                "Tap",
                x = send.centerX,
                y = send.centerY,
                preferRoot = true,
            ),
            traceAction = "confirm_share_send",
            progress = "本地 OCR 已定位发送按钮 [${send.centerX},${send.centerY}]，正在确认发送",
        )
    }

    private fun trimHistory(history: List<ChatMessage>): List<ChatMessage> {
        val bounded = if (history.size <= MAX_HISTORY_MESSAGES) {
            history
        } else {
            history.take(2) + history.takeLast(MAX_HISTORY_MESSAGES - 2)
        }
        val imageIndexesToKeep = bounded.indices
            .filter { bounded[it].images.isNotEmpty() }
            .takeLast(MAX_PRIOR_SCREENSHOTS)
            .toSet()
        return bounded.mapIndexed { index, message ->
            if (message.images.isEmpty() || index in imageIndexesToKeep) message
            else message.copy(images = emptyList())
        }
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

    private fun screenSignature(screen: PhoneAgentScreen): String {
        val material = if (screen.semanticSummary.isNotBlank()) {
            "${screen.application.orEmpty()}\n${screen.semanticSummary}"
        } else {
            screen.screenshot.base64Data
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    private fun screenshotHash(screenshot: GuiScreenshot): String =
        MessageDigest.getInstance("SHA-256")
            .digest(screenshot.base64Data.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }

    private fun strongerBuiltinLocalConnection(
        connection: PhoneAgentConnection,
    ): PhoneAgentConnection? {
        if (connection.providerId != BuiltinLocalProvider.ID ||
            connection.model == BuiltinVisionModels.ULTRA_9B.id ||
            BuiltinVisionRuntime.model(BuiltinVisionModels.ULTRA_9B.id) == null
        ) {
            return null
        }
        return connection.copy(
            model = BuiltinVisionModels.ULTRA_9B.id,
            reasoningEffort = BuiltinVisionModels.ULTRA_9B.defaultReasoningMode,
        )
    }

    private fun messageCompletionProblem(
        intent: PhoneAgentMessageIntent?,
        screen: PhoneAgentScreen,
        typedMessageAtStep: Int?,
        sendTapAtStep: Int?,
        lastSubmittedActionChangedScreen: Boolean,
    ): String? {
        intent ?: return null
        if (typedMessageAtStep == null) return "尚未成功输入用户指定正文“${intent.body}”"
        if (sendTapAtStep == null || sendTapAtStep <= typedMessageAtStep) {
            return "输入正文后尚未点击发送控件"
        }
        if (!lastSubmittedActionChangedScreen) return "点击发送后界面没有出现可验证变化"
        if (screen.semanticSummary.isNotBlank()) {
            if (!screen.semanticSummary.contains(intent.recipient)) {
                return "当前语义界面未核对到收件人“${intent.recipient}”"
            }
            if (!screen.semanticSummary.contains(intent.body)) {
                return "当前语义界面未核对到已发送正文“${intent.body}”"
            }
        }
        return null
    }

    private fun safeError(error: Throwable, apiKey: String): String {
        val raw = error.message ?: error.javaClass.simpleName
        val sanitized = if (apiKey.isNotBlank()) raw.replace(apiKey, "[REDACTED]") else raw
        return sanitized.take(MAX_ERROR_CHARS)
    }

    private fun visibleModelReply(response: PhoneAgentModelResponse): String {
        return response.protocolText()
            .replace(CLOSED_THINKING_BLOCK, " ")
            .replace(OPEN_THINKING_BLOCK, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_VISIBLE_MODEL_REPLY_CHARS)
    }

    private fun visibleNaturalLanguageReply(response: PhoneAgentModelResponse): String {
        return visibleNaturalLanguageText(response.content.orEmpty())
    }

    private fun visibleNaturalLanguageText(raw: String): String {
        val cleaned = raw
            .replace(CLOSED_THINKING_BLOCK, " ")
            .replace(OPEN_THINKING_BLOCK, " ")
            .substringBefore("<tool_call>")
            .substringBefore("do(")
            .substringBefore("finish(")
            .substringBefore("{\"action\"")
            .substringBefore("{\"type\"")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned
            .takeUnless { it.startsWith("{") || it.startsWith("[") }
            .orEmpty()
            .take(MAX_VISIBLE_MODEL_REPLY_CHARS)
    }

    private fun describeProgressAction(command: PhoneAgentCommand): String = when (
        command.action.lowercase()
    ) {
        "launch" -> "正在打开${command.app?.take(40) ?: "应用"}"
        "tap" -> "正在点击屏幕 (${command.x ?: "?"}, ${command.y ?: "?"})"
        "long press" -> "正在长按屏幕"
        "double tap" -> "正在双击屏幕"
        "type", "type_name" -> "正在输入文字"
        "swipe" -> "正在滑动屏幕"
        "back" -> "正在返回上一页"
        "home" -> "正在返回桌面"
        "wait" -> "正在等待页面加载"
        "key" -> "正在按下${command.text?.take(30) ?: "按键"}"
        "note" -> "正在记录当前结果"
        else -> "正在执行 ${command.action.take(40)}"
    }

    private companion object {
        const val GUIDED_LOG_TAG = "MurongPhoneGuided"
        const val MAX_INVALID_RESPONSES = 2
        const val MAX_ACTION_FAILURES = 5
        const val MAX_CONSECUTIVE_WAITS = 3
        const val MAX_NO_PROGRESS_EVENTS = 4
        const val MAX_REPEATED_SIGNATURES = 2
        const val MAX_HISTORY_MESSAGES = 20
        const val MAX_PRIOR_SCREENSHOTS = 2
        const val MAX_HISTORY_MESSAGE_CHARS = 8_000
        const val MAX_TRACE_DETAIL_CHARS = 600
        const val MAX_NOTE_CHARS = 1_000
        const val MAX_ERROR_CHARS = 800
        const val MAX_VISIBLE_MODEL_REPLY_CHARS = 2_000
        const val MODEL_PROGRESS_HEARTBEAT_MILLIS = 10_000L
        const val MODEL_STEP_TIMEOUT_MILLIS = 120_000L
        const val MODEL_STREAM_UPDATE_CHARS = 12
        val MODEL_STREAM_SENTENCE_ENDINGS = setOf('。', '！', '？', '!', '?', '\n')
        val CLOSED_THINKING_BLOCK = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
        val OPEN_THINKING_BLOCK = Regex("<think>[\\s\\S]*$", RegexOption.IGNORE_CASE)
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
        "wait",
        "key",
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
