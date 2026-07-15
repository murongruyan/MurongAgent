package com.murong.agent.core.tool

import com.murong.agent.common.shell.KeepShellPublic
import com.murong.agent.common.toolchain.ToolchainManager
import com.murong.agent.common.utils.RootFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 代码搜索工具——按关键字/正则搜索代码并返回精确文件与行号。
 */
class CodeSearchTool : Tool {

    override val name = "code_search"
    override val description =
        "搜索代码或文本并定位到精确文件与行号。优先用于源码定位，不要再用 shell + grep 手动拼。已默认弱化 build、.gradle、out、target、intermediates、mapping 等生成产物；如果你已知类名或文件名，先找精确文件，再读局部上下文。"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "pattern" to mapOf(
                "type" to "string",
                "description" to "要搜索的关键字或正则表达式"
            ),
            "path" to mapOf(
                "type" to "string",
                "description" to "要搜索的文件或目录绝对路径"
            ),
            "maxResults" to mapOf(
                "type" to "integer",
                "description" to "最多返回多少条匹配，默认 20，最大 100"
            ),
            "contextLines" to mapOf(
                "type" to "integer",
                "description" to "每条匹配前后附带多少行上下文，默认 2，最大 6"
            ),
            "fileGlob" to mapOf(
                "type" to "string",
                "description" to "可选文件过滤，如 *.kt、*.java、*.md"
            ),
            "excludeGlob" to mapOf(
                "type" to "string",
                "description" to "可选排除模式，支持逗号/分号/换行分隔多个模式，如 */build/*,*/out/*,*.min.js"
            ),
            "caseSensitive" to mapOf(
                "type" to "boolean",
                "description" to "是否区分大小写，默认 true"
            ),
            "includeGeneratedArtifacts" to mapOf(
                "type" to "boolean",
                "description" to "是否包含 build、intermediates、mapping、out、target 等生成产物，默认 false"
            )
        ),
        "required" to listOf("pattern", "path")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: String): String {
        return executeWithResult(args).output
    }

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val pattern = obj["pattern"]?.jsonPrimitive?.content?.trim()
                ?: return ToolExecutionResult("Error: 'pattern' required")
            val path = obj["path"]?.jsonPrimitive?.content?.trim()
                ?: return ToolExecutionResult("Error: 'path' required")
            val maxResults = (obj["maxResults"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_RESULTS)
                .coerceIn(1, MAX_MAX_RESULTS)
            val contextLines = (obj["contextLines"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CONTEXT_LINES)
                .coerceIn(0, MAX_CONTEXT_LINES)
            val fileGlob = obj["fileGlob"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { null }
            val excludeGlob = obj["excludeGlob"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { null }
            val caseSensitive = obj["caseSensitive"]?.jsonPrimitive?.booleanOrNull ?: true
            val includeGeneratedArtifacts =
                obj["includeGeneratedArtifacts"]?.jsonPrimitive?.booleanOrNull ?: false
            val effectiveExcludes = resolveEffectiveExcludeGlobs(
                excludeGlob = excludeGlob,
                includeGeneratedArtifacts = includeGeneratedArtifacts
            )

            if (pattern.isBlank()) {
                return ToolExecutionResult("Error: 'pattern' cannot be blank")
            }
            if (!KeepShellPublic.checkRoot()) {
                return ToolExecutionResult("Error: Root shell is not available. Cannot search code.")
            }
            if (!RootFile.exists(path)) {
                return ToolExecutionResult("Error: Search path not found: $path")
            }

            val engine = detectSearchEngine()
            val rawSearch = when (engine) {
                SearchEngine.BUNDLED_RG,
                SearchEngine.RG -> runCheckedShellCommand(
                    buildRgCommand(
                        pattern = pattern,
                        path = path,
                        maxResults = maxResults,
                        fileGlob = fileGlob,
                        excludeGlobs = effectiveExcludes,
                        caseSensitive = caseSensitive
                    )
                )
                SearchEngine.GREP -> runCheckedShellCommand(
                    buildGrepCommand(
                        pattern = pattern,
                        path = path,
                        maxResults = maxResults,
                        fileGlob = fileGlob,
                        excludeGlobs = effectiveExcludes,
                        caseSensitive = caseSensitive
                    )
                )
            }

            if (rawSearch.exitCode !in setOf(0, 1)) {
                return ToolExecutionResult(
                    "Error running code search (${engine.label}): ${rawSearch.output.ifBlank { "exit=${rawSearch.exitCode}" }}"
                )
            }

            val matches = parseMatches(rawSearch.output)
                .sortedWith(compareBy<SearchMatch> { classifySearchPath(it.filePath).sortOrder }
                    .thenBy { it.filePath.length }
                    .thenBy { it.lineNumber })
                .take(maxResults)

            if (matches.isEmpty()) {
                return ToolExecutionResult(
                    buildString {
                        append("未找到匹配结果。\n")
                        append("Search engine: ${engine.label}\n")
                        append("Pattern: $pattern\n")
                        append("Path: $path")
                        fileGlob?.let { append("\nFile glob: $it") }
                        if (effectiveExcludes.isNotEmpty()) {
                            append("\nExclude glob: ${effectiveExcludes.joinToString(", ")}")
                        }
                        append(buildNoMatchGuidance(pattern))
                    }
                )
            }

            ToolExecutionResult(
                output = formatMatches(
                    engine = engine,
                    pattern = pattern,
                    path = path,
                    matches = matches,
                    contextLines = contextLines,
                    fileGlob = fileGlob,
                    excludeGlobs = effectiveExcludes,
                    caseSensitive = caseSensitive
                )
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error running code_search: ${e.message}")
        }
    }

    private fun detectSearchEngine(): SearchEngine {
        if (ToolchainManager.getBundledCommandPath("rg") != null) {
            return SearchEngine.BUNDLED_RG
        }
        val result = KeepShellPublic.doCmdSync("command -v rg >/dev/null 2>&1 && echo rg || echo grep")
        return if (result.lineSequence().firstOrNull()?.trim() == "rg") SearchEngine.RG else SearchEngine.GREP
    }

    private fun buildRgCommand(
        pattern: String,
        path: String,
        maxResults: Int,
        fileGlob: String?,
        excludeGlobs: List<String>,
        caseSensitive: Boolean
    ): String {
        val rgExecutable = ToolchainManager.getBundledCommandPath("rg")?.let(::shellQuote) ?: "rg"
        val options = buildList {
            add("--line-number")
            add("--no-heading")
            add("--color")
            add("never")
            add("--max-count")
            add(maxResults.toString())
            if (!caseSensitive) add("-i")
            fileGlob?.let {
                add("-g")
                add(shellQuote(it))
            }
            excludeGlobs.forEach {
                add("-g")
                add(shellQuote("!$it"))
            }
        }.joinToString(" ")
        return "$rgExecutable $options -- ${shellQuote(pattern)} ${shellQuote(path)}"
    }

    private fun buildGrepCommand(
        pattern: String,
        path: String,
        maxResults: Int,
        fileGlob: String?,
        excludeGlobs: List<String>,
        caseSensitive: Boolean
    ): String {
        val grepExecutable = ToolchainManager.getBundledCommandPath("grep")?.let(::shellQuote) ?: "grep"
        val findExecutable = ToolchainManager.getBundledCommandPath("find")?.let(::shellQuote) ?: "find"
        val sedExecutable = ToolchainManager.getBundledCommandPath("sed")?.let(::shellQuote) ?: "sed"
        val grepOptions = buildList {
            add("-nH")
            add("-E")
            if (!caseSensitive) add("-i")
        }.joinToString(" ")

        return if (RootFile.fileExists(path)) {
            "$grepExecutable $grepOptions -- ${shellQuote(pattern)} ${shellQuote(path)} | $sedExecutable -n '1,${maxResults}p'"
        } else {
            val findFilters = buildString {
                fileGlob?.let { append(" -name ${shellQuote(it)}") }
                excludeGlobs.forEach {
                    if (it.contains('/')) {
                        append(" ! -path ${shellQuote(it)}")
                    } else {
                        append(" ! -name ${shellQuote(it)}")
                    }
                }
            }
            "$findExecutable ${shellQuote(path)} -type f$findFilters -exec $grepExecutable $grepOptions -- ${shellQuote(pattern)} {} + 2>/dev/null | $sedExecutable -n '1,${maxResults}p'"
        }
    }

    private fun parseMatches(output: String): List<SearchMatch> {
        return output.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trimEnd()
                if (trimmed.isBlank()) return@mapNotNull null
                val match = MATCH_LINE_REGEX.matchEntire(trimmed) ?: return@mapNotNull null
                val filePath = match.groupValues[1]
                val lineNumber = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val lineContent = match.groupValues[3]
                SearchMatch(
                    filePath = filePath,
                    lineNumber = lineNumber,
                    lineContent = lineContent
                )
            }
            .toList()
    }

    private fun formatMatches(
        engine: SearchEngine,
        pattern: String,
        path: String,
        matches: List<SearchMatch>,
        contextLines: Int,
        fileGlob: String?,
        excludeGlobs: List<String>,
        caseSensitive: Boolean
    ): String {
        return buildString {
            append("Search engine: ${engine.label}\n")
            append("Pattern: $pattern\n")
            append("Path: $path\n")
            append("Case sensitive: ${if (caseSensitive) "true" else "false"}\n")
            fileGlob?.let { append("File glob: $it\n") }
            if (excludeGlobs.isNotEmpty()) {
                append("Exclude glob: ${excludeGlobs.joinToString(", ")}\n")
            }
            append("Source-first sort: true\n")
            append("Matches returned: ${matches.size}\n\n")

            matches.forEachIndexed { index, match ->
                append("${index + 1}. File: ${match.filePath}\n")
                append("   Line: ${match.lineNumber}\n")
                append("   Match: ${match.lineContent}\n")
                if (contextLines > 0) {
                    val snippet = readContextSnippet(match.filePath, match.lineNumber, contextLines)
                    if (snippet.isNotBlank()) {
                        append("   Context:\n")
                        snippet.lineSequence().forEach { snippetLine ->
                            append("   ")
                            append(snippetLine)
                            append('\n')
                        }
                    }
                }
                if (index != matches.lastIndex) {
                    append('\n')
                }
            }
        }.trimEnd()
    }

    private fun readContextSnippet(path: String, lineNumber: Int, contextLines: Int): String {
        val startLine = (lineNumber - contextLines).coerceAtLeast(1)
        val offset = startLine - 1
        val lineLimit = (contextLines * 2) + 1
        val result = RootFile.readFilePaged(path, offsetLines = offset, lineLimit = lineLimit)
        if (result.status != RootFile.ReadStatus.OK) return ""
        val lines = result.content.lines()
        return buildString {
            lines.forEachIndexed { index, line ->
                val currentLineNumber = startLine + index
                val marker = if (currentLineNumber == lineNumber) ">" else " "
                append(marker)
                append(" ")
                append(currentLineNumber.toString().padStart(4, ' '))
                append(" | ")
                append(line)
                if (index != lines.lastIndex) append('\n')
            }
        }
    }

    private fun runCheckedShellCommand(command: String): ShellCommandResult {
        val marker = "__RSNX_EXIT__"
        val raw = KeepShellPublic.doCmdSync("($command) 2>&1; printf '\\n$marker%s' \$?")
        val markerIndex = raw.lastIndexOf(marker)
        if (markerIndex == -1) {
            return ShellCommandResult(
                output = raw,
                exitCode = -1
            )
        }
        return ShellCommandResult(
            output = raw.substring(0, markerIndex).trimEnd(),
            exitCode = raw.substring(markerIndex + marker.length).trim().toIntOrNull() ?: -1
        )
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }

    private fun buildNoMatchGuidance(pattern: String): String {
        val normalized = pattern.trim()
        if (normalized.isBlank()) return ""
        val looksLikeFileName = normalized.contains('.') && !normalized.contains(' ')
        val looksLikeClassName =
            normalized.none { it.isWhitespace() } &&
                normalized.any(Char::isUpperCase) &&
                normalized.none { it in setOf('*', '|', '(', ')', '[', ']', '{', '}', '\\') }
        if (!looksLikeFileName && !looksLikeClassName) return ""
        return buildString {
            append("\nHint: 当前 pattern 像文件名或类名。")
            append(" 先用 file.list 或 shell find 在 src/main、src/test、app/src、core/src、common/src 里定位精确路径，再读取局部上下文，通常比直接全文搜索更稳。")
        }
    }

    internal fun resolveEffectiveExcludeGlobs(
        excludeGlob: String?,
        includeGeneratedArtifacts: Boolean
    ): List<String> {
        val customGlobs = parseGlobList(excludeGlob)
        if (includeGeneratedArtifacts) return customGlobs
        return (DEFAULT_EXCLUDE_GLOBS + customGlobs).distinct()
    }

    internal fun sortFilePathsForDisplay(paths: List<String>): List<String> {
        return paths.sortedWith(
            compareBy<String> { classifySearchPath(it).sortOrder }
                .thenBy { it.length }
                .thenBy { it }
        )
    }

    private fun parseGlobList(raw: String?): List<String> {
        return raw.orEmpty()
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun classifySearchPath(path: String): SearchPathBucket {
        val normalized = path.replace('\\', '/').lowercase()
        return when {
            "/src/main/" in normalized -> SearchPathBucket.SOURCE_MAIN
            "/src/test/" in normalized || "/src/androidtest/" in normalized -> SearchPathBucket.SOURCE_TEST
            "/src/" in normalized -> SearchPathBucket.SOURCE_OTHER
            "/build/" in normalized ||
                "/intermediates/" in normalized ||
                "/mapping/" in normalized ||
                "/.gradle/" in normalized ||
                "/out/" in normalized ||
                "/target/" in normalized -> SearchPathBucket.GENERATED
            else -> SearchPathBucket.OTHER
        }
    }

    private data class SearchMatch(
        val filePath: String,
        val lineNumber: Int,
        val lineContent: String
    )

    private data class ShellCommandResult(
        val output: String,
        val exitCode: Int
    )

    private enum class SearchEngine(val label: String) {
        BUNDLED_RG("bundled-rg"),
        RG("rg"),
        GREP("grep")
    }

    private enum class SearchPathBucket(val sortOrder: Int) {
        SOURCE_MAIN(0),
        SOURCE_TEST(1),
        SOURCE_OTHER(2),
        OTHER(3),
        GENERATED(4)
    }

    private companion object {
        const val DEFAULT_MAX_RESULTS = 20
        const val MAX_MAX_RESULTS = 100
        const val DEFAULT_CONTEXT_LINES = 2
        const val MAX_CONTEXT_LINES = 6
        val DEFAULT_EXCLUDE_GLOBS = listOf(
            "*/build/*",
            "*/.gradle/*",
            "*/out/*",
            "*/target/*",
            "*/intermediates/*",
            "*/mapping/*",
            "*/generated/*",
            "*/node_modules/*"
        )
        val MATCH_LINE_REGEX = Regex("""^(.*?):(\d+):(.*)$""")
    }
}
