package com.murong.agent.core.provider

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
enum class ImageGenerationQuality(val apiValue: String, val displayName: String) {
    AUTO("auto", "自动"),
    LOW("low", "草稿"),
    MEDIUM("medium", "标准"),
    HIGH("high", "高质量"),
}

@Serializable
enum class ImageGenerationFormat(val apiValue: String, val mimeType: String, val extension: String) {
    PNG("png", "image/png", "png"),
    JPEG("jpeg", "image/jpeg", "jpg"),
    WEBP("webp", "image/webp", "webp"),
}

@Serializable
data class ImageGenerationSettings(
    val model: String = "gpt-image-2",
    val size: String = "1024x1024",
    val quality: ImageGenerationQuality = ImageGenerationQuality.AUTO,
    val format: ImageGenerationFormat = ImageGenerationFormat.PNG,
    val compression: Int = 90,
    val partialImages: Int = 2,
)

data class ImageGenerationRequest(
    val prompt: String,
    val settings: ImageGenerationSettings = ImageGenerationSettings(),
)

data class GeneratedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
    val revisedPrompt: String? = null,
)

sealed interface ImageGenerationProgress {
    data class Stage(val label: String) : ImageGenerationProgress
    data class PartialImage(val image: GeneratedImage, val index: Int) : ImageGenerationProgress
}

class ImageGenerationException(
    val statusCode: Int? = null,
    val errorCode: String? = null,
    message: String,
) : Exception(message)

/**
 * OpenAI Image API transport shared by official OpenAI and explicitly configured
 * OpenAI-compatible image endpoints. The caller owns persistence and UI state.
 */
class OpenAIImageGenerationClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var activeCall: Call? = null

    fun cancelActive() {
        activeCall?.cancel()
        activeCall = null
    }

    suspend fun generate(
        request: ImageGenerationRequest,
        apiKey: String,
        baseUrl: String,
        onProgress: (ImageGenerationProgress) -> Unit = {},
    ): GeneratedImage {
        require(request.prompt.isNotBlank()) { "生图提示词不能为空" }
        require(request.prompt.length <= MAX_PROMPT_CHARS) { "生图提示词不能超过 $MAX_PROMPT_CHARS 字符" }
        val endpoint = imageGenerationEndpoint(baseUrl)
        onProgress(ImageGenerationProgress.Stage("正在连接生图模型"))
        return try {
            execute(request, apiKey, endpoint, stream = true, onProgress = onProgress)
        } catch (error: ImageGenerationException) {
            if (!shouldRetryWithoutStreaming(error)) throw error
            onProgress(ImageGenerationProgress.Stage("上游不支持局部预览，继续生成完整图片"))
            execute(request, apiKey, endpoint, stream = false, onProgress = onProgress)
        } finally {
            activeCall = null
        }
    }

    private suspend fun execute(
        request: ImageGenerationRequest,
        apiKey: String,
        endpoint: String,
        stream: Boolean,
        onProgress: (ImageGenerationProgress) -> Unit,
    ): GeneratedImage = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val payload = buildPayload(request, stream)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .header("User-Agent", "Murong-Agent/1.37")
            .apply {
                apiKey.trim().takeIf(String::isNotEmpty)?.let { key ->
                    if (endpoint.contains(".openai.azure.com", ignoreCase = true)) {
                        header("api-key", key)
                    } else {
                        header("Authorization", "Bearer $key")
                    }
                }
            }
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = httpClient.newCall(httpRequest)
        activeCall = call
        call.execute().use { response ->
            val body = response.body ?: throw ImageGenerationException(message = "生图服务返回了空响应")
            if (!response.isSuccessful) {
                throw parseError(response.code, body.string())
            }
            val contentType = response.header("Content-Type").orEmpty()
            val result = if (stream && contentType.contains("text/event-stream", ignoreCase = true)) {
                parseStream(body.byteStream(), request.settings.format, onProgress)
            } else {
                parseJsonResponse(body.string(), request.settings.format)
            }
            onProgress(ImageGenerationProgress.Stage("图片生成完成"))
            result
        }
    }

    private fun buildPayload(request: ImageGenerationRequest, stream: Boolean): JsonObject {
        val settings = request.settings
        return buildJsonObject {
            put("model", settings.model.trim().ifBlank { "gpt-image-2" })
            put("prompt", request.prompt.trim())
            put("size", normalizeImageSize(settings.size))
            put("quality", settings.quality.apiValue)
            put("output_format", settings.format.apiValue)
            if (settings.format != ImageGenerationFormat.PNG) {
                put("output_compression", settings.compression.coerceIn(0, 100))
            }
            if (stream) {
                put("stream", true)
                put("partial_images", settings.partialImages.coerceIn(0, 3))
            }
        }
    }

    private fun parseStream(
        input: InputStream,
        requestedFormat: ImageGenerationFormat,
        onProgress: (ImageGenerationProgress) -> Unit,
    ): GeneratedImage {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        var latest: GeneratedImage? = null
        var eventIndex = 0
        reader.useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (!line.startsWith("data:")) return@forEach
                val payload = line.removePrefix("data:").trim()
                if (payload.isBlank() || payload == "[DONE]") return@forEach
                val event = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
                    ?: return@forEach
                val type = event["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (type == "error") {
                    throw parseError(null, payload)
                }
                val encoded = event["b64_json"]?.jsonPrimitive?.contentOrNull
                    ?: event["partial_image_b64"]?.jsonPrimitive?.contentOrNull
                    ?: return@forEach
                val image = decodeGeneratedImage(
                    encoded = encoded,
                    requestedFormat = requestedFormat,
                    revisedPrompt = event["revised_prompt"]?.jsonPrimitive?.contentOrNull,
                )
                latest = image
                eventIndex = event["partial_image_index"]?.jsonPrimitive?.intOrNull ?: (eventIndex + 1)
                onProgress(ImageGenerationProgress.PartialImage(image, eventIndex))
            }
        }
        return latest ?: throw ImageGenerationException(message = "生图流结束但没有返回图片")
    }

    private fun parseJsonResponse(payload: String, requestedFormat: ImageGenerationFormat): GeneratedImage {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse { throw ImageGenerationException(message = "生图服务返回了无法解析的数据") }
        root["error"]?.let { throw parseError(null, payload) }
        val first = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ImageGenerationException(message = "生图服务没有返回图片")
        val encoded = first["b64_json"]?.jsonPrimitive?.contentOrNull
            ?: throw ImageGenerationException(message = "当前上游只返回图片 URL，暂不接受未经校验的跨站下载")
        return decodeGeneratedImage(
            encoded = encoded,
            requestedFormat = requestedFormat,
            revisedPrompt = first["revised_prompt"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun decodeGeneratedImage(
        encoded: String,
        requestedFormat: ImageGenerationFormat,
        revisedPrompt: String?,
    ): GeneratedImage {
        if (encoded.length > MAX_BASE64_CHARS) {
            throw ImageGenerationException(message = "生成图片超过本地接收上限")
        }
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .getOrElse { throw ImageGenerationException(message = "生图服务返回了无效图片数据") }
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
            throw ImageGenerationException(message = "生成图片为空或超过本地接收上限")
        }
        val detected = detectImageFormat(bytes) ?: requestedFormat
        return GeneratedImage(
            bytes = bytes,
            mimeType = detected.mimeType,
            extension = detected.extension,
            revisedPrompt = revisedPrompt?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    private fun parseError(statusCode: Int?, body: String): ImageGenerationException {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull
        val type = error?.get("type")?.jsonPrimitive?.contentOrNull
        val upstream = error?.get("message")?.jsonPrimitive?.contentOrNull
        val message = when {
            code == "moderation_blocked" -> "图片生成请求未通过内容安全检查，请修改提示词后重试"
            statusCode == 401 || statusCode == 403 -> "生图连接鉴权失败，请检查 API Key 和账户权限"
            statusCode == 429 -> "生图额度不足或请求过于频繁，请稍后重试"
            statusCode != null && statusCode >= 500 -> "生图服务暂时不可用，请稍后重试"
            !upstream.isNullOrBlank() -> upstream.take(MAX_ERROR_CHARS)
            else -> "图片生成失败${statusCode?.let { "（HTTP $it）" }.orEmpty()}"
        }
        return ImageGenerationException(statusCode, code ?: type, message)
    }

    private fun shouldRetryWithoutStreaming(error: ImageGenerationException): Boolean {
        if (error.statusCode !in setOf(400, 404, 405, 415, 422)) return false
        val text = error.message.orEmpty().lowercase()
        return error.errorCode !in setOf("moderation_blocked", "image_generation_user_error") &&
            ("stream" in text || "partial" in text || error.statusCode in setOf(404, 405, 415))
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_PROMPT_CHARS = 32_000
        private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
        private const val MAX_BASE64_CHARS = 48 * 1024 * 1024
        private const val MAX_ERROR_CHARS = 600

        fun imageGenerationEndpoint(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "生图 Base URL 不能为空" }
            val uri = runCatching { URI(trimmed) }.getOrNull()
            require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
                "生图 Base URL 无效"
            }
            val suffixes = listOf("/images/generations", "/chat/completions", "/responses")
            val base = suffixes.firstOrNull { trimmed.endsWith(it, ignoreCase = true) }
                ?.let { trimmed.dropLast(it.length) }
                ?: trimmed
            val withVersion = if (base.endsWith("/v1", ignoreCase = true) || "/v1/" in base.lowercase()) {
                base
            } else {
                "$base/v1"
            }
            return withVersion.trimEnd('/') + "/images/generations"
        }

        internal fun normalizeImageSize(size: String): String {
            val value = size.trim().lowercase()
            if (value == "auto") return value
            val match = Regex("""^(\d{2,4})x(\d{2,4})$""").matchEntire(value)
                ?: return "1024x1024"
            val width = match.groupValues[1].toInt()
            val height = match.groupValues[2].toInt()
            if (width !in 256..3840 || height !in 256..3840) return "1024x1024"
            if (width % 16 != 0 || height % 16 != 0) return "1024x1024"
            val pixels = width.toLong() * height.toLong()
            if (pixels !in 655_360L..8_294_400L) return "1024x1024"
            val ratio = maxOf(width, height).toDouble() / minOf(width, height).toDouble()
            if (ratio > 3.0) return "1024x1024"
            return "${width}x$height"
        }

        private fun detectImageFormat(bytes: ByteArray): ImageGenerationFormat? = when {
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            ) -> ImageGenerationFormat.PNG
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
                ImageGenerationFormat.JPEG
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> ImageGenerationFormat.WEBP
            else -> null
        }
    }
}
