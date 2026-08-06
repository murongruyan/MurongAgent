package com.murong.agent.ui.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.murong.agent.common.toolchain.ToolchainManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.provider.BuiltinLocalProvider
import com.murong.agent.core.provider.ChatImageAttachment
import com.murong.agent.core.provider.ChatMessage
import com.murong.agent.core.provider.ChatRequest
import com.murong.agent.core.provider.StreamDelta
import com.murong.agent.core.loop.ChatSessionManager
import com.murong.agent.core.loop.PendingImageAttachmentUi
import com.murong.agent.core.tool.AndroidGuiAccessibilityAccess
import com.murong.agent.core.tool.AndroidGuiAccessibilityService
import com.murong.agent.core.tool.BuiltinVisionModels
import com.murong.agent.core.tool.BuiltinVisionRuntime
import com.murong.agent.core.tool.GuiAutomationTool
import com.murong.agent.core.tool.GuiToolResponse
import com.murong.agent.core.tool.PhoneAgentApps
import com.murong.agent.shizuku.ShizukuPhoneAgentIsolatedDisplaySession
import com.murong.agent.shizuku.ShizukuSystemAccess
import com.murong.agent.core.tool.PhoneAgentRunResult
import com.murong.agent.core.tool.WebSearchTool
import com.murong.agent.core.tool.isUsableWebSearchResult
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class AssistantFastTurnState(
    val responseId: Long = 0L,
    val routeLabel: String = "",
    val isProcessing: Boolean = false,
    val responseText: String = "",
    val error: String? = null,
)

internal data class AssistantBackgroundTurnResult(
    val accepted: Boolean,
    val responseText: String = "",
    val needsAttention: Boolean = false,
    val error: String? = null,
)

/**
 * Runs an assistant turn outside the popup Activity lifecycle, so dismissing or expanding the
 * popup does not silently cancel an already accepted model/tool request.
 */
@Singleton
class AssistantConversationRunner @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sessionManager: ChatSessionManager,
    private val configRepository: ConfigRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _lastError = MutableStateFlow<String?>(null)
    private val _fastTurnState = MutableStateFlow(AssistantFastTurnState())
    private val fastHistory = ArrayDeque<ChatMessage>()
    private val fastResponseIds = AtomicLong(9_000_000_000L)
    private val cancelledFastResponseId = AtomicLong(-1L)
    @Volatile private var fastTurnJob: Job? = null
    @Volatile private var activePhoneDisplay: ShizukuPhoneAgentIsolatedDisplaySession? = null

    val state = sessionManager.state
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    internal val fastTurnState: StateFlow<AssistantFastTurnState> =
        _fastTurnState.asStateFlow()

    fun sendMain(text: String, images: List<PendingImageAttachmentUi> = emptyList()) {
        if (text.isBlank() || state.value.isProcessing) return
        _fastTurnState.value = AssistantFastTurnState()
        _lastError.value = null
        scope.launch {
            runCatching {
                sessionManager.clearLastAutoRouteDecision()
                sessionManager.sendMessage(text, pendingImages = images)
            }.onFailure { error ->
                _lastError.value = error.message?.trim()?.take(300)
                    ?.ifBlank { null }
                    ?: "语音助手请求失败"
            }
        }
    }

    /**
     * Explicit screen/image understanding. Unlike normal chat this deliberately uses the model
     * selected for phone/screen work and never attaches an image unless the user chose a visual
     * action or explicitly referred to the screen.
     */
    fun sendVisualAnalysis(
        text: String,
        images: List<PendingImageAttachmentUi>,
    ) {
        if (text.isBlank() || images.isEmpty() || state.value.isProcessing) return
        _lastError.value = null
        val job = scope.launch {
            val config = configRepository.getConfig()
            // Tapping an explicit visual action is affirmative consent for this one image. Keep
            // the remote-screenshot guard below, but do not silently discard a selected crop.
            val visualConfig = config.getPhoneAgentResolvedConfig().copy(
                enableMultimodalMessages = true,
            )
            if (!visualConfig.isActiveProviderLocal() && !config.phoneAgentAllowRemoteScreenshots) {
                _fastTurnState.value = AssistantFastTurnState(
                    responseId = fastResponseIds.incrementAndGet(),
                    routeLabel = "屏幕理解",
                    error = "已阻止发送截图：请先在语音助手设置中允许所选视觉 API 接收截图，或改用已安装的本地视觉模型。",
                )
                return@launch
            }
            if (!visualConfig.isActiveProviderLocal()) {
                runCatching {
                    sessionManager.clearLastAutoRouteDecision()
                    sessionManager.sendMessageWithExecutionConfig(
                        text = visualAnalysisPrompt(text),
                        pendingImages = images,
                        forcedExecutionConfig = visualConfig,
                    )
                }.onFailure { error ->
                    _lastError.value = error.message?.trim()?.take(300)
                        ?.ifBlank { null }
                        ?: "视觉模型请求失败"
                }
                return@launch
            }

            val descriptor = BuiltinVisionModels.installedModel(
                context = appContext,
                modelId = visualConfig.builtinLocalModelOverride,
            )
            if (descriptor?.supportsVision != true) {
                _fastTurnState.value = AssistantFastTurnState(
                    responseId = fastResponseIds.incrementAndGet(),
                    routeLabel = "屏幕理解",
                    error = "当前“手机操作 / 屏幕理解模型”不是已安装的视觉模型；请在语音助手设置中选择 Qwen、Gemma 或 GLM-Edge-V 等视觉模型。",
                )
                return@launch
            }
            val responseId = fastResponseIds.incrementAndGet()
            _fastTurnState.value = AssistantFastTurnState(
                responseId = responseId,
                routeLabel = "本地视觉识别",
                isProcessing = true,
            )
            runCatching {
                val image = images.last().toLocalChatImage()
                val response = BuiltinLocalProvider().chatStream(
                    request = ChatRequest(
                        messages = listOf(
                            ChatMessage(
                                role = "system",
                                content = "你是 Murong 的离线视觉理解助手。本轮只理解用户明确选择的图片或屏幕内容。" +
                                    "不要调用工具、不要操作手机、不要输出工具协议；用中文直接回答。",
                            ),
                            ChatMessage(
                                role = "user",
                                content = visualAnalysisPrompt(text),
                                images = listOf(image),
                            ),
                        ),
                        model = descriptor.id,
                        temperature = 0.2,
                        maxTokens = 480,
                        stream = true,
                        reasoningEffort = "off",
                        tools = null,
                    ),
                    apiKey = "",
                    baseUrl = null,
                    onDelta = { delta ->
                        if (
                            cancelledFastResponseId.get() != responseId &&
                            delta is StreamDelta.Content &&
                            delta.text.isNotEmpty()
                        ) {
                            _fastTurnState.value = _fastTurnState.value.copy(
                                responseText = _fastTurnState.value.responseText + delta.text,
                            )
                        }
                    },
                )
                if (cancelledFastResponseId.get() == responseId) return@runCatching
                val finalText = _fastTurnState.value.responseText.trim()
                    .ifBlank { response.content.orEmpty().trim() }
                check(finalText.isNotBlank()) { "本地视觉模型没有返回内容" }
                _fastTurnState.value = _fastTurnState.value.copy(
                    isProcessing = false,
                    responseText = finalText,
                )
            }.onFailure { error ->
                if (cancelledFastResponseId.get() == responseId) return@onFailure
                _fastTurnState.value = _fastTurnState.value.copy(
                    isProcessing = false,
                    error = error.message?.take(220) ?: "本地视觉识别失败",
                )
            }
        }
        fastTurnJob = job
        job.invokeOnCompletion {
            if (fastTurnJob === job) fastTurnJob = null
        }
    }

    internal fun dispatchFast(text: String, route: AssistantRequestRoute) {
        if (
            text.isBlank() ||
            _fastTurnState.value.isProcessing
        ) {
            return
        }
        val responseId = fastResponseIds.incrementAndGet()
        _lastError.value = null
        _fastTurnState.value = AssistantFastTurnState(
            responseId = responseId,
            routeLabel = route.label,
            isProcessing = true,
        )
        val job = scope.launch {
            val instantResponse = if (route.kind == AssistantTaskKind.INSTANT_LOCAL) {
                runCatching { AssistantLocalActions.execute(appContext, text) }
                    .getOrElse {
                        _fastTurnState.value = _fastTurnState.value.copy(
                            isProcessing = false,
                            error = "系统指令执行失败，请重试。",
                        )
                        return@launch
                    }
            } else {
                null
            }
            if (cancelledFastResponseId.get() == responseId) return@launch
            if (instantResponse != null) {
                _fastTurnState.value = _fastTurnState.value.copy(
                    isProcessing = false,
                    responseText = instantResponse,
                )
                rememberFastExchange(text, instantResponse)
                return@launch
            }

            val preferredModelId = AssistantInvocationPreferences.read(appContext)
                .fastLocalModelId
            val descriptor = BuiltinVisionModels.fastestInstalledTextModel(
                context = appContext,
                preferredModelId = preferredModelId,
            )
            if (cancelledFastResponseId.get() == responseId) return@launch
            if (descriptor == null) {
                if (state.value.isProcessing) {
                    _fastTurnState.value = _fastTurnState.value.copy(
                        isProcessing = false,
                        responseText = "后台主任务仍在运行；安装极速本地模型后，日常问答可同时处理。",
                    )
                    return@launch
                }
                _fastTurnState.value = AssistantFastTurnState()
                sendMain(text)
                return@launch
            }
            val globalConfig = configRepository.getConfig()
            val webResult = if (route.kind == AssistantTaskKind.FAST_LOCAL_WEB) {
                withTimeoutOrNull(FAST_WEB_TIMEOUT_MILLIS) {
                    WebSearchTool(globalConfig).execute(
                        buildJsonObject {
                            put("query", text)
                            put("maxResults", 5)
                        }.toString(),
                    )
                } ?: "联网检索超时：${FAST_WEB_TIMEOUT_MILLIS / 1_000} 秒内没有返回结果。"
            } else {
                null
            }
            val webContext = webResult?.takeIf(::isUsableWebSearchResult)
            if (route.kind == AssistantTaskKind.FAST_LOCAL_WEB && webContext == null) {
                _fastTurnState.value = _fastTurnState.value.copy(
                    isProcessing = false,
                    responseText = fastWebSearchUnavailableMessage(webResult),
                )
                return@launch
            }
            if (cancelledFastResponseId.get() == responseId) return@launch
            runCatching {
                if (state.value.isProcessing && globalConfig.isActiveProviderLocal()) {
                    error("主任务正在占用本地推理引擎")
                }
                if (cancelledFastResponseId.get() == responseId) return@runCatching
                val requestMessages = buildList {
                    add(
                        ChatMessage(
                            role = "system",
                            content = buildFastSystemPrompt(
                                webContext = webContext,
                                currentRequest = text,
                            ),
                        ),
                    )
                    // A tiny on-device model readily latches onto the last completed answer when
                    // an unrelated fresh-search result is appended to a multi-turn transcript.
                    // Web summaries must be stateless: answer the current query from this search,
                    // not from a previous chat topic.
                    if (route.kind != AssistantTaskKind.FAST_LOCAL_WEB) {
                        addAll(synchronized(fastHistory) { fastHistory.takeLast(6) })
                    }
                    add(ChatMessage(role = "user", content = text))
                }
                val provider = BuiltinLocalProvider()
                val response = provider.chatStream(
                    request = ChatRequest(
                        messages = requestMessages,
                        model = descriptor.id,
                        temperature = 0.25,
                        maxTokens = 320,
                        stream = true,
                        reasoningEffort = "off",
                        tools = null,
                    ),
                    apiKey = "",
                    baseUrl = null,
                    onDelta = { delta ->
                        if (
                            cancelledFastResponseId.get() != responseId &&
                            delta is StreamDelta.Content &&
                            delta.text.isNotEmpty()
                        ) {
                            _fastTurnState.value = _fastTurnState.value.copy(
                                responseText = _fastTurnState.value.responseText + delta.text,
                            )
                        }
                    },
                )
                if (cancelledFastResponseId.get() == responseId) return@runCatching
                val finalText = _fastTurnState.value.responseText.trim()
                    .ifBlank { response.content.orEmpty().trim() }
                check(finalText.isNotBlank()) { "轻量本地模型没有返回内容" }
                _fastTurnState.value = _fastTurnState.value.copy(
                    isProcessing = false,
                    responseText = finalText,
                )
                rememberFastExchange(text, finalText)
            }.onFailure { error ->
                if (cancelledFastResponseId.get() == responseId) return@onFailure
                if (route.kind == AssistantTaskKind.FAST_LOCAL_WEB) {
                    _fastTurnState.value = _fastTurnState.value.copy(
                        isProcessing = false,
                        error = "已取得联网检索结果，但本地总结模型未能完成回答：${
                            error.message?.take(120) ?: error.javaClass.simpleName
                        }。请重试，不会改用没有搜索证据的模型回答。",
                    )
                    return@onFailure
                }
                if (state.value.isProcessing) {
                    _fastTurnState.value = _fastTurnState.value.copy(
                        isProcessing = false,
                        error = error.message?.take(160)
                            ?: "后台任务正在占用模型，请稍后重试",
                    )
                    return@onFailure
                }
                _fastTurnState.value = _fastTurnState.value.copy(
                    isProcessing = false,
                    error = "轻量本地模型失败，已转交主模型：${
                        error.message?.take(120) ?: error.javaClass.simpleName
                    }",
                )
                sendMain(text)
            }
        }
        fastTurnJob = job
        job.invokeOnCompletion {
            if (fastTurnJob === job) fastTurnJob = null
        }
    }

    internal fun postLocalStatus(message: String, routeLabel: String) {
        _fastTurnState.value = AssistantFastTurnState(
            responseId = fastResponseIds.incrementAndGet(),
            routeLabel = routeLabel,
            responseText = message,
        )
    }

    internal suspend fun runMainAndAwait(
        text: String,
        forcedExecutionConfig: ProviderConfig? = null,
    ): AssistantBackgroundTurnResult {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return AssistantBackgroundTurnResult(
                accepted = false,
                error = "任务内容为空",
            )
        }
        val before = state.value
        if (before.isProcessing) {
            return AssistantBackgroundTurnResult(
                accepted = false,
                error = "已有任务正在运行，请稍后重试",
            )
        }
        val baselineId = before.messages.maxOfOrNull { it.id } ?: 0L
        return runCatching {
            sessionManager.clearLastAutoRouteDecision()
            if (forcedExecutionConfig == null) {
                sessionManager.sendMessage(normalized)
            } else {
                sessionManager.sendMessageWithExecutionConfig(
                    text = normalized,
                    forcedExecutionConfig = forcedExecutionConfig,
                )
            }
            val completed = state.value
            val newMessages = completed.messages.filter { it.id > baselineId }
            val responseMessage = newMessages.lastOrNull { it.role == "assistant" }
                ?: newMessages.lastOrNull { it.role == "system" }
            val response = responseMessage?.content.orEmpty()
            val reportedError = completed.error ?: response.takeIf {
                responseMessage?.role == "system" &&
                    (it.startsWith("⚠") || "未配置可用模型" in it)
            }
            AssistantBackgroundTurnResult(
                accepted = true,
                responseText = response,
                needsAttention = completed.pendingApproval != null ||
                    completed.pendingAskRequest != null ||
                    completed.pendingClarificationRequest != null,
                error = reportedError,
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            AssistantBackgroundTurnResult(
                accepted = true,
                error = error.message?.trim()?.take(300)
                    ?.ifBlank { null }
                    ?: "后台任务执行失败",
            )
        }
    }

    internal suspend fun runCodeAndAwait(text: String): AssistantBackgroundTurnResult {
        val codeConfig = configRepository.getConfig().getAssistantCodeResolvedConfig()
        return runMainAndAwait(text, forcedExecutionConfig = codeConfig)
    }

    /**
     * Phone operations must use the explicitly selected Phone Agent model and its strict action
     * protocol. Sending these requests through the ordinary chat agent lets tiny local chat
     * models invent malformed tool JSON instead of actually controlling the device.
     */
    internal suspend fun runPhoneAndAwait(
        text: String,
        onProgress: (String) -> Unit = {},
    ): AssistantBackgroundTurnResult {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return AssistantBackgroundTurnResult(
                accepted = false,
                error = "手机任务内容为空",
            )
        }
        sessionManager.recordAssistantRequest(normalized)
        return runCatching {
            // A new Phone Agent request owns a new isolated workspace. Retire any result page
            // retained by the previous request before allocating the next display.
            releasePhoneDisplayAndBroker(activePhoneDisplay)
            activePhoneDisplay = null
            val currentConfig = configRepository.getConfig()
            val isolatedDisplay = ShizukuPhoneAgentIsolatedDisplaySession(appContext)
                .takeIf {
                    ShizukuSystemAccess.isAgentDisplayAvailable() &&
                        !requiresPhysicalPhoneDisplay(normalized)
                }
            activePhoneDisplay = isolatedDisplay
            val guiTool = GuiAutomationTool(
                configProvider = { currentConfig },
                progressReporter = onProgress,
                isolatedDisplaySession = isolatedDisplay,
                retainIsolatedDisplayOnCompletion = isolatedDisplay != null,
            )
            val leadingApp = leadingLaunchAppLabel(normalized)
            val simpleApp = simpleLaunchAppLabel(normalized)
            if (leadingApp != null && (simpleApp != null || isolatedDisplay == null)) {
                onProgress("正在直接启动$leadingApp，无需先读取屏幕")
                val packageName = PhoneAgentApps.packageFor(leadingApp)
                val launched = launchInstalledApplication(packageName)
                if (launched) delay(DIRECT_APP_LAUNCH_SETTLE_MILLIS)
                if (!launched || simpleApp != null) {
                    val result = AssistantBackgroundTurnResult(
                        accepted = true,
                        responseText = if (launched) "已打开$leadingApp。" else "",
                        error = if (launched) null else "找不到可启动的应用：$leadingApp",
                    )
                    sessionManager.recordAssistantReply(
                        userText = normalized,
                        assistantText = result.error ?: result.responseText,
                    )
                    return@runCatching result
                }
                onProgress("已打开$leadingApp，继续完成后续操作")
            } else if (leadingApp != null) {
                onProgress("将在隔离屏幕中直接启动$leadingApp，主屏可继续使用")
            }
            if (!AndroidGuiAccessibilityService.isConnected()) {
                onProgress("检测到 Murong 无障碍未连接，正在通过 Root 自动启用")
                val accessibilityResult = AndroidGuiAccessibilityAccess.enableWithRoot(appContext)
                onProgress(accessibilityResult.message)
                if (!accessibilityResult.success) {
                    val result = AssistantBackgroundTurnResult(
                        accepted = true,
                        needsAttention = true,
                        error = accessibilityResult.message,
                    )
                    sessionManager.recordAssistantReply(
                        userText = normalized,
                        assistantText = accessibilityResult.message,
                    )
                    return@runCatching result
                }
            }
            val toolArgs = buildJsonObject {
                put("action", "run_task")
                put("target", "android")
                put("task", normalized)
            }
            val execution = try {
                guiTool.executeWithResult(toolArgs.toString())
            } catch (error: Throwable) {
                if (activePhoneDisplay === isolatedDisplay) {
                    runCatching { isolatedDisplay?.close() }
                    activePhoneDisplay = null
                }
                throw error
            }
            val response = assistantJson.decodeFromString<GuiToolResponse>(execution.output)
            val phoneResult = response.modelResult
                ?.let { raw ->
                    runCatching {
                        assistantJson.decodeFromString<PhoneAgentRunResult>(raw)
                    }.getOrNull()
                }

            // Account-login models execute tools in their native conversation rather than through
            // a second API credential. Keep that supported without sending local models down this
            // generic path.
            if (phoneResult?.status == "delegated") {
                return runMainAndAwait(
                    "请直接执行下面的手机操作任务。使用 gui 工具逐步观察并操作 Android；" +
                        "不要解释工具协议，不要询问如何启动应用。\n\n用户任务：$normalized",
                )
            }

            AssistantBackgroundTurnResult(
                accepted = true,
                responseText = response.message.orEmpty(),
                needsAttention = phoneResult?.requiresUserAction == true,
                error = response.error.takeUnless { response.success },
            ).also { result ->
                sessionManager.recordAssistantReply(
                    userText = normalized,
                    assistantText = result.error ?: result.responseText,
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            AssistantBackgroundTurnResult(
                accepted = true,
                error = error.message?.trim()?.take(300)
                    ?.ifBlank { null }
                    ?: "Phone Agent 执行失败",
            ).also { result ->
                sessionManager.recordAssistantReply(
                    userText = normalized,
                    assistantText = result.error.orEmpty(),
                )
            }
        }
    }

    /** Backwards-compatible call site for non-voice assistant callers. */
    fun send(text: String, images: List<PendingImageAttachmentUi> = emptyList()) =
        sendMain(text, images)

    fun cancelCurrent(): Boolean {
        val phoneDisplay = activePhoneDisplay
        val brokerHasDisplay = runCatching {
            val service = ShizukuSystemAccess.connectedAgentDisplayService()
            (service?.currentAgentDisplayId() ?: -1) >= 0
        }.getOrDefault(false)
        if (phoneDisplay != null || brokerHasDisplay) {
            BuiltinVisionRuntime.cancelActiveGeneration()
            activePhoneDisplay = null
            scope.launch { releasePhoneDisplayAndBroker(phoneDisplay) }
        }
        val fast = _fastTurnState.value
        if (fast.isProcessing) {
            cancelledFastResponseId.set(fast.responseId)
            BuiltinVisionRuntime.cancelActiveGeneration()
            fastTurnJob?.cancel()
            _fastTurnState.value = fast.copy(
                isProcessing = false,
                error = "已取消本次请求",
            )
            return true
        }
        return sessionManager.cancelCurrentProcessing() || phoneDisplay != null || brokerHasDisplay
    }

    fun hasRetainedPhoneDisplay(): Boolean =
        activePhoneDisplay != null &&
            runCatching {
                ShizukuSystemAccess.agentDisplayService()?.currentAgentDisplayId() ?: -1
            }.getOrDefault(-1) >= 0

    /**
     * Session teardown normally owns the release. The broker-level second pass is intentional:
     * cancellation can race a vendor Binder timeout, and after that race the Kotlin session may
     * already have cleared its local reference while the Root process still owns the display.
     */
    private suspend fun releasePhoneDisplayAndBroker(
        phoneDisplay: ShizukuPhoneAgentIsolatedDisplaySession?,
    ) {
        runCatching { phoneDisplay?.close() }
        withContext(Dispatchers.IO) {
            runCatching {
                val service = ShizukuSystemAccess.connectedAgentDisplayService()
                if ((service?.currentAgentDisplayId() ?: -1) >= 0) {
                    service?.releaseAgentDisplay()
                }
            }
        }
    }

    private suspend fun launchInstalledApplication(packageName: String): Boolean {
        val normalized = packageName.trim()
        if (ANDROID_PACKAGE_NAME.matches(normalized)) {
            val rootLaunchSucceeded = withContext(Dispatchers.IO) {
                runCatching {
                    val command =
                        "monkey -p '$normalized' -c android.intent.category.LAUNCHER 1 " +
                            ">/dev/null 2>&1"
                    val process = ProcessBuilder(
                        ToolchainManager.resolveSystemCommandPath("su"),
                        "-c",
                        command,
                    ).redirectErrorStream(true).start()
                    val finished = process.waitFor(
                        DIRECT_APP_LAUNCH_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    )
                    if (!finished) {
                        process.destroy()
                        if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                            process.destroyForcibly()
                        }
                    }
                    finished && process.exitValue() == 0
                }.getOrDefault(false)
            }
            if (rootLaunchSucceeded) return true
        }
        return runCatching {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(normalized)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun buildFastSystemPrompt(
        webContext: String?,
        currentRequest: String,
    ): String = buildString {
        append(
            "你是 Murong 的极速本地语音助手。只处理日常闲聊或对检索结果做简短总结。" +
                "默认用中文，直接回答，控制在 1 到 4 句；不要声称执行了未执行的操作，" +
                "不要调用工具，不输出思考过程。必须回答本轮最后一条用户消息，" +
                "不得复述或回答先前不相关的话题。今天是 ${LocalDate.now()}。",
        )
        if (!webContext.isNullOrBlank()) {
            append(
                "\n\n本轮要回答的问题：${currentRequest.trim()}\n" +
                    "以下是检索器已经成功返回的联网证据。不得说“无法联网”“无法访问互联网”或“无法抓取实时数据”；" +
                    "只依据结果回答，至少提到一条具体标题或来源，标出信息可能过期。若结果与问题不相关，明确说“本次检索结果不足以可靠回答”，" +
                    "不要用模型记忆补全：\n",
            )
            append(webContext.take(8_000))
        }
    }

    private fun rememberFastExchange(userText: String, assistantText: String) {
        synchronized(fastHistory) {
            fastHistory.addLast(ChatMessage(role = "user", content = userText.take(1_000)))
            fastHistory.addLast(
                ChatMessage(role = "assistant", content = assistantText.take(2_000)),
            )
            while (fastHistory.size > 8) fastHistory.removeFirst()
        }
        sessionManager.recordAssistantExchange(userText, assistantText)
    }

    private fun PendingImageAttachmentUi.toLocalChatImage(): ChatImageAttachment {
        val parsed = Uri.parse(uri)
        val bytes = when (parsed.scheme?.lowercase()) {
            "file", null -> File(requireNotNull(parsed.path) { "图片路径无效" }).readBytes()
            else -> appContext.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: error("无法读取图片")
        }
        require(bytes.isNotEmpty()) { "图片为空" }
        require(bytes.size <= MAX_LOCAL_VISION_IMAGE_BYTES) {
            "图片过大，请重新圈选更小区域后再试"
        }
        return ChatImageAttachment(
            mimeType = mimeType?.takeIf { it.startsWith("image/") } ?: "image/png",
            base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
            fileName = fileName.ifBlank { "screen.png" },
            sizeBytes = bytes.size.toLong(),
        )
    }

    private fun visualAnalysisPrompt(userText: String): String =
        "请只根据用户明确选择的屏幕图片回答，不执行任何点击、输入、打开应用或其他动作。\n\n用户问题：${userText.trim()}"

    private companion object {
        const val FAST_WEB_TIMEOUT_MILLIS = 12_000L
        const val MAX_LOCAL_VISION_IMAGE_BYTES = 6 * 1024 * 1024
        val assistantJson = Json {
            ignoreUnknownKeys = true
        }
    }
}

internal fun fastWebSearchUnavailableMessage(rawResult: String?): String {
    val detail = rawResult?.trim().orEmpty()
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.take(180)
        .orEmpty()
    return buildString {
        append("联网检索没有取得可用结果，本次未调用本地模型编造回答。")
        if (detail.isNotBlank()) append(" 原因：$detail")
        append(" 请稍后重试，或在设置中配置自定义搜索后端。")
    }
}

internal fun simpleLaunchAppLabel(text: String): String? {
    val candidate = text.trim()
        .removePrefix("请")
        .removePrefix("帮我")
        .removePrefix("给我")
        .removePrefix("替我")
        .let { value ->
            when {
                value.startsWith("打开") -> value.removePrefix("打开")
                value.startsWith("启动") -> value.removePrefix("启动")
                else -> return null
            }
        }
        .removeSuffix("应用")
        .removeSuffix("app")
        .removeSuffix("APP")
        .trim()
        .trimEnd('。', '！', '!', '？', '?')
        .trim()
    if (candidate.isBlank()) return null
    if (ANDROID_PACKAGE_NAME.matches(candidate)) return candidate
    val packageName = PhoneAgentApps.packageMentionedIn(candidate) ?: return null
    val label = PhoneAgentApps.labelForPackage(packageName) ?: return null
    return label.takeIf { candidate.equals(it, ignoreCase = true) }
}

internal fun leadingLaunchAppLabel(text: String): String? {
    val candidate = text.trim()
        .removePrefix("请")
        .removePrefix("帮我")
        .removePrefix("给我")
        .removePrefix("替我")
        .let { value ->
            when {
                value.startsWith("打开") -> value.removePrefix("打开")
                value.startsWith("启动") -> value.removePrefix("启动")
                else -> return null
            }
        }
        .trim()
    if (candidate.isBlank()) return null
    val packageName = PhoneAgentApps.packageMentionedIn(candidate) ?: return null
    val label = PhoneAgentApps.labelForPackage(packageName) ?: return null
    return label.takeIf { candidate.startsWith(it, ignoreCase = true) }
}

private const val DIRECT_APP_LAUNCH_SETTLE_MILLIS = 650L
private const val DIRECT_APP_LAUNCH_TIMEOUT_SECONDS = 5L
private val ANDROID_PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")

private fun requiresPhysicalPhoneDisplay(task: String): Boolean {
    val normalized = task.lowercase()
    return listOf(
        "视频通话", "视频电话", "打视频", "语音通话", "语音电话", "打电话",
        "打开相机", "启动相机", "拍照", "录像", "扫码", "付款", "支付",
    ).any(normalized::contains)
}
