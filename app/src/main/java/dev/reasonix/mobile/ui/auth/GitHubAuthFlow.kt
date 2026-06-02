package dev.reasonix.mobile.ui.auth

import dev.reasonix.mobile.core.config.ProviderConfig
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object GitHubAuthFlow {
    fun buildAuthorizationUrl(config: ProviderConfig, clientState: String): String {
        val baseUrl = config.getReasonixBackendAuthApiUrl().trim()
        val separator = if ('?' in baseUrl) '&' else '?'
        return buildString {
            append(baseUrl)
            append(separator)
            append("action=start")
            append("&client_redirect_uri=")
            append(encodeQueryValue(config.getReasonixGitHubRedirectUri()))
            append("&client_state=")
            append(encodeQueryValue(clientState))
        }
    }

    fun isGitHubOAuthCallback(rawCallbackUri: String): Boolean {
        val callbackUri = runCatching { URI(rawCallbackUri.trim()) }.getOrNull() ?: return false
        val isLegacyGitHubCallback =
            callbackUri.scheme.equals("reasonix", ignoreCase = true) &&
                callbackUri.host.equals("github", ignoreCase = true)
        val isBackendGitHubCallback =
            callbackUri.scheme.equals("reasonix", ignoreCase = true) &&
                callbackUri.host.equals("auth", ignoreCase = true) &&
                callbackUri.path?.startsWith("/github") == true
        return isLegacyGitHubCallback || isBackendGitHubCallback
    }

    fun resolveCallback(
        rawCallbackUri: String,
        lastHandledCallback: String?
    ): GitHubCallbackResolution {
        val trimmed = rawCallbackUri.trim()
        if (trimmed.isBlank()) return GitHubCallbackResolution.IgnoreBlank
        if (lastHandledCallback == trimmed) return GitHubCallbackResolution.IgnoreDuplicate

        val callbackUri = runCatching { URI(trimmed) }.getOrNull()
            ?: return GitHubCallbackResolution.Invalid(
                normalizedCallback = trimmed,
                message = "GitHub 登录回调地址无效。"
            )

        val queryParameters = parseQueryParameters(callbackUri.rawQuery)
        val error = queryParameters["error"]
        if (!error.isNullOrBlank()) {
            return GitHubCallbackResolution.Error(
                normalizedCallback = trimmed,
                message = queryParameters["error_description"] ?: error
            )
        }

        val exchangeCode = queryParameters["exchange_code"].orEmpty()
        if (exchangeCode.isBlank()) {
            return GitHubCallbackResolution.MissingExchangeCode(
                normalizedCallback = trimmed,
                message = "回调里没有拿到登录票据。"
            )
        }

        return GitHubCallbackResolution.ExchangeCode(
            normalizedCallback = trimmed,
            exchangeCode = exchangeCode
        )
    }

    private fun parseQueryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery
            .split('&')
            .asSequence()
            .filter { it.isNotBlank() }
            .associate { part ->
                val separatorIndex = part.indexOf('=')
                val rawKey = if (separatorIndex >= 0) part.substring(0, separatorIndex) else part
                val rawValue = if (separatorIndex >= 0) part.substring(separatorIndex + 1) else ""
                decodeQueryValue(rawKey) to decodeQueryValue(rawValue)
            }
    }

    private fun encodeQueryValue(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }

    private fun decodeQueryValue(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
}

internal sealed interface GitHubCallbackResolution {
    data object IgnoreBlank : GitHubCallbackResolution
    data object IgnoreDuplicate : GitHubCallbackResolution

    data class Invalid(
        val normalizedCallback: String,
        val message: String
    ) : GitHubCallbackResolution

    data class Error(
        val normalizedCallback: String,
        val message: String
    ) : GitHubCallbackResolution

    data class MissingExchangeCode(
        val normalizedCallback: String,
        val message: String
    ) : GitHubCallbackResolution

    data class ExchangeCode(
        val normalizedCallback: String,
        val exchangeCode: String
    ) : GitHubCallbackResolution
}
