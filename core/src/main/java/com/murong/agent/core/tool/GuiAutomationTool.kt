package com.murong.agent.core.tool

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

/**
 * Unified Android implementation of the cross-platform `gui` tool.
 *
 * Semantic Accessibility data is preferred. Root/UIAutomator remains a compatibility fallback,
 * and visual inference is an explicit action with separate local/API privacy gates.
 */
class GuiAutomationTool(
    private val progressReporter: (String) -> Unit = {},
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
        val response = runCatching {
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
        }.getOrElse { error ->
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
        val controller = PhoneAgentController()
        val result = controller.run(
            request = request,
            config = config,
            onProgress = progressReporter,
            device = object : PhoneAgentDevice {
                override suspend fun observe(): PhoneAgentScreen {
                    val service = AndroidGuiAccessibilityService.connectedInstance() ?: initialService
                    val rawScreenshot = captureScreenshot(service, buildJsonObject { })
                    val screenshot = optimizePhoneAgentScreenshot(rawScreenshot)
                    val application = service?.observe(maxNodes = 1, includeText = false)?.application
                    return PhoneAgentScreen(
                        screenshot = screenshot,
                        application = application,
                        displayWidth = rawScreenshot.width,
                        displayHeight = rawScreenshot.height
                    )
                }

                override suspend fun execute(
                    command: PhoneAgentCommand,
                    screen: PhoneAgentScreen
                ): PhoneAgentDeviceResult {
                    val service = AndroidGuiAccessibilityService.connectedInstance() ?: initialService
                    val actionResponse = when (command.action.lowercase()) {
                        "launch" -> executeAction(
                            "launch",
                            buildJsonObject {
                                put("packageName", PhoneAgentApps.packageFor(command.app.orEmpty()))
                                put("prompt", command.app.orEmpty())
                            }
                        )
                        "tap" -> executeAction(
                            "tap",
                            pointArgs(command.x, command.y, screen)
                        )
                        "long press" -> executeAction(
                            "tap",
                            pointArgs(
                                command.x,
                                command.y,
                                screen,
                                durationMs = command.durationMs ?: 800
                            )
                        )
                        "double tap" -> {
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
                            val accessibilitySuccess = service?.setFocusedText(text) == true
                            val success = accessibilitySuccess || (
                                KeepShellPublic.checkRoot() && executeRootAction(
                                    "input",
                                    buildJsonObject { put("text", text) }
                                ).first
                                )
                            actionResult(
                                action = "input",
                                success = success,
                                source = if (accessibilitySuccess) "accessibility" else "root"
                            )
                        }
                        "swipe" -> executeAction(
                            "swipe",
                            swipeArgs(command, screen)
                        )
                        "back", "home" -> executeAction(
                            "key",
                            buildJsonObject { put("key", command.action.lowercase()) }
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
            val provider = ProviderRegistry.getActiveProvider(apiConfig.activeProviderId)
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
        private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}

internal fun foregroundPackageMatches(observed: String?, requested: String): Boolean =
    observed?.trim() == requested.trim().takeIf(String::isNotBlank)
