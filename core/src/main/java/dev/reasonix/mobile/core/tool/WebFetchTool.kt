package dev.reasonix.mobile.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class WebFetchTool : Tool {

    override val name = "web_fetch"
    override val description = "抓取单个网页并提取标题、摘要和正文文本，适合在搜索后继续读取网页内容。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "url" to mapOf(
                "type" to "string",
                "description" to "要抓取的网页链接"
            ),
            "maxChars" to mapOf(
                "type" to "integer",
                "description" to "正文最大返回字符数，默认 4000",
                "default" to 4000
            )
        ),
        "required" to listOf("url")
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: String): String {
        return try {
            val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(args).jsonObject
            val rawUrl = obj["url"]?.jsonPrimitive?.content?.trim()
                ?: return "Error: 'url' parameter required"
            val maxChars = (obj["maxChars"]?.jsonPrimitive?.intOrNull ?: 4000)
                .coerceIn(500, 12000)
            val normalizedUrl = normalizeUrl(rawUrl)
            val request = Request.Builder()
                .url(normalizedUrl)
                .addHeader("User-Agent", "Reasonix-Mobile/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return "Error: fetch failed with HTTP ${response.code}"
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return "Error: empty response body"
                }
                val title = extractTitle(body)
                val extracted = extractMainText(body)
                val truncated = extracted.length > maxChars
                val content = extracted.take(maxChars).trim()
                val excerpt = content.take(280).trim()
                buildJsonObject {
                    put("type", "web_fetch_result")
                    put("url", normalizedUrl)
                    put("title", title.ifBlank { normalizedUrl })
                    put("excerpt", excerpt)
                    put("content", content)
                    put("truncated", truncated)
                    put("totalChars", extracted.length)
                }.toString()
            }
        } catch (e: Exception) {
            "Error: web fetch failed: ${e.message}"
        }
    }

    private fun normalizeUrl(url: String): String {
        return if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            url
        } else {
            "https://$url"
        }
    }

    private fun extractTitle(html: String): String {
        val match = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        return decodeHtmlEntities(match).replace(Regex("\\s+"), " ").trim()
    }

    private fun extractMainText(html: String): String {
        val body = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<noscript[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|div|section|article|li|tr|h[1-6])>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
        return decodeHtmlEntities(body)
            .lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }
}
