package com.murong.agent.core.tool

import com.murong.agent.core.config.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Web 搜索工具。
 *
 * 默认搜索顺序：
 * 1. 自定义搜索后端（如配置）
 * 2. Bing 搜索页抓取
 * 3. Google 搜索页抓取
 * 4. 百度搜索页抓取
 */
class WebSearchTool(
    private val config: ProviderConfig
) : Tool {

    override val name = "web_search"
    override val description = "在互联网上搜索信息。支持自定义搜索后端，并内置 Bing、Google、百度抓取回退。"
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

    private val json = Json { ignoreUnknownKeys = true }
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun execute(args: String): String {
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val query = obj["query"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (query.isBlank()) return "Error: 'query' parameter required"
            val maxResults = (obj["maxResults"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 10)

            val attempts = mutableListOf<SearchAttempt>()
            val sources = buildList {
                config.getNormalizedWebSearchBackendUrl()?.let { backendUrl ->
                    add { searchViaConfiguredBackend(query, maxResults, backendUrl) }
                }
                add { searchViaBingPage(query, maxResults) }
                add { searchViaGooglePage(query, maxResults) }
                add { searchViaBaiduPage(query, maxResults) }
            }

            for (attempt in sources) {
                val result = attempt()
                if (result.content != null) return result.content
                attempts += result
            }

            buildUnavailableMessage(query, attempts)
        } catch (e: Exception) {
            "网页搜索出错：${e.message ?: e::class.java.simpleName}"
        }
    }

    private fun searchViaConfiguredBackend(
        query: String,
        maxResults: Int,
        backendUrl: String
    ): SearchAttempt {
        val parsed = backendUrl.toHttpUrlOrNull()
            ?: return SearchAttempt("自定义后端", detail = "地址无效")
        return when {
            parsed.encodedPath.endsWith("/mcp") || "anysearch" in parsed.host ->
                searchViaAnySearch(query, maxResults, parsed)
            else ->
                searchViaSearXngCompatible(query, maxResults, parsed)
        }
    }

    private fun searchViaAnySearch(
        query: String,
        maxResults: Int,
        endpoint: HttpUrl
    ): SearchAttempt {
        return runSearch("自定义后端(AnySearch)") {
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "search")
                    putJsonObject("arguments") {
                        put("query", query)
                        put("max_results", maxResults)
                    }
                }
            }
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Content-Type", "application/json")
            config.getTrimmedWebSearchApiKey()
                .ifBlank { System.getenv("ANYSEARCH_API_KEY").orEmpty() }
                .takeIf { it.isNotBlank() }
                ?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = body.replace('\n', ' ').trim().take(240)
                return@runSearch SearchEngineResponse.fail(
                    if (detail.isBlank()) "HTTP ${response.code}" else "HTTP ${response.code}: $detail"
                )
            }
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull?.let { message ->
                return@runSearch SearchEngineResponse.fail("API 错误: $message")
            }
            val text = root["result"]
                ?.jsonObject
                ?.get("content")
                ?.jsonArray
                ?.firstOrNull { item ->
                    item.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text"
                }
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
            if (text.isBlank()) {
                SearchEngineResponse.fail("返回为空")
            } else {
                SearchEngineResponse.success(text)
            }
        }
    }

    private fun searchViaSearXngCompatible(
        query: String,
        maxResults: Int,
        baseUrl: HttpUrl
    ): SearchAttempt {
        return runSearch("自定义后端") {
            val searchUrl = baseUrl.newBuilder()
                .apply {
                    val lastSegment = baseUrl.pathSegments.lastOrNull().orEmpty()
                    if (lastSegment != "search") addPathSegment("search")
                }
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("language", "zh-CN")
                .build()
            val requestBuilder = Request.Builder()
                .url(searchUrl)
                .get()
                .addHeader("User-Agent", BROWSER_USER_AGENT)
                .addHeader("Accept", "application/json")
            config.getTrimmedWebSearchApiKey()
                .takeIf { it.isNotBlank() }
                ?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                return@runSearch SearchEngineResponse.fail("HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty()
                .mapNotNull { item ->
                    val obj = item.jsonObject
                    SearchEntry(
                        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        snippet = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    ).takeIf { it.title.isNotBlank() && it.url.isNotBlank() }
                }
                .take(maxResults)
            if (results.isEmpty()) {
                SearchEngineResponse.fail("未返回可用结果")
            } else {
                SearchEngineResponse.success(formatEntries("自定义后端", results))
            }
        }
    }

    private fun searchViaBingPage(query: String, maxResults: Int): SearchAttempt {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("www.bing.com")
            .addPathSegment("search")
            .addQueryParameter("format", "rss")
            .addQueryParameter("q", query)
            .build()
        return runSearch("Bing 抓取") {
            val response = client.newCall(
                Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("User-Agent", BROWSER_USER_AGENT)
                    .addHeader("Accept", "application/rss+xml, application/xml, text/xml")
                    .build()
            ).execute()
            if (!response.isSuccessful) {
                return@runSearch SearchEngineResponse.fail("HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                return@runSearch SearchEngineResponse.fail("返回为空")
            }
            val doc = Jsoup.parse(body, "", Parser.xmlParser())
            val entries = doc.select("item").mapNotNull { item ->
                SearchEntry(
                    title = item.selectFirst("title")?.text().orEmpty().trim(),
                    url = item.selectFirst("link")?.text().orEmpty().trim(),
                    snippet = item.selectFirst("description")?.text().orEmpty().trim()
                ).takeIf { it.title.isNotBlank() && it.url.isNotBlank() }
            }.take(maxResults)
            if (entries.isEmpty()) {
                SearchEngineResponse.fail("RSS 未返回结果")
            } else {
                SearchEngineResponse.success(formatEntries("Bing 抓取", entries))
            }
        }
    }

    private fun searchViaGooglePage(query: String, maxResults: Int): SearchAttempt {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("www.google.com")
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("hl", "zh-CN")
            .addQueryParameter("num", maxResults.coerceIn(1, 10).toString())
            .build()
        return runPageSearch("Google 抓取", url, "https://www.google.com/") { doc ->
            doc.select("div.g, div[data-snc]").mapNotNull { item ->
                val h3 = item.selectFirst("h3") ?: return@mapNotNull null
                val anchor = h3.parents().select("a[href]").firstOrNull() ?: return@mapNotNull null
                val href = resolveGoogleHref(anchor.absUrl("href"))
                val snippet = item.selectFirst("div.VwiC3b, div.yXK7lf, div[data-sncf='1'], span.aCOpRe")
                    ?.text()
                    .orEmpty()
                    .trim()
                SearchEntry(
                    title = h3.text().trim(),
                    url = href,
                    snippet = snippet
                ).takeIf { it.title.isNotBlank() && it.url.isNotBlank() }
            }.distinctBy { it.url }.take(maxResults)
        }
    }

    private fun searchViaBaiduPage(query: String, maxResults: Int): SearchAttempt {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("www.baidu.com")
            .addPathSegment("s")
            .addQueryParameter("wd", query)
            .build()
        return runPageSearch("百度抓取", url, "https://www.baidu.com/") { doc ->
            doc.select("div.result, div.c-container, div[data-log], article, section").mapNotNull { item ->
                val link = item.selectFirst("h3 a") ?: return@mapNotNull null
                val title = link.text().trim()
                val href = link.absUrl("href").trim()
                val snippet = item.selectFirst("div.c-abstract, div.content-right_8Zs40, span.content-right_8Zs40, div.c-span-last p")
                    ?.text()
                    .orEmpty()
                    .trim()
                    .ifBlank {
                        item.select("span, div, p")
                            .map { it.text().trim() }
                            .firstOrNull { text -> text.isNotBlank() && text != title }
                            .orEmpty()
                    }
                SearchEntry(title = title, url = href, snippet = snippet)
                    .takeIf { it.title.isNotBlank() && it.url.isNotBlank() }
            }.ifEmpty {
                doc.select("a[href*=\"baidu.com/link\"], a[href^=\"http\"]").mapNotNull { link ->
                    val title = link.text().trim()
                    val href = link.absUrl("href").trim()
                    if (title.isBlank() || href.isBlank()) return@mapNotNull null
                    val snippet = link.parent()?.text().orEmpty().trim().removePrefix(title).trim()
                    SearchEntry(title = title, url = href, snippet = snippet)
                }
            }.distinctBy { it.url }.take(maxResults)
        }
    }

    private fun runPageSearch(
        source: String,
        url: HttpUrl,
        referer: String,
        parser: (Document) -> List<SearchEntry>
    ): SearchAttempt {
        return runSearch(source) {
            val response = client.newCall(
                Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("User-Agent", BROWSER_USER_AGENT)
                    .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                    .addHeader("Referer", referer)
                    .build()
            ).execute()
            if (!response.isSuccessful) {
                return@runSearch SearchEngineResponse.fail("HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                return@runSearch SearchEngineResponse.fail("返回为空")
            }
            val entries = parser(Jsoup.parse(body, url.toString()))
            if (entries.isEmpty()) {
                SearchEngineResponse.fail("未解析到结果")
            } else {
                SearchEngineResponse.success(formatEntries(source, entries))
            }
        }
    }

    private fun runSearch(
        source: String,
        block: () -> SearchEngineResponse
    ): SearchAttempt {
        return try {
            val result = block()
            SearchAttempt(
                source = source,
                content = result.content,
                detail = result.detail
            )
        } catch (e: Exception) {
            SearchAttempt(
                source = source,
                detail = e.message ?: e::class.java.simpleName
            )
        }
    }

    private fun formatEntries(source: String, entries: List<SearchEntry>): String {
        return buildString {
            appendLine("搜索源: $source")
            appendLine()
            entries.forEachIndexed { index, entry ->
                appendLine("${index + 1}. ${entry.title}")
                appendLine("   URL: ${entry.url}")
                entry.snippet.takeIf { it.isNotBlank() }?.let { appendLine("   ${it.take(220)}") }
                if (index != entries.lastIndex) appendLine()
            }
        }.trim()
    }

    private fun buildUnavailableMessage(query: String, attempts: List<SearchAttempt>): String {
        val detail = attempts.joinToString("；") { "${it.source}: ${it.detail}" }
        return buildString {
            append("网页搜索暂时不可用：当前搜索源均未返回可用结果。")
            append("\n查询词：$query")
            append("\n已尝试：")
            append(
                attempts.joinToString(" -> ") { attempt ->
                    attempt.source
                }.ifBlank { "Bing 抓取 -> Google 抓取 -> 百度抓取" }
            )
            if (detail.isNotBlank()) {
                append("\n失败详情：$detail")
            }
            append("\n可选处理：稍后重试，或在设置中填写自定义搜索后端 / API Key。")
        }
    }

    private fun resolveGoogleHref(rawUrl: String): String {
        val parsed = rawUrl.toHttpUrlOrNull() ?: return rawUrl
        val q = parsed.queryParameter("q")
        return if (parsed.host.contains("google.") && parsed.encodedPath == "/url" && !q.isNullOrBlank()) {
            URLDecoder.decode(q, Charsets.UTF_8.name())
        } else {
            rawUrl
        }
    }

    private data class SearchEntry(
        val title: String,
        val url: String,
        val snippet: String
    )

    private data class SearchAttempt(
        val source: String,
        val content: String? = null,
        val detail: String
    )

    private data class SearchEngineResponse(
        val content: String? = null,
        val detail: String
    ) {
        companion object {
            fun success(content: String): SearchEngineResponse =
                SearchEngineResponse(content = content, detail = "ok")

            fun fail(detail: String): SearchEngineResponse =
                SearchEngineResponse(content = null, detail = detail)
        }
    }

    private companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; MurongAgent) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
