package com.murong.agent.ui.auth

import com.murong.agent.core.config.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitHubAuthFlowTest {
    @Test
    fun buildAuthorizationUrl_appendsExpectedMurongParameters() {
        val config = ProviderConfig()

        val url = GitHubAuthFlow.buildAuthorizationUrl(
            config = config,
            clientState = "state value"
        )

        assertTrue(url.startsWith("${config.getMurongBackendAuthApiUrl()}?"))
        assertTrue(url.contains("action=start"))
        assertTrue(url.contains("client_redirect_uri=murongagent%3A%2F%2Fauth%2Fgithub"))
        assertTrue(url.contains("client_state=state%20value"))
    }

    @Test
    fun isGitHubOAuthCallback_acceptsLegacyAndBackendRoutes() {
        assertTrue(GitHubAuthFlow.isGitHubOAuthCallback("murongagent://github?exchange_code=abc"))
        assertTrue(GitHubAuthFlow.isGitHubOAuthCallback("murongagent://auth/github?exchange_code=abc"))
        assertFalse(GitHubAuthFlow.isGitHubOAuthCallback("murongagent://auth/other?exchange_code=abc"))
    }

    @Test
    fun resolveCallback_ignoresBlankAndDuplicateValues() {
        assertEquals(
            GitHubCallbackResolution.IgnoreBlank,
            GitHubAuthFlow.resolveCallback("   ", lastHandledCallback = null)
        )
        assertEquals(
            GitHubCallbackResolution.IgnoreDuplicate,
            GitHubAuthFlow.resolveCallback(
                rawCallbackUri = "murongagent://auth/github?exchange_code=abc",
                lastHandledCallback = "murongagent://auth/github?exchange_code=abc"
            )
        )
    }

    @Test
    fun resolveCallback_reportsOAuthErrorsFromQueryParameters() {
        val resolution = GitHubAuthFlow.resolveCallback(
            rawCallbackUri = "murongagent://auth/github?error=access_denied&error_description=%E7%94%A8%E6%88%B7%E5%8F%96%E6%B6%88",
            lastHandledCallback = null
        )

        val result = assertIs<GitHubCallbackResolution.Error>(resolution)
        assertEquals("用户取消", result.message)
        assertEquals(
            "murongagent://auth/github?error=access_denied&error_description=%E7%94%A8%E6%88%B7%E5%8F%96%E6%B6%88",
            result.normalizedCallback
        )
    }

    @Test
    fun resolveCallback_reportsInvalidCallbackUri() {
        val resolution = GitHubAuthFlow.resolveCallback(
            rawCallbackUri = "%%%not-a-uri%%%",
            lastHandledCallback = null
        )

        val result = assertIs<GitHubCallbackResolution.Invalid>(resolution)
        assertEquals("GitHub 登录回调地址无效。", result.message)
    }

    @Test
    fun resolveCallback_reportsMissingExchangeCode() {
        val resolution = GitHubAuthFlow.resolveCallback(
            rawCallbackUri = "murongagent://auth/github?state=test",
            lastHandledCallback = null
        )

        val result = assertIs<GitHubCallbackResolution.MissingExchangeCode>(resolution)
        assertEquals("回调里没有拿到登录票据。", result.message)
    }

    @Test
    fun resolveCallback_returnsDecodedExchangeCode() {
        val resolution = GitHubAuthFlow.resolveCallback(
            rawCallbackUri = "murongagent://auth/github?exchange_code=abc%2B123",
            lastHandledCallback = null
        )

        val result = assertIs<GitHubCallbackResolution.ExchangeCode>(resolution)
        assertEquals("abc+123", result.exchangeCode)
    }
}
