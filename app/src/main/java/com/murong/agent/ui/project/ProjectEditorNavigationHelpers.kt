package com.murong.agent.ui.project

import java.io.File
import java.util.Locale

internal fun searchProjectEntries(
    root: File,
    query: String,
    scope: ProjectSearchScope,
    limit: Int = MAX_PROJECT_SEARCH_RESULTS
): ProjectSearchResultUi {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) {
        return ProjectSearchResultUi(
            query = query,
            scope = scope,
            hits = emptyList(),
            totalCount = 0,
            truncated = false
        )
    }
    val lowerQuery = normalizedQuery.lowercase(Locale.getDefault())
    val hits = mutableListOf<ProjectSearchHitUi>()
    var totalCount = 0
    var truncated = false

    root.walkTopDown()
        .onEnter { dir -> !shouldSkipSearchDir(dir, root) }
        .filter { it.isFile }
        .forEach { file ->
            val fileName = file.name.lowercase(Locale.getDefault())
            val relativePath = relativeProjectPath(root.absolutePath, file.absolutePath)
            val fileNameMatched = (scope == ProjectSearchScope.ALL || scope == ProjectSearchScope.FILE_NAME) &&
                fileName.contains(lowerQuery)
            if (fileNameMatched) {
                totalCount++
                if (hits.size < limit) {
                    hits += ProjectSearchHitUi(
                        filePath = file.absolutePath,
                        relativePath = relativePath,
                        preview = "文件名匹配",
                        fileType = "文件名"
                    )
                } else {
                    truncated = true
                }
                if (scope == ProjectSearchScope.FILE_NAME) return@forEach
            }

            if (scope == ProjectSearchScope.FILE_NAME || file.length() > MAX_PROJECT_SEARCH_FILE_BYTES) {
                return@forEach
            }

            val contentResult = runCatching { file.readText() }.getOrNull() ?: return@forEach
            val matchLine = findMatchingLine(contentResult, lowerQuery) ?: return@forEach
            totalCount++
            if (hits.size < limit) {
                hits += ProjectSearchHitUi(
                    filePath = file.absolutePath,
                    relativePath = relativePath,
                    lineNumber = matchLine.first,
                    preview = matchLine.second,
                    fileType = "内容"
                )
            } else {
                truncated = true
            }
        }

    return ProjectSearchResultUi(
        query = query,
        scope = scope,
        hits = hits,
        totalCount = totalCount,
        truncated = truncated
    )
}

internal fun relativeProjectPath(rootPath: String, filePath: String): String {
    return filePath.removePrefix(rootPath).trimStart(File.separatorChar).ifBlank {
        File(filePath).name.ifBlank { filePath }
    }
}

internal fun formatProjectFileSize(size: Long): String {
    return when {
        size >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / 1024f / 1024f)
        size >= 1024 -> String.format(Locale.US, "%.1f KB", size / 1024f)
        else -> "$size B"
    }
}

private fun findMatchingLine(content: String, lowerQuery: String): Pair<Int, String>? {
    content.lineSequence().forEachIndexed { index, line ->
        if (line.lowercase(Locale.getDefault()).contains(lowerQuery)) {
            return (index + 1) to line.trim().ifBlank { "(空行命中)" }
        }
    }
    return null
}

internal fun shouldSkipSearchDir(dir: File, root: File): Boolean {
    if (dir.absolutePath == root.absolutePath) return false
    return dir.name in SEARCH_SKIPPED_DIR_NAMES
}

internal fun projectLanguageForPath(path: String?): String? {
    val name = path?.substringAfterLast(File.separatorChar)?.lowercase(Locale.getDefault()) ?: return null
    val ext = name.substringAfterLast('.', name)
    return when (ext) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "gradle", "groovy", "gvy" -> "groovy"
        "js", "mjs", "cjs" -> "javascript"
        "jsx" -> "jsx"
        "ts", "mts", "cts" -> "typescript"
        "tsx" -> "tsx"
        "rs" -> "rust"
        "c" -> "c"
        "h" -> "h"
        "cpp", "cc", "cxx" -> "cpp"
        "hpp", "hh", "hxx" -> "hpp"
        "json", "jsonc", "geojson", "webmanifest" -> "json"
        "toml" -> "toml"
        "pro" -> "pro"
        "ini" -> "ini"
        "conf", "cfg" -> "conf"
        "cmake" -> "cmake"
        "sql" -> "sql"
        "css" -> "css"
        "html", "htm" -> "html"
        "xml" -> "xml"
        "md", "mdown", "markdown" -> "markdown"
        "py" -> "python"
        "lua", "luau" -> "lua"
        "sh", "bash" -> "bash"
        "yml", "yaml" -> "yaml"
        "properties", "prop" -> "properties"
        else -> null
    }
}

internal fun projectSearchScopeLabel(scope: ProjectSearchScope): String = when (scope) {
    ProjectSearchScope.ALL -> "全部"
    ProjectSearchScope.FILE_NAME -> "文件名"
    ProjectSearchScope.TEXT -> "内容"
}

internal fun buildProjectOutlineEntries(
    foldRegions: List<ProjectFoldRegion>,
    language: String?
): List<ProjectOutlineEntry> {
    val sorted = foldRegions.sortedBy { it.startLine }
    return sorted.map { region ->
        ProjectOutlineEntry(
            startLine = region.startLine,
            lineNumber = region.startLine,
            depth = outlineDepthForRegion(region, sorted),
            label = normalizeOutlineLabel(region.headerLine, language),
            kind = outlineKindForHeader(region.headerLine, language)
        )
    }
}

private fun outlineDepthForRegion(
    region: ProjectFoldRegion,
    allRegions: List<ProjectFoldRegion>
): Int {
    return allRegions.count { candidate ->
        candidate.startLine < region.startLine && candidate.endLine >= region.endLine
    }
}

private fun normalizeOutlineLabel(headerLine: String, language: String?): String {
    val trimmed = headerLine.trim()
        .removeSuffix("{")
        .removeSuffix(":")
        .trim()
    val compact = trimmed.replace(Regex("\\s+"), " ")
    val kind = outlineKindForHeader(headerLine, language)
    return when (kind) {
        "类" -> compact.ifBlank { "未命名类" }
        "函数" -> compact.ifBlank { "未命名函数" }
        else -> compact.ifBlank { "代码块" }
    }
}

private fun outlineKindForHeader(headerLine: String, language: String?): String {
    val normalized = headerLine.trim().lowercase(Locale.getDefault())
    return when {
        normalized.startsWith("class ") || normalized.startsWith("interface ") ||
            normalized.startsWith("object ") || normalized.startsWith("enum ") ||
            normalized.startsWith("data class ") || normalized.startsWith("sealed class ") -> "类"
        normalized.startsWith("fun ") || normalized.contains(" fun ") ||
            normalized.startsWith("def ") || normalized.startsWith("async def ") ||
            normalized.contains("=>") -> "函数"
        language?.lowercase(Locale.getDefault()) == "python" && normalized.endsWith(":") -> "代码块"
        else -> "代码块"
    }
}

internal fun detectProjectFoldRegions(content: String, language: String?): List<ProjectFoldRegion> {
    val lines = content.lines()
    if (lines.size < 3) return emptyList()
    return when (language?.lowercase(Locale.getDefault())) {
        "python" -> detectIndentFoldRegions(lines)
        else -> detectBraceFoldRegions(lines)
    }
}

private fun detectBraceFoldRegions(lines: List<String>): List<ProjectFoldRegion> {
    val stack = ArrayDeque<Int>()
    val regions = mutableListOf<ProjectFoldRegion>()
    lines.forEachIndexed { index, line ->
        val lineNumber = index + 1
        line.forEach { ch ->
            when (ch) {
                '{' -> stack.addLast(lineNumber)
                '}' -> {
                    val startLine = stack.removeLastOrNull() ?: return@forEach
                    if (lineNumber - startLine >= 2) {
                        val headerLine = lines.getOrNull(startLine - 1)?.trimEnd().orEmpty()
                        if (isFoldableHeaderLine(headerLine)) {
                            regions += ProjectFoldRegion(
                                startLine = startLine,
                                endLine = lineNumber,
                                headerLine = headerLine
                            )
                        }
                    }
                }
            }
        }
    }
    return regions
        .distinctBy { it.startLine to it.endLine }
        .sortedBy { it.startLine }
}

private fun detectIndentFoldRegions(lines: List<String>): List<ProjectFoldRegion> {
    val regions = mutableListOf<ProjectFoldRegion>()
    var index = 0
    while (index < lines.size - 1) {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.isBlank() || !isPythonFoldableHeaderLine(trimmed)) {
            index++
            continue
        }
        val currentIndent = leadingIndentWidth(line)
        var nextIndex = index + 1
        while (nextIndex < lines.size && lines[nextIndex].trim().isBlank()) {
            nextIndex++
        }
        if (nextIndex >= lines.size) break
        val childIndent = leadingIndentWidth(lines[nextIndex])
        if (childIndent <= currentIndent) {
            index++
            continue
        }
        var endIndex = nextIndex
        var scanIndex = nextIndex + 1
        while (scanIndex < lines.size) {
            val candidate = lines[scanIndex]
            if (candidate.trim().isNotBlank() && leadingIndentWidth(candidate) <= currentIndent) {
                break
            }
            if (candidate.trim().isNotBlank()) {
                endIndex = scanIndex
            }
            scanIndex++
        }
        if (endIndex - index >= 2) {
            regions += ProjectFoldRegion(
                startLine = index + 1,
                endLine = endIndex + 1,
                headerLine = line.trimEnd()
            )
        }
        index++
    }
    return regions
}

private fun isFoldableHeaderLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isBlank()) return false
    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return false
    if (!trimmed.contains('{')) return false
    return true
}

private fun isPythonFoldableHeaderLine(line: String): Boolean {
    return line.startsWith("def ") ||
        line.startsWith("class ") ||
        line.startsWith("async def ") ||
        line.endsWith(":")
}

internal fun leadingIndentWidth(line: String): Int {
    var width = 0
    line.forEach { ch ->
        when (ch) {
            ' ' -> width++
            '\t' -> width += 4
            else -> return width
        }
    }
    return width
}

internal fun focusedPreviewRange(
    content: String,
    centerLine: Int,
    radius: Int
): ProjectFocusedPreviewRange? {
    if (content.isBlank()) return null
    val lines = content.lines()
    if (lines.isEmpty()) return null
    val safeCenter = centerLine.coerceIn(1, lines.size)
    val start = (safeCenter - radius).coerceAtLeast(1)
    val end = (safeCenter + radius).coerceAtMost(lines.size)
    val lineNumberWidth = end.toString().length.coerceAtLeast(2)
    val excerpt = buildString {
        for (lineNumber in start..end) {
            append(lineNumber.toString().padStart(lineNumberWidth, ' '))
            append(" | ")
            append(lines[lineNumber - 1])
            if (lineNumber < end) append('\n')
        }
    }
    return ProjectFocusedPreviewRange(
        startLine = start,
        endLine = end,
        excerpt = excerpt
    )
}

internal fun projectAncestorDirs(rootPath: String, filePath: String): Set<String> {
    val root = File(rootPath)
    val target = File(filePath).parentFile ?: return emptySet()
    val dirs = linkedSetOf<String>()
    var current: File? = target
    while (current != null && current.absolutePath.startsWith(root.absolutePath)) {
        if (current.absolutePath != root.absolutePath) {
            dirs += current.absolutePath
        }
        current = current.parentFile
    }
    return dirs
}
