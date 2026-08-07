package com.murong.agent.core.codex

import kotlinx.coroutines.delay

/**
 * Reads the app-server's local account first, then uses an online token refresh only while
 * confirming a device-code login. A transient refresh failure must not discard an earlier
 * local result or abort the remaining confirmation attempts.
 */
suspend fun readCodexAccountWithRetry(
    confirmingLogin: Boolean,
    delaysMillis: List<Long> = if (confirmingLogin) {
        listOf(0L, 1_500L, 3_000L, 5_000L)
    } else {
        listOf(0L)
    },
    accountReader: suspend (refreshToken: Boolean) -> CodexAccountReadResult,
): CodexAccountReadResult {
    require(delaysMillis.isNotEmpty()) { "Codex 账号状态读取至少需要一次尝试" }
    var latest: CodexAccountReadResult? = null
    var lastFailure: Throwable? = null

    delaysMillis.forEachIndexed { index, waitMillis ->
        require(waitMillis >= 0L) { "Codex 账号状态读取延迟不能为负数" }
        if (waitMillis > 0L) delay(waitMillis)
        runCatching {
            accountReader(confirmingLogin && index > 0)
        }.onSuccess { result ->
            latest = result
            if (result.account != null || !confirmingLogin) return result
        }.onFailure { error ->
            lastFailure = error
        }
    }

    return latest ?: throw checkNotNull(lastFailure) { "Codex 账号状态读取未执行" }
}
