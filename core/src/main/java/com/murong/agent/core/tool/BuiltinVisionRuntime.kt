package com.murong.agent.core.tool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.murong.agent.common.shell.KeepShellPublic
import com.murong.agent.core.provider.ChatImageAttachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class BuiltinVisionStreamKind {
    RAW,
    CONTENT,
    REASONING
}

data class BuiltinVisionStreamChunk(
    val kind: BuiltinVisionStreamKind,
    val text: String
)

/**
 * org.json's optString() renders JSONObject.NULL as the literal text "null". Streaming
 * chat servers legitimately send `content: null` on role/tool deltas, so accept only a
 * real JSON string before exposing text to the conversation.
 */
internal fun streamedJsonText(value: Any?): String = (value as? String).orEmpty()

internal const val BUILTIN_MNN_CACHE_SCHEMA = "mnn-3.5.0-config-entry-v2"

enum class BuiltinLocalComputeBackend {
    AUTO,
    GPU,
    CPU
}

enum class BuiltinLocalCpuCorePolicy {
    PERFORMANCE_CLUSTER,
    ALL_CORES
}

data class BuiltinLocalCpuTopology(
    val allCoreIds: List<Int>,
    val performanceCoreIds: List<Int>,
    val exclusivePerformanceCoreIds: List<Int> =
        performanceCoreIds.takeLast(minOf(2, performanceCoreIds.size)),
) {
    val logicalCoreCount: Int
        get() = allCoreIds.size.coerceAtLeast(1)

    val recommendedThreadCount: Int
        get() = performanceCoreIds.size.coerceAtLeast(1)
}

data class BuiltinLocalRuntimeSettings(
    val backend: BuiltinLocalComputeBackend = BuiltinLocalComputeBackend.AUTO,
    val cpuCorePolicy: BuiltinLocalCpuCorePolicy =
        BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER,
    val cpuThreads: Int = detectBuiltinLocalCpuTopology().recommendedThreadCount,
    val forceMaxPerformance: Boolean = false
) {
    fun normalized(): BuiltinLocalRuntimeSettings {
        val topology = detectBuiltinLocalCpuTopology()
        val maxThreads = when (cpuCorePolicy) {
            BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER ->
                topology.recommendedThreadCount
            BuiltinLocalCpuCorePolicy.ALL_CORES ->
                topology.logicalCoreCount
        }
        return copy(cpuThreads = cpuThreads.coerceIn(1, maxThreads))
    }
}

internal class BuiltinLocalGenerationCancellation {
    private val cancelled = AtomicBoolean(false)
    private val cancellationAction = AtomicReference<(() -> Unit)?>(null)

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel(): Boolean {
        if (!cancelled.compareAndSet(false, true)) return false
        cancellationAction.getAndSet(null)?.invoke()
        return true
    }

    fun invokeOnCancel(action: () -> Unit) {
        if (cancelled.get()) {
            action()
            return
        }
        check(cancellationAction.compareAndSet(null, action)) {
            "本地模型取消回调已注册"
        }
        if (cancelled.get()) {
            cancellationAction.getAndSet(null)?.invoke()
        }
    }

    fun clearCancellationAction() {
        cancellationAction.set(null)
    }
}

/**
 * Keeps one token run back from the UI so a collapsed sampler can be stopped
 * before hundreds of token-id-zero exclamation marks are persisted.
 */
internal class BuiltinLocalTokenStreamGuard(
    private val emit: (String) -> Unit,
    private val repeatedTokenLimit: Int = DEFAULT_REPEATED_TOKEN_LIMIT
) {
    private var pendingTokenId: Int? = null
    private val pendingText = StringBuilder()
    private var repeatedTokenCount = 0

    var failureReason: String? = null
        private set

    init {
        require(repeatedTokenLimit > 1)
    }

    fun accept(text: String, tokenId: Int): Boolean {
        if (failureReason != null) return false
        if (text.isEmpty()) return true

        if (pendingTokenId != tokenId) {
            flushPending()
            pendingTokenId = tokenId
            repeatedTokenCount = 0
        }
        pendingText.append(text)
        repeatedTokenCount += 1

        if (
            repeatedTokenCount >= repeatedTokenLimit &&
            pendingText.any { character -> !character.isWhitespace() }
        ) {
            pendingText.setLength(0)
            failureReason =
                "本地模型输出异常：连续重复同一 Token（ID $tokenId），已自动停止推理。" +
                    "请使用自动/CPU 后端；如果仍然出现，请重新选择模型。"
            return false
        }
        return true
    }

    fun finish() {
        if (failureReason == null) flushPending()
    }

    private fun flushPending() {
        if (pendingText.isNotEmpty()) {
            emit(pendingText.toString())
            pendingText.setLength(0)
        }
    }

    private companion object {
        const val DEFAULT_REPEATED_TOKEN_LIMIT = 32
    }
}

object BuiltinVisionRuntime {
    private const val MAX_IMAGE_SIDE = 1280
    private const val MAX_NEW_TOKENS = 512
    private const val MAX_CHAT_NEW_TOKENS = 1_024
    private const val RUNTIME_SETTINGS_PREFERENCES = "builtin_vision_runtime"
    private const val RUNTIME_BACKEND_KEY = "compute_backend"
    private const val RUNTIME_CPU_CORE_POLICY_KEY = "cpu_core_policy"
    private const val RUNTIME_CPU_THREADS_KEY = "cpu_threads"
    private const val RUNTIME_FORCE_MAX_PERFORMANCE_KEY = "force_max_performance"

    private val mutex = Mutex()
    @Volatile private var appContext: Context? = null
    @Volatile private var nativeLoaded: Boolean? = null
    private var handle: Long = 0L
    private var mnnTier: BuiltinVisionTier? = null
    private var mnnSettingsSignature: String? = null
    private var mnnModelFingerprint: String? = null
    private var liteEngine: Engine? = null
    private var liteTier: BuiltinVisionTier? = null
    private var liteSettingsSignature: String? = null
    private var llamaServerProcess: Process? = null
    private var llamaServerTier: BuiltinVisionTier? = null
    private var llamaServerSettingsSignature: String? = null
    private var llamaServerPort: Int = 0
    private val llamaServerLogs = ArrayDeque<String>()
    private val llamaClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()
    private val llamaHealthClient = llamaClient.newBuilder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
    private val activeGeneration =
        AtomicReference<BuiltinLocalGenerationCancellation?>(null)
    private val generationCancellationEpoch = AtomicLong(0L)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        ensureNativeLoaded()
    }

    fun isReady(modelId: String? = null): Boolean {
        val context = appContext ?: return false
        val descriptor = BuiltinVisionModels.installedModel(context, modelId) ?: return false
        return when (descriptor.engine) {
            BuiltinVisionEngine.MNN -> ensureNativeLoaded()
            BuiltinVisionEngine.LITERT_LM -> true
            BuiltinVisionEngine.LLAMA_CPP -> llamaServerExecutable(context).isFile
        }
    }

    fun activeModel(): BuiltinVisionModelDescriptor? =
        appContext?.let(BuiltinVisionModels::activeInstalledModel)

    fun model(modelId: String? = null): BuiltinVisionModelDescriptor? =
        appContext?.let { context -> BuiltinVisionModels.installedModel(context, modelId) }

    fun activeModelDisplayName(): String? = activeModel()?.displayName

    fun cpuTopology(): BuiltinLocalCpuTopology = detectBuiltinLocalCpuTopology()

    fun cancelActiveGeneration(): Boolean {
        generationCancellationEpoch.incrementAndGet()
        return activeGeneration.get()?.cancel() == true
    }

    fun runtimeSettings(context: Context): BuiltinLocalRuntimeSettings {
        val preferences = context.applicationContext.getSharedPreferences(
            RUNTIME_SETTINGS_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val defaultThreads = cpuTopology().recommendedThreadCount
        return BuiltinLocalRuntimeSettings(
            backend = preferences.getString(RUNTIME_BACKEND_KEY, null)
                ?.let { stored ->
                    BuiltinLocalComputeBackend.entries.firstOrNull { it.name == stored }
                }
                ?: BuiltinLocalComputeBackend.AUTO,
            cpuCorePolicy = preferences.getString(RUNTIME_CPU_CORE_POLICY_KEY, null)
                ?.let { stored ->
                    BuiltinLocalCpuCorePolicy.entries.firstOrNull { it.name == stored }
                }
                ?: BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER,
            cpuThreads = preferences.getInt(RUNTIME_CPU_THREADS_KEY, defaultThreads),
            forceMaxPerformance = preferences.getBoolean(
                RUNTIME_FORCE_MAX_PERFORMANCE_KEY,
                false
            )
        ).normalized()
    }

    fun updateRuntimeSettings(
        context: Context,
        settings: BuiltinLocalRuntimeSettings
    ): BuiltinLocalRuntimeSettings {
        val normalized = settings.normalized()
        context.applicationContext.getSharedPreferences(
            RUNTIME_SETTINGS_PREFERENCES,
            Context.MODE_PRIVATE
        ).edit()
            .putString(RUNTIME_BACKEND_KEY, normalized.backend.name)
            .putString(RUNTIME_CPU_CORE_POLICY_KEY, normalized.cpuCorePolicy.name)
            .putInt(RUNTIME_CPU_THREADS_KEY, normalized.cpuThreads)
            .putBoolean(
                RUNTIME_FORCE_MAX_PERFORMANCE_KEY,
                normalized.forceMaxPerformance
            )
            .apply()
        return normalized
    }

    suspend fun chat(
        prompt: String,
        image: ChatImageAttachment? = null,
        maxTokens: Int = MAX_CHAT_NEW_TOKENS,
        reasoningMode: String? = null,
        modelId: String? = null,
        onToken: ((BuiltinVisionStreamChunk) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val selected = model(modelId) ?: error("尚未安装或选择内置模型")
        require(image == null || selected.supportsVision) {
            "${selected.displayName} 是纯文本模型，不能读取图片；请切换到已安装的视觉模型"
        }
        val decodedBitmap = image?.let { attachment ->
            val decoded = Base64.decode(attachment.base64Data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                ?: error("无法解码聊天图片")
        }
        val scaled = decodedBitmap?.scaleDown(MAX_IMAGE_SIDE)
        try {
            generate(
                prompt = prompt,
                bitmap = scaled,
                maxTokens = maxTokens.coerceIn(64, MAX_CHAT_NEW_TOKENS),
                reasoningMode = reasoningMode,
                descriptorOverride = selected,
                onToken = onToken
            )
        } finally {
            if (scaled != null && scaled !== decodedBitmap) scaled.recycle()
            decodedBitmap?.recycle()
        }
    }

    suspend fun infer(prompt: String, screenshot: GuiScreenshot): String =
        withContext(Dispatchers.IO) {
            val decoded = Base64.decode(screenshot.base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                ?: error("无法解码当前截图")
            val scaled = bitmap.scaleDown(MAX_IMAGE_SIDE)
            try {
                val instruction = """
                    你是 Murong 的 GUI 视觉定位器。只分析当前截图，不推测屏幕外内容。
                    优先返回严格 JSON：
                    {"summary":"界面摘要","targetFound":true,"x":123,"y":456,"confidence":0.92,"reason":"简短依据"}
                    目标不存在时 targetFound=false，x 和 y 为 null。坐标必须基于输入图片像素。

                    用户任务：
                    $prompt
                """.trimIndent()
                restoreCoordinatesToOriginalImage(
                    result = generate(
                        instruction,
                        scaled,
                        MAX_NEW_TOKENS,
                        requireVisionModel = true
                    ),
                    originalWidth = bitmap.width,
                    originalHeight = bitmap.height,
                    inferenceWidth = scaled.width,
                    inferenceHeight = scaled.height
                )
            } finally {
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        }

    private suspend fun generate(
        prompt: String,
        bitmap: Bitmap?,
        maxTokens: Int,
        reasoningMode: String? = null,
        requireVisionModel: Boolean = false,
        descriptorOverride: BuiltinVisionModelDescriptor? = null,
        onToken: ((BuiltinVisionStreamChunk) -> Unit)? = null
    ): String {
        val requestCancellationEpoch = generationCancellationEpoch.get()
        mutex.lock()
        val requestContext = currentCoroutineContext()
        if (
            requestContext[Job]?.isActive == false ||
            requestCancellationEpoch != generationCancellationEpoch.get()
        ) {
            mutex.unlock()
            throw CancellationException("本地模型请求已在排队期间取消")
        }
        val generationContext = requestContext
        val generationJob = generationContext[Job]
        val generationCancellation = BuiltinLocalGenerationCancellation()
        activeGeneration.set(generationCancellation)
        try {
            val context = appContext ?: error("内置模型运行时尚未初始化")
            val runtimeSettings = runtimeSettings(context)
            val descriptor = descriptorOverride ?: if (requireVisionModel) {
                BuiltinVisionModels.installedVisionModel(context)
            } else {
                BuiltinVisionModels.activeInstalledModel(context)
            }
                ?: error("尚未安装或选择内置模型，请先在工具页的本地模型中心安装")
            require(bitmap == null || descriptor.supportsVision) {
                "${descriptor.displayName} 不支持图片输入"
            }
            val resolvedReasoningMode = descriptor.resolveReasoningMode(reasoningMode)
            val enableThinking = resolvedReasoningMode == "on"
            val performanceLease = if (runtimeSettings.forceMaxPerformance) {
                BuiltinLocalPerformanceController.acquire(
                    runtimeSettings.cpuCorePolicy,
                    runtimeSettings.cpuThreads,
                )
            } else {
                null
            }
            generationCancellation.invokeOnCancel {
                performanceLease?.restore()
            }
            if (generationCancellation.isCancelled) {
                throw CancellationException("用户已终止本地模型推理")
            }
            val result = try {
                when (descriptor.engine) {
                    BuiltinVisionEngine.MNN -> {
                        check(ensureNativeLoaded()) { "设备不支持内置 MNN 模型运行时" }
                        ensureMnnSession(context, descriptor, runtimeSettings)
                        val guardedStream = BuiltinLocalTokenStreamGuard(
                            emit = { text ->
                                onToken?.invoke(
                                    BuiltinVisionStreamChunk(
                                        BuiltinVisionStreamKind.RAW,
                                        text
                                    )
                                )
                            }
                        )
                        var callbackFailure: Throwable? = null
                        val nativeResult = BuiltinVisionNative.nativeInfer(
                            handle = handle,
                            prompt = prompt,
                            bgr = bitmap?.toBgrBytes() ?: byteArrayOf(),
                            width = bitmap?.width ?: 0,
                            height = bitmap?.height ?: 0,
                            maxTokens = maxTokens,
                            enableThinking = enableThinking,
                            listener = BuiltinVisionTokenListener { text, tokenId ->
                                if (
                                    generationCancellation.isCancelled ||
                                    generationJob?.isActive == false
                                ) {
                                    false
                                } else {
                                    try {
                                        guardedStream.accept(text, tokenId)
                                    } catch (error: Throwable) {
                                        callbackFailure = error
                                        false
                                    }
                                }
                            }
                        )
                        if (generationCancellation.isCancelled) {
                            throw CancellationException("用户已终止本地模型推理")
                        }
                        generationContext.ensureActive()
                        callbackFailure?.let { throw it }
                        guardedStream.failureReason?.let { error(it) }
                        guardedStream.finish()
                        nativeResult
                    }
                    BuiltinVisionEngine.LITERT_LM ->
                        inferWithLiteRt(
                            context,
                            descriptor,
                            prompt,
                            bitmap,
                            enableThinking,
                            runtimeSettings,
                            onToken
                        )
                    BuiltinVisionEngine.LLAMA_CPP ->
                        inferWithLlamaCpp(
                            context = context,
                            descriptor = descriptor,
                            instruction = prompt,
                            bitmap = bitmap,
                            maxTokens = maxTokens,
                            enableThinking = enableThinking,
                            settings = runtimeSettings,
                            cancellation = generationCancellation,
                            onToken = onToken
                        )
                }
            } finally {
                performanceLease?.restore()
                generationCancellation.clearCancellationAction()
            }
            return result.trim().takeUnless { it.isBlank() }
                ?: error("内置模型没有返回内容")
        } finally {
            activeGeneration.compareAndSet(generationCancellation, null)
            mutex.unlock()
        }
    }

    suspend fun release() {
        mutex.withLock {
            if (handle != 0L) {
                BuiltinVisionNative.nativeDestroy(handle)
                handle = 0L
                mnnTier = null
                mnnSettingsSignature = null
                mnnModelFingerprint = null
            }
            liteEngine?.close()
            liteEngine = null
            liteTier = null
            liteSettingsSignature = null
            stopLlamaServer()
        }
    }

    private fun ensureMnnSession(
        context: Context,
        descriptor: BuiltinVisionModelDescriptor,
        settings: BuiltinLocalRuntimeSettings
    ) {
        val modelDirectory = BuiltinVisionModels.modelDirectory(context, descriptor.tier)
        val modelFingerprint = mnnModelCacheFingerprint(modelDirectory, descriptor)
        val settingsSignature = settings.signature()
        if (
            handle != 0L &&
            mnnTier == descriptor.tier &&
            mnnSettingsSignature == settingsSignature &&
            mnnModelFingerprint == modelFingerprint
        ) {
            return
        }
        if (handle != 0L) {
            BuiltinVisionNative.nativeDestroy(handle)
            handle = 0L
            mnnTier = null
            mnnSettingsSignature = null
            mnnModelFingerprint = null
        }
        liteEngine?.close()
        liteEngine = null
        liteTier = null
        liteSettingsSignature = null
        stopLlamaServer()
        val configName = descriptor.configFileName ?: error("MNN 模型缺少配置文件名")
        val configPath = File(modelDirectory, configName).absolutePath
        var lastError: Throwable? = null
        for (backend in settings.mnnBackendOrder(descriptor)) {
            val cacheDirectory = prepareMnnCacheDirectory(
                context = context,
                descriptor = descriptor,
                backend = backend,
                modelFingerprint = modelFingerprint
            )
            val attempt = runCatching {
                BuiltinVisionNative.nativeCreate(
                    configPath,
                    mnnRuntimeConfig(
                        cacheDirectory,
                        backend,
                        settings.cpuThreads,
                        settings.cpuCorePolicy
                    )
                )
            }
            val candidate = attempt.getOrNull() ?: 0L
            if (candidate != 0L) {
                handle = candidate
                break
            }
            lastError = attempt.exceptionOrNull()
        }
        check(handle != 0L) {
            "MNN 无法用${settings.backend.displayName()}加载 ${descriptor.displayName}：" +
                lastError?.message.orEmpty()
        }
        mnnTier = descriptor.tier
        mnnSettingsSignature = settingsSignature
        mnnModelFingerprint = modelFingerprint
    }

    internal fun mnnRuntimeConfig(
        cacheDirectory: File,
        backend: String,
        cpuThreads: Int,
        cpuCorePolicy: BuiltinLocalCpuCorePolicy
    ): String =
        buildJsonObject {
            put("use_mmap", true)
            put("tmp_path", cacheDirectory.absolutePath)
            put("thread_num", cpuThreads)
            put(
                "power",
                if (cpuCorePolicy == BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER) {
                    "high"
                } else {
                    "normal"
                }
            )
            put("precision", "low")
            put("memory", "low")
            put("backend_type", backend)
            put("async", false)
            put("max_new_tokens", MAX_CHAT_NEW_TOKENS)
            put(
                "jinja",
                buildJsonObject {
                    put(
                        "context",
                        buildJsonObject {
                            put("enable_thinking", false)
                        }
                    )
                }
            )
        }.toString()

    private suspend fun inferWithLiteRt(
        context: Context,
        descriptor: BuiltinVisionModelDescriptor,
        instruction: String,
        bitmap: Bitmap?,
        enableThinking: Boolean,
        settings: BuiltinLocalRuntimeSettings,
        onToken: ((BuiltinVisionStreamChunk) -> Unit)?
    ): String {
        val settingsSignature = settings.signature()
        if (
            liteEngine == null ||
            liteTier != descriptor.tier ||
            liteSettingsSignature != settingsSignature
        ) {
            if (handle != 0L) {
                BuiltinVisionNative.nativeDestroy(handle)
                handle = 0L
                mnnTier = null
                mnnSettingsSignature = null
                mnnModelFingerprint = null
            }
            stopLlamaServer()
            liteEngine?.close()
            val modelDirectory = BuiltinVisionModels.modelDirectory(context, descriptor.tier)
            val modelFile = descriptor.files.singleOrNull()
                ?: error("LiteRT-LM 模型包结构不正确")
            val cacheDirectory = File(context.cacheDir, "litert-vlm/${descriptor.id}")
                .apply { mkdirs() }
            liteEngine = createLiteRtEngine(
                modelPath = File(modelDirectory, modelFile.name).absolutePath,
                cachePath = cacheDirectory.absolutePath,
                settings = settings
            )
            liteTier = descriptor.tier
            liteSettingsSignature = settingsSignature
        }
        val extraContext = mapOf<String, Any>("enable_thinking" to enableThinking)
        val conversation = liteEngine!!.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(
                    "You are Murong's private on-device assistant. Follow the user's instructions " +
                        "carefully and keep all content on this device."
                ),
                samplerConfig = SamplerConfig(
                    topK = 64,
                    topP = 0.95,
                    temperature = 1.0,
                    seed = 7
                ),
                extraContext = extraContext
            )
        )
        return conversation.use {
            val contents = if (bitmap == null) {
                Contents.of(Content.Text(instruction))
            } else {
                val png = ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "无法编码图片"
                    }
                    output.toByteArray()
                }
                Contents.of(Content.ImageBytes(png), Content.Text(instruction))
            }
            val output = StringBuilder()
            val reasoning = StringBuilder()
            val channelOutputs = mutableMapOf<String, String>()
            it.sendMessageAsync(Message.user(contents), extraContext).collect { response ->
                val chunk = response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { content -> content.text }
                val current = output.toString()
                val delta = if (chunk.startsWith(current)) {
                    chunk.substring(output.length)
                } else {
                    chunk
                }
                if (delta.isNotEmpty()) {
                    output.append(delta)
                    onToken?.invoke(
                        BuiltinVisionStreamChunk(BuiltinVisionStreamKind.CONTENT, delta)
                    )
                }
                response.channels.forEach { (channelName, channelText) ->
                    if (!channelName.isReasoningChannel() || channelText.isEmpty()) {
                        return@forEach
                    }
                    val previous = channelOutputs[channelName].orEmpty()
                    val channelDelta = if (channelText.startsWith(previous)) {
                        channelText.substring(previous.length)
                    } else {
                        channelText
                    }
                    channelOutputs[channelName] = if (channelText.startsWith(previous)) {
                        channelText
                    } else {
                        previous + channelText
                    }
                    if (channelDelta.isNotEmpty()) {
                        reasoning.append(channelDelta)
                        onToken?.invoke(
                            BuiltinVisionStreamChunk(
                                BuiltinVisionStreamKind.REASONING,
                                channelDelta
                            )
                        )
                    }
                }
            }
            buildString {
                if (reasoning.isNotEmpty()) {
                    append("<think>")
                    append(reasoning)
                    append("</think>")
                }
                append(output)
            }
        }
    }

    private fun String.isReasoningChannel(): Boolean {
        val normalized = lowercase()
        return normalized == "thought" ||
            normalized == "thinking" ||
            normalized == "reasoning" ||
            normalized == "analysis"
    }

    private fun createLiteRtEngine(
        modelPath: String,
        cachePath: String,
        settings: BuiltinLocalRuntimeSettings
    ): Engine {
        val attempts = settings.liteRtBackendOrder()
        var lastError: Throwable? = null
        attempts.forEach { (backend, visionBackend) ->
            val engine = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = visionBackend,
                    maxNumTokens = 4_096,
                    maxNumImages = 1,
                    cacheDir = cachePath
                )
            )
            val loaded = runCatching { engine.initialize() }
            if (loaded.isSuccess) return engine
            lastError = loaded.exceptionOrNull()
            engine.close()
        }
        throw IllegalStateException(
            "LiteRT-LM 无法用${settings.backend.displayName()}初始化：" +
                lastError?.message.orEmpty(),
            lastError
        )
    }

    private suspend fun inferWithLlamaCpp(
        context: Context,
        descriptor: BuiltinVisionModelDescriptor,
        instruction: String,
        bitmap: Bitmap?,
        maxTokens: Int,
        enableThinking: Boolean,
        settings: BuiltinLocalRuntimeSettings,
        cancellation: BuiltinLocalGenerationCancellation,
        onToken: ((BuiltinVisionStreamChunk) -> Unit)?
    ): String {
        // The pinned upstream Android llama.cpp archive currently ships CPU libraries only.
        // A global GPU preference must not make an otherwise runnable GGUF model fail: resolve
        // the effective backend per engine and keep this model on CPU until a GPU runtime is
        // actually packaged.
        ensureLlamaServer(context, descriptor, settings, cancellation)

        val messageContent: Any = if (bitmap == null) {
            instruction
        } else {
            val pngBase64 = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "无法编码本地模型图片"
                }
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put("url", "data:image/png;base64,$pngBase64")
                        )
                )
                .put(JSONObject().put("type", "text").put("text", instruction))
        }
        val payload = JSONObject()
            .put("model", descriptor.id)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", messageContent)
                )
            )
            .put("stream", true)
            .put("cache_prompt", true)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.3)
            .put("top_p", 0.9)
            .put(
                "chat_template_kwargs",
                JSONObject().put("enable_thinking", enableThinking)
            )
        val request = Request.Builder()
            .url("http://127.0.0.1:$llamaServerPort/v1/chat/completions")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = llamaClient.newCall(request)
        val content = StringBuilder()
        val reasoning = StringBuilder()
        try {
            call.execute().use { response ->
                check(response.isSuccessful) {
                    val detail = response.body?.string()?.take(1_000).orEmpty()
                    "llama.cpp 推理失败：HTTP ${response.code} $detail"
                }
                val source = response.body?.source() ?: error("llama.cpp 返回内容为空")
                while (!source.exhausted()) {
                    if (cancellation.isCancelled) {
                        call.cancel()
                        throw CancellationException("用户已终止本地模型推理")
                    }
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") continue
                    val delta = runCatching {
                        JSONObject(data)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                    }.getOrNull() ?: continue
                    val reasoningDelta = streamedJsonText(delta.opt("reasoning_content"))
                        .ifBlank { streamedJsonText(delta.opt("reasoning")) }
                    if (reasoningDelta.isNotEmpty()) {
                        reasoning.append(reasoningDelta)
                        onToken?.invoke(
                            BuiltinVisionStreamChunk(
                                BuiltinVisionStreamKind.REASONING,
                                reasoningDelta
                            )
                        )
                    }
                    val contentDelta = streamedJsonText(delta.opt("content"))
                    if (contentDelta.isNotEmpty()) {
                        content.append(contentDelta)
                        onToken?.invoke(
                            BuiltinVisionStreamChunk(
                                BuiltinVisionStreamKind.RAW,
                                contentDelta
                            )
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            if (cancellation.isCancelled && error !is CancellationException) {
                throw CancellationException("用户已终止本地模型推理").also {
                    it.initCause(error)
                }
            }
            throw error
        }
        return buildString {
            if (reasoning.isNotEmpty()) {
                append("<think>")
                append(reasoning)
                append("</think>")
            }
            append(content)
        }
    }

    private suspend fun ensureLlamaServer(
        context: Context,
        descriptor: BuiltinVisionModelDescriptor,
        settings: BuiltinLocalRuntimeSettings,
        cancellation: BuiltinLocalGenerationCancellation
    ) {
        val settingsSignature = settings.llamaCppSignature()
        if (
            llamaServerProcess?.isAlive == true &&
            llamaServerTier == descriptor.tier &&
            llamaServerSettingsSignature == settingsSignature &&
            llamaServerPort > 0
        ) {
            return
        }

        stopLlamaServer()
        if (handle != 0L) {
            BuiltinVisionNative.nativeDestroy(handle)
            handle = 0L
            mnnTier = null
            mnnSettingsSignature = null
            mnnModelFingerprint = null
        }
        liteEngine?.close()
        liteEngine = null
        liteTier = null
        liteSettingsSignature = null

        val executable = llamaServerExecutable(context)
        check(executable.isFile) {
            "APK 缺少 llama.cpp Android 运行时，请重新安装完整 Release 包"
        }
        val modelDirectory = BuiltinVisionModels.modelDirectory(context, descriptor.tier)
        val modelName = descriptor.ggufModelFileName
            ?: error("${descriptor.displayName} 缺少 GGUF 主模型")
        val modelFile = File(modelDirectory, modelName)
        check(modelFile.isFile) { "GGUF 主模型文件不存在：$modelName" }
        val port = ServerSocket(0).use { it.localPort }
        val command = mutableListOf(
            executable.absolutePath,
            "--model",
            modelFile.absolutePath
        )
        descriptor.ggufProjectorFileName?.let { projectorName ->
            val projector = File(modelDirectory, projectorName)
            check(projector.isFile) { "视觉投影文件不存在：$projectorName" }
            command += listOf("--mmproj", projector.absolutePath)
        }
        command += listOf(
            "--host",
            "127.0.0.1",
            "--port",
            port.toString(),
            "--ctx-size",
            LLAMA_CONTEXT_TOKENS.toString(),
            "--threads",
            settings.cpuThreads.toString(),
            "--threads-batch",
            settings.cpuThreads.toString(),
            "--parallel",
            "1",
            "--n-gpu-layers",
            "0",
            "--no-webui"
        )
        val nativeDirectory = context.applicationInfo.nativeLibraryDir
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = nativeDirectory
            }
            .start()
        synchronized(llamaServerLogs) {
            llamaServerLogs.clear()
        }
        Thread(
            {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach(::recordLlamaServerLog)
                    }
                }
            },
            "murong-llama-server-log"
        ).apply {
            isDaemon = true
            start()
        }
        llamaServerProcess = process
        llamaServerTier = descriptor.tier
        llamaServerSettingsSignature = settingsSignature
        llamaServerPort = port

        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
        var lastHealthError: Throwable? = null
        try {
            while (System.nanoTime() < deadline) {
                if (cancellation.isCancelled) {
                    stopLlamaServer()
                    throw CancellationException("用户已终止本地模型加载")
                }
                currentCoroutineContext().ensureActive()
                if (!process.isAlive) {
                    val logs = recentLlamaServerLogs()
                    stopLlamaServer()
                    error("llama.cpp 加载 ${descriptor.displayName} 失败：$logs")
                }
                val healthy = runCatching {
                    llamaHealthClient.newCall(
                        Request.Builder()
                            .url("http://127.0.0.1:$port/health")
                            .get()
                            .build()
                    ).execute().use { response -> response.isSuccessful }
                }.onFailure { lastHealthError = it }.getOrDefault(false)
                if (healthy) return
                Thread.sleep(250)
            }
        } catch (cancelled: CancellationException) {
            stopLlamaServer()
            throw cancelled
        }
        val detail = recentLlamaServerLogs().ifBlank {
            lastHealthError?.message.orEmpty()
        }
        stopLlamaServer()
        error("llama.cpp 加载 ${descriptor.displayName} 超时：$detail")
    }

    private fun recordLlamaServerLog(line: String) {
        synchronized(llamaServerLogs) {
            if (llamaServerLogs.size >= LLAMA_LOG_LINE_LIMIT) {
                llamaServerLogs.removeFirst()
            }
            llamaServerLogs.addLast(line.take(1_000))
        }
    }

    private fun recentLlamaServerLogs(): String =
        synchronized(llamaServerLogs) {
            llamaServerLogs.toList().takeLast(8).joinToString(" | ").take(2_000)
        }

    private fun stopLlamaServer() {
        llamaServerProcess?.let { process ->
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
        llamaServerProcess = null
        llamaServerTier = null
        llamaServerSettingsSignature = null
        llamaServerPort = 0
    }

    private fun llamaServerExecutable(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libmurong_llama_server.so")

    private fun ensureNativeLoaded(): Boolean {
        nativeLoaded?.let { return it }
        synchronized(this) {
            nativeLoaded?.let { return it }
            val loaded = runCatching {
                System.loadLibrary("murong_vlm")
            }.isSuccess
            nativeLoaded = loaded
            return loaded
        }
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val LLAMA_CONTEXT_TOKENS = 4_096
    private const val LLAMA_LOG_LINE_LIMIT = 80

    private fun Bitmap.scaleDown(maxSide: Int): Bitmap {
        val largest = maxOf(width, height)
        if (largest <= maxSide) return this
        val scale = maxSide.toFloat() / largest.toFloat()
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun Bitmap.toBgrBytes(): ByteArray {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val bgr = ByteArray(pixels.size * 3)
        var index = 0
        pixels.forEach { pixel ->
            bgr[index++] = (pixel and 0xFF).toByte()
            bgr[index++] = (pixel shr 8 and 0xFF).toByte()
            bgr[index++] = (pixel shr 16 and 0xFF).toByte()
        }
        return bgr
    }

    private fun restoreCoordinatesToOriginalImage(
        result: String,
        originalWidth: Int,
        originalHeight: Int,
        inferenceWidth: Int,
        inferenceHeight: Int
    ): String {
        if (originalWidth == inferenceWidth && originalHeight == inferenceHeight) return result
        val start = result.indexOf('{')
        val end = result.lastIndexOf('}')
        if (start < 0 || end <= start) return result
        val parsed = runCatching {
            Json.parseToJsonElement(result.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return result
        val values = parsed.toMutableMap()
        listOf(
            Triple("x", originalWidth, inferenceWidth),
            Triple("y", originalHeight, inferenceHeight)
        ).forEach { (key, original, inference) ->
            val coordinate = parsed[key]?.jsonPrimitive?.doubleOrNull ?: return@forEach
            if (inference > 0) {
                values[key] = JsonPrimitive(
                    (coordinate * original.toDouble() / inference.toDouble()).roundToInt()
                )
            }
        }
        return JsonObject(values).toString()
    }
}

internal fun mnnModelCacheFingerprint(
    modelDirectory: File,
    descriptor: BuiltinVisionModelDescriptor
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(BUILTIN_MNN_CACHE_SCHEMA.toByteArray(Charsets.UTF_8))
    listOfNotNull(
        descriptor.configFileName,
        "llm_config.json",
        "llm.mnn"
    ).distinct().forEach { name ->
        val file = File(modelDirectory, name)
        require(file.isFile) { "MNN 缓存指纹文件缺失：$name" }
        digest.update(0.toByte())
        digest.update(name.toByteArray(Charsets.UTF_8))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }
    val bytes = digest.digest()
    val hex = CharArray(bytes.size * 2)
    val alphabet = "0123456789abcdef"
    bytes.forEachIndexed { index, value ->
        val unsigned = value.toInt() and 0xFF
        hex[index * 2] = alphabet[unsigned ushr 4]
        hex[index * 2 + 1] = alphabet[unsigned and 0x0F]
    }
    return hex.concatToString().take(20)
}

private fun prepareMnnCacheDirectory(
    context: Context,
    descriptor: BuiltinVisionModelDescriptor,
    backend: String,
    modelFingerprint: String
): File {
    val cacheRoot = context.cacheDir.canonicalFile

    // Builds before the config-entry fix used this unversioned directory.
    // Its sync marker can make MNN trust partially stale mmap shards.
    val legacyDirectory = File(cacheRoot, "mnn-vlm/${descriptor.id}")
    deleteCacheDirectoryIfSafe(cacheRoot, legacyDirectory)

    val backendRoot = File(
        cacheRoot,
        "mnn-vlm/$BUILTIN_MNN_CACHE_SCHEMA/${descriptor.id}/$backend"
    ).canonicalFile
    check(backendRoot.mkdirs() || backendRoot.isDirectory) {
        "无法创建 MNN 缓存目录：${backendRoot.absolutePath}"
    }
    backendRoot.listFiles().orEmpty()
        .filter { it.name != modelFingerprint }
        .forEach { deleteCacheDirectoryIfSafe(cacheRoot, it) }

    val target = File(backendRoot, modelFingerprint).canonicalFile
    check(target.mkdirs() || target.isDirectory) {
        "无法创建 MNN 模型缓存：${target.absolutePath}"
    }
    return target
}

private fun deleteCacheDirectoryIfSafe(cacheRoot: File, target: File) {
    val resolved = runCatching { target.canonicalFile }.getOrNull() ?: return
    val rootPath = cacheRoot.canonicalPath.trimEnd(File.separatorChar) + File.separator
    if (
        resolved.exists() &&
        resolved.canonicalPath.startsWith(rootPath) &&
        resolved != cacheRoot
    ) {
        resolved.deleteRecursively()
    }
}

private fun BuiltinLocalRuntimeSettings.signature(): String =
    "${backend.name}:${cpuCorePolicy.name}:$cpuThreads"

/**
 * GGUF models use the pinned upstream Android llama.cpp archive, which currently contains only
 * CPU backends. Keep the effective signature honest so selecting a global GPU preference neither
 * restarts the server unnecessarily nor turns a supported model into a runtime error.
 */
internal fun BuiltinLocalRuntimeSettings.llamaCppSignature(): String =
    "${BuiltinLocalComputeBackend.CPU.name}:${cpuCorePolicy.name}:$cpuThreads"

internal fun BuiltinLocalRuntimeSettings.mnnBackendOrder(
    descriptor: BuiltinVisionModelDescriptor
): List<String> =
    when (backend) {
        // Qwen3.5 uses hybrid LinearAttention. MNN's OpenCL implementation can
        // load successfully on some Adreno drivers and still return collapsed
        // logits, so automatic mode must use the verified CPU path. Users may
        // still explicitly select GPU for devices whose driver is known-good.
        BuiltinLocalComputeBackend.AUTO ->
            if (descriptor.id.startsWith("qwen3.5-")) {
                listOf("cpu")
            } else {
                listOf("opencl", "cpu")
            }
        BuiltinLocalComputeBackend.GPU -> listOf("opencl")
        BuiltinLocalComputeBackend.CPU -> listOf("cpu")
    }

private fun BuiltinLocalRuntimeSettings.liteRtBackendOrder(): List<Pair<Backend, Backend>> =
    when (backend) {
        BuiltinLocalComputeBackend.AUTO -> listOf(
            Backend.GPU() to Backend.GPU(),
            Backend.CPU() to Backend.CPU()
        )
        BuiltinLocalComputeBackend.GPU -> listOf(Backend.GPU() to Backend.GPU())
        BuiltinLocalComputeBackend.CPU -> listOf(Backend.CPU() to Backend.CPU())
    }

private fun BuiltinLocalComputeBackend.displayName(): String =
    when (this) {
        BuiltinLocalComputeBackend.AUTO -> "自动选择的 GPU 或 CPU 后端"
        BuiltinLocalComputeBackend.GPU -> "GPU 后端"
        BuiltinLocalComputeBackend.CPU -> "CPU 后端"
    }

private val detectedBuiltinLocalCpuTopology: BuiltinLocalCpuTopology by lazy {
    val fallbackCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val fallbackIds = (0 until fallbackCount).toList()
    val policyRoot = File("/sys/devices/system/cpu/cpufreq")
    val coreMaximumFrequencies = linkedMapOf<Int, Long>()
    policyRoot.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name.matches(Regex("policy\\d+")) }
        .forEach { policy ->
            val related = runCatching {
                File(policy, "related_cpus").readText()
            }.getOrNull().orEmpty()
            val coreIds = related
                .split(Regex("\\s+"))
                .mapNotNull(String::toIntOrNull)
            val maximumFrequency = sequenceOf(
                File(policy, "cpuinfo_max_freq"),
                File(policy, "scaling_max_freq")
            ).mapNotNull { file ->
                runCatching { file.readText().trim().toLong() }.getOrNull()
            }.firstOrNull() ?: return@forEach
            coreIds.forEach { coreId ->
                coreMaximumFrequencies[coreId] = maximumFrequency
            }
        }
    val allIds = coreMaximumFrequencies.keys.sorted().takeIf { it.isNotEmpty() } ?: fallbackIds
    val performanceCount = ((allIds.size + 1) / 2).coerceAtLeast(1)
    val performanceIds = if (coreMaximumFrequencies.isNotEmpty()) {
        allIds.sortedWith(
            compareByDescending<Int> { coreMaximumFrequencies[it] ?: 0L }
                .thenByDescending { it }
        ).take(performanceCount).sorted()
    } else {
        allIds.takeLast(performanceCount)
    }
    val maximumFrequency = coreMaximumFrequencies.values.maxOrNull()
    val exclusivePerformanceIds = maximumFrequency?.let { maximum ->
        allIds.filter { coreMaximumFrequencies[it] == maximum }
    }.orEmpty().takeIf { it.isNotEmpty() && it.size < allIds.size }
        ?: allIds.takeLast(minOf(2, allIds.size))
    BuiltinLocalCpuTopology(
        allCoreIds = allIds,
        performanceCoreIds = performanceIds,
        exclusivePerformanceCoreIds = exclusivePerformanceIds,
    )
}

private fun detectBuiltinLocalCpuTopology(): BuiltinLocalCpuTopology =
    detectedBuiltinLocalCpuTopology

internal fun builtinLocalWorkerAffinityPlan(
    topology: BuiltinLocalCpuTopology,
    cpuCorePolicy: BuiltinLocalCpuCorePolicy,
    cpuThreads: Int,
): List<Pair<String, String>> {
    val eligibleCoreIds = when (cpuCorePolicy) {
        BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER -> topology.performanceCoreIds
        BuiltinLocalCpuCorePolicy.ALL_CORES -> topology.allCoreIds
    }.distinct().sorted()
    if (eligibleCoreIds.isEmpty()) return emptyList()
    val threadCount = cpuThreads.coerceIn(1, eligibleCoreIds.size)
    val exclusiveCoreIds = topology.exclusivePerformanceCoreIds
        .filter { it in eligibleCoreIds }
        .distinct()
        .sortedDescending()
    val sharedCoreIds = eligibleCoreIds.filterNot { it in exclusiveCoreIds }
    val plan = exclusiveCoreIds.take(threadCount).map { coreId ->
        coreId.toString() to BigInteger.ONE.shiftLeft(coreId).toString(16)
    }.toMutableList()
    if (plan.size < threadCount && sharedCoreIds.isNotEmpty()) {
        val sharedLabel = if (sharedCoreIds.zipWithNext().all { (left, right) -> right == left + 1 }) {
            "${sharedCoreIds.first()}-${sharedCoreIds.last()}"
        } else {
            sharedCoreIds.joinToString(separator = ",")
        }
        val sharedMask = sharedCoreIds
            .fold(BigInteger.ZERO) { mask, coreId -> mask.setBit(coreId) }
            .toString(16)
        repeat(threadCount - plan.size) {
            plan += sharedLabel to sharedMask
        }
    }
    return plan
}

private object BuiltinLocalPerformanceController {
    private val allowedPath = Regex(
        "^(?:" +
            "/sys/devices/system/cpu/cpufreq/policy\\d+/scaling_(?:min|max)_freq|" +
            "/sys/kernel/msm_performance/parameters/cpu_max_freq|" +
            "/proc/sys/walt/(?:sched_high_perf_cluster_freq_cap|sched_max_freq_partial_halt)|" +
            "/proc/game_opt/disable_cpufreq_limit|" +
            "/sys/module/cpufreq_bouncing/parameters/enable|" +
            "/sys/class/(?:kgsl/kgsl-3d0/devfreq|devfreq/[^/]+)/(?:min|max)_freq|" +
            "/sys/(?:devices/platform/(?:soc/)?[^/]+kgsl-3d0/kgsl/|class/kgsl/)" +
            "kgsl-3d0/(?:min_clock_mhz|max_clock_mhz|min_pwrlevel|max_pwrlevel)" +
            ")$"
    )

    fun acquire(
        cpuCorePolicy: BuiltinLocalCpuCorePolicy,
        cpuThreads: Int,
    ): PerformanceLease? {
        if (!KeepShellPublic.checkRoot()) return null
        val topology = detectBuiltinLocalCpuTopology()
        val selectedCoreIds = when (cpuCorePolicy) {
            BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER ->
                topology.performanceCoreIds
            BuiltinLocalCpuCorePolicy.ALL_CORES ->
                topology.allCoreIds
        }
        val selectedCoreIdList = selectedCoreIds.joinToString(separator = " ")
        val workerAffinityPlan = builtinLocalWorkerAffinityPlan(
            topology = topology,
            cpuCorePolicy = cpuCorePolicy,
            cpuThreads = cpuThreads,
        )
        val output = runCatching {
            KeepShellPublic.doCmdSync(
                """
                selected_cores=' $selectedCoreIdList '
                game_cpufreq_limit=/proc/game_opt/disable_cpufreq_limit
                if [ -r "${'$'}game_cpufreq_limit" ] && [ -w "${'$'}game_cpufreq_limit" ]; then
                  old_game_cpufreq_limit=${'$'}(cat "${'$'}game_cpufreq_limit" 2>/dev/null)
                  case "${'$'}old_game_cpufreq_limit" in
                    *[!0-9]*|'') ;;
                    *)
                      printf 'MURONG_FREQ|%s|%s\n' "${'$'}game_cpufreq_limit" "${'$'}old_game_cpufreq_limit"
                      printf 'MURONG_ENFORCE|%s|%s\n' "${'$'}game_cpufreq_limit" 1
                      printf '%s' 1 > "${'$'}game_cpufreq_limit" 2>/dev/null
                      applied_game_cpufreq_limit=${'$'}(cat "${'$'}game_cpufreq_limit" 2>/dev/null)
                      [ "${'$'}applied_game_cpufreq_limit" = 1 ] &&
                        printf 'MURONG_APPLIED|%s|%s\n' "${'$'}game_cpufreq_limit" 1
                      ;;
                  esac
                fi
                bouncing_path=/sys/module/cpufreq_bouncing/parameters/enable
                if [ -r "${'$'}bouncing_path" ] && [ -w "${'$'}bouncing_path" ]; then
                  old_bouncing=${'$'}(cat "${'$'}bouncing_path" 2>/dev/null)
                  case "${'$'}old_bouncing" in
                    *[!0-9]*|'') ;;
                    *)
                      printf 'MURONG_FREQ|%s|%s\n' "${'$'}bouncing_path" "${'$'}old_bouncing"
                      printf 'MURONG_ENFORCE|%s|%s\n' "${'$'}bouncing_path" 0
                      printf '%s' 0 > "${'$'}bouncing_path" 2>/dev/null
                      applied_bouncing=${'$'}(cat "${'$'}bouncing_path" 2>/dev/null)
                      [ "${'$'}applied_bouncing" = 0 ] &&
                        printf 'MURONG_APPLIED|%s|%s\n' "${'$'}bouncing_path" 0
                      ;;
                  esac
                fi
                perflock_path=/sys/kernel/msm_performance/parameters/cpu_max_freq
                if [ -r "${'$'}perflock_path" ] && [ -w "${'$'}perflock_path" ]; then
                  old_votes=${'$'}(tr '\n' ' ' < "${'$'}perflock_path" 2>/dev/null)
                  for cpu in $selectedCoreIds; do
                    old_vote=''
                    for pair in ${'$'}old_votes; do
                      case "${'$'}pair" in
                        "${'$'}cpu:"*) old_vote=${'$'}{pair#*:}; break ;;
                      esac
                    done
                    anchor_cpu=''
                    anchor_old_vote=''
                    for pair in ${'$'}old_votes; do
                      candidate_cpu=${'$'}{pair%%:*}
                      candidate_vote=${'$'}{pair#*:}
                      [ "${'$'}candidate_cpu" = "${'$'}cpu" ] && continue
                      case "${'$'}candidate_cpu:${'$'}candidate_vote" in
                        *[!0-9:]*|*::*|:*|*:) continue ;;
                      esac
                      anchor_cpu=${'$'}candidate_cpu
                      anchor_old_vote=${'$'}candidate_vote
                      break
                    done
                    case "${'$'}cpu:${'$'}old_vote:${'$'}anchor_cpu:${'$'}anchor_old_vote" in
                      *[!0-9:]*|*::*|:*|*:) continue ;;
                    esac
                    anchor_target_vote=${'$'}anchor_old_vote
                    case "${'$'}selected_cores" in
                      *" ${'$'}anchor_cpu "*) anchor_target_vote=4294967295 ;;
                    esac
                    # Some Qualcomm kernels only apply the first CPU:value pair
                    # while still requiring a second valid pair for parsing.
                    # Per-core two-pair transactions also remain valid on the
                    # kernels that consume every pair in one write.
                    printf 'MURONG_FREQ|%s|%s:%s %s:%s\n' \
                      "${'$'}perflock_path" "${'$'}cpu" "${'$'}old_vote" \
                      "${'$'}anchor_cpu" "${'$'}anchor_old_vote"
                    printf 'MURONG_ENFORCE|%s|%s:%s %s:%s\n' \
                      "${'$'}perflock_path" "${'$'}cpu" 4294967295 \
                      "${'$'}anchor_cpu" "${'$'}anchor_target_vote"
                    printf '%s' \
                      "${'$'}cpu:4294967295 ${'$'}anchor_cpu:${'$'}anchor_target_vote" \
                      > "${'$'}perflock_path" 2>/dev/null
                  done
                fi
                walt_cluster_cap=/proc/sys/walt/sched_high_perf_cluster_freq_cap
                if [ -r "${'$'}walt_cluster_cap" ] && [ -w "${'$'}walt_cluster_cap" ]; then
                  old_cluster_cap=${'$'}(cat "${'$'}walt_cluster_cap" 2>/dev/null)
                  unlimited_cluster_cap=''
                  tab=${'$'}(printf '\t')
                  for ignored in ${'$'}old_cluster_cap; do
                    [ -z "${'$'}unlimited_cluster_cap" ] || unlimited_cluster_cap="${'$'}unlimited_cluster_cap${'$'}tab"
                    unlimited_cluster_cap="${'$'}unlimited_cluster_cap"2147483647
                  done
                  if [ -n "${'$'}old_cluster_cap" ] && [ -n "${'$'}unlimited_cluster_cap" ]; then
                    printf 'MURONG_FREQ|%s|%s\n' "${'$'}walt_cluster_cap" "${'$'}old_cluster_cap"
                    printf 'MURONG_ENFORCE|%s|%s\n' "${'$'}walt_cluster_cap" "${'$'}unlimited_cluster_cap"
                    printf '%s' "${'$'}unlimited_cluster_cap" > "${'$'}walt_cluster_cap" 2>/dev/null
                  fi
                fi
                walt_partial_cap=/proc/sys/walt/sched_max_freq_partial_halt
                if [ -r "${'$'}walt_partial_cap" ] && [ -w "${'$'}walt_partial_cap" ]; then
                  old_partial_cap=${'$'}(cat "${'$'}walt_partial_cap" 2>/dev/null)
                  case "${'$'}old_partial_cap" in
                    *[!0-9]*|'') ;;
                    *)
                      printf 'MURONG_FREQ|%s|%s\n' "${'$'}walt_partial_cap" "${'$'}old_partial_cap"
                      printf 'MURONG_ENFORCE|%s|%s\n' "${'$'}walt_partial_cap" 2147483647
                      printf '%s' 2147483647 > "${'$'}walt_partial_cap" 2>/dev/null
                      ;;
                  esac
                fi
                for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do
                  [ -r "${'$'}d/scaling_min_freq" ] || continue
                  [ -r "${'$'}d/scaling_max_freq" ] || continue
                  [ -r "${'$'}d/cpuinfo_max_freq" ] || continue
                  use_policy=0
                  for cpu in ${'$'}(cat "${'$'}d/related_cpus" 2>/dev/null); do
                    case "${'$'}selected_cores" in *" ${'$'}cpu "*) use_policy=1 ;; esac
                  done
                  [ "${'$'}use_policy" = 1 ] || continue
                  old_min=${'$'}(cat "${'$'}d/scaling_min_freq" 2>/dev/null)
                  old_max=${'$'}(cat "${'$'}d/scaling_max_freq" 2>/dev/null)
                  hardware_max=${'$'}(cat "${'$'}d/cpuinfo_max_freq" 2>/dev/null)
                  case "${'$'}old_min:${'$'}old_max:${'$'}hardware_max" in
                    *[!0-9:]*|*::*|:*|*:) continue ;;
                  esac
                  printf 'MURONG_FREQ|%s|%s\n' "${'$'}d/scaling_min_freq" "${'$'}old_min"
                  printf 'MURONG_FREQ|%s|%s\n' "${'$'}d/scaling_max_freq" "${'$'}old_max"
                  printf '%s' "${'$'}hardware_max" > "${'$'}d/scaling_max_freq" 2>/dev/null
                  printf '%s' "${'$'}hardware_max" > "${'$'}d/scaling_min_freq" 2>/dev/null
                  applied_min=${'$'}(cat "${'$'}d/scaling_min_freq" 2>/dev/null)
                  applied_max=${'$'}(cat "${'$'}d/scaling_max_freq" 2>/dev/null)
                  case "${'$'}applied_min:${'$'}applied_max" in
                    *[!0-9:]*|*::*|:*|*:) applied_min=0; applied_max=0 ;;
                  esac
                  if [ "${'$'}applied_min" -gt 0 ] &&
                     [ "${'$'}applied_min" = "${'$'}applied_max" ]; then
                    printf 'MURONG_APPLIED|%s|%s\n' "${'$'}d/scaling_min_freq" "${'$'}applied_min"
                    printf 'MURONG_APPLIED|%s|%s\n' "${'$'}d/scaling_max_freq" "${'$'}applied_max"
                    printf 'MURONG_ENFORCE|%s|%s\n' "${'$'}d/scaling_max_freq" "${'$'}hardware_max"
                    printf 'MURONG_ENFORCE|%s|%s\n' "${'$'}d/scaling_min_freq" "${'$'}hardware_max"
                  else
                    printf '%s' "${'$'}old_min" > "${'$'}d/scaling_min_freq" 2>/dev/null
                    printf '%s' "${'$'}old_max" > "${'$'}d/scaling_max_freq" 2>/dev/null
                    printf '%s' "${'$'}old_min" > "${'$'}d/scaling_min_freq" 2>/dev/null
                  fi
                done
                for d in /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*; do
                  case "${'$'}d" in *kgsl*|*gpu*|*GPU*) ;; *) continue ;; esac
                  [ -r "${'$'}d/min_freq" ] || continue
                  [ -r "${'$'}d/max_freq" ] || continue
                  old_min=${'$'}(cat "${'$'}d/min_freq" 2>/dev/null)
                  old_max=${'$'}(cat "${'$'}d/max_freq" 2>/dev/null)
                  target=${'$'}old_max
                  for frequency in ${'$'}(cat "${'$'}d/available_frequencies" 2>/dev/null); do
                    case "${'$'}frequency" in *[!0-9]*|'') continue ;; esac
                    [ "${'$'}frequency" -gt "${'$'}target" ] && target=${'$'}frequency
                  done
                  case "${'$'}old_min:${'$'}old_max:${'$'}target" in
                    *[!0-9:]*|*::*|:*|*:) continue ;;
                  esac
                  printf 'MURONG_FREQ|%s|%s\n' "${'$'}d/min_freq" "${'$'}old_min"
                  printf 'MURONG_FREQ|%s|%s\n' "${'$'}d/max_freq" "${'$'}old_max"
                  printf '%s' "${'$'}target" > "${'$'}d/max_freq" 2>/dev/null
                  printf '%s' "${'$'}target" > "${'$'}d/min_freq" 2>/dev/null
                  applied_min=${'$'}(cat "${'$'}d/min_freq" 2>/dev/null)
                  applied_max=${'$'}(cat "${'$'}d/max_freq" 2>/dev/null)
                  case "${'$'}applied_min:${'$'}applied_max" in
                    *[!0-9:]*|*::*|:*|*:) applied_min=0; applied_max=0 ;;
                  esac
                  if [ "${'$'}applied_min" -ge "${'$'}old_min" ] &&
                     [ "${'$'}applied_min" -le "${'$'}applied_max" ]; then
                    printf 'MURONG_APPLIED|%s|%s\n' "${'$'}d/min_freq" "${'$'}applied_min"
                    printf 'MURONG_APPLIED|%s|%s\n' "${'$'}d/max_freq" "${'$'}applied_max"
                    printf 'MURONG_ENFORCE|%s|%s\n' "${'$'}d/max_freq" "${'$'}target"
                    printf 'MURONG_ENFORCE|%s|%s\n' "${'$'}d/min_freq" "${'$'}target"
                  else
                    printf '%s' "${'$'}old_min" > "${'$'}d/min_freq" 2>/dev/null
                    printf '%s' "${'$'}old_max" > "${'$'}d/max_freq" 2>/dev/null
                    printf '%s' "${'$'}old_min" > "${'$'}d/min_freq" 2>/dev/null
                  fi
                done
                seen_kgsl=' '
                for d in /sys/devices/platform/soc/*kgsl-3d0/kgsl/kgsl-3d0 \
                         /sys/devices/platform/*kgsl-3d0/kgsl/kgsl-3d0 \
                         /sys/class/kgsl/kgsl-3d0; do
                  resolved=${'$'}(readlink -f "${'$'}d" 2>/dev/null)
                  [ -n "${'$'}resolved" ] || continue
                  case "${'$'}seen_kgsl" in *" ${'$'}resolved "*) continue ;; esac
                  seen_kgsl="${'$'}seen_kgsl${'$'}resolved "
                  d=${'$'}resolved
                  [ -r "${'$'}d/min_clock_mhz" ] || continue
                  [ -r "${'$'}d/max_clock_mhz" ] || continue
                  old_min=${'$'}(cat "${'$'}d/min_clock_mhz" 2>/dev/null)
                  old_max=${'$'}(cat "${'$'}d/max_clock_mhz" 2>/dev/null)
                  old_min_level=${'$'}(cat "${'$'}d/min_pwrlevel" 2>/dev/null)
                  old_max_level=${'$'}(cat "${'$'}d/max_pwrlevel" 2>/dev/null)
                  target_hz=${'$'}(cat "${'$'}d/max_gpuclk" 2>/dev/null)
                  case "${'$'}target_hz" in
                    *[!0-9]*|'') target=${'$'}old_max ;;
                    *) target=${'$'}((target_hz / 1000000)) ;;
                  esac
                  case "${'$'}old_min:${'$'}old_max:${'$'}target:${'$'}old_min_level:${'$'}old_max_level" in
                    *[!0-9:]*|*::*|:*|*:) continue ;;
                  esac
                  printf 'MURONG_FREQ|%s|%s\n' "${'$'}d/min_clock_mhz" "${'$'}old_min"
                  printf 'MURONG_FREQ|%s|%s\n' "${'$'}d/max_clock_mhz" "${'$'}old_max"
                  printf 'MURONG_FREQ|%s|%s\n' "${'$'}d/min_pwrlevel" "${'$'}old_min_level"
                  printf 'MURONG_FREQ|%s|%s\n' "${'$'}d/max_pwrlevel" "${'$'}old_max_level"
                  # KGSL pwrlevel 0 is the highest OPP. Keep the previous
                  # ceiling as a temporary floor, matching the scheduler's
                  # high_level/low_level range instead of pinning an idle GPU.
                  printf '%s' 0 > "${'$'}d/max_pwrlevel" 2>/dev/null
                  printf '%s' "${'$'}old_max_level" > "${'$'}d/min_pwrlevel" 2>/dev/null
                  printf '%s' "${'$'}target" > "${'$'}d/max_clock_mhz" 2>/dev/null
                  applied_min=${'$'}(cat "${'$'}d/min_clock_mhz" 2>/dev/null)
                  applied_max=${'$'}(cat "${'$'}d/max_clock_mhz" 2>/dev/null)
                  applied_min_level=${'$'}(cat "${'$'}d/min_pwrlevel" 2>/dev/null)
                  applied_max_level=${'$'}(cat "${'$'}d/max_pwrlevel" 2>/dev/null)
                  case "${'$'}applied_min:${'$'}applied_max:${'$'}applied_min_level:${'$'}applied_max_level" in
                    *[!0-9:]*|*::*|:*|*:) applied_min=0; applied_max=0; applied_min_level=-1; applied_max_level=-1 ;;
                  esac
                  if [ "${'$'}applied_min" -ge "${'$'}old_min" ] &&
                     [ "${'$'}applied_min" -le "${'$'}applied_max" ]; then
                    printf 'MURONG_APPLIED|%s|%s\n' "${'$'}d/min_clock_mhz" "${'$'}applied_min"
                    printf 'MURONG_APPLIED|%s|%s\n' "${'$'}d/max_clock_mhz" "${'$'}applied_max"
                    printf 'MURONG_ENFORCE|%s|%s\n' "${'$'}d/max_clock_mhz" "${'$'}target"
                  fi
                  if [ "${'$'}applied_max_level" = 0 ]; then
                    printf 'MURONG_APPLIED|%s|%s\n' "${'$'}d/max_pwrlevel" "${'$'}applied_max_level"
                    printf 'MURONG_ENFORCE|%s|%s\n' "${'$'}d/max_pwrlevel" 0
                  fi
                  if [ "${'$'}applied_min_level" = "${'$'}old_max_level" ]; then
                    printf 'MURONG_APPLIED|%s|%s\n' "${'$'}d/min_pwrlevel" "${'$'}applied_min_level"
                    printf 'MURONG_ENFORCE|%s|%s\n' "${'$'}d/min_pwrlevel" "${'$'}old_max_level"
                  fi
                  if [ "${'$'}applied_min" -lt "${'$'}old_min" ] ||
                     [ "${'$'}applied_max_level" != 0 ]; then
                    printf '%s' "${'$'}old_min" > "${'$'}d/min_clock_mhz" 2>/dev/null
                    printf '%s' "${'$'}old_max" > "${'$'}d/max_clock_mhz" 2>/dev/null
                    printf '%s' "${'$'}old_min" > "${'$'}d/min_clock_mhz" 2>/dev/null
                    printf '%s' "${'$'}old_min_level" > "${'$'}d/min_pwrlevel" 2>/dev/null
                    printf '%s' "${'$'}old_max_level" > "${'$'}d/max_pwrlevel" 2>/dev/null
                  else
                    :
                  fi
                done
                """.trimIndent()
            )
        }.getOrNull() ?: return null
        val values = output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split('|')
            if (parts.size != 3 || parts[0] != "MURONG_FREQ") return@mapNotNull null
            val path = parts[1]
            val value = parts[2]
            if (!allowedPath.matches(path) || !isAllowedValue(path, value)) {
                return@mapNotNull null
            }
            path to value
        }.distinctBy { (path, value) ->
            if (path == "/sys/kernel/msm_performance/parameters/cpu_max_freq") {
                "$path|$value"
            } else {
                path
            }
        }.toList()
        val appliedValues = output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split('|')
            if (parts.size != 3 || parts[0] != "MURONG_APPLIED") return@mapNotNull null
            val path = parts[1]
            val value = parts[2]
            path.takeIf { allowedPath.matches(it) && isAllowedValue(it, value) }
                ?.let { it to value }
        }.distinctBy { it.first }.toMap()
        val enforcementValues = output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split('|')
            if (parts.size != 3 || parts[0] != "MURONG_ENFORCE") return@mapNotNull null
            val path = parts[1]
            val value = parts[2]
            path.takeIf { allowedPath.matches(it) && isAllowedValue(it, value) }
                ?.let { it to value }
        }.distinctBy { (path, value) ->
            if (path == "/sys/kernel/msm_performance/parameters/cpu_max_freq") {
                "$path|$value"
            } else {
                path
            }
        }.toList()
        val lease = values.takeIf { it.isNotEmpty() }
            ?.let { PerformanceLease(it, enforcementValues, workerAffinityPlan) }
            ?: return null
        if (appliedValues.isEmpty()) {
            Log.w(PERFORMANCE_TAG, "Root 满频请求未被任何 CPU/GPU 节点接受")
            lease.restore()
            return null
        }
        Log.i(
            PERFORMANCE_TAG,
            appliedValues.entries.joinToString(
                prefix = "已按内核回读值提升频率: ",
                separator = ", ",
            ) { (path, value) -> "${path.substringAfterLast('/')}=$value" },
        )
        lease.startEnforcing()
        return lease
    }

    private fun isAllowedValue(path: String, value: String): Boolean = when (path) {
        "/sys/kernel/msm_performance/parameters/cpu_max_freq" ->
            value.matches(Regex("(?:\\d+:\\d+\\s*)+"))
        "/proc/sys/walt/sched_high_perf_cluster_freq_cap" ->
            value.matches(Regex("\\d+(?:\\s+\\d+)*"))
        else -> value.isNotEmpty() && value.all(Char::isDigit)
    }

    class PerformanceLease(
        private val originalValues: List<Pair<String, String>>,
        private val enforcementValues: List<Pair<String, String>>,
        private val workerAffinityPlan: List<Pair<String, String>>,
    ) {
        private val restored = AtomicBoolean(false)
        private val enforcementFailureLogged = AtomicBoolean(false)
        private val shellLock = Any()
        private val appPid = android.os.Process.myPid()
        private val enforcerToken =
            "murong-local-perf-$appPid-${System.nanoTime().toString(16)}"
        private val affinitySnapshotPath = "/data/local/tmp/$enforcerToken.affinity"
        private val affinityAssignmentsPath = "/data/local/tmp/$enforcerToken.bindings"
        private val previousTicksPath = "/data/local/tmp/$enforcerToken.ticks"
        private val currentTicksPath = "/data/local/tmp/$enforcerToken.ticks-current"
        private val rankedThreadsPath = "/data/local/tmp/$enforcerToken.ranked"
        @Volatile
        private var enforcerPid: Int? = null

        fun startEnforcing() {
            if (enforcementValues.isEmpty() || restored.get() || enforcerPid != null) return
            val enforceCommand = buildWriteCommand(enforcementValues)
            val restoreCommand = buildWriteCommand(orderedOriginalValues())
            val restoreAffinityCommand = buildRestoreAffinityCommand()
            val workerPlans = workerAffinityPlan.joinToString(separator = " ") {
                (coreLabel, mask) -> "$coreLabel:$mask"
            }
            val helperScript =
                """
                affinity_file=${shellQuote(affinitySnapshotPath)}
                bindings_file=${shellQuote(affinityAssignmentsPath)}
                previous_ticks_file=${shellQuote(previousTicksPath)}
                current_ticks_file=${shellQuote(currentTicksPath)}
                ranked_threads_file=${shellQuote(rankedThreadsPath)}
                worker_plans=${shellQuote(workerPlans)}
                worker_count=${workerAffinityPlan.size}
                minimum_worker_ticks=$MINIMUM_ACTIVE_WORKER_TICKS
                umask 077
                : > "${'$'}affinity_file"
                : > "${'$'}bindings_file"
                : > "${'$'}previous_ticks_file"
                : > "${'$'}current_ticks_file"
                : > "${'$'}ranked_threads_file"
                write_thread_ticks() {
                  target_file=${'$'}1
                  : > "${'$'}target_file"
                  for stat_file in "/proc/$appPid"/task/*/stat; do
                    [ -r "${'$'}stat_file" ] || continue
                    tid_path=${'$'}{stat_file%/stat}
                    tid=${'$'}{tid_path##*/}
                    [ "${'$'}tid" = "$appPid" ] && continue
                    stat_line=${'$'}(cat "${'$'}stat_file" 2>/dev/null) || continue
                    stat_tail=${'$'}{stat_line##*) }
                    set -- ${'$'}stat_tail
                    user_ticks=${'$'}{12}
                    system_ticks=${'$'}{13}
                    case "${'$'}tid:${'$'}user_ticks:${'$'}system_ticks" in
                      *[!0-9:]*|*::*|:*|*:) continue ;;
                    esac
                    printf '%s %s\n' "${'$'}tid" "${'$'}((user_ticks + system_ticks))" \
                      >> "${'$'}target_file"
                  done
                }
                rank_active_workers() {
                  write_thread_ticks "${'$'}current_ticks_file"
                  : > "${'$'}ranked_threads_file"
                  if [ -s "${'$'}previous_ticks_file" ]; then
                    while read -r tid current_ticks; do
                      case "${'$'}tid:${'$'}current_ticks" in
                        *[!0-9:]*|*::*|:*|*:) continue ;;
                      esac
                      old_ticks=${'$'}(awk -v target="${'$'}tid" \
                        '${'$'}1 == target { print ${'$'}2; exit }' \
                        "${'$'}previous_ticks_file" 2>/dev/null)
                      case "${'$'}old_ticks" in *[!0-9]*|'') continue ;; esac
                      [ "${'$'}current_ticks" -ge "${'$'}old_ticks" ] || continue
                      delta=${'$'}((current_ticks - old_ticks))
                      [ "${'$'}delta" -ge "${'$'}minimum_worker_ticks" ] || continue
                      printf '%s %s\n' "${'$'}delta" "${'$'}tid" \
                        >> "${'$'}ranked_threads_file"
                    done < "${'$'}current_ticks_file"
                    sort -nr -k1,1 "${'$'}ranked_threads_file" \
                      > "${'$'}ranked_threads_file.sorted" 2>/dev/null
                    mv "${'$'}ranked_threads_file.sorted" \
                      "${'$'}ranked_threads_file" 2>/dev/null
                  fi
                  mv "${'$'}current_ticks_file" "${'$'}previous_ticks_file" 2>/dev/null
                }
                restore_current_bindings() {
                  while read -r tid ignored_core ignored_mask; do
                    case "${'$'}tid" in *[!0-9]*|'') continue ;; esac
                    old_mask=${'$'}(awk -v target="${'$'}tid" \
                      '${'$'}1 == target { print ${'$'}2; exit }' \
                      "${'$'}affinity_file" 2>/dev/null)
                    case "${'$'}old_mask" in *[!0-9a-fA-F]*|'') continue ;; esac
                    [ -d "/proc/$appPid/task/${'$'}tid" ] &&
                      taskset -p "${'$'}old_mask" "${'$'}tid" >/dev/null 2>&1
                  done < "${'$'}bindings_file"
                  : > "${'$'}bindings_file"
                }
                bind_active_workers() {
                  rank_active_workers
                  [ -s "${'$'}ranked_threads_file" ] || return
                  ranked_tids=${'$'}(awk '${'$'}1 > 0 { print ${'$'}2 }' \
                    "${'$'}ranked_threads_file" 2>/dev/null |
                    head -n "${'$'}worker_count" | tr '\n' ' ')
                  [ -n "${'$'}ranked_tids" ] || return
                  restore_current_bindings
                  set -- ${'$'}ranked_tids
                  for plan in ${'$'}worker_plans; do
                    [ "${'$'}#" -gt 0 ] || break
                    tid=${'$'}1
                    shift
                    core_label=${'$'}{plan%%:*}
                    mask=${'$'}{plan#*:}
                    case "${'$'}tid:${'$'}mask" in
                      *[!0-9a-fA-F:]*|*::*|:*|*:) continue ;;
                    esac
                    [ -d "/proc/$appPid/task/${'$'}tid" ] || continue
                    if ! awk -v target="${'$'}tid" \
                      '${'$'}1 == target { found = 1 } END { exit !found }' \
                      "${'$'}affinity_file" 2>/dev/null; then
                      old_mask=${'$'}(taskset -p "${'$'}tid" 2>/dev/null |
                        sed -n 's/.*: //p' | tail -n 1)
                      case "${'$'}old_mask" in *[!0-9a-fA-F]*|'') continue ;; esac
                      printf '%s %s\n' "${'$'}tid" "${'$'}old_mask" \
                        >> "${'$'}affinity_file"
                    fi
                    if taskset -p "${'$'}mask" "${'$'}tid" >/dev/null 2>&1; then
                      printf '%s %s %s\n' "${'$'}tid" "${'$'}core_label" "${'$'}mask" \
                        >> "${'$'}bindings_file"
                    fi
                  done
                }
                restore_affinity() {
                  $restoreAffinityCommand
                }
                restore_values() {
                  $restoreCommand
                }
                stop_and_restore() {
                  trap - EXIT HUP INT TERM
                  restore_affinity
                  restore_values
                  exit 0
                }
                trap stop_and_restore EXIT HUP INT TERM
                affinity_tick=0
                while [ -r "/proc/$appPid/cmdline" ] &&
                      grep -aFq "com.murong.agent" "/proc/$appPid/cmdline" 2>/dev/null; do
                  if [ "${'$'}affinity_tick" -eq 0 ]; then
                    bind_active_workers
                  fi
                  $enforceCommand
                  affinity_tick=${'$'}(((affinity_tick + 1) % 20))
                  sleep $PERFORMANCE_ENFORCEMENT_SLEEP_SECONDS
                done
                stop_and_restore
                """.trimIndent()
            val launchCommand =
                """
                /system/bin/sh -c ${shellQuote(helperScript)} ${shellQuote(enforcerToken)} \
                  >/dev/null 2>&1 &
                helper_pid=${'$'}!
                printf 'MURONG_ENFORCER_PID|%s\n' "${'$'}helper_pid"
                """.trimIndent()
            synchronized(shellLock) {
                if (restored.get()) return
                runCatching { KeepShellPublic.doCmdSync(launchCommand) }
                    .mapCatching { output ->
                        output.lineSequence()
                            .firstNotNullOfOrNull { line ->
                                line.substringAfter("MURONG_ENFORCER_PID|", "")
                                    .trim()
                                    .toIntOrNull()
                            }
                            ?.takeIf { it > 1 }
                            ?: error("Root 满频守护进程未返回有效 PID")
                    }
                    .onSuccess { pid -> enforcerPid = pid }
                    .onFailure {
                        if (enforcementFailureLogged.compareAndSet(false, true)) {
                            Log.w(PERFORMANCE_TAG, "推理满频守护进程启动失败", it)
                        }
                    }
            }
            enforcerPid?.let { pid ->
                Log.i(
                    PERFORMANCE_TAG,
                    "推理满频 Root 守护进程已启动: pid=$pid，每 ${PERFORMANCE_ENFORCEMENT_INTERVAL_MILLIS}ms 重申目标值；大核 worker 独占、小核 worker 共享=${workerAffinityPlan.joinToString { (cores, mask) -> "CPU$cores/$mask" }}",
                )
            }
        }

        fun restore() {
            if (!restored.compareAndSet(false, true)) return
            if (originalValues.isEmpty() || !KeepShellPublic.checkRoot()) return
            val pid = enforcerPid
            enforcerPid = null
            val stopCommand = pid?.let {
                """
                helper_pid=$it
                helper_token=${shellQuote(enforcerToken)}
                if [ -r "/proc/${'$'}helper_pid/cmdline" ] &&
                   grep -aFq "${'$'}helper_token" "/proc/${'$'}helper_pid/cmdline" 2>/dev/null; then
                  kill -TERM "${'$'}helper_pid" 2>/dev/null
                fi
                wait_count=0
                while [ -d "/proc/${'$'}helper_pid" ] && [ "${'$'}wait_count" -lt 10 ]; do
                  sleep 0.05
                  wait_count=${'$'}((wait_count + 1))
                done
                if [ -r "/proc/${'$'}helper_pid/cmdline" ] &&
                   grep -aFq "${'$'}helper_token" "/proc/${'$'}helper_pid/cmdline" 2>/dev/null; then
                  kill -KILL "${'$'}helper_pid" 2>/dev/null
                fi
                ${buildRestoreAffinityCommand()}
                """.trimIndent()
            }.orEmpty()
            val command = listOf(stopCommand, buildWriteCommand(orderedOriginalValues()))
                .filter(String::isNotBlank)
                .joinToString(separator = "\n")
            synchronized(shellLock) {
                runCatching { KeepShellPublic.doCmdSync(command) }
                    .onSuccess { Log.i(PERFORMANCE_TAG, "本地推理结束，已停止满频守护并恢复 CPU/GPU 原值") }
                    .onFailure {
                        Log.w(PERFORMANCE_TAG, "CPU/GPU 频率原值已逐项回写，但 Root 命令返回了错误")
                    }
            }
        }

        private fun orderedOriginalValues(): List<Pair<String, String>> =
            originalValues.sortedBy { (path, _) ->
                when {
                    path.endsWith("min_freq") || path.endsWith("min_clock_mhz") -> 0
                    path.endsWith("max_freq") || path.endsWith("max_clock_mhz") -> 1
                    path == "/proc/game_opt/disable_cpufreq_limit" ||
                        path == "/sys/module/cpufreq_bouncing/parameters/enable" -> 3
                    else -> 2
                }
            }

        private fun buildWriteCommand(values: List<Pair<String, String>>): String =
            values.joinToString(separator = "\n") { (path, value) ->
                "printf '%s' ${shellQuote(value)} > ${shellQuote(path)} 2>/dev/null"
            }

        private fun buildRestoreAffinityCommand(): String =
            """
            if [ -r ${shellQuote(affinitySnapshotPath)} ]; then
              while read -r tid old_mask; do
                case "${'$'}tid:${'$'}old_mask" in
                  *[!0-9a-fA-F:]*|*::*|:*|*:) continue ;;
                esac
                [ -d "/proc/$appPid/task/${'$'}tid" ] &&
                  taskset -p "${'$'}old_mask" "${'$'}tid" >/dev/null 2>&1
              done < ${shellQuote(affinitySnapshotPath)}
            fi
            rm -f ${shellQuote(affinitySnapshotPath)} \
              ${shellQuote(affinityAssignmentsPath)} \
              ${shellQuote(previousTicksPath)} \
              ${shellQuote(currentTicksPath)} \
              ${shellQuote(rankedThreadsPath)} \
              ${shellQuote("$rankedThreadsPath.sorted")}
            """.trimIndent()

        private fun shellQuote(value: String): String =
            "'${value.replace("'", "'\"'\"'")}'"
    }

    private const val PERFORMANCE_TAG = "MurongLocalPerf"
    private const val PERFORMANCE_ENFORCEMENT_INTERVAL_MILLIS = 50L
    private const val PERFORMANCE_ENFORCEMENT_SLEEP_SECONDS = "0.05"
    private const val MINIMUM_ACTIVE_WORKER_TICKS = 20
}

internal object BuiltinVisionNative {
    external fun nativeCreate(configPath: String, runtimeConfigJson: String): Long
    external fun nativeInfer(
        handle: Long,
        prompt: String,
        bgr: ByteArray,
        width: Int,
        height: Int,
        maxTokens: Int,
        enableThinking: Boolean,
        listener: BuiltinVisionTokenListener?
    ): String
    external fun nativeDestroy(handle: Long)
}

fun interface BuiltinVisionTokenListener {
    /**
     * Returning false asks the native decoder to stop before generating the
     * next token. tokenId comes from MNN's current decoder context.
     */
    fun onToken(text: String, tokenId: Int): Boolean
}
