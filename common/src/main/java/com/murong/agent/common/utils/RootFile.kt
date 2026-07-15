package com.murong.agent.common.utils

import com.murong.agent.common.shell.KeepShellPublic

/**
 * Root 文件操作
 * 移植自 murongdiaodu-apk/common/.../shell/RootFile.kt
 *
 * 通过 Root Shell 执行文件存在性检查。
 */
object RootFile {

    enum class ReadStatus {
        OK,
        NOT_FOUND,
        IS_DIRECTORY,
        ERROR
    }

    data class OperationResult(
        val success: Boolean,
        val output: String = "",
        val error: String? = null
    )

    data class ReadResult(
        val content: String,
        val truncated: Boolean = false,
        val status: ReadStatus = ReadStatus.OK
    )

    data class ListResult(
        val entries: List<String>,
        val truncated: Boolean = false
    )

    fun fileExists(path: String): Boolean {
        val result = KeepShellPublic.doCmdSync("[ -f \"$path\" ] && echo 1 || echo 0")
        return result.trim() == "1"
    }

    fun dirExists(path: String): Boolean {
        val result = KeepShellPublic.doCmdSync("[ -d \"$path\" ] && echo 1 || echo 0")
        return result.trim() == "1"
    }

    fun exists(path: String): Boolean {
        val result = KeepShellPublic.doCmdSync("[ -e \"$path\" ] && echo 1 || echo 0")
        return result.trim() == "1"
    }

    fun readFile(path: String, maxBytes: Int = 100_000): String {
        // Guard: check file size before reading to avoid OOM on large files
        val sizeResult = KeepShellPublic.doCmdSync("stat -c%s \"$path\" 2>/dev/null || echo 0").trim()
        val size = sizeResult.toLongOrNull() ?: 0L
        if (size > maxBytes) {
            return "error: file too large (${size} bytes, max ${maxBytes}). Use readFilePaged to read in windows."
        }
        return KeepShellPublic.doCmdSync("cat \"$path\"")
    }

    fun readFilePaged(
        path: String,
        offsetLines: Int = 0,
        lineLimit: Int = DEFAULT_READ_LINE_LIMIT
    ): ReadResult {
        val safeOffset = offsetLines.coerceAtLeast(0)
        val safeLimit = lineLimit.coerceIn(1, MAX_READ_LINE_LIMIT)
        val startLine = safeOffset + 1
        val endLine = startLine + safeLimit - 1
        val marker = "__RSNX_TRUNCATED__"
        val statusMarker = "__RSNX_STATUS__"
        val raw = KeepShellPublic.doCmdSync(
            """
            if [ ! -e "$path" ]; then
                printf '%sNOT_FOUND' '$statusMarker'
            elif [ -d "$path" ]; then
                printf '%sIS_DIRECTORY' '$statusMarker'
            else
                sed -n '${startLine},${endLine}p;${endLine + 1}{s/.*/$marker/;p;q;}' "$path" 2>/dev/null
                printf '\n%sOK' '$statusMarker'
            fi
            """.trimIndent()
        )
        if (raw.startsWith("error:")) {
            return ReadResult(content = raw, truncated = false, status = ReadStatus.ERROR)
        }
        val statusIndex = raw.lastIndexOf(statusMarker)
        if (statusIndex == -1) {
            return ReadResult(
                content = "error: missing read status marker",
                truncated = false,
                status = ReadStatus.ERROR
            )
        }
        val status = when (raw.substring(statusIndex + statusMarker.length).trim()) {
            "OK" -> ReadStatus.OK
            "NOT_FOUND" -> ReadStatus.NOT_FOUND
            "IS_DIRECTORY" -> ReadStatus.IS_DIRECTORY
            else -> ReadStatus.ERROR
        }
        if (status != ReadStatus.OK) {
            return ReadResult(content = "", truncated = false, status = status)
        }
        val contentWithMarker = raw.substring(0, statusIndex).trimEnd('\n')
        val lines = contentWithMarker.lines()
        val truncated = lines.lastOrNull() == marker
        val content = if (truncated) {
            lines.dropLast(1).joinToString("\n")
        } else {
            contentWithMarker
        }
        return ReadResult(content = content, truncated = truncated, status = ReadStatus.OK)
    }

    fun writeFile(path: String, content: String) {
        writeFileChecked(path, content)
    }

    fun writeFileChecked(path: String, content: String): OperationResult {
        val parent = parentPath(path)
        if (parent != null && !dirExists(parent)) {
            return OperationResult(
                success = false,
                error = "Parent directory does not exist: $parent"
            )
        }

        val escapedContent = escapeForSingleQuotedShell(content)
        // 用 printf 保留多行内容且不额外追加换行。
        val commandResult = runCheckedCommand("printf '%s' '$escapedContent' > \"$path\"")
        if (!commandResult.success) {
            return commandResult
        }

        return if (fileExists(path)) {
            OperationResult(success = true, output = "OK")
        } else {
            OperationResult(success = false, error = "File was not created: $path")
        }
    }

    fun chmod(path: String, mode: String) {
        chmodChecked(path, mode)
    }

    fun chmodChecked(path: String, mode: String): OperationResult {
        if (!exists(path)) {
            return OperationResult(success = false, error = "Target does not exist: $path")
        }
        return runCheckedCommand("chmod $mode \"$path\"")
    }

    fun delete(path: String) {
        deleteChecked(path)
    }

    fun deleteChecked(path: String): OperationResult {
        if (!exists(path)) {
            return OperationResult(success = false, error = "Target does not exist: $path")
        }
        return runCheckedCommand("rm -rf \"$path\"")
    }

    fun ls(path: String): List<String> {
        return lsPaged(path).entries
    }

    fun lsPaged(
        path: String,
        offset: Int = 0,
        limit: Int = DEFAULT_LIST_LIMIT
    ): ListResult {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        val startLine = safeOffset + 1
        val endLine = startLine + safeLimit - 1
        val marker = "__RSNX_TRUNCATED__"
        val raw = KeepShellPublic.doCmdSync(
            "ls -1A \"$path\" 2>/dev/null | sed -n '${startLine},${endLine}p;${endLine + 1}{s/.*/$marker/;p;q;}'"
        )
        if (raw.isBlank() || raw.startsWith("error:")) {
            return ListResult(entries = emptyList(), truncated = false)
        }
        val lines = raw.lines().filter { it.isNotBlank() }
        val truncated = lines.lastOrNull() == marker
        val entries = if (truncated) lines.dropLast(1) else lines
        return ListResult(entries = entries, truncated = truncated)
    }

    private fun escapeForSingleQuotedShell(content: String): String {
        return content.replace("'", "'\"'\"'")
    }

    private fun parentPath(path: String): String? {
        val normalized = path.trim().trimEnd('/', '\\')
        val lastSlash = maxOf(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'))
        if (lastSlash <= 0) return null
        return normalized.substring(0, lastSlash)
    }

    private fun runCheckedCommand(command: String): OperationResult {
        val marker = "__RSNX_EXIT__"
        val raw = KeepShellPublic.doCmdSync("($command) 2>&1; printf '\\n$marker%s' \$?")
        val markerIndex = raw.lastIndexOf(marker)
        if (markerIndex == -1) {
            return OperationResult(success = false, error = "Unable to determine command status: $raw")
        }

        val output = raw.substring(0, markerIndex).trimEnd()
        val exitCode = raw.substring(markerIndex + marker.length).trim().toIntOrNull()
        return if (exitCode == 0) {
            OperationResult(success = true, output = output)
        } else {
            OperationResult(
                success = false,
                output = output,
                error = output.ifBlank { "Command failed with exit code $exitCode" }
            )
        }
    }

    private const val DEFAULT_READ_LINE_LIMIT = 200
    private const val MAX_READ_LINE_LIMIT = 2000
    private const val DEFAULT_LIST_LIMIT = 200
    private const val MAX_LIST_LIMIT = 1000
}
