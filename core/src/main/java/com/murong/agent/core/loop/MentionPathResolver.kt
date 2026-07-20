package com.murong.agent.core.loop

private val WINDOWS_ABSOLUTE_MENTION_PATH = Regex("^[A-Za-z]:[\\\\/].*")

/**
 * Keeps Android absolute paths in their POSIX form while accepting a Windows-style path from
 * copied remote-workspace output. The previous blanket slash replacement turned
 * /storage/emulated/0/... into an invalid Android path.
 */
internal fun normalizeDirectMentionPath(path: String): String {
    val normalized = path.trim()
    return if (WINDOWS_ABSOLUTE_MENTION_PATH.matches(normalized)) {
        normalized.replace('/', '\\')
    } else {
        normalized
    }
}

internal fun isDirectMentionPath(path: String): Boolean {
    val normalized = path.trim()
    return normalized.startsWith('/') || WINDOWS_ABSOLUTE_MENTION_PATH.matches(normalized)
}
