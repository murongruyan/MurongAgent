package com.murong.agent.core.config

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import com.murong.agent.core.provider.ProviderWireFormat

data class ProviderUsageImportRule(
    val endpoint: String,
    val sourceLabel: String,
)

data class ProviderImportPayload(
    val sourceScheme: String,
    val app: String,
    val name: String,
    val homepage: String?,
    val endpoints: List<String>,
    val apiKey: String,
    val model: String?,
    val notes: String?,
    val requestedActive: Boolean,
    val usageScript: String?,
    val requestedUsageEnabled: Boolean,
    val usageAutoIntervalMinutes: Int?,
    val usageRule: ProviderUsageImportRule?,
) {
    val providerId: String
        get() = when (app) {
            "claude" -> "claude"
            "gemini" -> "gemini"
            else -> "openai-compatible"
        }

    val primaryEndpoint: String
        get() = endpoints.first()

    val maskedApiKey: String
        get() = maskImportedSecret(apiKey)

    fun applyTo(
        current: ProviderConfig,
        activate: Boolean,
        enableUsage: Boolean,
    ): ProviderConfig {
        require(!enableUsage || usageRule != null) { "这段用量脚本无法安全转换，不能启用自动查询" }
        val relayId = stableRelayId(providerId, name, primaryEndpoint)
        val imported = RelayConfig(
            id = relayId,
            name = name,
            baseUrl = primaryEndpoint,
            apiKey = apiKey,
            model = model.orEmpty(),
            reasoningEffort = "high",
            modelPreset = "custom",
            autoModelSelection = model.isNullOrBlank(),
            autoReasoningEffort = true,
            balanceApiPath = if (enableUsage) usageRule?.endpoint.orEmpty() else "",
            kind = RelayKind.CUSTOM,
            apiFormat = if (providerId == "claude") {
                ProviderWireFormat.ANTHROPIC_MESSAGES
            } else {
                ProviderWireFormat.CHAT_COMPLETIONS
            },
        )
        val migrated = current.withLegacyRelayConfigurations()
        val relays = migrated.getRelayConfigs(providerId).toMutableList()
        val existingIndex = relays.indexOfFirst { relay ->
            relay.id == relayId ||
                (
                    relay.name.trim().equals(name.trim(), ignoreCase = true) &&
                        relay.baseUrl.trimEnd('/').equals(primaryEndpoint.trimEnd('/'), ignoreCase = true)
                    )
        }
        if (existingIndex >= 0) {
            imported.copy(id = relays[existingIndex].id).also { relays[existingIndex] = it }
        } else {
            relays += imported
        }
        val importedId = relays.getOrNull(existingIndex)?.id ?: relayId
        val activeRelayId = if (activate) {
            importedId
        } else {
            migrated.getActiveRelayId(providerId).orEmpty().ifBlank { importedId }
        }
        val updated = migrated.withRelayConfigs(providerId, relays, activeRelayId)
        return if (activate) {
            updated.copy(
                activeAgentBackend = AgentBackendKind.PROVIDER_API,
                activeProviderId = providerId,
            )
        } else {
            updated
        }
    }
}

object ProviderImportDeepLink {
    private const val MAX_LINK_LENGTH = 65_536
    private const val MAX_SCRIPT_LENGTH = 32_768
    private const val MAX_API_KEY_LENGTH = 8_192
    private const val MAX_URL_LENGTH = 2_048
    private val allowedApps = setOf(
        "claude", "codex", "gemini", "grokbuild", "opencode", "openclaw", "hermes"
    )
    private val urlPattern = Regex("""\burl\s*:\s*([\"'])(.{1,2048}?)\1""", RegexOption.DOT_MATCHES_ALL)
    private val methodPattern = Regex("""\bmethod\s*:\s*([\"'])([A-Za-z]+)\1""")
    private val bearerPattern = Regex(
        """Authorization[\"']?\s*:\s*([\"'])Bearer\s+\{\{(?:apiKey|usageApiKey)\}\}\1""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(rawLink: String): Result<ProviderImportPayload> = runCatching {
        val link = rawLink.trim()
        require(link.isNotEmpty()) { "导入链接为空" }
        require(link.length <= MAX_LINK_LENGTH) { "导入链接过长" }
        require(link.none(Char::isISOControl)) { "导入链接包含控制字符" }
        val uri = URI(link)
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        val validRoute = (scheme == "ccswitch" && host == "v1" && path == "/import") ||
            (scheme == "murongagent" && host == "provider" && path == "/import")
        require(validRoute) { "不支持的导入协议或版本" }
        require(uri.fragment == null && uri.userInfo == null) { "导入链接不能包含账户信息或片段" }

        val params = parseUniqueQuery(uri.rawQuery.orEmpty())
        require(params["resource"] == "provider") { "目前只支持导入供应商配置" }
        val app = required(params, "app", 32).lowercase()
        require(app in allowedApps) { "不支持的应用类型：$app" }
        val name = required(params, "name", 100)
        val endpointValue = required(params, "endpoint", MAX_URL_LENGTH * 4)
        val endpoints = endpointValue.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .also { require(it.isNotEmpty() && it.size <= 4) { "API 端点数量无效" } }
            .map { validateRemoteUrl(it, "API 端点") }
        val apiKey = required(params, "apiKey", MAX_API_KEY_LENGTH)
        require(apiKey.none(Char::isWhitespace)) { "API Key 不能包含空白字符" }
        val homepage = params["homepage"]?.trim()?.takeIf(String::isNotEmpty)?.let {
            validateRemoteUrl(it, "官网地址", allowPath = true)
        }
        val model = optional(params, "model", 200)
        val notes = optional(params, "notes", 2_000)
        val usageScript = optional(params, "usageScript", MAX_SCRIPT_LENGTH)?.let(::decodeUsageScript)
        val usageBaseUrl = optional(params, "usageBaseUrl", MAX_URL_LENGTH)
        val usageRule = usageScript?.let {
            extractUsageRule(
                script = it,
                providerBaseUrl = endpoints.first(),
                usageBaseUrl = usageBaseUrl,
            )
        }
        val usageInterval = params["usageAutoInterval"]?.trim()?.takeIf(String::isNotEmpty)?.let {
            it.toIntOrNull()?.also { value -> require(value in 5..10_080) { "自动查询间隔必须在 5 到 10080 分钟之间" } }
                ?: error("自动查询间隔无效")
        }

        ProviderImportPayload(
            sourceScheme = scheme,
            app = app,
            name = name,
            homepage = homepage,
            endpoints = endpoints,
            apiKey = apiKey,
            model = model,
            notes = notes,
            requestedActive = params["enabled"].toStrictBooleanOrFalse(),
            usageScript = usageScript,
            requestedUsageEnabled = params["usageEnabled"].toStrictBooleanOrFalse(),
            usageAutoIntervalMinutes = usageInterval,
            usageRule = usageRule,
        )
    }

    private fun parseUniqueQuery(rawQuery: String): Map<String, String> {
        require(rawQuery.isNotBlank()) { "导入链接缺少参数" }
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val separator = pair.indexOf('=')
            val encodedKey = if (separator >= 0) pair.substring(0, separator) else pair
            val encodedValue = if (separator >= 0) pair.substring(separator + 1) else ""
            val key = decodeQueryPart(encodedKey)
            require(key.isNotBlank() && key.length <= 64) { "导入参数名无效" }
            require(key !in result) { "导入参数重复：$key" }
            result[key] = decodeQueryPart(encodedValue)
        }
        return result
    }

    private fun decodeQueryPart(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun required(params: Map<String, String>, name: String, maxLength: Int): String {
        return params[name]?.trim()?.also {
            require(it.isNotEmpty()) { "导入参数 $name 不能为空" }
            require(it.length <= maxLength) { "导入参数 $name 过长" }
            require(it.none(Char::isISOControl)) { "导入参数 $name 包含控制字符" }
        } ?: error("导入链接缺少 $name")
    }

    private fun optional(params: Map<String, String>, name: String, maxLength: Int): String? {
        val value = params[name]?.trim()?.takeIf(String::isNotEmpty) ?: return null
        require(value.length <= maxLength) { "导入参数 $name 过长" }
        require(value.none { it == '\u0000' }) { "导入参数 $name 包含空字符" }
        return value
    }

    private fun validateRemoteUrl(raw: String, label: String, allowPath: Boolean = true): String {
        require(raw.length <= MAX_URL_LENGTH) { "$label 过长" }
        val uri = URI(raw)
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "$label 必须是无账户信息、查询参数和片段的有效 URL"
        }
        val loopback = uri.host.equals("localhost", true) ||
            uri.host == "127.0.0.1" || uri.host == "::1"
        require(uri.scheme.equals("https", true) || loopback && uri.scheme.equals("http", true)) {
            "$label 必须使用 HTTPS（本机回环地址除外）"
        }
        require(allowPath || uri.path.isNullOrBlank() || uri.path == "/") { "$label 不能包含路径" }
        return raw.trimEnd('/')
    }

    private fun decodeUsageScript(value: String): String {
        if (value.any { it in "{}();?" }) return value
        val decoded = sequenceOf(Base64.getDecoder(), Base64.getUrlDecoder()).mapNotNull { decoder ->
            runCatching { String(decoder.decode(value), StandardCharsets.UTF_8) }.getOrNull()
        }.firstOrNull { it.any { char -> char in "{}();" } }
        return decoded ?: value
    }

    private fun extractUsageRule(
        script: String,
        providerBaseUrl: String,
        usageBaseUrl: String?,
    ): ProviderUsageImportRule? {
        if (script.length > MAX_SCRIPT_LENGTH) return null
        val method = methodPattern.find(script)?.groupValues?.get(2)?.uppercase() ?: "GET"
        if (method != "GET" || !bearerPattern.containsMatchIn(script)) return null
        val hasRemaining = listOf(
            "response?.remaining", "response.remaining", "response?.quota?.remaining",
            "response.quota.remaining", "response?.balance", "response.balance"
        ).any(script::contains)
        if (!hasRemaining || !script.contains("unit")) return null
        val rawRequestUrl = urlPattern.find(script)?.groupValues?.get(2)?.trim() ?: return null
        val base = usageBaseUrl?.let { validateRemoteUrl(it, "用量查询地址") } ?: providerBaseUrl
        val endpoint = when {
            rawRequestUrl.startsWith("{{baseUrl}}") -> combineBaseAndPath(base, rawRequestUrl.removePrefix("{{baseUrl}}"))
            rawRequestUrl.startsWith("{{usageBaseUrl}}") -> combineBaseAndPath(base, rawRequestUrl.removePrefix("{{usageBaseUrl}}"))
            else -> validateRemoteUrl(rawRequestUrl, "用量查询请求地址")
        }
        if (!sameOrigin(base, endpoint)) return null
        return ProviderUsageImportRule(endpoint = endpoint, sourceLabel = "受限 GET + Bearer 余额规则")
    }

    private fun combineBaseAndPath(base: String, suffix: String): String {
        if (suffix.isBlank()) return base
        require(suffix.startsWith('/') && !suffix.contains("//")) { "用量查询路径无效" }
        return base.trimEnd('/') + suffix
    }

    private fun sameOrigin(left: String, right: String): Boolean {
        val first = URI(left)
        val second = URI(right)
        fun port(uri: URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        return first.scheme.equals(second.scheme, true) &&
            first.host.equals(second.host, true) && port(first) == port(second)
    }
}

private fun String?.toStrictBooleanOrFalse(): Boolean = this?.trim()?.equals("true", true) == true

private fun stableRelayId(providerId: String, name: String, endpoint: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$providerId\u0000$name\u0000${endpoint.lowercase()}".toByteArray(StandardCharsets.UTF_8))
        .take(10)
        .joinToString("") { "%02x".format(it) }
    return "import-$digest"
}

private fun maskImportedSecret(value: String): String {
    if (value.length <= 8) return "*".repeat(value.length.coerceAtLeast(4))
    return value.take(4) + "*".repeat(12) + value.takeLast(4)
}
