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

internal fun providerFailureUserVisibleMessage(error: Throwable, hasImages: Boolean = false): String {
    val detail = error.message.orEmpty().lowercase()
    if (hasImages && error is ProviderHttpException && error.statusCode == 400 &&
        ("content.type" in detail || "image_url" in detail || "input_image" in detail)
    ) {
        return "当前模型接口不支持图片输入，请切换支持视觉的模型，或移除图片后重试。"
    }
    return when (error) {
        is ProviderHttpException -> when (error.statusCode) {
            400, 404, 405, 409, 422 -> "当前模型连接不接受这次请求，请检查模型与接口配置后重试。"
            429 -> "模型服务当前请求过多，请稍后再试。"
            in 500..599 -> "模型服务暂时不可用，请稍后再试。"
            else -> "模型请求未成功，请检查模型连接后重试。"
        }
        else -> "网络连接异常，请检查网络后重试。"
    }
}

internal fun providerRetryDelayMillis(attempt: Int, retryAfterMillis: Long? = null): Long {
    retryAfterMillis?.let { return it.coerceIn(0L, 15_000L) }
    val base = min(15_000L, 500L * (1L shl (attempt - 1).coerceAtMost(5)))
    return base + Random.nextLong(0L, 250L)
}

internal fun parseRetryAfterMillis(value: String?): Long? {
    return value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1_000L)
}
