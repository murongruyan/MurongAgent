package com.murong.agent.core.provider

import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

/** Structured transport failure used by AgentLoop to make retry decisions. */
class ProviderHttpException(
    val statusCode: Int,
    val retryAfterMillis: Long? = null,
    body: String = ""
) : IOException("HTTP $statusCode${body.take(1024).takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")

class IncompleteSseException(message: String) : IOException(message)

internal fun isRetryableProviderFailure(error: Throwable): Boolean = when (error) {
    is ProviderHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
    is IncompleteSseException -> true
    is java.net.UnknownHostException,
    is java.net.SocketTimeoutException,
    is java.net.ConnectException,
    is java.net.SocketException -> true
    else -> error is IOException
}

internal fun providerRetryDelayMillis(attempt: Int, retryAfterMillis: Long? = null): Long {
    retryAfterMillis?.let { return it.coerceIn(0L, 15_000L) }
    val base = min(15_000L, 500L * (1L shl (attempt - 1).coerceAtMost(5)))
    return base + Random.nextLong(0L, 250L)
}

internal fun parseRetryAfterMillis(value: String?): Long? {
    return value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1_000L)
}
