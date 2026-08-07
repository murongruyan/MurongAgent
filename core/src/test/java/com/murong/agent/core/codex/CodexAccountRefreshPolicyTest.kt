package com.murong.agent.core.codex

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodexAccountRefreshPolicyTest {
    @Test
    fun loginConfirmation_readsLocalStateBeforeRefreshingToken() = runBlocking {
        val refreshRequests = mutableListOf<Boolean>()
        val account = accountResult("user@example.com")

        val result = readCodexAccountWithRetry(
            confirmingLogin = true,
            delaysMillis = listOf(0L, 0L),
        ) { refreshToken ->
            refreshRequests += refreshToken
            if (refreshToken) account else accountResult(null)
        }

        assertEquals(listOf(false, true), refreshRequests)
        assertEquals("user@example.com", result.account?.email)
    }

    @Test
    fun oneFailedRead_doesNotAbortLaterLoginConfirmation() = runBlocking {
        var attempts = 0

        val result = readCodexAccountWithRetry(
            confirmingLogin = true,
            delaysMillis = listOf(0L, 0L, 0L),
        ) { refreshToken ->
            attempts += 1
            if (attempts == 1) error("temporary network failure")
            if (refreshToken) accountResult("recovered@example.com") else accountResult(null)
        }

        assertEquals(2, attempts)
        assertEquals("recovered@example.com", result.account?.email)
    }

    @Test
    fun refreshFailures_preserveTheLatestLocalResult() = runBlocking {
        val localResult = accountResult(null)

        val result = readCodexAccountWithRetry(
            confirmingLogin = true,
            delaysMillis = listOf(0L, 0L, 0L),
        ) { refreshToken ->
            if (refreshToken) error("OpenAI refresh temporarily unavailable")
            localResult
        }

        assertEquals(localResult, result)
        assertNull(result.account)
    }

    private fun accountResult(email: String?): CodexAccountReadResult = CodexAccountReadResult(
        account = email?.let {
            CodexAccount(
                type = "chatgpt",
                email = it,
                planType = "plus",
                raw = JsonObject(emptyMap()),
            )
        },
        requiresOpenaiAuth = true,
        raw = JsonObject(emptyMap()),
    )
}
