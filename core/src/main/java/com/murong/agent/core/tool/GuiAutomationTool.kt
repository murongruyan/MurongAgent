package com.murong.agent.core.tool

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.murong.agent.common.shell.KeepShellPublic
import com.murong.agent.core.config.GuiInferenceMode
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.isLocalModelBaseUrl
import com.murong.agent.core.provider.ChatImageAttachment
import com.murong.agent.core.provider.ChatMessage
import com.murong.agent.core.provider.ChatRequest
import com.murong.agent.core.provider.OpenAIProvider
import com.murong.agent.core.provider.ProviderRegistry
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val FOREGROUND_ACTIVITY_COMPONENT = Regex(
    "([A-Za-z0-9._]+)/([A-Za-z0-9._]+)",
)

/**
 * Unified Android implementation of the cross-platform `gui` tool.
 *
 * Semantic Accessibility data is preferred. Root/UIAutomator remains a compatibility fallback,
 * and visual inference is an explicit action with separate local/API privacy gates.
 */
class GuiAutomationTool(
    private val progressReporter: (String) -> Unit = {},
    private val isolatedDisplaySession: PhoneAgentIsolatedDisplaySession? = null,
    private val retainIsolatedDisplayOnCompletion: Boolean = false,
    private val configProvider: () -> ProviderConfig,
) : Tool {
    override val name: String = "gui"

    override val description: String =
        "跨平台 GUI 自动化工具（当前目标为 Android）。普通操作先 observe；需要自主完成多步手机任务或跨应用外卖比价时，使用 run_task 让当前已配置的聊天模型连续观察、执行、纠错，并在支付/验证码前人工接管。"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to mapOf(
            "target" to mapOf(
                "type" to "string",
                "enum" to listOf("android"),
                "description" to "目标平台，Android 端固定为 android"
            ),
            "action" to mapOf(
                "type" to "string",
                "enum" to listOf(
                    "observe",
                    "click",
                    "long_click",
                    "input",
                    "scroll",
                    "tap",
                    "swipe",
                    "key",
                    "launch",
                    "wait",
                    "screenshot",
                    "vision_query",
                    "run_task"
                )
            ),
            "nodeId" to mapOf(
                "type" to "string",
                "description" to "最近一次 observe 返回的 observation-scoped 节点 ID"
            ),
            "text" to mapOf(
                "type" to "string",
                "description" to "input 要写入的文本"
            ),
            "selectorText" to mapOf(
                "type" to "string",
                "description" to "仅供 Root/UIAutomator 回退定位节点的现有文字"
            ),
            "contentDescription" to mapOf("type" to "string"),
            "resourceId" to mapOf("type" to "string"),
            "className" to mapOf("type" to "string"),
            "index" to mapOf("type" to "integer", "minimum" to 0),
            "x" to mapOf("type" to "integer"),
            "y" to mapOf("type" to "integer"),
            "startX" to mapOf("type" to "integer"),
            "startY" to mapOf("type" to "integer"),
            "endX" to mapOf("type" to "integer"),
            "endY" to mapOf("type" to "integer"),
            "durationMs" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 5_000),
            "direction" to mapOf(
                "type" to "string",
                "enum" to listOf("forward", "backward", "up", "down", "left", "right")
            ),
            "key" to mapOf(
                "type" to "string",
                "description" to "back/home/recents/notifications/quick_settings 或 Android KEYCODE"
            ),
            "packageName" to mapOf("type" to "string"),
            "waitMs" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 60_000),
            "maxNodes" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 500),
            "prompt" to mapOf(
                "type" to "string",
                "description" to "vision_query 要模型定位或解释的目标"
            ),
            "task" to mapOf(
                "type" to "string",
                "description" to "run_task 要连续完成的手机任务"
            ),
            "taskType" to mapOf(
                "type" to "string",
                "enum" to listOf("general", "food_delivery_compare"),
                "description" to "外卖到手价比较使用 food_delivery_compare"
            ),
            "maxSteps" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100),
            "platforms" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "外卖比价平台，如美团、饿了么、京东秒送、淘宝闪购"
            ),
            "quantity" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 99),
            "cropLeft" to mapOf("type" to "integer", "minimum" to 0),
            "cropTop" to mapOf("type" to "integer", "minimum" to 0),
            "cropRight" to mapOf("type" to "integer", "minimum" to 1),
            "cropBottom" to mapOf("type" to "integer", "minimum" to 1)
        ),
        "required" to listOf("action")
    )

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val obj = runCatching { json.parseToJsonElement(args) as JsonObject }.getOrNull()
            ?: return null
        val requestedAction = obj.string("action") ?: return null
        val action = if (requestedAction.equals("open", ignoreCase = true)) {
            "launch"
        } else {
            requestedAction
        }
        val risk = when (action) {
            "observe", "wait" -> ApprovalRiskLevel.LOW
            "run_task" -> ApprovalRiskLevel.HIGH
            "screenshot", "vision_query" -> ApprovalRiskLevel.MEDIUM
            else -> ApprovalRiskLevel.MEDIUM
        }
        val config = configProvider()
        val mayUseRemoteVision = when (config.guiInferenceMode) {
            GuiInferenceMode.LOCAL_ONLY -> false
            GuiInferenceMode.USER_API -> !config.isActiveProviderLocal()
            GuiInferenceMode.LOCAL_FIRST ->
                config.guiAllowRemoteScreenshots && !config.isActiveProviderLocal()
        }
        val scope = when {
            action == "vision_query" && mayUseRemoteVision -> setOf("gui:remote_screenshot")
            action == "run_task" -> buildSet {
                add("gui:continuous_control")
                if (!config.isActiveProviderLocal()) add("gui:remote_screenshot")
            }
            else -> emptySet()
        }
        return ToolApprovalRequest(
            toolName = name,
            summary = when (action) {
                "observe" -> "读取当前界面语义树"
                "screenshot" -> "读取当前界面截图摘要"
                "vision_query" -> "使用视觉模型识别当前界面"
                "run_task" -> "由手机操作 Agent 选中的模型连续操作手机"
                "input" -> "向界面输入文本"
                "launch" -> "启动 Android 应用"
                else -> "执行 GUI 操作：$action"
            },
            detail = obj.string("prompt")
                ?: obj.string("task")
                ?: obj.string("packageName")
                ?: obj.string("nodeId")
                ?: action,
            riskLevel = risk,
            rawArgs = args,
            approvalScopeTokens = scope
        )
    }

    override suspend fun execute(args: String): String = executeWithResult(args).output

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        val response = try {
            val obj = json.parseToJsonElement(args) as? JsonObject
                ?: error("工具参数必须是 JSON 对象")
            val requestedAction = obj.string("action")?.lowercase()
                ?: error("缺少 action")
            val action = if (requestedAction == "open") "launch" else requestedAction
            val target = obj.string("target")
                ?.takeUnless { it.equals("null", ignoreCase = true) }
                ?.lowercase()
                ?: "android"
            require(target == "android") { "Android 端只支持 target=android" }
            executeAction(action, obj)
        } catch (cancelled: CancellationException) {
            // Cancellation is control flow, not a model/tool failure. Converting it into a
            // GuiToolResponse leaks messages such as "StandaloneCoroutine was cancelled" into
            // chat history and lets the cancelled task continue far enough to persist them.
            throw cancelled
        } catch (error: Throwable) {
            GuiToolResponse(
                success = false,
                action = runCatching {
                    (json.parseToJsonElement(args) as JsonObject).string("action")
                }.getOrNull().orEmpty(),
                error = error.message ?: error.javaClass.simpleName
            )
        }
        return ToolExecutionResult(
            output = json.encodeToString(response),
            status = if (response.success) ToolExecutionStatus.SUCCESS else ToolExecutionStatus.FAILURE,
            success = response.success
        )
    }

    private suspend fun executeAction(action: String, obj: JsonObject): GuiToolResponse {
        val service = AndroidGuiAccessibilityService.connectedInstance()
        return when (action) {
            "observe" -> observe(service, obj)
            "click", "long_click" -> {
                val nodeId = obj.string("nodeId")
                val success = if (service != null && nodeId != null) {
                    service.click(nodeId, longClick = action == "long_click")
                } else {
                    executeRootAction(action, obj).first
                }
                actionResult(action, success, if (service != null && nodeId != null) "accessibility" else "root")
            }
            "input" -> {
                val text = obj.string("text") ?: error("input 缺少 text")
                val nodeId = obj.string("nodeId")
                val success = if (service != null && nodeId != null) {
                    service.setText(nodeId, text)
                } else {
                    executeRootAction(action, obj).first
                }
                actionResult(action, success, if (service != null && nodeId != null) "accessibility" else "root")
            }
            "scroll" -> {
                val direction = obj.string("direction")?.lowercase() ?: "forward"
                val success = if (service != null) {
                    service.scroll(
                        nodeId = obj.string("nodeId"),
                        forward = direction !in setOf("backward", "up", "left")
                    )
                } else {
                    executeRootAction(action, obj).first
                }
                actionResult(action, success, if (service != null) "accessibility" else "root")
            }
            "tap" -> {
                val x = obj.int("x") ?: error("tap 缺少 x")
                val y = obj.int("y") ?: error("tap 缺少 y")
                val success = if (service != null) {
                    service.tap(x, y, (obj.int("durationMs") ?: 80).toLong())
                } else {
                    executeRootAction(action, obj).first
                }
                actionResult(action, success, if (service != null) "accessibility" else "root")
            }
            "swipe" -> {
                val startX = obj.int("startX") ?: error("swipe 缺少 startX")
                val startY = obj.int("startY") ?: error("swipe 缺少 startY")
                val endX = obj.int("endX") ?: error("swipe 缺少 endX")
                val endY = obj.int("endY") ?: error("swipe 缺少 endY")
                val duration = (obj.int("durationMs") ?: 350).coerceIn(50, 5_000)
                val success = if (service != null) {
                    service.swipe(startX, startY, endX, endY, duration.toLong())
                } else {
                    executeRootAction(action, obj).first
                }
                actionResult(action, success, if (service != null) "accessibility" else "root")
            }
            "key" -> {
                val key = obj.string("key") ?: error("key 缺少 key")
                val semanticKey = key.lowercase()
                val success = if (
                    service != null &&
                    semanticKey in setOf("back", "home", "recents", "notifications", "quick_settings")
                ) {
                    service.globalAction(semanticKey)
                } else {
                    executeRootAction(action, obj).first
                }
                actionResult(action, success, if (service != null) "accessibility/root" else "root")
            }
            "launch" -> {
                launchApplication(service, obj)
            }
            "wait" -> {
                delay((obj.int("waitMs") ?: 500).coerceIn(0, 60_000).toLong())
                observe(service, obj).copy(action = action)
            }
            "screenshot" -> {
                val screenshot = captureScreenshot(service, obj)
                GuiToolResponse(
                    success = true,
                    action = action,
                    source = if (service != null) "accessibility" else "root",
                    message = "截图仅在内存中生成，未写入磁盘，也未把像素放入工具日志。",
                    imageWidth = screenshot.width,
                    imageHeight = screenshot.height,
                    imageSha256 = sha256(screenshot.base64Data)
                )
            }
            "vision_query" -> visionQuery(service, obj)
            "run_task" -> runPhoneAgentTask(service, obj)
            else -> error("不支持的 GUI action：$action")
        }
    }

    private suspend fun runPhoneAgentTask(
        initialService: AndroidGuiAccessibilityService?,
        obj: JsonObject
    ): GuiToolResponse {
        val task = obj.string("task") ?: obj.string("prompt")
            ?: error("run_task 缺少 task")
        val config = configProvider()
        val request = PhoneAgentTaskRequest(
            task = task.take(MAX_PHONE_TASK_CHARS),
            taskType = obj.string("taskType") ?: "general",
            maxSteps = obj.int("maxSteps"),
            platforms = (obj["platforms"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                ?.takeIf { it.isNotEmpty() }
                ?: config.phoneAgentFoodPlatforms,
            quantity = (obj.int("quantity") ?: 1).coerceIn(1, 99)
        )
        val videoCallIntent = PhoneAgentVideoCallIntent.parse(request.task)
        if (videoCallIntent != null) {
            progressReporter("视频通话需要使用主屏摄像头和麦克风，已切换到主屏执行")
        }
        var isolatedInfo = isolatedDisplaySession
            ?.takeIf { videoCallIntent == null }
            ?.takeIf { it.isAvailable() }
            ?.let { session ->
                runCatching { session.start() }
                    .onSuccess { info ->
                        progressReporter(
                            "已启动离屏操作，应用操作不会占用手机主屏",
                        )
                    }
                    .onFailure { error ->
                        progressReporter(
                            "隔离屏幕启动失败，已回退主屏：${error.message?.take(120).orEmpty()}",
                        )
                    }
                    .getOrNull()
            }
        var activeIsolatedSession = isolatedDisplaySession.takeIf { isolatedInfo != null }
        val explicitMessage = PhoneAgentMessageIntent.parse(request.task)
        val mentionedPackage = PhoneAgentApps.packageMentionedIn(request.task)
        val searchIntent = PhoneAgentSearchIntent.parse(request.task)
        if (
            shouldDirectLaunchPhoneAgentTarget(
                isolatedDisplayActive = isolatedInfo != null,
                videoCallRequested = videoCallIntent != null,
                mentionedPackage = mentionedPackage,
            )
        ) {
            progressReporter(
                "正在主屏直接启动${PhoneAgentApps.labelForPackage(mentionedPackage)}，再核对联系人并发起视频通话",
            )
            val launchResult = executeAction(
                "launch",
                buildJsonObject {
                    put("packageName", mentionedPackage!!)
                    put("prompt", request.task)
                },
            )
            progressReporter(
                if (launchResult.success) {
                    "已在主屏启动${PhoneAgentApps.labelForPackage(mentionedPackage)}，无需先让模型识别旧界面"
                } else {
                    "主屏启动${PhoneAgentApps.labelForPackage(mentionedPackage)}失败，正在让模型从当前界面恢复"
                },
            )
            delay(900)
        }
        val initiallyIsolatedSession = activeIsolatedSession
        if (
            initiallyIsolatedSession != null &&
            mentionedPackage != null &&
            explicitMessage == null &&
            searchIntent?.verifiedNativeSearchUri() == null
        ) {
            if (initiallyIsolatedSession.launchPackage(mentionedPackage)) {
                progressReporter(
                    "已在隔离屏幕直接启动${PhoneAgentApps.labelForPackage(mentionedPackage)}，无需先识别主屏",
                )
                delay(900)
                val isolatedReady = runCatching {
                    initiallyIsolatedSession.captureScreenshot()
                }.isSuccess
                if (!isolatedReady) {
                    progressReporter(
                        "目标应用没有向隔离屏输出画面，已自动回退主屏继续执行",
                    )
                    runCatching { initiallyIsolatedSession.close() }
                    activeIsolatedSession = null
                    isolatedInfo = null
                    executeAction(
                        "launch",
                        buildJsonObject {
                            put("packageName", mentionedPackage)
                            put("prompt", request.task)
                        },
                    )
                    delay(900)
                }
            }
        }
        val shareService = AndroidGuiAccessibilityService.connectedInstance() ?: initialService
        val searchPrepared = if (
            searchIntent != null && searchIntent.packageName == mentionedPackage
        ) {
            progressReporter("正在通过应用搜索框填写“${searchIntent.query}”")
            val nativeSearchLaunched = launchVerifiedNativeSearch(
                searchIntent = searchIntent,
                isolatedSession = activeIsolatedSession,
                service = shareService,
            )
            if (nativeSearchLaunched) {
                progressReporter("已通过抖音自身注册的搜索入口直达“${searchIntent.query}”结果页")
                delay(1_200)
                var verified = verifySearchQueryVisible(
                    query = searchIntent.query,
                    service = shareService,
                    isolatedSession = activeIsolatedSession,
                    isolatedInfo = isolatedInfo,
                )
                if (!verified) {
                    // Douyin can consume a cold-start URI before its search router is ready and
                    // then restore the previous feed. Send the same verified package-scoped URI
                    // once more after the process has warmed; ADB follows this exact path.
                    progressReporter("抖音冷启动尚未显示目标搜索词，正在重试自身搜索入口")
                    delay(900)
                    val retried = launchVerifiedNativeSearch(
                        searchIntent = searchIntent,
                        isolatedSession = activeIsolatedSession,
                        service = shareService,
                    )
                    if (retried) {
                        delay(1_200)
                        verified = verifySearchQueryVisible(
                            query = searchIntent.query,
                            service = shareService,
                            isolatedSession = activeIsolatedSession,
                            isolatedInfo = isolatedInfo,
                        )
                    }
                }
                progressReporter(
                    if (verified) {
                        "已从结果页验证搜索词“${searchIntent.query}”（app-deep-link）"
                    } else {
                        "抖音搜索入口未显示目标词，已禁止确定性点赞并交给模型复核"
                    },
                )
                verified
            } else {
                var textPrepared = shareService?.prepareSearchQuery(
                    searchIntent.query,
                    isolatedInfo?.displayId,
                ) == true
                var preparationSource = "accessibility"
                if (!textPrepared && activeIsolatedSession != null && isolatedInfo != null) {
                    val screenshot = runCatching { activeIsolatedSession.captureScreenshot() }.getOrNull()
                    val searchEntry = screenshot
                        ?.let { OnDeviceScreenOcr.recognize(it) }
                        ?.likelySearchEntry(
                            allowGenericTopBar = searchIntent.packageName != PhoneAgentApps.DOUYIN_PACKAGE,
                        )
                    if (searchEntry != null) {
                        progressReporter("当前应用未提供语义控件，已切换屏幕文字识别来定位搜索入口")
                        val tapped = activeIsolatedSession.tap(
                            (searchEntry.centerX.toLong() * isolatedInfo.width / 1000L).toInt(),
                            (searchEntry.centerY.toLong() * isolatedInfo.height / 1000L).toInt(),
                        )
                        if (tapped) {
                            delay(650)
                            textPrepared = activeIsolatedSession.typeText(searchIntent.query)
                            preparationSource = "screen-ocr"
                        }
                    }
                }
                val submitted = if (textPrepared) {
                    (shareService?.submitFocusedSearch(isolatedInfo?.displayId) == true) ||
                        activeIsolatedSession?.key("KEYCODE_ENTER") == true
                } else {
                    progressReporter("语义控件和屏幕文字暂未定位到搜索入口，正在使用截图视觉模型")
                    false
                }
                if (submitted) {
                    val verified = verifySearchQueryVisible(
                        query = searchIntent.query,
                        service = shareService,
                        isolatedSession = activeIsolatedSession,
                        isolatedInfo = isolatedInfo,
                    )
                    if (verified) {
                        progressReporter(
                            "已提交搜索“${searchIntent.query}”并从界面验证（$preparationSource）",
                        )
                    } else {
                        progressReporter("搜索已提交但界面尚未显示目标词，正在交给模型复核")
                    }
                    verified
                } else {
                    if (textPrepared) {
                        progressReporter("搜索词已填写但提交动作失败，正在交给模型继续处理")
                    }
                    false
                }
            }
        } else {
            false
        }
        val messagePreparedByShare = explicitMessage != null && mentionedPackage != null && (
            if (activeIsolatedSession != null) {
                activeIsolatedSession.launchShareText(mentionedPackage, explicitMessage.body)
            } else {
                shareService?.launchShareText(mentionedPackage, explicitMessage.body) == true
            }
        )
        if (messagePreparedByShare) {
            progressReporter("已通过系统分享能力预填消息，正在选择收件人")
            delay(1_000)
        }
        val controller = PhoneAgentController()
        val result = try {
            if (searchIntent?.searchOnly == true && searchPrepared) {
                PhoneAgentRunResult(
                    success = true,
                    status = "completed",
                    message = "已在${PhoneAgentApps.labelForPackage(searchIntent.packageName)}搜索${searchIntent.query}",
                    stepsExecuted = 0,
                    currentApplication = searchIntent.packageName,
                    trace = listOf(
                        PhoneAgentStepRecord(
                            step = 0,
                            application = searchIntent.packageName,
                            action = "structured_search",
                            success = true,
                            detail = "已通过语义树或屏幕文字定位搜索框，并填写、提交、验证搜索词",
                        ),
                    ),
                )
            } else controller.run(
            request = request,
            config = config,
            onProgress = progressReporter,
            messagePreparedByShare = messagePreparedByShare,
            searchContextVerified = searchIntent == null || searchPrepared,
            device = object : PhoneAgentDevice {
                override suspend fun observe(): PhoneAgentScreen {
                    val service = AndroidGuiAccessibilityService.connectedInstance() ?: initialService
                    val rawScreenshot = activeIsolatedSession?.captureScreenshot()
                        ?: captureScreenshot(service, buildJsonObject { })
                    val screenshot = optimizePhoneAgentScreenshot(rawScreenshot)
                    val isolatedPackage = activeIsolatedSession?.let { session ->
                        runCatching { session.currentPackageName() }.getOrNull()
                    }
                    val rawSemanticObservation = service?.observe(
                        maxNodes = 90,
                        includeText = true,
                        displayId = isolatedInfo?.displayId,
                    )
                    val semanticObservation = selectPhoneAgentSemanticObservation(
                        isolatedDisplayActive = isolatedInfo != null,
                        semanticObservation = rawSemanticObservation,
                        isolatedPackage = isolatedPackage,
                    )
                    val textElements = OnDeviceScreenOcr.recognize(rawScreenshot)
                    val application = resolvePhoneAgentApplication(
                        isolatedDisplayActive = isolatedInfo != null,
                        semanticObservation = semanticObservation,
                        isolatedPackage = isolatedPackage,
                        latestPhysicalPackage = service?.latestObservedWindowPackage(),
                    )
                    val foregroundActivityClass = if (isolatedInfo == null) {
                        readForegroundActivityClass(application)
                    } else {
                        null
                    }
                    return PhoneAgentScreen(
                        screenshot = screenshot,
                        application = application,
                        windowClassName = foregroundActivityClass
                            ?: semanticObservation
                                ?.takeIf { it.success }
                                ?.windowTitle
                            ?: service
                                ?.latestObservedWindowClassName(application)
                                ?.takeIf { isolatedInfo == null },
                        semanticSummary = listOf(
                            semanticObservation?.toPhoneAgentSemanticSummary(
                                displayWidth = isolatedInfo?.width ?: rawScreenshot.width,
                                displayHeight = isolatedInfo?.height ?: rawScreenshot.height,
                            ).orEmpty(),
                            textElements.toPhoneAgentOcrSummary(),
                        ).filter(String::isNotBlank).joinToString("\n"),
                        displayWidth = isolatedInfo?.width ?: rawScreenshot.width,
                        displayHeight = isolatedInfo?.height ?: rawScreenshot.height,
                        textElements = textElements,
                    )
                }

                override suspend fun execute(
                    command: PhoneAgentCommand,
                    screen: PhoneAgentScreen
                ): PhoneAgentDeviceResult {
                    val service = AndroidGuiAccessibilityService.connectedInstance() ?: initialService
                    val normalizedAction = command.action.lowercase()
                    val overlaySensitiveAction = normalizedAction in setOf(
                        "tap", "long press", "double tap", "type", "type_name", "swipe",
                    )
                    val hiddenOverlay = if (
                        activeIsolatedSession == null && overlaySensitiveAction
                    ) {
                        service?.hideTaskOverlayForAutomation()
                    } else {
                        null
                    }
                    val actionResponse = try {
                        when (normalizedAction) {
                        "launch" -> if (activeIsolatedSession != null) {
                            actionResult(
                                "launch",
                                activeIsolatedSession.launchPackage(
                                    PhoneAgentApps.packageFor(command.app.orEmpty()),
                                ),
                                "isolated-display",
                            )
                        } else executeAction(
                            "launch",
                            buildJsonObject {
                                put("packageName", PhoneAgentApps.packageFor(command.app.orEmpty()))
                                put("prompt", command.app.orEmpty())
                            }
                        )
                        "tap" -> if (activeIsolatedSession != null) {
                            val x = scalePhoneCoordinate(command.x, screen.displayWidth, "x")
                            val y = scalePhoneCoordinate(command.y, screen.displayHeight, "y")
                            actionResult(
                                "tap",
                                activeIsolatedSession.tap(x, y),
                                "isolated-display",
                            )
                        } else if (command.preferRoot && KeepShellPublic.checkRoot()) {
                            val x = scalePhoneCoordinate(command.x, screen.displayWidth, "x")
                            val y = scalePhoneCoordinate(command.y, screen.displayHeight, "y")
                            val (success, detail) = executeShellUidTap(x, y)
                            actionResult("tap", success, "root").copy(message = detail)
                        } else {
                            executeAction(
                                "tap",
                                pointArgs(command.x, command.y, screen)
                            )
                        }
                        "long press" -> if (activeIsolatedSession != null) {
                            val x = scalePhoneCoordinate(command.x, screen.displayWidth, "x")
                            val y = scalePhoneCoordinate(command.y, screen.displayHeight, "y")
                            val success = activeIsolatedSession.swipe(
                                x, y, x, y, command.durationMs ?: 800,
                            )
                            actionResult("long_click", success, "isolated-display")
                        } else executeAction(
                            "tap",
                            pointArgs(
                                command.x,
                                command.y,
                                screen,
                                durationMs = command.durationMs ?: 800
                            )
                        )
                        "double tap" -> if (activeIsolatedSession != null) {
                            val x = scalePhoneCoordinate(command.x, screen.displayWidth, "x")
                            val y = scalePhoneCoordinate(command.y, screen.displayHeight, "y")
                            val first = activeIsolatedSession.tap(x, y)
                            if (first) delay(90)
                            actionResult(
                                "double_tap",
                                first && activeIsolatedSession.tap(x, y),
                                "isolated-display",
                            )
                        } else {
                            val args = pointArgs(command.x, command.y, screen)
                            val first = executeAction("tap", args)
                            if (first.success) {
                                delay(90)
                                executeAction("tap", args)
                            } else {
                                first
                            }
                        }
                        "type", "type_name" -> {
                            val text = command.text ?: error("${command.action} 缺少 text")
                            val accessibilitySuccess = service?.setFocusedText(
                                text,
                                isolatedInfo?.displayId,
                            ) == true
                            var usedContextMenuPaste = false
                            var usedAutomationIme = false
                            val isolatedInputSuccess = if (
                                !accessibilitySuccess && activeIsolatedSession != null
                            ) {
                                activeIsolatedSession.typeText(text)
                            } else {
                                false
                            }
                            val automationImeSuccess = if (
                                !accessibilitySuccess &&
                                !isolatedInputSuccess &&
                                activeIsolatedSession == null
                            ) {
                                inputTextThroughAutomationIme(
                                    text = text,
                                    expectedPackage = screen.application,
                                ).also {
                                    usedAutomationIme = it
                                }
                            } else {
                                false
                            }
                            val contextPasteSuccess = if (
                                !accessibilitySuccess &&
                                !isolatedInputSuccess &&
                                !automationImeSuccess &&
                                activeIsolatedSession == null &&
                                service != null &&
                                command.x != null &&
                                command.y != null
                            ) {
                                val clipboardSnapshot = service.stageClipboardText(text)
                                if (clipboardSnapshot != null) {
                                    try {
                                        delay(80)
                                        val x = scalePhoneCoordinate(
                                            command.x,
                                            screen.displayWidth,
                                            "x",
                                        )
                                        val y = scalePhoneCoordinate(
                                            command.y,
                                            screen.displayHeight,
                                            "y",
                                        )
                                        val menuPaste = service.pasteFromContextMenuAt(x, y)
                                        usedContextMenuPaste = menuPaste
                                        if (menuPaste) {
                                            delay(180)
                                            true
                                        } else {
                                            false
                                        }
                                    } finally {
                                        service.restoreClipboard(clipboardSnapshot)
                                    }
                                } else {
                                    false
                                }
                            } else {
                                false
                            }
                            val success = accessibilitySuccess || isolatedInputSuccess ||
                                contextPasteSuccess || automationImeSuccess
                            Log.i(
                                PHONE_INPUT_TAG,
                                "package=${screen.application.orEmpty()} success=$success " +
                                    "accessibility=$accessibilitySuccess isolated=$isolatedInputSuccess " +
                                    "temporaryIme=$automationImeSuccess contextPaste=$contextPasteSuccess",
                            )
                            actionResult(
                                action = "input",
                                success = success,
                                source = when {
                                    accessibilitySuccess -> "accessibility"
                                    isolatedInputSuccess -> "isolated-display"
                                    usedContextMenuPaste -> "accessibility-context-paste"
                                    usedAutomationIme -> "root-temporary-ime"
                                    else -> "input-unavailable"
                                }
                            )
                        }
                        "swipe" -> if (activeIsolatedSession != null) {
                            val x1 = scalePhoneCoordinate(command.startX, screen.displayWidth, "startX")
                            val y1 = scalePhoneCoordinate(command.startY, screen.displayHeight, "startY")
                            val x2 = scalePhoneCoordinate(command.endX, screen.displayWidth, "endX")
                            val y2 = scalePhoneCoordinate(command.endY, screen.displayHeight, "endY")
                            actionResult(
                                "swipe",
                                activeIsolatedSession.swipe(
                                    x1, y1, x2, y2, (command.durationMs ?: 350).coerceIn(50, 5_000),
                                ),
                                "isolated-display",
                            )
                        } else executeAction(
                            "swipe",
                            swipeArgs(command, screen)
                        )
                        "back", "home" -> if (activeIsolatedSession != null) {
                            actionResult(
                                command.action.lowercase(),
                                activeIsolatedSession.key(
                                    if (command.action.equals("back", true)) {
                                        "KEYCODE_BACK"
                                    } else {
                                        "KEYCODE_HOME"
                                    },
                                ),
                                "isolated-display",
                            )
                        } else executeAction(
                            "key",
                            buildJsonObject { put("key", command.action.lowercase()) }
                        )
                        "key" -> if (activeIsolatedSession != null) {
                            actionResult(
                                "key",
                                activeIsolatedSession.key(
                                    command.text ?: error("Key 动作缺少按键"),
                                ),
                                "isolated-display",
                            )
                        } else executeAction(
                            "key",
                            buildJsonObject {
                                put("key", command.text ?: error("Key 动作缺少按键"))
                            },
                        )
                        "wait" -> {
                            delay((command.durationMs ?: 800).coerceIn(200, 3_000).toLong())
                            GuiToolResponse(
                                success = true,
                                action = "wait",
                                source = "controller",
                                message = "等待完成"
                            )
                        }
                        else -> GuiToolResponse(
                            success = false,
                            action = command.action,
                            error = "不支持的 Phone Agent 动作"
                        )
                        }
                    } finally {
                        service?.restoreTaskOverlayAfterAutomation(hiddenOverlay)
                    }
                    if (actionResponse.success && !command.action.equals("Wait", ignoreCase = true)) {
                        delay(PHONE_ACTION_SETTLE_MS)
                    }
                    return PhoneAgentDeviceResult(
                        success = actionResponse.success,
                        detail = actionResponse.error ?: actionResponse.message
                    )
                }
            }
        )
        } finally {
            if (!retainIsolatedDisplayOnCompletion) {
                runCatching { activeIsolatedSession?.close() }
            }
        }
        val accepted = result.success || result.requiresUserAction
        return GuiToolResponse(
            success = accepted,
            action = "run_task",
            source = when {
                config.usesCodexChatGptBackend() -> "phone_agent_current_account"
                config.isActiveProviderLocal() -> "phone_agent_current_local"
                else -> "phone_agent_current_api"
            },
            message = result.message,
            modelResult = json.encodeToString(result),
            error = result.message.takeUnless { accepted }
        )
    }

    private fun pointArgs(
        normalizedX: Int?,
        normalizedY: Int?,
        screen: PhoneAgentScreen,
        durationMs: Int? = null
    ): JsonObject = buildJsonObject {
        put("x", scalePhoneCoordinate(normalizedX, screen.displayWidth, "x"))
        put("y", scalePhoneCoordinate(normalizedY, screen.displayHeight, "y"))
        durationMs?.let { put("durationMs", it.coerceIn(1, 2_000)) }
    }

    private fun swipeArgs(
        command: PhoneAgentCommand,
        screen: PhoneAgentScreen
    ): JsonObject = buildJsonObject {
        put("startX", scalePhoneCoordinate(command.startX, screen.displayWidth, "startX"))
        put("startY", scalePhoneCoordinate(command.startY, screen.displayHeight, "startY"))
        put("endX", scalePhoneCoordinate(command.endX, screen.displayWidth, "endX"))
        put("endY", scalePhoneCoordinate(command.endY, screen.displayHeight, "endY"))
        put("durationMs", (command.durationMs ?: 350).coerceIn(50, 5_000))
    }

    private fun scalePhoneCoordinate(value: Int?, dimension: Int, name: String): Int {
        require(value != null) { "Phone Agent 动作缺少 $name" }
        require(dimension > 0) { "截图尺寸无效，无法换算 $name" }
        return ((value.coerceIn(0, 1000).toLong() * dimension) / 1000L)
            .toInt()
            .coerceIn(0, dimension - 1)
    }

    private fun optimizePhoneAgentScreenshot(screenshot: GuiScreenshot): GuiScreenshot {
        if (screenshot.width <= 0 || screenshot.height <= 0) return screenshot
        val sourceBytes = android.util.Base64.decode(
            screenshot.base64Data,
            android.util.Base64.DEFAULT
        )
        val source = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
            ?: return screenshot
        val scale = (PHONE_SCREENSHOT_MAX_LONG_EDGE.toDouble() /
            maxOf(source.width, source.height).toDouble()).coerceAtMost(1.0)
        val targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val optimized = if (targetWidth == source.width && targetHeight == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }
        val output = ByteArrayOutputStream()
        val encoded = optimized.compress(
            Bitmap.CompressFormat.JPEG,
            PHONE_SCREENSHOT_JPEG_QUALITY,
            output
        )
        val resultBytes = output.toByteArray()
        if (optimized !== source) optimized.recycle()
        source.recycle()
        if (!encoded || resultBytes.isEmpty() || resultBytes.size >= sourceBytes.size) {
            return screenshot
        }
        return GuiScreenshot(
            mimeType = "image/jpeg",
            base64Data = android.util.Base64.encodeToString(
                resultBytes,
                android.util.Base64.NO_WRAP
            ),
            width = targetWidth,
            height = targetHeight
        )
    }

    private suspend fun observe(
        service: AndroidGuiAccessibilityService?,
        obj: JsonObject
    ): GuiToolResponse {
        val config = configProvider()
        val includeText = config.isActiveProviderLocal() || config.guiAllowRemoteSemanticTree
        if (service != null) {
            val observation = service.observe(
                maxNodes = obj.int("maxNodes") ?: DEFAULT_MAX_NODES,
                includeText = includeText
            )
            return GuiToolResponse(
                success = observation.success,
                action = "observe",
                observation = observation,
                source = "accessibility",
                error = observation.error
            )
        }
        if (!KeepShellPublic.checkRoot()) {
            return GuiToolResponse(
                success = false,
                action = "observe",
                error = "AccessibilityService 未启用，Root 也不可用"
            )
        }
        val rootArgs = if (includeText) {
            buildJsonObject {
                put("action", "ui_dump_summary")
                put("lines", (obj.int("maxNodes") ?: DEFAULT_MAX_NODES).coerceAtMost(120))
            }.toString()
        } else {
            buildJsonObject { put("action", "ui_current_focus") }.toString()
        }
        val output = AndroidTool().execute(rootArgs)
        return GuiToolResponse(
            success = !output.startsWith("Error:", ignoreCase = true),
            action = "observe",
            source = "root_uiautomator",
            message = output,
            error = output.takeIf { it.startsWith("Error:", ignoreCase = true) }
        )
    }

    private suspend fun visionQuery(
        service: AndroidGuiAccessibilityService?,
        obj: JsonObject
    ): GuiToolResponse {
        val prompt = obj.string("prompt")?.take(MAX_VISION_PROMPT_CHARS)
            ?: error("vision_query 缺少 prompt")
        val screenshot = captureScreenshot(service, obj)
        val config = configProvider()
        val fullScreen = !hasCrop(obj)

        suspend fun localAttempt(): Pair<String, String> {
            val builtIn = runCatching {
                BuiltinVisionRuntime.infer(prompt, screenshot)
            }
            if (builtIn.isSuccess) {
                return "builtin_vlm" to builtIn.getOrThrow()
            }
            if (config.guiLocalBaseUrl.isNotBlank() || config.guiLocalModel.isNotBlank()) {
                require(config.guiLocalBaseUrl.isNotBlank()) { "高级本地视觉服务缺少 Base URL" }
                require(isLocalModelBaseUrl(config.guiLocalBaseUrl)) {
                    "高级本地视觉 Base URL 必须是 loopback、局域网或 .local 地址"
                }
                require(config.guiLocalModel.isNotBlank()) { "高级本地视觉服务缺少模型名" }
                val response = OpenAIProvider().chat(
                    request = visionRequest(prompt, screenshot, config.guiLocalModel),
                    apiKey = "",
                    baseUrl = config.guiLocalBaseUrl
                )
                val content = response.content?.trim().takeUnless { it.isNullOrBlank() }
                    ?: error("高级本地视觉服务没有返回内容")
                return "external_local" to content
            }
            throw IllegalStateException(
                builtIn.exceptionOrNull()?.message ?: "内置视觉模型不可用",
                builtIn.exceptionOrNull()
            )
        }

        suspend fun apiAttempt(): String {
            val apiConfig = if (
                config.activeProviderId != "murong-local" &&
                (
                    config.getActiveApiKey().isNotBlank() ||
                        (
                            config.isActiveProviderLocal() &&
                                config.getActiveModel().isNotBlank()
                            )
                    )
            ) {
                config
            } else {
                ProviderRegistry.getAllProviders()
                    .asSequence()
                    .flatMap { provider ->
                        config.getRelayConfigs(provider.id).asSequence().map { relay ->
                            config.selectConfiguration(provider.id, relay.id)
                        }
                    }
                    .firstOrNull { candidate ->
                        candidate.getActiveApiKey().isNotBlank() ||
                            (
                                candidate.isActiveProviderLocal() &&
                                    candidate.getActiveModel().isNotBlank()
                                )
                    }
                    ?: error("没有另外配置可用的用户 API 模型；Codex 订阅不能代替截图 API")
            }
            if (!apiConfig.isActiveProviderLocal()) {
                require(config.guiAllowRemoteScreenshots) {
                    "远程截图识别未授权；请在工具设置中明确开启"
                }
                if (fullScreen) {
                    require(config.guiAllowRemoteFullScreen) {
                        "远程完整屏幕上传未授权；请指定裁剪区域或单独开启完整屏幕"
                    }
                }
            }
            require(
                apiConfig.getActiveApiKey().isNotBlank() ||
                    (apiConfig.isActiveProviderLocal() && apiConfig.getActiveModel().isNotBlank())
            ) {
                "当前视觉 API 连接没有可用凭据；Codex 订阅不能代替用户自定义视觉 API"
            }
            val provider = ProviderRegistry.getActiveProvider(apiConfig.getActiveRuntimeProviderId())
            val response = provider.chat(
                request = visionRequest(prompt, screenshot, apiConfig.getActiveModel()),
                apiKey = apiConfig.getActiveApiKey(),
                baseUrl = apiConfig.getActiveBaseUrl()
            )
            return response.content?.trim().takeUnless { it.isNullOrBlank() }
                ?: error("视觉 API 没有返回内容")
        }

        val (source, result) = when (config.guiInferenceMode) {
            GuiInferenceMode.LOCAL_ONLY -> localAttempt()
            GuiInferenceMode.USER_API -> "api" to apiAttempt()
            GuiInferenceMode.LOCAL_FIRST -> {
                val local = runCatching { localAttempt() }
                if (local.isSuccess) {
                    local.getOrThrow()
                } else {
                    val remote = runCatching { apiAttempt() }
                    if (remote.isFailure) {
                        val localMessage = local.exceptionOrNull()?.message.orEmpty()
                        val remoteMessage = remote.exceptionOrNull()?.message.orEmpty()
                        error("本地视觉失败：$localMessage；API 回退失败：$remoteMessage")
                    }
                    "api_fallback" to remote.getOrThrow()
                }
            }
        }
        val coordinateAdjustedResult = adjustVisionCoordinates(result, obj)
        val exposedResult = if (
            source != "api" &&
            source != "api_fallback" &&
            !config.isActiveProviderLocal() &&
            !config.guiAllowRemoteSemanticTree
        ) {
            sanitizeLocalVisionResultForRemote(coordinateAdjustedResult)
        } else {
            coordinateAdjustedResult
        }
        return GuiToolResponse(
            success = true,
            action = "vision_query",
            source = source,
            modelResult = exposedResult,
            imageWidth = screenshot.width,
            imageHeight = screenshot.height,
            imageSha256 = sha256(screenshot.base64Data),
            message = "截图在推理完成后已从工具作用域释放，未写入磁盘。"
        )
    }

    private fun visionRequest(
        prompt: String,
        screenshot: GuiScreenshot,
        model: String
    ): ChatRequest = ChatRequest(
        messages = listOf(
            ChatMessage(
                role = "system",
                content = """
                    你是 GUI 视觉定位器。只分析当前截图，不推测屏幕外内容。
                    优先返回严格 JSON：
                    {"summary":"界面摘要","targetFound":true,"x":123,"y":456,"confidence":0.92,"reason":"简短依据"}
                    如果目标不存在，targetFound=false，x/y 使用 null。坐标必须基于输入图片像素。
                """.trimIndent()
            ),
            ChatMessage(
                role = "user",
                content = prompt,
                images = listOf(
                    ChatImageAttachment(
                        mimeType = screenshot.mimeType,
                        base64Data = screenshot.base64Data,
                        fileName = "gui-screenshot.png",
                        width = screenshot.width,
                        height = screenshot.height,
                        sizeBytes = (screenshot.base64Data.length * 3L) / 4L
                    )
                )
            )
        ),
        model = model,
        temperature = 0.1,
        maxTokens = 800,
        stream = false
    )

    private suspend fun captureScreenshot(
        service: AndroidGuiAccessibilityService?,
        obj: JsonObject
    ): GuiScreenshot {
        val raw = if (service != null) {
            service.captureScreenshot()
        } else {
            captureRootScreenshot()
        }
        return if (hasCrop(obj)) cropScreenshot(raw, obj) else raw
    }

    private suspend fun captureRootScreenshot(): GuiScreenshot = withContext(Dispatchers.IO) {
        require(KeepShellPublic.checkRoot()) {
            "AccessibilityService 未启用且 Root 不可用，无法截图"
        }
        val dimensions = KeepShellPublic.doCmdSync("wm size | tail -n 1")
            .substringAfter(':', "")
            .trim()
            .split('x')
            .mapNotNull(String::toIntOrNull)
        val base64 = KeepShellPublic.doCmdSync(
            "screencap -p | base64 | tr -d '\\r\\n'"
        ).trim()
        require(base64.length in 64..MAX_SCREENSHOT_BASE64_CHARS) {
            "Root 截图为空或超过安全大小限制"
        }
        GuiScreenshot(
            mimeType = "image/png",
            base64Data = base64,
            width = dimensions.getOrNull(0) ?: 0,
            height = dimensions.getOrNull(1) ?: 0
        )
    }

    private fun cropScreenshot(screenshot: GuiScreenshot, obj: JsonObject): GuiScreenshot {
        val bytes = android.util.Base64.decode(screenshot.base64Data, android.util.Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("无法解码截图")
        val left = (obj.int("cropLeft") ?: 0).coerceIn(0, bitmap.width - 1)
        val top = (obj.int("cropTop") ?: 0).coerceIn(0, bitmap.height - 1)
        val right = (obj.int("cropRight") ?: bitmap.width).coerceIn(left + 1, bitmap.width)
        val bottom = (obj.int("cropBottom") ?: bitmap.height).coerceIn(top + 1, bitmap.height)
        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val output = ByteArrayOutputStream()
        check(cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            "无法编码裁剪截图"
        }
        val response = GuiScreenshot(
            mimeType = "image/png",
            base64Data = android.util.Base64.encodeToString(
                output.toByteArray(),
                android.util.Base64.NO_WRAP
            ),
            width = cropped.width,
            height = cropped.height
        )
        cropped.recycle()
        bitmap.recycle()
        return response
    }

    private suspend fun launchVerifiedNativeSearch(
        searchIntent: PhoneAgentSearchIntent,
        isolatedSession: PhoneAgentIsolatedDisplaySession?,
        service: AndroidGuiAccessibilityService?,
    ): Boolean {
        val uri = searchIntent.verifiedNativeSearchUri() ?: return false
        return when {
            isolatedSession != null -> isolatedSession.launchViewUri(searchIntent.packageName, uri)
            service != null -> service.launchViewUri(searchIntent.packageName, uri)
            KeepShellPublic.checkRoot() -> launchViewUriWithRoot(searchIntent.packageName, uri)
            else -> false
        }
    }

    private suspend fun launchViewUriWithRoot(packageName: String, uri: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!packageName.matches(Regex("[A-Za-z0-9._]+")) ||
                !uri.matches(Regex("[A-Za-z0-9:/?&=._%+~-]+"))
            ) {
                return@withContext false
            }
            val output = KeepShellPublic.doCmdSync(
                "su 2000 -c 'am start -W -a android.intent.action.VIEW " +
                    "-d \"$uri\" -p $packageName'",
            ).trim()
            !output.startsWith("Error:", ignoreCase = true) &&
                !output.contains("exception", ignoreCase = true) &&
                !output.contains("unable to resolve", ignoreCase = true) &&
                !output.contains("denied", ignoreCase = true)
        }

    private suspend fun verifySearchQueryVisible(
        query: String,
        service: AndroidGuiAccessibilityService?,
        isolatedSession: PhoneAgentIsolatedDisplaySession?,
        isolatedInfo: PhoneAgentIsolatedDisplayInfo?,
    ): Boolean {
        val verificationDeadline = android.os.SystemClock.uptimeMillis() + 4_000L
        var verified = false
        while (!verified && android.os.SystemClock.uptimeMillis() < verificationDeadline) {
            delay(500)
            val semanticVerified = service?.observe(
                maxNodes = 100,
                includeText = true,
                displayId = isolatedInfo?.displayId,
            )?.nodes.orEmpty().any { node ->
                listOf(node.text, node.contentDescription)
                    .filterNotNull()
                    .any { value -> value.contains(query, ignoreCase = true) }
            }
            val ocrVerified = if (!semanticVerified && isolatedSession != null) {
                runCatching { isolatedSession.captureScreenshot() }
                    .getOrNull()
                    ?.let { OnDeviceScreenOcr.recognize(it) }
                    .orEmpty()
                    .any { element -> element.text.contains(query, ignoreCase = true) }
            } else {
                false
            }
            verified = semanticVerified || ocrVerified
        }
        return verified
    }

    private suspend fun executeRootAction(
        action: String,
        obj: JsonObject
    ): Pair<Boolean, String> {
        require(KeepShellPublic.checkRoot()) {
            "AccessibilityService 未启用且 Root 不可用"
        }
        if (action == "input") {
            val text = obj.string("text") ?: error("input 缺少 text")
            if (hasRootSelector(obj)) {
                val focusArgs = buildJsonObject {
                    put("action", "ui_click_node")
                    copyString(obj, this, "selectorText", "text")
                    copyString(obj, this, "resourceId")
                    copyString(obj, this, "contentDescription", "contentDesc")
                    copyString(obj, this, "className")
                    copyInt(obj, this, "index")
                }
                val focusOutput = AndroidTool().execute(focusArgs.toString())
                if (
                    focusOutput.startsWith("Error:", ignoreCase = true) ||
                    "未找到" in focusOutput ||
                    "\"success\":false" in focusOutput.replace(" ", "")
                ) {
                    return false to focusOutput
                }
            }
            val inputArgs = buildJsonObject {
                put("action", "ui_text")
                put("text", text)
            }
            val inputOutput = AndroidTool().execute(inputArgs.toString())
            return (!inputOutput.startsWith("Error:", ignoreCase = true)) to inputOutput
        }
        val mapped = buildJsonObject {
            when (action) {
                "click", "long_click" -> {
                    val hasCoordinates = obj.int("x") != null && obj.int("y") != null
                    put(
                        "action",
                        when {
                            action == "long_click" && hasCoordinates -> "ui_long_press"
                            action == "long_click" -> "ui_long_press_node"
                            hasCoordinates -> "ui_tap"
                            else -> "ui_click_node"
                        }
                    )
                }
                "scroll" -> put("action", "ui_scroll_percent")
                "tap" -> put("action", "ui_tap")
                "swipe" -> put("action", "ui_swipe")
                "key" -> put("action", "ui_keyevent")
                "launch" -> put("action", "app_start")
                else -> error("Root 回退不支持 action=$action")
            }
            copyString(obj, this, "selectorText", "text")
            copyString(obj, this, "resourceId")
            copyString(obj, this, "contentDescription", "contentDesc")
            copyString(obj, this, "className")
            copyString(obj, this, "packageName")
            copyInt(obj, this, "index")
            copyInt(obj, this, "x")
            copyInt(obj, this, "y")
            copyInt(obj, this, "startX", "x")
            copyInt(obj, this, "startY", "y")
            copyInt(obj, this, "endX", "x2")
            copyInt(obj, this, "endY", "y2")
            copyInt(obj, this, "durationMs", "durationMs")
            copyInt(obj, this, "durationMs", "longPressDurationMs")
            if (action == "key") put("keyCode", obj.string("key") ?: "")
            if (action == "scroll") {
                val direction = obj.string("direction")?.lowercase() ?: "down"
                val points = when (direction) {
                    "up", "backward" -> listOf(0.5, 0.25, 0.5, 0.75)
                    "left" -> listOf(0.25, 0.5, 0.75, 0.5)
                    "right" -> listOf(0.75, 0.5, 0.25, 0.5)
                    else -> listOf(0.5, 0.75, 0.5, 0.25)
                }
                put("xPercent", points[0])
                put("yPercent", points[1])
                put("x2Percent", points[2])
                put("y2Percent", points[3])
                put("durationMs", obj.int("durationMs") ?: 350)
            }
        }
        val output = AndroidTool().execute(mapped.toString())
        return (!output.startsWith("Error:", ignoreCase = true)) to output
    }

    private suspend fun executeShellUidTap(x: Int, y: Int): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val output = KeepShellPublic.doCmdSync(
                "su 2000 -c 'input tap $x $y'",
            ).trim()
            val failed = output.startsWith("Error:", ignoreCase = true) ||
                output.contains("not found", ignoreCase = true) ||
                output.contains("denied", ignoreCase = true) ||
                output.contains("exception", ignoreCase = true)
            (!failed) to if (failed) output else "Shell UID 点击命令已执行"
        }

    private suspend fun readForegroundActivityClass(expectedPackage: String?): String? =
        withContext(Dispatchers.IO) {
            if (expectedPackage.isNullOrBlank() || !KeepShellPublic.checkRoot()) {
                return@withContext null
            }
            val output = runCatching {
                KeepShellPublic.doCmdSync(
                    "dumpsys activity activities | grep -m 1 topResumedActivity",
                )
            }.getOrNull().orEmpty()
            parseForegroundActivityClass(output, expectedPackage)
        }

    private suspend fun inputTextThroughAutomationIme(
        text: String,
        expectedPackage: String?,
    ): Boolean {
        if (
            text.isEmpty() ||
            expectedPackage.isNullOrBlank()
        ) {
            return false
        }
        val previousIme = withContext(Dispatchers.IO) {
            KeepShellPublic.doCmdSync("settings get secure default_input_method").trim()
        }
        if (!INPUT_METHOD_COMPONENT.matches(previousIme)) return false
        val enabledBefore = withContext(Dispatchers.IO) {
            KeepShellPublic.doCmdSync("ime list -s")
                .lineSequence()
                .map(String::trim)
                .any { it == AUTOMATION_INPUT_METHOD_COMPONENT }
        }
        return try {
            val selected = withContext(Dispatchers.IO) {
                val enableOutput = KeepShellPublic.doCmdSync(
                    "ime enable $AUTOMATION_INPUT_METHOD_COMPONENT",
                )
                val selectOutput = KeepShellPublic.doCmdSync(
                    "ime set $AUTOMATION_INPUT_METHOD_COMPONENT",
                )
                shellCommandAccepted(enableOutput) && shellCommandAccepted(selectOutput)
            }
            if (!selected) {
                false
            } else {
                var committed = false
                for (attempt in 0 until AUTOMATION_IME_READY_ATTEMPTS) {
                    delay(AUTOMATION_IME_READY_INTERVAL_MILLIS)
                    if (
                        MurongAutomationInputMethodService.commitExactText(
                            text,
                            expectedPackage,
                        )
                    ) {
                        committed = true
                        break
                    }
                }
                if (committed) delay(AUTOMATION_IME_COMMIT_SETTLE_MILLIS)
                committed
            }
        } finally {
            withContext(Dispatchers.IO) {
                KeepShellPublic.doCmdSync("ime set $previousIme")
                if (!enabledBefore) {
                    KeepShellPublic.doCmdSync(
                        "ime disable $AUTOMATION_INPUT_METHOD_COMPONENT",
                    )
                }
            }
        }
    }

    private fun shellCommandAccepted(output: String): Boolean =
        !output.contains("error", ignoreCase = true) &&
            !output.contains("exception", ignoreCase = true) &&
            !output.contains("denied", ignoreCase = true) &&
            !output.contains("unknown", ignoreCase = true)

    private suspend fun launchApplication(
        service: AndroidGuiAccessibilityService?,
        obj: JsonObject
    ): GuiToolResponse {
        val requestedPackage = obj.string("packageName") ?: error("launch 缺少 packageName")
        val hint = listOfNotNull(
            obj.string("prompt"),
            obj.string("contentDescription"),
            obj.string("selectorText")
        ).joinToString(" ")
        val candidates = buildList {
            if ("抖音" in hint) add(DOUYIN_PACKAGE)
            add(requestedPackage)
        }.distinct()

        var launchWasSubmitted = false
        for (packageName in candidates) {
            val accessibilityLaunch = service?.packageManager
                ?.getLaunchIntentForPackage(packageName)
                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { intent ->
                    runCatching {
                        service.startActivity(intent)
                        true
                    }.getOrDefault(false)
                } == true
            launchWasSubmitted = launchWasSubmitted || accessibilityLaunch
            if (accessibilityLaunch) {
                if (waitForForegroundPackage(service, packageName)) {
                    return GuiToolResponse(
                        success = true,
                        action = "launch",
                        source = "android",
                        message = "已验证 $packageName 进入前台。"
                    )
                }
            }
            if (KeepShellPublic.checkRoot()) {
                val candidateArgs = JsonObject(
                    obj.toMutableMap().apply {
                        put("packageName", kotlinx.serialization.json.JsonPrimitive(packageName))
                    }
                )
                val (launched, output) = executeRootAction("launch", candidateArgs)
                launchWasSubmitted = launchWasSubmitted || launched
                if (launched && !output.contains("\"success\":false")) {
                    if (waitForForegroundPackage(service, packageName)) {
                        return GuiToolResponse(
                            success = true,
                            action = "launch",
                            source = "root",
                            message = "已通过 Root 启动并验证 $packageName 进入前台。"
                        )
                    }
                }
            }
        }
        return GuiToolResponse(
            success = false,
            action = "launch",
            source = if (service != null) "android/root" else "root",
            error = if (launchWasSubmitted) {
                "系统已接收启动请求，但目标应用没有进入前台：${candidates.joinToString()}"
            } else {
                "找不到可启动的应用包：${candidates.joinToString()}"
            }
        )
    }

    private suspend fun waitForForegroundPackage(
        service: AndroidGuiAccessibilityService?,
        packageName: String,
    ): Boolean {
        repeat(FOREGROUND_VERIFY_ATTEMPTS) {
            val accessibilityPackage = runCatching {
                service?.observe(maxNodes = 1, includeText = false)?.application
            }.getOrNull()
            if (foregroundPackageMatches(accessibilityPackage, packageName)) return true

            if (KeepShellPublic.checkRoot()) {
                val focus = AndroidTool().execute(
                    buildJsonObject { put("action", "ui_current_focus") }.toString(),
                )
                if (
                    focus.contains("$packageName/") ||
                    focus.contains("\"packageName\":\"$packageName\"") ||
                    focus.contains("\"packageName\": \"$packageName\"")
                ) {
                    return true
                }
            }
            delay(FOREGROUND_VERIFY_INTERVAL_MILLIS)
        }
        return false
    }

    private fun actionResult(action: String, success: Boolean, source: String) =
        GuiToolResponse(
            success = success,
            action = action,
            source = source,
            message = if (success) "操作已提交；请重新 observe 验证界面变化。" else null,
            error = if (!success) "目标不可用、已过期或系统拒绝了操作" else null
        )

    private fun hasCrop(obj: JsonObject): Boolean =
        obj.int("cropLeft") != null && obj.int("cropTop") != null &&
            obj.int("cropRight") != null && obj.int("cropBottom") != null

    private fun hasRootSelector(obj: JsonObject): Boolean =
        listOf("selectorText", "resourceId", "contentDescription", "className")
            .any { !obj.string(it).isNullOrBlank() }

    private fun sha256(base64Data: String): String {
        val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeLocalVisionResultForRemote(result: String): String {
        val start = result.indexOf('{')
        val end = result.lastIndexOf('}')
        val parsed = if (start >= 0 && end > start) {
            runCatching {
                json.parseToJsonElement(result.substring(start, end + 1)) as? JsonObject
            }.getOrNull()
        } else {
            null
        }
        return buildJsonObject {
            put("privacyRedacted", true)
            put(
                "message",
                "本地视觉结果中的文字已按隐私设置隐藏；只返回定位坐标和置信度。"
            )
            listOf("targetFound", "x", "y", "confidence", "coordinates").forEach { key ->
                parsed?.get(key)?.let { put(key, it) }
            }
        }.toString()
    }

    private fun adjustVisionCoordinates(result: String, obj: JsonObject): String {
        if (!hasCrop(obj)) return result
        val start = result.indexOf('{')
        val end = result.lastIndexOf('}')
        if (start < 0 || end <= start) return result
        val parsed = runCatching {
            json.parseToJsonElement(result.substring(start, end + 1)) as? JsonObject
        }.getOrNull() ?: return result
        val left = obj.int("cropLeft") ?: 0
        val top = obj.int("cropTop") ?: 0
        return buildJsonObject {
            parsed.forEach { (key, value) -> put(key, value) }
            parsed["x"]?.jsonPrimitive?.intOrNull?.let { put("x", it + left) }
            parsed["y"]?.jsonPrimitive?.intOrNull?.let { put("y", it + top) }
            put("coordinates", "screen")
        }.toString()
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(name: String): Int? =
        this[name]?.jsonPrimitive?.intOrNull

    private fun copyString(
        source: JsonObject,
        target: kotlinx.serialization.json.JsonObjectBuilder,
        sourceName: String,
        targetName: String = sourceName
    ) {
        source.string(sourceName)?.let { target.put(targetName, it) }
    }

    private fun copyInt(
        source: JsonObject,
        target: kotlinx.serialization.json.JsonObjectBuilder,
        sourceName: String,
        targetName: String = sourceName
    ) {
        source.int(sourceName)?.let { target.put(targetName, it) }
    }

    companion object {
        private const val DEFAULT_MAX_NODES = 180
        private const val MAX_VISION_PROMPT_CHARS = 4_000
        private const val MAX_PHONE_TASK_CHARS = 8_000
        private const val MAX_SCREENSHOT_BASE64_CHARS = 24 * 1024 * 1024
        private const val PHONE_ACTION_SETTLE_MS = 700L
        private const val PHONE_SCREENSHOT_MAX_LONG_EDGE = 1_920
        private const val PHONE_SCREENSHOT_JPEG_QUALITY = 88
        private const val FOREGROUND_VERIFY_ATTEMPTS = 8
        private const val FOREGROUND_VERIFY_INTERVAL_MILLIS = 250L
        private const val AUTOMATION_INPUT_METHOD_COMPONENT =
            "com.murong.agent/.core.tool.MurongAutomationInputMethodService"
        private const val AUTOMATION_IME_READY_ATTEMPTS = 20
        private const val AUTOMATION_IME_READY_INTERVAL_MILLIS = 100L
        private const val AUTOMATION_IME_COMMIT_SETTLE_MILLIS = 300L
        private const val PHONE_INPUT_TAG = "MurongPhoneInput"
        private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
        private val INPUT_METHOD_COMPONENT = Regex("[A-Za-z0-9._]+/[A-Za-z0-9._]+")
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}

internal fun foregroundPackageMatches(observed: String?, requested: String): Boolean =
    observed?.trim() == requested.trim().takeIf(String::isNotBlank)

internal fun resolvePhoneAgentApplication(
    isolatedDisplayActive: Boolean,
    semanticObservation: GuiObservation?,
    isolatedPackage: String?,
    latestPhysicalPackage: String?,
): String? {
    val managedDisplayPackage = isolatedPackage?.trim()?.takeIf(String::isNotBlank)
    if (isolatedDisplayActive && managedDisplayPackage != null) {
        return managedDisplayPackage
    }
    val semanticPackage = semanticObservation
        ?.takeIf { it.success }
        ?.application
        ?.trim()
        ?.takeIf(String::isNotBlank)
    if (semanticPackage != null) return semanticPackage
    return if (isolatedDisplayActive) {
        null
    } else {
        latestPhysicalPackage?.trim()?.takeIf(String::isNotBlank)
    }
}

internal fun selectPhoneAgentSemanticObservation(
    isolatedDisplayActive: Boolean,
    semanticObservation: GuiObservation?,
    isolatedPackage: String?,
): GuiObservation? {
    val observation = semanticObservation?.takeIf { it.success } ?: return null
    if (!isolatedDisplayActive) return observation

    val expectedPackage = isolatedPackage?.trim()?.takeIf(String::isNotBlank)
    val observedPackage = observation.application?.trim()?.takeIf(String::isNotBlank)
    if (expectedPackage != null && observedPackage != expectedPackage) return null

    // A stale active-window root can occasionally be reported as a successful one-node tree.
    // It has no visible screen bounds and must not be supplied to the planner as real semantics.
    return observation.takeIf { candidate ->
        candidate.nodes.any { node ->
            node.visible && node.bounds.width > 0 && node.bounds.height > 0
        }
    }
}

internal fun shouldDirectLaunchPhoneAgentTarget(
    isolatedDisplayActive: Boolean,
    videoCallRequested: Boolean,
    mentionedPackage: String?,
): Boolean = !isolatedDisplayActive &&
    videoCallRequested &&
    !mentionedPackage.isNullOrBlank()

internal fun parseForegroundActivityClass(
    activityManagerOutput: String,
    expectedPackage: String,
): String? {
    val match = FOREGROUND_ACTIVITY_COMPONENT.find(activityManagerOutput) ?: return null
    val packageName = match.groupValues[1]
    if (packageName != expectedPackage) return null
    val className = match.groupValues[2]
    return if (className.startsWith('.')) packageName + className else className
}
