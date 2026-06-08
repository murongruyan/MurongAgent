package dev.reasonix.mobile.ui.project

import dev.reasonix.mobile.common.utils.RootFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ProjectGitStatusUi(
    val projectPath: String?,
    val repoRoot: String?,
    val hasGitCommand: Boolean,
    val branchSummary: String,
    val currentBranch: String?,
    val remoteUrl: String?,
    val upstreamBranch: String?,
    val aheadCount: Int,
    val behindCount: Int,
    val lastCommitSummary: String?,
    val localBranches: List<ProjectGitBranchUi>,
    val remoteBranches: List<String>,
    val recentCommits: List<ProjectGitCommitUi>,
    val conflictedFiles: List<ProjectGitFileChangeUi>,
    val stagedFiles: List<ProjectGitFileChangeUi>,
    val modifiedFiles: List<ProjectGitFileChangeUi>,
    val untrackedFiles: List<ProjectGitFileChangeUi>,
    val errorMessage: String?
) {
    val isRepository: Boolean get() = !repoRoot.isNullOrBlank()
    val hasRemote: Boolean get() = !remoteUrl.isNullOrBlank()
    val canPull: Boolean get() = hasGitCommand && hasRemote && !upstreamBranch.isNullOrBlank()
    val canPush: Boolean get() = hasGitCommand && hasRemote && !currentBranch.isNullOrBlank()
    val canStageAll: Boolean get() = hasGitCommand && (modifiedFiles.isNotEmpty() || untrackedFiles.isNotEmpty())
    val canCommit: Boolean get() = hasGitCommand && stagedFiles.isNotEmpty()
    val hasWorkingTreeChanges: Boolean get() = conflictedFiles.isNotEmpty() || stagedFiles.isNotEmpty() || modifiedFiles.isNotEmpty() || untrackedFiles.isNotEmpty()

    companion object {
        fun empty(projectPath: String?) = ProjectGitStatusUi(
            projectPath = projectPath,
            repoRoot = null,
            hasGitCommand = true,
            branchSummary = "",
            currentBranch = null,
            remoteUrl = null,
            upstreamBranch = null,
            aheadCount = 0,
            behindCount = 0,
            lastCommitSummary = null,
            localBranches = emptyList(),
            remoteBranches = emptyList(),
            recentCommits = emptyList(),
            conflictedFiles = emptyList(),
            stagedFiles = emptyList(),
            modifiedFiles = emptyList(),
            untrackedFiles = emptyList(),
            errorMessage = null
        )
    }
}

internal data class ProjectGitFileChangeUi(
    val displayPath: String,
    val actionPath: String,
    val statusLabel: String,
    val diffMode: ProjectGitDiffMode
)

internal enum class ProjectGitDiffMode {
    STAGED,
    WORKTREE,
    UNTRACKED
}

internal data class ProjectGitDiffPreviewUi(
    val title: String,
    val content: String
)

internal data class ProjectGitBranchUi(
    val name: String,
    val isCurrent: Boolean,
    val trackInfo: String?
)

internal data class ProjectGitCommitUi(
    val commitHash: String,
    val shortHash: String,
    val subject: String,
    val relativeTime: String,
    val author: String
)

internal data class ProjectGitOperationRecordUi(
    val title: String,
    val detail: String,
    val repoRoot: String?,
    val repoLabel: String,
    val timeLabel: String,
    val isSuccess: Boolean,
    val categoryLabel: String
)

internal data class ProjectGitOperationSummaryUi(
    val totalCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val repoCount: Int,
    val latestTimeLabel: String?,
    val latestTitle: String?,
    val syncCount: Int
)

internal data class ProjectGitCommandResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int = 0
)

internal fun runEmbeddedGitAction(block: () -> String): ProjectGitCommandResult {
    return runCatching {
        ProjectGitCommandResult(
            success = true,
            output = block()
        )
    }.getOrElse { error ->
        ProjectGitCommandResult(
            success = false,
            output = "",
            error = error.message ?: "Git 操作失败"
        )
    }
}

internal fun loadProjectGitStatus(projectPath: String): ProjectGitStatusUi {
    val repoRoot = findEmbeddedGitRoot(projectPath) ?: findProjectGitRoot(projectPath)
        ?: return ProjectGitStatusUi.empty(projectPath).copy(
            errorMessage = "当前目录下没有识别到 `.git` 仓库。"
        )
    return runCatching {
        val status = loadEmbeddedGitStatus(projectPath)
        ProjectGitStatusUi(
            projectPath = projectPath,
            repoRoot = status.repoRoot,
            hasGitCommand = true,
            branchSummary = status.branchSummary,
            currentBranch = status.currentBranch,
            remoteUrl = status.remoteUrl,
            upstreamBranch = status.upstreamBranch,
            aheadCount = status.aheadCount,
            behindCount = status.behindCount,
            lastCommitSummary = status.lastCommitSummary,
            localBranches = status.localBranches.map { branch ->
                ProjectGitBranchUi(
                    name = branch.name,
                    isCurrent = branch.isCurrent,
                    trackInfo = branch.trackInfo
                )
            },
            remoteBranches = status.remoteBranches,
            recentCommits = status.recentCommits.map { commit ->
                ProjectGitCommitUi(
                    commitHash = commit.commitHash,
                    shortHash = commit.shortHash,
                    subject = commit.subject,
                    relativeTime = commit.relativeTime,
                    author = commit.author
                )
            },
            conflictedFiles = status.conflictedFiles.map { change -> change.toProjectGitFileChange() },
            stagedFiles = status.stagedFiles.map { change -> change.toProjectGitFileChange() },
            modifiedFiles = status.modifiedFiles.map { change -> change.toProjectGitFileChange() },
            untrackedFiles = status.untrackedFiles.map { change -> change.toProjectGitFileChange() },
            errorMessage = null
        )
    }.getOrElse { error ->
        loadProjectGitMetadataFallback(repoRoot)?.copy(
            projectPath = projectPath,
            errorMessage = error.message ?: "当前目录的 Git 元数据已识别，但完整状态读取失败"
        ) ?: ProjectGitStatusUi.empty(projectPath).copy(
            repoRoot = repoRoot,
            hasGitCommand = false,
            errorMessage = error.message ?: "读取 Git 状态失败"
        )
    }
}

private fun loadProjectGitMetadataFallback(repoRoot: String): ProjectGitStatusUi? {
    val headContent = readProjectGitMetadataFile("$repoRoot/.git/HEAD") ?: return null
    val configContent = readProjectGitMetadataFile("$repoRoot/.git/config").orEmpty()
    val currentBranch = parseProjectGitHeadBranch(headContent)
    val config = parseProjectGitConfig(configContent)
    val remoteUrl = config["remote \"origin\""]?.get("url")?.takeIf { it.isNotBlank() }
    val upstreamBranch = currentBranch?.let { branch ->
        val branchSection = config["branch \"$branch\""].orEmpty()
        val remoteName = branchSection["remote"].orEmpty().ifBlank { "origin" }
        val mergeRef = branchSection["merge"].orEmpty()
        mergeRef
            .substringAfter("refs/heads/", "")
            .takeIf { it.isNotBlank() }
            ?.let { "$remoteName/$it" }
    }
    val branchSummary = buildProjectGitBranchSummary(currentBranch, upstreamBranch)
    val localBranches = currentBranch?.let { branch ->
        listOf(ProjectGitBranchUi(name = branch, isCurrent = true, trackInfo = upstreamBranch))
    }.orEmpty()
    return ProjectGitStatusUi.empty(repoRoot).copy(
        projectPath = repoRoot,
        repoRoot = repoRoot,
        hasGitCommand = false,
        branchSummary = branchSummary,
        currentBranch = currentBranch,
        remoteUrl = remoteUrl,
        upstreamBranch = upstreamBranch,
        localBranches = localBranches,
        errorMessage = "已识别 Git 元数据，但当前目录暂未启用完整 Git 操作。"
    )
}

private fun readProjectGitMetadataFile(path: String): String? {
    val direct = runCatching {
        val file = File(path)
        if (file.exists() && file.isFile) file.readText() else null
    }.getOrNull()
    if (!direct.isNullOrBlank()) return direct
    val content = RootFile.readFile(path)
    if (content.startsWith("error:")) return null
    return content
}

private fun parseProjectGitHeadBranch(content: String): String? {
    val trimmed = content.trim()
    if (trimmed.startsWith("ref:", ignoreCase = true).not()) return null
    return trimmed
        .substringAfter("ref:", "")
        .trim()
        .substringAfterLast('/')
        .takeIf { it.isNotBlank() }
}

private fun parseProjectGitConfig(content: String): Map<String, Map<String, String>> {
    val sections = linkedMapOf<String, MutableMap<String, String>>()
    var currentSection: String? = null
    content.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) return@forEach
        if (line.startsWith("[") && line.endsWith("]")) {
            currentSection = line.removePrefix("[").removeSuffix("]").trim()
            sections.getOrPut(currentSection) { linkedMapOf() }
            return@forEach
        }
        val section = currentSection ?: return@forEach
        val separator = line.indexOf('=')
        if (separator <= 0) return@forEach
        val key = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        sections.getOrPut(section) { linkedMapOf() }[key] = value
    }
    return sections
}

private fun buildProjectGitBranchSummary(currentBranch: String?, upstreamBranch: String?): String {
    val current = currentBranch.orEmpty()
    if (current.isBlank()) return ""
    return if (upstreamBranch.isNullOrBlank()) current else "$current -> $upstreamBranch"
}

internal fun loadProjectGitCommitPreview(
    repoRoot: String,
    commitHash: String
): ProjectGitDiffPreviewUi? {
    return runCatching {
        loadEmbeddedGitCommitPreview(repoRoot, commitHash)?.let { preview ->
            ProjectGitDiffPreviewUi(
                title = preview.title,
                content = preview.content
            )
        }
    }.getOrNull()
}

internal fun buildGitCommitMessage(title: String, detail: String): String {
    val normalizedTitle = title.trim()
    val normalizedDetail = detail.trim()
    if (normalizedTitle.isBlank()) return ""
    return if (normalizedDetail.isBlank()) {
        normalizedTitle
    } else {
        normalizedTitle + "\n\n" + normalizedDetail
    }
}

internal fun initializeProjectGitRepository(
    projectPath: String,
    preferredBranch: String
): ProjectGitCommandResult {
    return runEmbeddedGitAction {
        initializeEmbeddedGitRepository(projectPath, preferredBranch)
    }
}

internal fun bindProjectGitHubRemote(
    projectPath: String,
    remoteUrl: String,
    preferredBranch: String
): ProjectGitCommandResult {
    return runEmbeddedGitAction {
        bindEmbeddedGitRemote(projectPath, remoteUrl, preferredBranch)
    }
}

internal fun sanitizeProjectDownloadFileName(value: String): String {
    return value.replace(Regex("""[\\/:*?"<>|]"""), "_")
}

internal fun createProjectGitOperationRecord(
    title: String,
    detail: String,
    repoRoot: String?,
    isSuccess: Boolean
): ProjectGitOperationRecordUi {
    return ProjectGitOperationRecordUi(
        title = title,
        detail = detail.trim().ifBlank { if (isSuccess) "操作已完成" else "操作失败" },
        repoRoot = repoRoot,
        repoLabel = repoRoot
            ?.let(::File)
            ?.name
            ?.ifBlank { repoRoot }
            ?: "当前仓库",
        timeLabel = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
        isSuccess = isSuccess,
        categoryLabel = inferProjectGitOperationCategory(title)
    )
}

internal fun buildProjectGitOperationSummary(
    records: List<ProjectGitOperationRecordUi>
): ProjectGitOperationSummaryUi {
    return ProjectGitOperationSummaryUi(
        totalCount = records.size,
        successCount = records.count { it.isSuccess },
        failureCount = records.count { !it.isSuccess },
        repoCount = records.mapNotNull { it.repoRoot ?: it.repoLabel }.distinct().size,
        latestTimeLabel = records.firstOrNull()?.timeLabel,
        latestTitle = records.firstOrNull()?.title,
        syncCount = records.count { it.categoryLabel == "同步" }
    )
}

private fun inferProjectGitOperationCategory(title: String): String {
    val normalized = title.trim()
    return when {
        normalized.contains("推送") ||
            normalized.contains("拉取") ||
            normalized.contains("抓取") ||
            normalized.contains("同步") -> "同步"
        normalized.contains("提交") -> "提交"
        normalized.contains("分支") ||
            normalized.contains("切换") ||
            normalized.contains("跟踪") -> "分支"
        normalized.contains("暂存") -> "暂存"
        normalized.contains("初始化") ||
            normalized.contains("绑定") -> "仓库"
        else -> "其他"
    }
}

internal fun loadProjectGitDiffPreview(
    repoRoot: String,
    change: ProjectGitFileChangeUi
): ProjectGitDiffPreviewUi? {
    return runCatching {
        loadEmbeddedGitDiffPreview(
            repoRoot = repoRoot,
            change = EmbeddedGitFileChange(
                displayPath = change.displayPath,
                actionPath = change.actionPath,
                statusLabel = change.statusLabel,
                diffMode = change.diffMode.toEmbeddedDiffMode()
            )
        )?.let { preview ->
            ProjectGitDiffPreviewUi(
                title = preview.title,
                content = preview.content
            )
        }
    }.getOrNull()
}

private fun EmbeddedGitFileChange.toProjectGitFileChange(): ProjectGitFileChangeUi {
    return ProjectGitFileChangeUi(
        displayPath = displayPath,
        actionPath = actionPath,
        statusLabel = statusLabel,
        diffMode = when (diffMode) {
            EmbeddedGitDiffMode.STAGED -> ProjectGitDiffMode.STAGED
            EmbeddedGitDiffMode.WORKTREE -> ProjectGitDiffMode.WORKTREE
            EmbeddedGitDiffMode.UNTRACKED -> ProjectGitDiffMode.UNTRACKED
        }
    )
}

private fun ProjectGitDiffMode.toEmbeddedDiffMode(): EmbeddedGitDiffMode {
    return when (this) {
        ProjectGitDiffMode.STAGED -> EmbeddedGitDiffMode.STAGED
        ProjectGitDiffMode.WORKTREE -> EmbeddedGitDiffMode.WORKTREE
        ProjectGitDiffMode.UNTRACKED -> EmbeddedGitDiffMode.UNTRACKED
    }
}

internal fun findProjectGitRoot(projectPath: String): String? {
    var current: File? = File(projectPath)
    while (current != null) {
        val dotGit = File(current, ".git")
        if (dotGit.exists() || RootFile.exists(dotGit.absolutePath)) {
            return current.absolutePath
        }
        current = current.parentFile
    }
    return null
}

internal fun normalizeGitActionPath(repoRoot: String, rawPath: String): String {
    val normalized = rawPath.substringAfter(" -> ", rawPath).trim()
    if (normalized.startsWith("/")) return normalized
    return normalized.removePrefix("${repoRoot.trimEnd('/')}/")
}

internal fun suggestProjectGitHubRepoName(projectPath: String?): String {
    return projectPath
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it).name }
        ?.ifBlank { null }
        ?: "new-repo"
}
