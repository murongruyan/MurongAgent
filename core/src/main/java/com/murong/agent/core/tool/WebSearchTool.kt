package com.murong.agent.core.tool

import kotlinx.serialization.json.*

/**
 * Web 搜索工具——从搜索引擎获取信息
 * 使用 OkHttp 请求外部搜索 API
 */
class WebSearchTool : Tool {

    override val name = "web_search"
    override val description = "在互联网上搜索信息。获取最新的文档、API 参考、解决方案等。支持 Bing 和自定义搜索引擎。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf(
                "type" to "string",
                "description" to "搜索关键词"
            ),
            "maxResults" to mapOf(
                "type" to "integer",
                "description" to "返回结果数量，默认 5",
                "default" to 5
            )
        ),
        "required" to listOf("query")
    )

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: String): String {
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(args).jsonObject
            val query = obj["query"]?.jsonPrimitive?.content
                ?: return "Error: 'query' parameter required"
            val maxResults = obj["maxResults"]?.jsonPrimitive?.intOrNull ?: 5

            // 使用 SearXNG 或 fallback 到 Bing
            searchViaSearXNG(query, maxResults)
                ?: searchViaBing(query, maxResults)
                ?: "Web search is not configured. Please set up a search engine in settings."
        } catch (e: Exception) {
            "Web search error: ${e.message}"
        }
    }

    private fun searchViaSearXNG(query: String, maxResults: Int): String? {
        return try {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("searx.be")
                .addPathSegment("search")
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("language", "zh-CN")
                .build()

            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Murong-Mobile/1.0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null

            val root = kotlinx.serialization.json.Json
                .parseToJsonElement(body).jsonObject
            val results = root["results"]?.jsonArray ?: return null

            results.take(maxResults).mapIndexed { i, r ->
                val obj = r.jsonObject
                "${i + 1}. ${obj["title"]?.jsonPrimitive?.contentOrNull ?: "No title"}\n" +
                    "   URL: ${obj["url"]?.jsonPrimitive?.contentOrNull ?: "N/A"}\n" +
                    "   ${obj["content"]?.jsonPrimitive?.contentOrNull ?: ""}"
            }.joinToString("\n\n")
        } catch (_: Exception) { null }
    }

    private fun searchViaBing(query: String, maxResults: Int): String? {
        return try {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("api.bing.microsoft.com")
                .addPathSegments("v7.0/search")
                .addQueryParameter("q", query)
                .addQueryParameter("count", maxResults.toString())
                .addQueryParameter("mkt", "zh-CN")
                .build()

            // 注意：需要设置 BING_SEARCH_KEY 环境变量或配置
            val apiKey = System.getenv("BING_SEARCH_KEY")
                ?: return null // 没有配置 Bing key

            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null

            val root = kotlinx.serialization.json.Json
                .parseToJsonElement(body).jsonObject
            val webPages = root["webPages"]?.jsonObject ?: return null
            val results = webPages["value"]?.jsonArray ?: return null

            results.take(maxResults).mapIndexed { i, r ->
                val obj = r.jsonObject
                "${i + 1}. ${obj["name"]?.jsonPrimitive?.contentOrNull ?: "No title"}\n" +
                    "   URL: ${obj["url"]?.jsonPrimitive?.contentOrNull ?: "N/A"}\n" +
                    "   ${obj["snippet"]?.jsonPrimitive?.contentOrNull ?: ""}"
            }.joinToString("\n\n")
        } catch (_: Exception) { null }
    }
}
