package com.murong.agent.ui.project

internal data class ProjectGitHubRemoteBrowserRefreshResult(
    val state: ProjectGitHubRemoteBrowserState,
    val nextRefDraft: String?,
    val feedbackMessage: String?
)

internal data class ProjectGitHubRemoteFileOpenEditorResult(
    val file: ProjectGitHubRemoteFileUi?,
    val contentDraft: String,
    val commitMessageDraft: String,
    val feedbackMessage: String?
)

internal data class ProjectGitHubRemoteFileSaveResult(
    val success: Boolean,
    val feedbackMessage: String
)

internal suspend fun refreshProjectGitHubRemoteBrowserState(
    currentRepo: ProjectGitHubRepoRef?,
    gitRemoteUrl: String?,
    token: String,
    currentBranch: String?,
    defaultBranch: String?,
    refDraft: String,
    targetPath: String,
    resetRefIfBlank: Boolean,
    apiBaseUrl: String
): ProjectGitHubRemoteBrowserRefreshResult {
    val repo = currentRepo ?: parseProjectGitHubRemoteRepoRef(gitRemoteUrl)
    if (repo == null) {
        return ProjectGitHubRemoteBrowserRefreshResult(
            state = ProjectGitHubRemoteBrowserState.empty().copy(
                errorMessage = "当前 origin 远端还不能识别成 GitHub 仓库地址。"
            ),
            nextRefDraft = null,
            feedbackMessage = null
        )
    }
    if (token.isBlank()) {
        return ProjectGitHubRemoteBrowserRefreshResult(
            state = ProjectGitHubRemoteBrowserState.empty(repo).copy(
                errorMessage = "请先在设置页填写 GitHub Token。"
            ),
            nextRefDraft = null,
            feedbackMessage = null
        )
    }
    val fallbackRef = currentBranch ?: defaultBranch ?: "main"
    val nextRefDraft = fallbackRef.takeIf { resetRefIfBlank && refDraft.isBlank() }
    val ref = (nextRefDraft ?: refDraft).trim().ifBlank { fallbackRef }
    val normalizedPath = normalizeProjectGitHubRemoteRepoPath(targetPath)
    val result = loadProjectGitHubRemoteDirectory(
        repo = repo,
        path = normalizedPath,
        ref = ref,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return if (result.success && result.state != null) {
        ProjectGitHubRemoteBrowserRefreshResult(
            state = result.state,
            nextRefDraft = nextRefDraft,
            feedbackMessage = null
        )
    } else {
        val errorMessage = result.error ?: "读取远端仓库失败"
        ProjectGitHubRemoteBrowserRefreshResult(
            state = ProjectGitHubRemoteBrowserState.empty(repo).copy(
                currentRef = ref,
                currentPath = normalizedPath,
                errorMessage = errorMessage
            ),
            nextRefDraft = nextRefDraft,
            feedbackMessage = errorMessage
        )
    }
}

internal suspend fun openProjectGitHubRemoteFileEditor(
    currentRepo: ProjectGitHubRepoRef?,
    gitRemoteUrl: String?,
    token: String,
    currentRemoteRef: String,
    refDraft: String,
    currentBranch: String?,
    defaultBranch: String?,
    entry: ProjectGitHubRemoteEntryUi,
    apiBaseUrl: String
): ProjectGitHubRemoteFileOpenEditorResult {
    val repo = currentRepo ?: parseProjectGitHubRemoteRepoRef(gitRemoteUrl)
    if (repo == null) {
        return ProjectGitHubRemoteFileOpenEditorResult(
            file = null,
            contentDraft = "",
            commitMessageDraft = "",
            feedbackMessage = "当前 origin 远端还不能识别成 GitHub 仓库地址。"
        )
    }
    if (token.isBlank()) {
        return ProjectGitHubRemoteFileOpenEditorResult(
            file = null,
            contentDraft = "",
            commitMessageDraft = "",
            feedbackMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val ref = currentRemoteRef.ifBlank {
        refDraft.trim().ifBlank { currentBranch ?: defaultBranch ?: "main" }
    }
    val result = loadProjectGitHubRemoteFile(
        repo = repo,
        path = entry.path,
        ref = ref,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return if (result.success && result.file != null) {
        ProjectGitHubRemoteFileOpenEditorResult(
            file = result.file,
            contentDraft = result.file.content,
            commitMessageDraft = "更新 ${result.file.path}",
            feedbackMessage = null
        )
    } else {
        ProjectGitHubRemoteFileOpenEditorResult(
            file = null,
            contentDraft = "",
            commitMessageDraft = "",
            feedbackMessage = result.error ?: "读取远端文件失败"
        )
    }
}

internal suspend fun saveProjectGitHubRemoteFileEditor(
    currentRepo: ProjectGitHubRepoRef?,
    gitRemoteUrl: String?,
    token: String,
    currentRemoteRef: String,
    defaultBranch: String?,
    file: ProjectGitHubRemoteFileUi,
    contentDraft: String,
    commitMessageDraft: String,
    apiBaseUrl: String
): ProjectGitHubRemoteFileSaveResult {
    val repo = currentRepo ?: parseProjectGitHubRemoteRepoRef(gitRemoteUrl)
    if (repo == null) {
        return ProjectGitHubRemoteFileSaveResult(
            success = false,
            feedbackMessage = "当前 origin 远端还不能识别成 GitHub 仓库地址。"
        )
    }
    val message = commitMessageDraft.trim()
    if (token.isBlank() || message.isBlank()) {
        return ProjectGitHubRemoteFileSaveResult(
            success = false,
            feedbackMessage = "请先填写提交说明并确认 GitHub Token 已配置。"
        )
    }
    val branch = file.ref.ifBlank {
        currentRemoteRef.ifBlank { defaultBranch ?: "main" }
    }
    val result = updateProjectGitHubRemoteFile(
        repo = repo,
        path = file.path,
        branch = branch,
        message = message,
        content = contentDraft,
        sha = file.sha,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return ProjectGitHubRemoteFileSaveResult(
        success = result.success,
        feedbackMessage = if (result.success) {
            result.message.ifBlank { "已更新远端文件 ${file.path}" }
        } else {
            result.error ?: result.message.ifBlank { "更新远端文件失败" }
        }
    )
}

private fun normalizeProjectGitHubRemoteRepoPath(path: String): String {
    val normalized = path.replace('\\', '/').trim('/')
    return if (normalized == "." || normalized == "/") "" else normalized
}

private fun parseProjectGitHubRemoteRepoRef(remoteUrl: String?): ProjectGitHubRepoRef? {
    val raw = remoteUrl?.trim().orEmpty()
    if (raw.isBlank()) return null
    val sanitized = raw.removeSuffix(".git")
    val repoPath = when {
        sanitized.contains("://") -> sanitized.substringAfter("://").substringAfter('/', "")
        sanitized.startsWith("git@") -> sanitized.substringAfter(':', "")
        else -> sanitized.substringAfter(':', sanitized)
    }.trim('/')
    val segments = repoPath.split('/').filter { it.isNotBlank() }
    if (segments.size < 2) return null
    return ProjectGitHubRepoRef(
        owner = segments[0],
        repo = segments[1]
    )
}
