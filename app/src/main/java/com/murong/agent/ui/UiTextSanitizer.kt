package com.murong.agent.ui

private val ANSI_ESCAPE_REGEX = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")

fun sanitizeForUiDisplay(raw: String): String {
    if (raw.isEmpty()) return raw
    val withoutAnsi = ANSI_ESCAPE_REGEX.replace(raw, "")
    val normalizedLines = withoutAnsi
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    return buildString(normalizedLines.length) {
        normalizedLines.forEach { ch ->
            when {
                ch == '\n' || ch == '\t' -> append(ch)
                ch.code in 0x20..0x7E || ch.code >= 0xA0 -> append(ch)
            }
        }
    }
}
