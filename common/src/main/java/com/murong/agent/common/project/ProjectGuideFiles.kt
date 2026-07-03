package com.murong.agent.common.project

import java.io.File

data class ProjectKnowledgeFileResolution(
    val configuredPaths: List<String>,
    val autoGuidePaths: List<String>,
    val mergedPaths: List<String>
)

private val DEFAULT_PROJECT_GUIDE_RELATIVE_PATHS = listOf(
    "AGENTS.md",
    "REASONIX.md",
    "CLAUDE.md",
    "README.md",
    "README.zh-CN.md",
    "README.zh.md",
    "docs/SPEC.md",
    "docs/GUIDE.md",
    "docs/GUIDE.zh-CN.md",
    "docs/README.md"
)

fun resolveProjectKnowledgeFiles(
    projectPath: String?,
    configuredPaths: List<String>,
    candidateRelativePaths: List<String> = DEFAULT_PROJECT_GUIDE_RELATIVE_PATHS
): ProjectKnowledgeFileResolution {
    val normalizedConfigured = configuredPaths
        .mapNotNull(::normalizeProjectKnowledgePath)
        .distinct()
    val autoGuidePaths = discoverProjectGuideFiles(projectPath, candidateRelativePaths)
    return ProjectKnowledgeFileResolution(
        configuredPaths = normalizedConfigured,
        autoGuidePaths = autoGuidePaths,
        mergedPaths = (autoGuidePaths + normalizedConfigured).distinct()
    )
}

fun discoverProjectGuideFiles(
    projectPath: String?,
    candidateRelativePaths: List<String> = DEFAULT_PROJECT_GUIDE_RELATIVE_PATHS
): List<String> {
    val root = normalizeProjectRoot(projectPath) ?: return emptyList()
    return candidateRelativePaths
        .mapNotNull { relativePath ->
            val candidate = File(root, relativePath.replace('/', File.separatorChar))
            normalizeProjectKnowledgePath(candidate.absolutePath)
                ?.takeIf { File(it).isFile }
        }
        .distinct()
}

private fun normalizeProjectRoot(projectPath: String?): File? {
    val raw = projectPath?.trim().orEmpty()
    if (raw.isBlank()) return null
    return runCatching { File(raw).canonicalFile }
        .getOrNull()
        ?.takeIf { it.exists() && it.isDirectory }
}

private fun normalizeProjectKnowledgePath(rawPath: String?): String? {
    val candidate = rawPath?.trim().orEmpty()
    if (candidate.isBlank()) return null
    return runCatching { File(candidate).canonicalPath }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}
