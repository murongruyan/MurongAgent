package com.murong.agent.ui

import java.io.File
import java.util.Locale

data class ProjectKnowledgeOutlineUi(
    val path: String,
    val displayPath: String,
    val fileType: String,
    val lineCount: Int,
    val symbolNames: List<String>,
    val summary: String
)

data class ProjectKnowledgeRecommendationUi(
    val path: String,
    val displayPath: String,
    val fileType: String,
    val summary: String,
    val sourceDisplayPath: String,
    val reasons: List<String>,
    val score: Int
)

data class ProjectKnowledgeFocusSuggestionUi(
    val path: String,
    val displayPath: String,
    val fileType: String,
    val summary: String,
    val reasons: List<String>,
    val score: Int
)

fun buildProjectKnowledgeOutlines(
    projectPath: String?,
    filePaths: List<String>,
    maxSymbols: Int = 4
): List<ProjectKnowledgeOutlineUi> {
    val normalizedProjectPath = projectPath?.trim()?.takeIf { it.isNotBlank() }
    val root = normalizedProjectPath?.let { path ->
        runCatching { File(path).canonicalFile }.getOrNull()
    }
    return filePaths.mapNotNull { rawPath ->
        val safeFile = runCatching { File(rawPath).canonicalFile }.getOrNull() ?: return@mapNotNull null
        val displayPath = root?.let { projectRoot ->
            runCatching { safeFile.relativeTo(projectRoot).invariantSeparatorsPath }.getOrNull()
        } ?: safeFile.name
        buildProjectKnowledgeOutline(
            safeFile = safeFile,
            displayPath = displayPath,
            maxSymbols = maxSymbols
        )
    }.distinctBy { it.path }
}

fun projectKnowledgeFileTypeLabel(fileType: String): String {
    return if (fileType == "(no_extension)") {
        "无后缀"
    } else {
        ".${fileType}"
    }
}

fun buildProjectKnowledgeRecommendations(
    projectPath: String?,
    mountedPaths: List<String>,
    candidatePaths: List<String>,
    maxRecommendations: Int = 6
): List<ProjectKnowledgeRecommendationUi> {
    val mountedOutlines = buildProjectKnowledgeOutlines(projectPath, mountedPaths)
    if (mountedOutlines.isEmpty()) return emptyList()
    val mountedPathSet = mountedOutlines.map { it.path }.toSet()
    val candidateOutlines = buildProjectKnowledgeOutlines(
        projectPath = projectPath,
        filePaths = candidatePaths.filterNot { it in mountedPathSet }
    )
    return candidateOutlines.mapNotNull { candidate ->
        mountedOutlines
            .map { mounted -> scoreProjectKnowledgeRecommendation(candidate, mounted) }
            .filter { it.score > 0 }
            .maxByOrNull { it.score }
            ?.let { scored ->
                ProjectKnowledgeRecommendationUi(
                    path = candidate.path,
                    displayPath = candidate.displayPath,
                    fileType = candidate.fileType,
                    summary = candidate.summary,
                    sourceDisplayPath = scored.sourceDisplayPath,
                    reasons = scored.reasons,
                    score = scored.score
                ) to scored.score
            }
    }
        .sortedWith(
            compareByDescending<Pair<ProjectKnowledgeRecommendationUi, Int>> { it.second }
                .thenBy { it.first.displayPath.length }
                .thenBy { it.first.displayPath.lowercase(Locale.ROOT) }
        )
        .map { it.first }
        .take(maxRecommendations)
}

fun projectKnowledgeRecommendationStrengthLabel(score: Int): String {
    return when {
        score >= 8 -> "高相关"
        score >= 5 -> "中相关"
        else -> "轻相关"
    }
}

fun buildProjectKnowledgeFocusSuggestions(
    projectPath: String?,
    mountedPaths: List<String>,
    recommendations: List<ProjectKnowledgeRecommendationUi>,
    maxSuggestions: Int = 4
): List<ProjectKnowledgeFocusSuggestionUi> {
    val mountedOutlines = buildProjectKnowledgeOutlines(projectPath, mountedPaths)
    if (mountedOutlines.isEmpty()) return emptyList()
    val recommendationCountBySource = recommendations
        .groupingBy { it.sourceDisplayPath }
        .eachCount()
    return mountedOutlines.map { entry ->
        val scored = scoreProjectKnowledgeFocus(entry, recommendationCountBySource[entry.displayPath] ?: 0)
        ProjectKnowledgeFocusSuggestionUi(
            path = entry.path,
            displayPath = entry.displayPath,
            fileType = entry.fileType,
            summary = entry.summary,
            reasons = scored.reasons,
            score = scored.score
        )
    }
        .sortedWith(
            compareByDescending<ProjectKnowledgeFocusSuggestionUi> { it.score }
                .thenByDescending { it.summary.length }
                .thenBy { it.displayPath.lowercase(Locale.ROOT) }
        )
        .take(maxSuggestions.coerceAtLeast(1).coerceAtMost(mountedOutlines.size))
}

private fun buildProjectKnowledgeOutline(
    safeFile: File,
    displayPath: String,
    maxSymbols: Int
): ProjectKnowledgeOutlineUi? {
    if (!safeFile.isFile) return null
    val fileType = projectKnowledgeFileType(safeFile.absolutePath)
    val preview = readProjectKnowledgePreview(safeFile)
    val symbolNames = extractProjectKnowledgeSymbols(
        text = preview.previewText,
        fileType = fileType,
        maxSymbols = maxSymbols
    )
    val summary = when {
        preview.previewText.isBlank() && preview.lineCount == 0 ->
            "${projectKnowledgeFileTypeLabel(fileType)} · 暂不可读取"
        symbolNames.isNotEmpty() ->
            "${projectKnowledgeFileTypeLabel(fileType)} · ${preview.lineCount} 行 · ${symbolNames.joinToString(" / ")}"
        else ->
            "${projectKnowledgeFileTypeLabel(fileType)} · ${preview.lineCount} 行 · ${projectKnowledgeContentLabel(fileType)}"
    }
    return ProjectKnowledgeOutlineUi(
        path = safeFile.absolutePath,
        displayPath = displayPath,
        fileType = fileType,
        lineCount = preview.lineCount,
        symbolNames = symbolNames,
        summary = summary
    )
}

private data class ScoredProjectKnowledgeRecommendation(
    val sourceDisplayPath: String,
    val reasons: List<String>,
    val score: Int
)

private data class ScoredProjectKnowledgeFocus(
    val reasons: List<String>,
    val score: Int
)

private fun scoreProjectKnowledgeRecommendation(
    candidate: ProjectKnowledgeOutlineUi,
    mounted: ProjectKnowledgeOutlineUi
): ScoredProjectKnowledgeRecommendation {
    var score = 0
    val reasons = mutableListOf<String>()
    val candidateDirectory = projectKnowledgeParentPath(candidate.displayPath)
    val mountedDirectory = projectKnowledgeParentPath(mounted.displayPath)
    if (candidateDirectory != null && candidateDirectory == mountedDirectory) {
        score += 4
        reasons += "同目录"
    }
    if (candidate.fileType == mounted.fileType) {
        score += 1
        reasons += "同类型"
    }
    val candidateStem = projectKnowledgeFileStem(candidate.displayPath)
    val mountedStem = projectKnowledgeFileStem(mounted.displayPath)
    if (candidateStem.isNotBlank() && mountedStem.isNotBlank()) {
        when {
            candidateStem == mountedStem -> {
                score += 4
                reasons += "同名文件"
            }
            candidateStem.startsWith(mountedStem) || mountedStem.startsWith(candidateStem) -> {
                score += 2
                reasons += "同名前缀"
            }
        }
    }
    val sharedSymbols = candidate.symbolNames
        .map { it.lowercase(Locale.ROOT) }
        .intersect(mounted.symbolNames.map { it.lowercase(Locale.ROOT) }.toSet())
    if (sharedSymbols.isNotEmpty()) {
        score += 3 + sharedSymbols.size.coerceAtMost(2)
        reasons += "共享符号 ${sharedSymbols.first()}"
    }
    return ScoredProjectKnowledgeRecommendation(
        sourceDisplayPath = mounted.displayPath,
        reasons = reasons,
        score = score
    )
}

private fun scoreProjectKnowledgeFocus(
    entry: ProjectKnowledgeOutlineUi,
    recommendationCount: Int
): ScoredProjectKnowledgeFocus {
    var score = 0
    val reasons = mutableListOf<String>()
    val symbolCount = entry.symbolNames.size
    if (symbolCount > 0) {
        score += 2 + symbolCount.coerceAtMost(3)
        reasons += "符号更集中"
    }
    when {
        entry.lineCount in 1..400 -> {
            score += 2
            reasons += "体量适中"
        }
        entry.lineCount in 401..900 -> {
            score += 1
            reasons += "体量可控"
        }
    }
    when (entry.fileType) {
        "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "go", "rs", "c", "cpp", "h", "hpp" -> {
            score += 1
            reasons += "源码入口"
        }
        "md", "json", "xml", "yaml", "yml", "toml", "properties" -> {
            score += 1
            reasons += "文档/配置"
        }
    }
    if (recommendationCount > 0) {
        score += 3 + recommendationCount.coerceAtMost(2)
        reasons += "可带出关联候选"
    }
    val normalizedPath = entry.displayPath.lowercase(Locale.ROOT)
    if (
        normalizedPath.contains("readme") ||
        normalizedPath.contains("index") ||
        normalizedPath.contains("main") ||
        normalizedPath.contains("app") ||
        normalizedPath.contains("router") ||
        normalizedPath.contains("config")
    ) {
        score += 2
        reasons += "常见入口文件"
    }
    return ScoredProjectKnowledgeFocus(
        reasons = reasons.ifEmpty { listOf("当前内容相对聚焦") },
        score = score
    )
}

private data class ProjectKnowledgePreview(
    val previewText: String,
    val lineCount: Int
)

private fun readProjectKnowledgePreview(file: File, maxLines: Int = 240): ProjectKnowledgePreview {
    return runCatching {
        file.bufferedReader().useLines { sequence ->
            var lineCount = 0
            val previewLines = mutableListOf<String>()
            sequence.forEach { line ->
                lineCount += 1
                if (previewLines.size < maxLines) {
                    previewLines += line
                }
            }
            ProjectKnowledgePreview(
                previewText = previewLines.joinToString("\n"),
                lineCount = lineCount
            )
        }
    }.getOrElse {
        ProjectKnowledgePreview(
            previewText = "",
            lineCount = 0
        )
    }
}

private data class SymbolPattern(
    val regex: Regex,
    val groupIndex: Int
)

private fun extractProjectKnowledgeSymbols(
    text: String,
    fileType: String,
    maxSymbols: Int
): List<String> {
    if (text.isBlank()) return emptyList()
    val patterns = projectKnowledgeSymbolPatterns(fileType)
    if (patterns.isEmpty()) return emptyList()
    val collected = linkedSetOf<String>()
    patterns.forEach { pattern ->
        pattern.regex.findAll(text).forEach { match ->
            val value = match.groups[pattern.groupIndex]?.value?.trim().orEmpty()
            if (value.isNotBlank()) {
                collected += value
            }
            if (collected.size >= maxSymbols) {
                return@forEach
            }
        }
        if (collected.size >= maxSymbols) {
            return@forEach
        }
    }
    return collected.take(maxSymbols)
}

private fun projectKnowledgeSymbolPatterns(fileType: String): List<SymbolPattern> {
    return when (fileType) {
        "kt", "kts", "java" -> listOf(
            SymbolPattern(Regex("""\b(?:data\s+class|sealed\s+class|enum\s+class|class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)"""), 1),
            SymbolPattern(Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("""), 1)
        )
        "js", "jsx", "ts", "tsx" -> listOf(
            SymbolPattern(Regex("""\b(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("""), 1),
            SymbolPattern(Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)"""), 1),
            SymbolPattern(Regex("""\b(?:const|let|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_][A-Za-z0-9_]*)\s*=>"""), 1)
        )
        "py" -> listOf(
            SymbolPattern(Regex("""^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)""", setOf(RegexOption.MULTILINE)), 1),
            SymbolPattern(Regex("""^\s*def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""", setOf(RegexOption.MULTILINE)), 1)
        )
        "go" -> listOf(
            SymbolPattern(Regex("""\btype\s+([A-Za-z_][A-Za-z0-9_]*)\s+(?:struct|interface)"""), 1),
            SymbolPattern(Regex("""\bfunc\s+(?:\([^)]+\)\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\("""), 1)
        )
        "rs" -> listOf(
            SymbolPattern(Regex("""\b(?:struct|enum|trait|mod|impl)\s+([A-Za-z_][A-Za-z0-9_]*)"""), 1),
            SymbolPattern(Regex("""\bfn\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("""), 1)
        )
        "c", "cc", "cpp", "cxx", "h", "hpp", "cs", "php" -> listOf(
            SymbolPattern(Regex("""\b(?:class|struct|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)"""), 1),
            SymbolPattern(Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{}]*\)\s*\{"""), 1)
        )
        "xml", "html" -> listOf(
            SymbolPattern(Regex("""<([A-Za-z][A-Za-z0-9._:-]*)\b"""), 1)
        )
        "json", "yaml", "yml", "toml", "properties", "gradle" -> listOf(
            SymbolPattern(Regex(""""([^"]+)"\s*:"""), 1),
            SymbolPattern(Regex("""^\s*([A-Za-z0-9_.-]+)\s*[:=]""", setOf(RegexOption.MULTILINE)), 1)
        )
        "md" -> listOf(
            SymbolPattern(Regex("""^\s*#+\s+(.+)""", setOf(RegexOption.MULTILINE)), 1)
        )
        else -> listOf(
            SymbolPattern(Regex("""\b(?:class|interface|object|struct|enum|trait|type|module|mod)\s+([A-Za-z_][A-Za-z0-9_]*)"""), 1),
            SymbolPattern(Regex("""\b(?:fun|function|def|fn)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("""), 1)
        )
    }
}

private fun projectKnowledgeFileType(filePath: String): String {
    val extension = File(filePath).extension.lowercase(Locale.ROOT)
    return extension.ifBlank { "(no_extension)" }
}

private fun projectKnowledgeContentLabel(fileType: String): String {
    return when (fileType) {
        "md" -> "文档内容"
        "json", "yaml", "yml", "toml", "properties", "gradle", "kts", "xml" -> "配置结构"
        "sh", "ps1", "bat" -> "脚本内容"
        else -> "文件内容"
    }
}

private fun projectKnowledgeParentPath(displayPath: String): String? {
    val normalized = displayPath.replace('\\', '/')
    val separatorIndex = normalized.lastIndexOf('/')
    if (separatorIndex <= 0) return null
    return normalized.substring(0, separatorIndex)
}

private fun projectKnowledgeFileStem(displayPath: String): String {
    val fileName = displayPath.replace('\\', '/').substringAfterLast('/')
    return fileName.substringBeforeLast('.').lowercase(Locale.ROOT)
}
