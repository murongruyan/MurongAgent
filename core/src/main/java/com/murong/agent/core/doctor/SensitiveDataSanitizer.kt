package com.murong.agent.core.doctor

object SensitiveDataSanitizer {

    private val bearerPattern = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val jwtPattern = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b""")
    private val emailPattern = Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
    private val keyedSecretPattern = Regex(
        """(?i)\b(api[_-]?key|access[_-]?token|refresh[_-]?token|session[_-]?token|secret|password)\b(\s*[:=]\s*)(["']?)([^\s,"'}\]]{6,})(\3)"""
    )
    private val githubTokenPattern = Regex("""\bgh[pousr]_[A-Za-z0-9]{8,}\b""")
    private val windowsPathPattern = Regex("""\b[A-Za-z]:(?:\\+|/)(?:[^\\/:*?"<>|\r\n]+(?:\\+|/))*[^\\/:*?"<>|\r\n]*""")
    private val unixPathPattern = Regex("""(?<!https:)(?<!http:)(?<!file:)(?<!content:)(?<![A-Za-z0-9])/+(?:[^/\s"'`]+/+)+[^/\s"'`]*""")
    private val controlCharacterPattern = Regex("""[\u0000-\u001F]+""")

    fun sanitizeText(value: String, redactPaths: Boolean = true): String {
        if (value.isBlank()) return value
        val withoutSecrets = value
            .let(::sanitizeBearer)
            .let(::sanitizeJwt)
            .let(::sanitizeEmails)
            .let(::sanitizeKeyedSecrets)
            .let(::sanitizeGithubTokens)
        return if (redactPaths) sanitizePaths(withoutSecrets) else withoutSecrets
    }

    fun sanitizeTransportHeaderValue(value: String): String {
        return value
            .replace(controlCharacterPattern, " ")
            .replace(Regex("""[\r\n]+"""), " ")
            .trim()
    }

    private fun sanitizeBearer(value: String): String {
        return bearerPattern.replace(value) { _ -> "Bearer [REDACTED_BEARER]" }
    }

    private fun sanitizeJwt(value: String): String {
        return jwtPattern.replace(value) { _ -> "[REDACTED_JWT]" }
    }

    private fun sanitizeEmails(value: String): String {
        return emailPattern.replace(value) { _ -> "[REDACTED_EMAIL]" }
    }

    private fun sanitizeKeyedSecrets(value: String): String {
        return keyedSecretPattern.replace(value) { match ->
            val key = match.groupValues[1]
            val separator = match.groupValues[2]
            val quote = match.groupValues[3]
            "$key$separator${quote}[REDACTED_SECRET]$quote"
        }
    }

    private fun sanitizeGithubTokens(value: String): String {
        return githubTokenPattern.replace(value) { _ -> "[REDACTED_TOKEN]" }
    }

    private fun sanitizePaths(value: String): String {
        return value
            .replace(windowsPathPattern) { _ -> "[REDACTED_PATH]" }
            .replace(unixPathPattern) { _ -> "[REDACTED_PATH]" }
    }
}
