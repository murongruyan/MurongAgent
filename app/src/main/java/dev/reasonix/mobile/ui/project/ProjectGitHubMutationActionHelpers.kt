package dev.reasonix.mobile.ui.project

internal data class ProjectGitHubReleaseDraftState(
    val releaseName: String = "",
    val releaseTag: String = "",
    val releaseBody: String = "",
    val isDraft: Boolean = false,
    val isPrerelease: Boolean = false
)

internal data class ProjectGitHubIssueDraftState(
    val title: String = "",
    val body: String = ""
)

internal data class ProjectGitHubPullRequestDraftState(
    val title: String = "",
    val body: String = "",
    val head: String = "",
    val base: String = "",
    val isDraft: Boolean = false
)

internal data class ProjectGitHubMutationActionResult(
    val success: Boolean = false,
    val feedbackMessage: String,
    val shouldRefreshGitHubActions: Boolean = false,
    val shouldDismissDialog: Boolean = false,
    val nextReleaseDraft: ProjectGitHubReleaseDraftState? = null,
    val nextIssueDraft: ProjectGitHubIssueDraftState? = null,
    val nextPullRequestDraft: ProjectGitHubPullRequestDraftState? = null
)

internal fun clearProjectGitHubReleaseDraftState(): ProjectGitHubReleaseDraftState {
    return ProjectGitHubReleaseDraftState()
}

internal fun createProjectGitHubReleaseDraftState(currentBranch: String?): ProjectGitHubReleaseDraftState {
    return clearProjectGitHubReleaseDraftState().copy(
        releaseTag = currentBranch
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { "v-$it" }
            .orEmpty()
    )
}

internal fun editProjectGitHubReleaseDraftState(
    release: ProjectGitHubReleaseUi
): ProjectGitHubReleaseDraftState {
    return ProjectGitHubReleaseDraftState(
        releaseName = release.name,
        releaseTag = release.tagName,
        releaseBody = release.body,
        isDraft = release.isDraft,
        isPrerelease = release.isPrerelease
    )
}

internal fun toggleProjectGitHubReleaseDraftState(
    currentDraft: ProjectGitHubReleaseDraftState
): ProjectGitHubReleaseDraftState {
    val nextDraft = !currentDraft.isDraft
    return currentDraft.copy(
        isDraft = nextDraft,
        isPrerelease = if (nextDraft) false else currentDraft.isPrerelease
    )
}

internal fun toggleProjectGitHubReleasePrereleaseState(
    currentDraft: ProjectGitHubReleaseDraftState
): ProjectGitHubReleaseDraftState {
    val nextPrerelease = !currentDraft.isPrerelease
    return currentDraft.copy(
        isPrerelease = nextPrerelease,
        isDraft = if (nextPrerelease) false else currentDraft.isDraft
    )
}

internal fun clearProjectGitHubIssueDraftState(): ProjectGitHubIssueDraftState {
    return ProjectGitHubIssueDraftState()
}

internal fun clearProjectGitHubPullRequestDraftState(
    currentBranch: String?,
    defaultBranch: String?,
    upstreamBranch: String?
): ProjectGitHubPullRequestDraftState {
    return ProjectGitHubPullRequestDraftState(
        head = currentBranch.orEmpty(),
        base = defaultBranch
            ?: upstreamBranch?.substringAfterLast('/')
            ?: "main"
    )
}

internal suspend fun submitProjectGitHubCreateIssueAction(
    repo: ProjectGitHubRepoRef?,
    token: String,
    apiBaseUrl: String,
    draft: ProjectGitHubIssueDraftState
): ProjectGitHubMutationActionResult {
    if (repo == null) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "当前还没有可操作的 GitHub 仓库。"
        )
    }
    if (token.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val title = draft.title.trim()
    if (title.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先填写 Issue 标题。"
        )
    }
    val result = createProjectGitHubIssue(
        repo = repo,
        title = title,
        body = draft.body,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return buildProjectGitHubMutationActionResult(
        commandResult = result,
        successFallback = "已创建 Issue $title",
        shouldDismissDialog = result.success,
        shouldRefreshGitHubActions = result.success,
        nextIssueDraft = if (result.success) clearProjectGitHubIssueDraftState() else null
    )
}

internal suspend fun submitProjectGitHubCreatePullRequestAction(
    repo: ProjectGitHubRepoRef?,
    token: String,
    apiBaseUrl: String,
    draft: ProjectGitHubPullRequestDraftState
): ProjectGitHubMutationActionResult {
    if (repo == null) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "当前还没有可操作的 GitHub 仓库。"
        )
    }
    if (token.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val title = draft.title.trim()
    val head = draft.head.trim()
    val base = draft.base.trim()
    if (title.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先填写 Pull Request 标题。"
        )
    }
    if (head.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先填写来源分支。"
        )
    }
    if (base.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先填写目标分支。"
        )
    }
    val result = createProjectGitHubPullRequest(
        repo = repo,
        title = title,
        body = draft.body,
        head = head,
        base = base,
        isDraft = draft.isDraft,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return buildProjectGitHubMutationActionResult(
        commandResult = result,
        successFallback = "已创建 PR $title",
        shouldDismissDialog = result.success,
        shouldRefreshGitHubActions = result.success,
        nextPullRequestDraft = if (result.success) {
            clearProjectGitHubPullRequestDraftState(
                currentBranch = draft.head,
                defaultBranch = draft.base,
                upstreamBranch = null
            )
        } else {
            null
        }
    )
}

internal suspend fun submitProjectGitHubCreateReleaseAction(
    repo: ProjectGitHubRepoRef?,
    token: String,
    apiBaseUrl: String,
    draft: ProjectGitHubReleaseDraftState
): ProjectGitHubMutationActionResult {
    if (repo == null) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "当前还没有可操作的 GitHub 仓库。"
        )
    }
    if (token.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val tag = draft.releaseTag.trim()
    if (tag.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先填写 Release Tag。"
        )
    }
    val result = createProjectGitHubRelease(
        repo = repo,
        tagName = tag,
        releaseName = draft.releaseName.trim(),
        body = draft.releaseBody,
        isDraft = draft.isDraft,
        isPrerelease = draft.isPrerelease,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return buildProjectGitHubMutationActionResult(
        commandResult = result,
        successFallback = "已创建 Release ${draft.releaseName.ifBlank { tag }}",
        shouldDismissDialog = result.success,
        shouldRefreshGitHubActions = result.success,
        nextReleaseDraft = if (result.success) clearProjectGitHubReleaseDraftState() else null
    )
}

internal suspend fun submitProjectGitHubEditReleaseAction(
    repo: ProjectGitHubRepoRef?,
    release: ProjectGitHubReleaseUi?,
    token: String,
    apiBaseUrl: String,
    draft: ProjectGitHubReleaseDraftState
): ProjectGitHubMutationActionResult {
    if (repo == null || release == null) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "当前还没有可编辑的 Release。"
        )
    }
    if (token.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val tag = draft.releaseTag.trim()
    if (tag.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先填写 Release Tag。"
        )
    }
    val result = updateProjectGitHubRelease(
        repo = repo,
        releaseId = release.id,
        tagName = tag,
        releaseName = draft.releaseName.trim(),
        body = draft.releaseBody,
        isDraft = draft.isDraft,
        isPrerelease = draft.isPrerelease,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return buildProjectGitHubMutationActionResult(
        commandResult = result,
        successFallback = "Release 已更新",
        shouldDismissDialog = result.success,
        shouldRefreshGitHubActions = result.success,
        nextReleaseDraft = if (result.success) clearProjectGitHubReleaseDraftState() else null
    )
}

internal suspend fun toggleProjectGitHubReleaseModeAction(
    repo: ProjectGitHubRepoRef?,
    release: ProjectGitHubReleaseUi,
    token: String,
    apiBaseUrl: String,
    makeDraft: Boolean
): ProjectGitHubMutationActionResult {
    if (repo == null) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "当前还没有可操作的 GitHub 仓库。"
        )
    }
    if (token.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val result = updateProjectGitHubRelease(
        repo = repo,
        releaseId = release.id,
        tagName = release.tagName,
        releaseName = release.name,
        body = release.body,
        isDraft = makeDraft,
        isPrerelease = if (makeDraft) release.isPrerelease else false,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return buildProjectGitHubMutationActionResult(
        commandResult = result,
        successFallback = if (makeDraft) "已切回草稿 Release" else "已发布为正式 Release",
        shouldRefreshGitHubActions = result.success
    )
}

internal suspend fun toggleProjectGitHubReleasePrereleaseAction(
    repo: ProjectGitHubRepoRef?,
    release: ProjectGitHubReleaseUi,
    token: String,
    apiBaseUrl: String,
    makePrerelease: Boolean
): ProjectGitHubMutationActionResult {
    if (repo == null) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "当前还没有可操作的 GitHub 仓库。"
        )
    }
    if (token.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val result = updateProjectGitHubRelease(
        repo = repo,
        releaseId = release.id,
        tagName = release.tagName,
        releaseName = release.name,
        body = release.body,
        isDraft = release.isDraft,
        isPrerelease = makePrerelease,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return buildProjectGitHubMutationActionResult(
        commandResult = result,
        successFallback = if (makePrerelease) "已标记为预发布 Release" else "已取消预发布标记",
        shouldRefreshGitHubActions = result.success
    )
}

internal suspend fun deleteProjectGitHubReleaseAction(
    repo: ProjectGitHubRepoRef?,
    release: ProjectGitHubReleaseUi,
    token: String,
    apiBaseUrl: String
): ProjectGitHubMutationActionResult {
    if (repo == null) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "当前还没有可操作的 GitHub 仓库。"
        )
    }
    if (token.isBlank()) {
        return ProjectGitHubMutationActionResult(
            feedbackMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val result = deleteProjectGitHubRelease(
        repo = repo,
        releaseId = release.id,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return buildProjectGitHubMutationActionResult(
        commandResult = result,
        successFallback = "已删除 Release ${release.name.ifBlank { release.tagName }}",
        shouldRefreshGitHubActions = result.success
    )
}

private fun buildProjectGitHubMutationActionResult(
    commandResult: ProjectGitHubCommandResult,
    successFallback: String,
    shouldRefreshGitHubActions: Boolean = false,
    shouldDismissDialog: Boolean = false,
    nextReleaseDraft: ProjectGitHubReleaseDraftState? = null,
    nextIssueDraft: ProjectGitHubIssueDraftState? = null,
    nextPullRequestDraft: ProjectGitHubPullRequestDraftState? = null
): ProjectGitHubMutationActionResult {
    return ProjectGitHubMutationActionResult(
        success = commandResult.success,
        feedbackMessage = if (commandResult.success) {
            commandResult.message.ifBlank { successFallback }
        } else {
            commandResult.error ?: commandResult.message.ifBlank { "GitHub 操作失败" }
        },
        shouldRefreshGitHubActions = commandResult.success && shouldRefreshGitHubActions,
        shouldDismissDialog = commandResult.success && shouldDismissDialog,
        nextReleaseDraft = nextReleaseDraft,
        nextIssueDraft = nextIssueDraft,
        nextPullRequestDraft = nextPullRequestDraft
    )
}
