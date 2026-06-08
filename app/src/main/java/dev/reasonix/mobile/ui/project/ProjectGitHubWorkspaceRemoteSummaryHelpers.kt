package dev.reasonix.mobile.ui.project

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

import kotlinx.serialization.json.Json
import java.io.File

private val PROJECT_GITHUB_REMOTE_SUMMARY_JSON = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

internal fun loadProjectGitHubWorkspaceRemoteSummaryCache(contextPath: String): ProjectGitHubWorkspaceRemoteSummaryCache {
    return try {
        val file = File(contextPath, "github_remote_summaries.json")
        if (file.exists()) {
            val json = file.readText()
            PROJECT_GITHUB_REMOTE_SUMMARY_JSON.decodeFromString<ProjectGitHubWorkspaceRemoteSummaryCache>(json)
        } else {
            ProjectGitHubWorkspaceRemoteSummaryCache()
        }
    } catch (e: Exception) {
        ProjectGitHubWorkspaceRemoteSummaryCache()
    }
}

internal fun saveProjectGitHubWorkspaceRemoteSummaryCache(
    contextPath: String,
    summaries: Map<String, ProjectGitHubWorkspaceRemoteSummaryUi>
) {
    try {
        val cache = ProjectGitHubWorkspaceRemoteSummaryCache(
            summaries = summaries,
            lastUpdatedMillis = System.currentTimeMillis()
        )
        val file = File(contextPath, "github_remote_summaries.json")
        val json = PROJECT_GITHUB_REMOTE_SUMMARY_JSON.encodeToString(ProjectGitHubWorkspaceRemoteSummaryCache.serializer(), cache)
        file.writeText(json)
    } catch (e: Exception) {
        // Ignore save errors
    }
}

internal const val PROJECT_GITHUB_WORKSPACE_REMOTE_SUMMARY_CACHE_TTL_MS = 5 * 60 * 1000L
internal const val PROJECT_GITHUB_WORKSPACE_REMOTE_SUMMARY_RESUME_CHECK_DEBOUNCE_MS = 15 * 1000L
internal const val PROJECT_GITHUB_WORKSPACE_REMOTE_SUMMARY_PREVIEW_TARGET_LIMIT = 4

internal data class ProjectGitHubWorkspaceRemoteSummaryStore(
    val summaries: Map<String, ProjectGitHubWorkspaceRemoteSummaryUi> = emptyMap(),
    val fetchedAtMillisByRoot: Map<String, Long> = emptyMap(),
    val lastUpdatedMillis: Long = 0,
    val cacheIdentity: String = "",
    val isLoading: Boolean = false,
    val globalErrorMessage: String? = null
)

internal data class ProjectGitHubWorkspaceRemoteTargetUi(
    val rootPath: String,
    val repo: ProjectGitHubRepoRef,
    val includeWorkItemPreview: Boolean
)

internal data class ProjectGitHubWorkspaceRemoteSummaryRefreshPlan(
    val shouldRefresh: Boolean,
    val targetsToRefresh: List<ProjectGitHubWorkspaceRemoteTargetUi> = emptyList(),
    val rootsToRemove: Set<String> = emptySet(),
    val cacheIdentity: String = ""
)

internal fun clearProjectGitHubWorkspaceRemoteSummaryStore():
    ProjectGitHubWorkspaceRemoteSummaryStore {
    return ProjectGitHubWorkspaceRemoteSummaryStore()
}

internal fun beginProjectGitHubWorkspaceRemoteSummaryRefresh(
    currentStore: ProjectGitHubWorkspaceRemoteSummaryStore
): ProjectGitHubWorkspaceRemoteSummaryStore {
    return currentStore.copy(
        isLoading = true,
        globalErrorMessage = null
    )
}

internal fun failProjectGitHubWorkspaceRemoteSummaryRefresh(
    currentStore: ProjectGitHubWorkspaceRemoteSummaryStore,
    errorMessage: String
): ProjectGitHubWorkspaceRemoteSummaryStore {
    return currentStore.copy(
        isLoading = false,
        globalErrorMessage = errorMessage
    )
}

internal fun shouldCheckProjectGitHubWorkspaceRemoteSummaryOnResume(
    showGitHubWorkspacePage: Boolean,
    lastResumeCheckAtMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    minIntervalMillis: Long = PROJECT_GITHUB_WORKSPACE_REMOTE_SUMMARY_RESUME_CHECK_DEBOUNCE_MS
): Boolean {
    if (!showGitHubWorkspacePage) return false
    return nowMillis - lastResumeCheckAtMillis >= minIntervalMillis
}

internal fun applyProjectGitHubWorkspaceRemoteSummaryRefreshResult(
    currentStore: ProjectGitHubWorkspaceRemoteSummaryStore,
    refreshPlan: ProjectGitHubWorkspaceRemoteSummaryRefreshPlan,
    refreshedSummaries: Map<String, ProjectGitHubWorkspaceRemoteSummaryUi>,
    fetchedAtMillis: Long?
): ProjectGitHubWorkspaceRemoteSummaryStore {
    val nextSummaries = currentStore.summaries
        .minus(refreshPlan.rootsToRemove)
        .plus(refreshedSummaries)
    val refreshedFetchedAt = fetchedAtMillis?.let {
        buildProjectGitHubWorkspaceRemoteSummaryFetchedAtMap(
            rootPaths = refreshedSummaries.keys,
            fetchedAtMillis = it
        )
    }.orEmpty()
    val nextFetchedAt = currentStore.fetchedAtMillisByRoot
        .minus(refreshPlan.rootsToRemove)
        .plus(refreshedFetchedAt)
    return currentStore.copy(
        summaries = nextSummaries,
        fetchedAtMillisByRoot = nextFetchedAt,
        cacheIdentity = refreshPlan.cacheIdentity,
        isLoading = false,
        globalErrorMessage = null
    )
}

internal fun buildProjectGitHubWorkspaceRemoteTargets(
    detectedRepos: List<ProjectDetectedRepoUi>,
    repoStatusSummaries: Map<String, ProjectGitStatusUi>,
    selectedRepoRoot: String? = null,
    currentStore: ProjectGitHubWorkspaceRemoteSummaryStore? = null
): List<ProjectGitHubWorkspaceRemoteTargetUi> {
    val previewRoots = buildProjectGitHubWorkspaceRemotePreviewRoots(
        detectedRepos = detectedRepos,
        repoStatusSummaries = repoStatusSummaries,
        selectedRepoRoot = selectedRepoRoot,
        currentStore = currentStore
    )
    return detectedRepos.mapNotNull { repo ->
        val remoteUrl = repoStatusSummaries[repo.rootPath]?.remoteUrl
        parseProjectGitHubRepoRef(remoteUrl)?.let { githubRepo ->
            ProjectGitHubWorkspaceRemoteTargetUi(
                rootPath = repo.rootPath,
                repo = githubRepo,
                includeWorkItemPreview = repo.rootPath in previewRoots
            )
        }
    }.distinctBy { it.rootPath }
}

internal fun planProjectGitHubWorkspaceRemoteSummaryRefresh(
    detectedRepos: List<ProjectDetectedRepoUi>,
    repoStatusSummaries: Map<String, ProjectGitStatusUi>,
    selectedRepoRoot: String?,
    token: String,
    apiBaseUrl: String,
    currentStore: ProjectGitHubWorkspaceRemoteSummaryStore,
    nowMillis: Long = System.currentTimeMillis(),
    cacheTtlMillis: Long = PROJECT_GITHUB_WORKSPACE_REMOTE_SUMMARY_CACHE_TTL_MS,
    forceRefreshTargets: Boolean = false
): ProjectGitHubWorkspaceRemoteSummaryRefreshPlan {
    val expectedIdentity = buildProjectGitHubWorkspaceRemoteSummaryCacheIdentity(
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    if (token.isBlank()) {
        return ProjectGitHubWorkspaceRemoteSummaryRefreshPlan(
            shouldRefresh = currentStore.summaries.isNotEmpty() ||
                currentStore.fetchedAtMillisByRoot.isNotEmpty() ||
                currentStore.cacheIdentity.isNotBlank()
        )
    }
    val targetRoots = buildProjectGitHubWorkspaceRemoteTargets(
        detectedRepos = detectedRepos,
        repoStatusSummaries = repoStatusSummaries,
        selectedRepoRoot = selectedRepoRoot,
        currentStore = currentStore
    )
    val targetRootPaths = targetRoots.map { it.rootPath }.toSet()
    val cachedRoots = (currentStore.summaries.keys + currentStore.fetchedAtMillisByRoot.keys).toSet()
    val rootsToRemove = cachedRoots - targetRootPaths
    if (targetRoots.isEmpty()) {
        return ProjectGitHubWorkspaceRemoteSummaryRefreshPlan(
            shouldRefresh = currentStore.summaries.isNotEmpty() ||
                currentStore.fetchedAtMillisByRoot.isNotEmpty() ||
                currentStore.cacheIdentity != expectedIdentity,
            rootsToRemove = rootsToRemove,
            cacheIdentity = expectedIdentity
        )
    }
    val shouldRefreshAll = currentStore.cacheIdentity != expectedIdentity ||
        (nowMillis - currentStore.lastUpdatedMillis >= cacheTtlMillis)
    val targetsToRefresh = targetRoots.filter { target ->
        val rootPath = target.rootPath
        val fetchedAtMillis = currentStore.fetchedAtMillisByRoot[rootPath] ?: return@filter true
        val summary = currentStore.summaries[rootPath] ?: return@filter true
        forceRefreshTargets ||
            shouldRefreshAll ||
            summary.repo.owner.isBlank() ||
            summary.repo.repo.isBlank() ||
            (target.includeWorkItemPreview && !summary.hasWorkItemPreview) ||
            nowMillis - fetchedAtMillis >= cacheTtlMillis
    }
    return ProjectGitHubWorkspaceRemoteSummaryRefreshPlan(
        shouldRefresh = shouldRefreshAll || rootsToRemove.isNotEmpty() || targetsToRefresh.isNotEmpty(),
        targetsToRefresh = if (shouldRefreshAll) targetRoots else targetsToRefresh,
        rootsToRemove = rootsToRemove,
        cacheIdentity = expectedIdentity
    )
}

internal fun buildProjectGitHubWorkspaceRemoteSummaryCacheIdentity(
    token: String,
    apiBaseUrl: String
): String {
    if (token.isBlank()) return ""
    return "${apiBaseUrl.trim()}#${token.hashCode()}"
}

internal fun buildProjectGitHubWorkspaceRemoteSummaryFetchedAtMap(
    rootPaths: Collection<String>,
    fetchedAtMillis: Long
): Map<String, Long> {
    return rootPaths.associateWith { fetchedAtMillis }
}

internal suspend fun loadProjectGitHubWorkspaceRemoteSummaries(
    targets: List<ProjectGitHubWorkspaceRemoteTargetUi>,
    token: String,
    apiBaseUrl: String
): Map<String, ProjectGitHubWorkspaceRemoteSummaryUi> {
    if (token.isBlank() || targets.isEmpty()) return emptyMap()
    return coroutineScope {
        targets.map { target ->
            async {
                target.rootPath to loadProjectGitHubWorkspaceRemoteSummary(
                    repo = target.repo,
                    token = token,
                    apiBaseUrl = apiBaseUrl,
                    includeWorkItemPreview = target.includeWorkItemPreview
                )
            }
        }.awaitAll().toMap()
    }
}

internal suspend fun loadProjectGitHubWorkspaceRemoteSummaries(
    detectedRepos: List<ProjectDetectedRepoUi>,
    repoStatusSummaries: Map<String, ProjectGitStatusUi>,
    selectedRepoRoot: String? = null,
    currentStore: ProjectGitHubWorkspaceRemoteSummaryStore? = null,
    token: String,
    apiBaseUrl: String
): Map<String, ProjectGitHubWorkspaceRemoteSummaryUi> {
    if (token.isBlank()) return emptyMap()
    val targets = buildProjectGitHubWorkspaceRemoteTargets(
        detectedRepos = detectedRepos,
        repoStatusSummaries = repoStatusSummaries,
        selectedRepoRoot = selectedRepoRoot,
        currentStore = currentStore
    )
    return loadProjectGitHubWorkspaceRemoteSummaries(
        targets = targets,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
}

private fun buildProjectGitHubWorkspaceRemotePreviewRoots(
    detectedRepos: List<ProjectDetectedRepoUi>,
    repoStatusSummaries: Map<String, ProjectGitStatusUi>,
    selectedRepoRoot: String?,
    currentStore: ProjectGitHubWorkspaceRemoteSummaryStore?,
    maxPreviewCount: Int = PROJECT_GITHUB_WORKSPACE_REMOTE_SUMMARY_PREVIEW_TARGET_LIMIT
): Set<String> {
    val importantCachedRoots = currentStore?.summaries.orEmpty()
        .filterValues { summary ->
            summary.latestRunHasIssue ||
                summary.hasOpenWorkItems ||
                !summary.errorMessage.isNullOrBlank()
        }
        .keys
    val localPriorityRoots = detectedRepos
        .sortedByDescending { repo ->
            calculateProjectGitHubWorkspacePreviewPriority(
                status = repoStatusSummaries[repo.rootPath] ?: ProjectGitStatusUi.empty(repo.rootPath),
                isSelected = repo.rootPath == selectedRepoRoot
            )
        }
        .map { it.rootPath }
    return buildList {
        selectedRepoRoot?.let(::add)
        addAll(importantCachedRoots)
        addAll(localPriorityRoots)
    }.filter { it.isNotBlank() }
        .distinct()
        .take(maxPreviewCount)
        .toSet()
}

private fun calculateProjectGitHubWorkspacePreviewPriority(
    status: ProjectGitStatusUi,
    isSelected: Boolean
): Int {
    if (isSelected) return 1000
    return when {
        status.conflictedFiles.isNotEmpty() -> 900
        status.behindCount > 0 -> 800
        status.hasWorkingTreeChanges -> 700
        status.isRepository -> 100
        else -> 0
    }
}

internal fun buildProjectGitHubWorkspaceRemoteSummaryStatusLabel(
    tokenConfigured: Boolean,
    targetRepoCount: Int,
    currentStore: ProjectGitHubWorkspaceRemoteSummaryStore
): String {
    if (!tokenConfigured) {
        return "GitHub Token 未配置，当前先展示本地 Git 状态和远端绑定结果。"
    }
    if (targetRepoCount == 0) {
        return "当前工作区还没有可拉取远端摘要的 GitHub 仓库。"
    }
    currentStore.globalErrorMessage?.takeIf { it.isNotBlank() }?.let {
        return if (currentStore.summaries.isNotEmpty()) {
            "最近一次远端摘要刷新失败，当前先展示已有缓存。"
        } else {
            "远端摘要暂时拉取失败，请稍后重试。"
        }
    }
    if (currentStore.isLoading) {
        return if (currentStore.summaries.isNotEmpty()) {
            "正在后台刷新 $targetRepoCount 个仓库的远端摘要，当前先展示已有缓存；重点仓库会补拉协作预览。"
        } else {
            "正在拉取 $targetRepoCount 个仓库的远端摘要，并为重点仓库补拉协作预览。"
        }
    }
    return if (currentStore.summaries.isNotEmpty()) {
        "已缓存 ${currentStore.summaries.size} 个仓库的远端摘要，重点仓库附带协作预览，可手动刷新。"
    } else {
        "工作区已识别到 $targetRepoCount 个 GitHub 仓库，准备拉取轻量远端摘要。"
    }
}

internal fun buildProjectGitHubWorkspaceRepoCards(
    repos: List<ProjectDetectedRepoUi>,
    repoStatusSummaries: Map<String, ProjectGitStatusUi>,
    selectedRepoRoot: String?,
    remoteSummaries: Map<String, ProjectGitHubWorkspaceRemoteSummaryUi>,
    filterType: ProjectGitHubWorkspaceFilterType = ProjectGitHubWorkspaceFilterType.ALL,
    searchQuery: String = ""
): List<ProjectGitHubWorkspaceRepoCardUi> {
    val allCards = repos.map { repo ->
        val status = repoStatusSummaries[repo.rootPath] ?: ProjectGitStatusUi.empty(repo.rootPath)
        val githubRepo = parseProjectGitHubRepoRef(status.remoteUrl)
        val remoteSummary = remoteSummaries[repo.rootPath]
        val latestWorkflowTitle = remoteSummary?.latestRun?.displayTitle
            ?.ifBlank { remoteSummary.latestRun.name }
            ?.ifBlank { null }
        val severityScore = calculateProjectGitHubWorkspaceRepoSeverity(
            hasGitMetadata = repo.hasGitMetadata,
            hasGitHubRepo = githubRepo != null,
            hasWorkingTreeChanges = status.hasWorkingTreeChanges,
            behindCount = status.behindCount,
            conflictCount = status.conflictedFiles.size,
            hasRemoteError = !remoteSummary?.errorMessage.isNullOrBlank(),
            latestRunHasIssue = remoteSummary?.latestRunHasIssue == true,
            hasOpenWorkItems = remoteSummary?.hasOpenWorkItems == true
        )
        ProjectGitHubWorkspaceRepoCardUi(
            rootPath = repo.rootPath,
            title = if (repo.isWorkspaceRoot) {
                "${repo.displayName}（工作区根）"
            } else {
                repo.relativePath
            },
            subtitle = buildString {
                append(if (status.branchSummary.isNotBlank()) status.branchSummary else "分支未知")
                if (githubRepo != null) {
                    append(" · GitHub ${githubRepo.owner}/${githubRepo.repo}")
                } else if (repo.hasGitMetadata) {
                    append(" · 未识别 GitHub 远端")
                } else {
                    append(" · 非 Git 仓库")
                }
            },
            changeSummary = "暂存 ${status.stagedFiles.size} · 修改 ${status.modifiedFiles.size} · 未跟踪 ${status.untrackedFiles.size} · 冲突 ${status.conflictedFiles.size} · ↑${status.aheadCount} ↓${status.behindCount}",
            highlightChanges = status.hasWorkingTreeChanges || status.behindCount > 0,
            remoteSummary = remoteSummary?.let { remote ->
                buildString {
                    append("默认分支 ")
                    append(remote.defaultBranch ?: "未知")
                    append(" · Issue ")
                    append(remote.openIssueCount)
                    append(" · PR ")
                    append(remote.openPullRequestCount)
                    if (remote.runningRunCount > 0) {
                        append(" · 运行中 ")
                        append(remote.runningRunCount)
                    }
                }
            },
            highlightRemoteSummary = remoteSummary?.latestRunHasIssue == true || remoteSummary?.hasOpenWorkItems == true,
            remoteErrorMessage = remoteSummary?.errorMessage,
            latestWorkflowSummary = latestWorkflowTitle?.let { title ->
                "最近工作流：$title · ${remoteSummary.latestRun.statusLabel}"
            },
            latestWorkflowHasIssue = remoteSummary?.latestRunHasIssue == true,
            isSelected = repo.rootPath == selectedRepoRoot,
            hasGitMetadata = repo.hasGitMetadata,
            hasGitHubRepo = githubRepo != null,
            hasWorkingTreeChanges = status.hasWorkingTreeChanges,
            behindCount = status.behindCount,
            conflictCount = status.conflictedFiles.size,
            latestRunHasIssue = remoteSummary?.latestRunHasIssue == true,
            hasOpenWorkItems = remoteSummary?.hasOpenWorkItems == true,
            openIssueCount = remoteSummary?.openIssueCount ?: 0,
            latestOpenIssue = remoteSummary?.latestOpenIssue,
            openPullRequestCount = remoteSummary?.openPullRequestCount ?: 0,
            latestOpenPullRequest = remoteSummary?.latestOpenPullRequest,
            latestWorkflowTitle = latestWorkflowTitle,
            latestRun = remoteSummary?.latestRun,
            severityScore = severityScore,
            severityLabel = projectGitHubWorkspaceRepoSeverityLabel(severityScore),
            recommendedActions = buildProjectGitHubWorkspaceRepoRecommendedActions(
                hasGitMetadata = repo.hasGitMetadata,
                hasGitHubRepo = githubRepo != null,
                hasWorkingTreeChanges = status.hasWorkingTreeChanges,
                behindCount = status.behindCount,
                conflictCount = status.conflictedFiles.size,
                hasRemoteError = !remoteSummary?.errorMessage.isNullOrBlank(),
                latestRunHasIssue = remoteSummary?.latestRunHasIssue == true,
                latestRun = remoteSummary?.latestRun,
                hasOpenWorkItems = remoteSummary?.hasOpenWorkItems == true,
                latestOpenIssue = remoteSummary?.latestOpenIssue,
                latestOpenPullRequest = remoteSummary?.latestOpenPullRequest
            )
        )
    }

    val filteredCards = when (filterType) {
        ProjectGitHubWorkspaceFilterType.ALL -> allCards
        ProjectGitHubWorkspaceFilterType.ABNORMAL -> allCards.filter { it.severityScore > 0 }
        ProjectGitHubWorkspaceFilterType.LOCAL_CHANGES -> allCards.filter { it.hasWorkingTreeChanges || it.conflictCount > 0 }
        ProjectGitHubWorkspaceFilterType.OPEN_ITEMS -> allCards.filter { it.hasOpenWorkItems }
        ProjectGitHubWorkspaceFilterType.GITHUB_ONLY -> allCards.filter { it.hasGitHubRepo }
    }.let { cards ->
        if (searchQuery.isBlank()) {
            cards
        } else {
            val query = searchQuery.trim().lowercase()
            cards.filter { card ->
                card.title.lowercase().contains(query) ||
                    card.rootPath.lowercase().contains(query) ||
                    card.subtitle.lowercase().contains(query)
            }
        }
    }

    return filteredCards.sortedWith(
        compareByDescending<ProjectGitHubWorkspaceRepoCardUi> { it.isSelected }
            .thenByDescending { it.severityScore }
            .thenByDescending { it.hasGitHubRepo }
            .thenBy { it.title }
    )
}

internal fun buildProjectGitHubGlobalTaskCenter(
    repoCards: List<ProjectGitHubWorkspaceRepoCardUi>
): ProjectGitHubGlobalTaskCenterUi {
    val criticalTasks = mutableListOf<ProjectGitHubWorkspaceTaskUi>()
    val attentionTasks = mutableListOf<ProjectGitHubWorkspaceTaskUi>()

    repoCards.forEach { card ->
        // 1. 工作流失败任务
        if (card.latestRunHasIssue && card.latestRun != null) {
            criticalTasks.add(ProjectGitHubWorkspaceTaskUi(
                title = "工作流失败: ${card.title}",
                subtitle = card.latestWorkflowSummary ?: "最近运行失败",
                repoRoot = card.rootPath,
                repoTitle = card.title,
                isCritical = true,
                kind = ProjectGitHubWorkspaceTaskKind.WORKFLOW,
                destinationLabel = "运行详情",
                targetWorkflowRun = card.latestRun,
                actionLabel = "查看详情"
            ))
        }

        // 2. 本地冲突任务
        if (card.conflictCount > 0) {
            criticalTasks.add(ProjectGitHubWorkspaceTaskUi(
                title = "Git 冲突: ${card.title}",
                subtitle = "发现 ${card.conflictCount} 个冲突文件，请尽快处理。",
                repoRoot = card.rootPath,
                repoTitle = card.title,
                isCritical = true,
                kind = ProjectGitHubWorkspaceTaskKind.LOCAL_CONFLICT,
                destinationLabel = "仓库概览",
                actionLabel = "处理冲突"
            ))
        }

        // 3. 落后远端任务
        if (card.behindCount > 0) {
            attentionTasks.add(ProjectGitHubWorkspaceTaskUi(
                title = "落后远端: ${card.title}",
                subtitle = "落后 ${card.behindCount} 个提交，建议同步。",
                repoRoot = card.rootPath,
                repoTitle = card.title,
                isCritical = false,
                kind = ProjectGitHubWorkspaceTaskKind.SYNC,
                destinationLabel = "仓库概览",
                targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW,
                actionLabel = "去同步"
            ))
        }

        // 4. 开放事项任务 (PR 优先)
        if (card.openPullRequestCount > 0 && card.latestOpenPullRequest != null) {
            attentionTasks.add(ProjectGitHubWorkspaceTaskUi(
                title = "待合并 PR: ${card.title}",
                subtitle = "最新 PR #${card.latestOpenPullRequest.number}: ${card.latestOpenPullRequest.title}",
                repoRoot = card.rootPath,
                repoTitle = card.title,
                isCritical = false,
                kind = ProjectGitHubWorkspaceTaskKind.COLLABORATION,
                destinationLabel = "PR 详情",
                targetPullRequest = card.latestOpenPullRequest,
                actionLabel = "查看 PR"
            ))
        } else if (card.openIssueCount > 0 && card.latestOpenIssue != null) {
            attentionTasks.add(ProjectGitHubWorkspaceTaskUi(
                title = "开放 Issue: ${card.title}",
                subtitle = "最新 Issue #${card.latestOpenIssue.number}: ${card.latestOpenIssue.title}",
                repoRoot = card.rootPath,
                repoTitle = card.title,
                isCritical = false,
                kind = ProjectGitHubWorkspaceTaskKind.COLLABORATION,
                destinationLabel = "Issue 详情",
                targetIssue = card.latestOpenIssue,
                actionLabel = "查看 Issue"
            ))
        }
    }

    return ProjectGitHubGlobalTaskCenterUi(
        criticalTasks = criticalTasks.sortedByDescending { it.isCritical },
        attentionTasks = attentionTasks
    )
}

internal fun buildProjectGitHubWorkspaceOverview(
    repoCards: List<ProjectGitHubWorkspaceRepoCardUi>,
    remoteSummaryCount: Int,
    activeRepo: ProjectGitHubRepoRef?,
    activeGitHubState: ProjectGitHubActionsState,
    lastUpdatedMillis: Long = 0
): ProjectGitHubWorkspaceOverviewUi {
    val tasks = buildList {
        repoCards.firstOrNull { it.conflictCount > 0 }?.let { summary ->
            add(
                ProjectGitHubWorkspaceTaskUi(
                    title = "优先处理 ${summary.title} 的冲突文件",
                    subtitle = "当前有 ${summary.conflictCount} 个冲突文件，建议先进入仓库工作台处理。",
                    repoRoot = summary.rootPath,
                    repoTitle = summary.title,
                    isCritical = true,
                    kind = ProjectGitHubWorkspaceTaskKind.LOCAL_CONFLICT,
                    destinationLabel = "仓库概览",
                    actionLabel = "处理冲突",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
                )
            )
        }
        repoCards.firstOrNull { it.latestRunHasIssue }?.let { summary ->
            add(
                ProjectGitHubWorkspaceTaskUi(
                    title = "${summary.title} 最近工作流异常",
                    subtitle = summary.latestWorkflowTitle?.let { "最近异常工作流：$it" }
                        ?: "最近一次工作流运行状态异常。",
                    repoRoot = summary.rootPath,
                    repoTitle = summary.title,
                    isCritical = true,
                    kind = ProjectGitHubWorkspaceTaskKind.WORKFLOW,
                    destinationLabel = "运行详情",
                    actionLabel = "运行详情",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.WORKFLOW,
                    targetWorkflowRun = summary.latestRun
                )
            )
        }
        repoCards.firstOrNull { !it.remoteErrorMessage.isNullOrBlank() }?.let { summary ->
            add(
                ProjectGitHubWorkspaceTaskUi(
                    title = "检查 ${summary.title} 的远端摘要结果",
                    subtitle = summary.remoteErrorMessage.orEmpty(),
                    repoRoot = summary.rootPath,
                    repoTitle = summary.title,
                    isCritical = false,
                    kind = ProjectGitHubWorkspaceTaskKind.REMOTE_ERROR,
                    destinationLabel = "仓库概览",
                    actionLabel = "检查仓库",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
                )
            )
        }
        repoCards.firstOrNull { it.behindCount > 0 }?.let { summary ->
            add(
                ProjectGitHubWorkspaceTaskUi(
                    title = "${summary.title} 落后远端 ${summary.behindCount} 个提交",
                    subtitle = "建议先同步远端状态，再处理本地工作。",
                    repoRoot = summary.rootPath,
                    repoTitle = summary.title,
                    isCritical = true,
                    kind = ProjectGitHubWorkspaceTaskKind.SYNC,
                    destinationLabel = "仓库概览",
                    actionLabel = "同步远端",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
                )
            )
        }
        repoCards.firstOrNull { it.hasWorkingTreeChanges }?.let { summary ->
            add(
                ProjectGitHubWorkspaceTaskUi(
                    title = "${summary.title} 存在未提交本地改动",
                    subtitle = "建议先整理暂存区和提交说明，避免后续协作状态混乱。",
                    repoRoot = summary.rootPath,
                    repoTitle = summary.title,
                    isCritical = false,
                    kind = ProjectGitHubWorkspaceTaskKind.LOCAL_CHANGES,
                    destinationLabel = "仓库概览",
                    actionLabel = "整理改动",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
                )
            )
        }
        repoCards.firstOrNull { it.hasGitMetadata && !it.hasGitHubRepo }?.let { summary ->
            add(
                ProjectGitHubWorkspaceTaskUi(
                    title = "${summary.title} 还没有识别出 GitHub 远端绑定",
                    subtitle = "当前只能用本地 Git 能力，建议检查 origin 地址或重新绑定远端。",
                    repoRoot = summary.rootPath,
                    repoTitle = summary.title,
                    isCritical = false,
                    kind = ProjectGitHubWorkspaceTaskKind.REMOTE_BINDING,
                    destinationLabel = "远端页",
                    actionLabel = "检查绑定",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.REMOTE
                )
            )
        }
        repoCards.firstOrNull { it.hasOpenWorkItems }?.let { summary ->
            add(
                ProjectGitHubWorkspaceTaskUi(
                    title = "${summary.title} 仍有开放事项",
                    subtitle = when {
                        summary.latestOpenPullRequest != null ->
                            "最近开放 PR：#${summary.latestOpenPullRequest.number} ${summary.latestOpenPullRequest.title}"
                        summary.latestOpenIssue != null ->
                            "最近开放 Issue：#${summary.latestOpenIssue.number} ${summary.latestOpenIssue.title}"
                        else -> "Issue ${summary.openIssueCount} / PR ${summary.openPullRequestCount}"
                    },
                    repoRoot = summary.rootPath,
                    repoTitle = summary.title,
                    isCritical = false,
                    kind = ProjectGitHubWorkspaceTaskKind.COLLABORATION,
                    destinationLabel = when {
                        summary.latestOpenPullRequest != null -> "PR 详情"
                        summary.latestOpenIssue != null -> "Issue 详情"
                        summary.openPullRequestCount > 0 -> "PR 列表"
                        else -> "Issue 列表"
                    },
                    actionLabel = when {
                        summary.latestOpenPullRequest != null -> "PR 详情"
                        summary.latestOpenIssue != null -> "Issue 详情"
                        summary.openPullRequestCount > 0 -> "查看 PR"
                        else -> "查看 Issue"
                    },
                    targetTab = if (summary.openPullRequestCount > 0) {
                        ProjectGitHubWorkspaceRepoWorkbenchTab.PULL_REQUESTS
                    } else {
                        ProjectGitHubWorkspaceRepoWorkbenchTab.ISSUES
                    },
                    targetIssue = summary.latestOpenIssue,
                    targetPullRequest = summary.latestOpenPullRequest
                )
            )
        }
    }.sortedByDescending { it.isCritical }.ifEmpty {
        listOf(
            ProjectGitHubWorkspaceTaskUi(
                title = "当前工作区暂无明显异常，可继续补强仓库级 GitHub 摘要加载器。",
                subtitle = "工作区状态整体平稳，可以继续做搜索、任务中心和下载能力增强。",
                repoRoot = "",
                repoTitle = "当前工作区",
                isCritical = false
            )
        )
    }
    return ProjectGitHubWorkspaceOverviewUi(
        repoCount = repoCards.size,
        gitRepoCount = repoCards.count { it.hasGitMetadata },
        githubRepoCount = repoCards.count { it.hasGitHubRepo },
        remoteSummaryCount = remoteSummaryCount,
        dirtyRepoCount = repoCards.count { it.hasWorkingTreeChanges },
        behindRepoCount = repoCards.count { it.behindCount > 0 },
        conflictRepoCount = repoCards.count { it.conflictCount > 0 },
        failingWorkflowRepoCount = repoCards.count { it.latestRunHasIssue },
        openWorkItemRepoCount = repoCards.count { it.hasOpenWorkItems },
        criticalTaskCount = tasks.count { it.isCritical },
        attentionTaskCount = tasks.count { !it.isCritical && it.repoRoot.isNotBlank() },
        healthyRepoCount = repoCards.count { it.severityScore <= 0 },
        lastUpdatedLabel = if (lastUpdatedMillis == 0L) "从未更新" else formatProjectDateTime(lastUpdatedMillis)
    )
}

private fun calculateProjectGitHubWorkspaceRepoSeverity(
    hasGitMetadata: Boolean,
    hasGitHubRepo: Boolean,
    hasWorkingTreeChanges: Boolean,
    behindCount: Int,
    conflictCount: Int,
    hasRemoteError: Boolean,
    latestRunHasIssue: Boolean,
    hasOpenWorkItems: Boolean
): Int {
    if (!hasGitMetadata) return 0
    return when {
        conflictCount > 0 -> 100
        latestRunHasIssue -> 95
        hasRemoteError -> 90
        behindCount > 0 -> 80
        hasWorkingTreeChanges -> 70
        !hasGitHubRepo -> 60
        hasOpenWorkItems -> 40
        else -> 0
    }
}

private fun projectGitHubWorkspaceRepoSeverityLabel(severityScore: Int): String = when {
    severityScore >= 90 -> "优先处理"
    severityScore >= 70 -> "建议尽快处理"
    severityScore > 0 -> "可跟进"
    else -> "状态稳定"
}

private fun buildProjectGitHubWorkspaceRepoRecommendedActions(
    hasGitMetadata: Boolean,
    hasGitHubRepo: Boolean,
    hasWorkingTreeChanges: Boolean,
    behindCount: Int,
    conflictCount: Int,
    hasRemoteError: Boolean,
    latestRunHasIssue: Boolean,
    latestRun: ProjectGitHubWorkflowRunUi?,
    hasOpenWorkItems: Boolean,
    latestOpenIssue: ProjectGitHubIssueUi?,
    latestOpenPullRequest: ProjectGitHubPullRequestUi?
): List<ProjectGitHubWorkspaceQuickActionUi> {
    if (!hasGitMetadata) {
        return listOf(
            ProjectGitHubWorkspaceQuickActionUi(
                label = "查看概览",
                targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
            )
        )
    }
    return buildList {
        if (conflictCount > 0) {
            add(
                ProjectGitHubWorkspaceQuickActionUi(
                    label = "处理冲突",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
                )
            )
        }
        if (latestRunHasIssue) {
            add(
                ProjectGitHubWorkspaceQuickActionUi(
                    label = "失败工作流",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.WORKFLOW,
                    targetWorkflowRun = latestRun
                )
            )
        }
        if (hasRemoteError) {
            add(
                ProjectGitHubWorkspaceQuickActionUi(
                    label = "检查概览",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
                )
            )
        }
        if (behindCount > 0) {
            add(
                ProjectGitHubWorkspaceQuickActionUi(
                    label = "同步远端",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
                )
            )
        }
        if (hasWorkingTreeChanges) {
            add(
                ProjectGitHubWorkspaceQuickActionUi(
                    label = "整理改动",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
                )
            )
        }
        if (!hasGitHubRepo) {
            add(
                ProjectGitHubWorkspaceQuickActionUi(
                    label = "检查绑定",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.REMOTE
                )
            )
        }
        if (hasOpenWorkItems) {
            add(
                ProjectGitHubWorkspaceQuickActionUi(
                    label = when {
                        latestOpenPullRequest != null -> "PR 详情"
                        latestOpenIssue != null -> "Issue 详情"
                        else -> "查看协作"
                    },
                    targetTab = when {
                        latestOpenPullRequest != null -> ProjectGitHubWorkspaceRepoWorkbenchTab.PULL_REQUESTS
                        latestOpenIssue != null -> ProjectGitHubWorkspaceRepoWorkbenchTab.ISSUES
                        else -> ProjectGitHubWorkspaceRepoWorkbenchTab.PULL_REQUESTS
                    },
                    targetIssue = latestOpenIssue,
                    targetPullRequest = latestOpenPullRequest
                )
            )
        }
        if (isEmpty()) {
            add(
                ProjectGitHubWorkspaceQuickActionUi(
                    label = "进入工作台",
                    targetTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
                )
            )
        }
    }.take(3)
}
