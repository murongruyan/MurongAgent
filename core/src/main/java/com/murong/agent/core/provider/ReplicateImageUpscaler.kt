package com.murong.agent.core.provider

import android.graphics.BitmapFactory
import android.util.Base64
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ImageUpscaleRequest(
    val image: ByteArray,
    val mimeType: String,
    val model: String = "nightmareai/real-esrgan",
    val scale: Int = 4,
)

data class ImageUpscaledResult(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
    val width: Int,
    val height: Int,
)

sealed interface ImageUpscaleProgress {
    data class Stage(val label: String) : ImageUpscaleProgress
}

class ImageUpscaleException(message: String, val statusCode: Int? = null) : Exception(message)

/** Real hosted Real-ESRGAN route. It never treats a resized bitmap as 4K. */
class ReplicateImageUpscaler(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
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

    suspend fun upscale(
        request: ImageUpscaleRequest,
        apiKey: String,
        baseUrl: String = "https://api.replicate.com/v1",
        onProgress: (ImageUpscaleProgress) -> Unit = {},
    ): ImageUpscaledResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Real-ESRGAN API Key 不能为空" }
        require(request.image.isNotEmpty()) { "待超分图片为空" }
        require(request.image.size <= MAX_INPUT_BYTES) { "待超分图片超过本地接收上限" }
        val scale = request.scale.coerceIn(2, 4)
        val model = request.model.trim().ifBlank { "nightmareai/real-esrgan" }
        val endpoint = predictionsEndpoint(baseUrl, model)
        val dataUri = "data:${normalizeMime(request.mimeType)};base64," +
            Base64.encodeToString(request.image, Base64.NO_WRAP)
        val payload = buildJsonObject {
            put("input", buildJsonObject {
                put("image", dataUri)
                put("scale", scale)
            })
        }
        onProgress(ImageUpscaleProgress.Stage("正在提交真实 4K 超分任务"))
        val prediction = executeJson(
            request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Token ${apiKey.trim()}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "Murong-Agent/1.37")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val id = prediction["id"]?.jsonPrimitive?.contentOrNull
            ?: throw ImageUpscaleException("超分服务没有返回任务 ID")
        val getUrl = prediction["urls"]?.jsonObject?.get("get")?.jsonPrimitive?.contentOrNull
            ?: throw ImageUpscaleException("超分服务没有返回轮询地址")
        validatePollUrl(getUrl, baseUrl)
        val deadline = System.currentTimeMillis() + MAX_POLL_MILLIS
        var lastStatus = ""
        var completed: ImageUpscaledResult? = null
        while (true) {
            coroutineContext.ensureActive()
            if (System.currentTimeMillis() > deadline) {
                throw ImageUpscaleException("真实 4K 超分任务超时（$id）")
            }
            val state = executeJson(
                Request.Builder()
                    .url(getUrl)
                    .header("Authorization", "Token ${apiKey.trim()}")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Murong-Agent/1.37")
                    .get()
                    .build(),
            )
            val status = state["status"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            if (status != lastStatus) {
                lastStatus = status
                onProgress(ImageUpscaleProgress.Stage(upscaleStatusLabel(status)))
            }
            when (status) {
                "succeeded" -> {
                    val output = extractOutputUrl(state["output"])
                        ?: throw ImageUpscaleException("超分服务没有返回图片地址")
                    val image = downloadOutput(output, baseUrl)
                    if (image.width < MIN_4K_EDGE && image.height < MIN_4K_EDGE) {
                        throw ImageUpscaleException("超分结果未达到 4K：${image.width}×${image.height}")
                    }
                    onProgress(ImageUpscaleProgress.Stage("真实 4K 超分完成：${image.width}×${image.height}"))
                    completed = image
                    break
                }
                "failed" -> throw ImageUpscaleException(
                    state["error"]?.jsonPrimitive?.contentOrNull?.take(MAX_ERROR_CHARS)
                        ?: "Real-ESRGAN 超分失败",
                )
                "canceled", "cancelled" -> throw CancellationException("超分任务已取消")
            }
            delay(POLL_INTERVAL_MILLIS)
        }
        completed ?: throw ImageUpscaleException("超分任务没有返回结果")
    }

    private fun executeJson(request: Request): JsonObject {
        val call = httpClient.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ImageUpscaleException(
                        "超分服务请求失败${response.code.let { "（HTTP $it）" }}",
                        response.code,
                    )
                }
                return runCatching { json.parseToJsonElement(body).jsonObject }
                    .getOrElse { throw ImageUpscaleException("超分服务返回了无法解析的数据") }
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun downloadOutput(url: String, baseUrl: String): ImageUpscaledResult {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val base = runCatching { URI(baseUrl) }.getOrNull()
        val isReplicateDelivery = uri?.scheme.equals("https", ignoreCase = true) &&
            (host == "replicate.delivery" || host.endsWith(".replicate.delivery"))
        val isLoopbackTest = base?.host in setOf("127.0.0.1", "localhost") &&
            uri?.scheme.equals(base?.scheme, ignoreCase = true) && uri?.authority == base?.authority
        require(isReplicateDelivery || isLoopbackTest) {
            "超分服务返回了不受信任的图片地址"
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/*")
            .header("User-Agent", "Murong-Agent/1.37")
            .get()
            .build()
        val call = httpClient.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw ImageUpscaleException("下载超分结果失败（HTTP ${response.code}）", response.code)
                val body = response.body ?: throw ImageUpscaleException("超分结果为空")
                if (body.contentLength() > MAX_OUTPUT_BYTES) throw ImageUpscaleException("超分结果超过本地接收上限")
                val bytes = body.bytes()
                if (bytes.size > MAX_OUTPUT_BYTES) throw ImageUpscaleException("超分结果超过本地接收上限")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val format = detectFormat(bytes)
                    ?: throw ImageUpscaleException("超分服务返回了无效图片")
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImageUpscaleException("超分结果尺寸无效")
                return ImageUpscaledResult(bytes, format.first, format.second, bounds.outWidth, bounds.outHeight)
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_INPUT_BYTES = 32 * 1024 * 1024
        private const val MAX_OUTPUT_BYTES = 64 * 1024 * 1024
        private const val MAX_POLL_MILLIS = 15 * 60 * 1000L
        private const val POLL_INTERVAL_MILLIS = 1_500L
        private const val MIN_4K_EDGE = 3_840
        private const val MAX_ERROR_CHARS = 500

        fun predictionsEndpoint(baseUrl: String, model: String): String {
            val root = baseUrl.trim().trimEnd('/')
            val uri = runCatching { URI(root) }.getOrNull()
            require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) { "超分 Base URL 无效" }
            val normalized = if (root.endsWith("/v1", ignoreCase = true)) root else "$root/v1"
            val parts = model.trim().trim('/').split('/')
            require(parts.size == 2 && parts.all { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }) { "超分模型必须是 owner/model" }
            return "$normalized/models/${parts[0]}/${parts[1]}/predictions"
        }

        private fun validatePollUrl(value: String, baseUrl: String) {
            val uri = runCatching { URI(value) }.getOrNull()
            val base = runCatching { URI(baseUrl) }.getOrNull()
            require(uri?.scheme.equals(base?.scheme, ignoreCase = true) && uri?.authority.equals(base?.authority, ignoreCase = true)) {
                "超分服务返回了不受信任的轮询地址"
            }
        }

        private fun extractOutputUrl(element: JsonElement?): String? {
            if (element == null) return null
            val primitive = element.jsonPrimitiveOrNull()?.contentOrNull
            if (primitive?.startsWith("https://") == true) return primitive
            if (element is kotlinx.serialization.json.JsonArray) {
                for (child in element) extractOutputUrl(child)?.let { return it }
            }
            if (element is JsonObject) {
                for (child in element.values) extractOutputUrl(child)?.let { return it }
            }
            return null
        }

        private fun JsonElement.jsonPrimitiveOrNull() = runCatching { jsonPrimitive }.getOrNull()

        private fun normalizeMime(value: String): String = when (value.lowercase()) {
            "image/jpeg", "image/webp", "image/png" -> value.lowercase()
            else -> "image/png"
        }

        private fun detectFormat(bytes: ByteArray): Pair<String, String>? = when {
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png" to "png"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg" to "jpg"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp" to "webp"
            else -> null
        }

        private fun upscaleStatusLabel(status: String) = when (status) {
            "starting" -> "正在启动真实 4K 超分"
            "processing" -> "Real-ESRGAN 正在处理图片"
            "succeeded" -> "正在下载 4K 超分结果"
            else -> "超分任务状态：${status.ifBlank { "等待中" }}"
        }
    }
}
