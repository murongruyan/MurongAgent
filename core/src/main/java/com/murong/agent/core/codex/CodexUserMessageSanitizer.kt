package com.murong.agent.core.codex

import com.murong.agent.core.doctor.SensitiveDataSanitizer

/** Converts app-server errors into concise, user-facing messages without exposing transport details. */
object CodexUserMessageSanitizer {
    fun sanitize(value: String?): String {
        val raw = value.orEmpty().trim()
        if (raw.isBlank()) return "未知错误"

        val normalized = raw.lowercase()
        val message = when {
            normalized.contains("account_deactivated") ||
                normalized.contains("account deleted") ||
                normalized.contains("account has been deleted") ||
                normalized.contains("account has been deactivated") ->
                "该 ChatGPT 账号已被 OpenAI 删除或停用，请更换账号后重试。"

            normalized.contains("invalid_grant") ||
                normalized.contains("invalid token") ||
                normalized.contains("unauthorized") ||
                normalized.contains("401") ->
                "ChatGPT 登录已失效，请重新登录。"

            normalized.contains("error sending request for url") ||
                normalized.contains("timed out") ||
                normalized.contains("timeout") ||
                normalized.contains("connection refused") ||
                normalized.contains("dns") ||
                normalized.contains("network") ->
                "连接 OpenAI 失败，请检查网络或代理后重试。"

            else -> SensitiveDataSanitizer.sanitizeText(raw)
        }
        return message
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_MESSAGE_CHARS)
            .ifBlank { "未知错误" }
    }

    private const val MAX_MESSAGE_CHARS = 500
}
