package dev.reasonix.mobile.core.config

import dev.reasonix.mobile.core.provider.ProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ProviderBalanceSnapshot(
    val providerId: String,
    val balance: Double,
    val currency: String,
    val syncedAt: Long
)

class ProviderBalanceService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetchBalance(
        config: ProviderConfig,
        providerId: String = config.activeProviderId
    ): Result<ProviderBalanceSnapshot> {
        return runCatching {
            when (providerId) {
                "deepseek" -> fetchDeepSeekBalance(config)
                "openai-compatible" -> fetchCompatibleBalance(config, providerId)
                "claude" -> fetchCompatibleBalance(config, providerId)
                else -> error("当前 Provider 暂不支持余额查询。")
            }
        }
    }

    fun supportsBalanceFetch(
        providerId: String,
        config: ProviderConfig? = null
    ): Boolean {
        return when (providerId) {
            "deepseek" -> true
            "openai-compatible", "claude" -> !config?.getBalanceApiPath(providerId).isNullOrBlank()
            else -> false
        }
    }

    private suspend fun fetchDeepSeekBalance(config: ProviderConfig): ProviderBalanceSnapshot {
        val apiKey = config.deepseekApiKey.trim()
        require(apiKey.isNotBlank()) { "请先填写 DeepSeek API Key" }

        val endpoint = (config.deepseekBaseUrl.ifBlank {
            ProviderRegistry.getProvider("deepseek")?.defaultBaseUrl ?: "https://api.deepseek.com"
        }).trimEnd('/')

        val request = Request.Builder()
            .url("$endpoint/user/balance")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("余额查询失败：HTTP ${response.code}")
                }
                if (responseBody.isBlank()) {
                    throw IOException("余额查询失败：响应为空")
                }

                val root = json.parseToJsonElement(responseBody).jsonObject
                val balanceInfos = root["balance_infos"]?.jsonArray
                    ?: throw IOException("余额查询失败：响应中缺少 balance_infos")
                if (balanceInfos.isEmpty()) {
                    throw IOException("余额查询失败：balance_infos 为空")
                }

                val preferred = balanceInfos.firstOrNull {
                    it.jsonObject["currency"]?.jsonPrimitive?.contentOrNull?.equals("USD", ignoreCase = true) == true
                } ?: balanceInfos.first()

                val balanceObject = preferred.jsonObject
                val currency = balanceObject["currency"]?.jsonPrimitive?.contentOrNull
                    ?.uppercase(Locale.getDefault())
                    ?: "USD"
                val balance = balanceObject["total_balance"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    ?: balanceObject["balance"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    ?: throw IOException("余额查询失败：缺少 total_balance")

                ProviderBalanceSnapshot(
                    providerId = "deepseek",
                    balance = balance,
                    currency = currency,
                    syncedAt = System.currentTimeMillis()
                )
            }
        }
    }

    private suspend fun fetchCompatibleBalance(
        config: ProviderConfig,
        providerId: String
    ): ProviderBalanceSnapshot {
        val apiKey = config.getApiKey(providerId).trim()
        require(apiKey.isNotBlank()) { "请先填写 API Key" }

        val rawPath = config.getBalanceApiPath(providerId).trim()
        require(rawPath.isNotBlank()) { "请先填写余额接口路径" }

        val endpoint = resolveBalanceEndpoint(config, providerId, rawPath)
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")

        if (providerId == "claude") {
            requestBuilder.addHeader("x-api-key", apiKey)
        }

        val request = requestBuilder
            .get()
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("余额查询失败：HTTP ${response.code}")
                }
                if (responseBody.isBlank()) {
                    throw IOException("余额查询失败：响应为空")
                }

                parseCompatibleBalanceResponse(
                    providerId = providerId,
                    responseBody = responseBody
                )
            }
        }
    }

    private fun resolveBalanceEndpoint(
        config: ProviderConfig,
        providerId: String,
        rawPath: String
    ): String {
        if (rawPath.startsWith("http://", ignoreCase = true) ||
            rawPath.startsWith("https://", ignoreCase = true)
        ) {
            return rawPath
        }

        val baseUrl = (config.getBaseUrl(providerId).orEmpty().ifBlank {
            ProviderRegistry.getProvider(providerId)?.defaultBaseUrl.orEmpty()
        }).trimEnd('/')
        require(baseUrl.isNotBlank()) { "请先填写 Base URL，或将余额接口路径改成完整 URL" }

        val normalizedPath = rawPath.removePrefix("/")
        return "$baseUrl/$normalizedPath"
    }

    private fun parseCompatibleBalanceResponse(
        providerId: String,
        responseBody: String
    ): ProviderBalanceSnapshot {
        val root = json.parseToJsonElement(responseBody)
        val balanceEntry = findBalanceEntry(root)
            ?: throw IOException("余额查询失败：未识别到可用余额字段，请检查接口返回结构")

        return ProviderBalanceSnapshot(
            providerId = providerId,
            balance = balanceEntry.balance,
            currency = balanceEntry.currency ?: "USD",
            syncedAt = System.currentTimeMillis()
        )
    }

    private fun findBalanceEntry(element: JsonElement): ParsedBalanceEntry? {
        parseDeepSeekLikeBalance(element)?.let { return it }

        val objects = flattenObjects(element)
        objects.forEach { obj ->
            val balance = findFirstNumeric(
                obj,
                listOf(
                    "available_balance",
                    "total_balance",
                    "balance",
                    "credit_balance",
                    "remaining_balance",
                    "availableBalance",
                    "totalBalance",
                    "creditBalance",
                    "remainingBalance",
                    "amount",
                    "quota"
                )
            )
            if (balance != null) {
                val currency = findFirstString(
                    obj,
                    listOf("currency", "currency_code", "currencyCode", "unit")
                )?.uppercase(Locale.getDefault())
                return ParsedBalanceEntry(balance = balance, currency = currency)
            }
        }
        return null
    }

    private fun parseDeepSeekLikeBalance(element: JsonElement): ParsedBalanceEntry? {
        val objects = flattenObjects(element)
        objects.forEach { obj ->
            val balanceInfos = obj["balance_infos"] as? JsonArray ?: return@forEach
            if (balanceInfos.isEmpty()) return@forEach
            val preferred = balanceInfos.firstOrNull {
                it.jsonObject["currency"]?.jsonPrimitive?.contentOrNull?.equals("USD", ignoreCase = true) == true
            } ?: balanceInfos.first()
            val balanceObject = preferred.jsonObject
            val currency = balanceObject["currency"]?.jsonPrimitive?.contentOrNull
                ?.uppercase(Locale.getDefault())
            val balance = balanceObject["total_balance"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: balanceObject["balance"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            if (balance != null) {
                return ParsedBalanceEntry(balance = balance, currency = currency)
            }
        }
        return null
    }

    private fun flattenObjects(element: JsonElement): List<JsonObject> = when (element) {
        is JsonObject -> listOf(element) + element.values.flatMap { flattenObjects(it) }
        is JsonArray -> element.flatMap { flattenObjects(it) }
        else -> emptyList()
    }

    private fun findFirstNumeric(
        obj: JsonObject,
        keys: List<String>
    ): Double? {
        keys.forEach { key ->
            obj[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun findFirstString(
        obj: JsonObject,
        keys: List<String>
    ): String? {
        keys.forEach { key ->
            obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private data class ParsedBalanceEntry(
        val balance: Double,
        val currency: String?
    )
}
