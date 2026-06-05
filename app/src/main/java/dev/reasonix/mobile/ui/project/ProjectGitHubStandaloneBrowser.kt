package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.rememberReasonixChromeColor
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor

internal enum class ProjectGitHubStandaloneSection(val label: String) {
    REPOSITORIES("项目"),
    WORKFLOWS("工作流"),
    RELEASES("Release"),
    ISSUES("Issue"),
    PULL_REQUESTS("PR"),
    README("README")
}

@Composable
internal fun ProjectGitHubStandaloneBrowserSection(
    tokenConfigured: Boolean,
    repoListState: ProjectGitHubViewerRepositoriesState,
    isRepoListLoading: Boolean,
    selectedRepo: ProjectGitHubAccountRepoUi?,
    selectedTaskRepo: ProjectGitHubRepoRef?,
    activeSection: ProjectGitHubStandaloneSection,
    repoDetailState: ProjectGitHubActionsState,
    isRepoDetailLoading: Boolean,
    remoteRepoState: ProjectGitHubRemoteBrowserState,
    isRemoteRepoLoading: Boolean,
    isActionRunning: Boolean,
    remoteRepoRefDraft: String,
    readme: ProjectGitHubReadmeUi?,
    readmeErrorMessage: String?,
    isReadmeLoading: Boolean,
    onRefreshRepoList: () -> Unit,
    onSelectRepo: (ProjectGitHubAccountRepoUi) -> Unit,
    onShowRepoMenu: (ProjectGitHubAccountRepoUi) -> Unit,
    onEditRepoDescription: (ProjectGitHubAccountRepoUi) -> Unit,
    onBackToRepoList: () -> Unit,
    onChangeSection: (ProjectGitHubStandaloneSection) -> Unit,
    onRefreshRepoDetail: () -> Unit,
    onOpenRepoPage: () -> Unit,
    onRemoteRepoRefDraftChange: (String) -> Unit,
    onRefreshRemoteRepository: () -> Unit,
    onApplyRemoteRef: () -> Unit,
    onOpenRemoteParent: () -> Unit,
    onOpenRemoteRoot: () -> Unit,
    onOpenRemoteEntry: (ProjectGitHubRemoteEntryUi) -> Unit,
    onOpenRemoteEntryPage: (ProjectGitHubRemoteEntryUi) -> Unit,
    onRunWorkflow: (ProjectGitHubWorkflowUi) -> Unit,
    onOpenWorkflowPage: (ProjectGitHubWorkflowUi) -> Unit,
    onOpenArtifacts: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRunPage: (ProjectGitHubWorkflowRunUi) -> Unit,
    onDownloadRunLogs: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRunDetail: (ProjectGitHubWorkflowRunUi) -> Unit,
    onCreateRelease: () -> Unit,
    onEditRelease: (ProjectGitHubReleaseUi) -> Unit,
    onToggleReleaseMode: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onTogglePrerelease: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onDeleteRelease: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleasePage: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleaseAssets: (ProjectGitHubReleaseUi) -> Unit,
    onCreateIssue: () -> Unit,
    onOpenDetail: (ProjectGitHubIssueUi) -> Unit,
    onToggleIssueState: (ProjectGitHubIssueUi, Boolean) -> Unit,
    onOpenIssuePage: (ProjectGitHubIssueUi) -> Unit,
    onCreatePullRequest: () -> Unit,
    onOpenPullRequestDetail: (ProjectGitHubPullRequestUi) -> Unit,
    onTogglePullRequestState: (ProjectGitHubPullRequestUi, Boolean) -> Unit,
    onMergePullRequest: (ProjectGitHubPullRequestUi) -> Unit,
    onOpenPullRequestPage: (ProjectGitHubPullRequestUi) -> Unit,
    onEditReadme: (ProjectGitHubReadmeUi) -> Unit
) {
    val githubColors = rememberGitHubColors()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GitHubCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GitHub 账号仓库", style = MaterialTheme.typography.titleMedium, color = githubColors.text)
                Text(
                    text = buildString {
                        append(repoListState.viewerLogin?.let { "@$it" } ?: "未识别账号")
                        repoListState.viewerName?.takeIf { it.isNotBlank() }?.let {
                            append(" · ")
                            append(it)
                        }
                        if (repoListState.repositories.isNotEmpty()) {
                            append(" · 仓库 ${repoListState.repositories.size}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = githubColors.mutedText
                )
                Text(
                    text = if (selectedRepo == null) {
                        "未选择本地项目时，直接显示当前账号下的仓库；点击进入二级页，长按可设为当前任务仓库。"
                    } else {
                        "当前查看 ${selectedRepo.fullName}，未被设为任务仓库的项目只允许浏览，修改前需确认。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = githubColors.mutedText
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onRefreshRepoList,
                        enabled = tokenConfigured && !isRepoListLoading
                    ) {
                        Text("刷新仓库", color = githubColors.accent)
                    }
                    if (selectedRepo != null) {
                        TextButton(onClick = onBackToRepoList) {
                            Text("返回列表", color = githubColors.accent)
                        }
                    }
                }
                if (!tokenConfigured) {
                    Text(
                        text = "请先在设置页填写 GitHub Token，未选择本地项目时才能直接浏览账号仓库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = githubColors.danger
                    )
                }
                repoListState.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = githubColors.danger
                    )
                }
            }
        }

        if (selectedRepo == null) {
            if (isRepoListLoading) {
                ProjectGitHubStandaloneLoadingCard("正在读取当前账号下的仓库列表...")
            }
            if (repoListState.repositories.isEmpty() && !isRepoListLoading && tokenConfigured) {
                GitHubCard {
                    Text(
                        text = "当前账号下还没有读取到仓库，或该 Token 没有仓库读取权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = githubColors.mutedText
                    )
                }
            }
            repoListState.repositories.forEach { repo ->
                ProjectGitHubStandaloneRepoRow(
                    repo = repo,
                    isTaskRepo = selectedTaskRepo == repo.repoRef,
                    onClick = { onSelectRepo(repo) },
                    onLongClick = { onShowRepoMenu(repo) }
                )
            }
            return
        }

        GitHubCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(selectedRepo.fullName, style = MaterialTheme.typography.titleMedium, color = githubColors.text)
                GitHubCard(
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { onEditRepoDescription(selectedRepo) }
                    )
                ) {
                    Text(
                        text = selectedRepo.description.ifBlank { "这个仓库暂时还没有简介。长按这里可编辑。" },
                        style = MaterialTheme.typography.bodySmall,
                        color = githubColors.mutedText
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    GitHubLabel(selectedRepo.visibilityLabel, githubColors.mutedText)
                    Text(
                        text = "${selectedRepo.stargazerCount} 星标 · ${selectedRepo.forkCount} 复刻",
                        style = MaterialTheme.typography.labelSmall,
                        color = githubColors.mutedText
                    )
                }
                Text(
                    text = if (selectedTaskRepo == selectedRepo.repoRef) {
                        "当前任务仓库，可直接编辑源码。"
                    } else {
                        "当前不是任务仓库，可浏览；若要修改该仓库，后续需要用户确认。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedTaskRepo == selectedRepo.repoRef) githubColors.primary else githubColors.mutedText
                )
            }
        }

        ProjectGitHubStandaloneTopActions(
            selectedRepo = selectedRepo,
            activeSection = activeSection,
            onBackToRepoList = onBackToRepoList,
            onChangeSection = onChangeSection
        )

        when (activeSection) {
            ProjectGitHubStandaloneSection.REPOSITORIES -> {
                ProjectGitHubStandaloneProjectSection(
                    tokenConfigured = tokenConfigured,
                    selectedRepo = selectedRepo,
                    repoDetailState = repoDetailState,
                    isRepoDetailLoading = isRepoDetailLoading,
                    remoteRepoState = remoteRepoState,
                    isRemoteRepoLoading = isRemoteRepoLoading,
                    isActionRunning = isActionRunning,
                    remoteRepoRefDraft = remoteRepoRefDraft,
                    onRefreshRepoDetail = onRefreshRepoDetail,
                    onOpenRepoPage = onOpenRepoPage,
                    onRemoteRepoRefDraftChange = onRemoteRepoRefDraftChange,
                    onRefreshRemoteRepository = onRefreshRemoteRepository,
                    onApplyRemoteRef = onApplyRemoteRef,
                    onOpenRemoteParent = onOpenRemoteParent,
                    onOpenRemoteRoot = onOpenRemoteRoot,
                    onOpenRemoteEntry = onOpenRemoteEntry,
                    onOpenRemoteEntryPage = onOpenRemoteEntryPage,
                    onShowWorkflows = {
                        onChangeSection(ProjectGitHubStandaloneSection.WORKFLOWS)
                    },
                    onShowReleases = {
                        onChangeSection(ProjectGitHubStandaloneSection.RELEASES)
                    },
                    onShowIssues = {
                        onChangeSection(ProjectGitHubStandaloneSection.ISSUES)
                    },
                    onShowPullRequests = {
                        onChangeSection(ProjectGitHubStandaloneSection.PULL_REQUESTS)
                    },
                    onShowReadme = {
                        onChangeSection(ProjectGitHubStandaloneSection.README)
                    },
                    onOpenLatestRunDetail = repoDetailState.recentRuns.firstOrNull()?.let { run ->
                        { onOpenRunDetail(run) }
                    }
                )
            }

            ProjectGitHubStandaloneSection.WORKFLOWS -> {
                ProjectGitHubActionsSection(
                    state = repoDetailState,
                    isLoading = isRepoDetailLoading,
                    isActionRunning = false,
                    tokenConfigured = tokenConfigured,
                    onRefresh = onRefreshRepoDetail,
                    onOpenRepo = onOpenRepoPage,
                    onRunWorkflow = onRunWorkflow,
                    onOpenWorkflowPage = onOpenWorkflowPage,
                    onOpenArtifacts = onOpenArtifacts,
                    onOpenRunPage = onOpenRunPage,
                    onDownloadRunLogs = onDownloadRunLogs,
                    onOpenRunDetail = onOpenRunDetail
                )
            }

            ProjectGitHubStandaloneSection.RELEASES -> {
                ProjectGitHubStandaloneReleaseSection(
                    releases = repoDetailState.releases,
                    isLoading = isRepoDetailLoading,
                    isActionRunning = isActionRunning,
                    onRefresh = onRefreshRepoDetail,
                    onCreateRelease = onCreateRelease,
                    onEditRelease = onEditRelease,
                    onToggleReleaseMode = onToggleReleaseMode,
                    onTogglePrerelease = onTogglePrerelease,
                    onDeleteRelease = onDeleteRelease,
                    onOpenReleasePage = onOpenReleasePage,
                    onOpenReleaseAssets = onOpenReleaseAssets
                )
            }

            ProjectGitHubStandaloneSection.ISSUES -> {
                ProjectGitHubIssueSection(
                    issues = repoDetailState.issues,
                    isActionRunning = isActionRunning,
                    onCreateIssue = onCreateIssue,
                    onOpenDetail = onOpenIssueDetail,
                    onToggleIssueState = onToggleIssueState,
                    onOpenIssuePage = onOpenIssuePage
                )
            }

            ProjectGitHubStandaloneSection.PULL_REQUESTS -> {
                ProjectGitHubPullRequestSection(
                    pullRequests = repoDetailState.pullRequests,
                    isActionRunning = isActionRunning,
                    onCreatePullRequest = onCreatePullRequest,
                    onOpenDetail = onOpenPullRequestDetail,
                    onTogglePullRequestState = onTogglePullRequestState,
                    onMergePullRequest = onMergePullRequest,
                    onOpenPullRequestPage = onOpenPullRequestPage
                )
            }

            ProjectGitHubStandaloneSection.README -> {
                ProjectGitHubReadmeSection(
                    readme = readme,
                    isLoading = isReadmeLoading,
                    errorMessage = readmeErrorMessage,
                    onRefresh = onRefreshRepoDetail,
                    onOpenRepoPage = onOpenRepoPage,
                    onEditReadme = onEditReadme
                )
            }
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneProjectSection(
    tokenConfigured: Boolean,
    selectedRepo: ProjectGitHubAccountRepoUi,
    repoDetailState: ProjectGitHubActionsState,
    isRepoDetailLoading: Boolean,
    remoteRepoState: ProjectGitHubRemoteBrowserState,
    isRemoteRepoLoading: Boolean,
    isActionRunning: Boolean,
    remoteRepoRefDraft: String,
    onRefreshRepoDetail: () -> Unit,
    onOpenRepoPage: () -> Unit,
    onRemoteRepoRefDraftChange: (String) -> Unit,
    onRefreshRemoteRepository: () -> Unit,
    onApplyRemoteRef: () -> Unit,
    onOpenRemoteParent: () -> Unit,
    onOpenRemoteRoot: () -> Unit,
    onOpenRemoteEntry: (ProjectGitHubRemoteEntryUi) -> Unit,
    onOpenRemoteEntryPage: (ProjectGitHubRemoteEntryUi) -> Unit,
    onShowWorkflows: () -> Unit,
    onShowReleases: () -> Unit,
    onShowIssues: () -> Unit,
    onShowPullRequests: () -> Unit,
    onShowReadme: () -> Unit,
    onOpenLatestRunDetail: (() -> Unit)?
) {
    val githubColors = rememberGitHubColors()
    val latestRun = remember(repoDetailState.recentRuns) { repoDetailState.recentRuns.firstOrNull() }
    val latestRelease = remember(repoDetailState.releases) { repoDetailState.releases.firstOrNull() }
    val latestIssue = remember(repoDetailState.issues) { repoDetailState.issues.firstOrNull() }
    val latestPullRequest = remember(repoDetailState.pullRequests) {
        repoDetailState.pullRequests.firstOrNull()
    }
    GitHubCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("项目概览", style = MaterialTheme.typography.titleMedium, color = githubColors.text)
            Text(
                text = "这里先提供远端仓库树浏览、网页入口和直接编辑链路。当前任务仓库会保留“可直接编辑”标记。",
                style = MaterialTheme.typography.bodySmall,
                color = githubColors.mutedText
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onRefreshRepoDetail,
                    enabled = tokenConfigured && !isRepoDetailLoading
                ) {
                    Text("刷新详情", color = githubColors.accent)
                }
                TextButton(onClick = onOpenRepoPage) {
                    Text("打开网页", color = githubColors.accent)
                }
            }
            Text(
                text = "默认分支: ${
                    repoDetailState.defaultBranch
                        .takeIf { !it.isNullOrBlank() }
                        ?: selectedRepo.defaultBranch.ifBlank { "未知" }
                }",
                style = MaterialTheme.typography.bodySmall,
                color = githubColors.mutedText
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GitHubLabel("工作流 ${repoDetailState.workflows.size}", githubColors.mutedText, Modifier.clickable { onShowWorkflows() })
                GitHubLabel("运行 ${repoDetailState.recentRuns.size}", githubColors.mutedText, Modifier.clickable { onShowWorkflows() })
                GitHubLabel("Release ${repoDetailState.releases.size}", githubColors.mutedText, Modifier.clickable { onShowReleases() })
                GitHubLabel("README", githubColors.mutedText, Modifier.clickable { onShowReadme() })
                GitHubLabel("Issue ${repoDetailState.issues.size}", githubColors.mutedText, Modifier.clickable { onShowIssues() })
                GitHubLabel("PR ${repoDetailState.pullRequests.size}", githubColors.mutedText, Modifier.clickable { onShowPullRequests() })
            }
            latestRun?.let { run ->
                GitHubCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "最近工作流活动",
                            style = MaterialTheme.typography.labelMedium,
                            color = githubColors.accent
                        )
                        Text(
                            text = run.displayTitle.ifBlank { run.name.ifBlank { "最近一次运行" } },
                            style = MaterialTheme.typography.bodyMedium,
                            color = githubColors.text
                        )
                        Text(
                            text = "${run.statusLabel} · 分支 ${run.headBranch.ifBlank { "未知" }} · 更新 ${run.updatedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (run.hasIssue) githubColors.danger else githubColors.mutedText
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onShowWorkflows) {
                                Text("切到工作流", color = githubColors.accent)
                            }
                            onOpenLatestRunDetail?.let { openDetail ->
                                TextButton(onClick = openDetail) {
                                    Text("运行详情", color = githubColors.accent)
                                }
                            }
                        }
                    }
                }
            }
            latestRelease?.let { release ->
                GitHubCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "最近 Release",
                            style = MaterialTheme.typography.labelMedium,
                            color = githubColors.accent
                        )
                        Text(
                            text = release.name.ifBlank { release.tagName },
                            style = MaterialTheme.typography.bodyMedium,
                            color = githubColors.text
                        )
                        Text(
                            text = buildString {
                                append(
                                    when {
                                        release.isDraft -> "草稿"
                                        release.isPrerelease -> "预发布"
                                        else -> "正式版"
                                    }
                                )
                                append(" · 资产 ${release.assets.size}")
                                append(" · ")
                                append(
                                    release.publishedAt
                                        .takeIf { it.isNotBlank() }
                                        ?.let(::formatProjectGitHubIsoDateTime)
                                        ?: "发布时间未知"
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = githubColors.mutedText
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onShowReleases) {
                                Text("切到 Release", color = githubColors.accent)
                            }
                            TextButton(onClick = onShowReadme) {
                                Text("README", color = githubColors.accent)
                            }
                        }
                    }
                }
            }
            latestIssue?.let { issue ->
                GitHubCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "最近 Issue",
                            style = MaterialTheme.typography.labelMedium,
                            color = githubColors.accent
                        )
                        Text(
                            text = "#${issue.number} · ${issue.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = githubColors.text
                        )
                        Text(
                            text = "${issue.stateLabel} · ${issue.authorLabel} · 更新 ${issue.updatedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (issue.isOpen) githubColors.primary else githubColors.purple
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onShowIssues) {
                                Text("切到 Issue", color = githubColors.accent)
                            }
                        }
                    }
                }
            }
            latestPullRequest?.let { pullRequest ->
                GitHubCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "最近 PR",
                            style = MaterialTheme.typography.labelMedium,
                            color = githubColors.accent
                        )
                        Text(
                            text = "#${pullRequest.number} · ${pullRequest.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = githubColors.text
                        )
                        Text(
                            text = buildString {
                                append(pullRequest.stateLabel)
                                append(" · ")
                                append(pullRequest.headBranch)
                                append(" -> ")
                                append(pullRequest.baseBranch)
                                append(" · 更新 ")
                                append(pullRequest.updatedAt)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pullRequest.canMerge) githubColors.primary else githubColors.mutedText
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onShowPullRequests) {
                                Text("切到 PR", color = githubColors.accent)
                            }
                        }
                    }
                }
            }
            repoDetailState.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = githubColors.danger
                )
            }
        }
    }
    ProjectGitHubRemoteRepositorySection(
        state = remoteRepoState,
        isLoading = isRemoteRepoLoading,
        isActionRunning = isActionRunning,
        tokenConfigured = tokenConfigured,
        refDraft = remoteRepoRefDraft,
        onRefDraftChange = onRemoteRepoRefDraftChange,
        onRefresh = onRefreshRemoteRepository,
        onApplyRef = onApplyRemoteRef,
        onOpenRepo = onOpenRepoPage,
        onOpenParent = onOpenRemoteParent,
        onOpenRoot = onOpenRemoteRoot,
        onOpenPath = composeProjectGitHubRemoteDirectoryPathOpener(onOpenRemoteEntry),
        onOpenEntry = onOpenRemoteEntry,
        onOpenEntryPage = onOpenRemoteEntryPage
    )
}

@Composable
private fun ProjectGitHubStandaloneTopActions(
    selectedRepo: ProjectGitHubAccountRepoUi,
    activeSection: ProjectGitHubStandaloneSection,
    onBackToRepoList: () -> Unit,
    onChangeSection: (ProjectGitHubStandaloneSection) -> Unit
) {
    val buttonRows = listOf(
        listOf("返回" to null, ProjectGitHubStandaloneSection.REPOSITORIES.label to ProjectGitHubStandaloneSection.REPOSITORIES),
        listOf(ProjectGitHubStandaloneSection.WORKFLOWS.label to ProjectGitHubStandaloneSection.WORKFLOWS, ProjectGitHubStandaloneSection.RELEASES.label to ProjectGitHubStandaloneSection.RELEASES),
        listOf(ProjectGitHubStandaloneSection.ISSUES.label to ProjectGitHubStandaloneSection.ISSUES, ProjectGitHubStandaloneSection.PULL_REQUESTS.label to ProjectGitHubStandaloneSection.PULL_REQUESTS),
        listOf(ProjectGitHubStandaloneSection.README.label to ProjectGitHubStandaloneSection.README)
    )
    buttonRows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            row.forEach { (label, section) ->
                if (section == null) {
                    Button(
                        onClick = onBackToRepoList,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("返回")
                    }
                } else {
                    OutlinedButton(
                        onClick = { onChangeSection(section) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (section == activeSection) "${selectedRepo.name} · $label" else label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (row.size == 1) {
                Row(modifier = Modifier.weight(1f)) {}
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectGitHubStandaloneRepoRow(
    repo: ProjectGitHubAccountRepoUi,
    isTaskRepo: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val githubColors = rememberGitHubColors()
    GitHubCard(
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(repo.fullName, style = MaterialTheme.typography.titleMedium, color = githubColors.accent)
            Text(
                text = repo.description.ifBlank { "暂无仓库简介" },
                style = MaterialTheme.typography.bodySmall,
                color = githubColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                GitHubLabel(repo.visibilityLabel, githubColors.mutedText)
                Text(
                    text = "更新于 ${repo.updatedAt.ifBlank { "未知" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = githubColors.mutedText
                )
                if (isTaskRepo) {
                    GitHubLabel("可直接编辑", githubColors.primary)
                }
            }
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneReleaseSection(
    releases: List<ProjectGitHubReleaseUi>,
    isLoading: Boolean,
    isActionRunning: Boolean,
    onRefresh: () -> Unit,
    onCreateRelease: () -> Unit,
    onEditRelease: (ProjectGitHubReleaseUi) -> Unit,
    onToggleReleaseMode: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onTogglePrerelease: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onDeleteRelease: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleasePage: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleaseAssets: (ProjectGitHubReleaseUi) -> Unit
) {
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = rememberReasonixSurfaceColor().copy(alpha = 0.58f)
    ) {
        ProjectGitHubReleaseSection(
            releases = releases,
            isLoading = isLoading,
            isActionRunning = isActionRunning,
            onCreateRelease = onCreateRelease,
            onRefresh = onRefresh,
            onEditRelease = onEditRelease,
            onToggleReleaseMode = onToggleReleaseMode,
            onTogglePrerelease = onTogglePrerelease,
            onDeleteRelease = onDeleteRelease,
            onOpenReleasePage = onOpenReleasePage,
            onOpenAssets = onOpenReleaseAssets
        )
    }
}

@Composable
private fun ProjectGitHubStandaloneLoadingCard(message: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
