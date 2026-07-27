package com.murong.agent.core.tool

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

enum class BuiltinVisionTier {
    GEMMA_12B_DESKTOP,
    ULTRA_9B,
    CODER_7B,
    PRO_4B,
    LITE_2B,
    GEMMA_E4B,
    GEMMA_E2B,
    GLM_EDGE_V_2B,
    GLM_EDGE_1_5B_CHAT,
    DEEPSEEK_R1_QWEN_7B,
    DEEPSEEK_R1_QWEN_1_5B,
    DEEPSEEK_R1_LLAMA_8B,
    DEEPSEEK_CODER_V2_LITE
}

enum class BuiltinVisionEngine {
    MNN,
    LITERT_LM,
    LLAMA_CPP
}

enum class BuiltinVisionSource {
    MODELSCOPE,
    HUGGING_FACE
}

data class BuiltinVisionModelFile(
    val name: String,
    val size: Long = 0,
    val sha256: String = ""
)

/**
 * Hugging Face's expanded tree currently exposes the LFS content digest as `lfs.oid`; some
 * older responses used `lfs.sha256`. Both represent the content SHA-256, unlike the Git blob OID
 * or Xet storage hash.
 */
internal fun huggingFaceLfsSha256(lfs: JSONObject): String =
    sequenceOf("sha256", "oid")
        .map { key -> lfs.optString(key).trim().removePrefix("sha256:") }
        .firstOrNull { value -> value.matches(Regex("[0-9a-fA-F]{64}")) }
        .orEmpty()

data class BuiltinVisionReasoningMode(
    val id: String,
    val displayName: String
)

data class BuiltinVisionModelDescriptor(
    val tier: BuiltinVisionTier,
    val id: String,
    val displayName: String,
    val engine: BuiltinVisionEngine,
    val source: BuiltinVisionSource,
    val repository: String,
    val files: List<BuiltinVisionModelFile>,
    val estimatedDownloadBytes: Long,
    val minimumFreeBytes: Long,
    val recommendation: String,
    val supportsVision: Boolean = true,
    val androidSupported: Boolean = true,
    val unavailableReason: String = "",
    val reasoningModes: List<BuiltinVisionReasoningMode> = emptyList(),
    val defaultReasoningMode: String = "",
    val resolvedRevision: String = ""
) {
    val totalBytes: Long
        get() = files.sumOf { it.size }.takeIf { it > 0L } ?: estimatedDownloadBytes
    val configFileName: String? get() =
        if (engine == BuiltinVisionEngine.MNN) "config.json" else null
    val ggufModelFileName: String? get() =
        if (engine == BuiltinVisionEngine.LLAMA_CPP) {
            files.singleOrNull { it.name.endsWith(".gguf") && !it.name.startsWith("mmproj-") }?.name
        } else {
            null
        }
    val ggufProjectorFileName: String? get() =
        if (engine == BuiltinVisionEngine.LLAMA_CPP) {
            files.singleOrNull { it.name.startsWith("mmproj-") && it.name.endsWith(".gguf") }?.name
        } else {
            null
        }

    fun resolveReasoningMode(requested: String?): String {
        if (reasoningModes.isEmpty()) return ""
        val normalized = requested?.trim()?.lowercase().orEmpty()
        return reasoningModes.firstOrNull { it.id == normalized }?.id
            ?: defaultReasoningMode.takeIf { candidate ->
                reasoningModes.any { it.id == candidate }
            }
            ?: reasoningModes.first().id
    }

    fun reasoningDisplayName(mode: String?): String? {
        val resolved = resolveReasoningMode(mode)
        return reasoningModes.firstOrNull { it.id == resolved }?.displayName
    }

    fun fileUrl(file: BuiltinVisionModelFile): String = when (source) {
        BuiltinVisionSource.MODELSCOPE ->
            "https://modelscope.cn/models/$repository/resolve/" +
                "${resolvedRevision.ifBlank { "master" }}/${encodePath(file.name)}"
        BuiltinVisionSource.HUGGING_FACE ->
            "https://huggingface.co/$repository/resolve/" +
                "${resolvedRevision.ifBlank { "main" }}/${encodePath(file.name)}?download=true"
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }
}

data class BuiltinVisionDeviceRecommendation(
    val recommendedTier: BuiltinVisionTier?,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val message: String
)

data class BuiltinVisionModelState(
    val installedTiers: Set<BuiltinVisionTier> = emptySet(),
    val activeTier: BuiltinVisionTier? = null,
    val installingTier: BuiltinVisionTier? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String = "",
    val error: String? = null,
    val recommendation: BuiltinVisionDeviceRecommendation? = null
) {
    val isInstalling: Boolean get() = installingTier != null
    val progress: Float
        get() = if (totalBytes <= 0L) 0f
        else (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

object BuiltinVisionModels {
    const val PACKAGE_VERSION = "builtin-vlm-2026-07-v1"
    const val READY_MARKER = ".murong-ready-$PACKAGE_VERSION"
    const val INSTALL_MANIFEST = ".murong-model-manifest.json"
    private const val GIB = 1024L * 1024L * 1024L
    private const val PREFERENCES = "builtin_vision_models"
    private const val ACTIVE_TIER = "active_tier"
    // This text-only 0.8B package was retired because it is too weak for the
    // assistant's conversational and summary routes. Keep the ID only long
    // enough to remove an already-downloaded package on upgrade.
    private const val RETIRED_FAST_MODEL_ID = "qwen3.5-0.8b-fast"

    private val THINKING_TOGGLE = listOf(
        BuiltinVisionReasoningMode("off", "关闭思考（更快）"),
        BuiltinVisionReasoningMode("on", "开启思考（显示过程）")
    )

    val GEMMA_12B_DESKTOP = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.GEMMA_12B_DESKTOP,
        id = "gemma-4-12b-experimental",
        displayName = "Gemma 4 12B（桌面实验档）",
        engine = BuiltinVisionEngine.LITERT_LM,
        source = BuiltinVisionSource.HUGGING_FACE,
        repository = "litert-community/gemma-4-12B-it-litert-lm",
        estimatedDownloadBytes = 6_550_000_000,
        minimumFreeBytes = 12L * GIB,
        recommendation = "约 6.55GB；Google 当前包支持电脑端文本/音频，Android 移动包和图像支持尚未发布。",
        supportsVision = false,
        androidSupported = false,
        unavailableReason = "等待 Google 发布 Gemma 4 12B Android 移动包",
        reasoningModes = THINKING_TOGGLE,
        defaultReasoningMode = "off",
        files = listOf(
            BuiltinVisionModelFile("gemma-4-12B-it.litertlm")
        )
    )

    val ULTRA_9B = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.ULTRA_9B,
        id = "qwen3.5-9b-ultra",
        displayName = "Qwen3.5-9B Ultra",
        engine = BuiltinVisionEngine.MNN,
        source = BuiltinVisionSource.MODELSCOPE,
        repository = "MNN/Qwen3.5-9B-MNN",
        estimatedDownloadBytes = 7_275_000_000,
        minimumFreeBytes = 12L * GIB,
        recommendation = "16GB 旗舰设备的最高质量档；代码、Agent 和视觉更强，但更慢、更耗电。",
        reasoningModes = THINKING_TOGGLE,
        defaultReasoningMode = "off",
        files = listOf(
            BuiltinVisionModelFile("config.json"),
            BuiltinVisionModelFile("embeddings_bf16.bin"),
            BuiltinVisionModelFile("llm.mnn"),
            BuiltinVisionModelFile("llm.mnn.json"),
            BuiltinVisionModelFile("llm.mnn.weight"),
            BuiltinVisionModelFile("llm_config.json"),
            BuiltinVisionModelFile("tokenizer.txt"),
            BuiltinVisionModelFile("visual.mnn"),
            BuiltinVisionModelFile("visual.mnn.weight")
        )
    )

    val CODER_7B = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.CODER_7B,
        id = "qwen2.5-coder-7b",
        displayName = "Qwen2.5-Coder-7B",
        engine = BuiltinVisionEngine.MNN,
        source = BuiltinVisionSource.MODELSCOPE,
        repository = "MNN/Qwen2.5-Coder-7B-Instruct-MNN",
        estimatedDownloadBytes = 4_427_000_000,
        minimumFreeBytes = 7L * GIB,
        recommendation = "纯文本代码专用档；补全、重构和解释代码更稳，不支持图片。",
        supportsVision = false,
        files = listOf(
            BuiltinVisionModelFile("config.json"),
            BuiltinVisionModelFile("llm_config.json"),
            BuiltinVisionModelFile("llm.mnn"),
            BuiltinVisionModelFile("llm.mnn.json"),
            BuiltinVisionModelFile("llm.mnn.weight"),
            BuiltinVisionModelFile("tokenizer.mtok")
        )
    )

    val PRO_4B = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.PRO_4B,
        id = "qwen3.5-4b-pro",
        displayName = "Qwen3.5-4B Pro",
        engine = BuiltinVisionEngine.MNN,
        source = BuiltinVisionSource.MODELSCOPE,
        repository = "MNN/Qwen3.5-4B-MNN",
        estimatedDownloadBytes = 2_846_000_000,
        minimumFreeBytes = 6L * GIB,
        recommendation = "中文、代码和手机/电脑 GUI Agent 首选。",
        reasoningModes = THINKING_TOGGLE,
        defaultReasoningMode = "off",
        files = listOf(
            BuiltinVisionModelFile("config.json"),
            BuiltinVisionModelFile("llm.mnn"),
            BuiltinVisionModelFile("llm.mnn.json"),
            BuiltinVisionModelFile("llm.mnn.weight"),
            BuiltinVisionModelFile("llm_config.json"),
            BuiltinVisionModelFile("tokenizer.txt"),
            BuiltinVisionModelFile("visual.mnn"),
            BuiltinVisionModelFile("visual.mnn.weight")
        )
    )

    val LITE_2B = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.LITE_2B,
        id = "qwen3.5-2b-lite",
        displayName = "Qwen3.5-2B Lite",
        engine = BuiltinVisionEngine.MNN,
        source = BuiltinVisionSource.MODELSCOPE,
        repository = "MNN/Qwen3.5-2B-MNN",
        estimatedDownloadBytes = 1_387_000_000,
        minimumFreeBytes = 3L * GIB,
        recommendation = "内存较小设备的中文 GUI 与代码轻量档。",
        reasoningModes = THINKING_TOGGLE,
        defaultReasoningMode = "off",
        files = listOf(
            BuiltinVisionModelFile("config.json"),
            BuiltinVisionModelFile("llm.mnn"),
            BuiltinVisionModelFile("llm.mnn.json"),
            BuiltinVisionModelFile("llm.mnn.weight"),
            BuiltinVisionModelFile("llm_config.json"),
            BuiltinVisionModelFile("tokenizer.txt"),
            BuiltinVisionModelFile("visual.mnn"),
            BuiltinVisionModelFile("visual.mnn.weight")
        )
    )

    val GEMMA_E4B = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.GEMMA_E4B,
        id = "gemma-4-e4b",
        displayName = "Gemma 4 E4B",
        engine = BuiltinVisionEngine.LITERT_LM,
        source = BuiltinVisionSource.HUGGING_FACE,
        repository = "litert-community/gemma-4-E4B-it-litert-lm",
        estimatedDownloadBytes = 3_660_000_000,
        minimumFreeBytes = 7L * GIB,
        recommendation = "端侧优化、原生图像/音频；中文 GUI 定位不如 Qwen 有针对性。",
        reasoningModes = THINKING_TOGGLE,
        defaultReasoningMode = "off",
        files = listOf(
            BuiltinVisionModelFile("gemma-4-E4B-it.litertlm")
        )
    )

    val GEMMA_E2B = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.GEMMA_E2B,
        id = "gemma-4-e2b",
        displayName = "Gemma 4 E2B",
        engine = BuiltinVisionEngine.LITERT_LM,
        source = BuiltinVisionSource.HUGGING_FACE,
        repository = "litert-community/gemma-4-E2B-it-litert-lm",
        estimatedDownloadBytes = 2_589_000_000,
        minimumFreeBytes = 5L * GIB,
        recommendation = "最省运行内存的完整图像/音频档，适合中端手机。",
        reasoningModes = THINKING_TOGGLE,
        defaultReasoningMode = "off",
        files = listOf(
            BuiltinVisionModelFile("gemma-4-E2B-it.litertlm")
        )
    )

    val GLM_EDGE_V_2B = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.GLM_EDGE_V_2B,
        id = "glm-edge-v-2b",
        displayName = "智谱 GLM-Edge-V-2B",
        engine = BuiltinVisionEngine.LLAMA_CPP,
        source = BuiltinVisionSource.MODELSCOPE,
        repository = "ZhipuAI/glm-edge-v-2b-gguf",
        estimatedDownloadBytes = 1_914_000_000,
        minimumFreeBytes = 4L * GIB,
        recommendation = "智谱手机端视觉模型 Q4_K_M；可离线看图和操作界面，当前通用包使用 CPU。",
        supportsVision = true,
        files = listOf(
            BuiltinVisionModelFile("ggml-model-Q4_K_M.gguf"),
            BuiltinVisionModelFile("mmproj-model-f16.gguf")
        )
    )

    val GLM_EDGE_1_5B_CHAT = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.GLM_EDGE_1_5B_CHAT,
        id = "glm-edge-1.5b-chat",
        displayName = "智谱 GLM-Edge-1.5B Chat",
        engine = BuiltinVisionEngine.LLAMA_CPP,
        source = BuiltinVisionSource.HUGGING_FACE,
        repository = "zai-org/glm-edge-1.5b-chat-gguf",
        estimatedDownloadBytes = 1_050_000_000,
        minimumFreeBytes = 3L * GIB,
        recommendation = "官方 Q4_0 GGUF 轻量聊天档；比 0.8B 更适合短对话与检索摘要，纯文本、不读取截图。",
        supportsVision = false,
        files = listOf(
            BuiltinVisionModelFile("ggml-model-Q4_0.gguf")
        )
    )

    val DEEPSEEK_R1_QWEN_7B = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.DEEPSEEK_R1_QWEN_7B,
        id = "deepseek-r1-distill-qwen-7b",
        displayName = "DeepSeek R1 Distill Qwen 7B",
        engine = BuiltinVisionEngine.LITERT_LM,
        source = BuiltinVisionSource.HUGGING_FACE,
        repository = "litert-community/DeepSeek-R1-Distill-Qwen-7B",
        estimatedDownloadBytes = 4_530_000_000,
        minimumFreeBytes = 8L * GIB,
        recommendation = "MIT 许可的 LiteRT-LM 推理包；推理和代码更强，建议 12GB 以上内存。",
        supportsVision = false,
        files = listOf(
            BuiltinVisionModelFile(
                "DeepSeek-R1-Distill-Qwen-7B_q4_block32_ekv4096.litertlm"
            )
        )
    )

    val DEEPSEEK_R1_QWEN_1_5B = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.DEEPSEEK_R1_QWEN_1_5B,
        id = "deepseek-r1-distill-qwen-1.5b",
        displayName = "DeepSeek R1 Distill Qwen 1.5B",
        engine = BuiltinVisionEngine.LITERT_LM,
        source = BuiltinVisionSource.HUGGING_FACE,
        repository = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
        estimatedDownloadBytes = 1_830_000_000,
        minimumFreeBytes = 4L * GIB,
        recommendation = "MIT 许可的 LiteRT-LM Q8 包；最适合手机本地推理与轻量代码任务。",
        supportsVision = false,
        files = listOf(
            BuiltinVisionModelFile(
                "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm"
            )
        )
    )

    val DEEPSEEK_R1_LLAMA_8B = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.DEEPSEEK_R1_LLAMA_8B,
        id = "deepseek-r1-distill-llama-8b",
        displayName = "DeepSeek R1 Distill Llama 8B",
        engine = BuiltinVisionEngine.LLAMA_CPP,
        source = BuiltinVisionSource.HUGGING_FACE,
        repository = "bartowski/DeepSeek-R1-Distill-Llama-8B-GGUF",
        estimatedDownloadBytes = 4_920_000_000,
        minimumFreeBytes = 8L * GIB,
        recommendation = "Q4_K_M 通用 GGUF 推理档；适合高内存手机，当前内置运行时使用 CPU。",
        supportsVision = false,
        files = listOf(
            BuiltinVisionModelFile("DeepSeek-R1-Distill-Llama-8B-Q4_K_M.gguf")
        )
    )

    val DEEPSEEK_CODER_V2_LITE = BuiltinVisionModelDescriptor(
        tier = BuiltinVisionTier.DEEPSEEK_CODER_V2_LITE,
        id = "deepseek-coder-v2-lite",
        displayName = "DeepSeek Coder V2 Lite",
        engine = BuiltinVisionEngine.LLAMA_CPP,
        source = BuiltinVisionSource.HUGGING_FACE,
        repository = "QuantFactory/DeepSeek-Coder-V2-Lite-Instruct-GGUF",
        estimatedDownloadBytes = 6_430_000_000,
        minimumFreeBytes = 10L * GIB,
        recommendation = "16B 总参数 / 2.4B 激活参数的代码 MoE；Q2_K 约 6.4GB，手机端属于实验档。",
        supportsVision = false,
        files = listOf(
            BuiltinVisionModelFile("DeepSeek-Coder-V2-Lite-Instruct.Q2_K.gguf")
        )
    )

    val all: List<BuiltinVisionModelDescriptor> =
        listOf(
            GEMMA_12B_DESKTOP,
            ULTRA_9B,
            CODER_7B,
            PRO_4B,
            LITE_2B,
            GEMMA_E4B,
            GEMMA_E2B,
            GLM_EDGE_V_2B,
            GLM_EDGE_1_5B_CHAT,
            DEEPSEEK_R1_QWEN_7B,
            DEEPSEEK_R1_QWEN_1_5B,
            DEEPSEEK_R1_LLAMA_8B,
            DEEPSEEK_CODER_V2_LITE
        )

    fun descriptor(tier: BuiltinVisionTier): BuiltinVisionModelDescriptor =
        all.first { it.tier == tier }

    fun descriptor(modelId: String): BuiltinVisionModelDescriptor? =
        all.firstOrNull { it.id == modelId.trim() }

    fun modelRoot(context: Context): File =
        File(context.filesDir, "vision-models/$PACKAGE_VERSION")

    fun modelDirectory(context: Context, tier: BuiltinVisionTier): File =
        File(modelRoot(context), descriptor(tier).id)

    /**
     * Deletes only the known retired 0.8B package. This runs on upgrade so a
     * model that is no longer selectable does not silently continue to occupy
     * the user's storage. The canonical-path check makes this safe even if a
     * malformed directory or symlink is present.
     */
    fun removeRetiredModelArtifacts(context: Context) {
        val root = modelRoot(context).canonicalFile
        val retired = File(root, RETIRED_FAST_MODEL_ID).canonicalFile
        if (retired.parentFile != root || !retired.exists()) return
        retired.deleteRecursively()
    }

    fun isInstalled(context: Context, tier: BuiltinVisionTier): Boolean {
        val descriptor = descriptor(tier)
        val directory = modelDirectory(context, tier)
        if (!File(directory, READY_MARKER).isFile) return false
        val installedFiles = readInstalledFiles(directory, descriptor) ?: descriptor.files
        return installedFiles.all { file ->
            File(directory, file.name).let {
                it.isFile && it.length() > 0L && (file.size <= 0L || it.length() == file.size)
            }
        }
    }

    private fun readInstalledFiles(
        directory: File,
        descriptor: BuiltinVisionModelDescriptor
    ): List<BuiltinVisionModelFile>? = runCatching {
        val root = JSONObject(File(directory, INSTALL_MANIFEST).readText(Charsets.UTF_8))
        val entries = root.getJSONArray("files")
        val byName = buildMap {
            repeat(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                val name = entry.getString("name")
                put(
                    name,
                    BuiltinVisionModelFile(
                        name = name,
                        size = entry.getLong("size"),
                        sha256 = entry.getString("sha256")
                    )
                )
            }
        }
        descriptor.files.map { required ->
            byName[required.name] ?: error("安装清单缺少 ${required.name}")
        }
    }.getOrNull()

    fun selectedTier(context: Context): BuiltinVisionTier? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ACTIVE_TIER, null)
            ?.let { stored -> BuiltinVisionTier.entries.firstOrNull { it.name == stored } }

    fun select(context: Context, tier: BuiltinVisionTier?) {
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
        if (tier == null) editor.remove(ACTIVE_TIER) else editor.putString(ACTIVE_TIER, tier.name)
        editor.apply()
    }

    fun activeInstalledModel(context: Context): BuiltinVisionModelDescriptor? {
        val selected = selectedTier(context)
        if (selected != null && descriptor(selected).androidSupported && isInstalled(context, selected)) {
            return descriptor(selected)
        }
        return all.firstOrNull { it.androidSupported && isInstalled(context, it.tier) }
    }

    fun installedModel(
        context: Context,
        modelId: String?
    ): BuiltinVisionModelDescriptor? {
        val normalized = modelId?.trim().orEmpty()
        if (normalized.isBlank() || normalized == "builtin-selected") {
            return activeInstalledModel(context)
        }
        return descriptor(normalized)?.takeIf {
            it.androidSupported && isInstalled(context, it.tier)
        }
    }

    fun installedVisionModel(context: Context): BuiltinVisionModelDescriptor? {
        val active = activeInstalledModel(context)
        if (active?.supportsVision == true) return active
        return all.firstOrNull {
            it.androidSupported && it.supportsVision && isInstalled(context, it.tier)
        }
    }

    fun fastestInstalledTextModel(
        context: Context,
        preferredModelId: String? = null
    ): BuiltinVisionModelDescriptor? {
        descriptor(preferredModelId.orEmpty())
            ?.takeIf {
                it.androidSupported &&
                    !it.supportsVision &&
                    isInstalled(context, it.tier)
            }
            ?.let { return it }
        return all
            .asSequence()
            .filter {
                it.androidSupported &&
                    !it.supportsVision &&
                    isInstalled(context, it.tier)
            }
            .minByOrNull { it.totalBytes }
    }

    fun recommend(totalBytes: Long, availableBytes: Long): BuiltinVisionDeviceRecommendation {
        val recommended = when {
            totalBytes >= 15L * GIB && availableBytes >= 6L * GIB -> BuiltinVisionTier.ULTRA_9B
            totalBytes >= 12L * GIB && availableBytes >= 4L * GIB -> BuiltinVisionTier.CODER_7B
            totalBytes >= 8L * GIB && availableBytes >= 4L * GIB -> BuiltinVisionTier.PRO_4B
            totalBytes >= 6L * GIB && availableBytes >= 2L * GIB -> BuiltinVisionTier.LITE_2B
            totalBytes >= 4L * GIB && availableBytes >= 1_500L * 1024L * 1024L ->
                BuiltinVisionTier.GEMMA_E2B
            else -> null
        }
        val message = when (recommended) {
            BuiltinVisionTier.ULTRA_9B ->
                "16GB 旗舰设备：推荐 Qwen3.5-9B Ultra；追求速度时切回 4B Pro。"
            BuiltinVisionTier.CODER_7B ->
                "12GB 以上设备可选 Coder 7B 写代码；看图和 GUI 建议另装 4B Pro。"
            BuiltinVisionTier.PRO_4B -> "设备内存适合 4B Pro；它的界面理解和坐标定位更稳。"
            BuiltinVisionTier.LITE_2B -> "建议安装 2B Lite；4B Pro 可能因内存不足被系统终止。"
            BuiltinVisionTier.GEMMA_E2B -> "建议安装 Gemma 4 E2B；它针对低内存端侧运行优化。"
            BuiltinVisionTier.GEMMA_E4B -> "建议安装 Gemma 4 E4B。"
            BuiltinVisionTier.GEMMA_12B_DESKTOP ->
                "Gemma 4 12B 当前只在电脑端提供；Android 等待官方移动包。"
            BuiltinVisionTier.GLM_EDGE_V_2B -> "建议安装智谱 GLM-Edge-V-2B。"
            BuiltinVisionTier.GLM_EDGE_1_5B_CHAT -> "建议安装智谱 GLM-Edge-1.5B Chat。"
            BuiltinVisionTier.DEEPSEEK_R1_QWEN_7B -> "建议安装 DeepSeek R1 Distill Qwen 7B。"
            BuiltinVisionTier.DEEPSEEK_R1_QWEN_1_5B ->
                "建议安装 DeepSeek R1 Distill Qwen 1.5B。"
            BuiltinVisionTier.DEEPSEEK_R1_LLAMA_8B ->
                "DeepSeek R1 Distill Llama 8B 建议在高内存设备上尝试。"
            BuiltinVisionTier.DEEPSEEK_CODER_V2_LITE ->
                "DeepSeek Coder V2 Lite 建议仅在高内存设备上尝试。"
            null -> "当前可用内存偏低，建议使用用户 API；仍可手动尝试安装 Lite。"
        }
        return BuiltinVisionDeviceRecommendation(
            recommendedTier = recommended,
            totalMemoryBytes = totalBytes,
            availableMemoryBytes = availableBytes,
            message = message
        )
    }
}

class BuiltinVisionModelManager(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(snapshot())
    private var installJob: Job? = null
    private var lastProgressUpdateNanos: Long = 0L

    val state: StateFlow<BuiltinVisionModelState> = mutableState.asStateFlow()

    fun refresh() {
        val previous = mutableState.value
        mutableState.value = snapshot(
            message = previous.message,
            error = previous.error
        )
    }

    @Synchronized
    fun install(tier: BuiltinVisionTier) {
        if (installJob?.isActive == true) return
        val catalogDescriptor = BuiltinVisionModels.descriptor(tier)
        mutableState.value = snapshot(
            message = "正在读取 ${catalogDescriptor.displayName} 官方文件清单…"
        ).copy(
            installingTier = tier,
            totalBytes = catalogDescriptor.totalBytes
        )
        installJob = scope.launch {
            try {
                require(catalogDescriptor.androidSupported) {
                    catalogDescriptor.unavailableReason.ifBlank { "当前 Android 运行时不支持此模型" }
                }
                require(Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) {
                    "内置本地模型运行时目前仅支持 arm64-v8a"
                }
                val descriptor = resolveRemoteDescriptor(catalogDescriptor)
                val modelDirectory = BuiltinVisionModels.modelDirectory(appContext, tier)
                requireFreeSpace(modelDirectory, descriptor.minimumFreeBytes)
                modelDirectory.mkdirs()
                File(modelDirectory, BuiltinVisionModels.READY_MARKER).delete()
                File(modelDirectory, BuiltinVisionModels.INSTALL_MANIFEST).delete()
                writeInstallationManifest(modelDirectory, descriptor)
                val existingBytes = descriptor.files.sumOf { file ->
                    val target = File(modelDirectory, file.name)
                    when {
                        target.isFile && target.length() == file.size -> file.size
                        else -> File(modelDirectory, "${file.name}.part")
                            .length()
                            .coerceAtMost(file.size)
                    }
                }
                updateInstalling(
                    descriptor = descriptor,
                    downloadedBytes = existingBytes,
                    message = "正在准备 ${descriptor.displayName}…"
                )
                var completedBefore = 0L
                descriptor.files.forEachIndexed { index, file ->
                    val target = File(modelDirectory, file.name)
                    if (target.isFile && target.length() == file.size &&
                        sha256(target).equals(file.sha256, ignoreCase = true)
                    ) {
                        completedBefore += file.size
                        updateInstalling(
                            descriptor,
                            completedBefore,
                            "已校验 ${index + 1}/${descriptor.files.size}：${file.name}"
                        )
                    } else {
                        target.delete()
                        downloadFile(
                            descriptor = descriptor,
                            file = file,
                            directory = modelDirectory,
                            completedBefore = completedBefore,
                            fileIndex = index
                        )
                        completedBefore += file.size
                    }
                }
                val marker = File(modelDirectory, BuiltinVisionModels.READY_MARKER)
                marker.writeText(
                    "${descriptor.id}\n${descriptor.resolvedRevision}\n${descriptor.totalBytes}\n",
                    Charsets.UTF_8
                )
                // A text-only assistant model must not silently replace the user's selected
                // local vision/coding model.
                if (descriptor.supportsVision) {
                    BuiltinVisionModels.select(appContext, tier)
                }
                BuiltinVisionRuntime.release()
                mutableState.value = snapshot(
                    message = if (descriptor.supportsVision) {
                        "${descriptor.displayName} 已安装，可用于离线聊天、看图和 GUI 操作。"
                    } else {
                        "${descriptor.displayName} 已安装，可用于离线聊天和代码任务。"
                    }
                )
            } catch (_: CancellationException) {
                mutableState.value = snapshot(
                    message = "已暂停下载；断点文件已保留。"
                )
            } catch (error: Throwable) {
                mutableState.value = snapshot(
                    message = "模型安装未完成；下次点击会从断点继续。",
                    error = error.message ?: error.javaClass.simpleName
                )
            } finally {
                installJob = null
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        mutableState.value = snapshot(message = "已暂停下载；断点文件已保留。")
    }

    fun delete(tier: BuiltinVisionTier) {
        if (installJob?.isActive == true && mutableState.value.installingTier == tier) {
            cancelInstall()
        }
        scope.launch {
            BuiltinVisionRuntime.release()
            val directory = BuiltinVisionModels.modelDirectory(appContext, tier)
            check(directory.canonicalPath.startsWith(BuiltinVisionModels.modelRoot(appContext).canonicalPath)) {
                "拒绝删除模型目录之外的路径"
            }
            if (directory.exists() && !directory.deleteRecursively()) {
                mutableState.value = snapshot(error = "无法删除 ${BuiltinVisionModels.descriptor(tier).displayName}")
            } else {
                if (BuiltinVisionModels.selectedTier(appContext) == tier) {
                    BuiltinVisionModels.select(
                        appContext,
                        BuiltinVisionModels.all
                            .firstOrNull {
                                it.tier != tier &&
                                    it.androidSupported &&
                                    BuiltinVisionModels.isInstalled(appContext, it.tier)
                            }
                            ?.tier
                    )
                }
                mutableState.value = snapshot(
                    message = "${BuiltinVisionModels.descriptor(tier).displayName} 已删除。"
                )
            }
        }
    }

    fun select(tier: BuiltinVisionTier) {
        require(BuiltinVisionModels.descriptor(tier).androidSupported) {
            BuiltinVisionModels.descriptor(tier).unavailableReason
        }
        require(BuiltinVisionModels.isInstalled(appContext, tier)) {
            "${BuiltinVisionModels.descriptor(tier).displayName} 尚未安装"
        }
        scope.launch {
            BuiltinVisionRuntime.release()
            BuiltinVisionModels.select(appContext, tier)
            mutableState.value = snapshot(
                message = "已选择 ${BuiltinVisionModels.descriptor(tier).displayName}。"
            )
        }
    }

    private fun resolveRemoteDescriptor(
        descriptor: BuiltinVisionModelDescriptor
    ): BuiltinVisionModelDescriptor = when (descriptor.source) {
        BuiltinVisionSource.MODELSCOPE -> resolveModelScopeDescriptor(descriptor)
        BuiltinVisionSource.HUGGING_FACE -> resolveHuggingFaceDescriptor(descriptor)
    }

    private fun resolveModelScopeDescriptor(
        descriptor: BuiltinVisionModelDescriptor
    ): BuiltinVisionModelDescriptor {
        val root = requestJson(
            "https://modelscope.cn/api/v1/models/${descriptor.repository}/repo/files?Recursive=true"
        )
        check(root.optBoolean("Success") && root.optInt("Code") == 200) {
            "ModelScope 官方清单读取失败：${root.optString("Message", "未知错误")}"
        }
        val data = root.getJSONObject("Data")
        val remoteFiles = data.getJSONArray("Files")
        val byPath = buildMap {
            repeat(remoteFiles.length()) { index ->
                val entry = remoteFiles.getJSONObject(index)
                if (entry.optString("Type") == "blob") {
                    put(entry.getString("Path"), entry)
                }
            }
        }
        val files = descriptor.files.map { required ->
            val entry = byPath[required.name]
                ?: error("ModelScope 官方清单缺少 ${required.name}")
            manifestFile(
                name = required.name,
                size = entry.getLong("Size"),
                sha256 = entry.getString("Sha256")
            )
        }
        val revision = data.optJSONObject("LatestCommitter")
            ?.optString("ShortId")
            .orEmpty()
            .ifBlank { "master" }
        return descriptor.copy(files = files, resolvedRevision = revision)
    }

    private fun resolveHuggingFaceDescriptor(
        descriptor: BuiltinVisionModelDescriptor
    ): BuiltinVisionModelDescriptor {
        val model = requestJson("https://huggingface.co/api/models/${descriptor.repository}")
        val revision = model.optString("sha").ifBlank { "main" }
        val tree = requestJsonArray(
            "https://huggingface.co/api/models/${descriptor.repository}/tree/" +
                "$revision?recursive=true&expand=true"
        )
        val byPath = buildMap {
            repeat(tree.length()) { index ->
                val entry = tree.getJSONObject(index)
                if (entry.optString("type") == "file") {
                    put(entry.getString("path"), entry)
                }
            }
        }
        val files = descriptor.files.map { required ->
            val entry = byPath[required.name]
                ?: error("Hugging Face 官方清单缺少 ${required.name}")
            val lfs = entry.optJSONObject("lfs")
                ?: error("Hugging Face 未提供 ${required.name} 的 SHA-256")
            manifestFile(
                name = required.name,
                size = lfs.optLong("size", entry.optLong("size")),
                sha256 = huggingFaceLfsSha256(lfs)
            )
        }
        return descriptor.copy(files = files, resolvedRevision = revision)
    }

    private fun requestJson(url: String): JSONObject =
        JSONObject(requestText(url))

    private fun requestJsonArray(url: String): JSONArray =
        JSONArray(requestText(url))

    private fun requestText(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "读取官方模型清单失败：HTTP ${response.code}"
            }
            return response.body?.string() ?: error("官方模型清单为空")
        }
    }

    private fun manifestFile(name: String, size: Long, sha256: String): BuiltinVisionModelFile {
        require(size > 0L) { "$name 的官方大小无效" }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "$name 的官方 SHA-256 无效" }
        return BuiltinVisionModelFile(name, size, sha256.lowercase())
    }

    private fun writeInstallationManifest(
        directory: File,
        descriptor: BuiltinVisionModelDescriptor
    ) {
        val files = JSONArray()
        descriptor.files.forEach { file ->
            files.put(
                JSONObject()
                    .put("name", file.name)
                    .put("size", file.size)
                    .put("sha256", file.sha256)
            )
        }
        val root = JSONObject()
            .put("version", 1)
            .put("repository", descriptor.repository)
            .put("revision", descriptor.resolvedRevision)
            .put("files", files)
        val target = File(directory, BuiltinVisionModels.INSTALL_MANIFEST)
        val partial = File(directory, "${BuiltinVisionModels.INSTALL_MANIFEST}.part")
        partial.writeText(root.toString(), Charsets.UTF_8)
        target.delete()
        check(partial.renameTo(target)) { "无法保存模型安装清单" }
    }

    private fun downloadFile(
        descriptor: BuiltinVisionModelDescriptor,
        file: BuiltinVisionModelFile,
        directory: File,
        completedBefore: Long,
        fileIndex: Int
    ) {
        val partial = File(directory, "${file.name}.part")
        if (partial.length() > file.size) partial.delete()
        if (partial.length() == file.size) {
            if (sha256(partial).equals(file.sha256, ignoreCase = true)) {
                val target = File(directory, file.name)
                target.delete()
                check(partial.renameTo(target)) { "无法启用已校验的 ${file.name}" }
                updateInstalling(
                    descriptor,
                    completedBefore + file.size,
                    "已校验 ${fileIndex + 1}/${descriptor.files.size}：${file.name}"
                )
                return
            }
            partial.delete()
        }
        val offset = partial.length()
        val request = Request.Builder()
            .url(descriptor.fileUrl(file))
            .apply {
                if (offset > 0L) header("Range", "bytes=$offset-")
            }
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "下载 ${file.name} 失败：HTTP ${response.code}"
            }
            val append = offset > 0L && response.code == 206
            val startingLength = if (append) offset else 0L
            if (!append && partial.exists()) partial.delete()
            val body = response.body ?: error("下载 ${file.name} 时服务端未返回内容")
            RandomAccessFile(partial, "rw").use { output ->
                if (append) output.seek(offset) else output.setLength(0)
                body.byteStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    var written = startingLength
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        updateInstalling(
                            descriptor = descriptor,
                            downloadedBytes = completedBefore + written,
                            message = "下载 ${fileIndex + 1}/${descriptor.files.size}：${file.name}",
                            force = false
                        )
                    }
                }
            }
        }
        check(partial.length() == file.size) {
            "${file.name} 大小不正确：${partial.length()} / ${file.size}"
        }
        check(sha256(partial).equals(file.sha256, ignoreCase = true)) {
            "${file.name} SHA-256 校验失败"
        }
        val target = File(directory, file.name)
        target.delete()
        check(partial.renameTo(target)) { "无法启用已校验的 ${file.name}" }
        updateInstalling(
            descriptor,
            completedBefore + file.size,
            "已校验 ${fileIndex + 1}/${descriptor.files.size}：${file.name}"
        )
    }

    private fun updateInstalling(
        descriptor: BuiltinVisionModelDescriptor,
        downloadedBytes: Long,
        message: String,
        force: Boolean = true
    ) {
        val now = System.nanoTime()
        if (!force && now - lastProgressUpdateNanos < 250_000_000L) return
        lastProgressUpdateNanos = now
        val installed = installedTiers()
        val selected = BuiltinVisionModels.selectedTier(appContext)
        mutableState.value = BuiltinVisionModelState(
            installedTiers = installed,
            activeTier = selected?.takeIf {
                it in installed && BuiltinVisionModels.descriptor(it).androidSupported
            } ?: BuiltinVisionModels.all
                .firstOrNull { it.androidSupported && it.tier in installed }
                ?.tier,
            installingTier = descriptor.tier,
            downloadedBytes = downloadedBytes.coerceAtMost(descriptor.totalBytes),
            totalBytes = descriptor.totalBytes,
            message = message,
            recommendation = deviceRecommendation()
        )
    }

    private fun snapshot(
        message: String = "",
        error: String? = null
    ): BuiltinVisionModelState {
        val installed = installedTiers()
        val selected = BuiltinVisionModels.selectedTier(appContext)
        return BuiltinVisionModelState(
            installedTiers = installed,
            activeTier = selected?.takeIf {
                it in installed && BuiltinVisionModels.descriptor(it).androidSupported
            } ?: BuiltinVisionModels.all
                .firstOrNull { it.androidSupported && it.tier in installed }
                ?.tier,
            message = message,
            error = error,
            recommendation = deviceRecommendation()
        )
    }

    private fun installedTiers(): Set<BuiltinVisionTier> =
        BuiltinVisionModels.all
            .filter { BuiltinVisionModels.isInstalled(appContext, it.tier) }
            .mapTo(linkedSetOf()) { it.tier }

    private fun deviceRecommendation(): BuiltinVisionDeviceRecommendation {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return BuiltinVisionModels.recommend(info.totalMem, info.availMem)
    }

    private fun requireFreeSpace(directory: File, requiredBytes: Long) {
        directory.parentFile?.mkdirs()
        val available = StatFs(directory.parentFile?.absolutePath ?: appContext.filesDir.absolutePath)
            .availableBytes
        require(available >= requiredBytes) {
            "存储空间不足：至少需要 ${formatBytes(requiredBytes)}，当前可用 ${formatBytes(available)}"
        }
    }

    override fun close() {
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        @Volatile private var sharedInstance: BuiltinVisionModelManager? = null

        fun shared(context: Context): BuiltinVisionModelManager =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: BuiltinVisionModelManager(context.applicationContext)
                    .also { sharedInstance = it }
            }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun formatBytes(bytes: Long): String {
            val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return "%.1f GB".format(gib)
        }
    }
}
