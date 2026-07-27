package com.murong.agent.ui.settings

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.isLocalModelBaseUrl
import com.murong.agent.core.provider.ModelProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ProviderModelCatalogUiState(
    val providerId: String,
    val models: List<String> = emptyList(),
    val sourceLabel: String = "内置推荐",
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val syncedAt: Long? = null
)

data class ProviderModelCatalogFetchResult(
    val models: List<String>,
    val sourceLabel: String,
    val syncedAt: Long = System.currentTimeMillis()
)

private val PROVIDER_MODEL_CATALOG_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val PROVIDER_MODEL_CATALOG_HTTP = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

internal fun builtinProviderModels(providerId: String): List<String> = when (providerId) {
    "deepseek" -> listOf("deepseek-v4-flash", "deepseek-v4-pro")
    "openai-compatible" -> listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5")
    "claude" -> listOf("claude-fable-5", "claude-opus-4-8")
    "kimi" -> listOf("kimi-k3")
    "glm" -> listOf("glm-5.2")
    "qwen" -> listOf("qwen3.8-max-preview")
    "minimax" -> listOf("MiniMax-M3")
    "grok" -> listOf("grok-4.5")
    "mimo" -> listOf("mimo-v2.5", "mimo-v2.5-pro")
    "hy3" -> listOf("hy3-preview")
    "gemini" -> listOf("gemini-3.6-flash")
    else -> emptyList()
}

internal fun mergeProviderModelCandidates(
    providerId: String,
    currentModel: String,
    fetchedModels: List<String> = emptyList()
): List<String> {
    return linkedSetOf<String>().apply {
        currentModel.trim().takeIf { it.isNotBlank() }?.let(::add)
        // Once the provider has answered, its catalog is authoritative.  The
        // older implementation always merged static examples here, which made
        // OpenAI and Claude look as if they were returning hard-coded models.
        if (fetchedModels.isEmpty()) addAll(builtinProviderModels(providerId))
        fetchedModels
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach(::add)
    }.toList()
}

internal fun ProviderConfig.withProviderModelSelection(providerId: String, modelId: String): ProviderConfig {
    val normalizedModel = modelId.trim()
    if (normalizedModel.isBlank()) return this
    return when (providerId) {
        "deepseek" -> {
            val preset = when (normalizedModel) {
                "deepseek-v4-flash" -> "flash"
                "deepseek-v4-pro" -> "pro"
                else -> "custom"
            }
            copy(
                deepseekModelPreset = preset,
                deepseekModel = normalizedModel
            ).withModelAutoSelection(providerId, false)
        }

        "openai-compatible" -> copy(
            openaiModel = normalizedModel
        ).withModelAutoSelection(providerId, false)

        "claude" -> copy(
            claudeModel = normalizedModel
        ).withModelAutoSelection(providerId, false)

        else -> updateActiveRelay(providerId) {
            it.copy(model = normalizedModel)
        }.withModelAutoSelection(providerId, false)
    }
}

internal fun fetchProviderModelCatalog(
    config: ProviderConfig,
    provider: ModelProvider
): Result<ProviderModelCatalogFetchResult> {
    val apiKey = config.getApiKey(provider.id).trim()
    val baseUrl = config.getBaseUrl(provider.id) ?: provider.defaultBaseUrl
    if (apiKey.isBlank() && !isLocalModelBaseUrl(baseUrl)) {
        return Result.failure(IllegalStateException("请先填写 ${provider.name} 的 API Key。"))
    }
    val endpointCandidates = buildProviderModelEndpointCandidates(
        providerId = provider.id,
        baseUrl = baseUrl
    )
    var lastError: String? = null
    endpointCandidates.forEach { endpoint ->
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .get()
            .addHeader("Accept", "application/json")
        when (provider.id) {
            "claude" -> {
                apiKey.takeIf { it.isNotBlank() }?.let {
                    requestBuilder.addHeader("x-api-key", it)
                }
                requestBuilder.addHeader("anthropic-version", "2023-06-01")
                config.getBaseUrl(provider.id)?.takeIf { it.isNotBlank() }?.let { customBaseUrl ->
                    requestBuilder.addHeader("anthropic-dangerous-direct-browser-access", "true")
                }
            }

            "gemini" -> {
                // Gemini is a native GenerateContent API, not an OpenAI
                // endpoint: model discovery uses its own API-key header.
                apiKey.takeIf { it.isNotBlank() }?.let {
                    requestBuilder.addHeader("x-goog-api-key", it)
                }
            }

            else -> {
                apiKey.takeIf { it.isNotBlank() }?.let {
                    requestBuilder.addHeader("Authorization", "Bearer $it")
                }
            }
        }
        runCatching {
            PROVIDER_MODEL_CATALOG_HTTP.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: ${body.take(160)}")
                }
                val models = parseProviderModelIds(body)
                if (models.isEmpty()) {
                    throw IllegalStateException("接口返回成功，但没有解析到模型列表。")
                }
                val normalizedModels = if (provider.id == "gemini") {
                    models.map { it.removePrefix("models/") }
                } else {
                    models
                }
                ProviderModelCatalogFetchResult(
                    models = mergeProviderModelCandidates(
                        provider.id,
                        config.getResolvedModel(provider.id),
                        normalizedModels
                    ),
                    sourceLabel = "上游接口"
                )
            }
        }.onSuccess { return Result.success(it) }
            .onFailure { error ->
                lastError = error.message ?: "读取模型列表失败"
            }
    }
    return Result.failure(IllegalStateException(lastError ?: "读取模型列表失败"))
}

private fun buildProviderModelEndpointCandidates(providerId: String, baseUrl: String): List<String> {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    if (normalizedBaseUrl.isBlank()) return emptyList()
    val candidates = linkedSetOf<String>()
    when (providerId) {
        "claude" -> {
            candidates += "$normalizedBaseUrl/v1/models"
            candidates += "$normalizedBaseUrl/models"
        }

        "gemini" -> {
            candidates += "$normalizedBaseUrl/models"
            if (!normalizedBaseUrl.endsWith("/v1beta", ignoreCase = true)) {
                candidates += "$normalizedBaseUrl/v1beta/models"
            }
        }

        else -> {
            candidates += "$normalizedBaseUrl/models"
            if (!normalizedBaseUrl.endsWith("/v1", ignoreCase = true)) {
                candidates += "$normalizedBaseUrl/v1/models"
            } else {
                candidates += "${normalizedBaseUrl.removeSuffix("/v1")}/models"
            }
        }
    }
    return candidates.toList()
}

private fun parseProviderModelIds(body: String): List<String> {
    val root = PROVIDER_MODEL_CATALOG_JSON.parseToJsonElement(body)
    val arrays = mutableListOf<JsonArray>()
    when (root) {
        is JsonArray -> arrays += root
        is JsonObject -> {
            listOf("data", "models", "items").forEach { key ->
                root[key]?.let { value ->
                    if (value is JsonArray) {
                        arrays += value
                    }
                }
            }
            if (arrays.isEmpty()) {
                extractModelId(root)?.let { return listOf(it) }
            }
        }
        else -> {
            extractModelId(root)?.let { return listOf(it) }
        }
    }
    return linkedSetOf<String>().apply {
        arrays.forEach { array ->
            array.forEach { element ->
                extractModelId(element)?.let(::add)
            }
        }
    }.toList()
}

private fun extractModelId(element: JsonElement): String? {
    return when (element) {
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        is JsonObject -> {
            listOf("id", "name", "model").firstNotNullOfOrNull { key ->
                element[key]?.let(::extractModelId)
            }?.trim()?.takeIf { it.isNotBlank() }
        }

        else -> null
    }
}
