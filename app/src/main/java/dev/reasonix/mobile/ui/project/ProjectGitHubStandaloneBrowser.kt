package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.MarkdownText
import dev.reasonix.mobile.ui.rememberReasonixChromeColor
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor

internal enum class ProjectGitHubStandaloneSection(val label: String) {
    REPOSITORIES("项目"),
    WORKFLOWS("工作流"),
    RELEASES("Release"),
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
    onOpenReleasePage: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleaseAssets: (ProjectGitHubReleaseUi) -> Unit
) {
    val chromeColor = rememberReasonixChromeColor()
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProjectSectionCard(
            shape = RoundedCornerShape(14.dp),
            surfaceColorOverride = chromeColor.copy(alpha = 0.38f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GitHub 账号仓库", style = MaterialTheme.typography.titleSmall)
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
                    color = mutedTextColor
                )
                Text(
                    text = if (selectedRepo == null) {
                        "未选择本地项目时，直接显示当前账号下的仓库；点击进入二级页，长按可设为当前任务仓库。"
                    } else {
                        "当前查看 ${selectedRepo.fullName}，未被设为任务仓库的项目只允许浏览，修改前需确认。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRefreshRepoList,
                        enabled = tokenConfigured && !isRepoListLoading
                    ) {
                        Text("刷新仓库")
                    }
                    if (selectedRepo != null) {
                        TextButton(onClick = onBackToRepoList) {
                            Text("返回列表")
                        }
                    }
                }
                if (!tokenConfigured) {
                    Text(
                        text = "请先在设置页填写 GitHub Token，未选择本地项目时才能直接浏览账号仓库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                repoListState.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (selectedRepo == null) {
            if (isRepoListLoading) {
                ProjectGitHubStandaloneLoadingCard("正在读取当前账号下的仓库列表...")
            }
            if (repoListState.repositories.isEmpty() && !isRepoListLoading && tokenConfigured) {
                ProjectSectionCard(
                    shape = RoundedCornerShape(14.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.55f)
                ) {
                    Text(
                        text = "当前账号下还没有读取到仓库，或该 Token 没有仓库读取权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
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

        ProjectSectionCard(
            shape = RoundedCornerShape(14.dp),
            surfaceColorOverride = surfaceColor.copy(alpha = 0.58f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(selectedRepo.fullName, style = MaterialTheme.typography.titleSmall)
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.42f),
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { onEditRepoDescription(selectedRepo) }
                    )
                ) {
                    Text(
                        text = selectedRepo.description.ifBlank { "这个仓库暂时还没有简介。长按这里可编辑。" },
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
                Text(
                    text = "${selectedRepo.visibilityLabel} · ${selectedRepo.stargazerCount} 星标 · ${selectedRepo.forkCount} 复刻",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                Text(
                    text = if (selectedTaskRepo == selectedRepo.repoRef) {
                        "当前任务仓库，可直接编辑源码。"
                    } else {
                        "当前不是任务仓库，可浏览；若要修改该仓库，后续需要用户确认。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedTaskRepo == selectedRepo.repoRef) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        mutedTextColor
                    }
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
                    onOpenRemoteEntryPage = onOpenRemoteEntryPage
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
                    onRefresh = onRefreshRepoDetail,
                    onOpenReleasePage = onOpenReleasePage,
                    onOpenReleaseAssets = onOpenReleaseAssets
                )
            }

            ProjectGitHubStandaloneSection.README -> {
                ProjectGitHubStandaloneReadmeSection(
                    readme = readme,
                    isLoading = isReadmeLoading,
                    errorMessage = readmeErrorMessage,
                    onOpenRepoPage = onOpenRepoPage
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
    onOpenRemoteEntryPage: (ProjectGitHubRemoteEntryUi) -> Unit
) {
    val chromeColor = rememberReasonixChromeColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.28f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("项目概览", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "这里先提供远端仓库树浏览、网页入口和直接编辑链路。当前任务仓库会保留“可直接编辑”标记。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefreshRepoDetail,
                    enabled = tokenConfigured && !isRepoDetailLoading
                ) {
                    Text("刷新详情")
                }
                TextButton(onClick = onOpenRepoPage) {
                    Text("打开网页")
                }
            }
            Text(
                text = "默认分支: ${
                    repoDetailState.defaultBranch
                        .takeIf { !it.isNullOrBlank() }
                        ?: selectedRepo.defaultBranch.ifBlank { "未知" }
                }",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
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
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    ProjectInsetCard(
        shape = RoundedCornerShape(12.dp),
        surfaceColorOverride = surfaceColor.copy(alpha = 0.56f),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(repo.fullName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = repo.description.ifBlank { "暂无仓库简介" },
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(repo.visibilityLabel)
                    append(" · ")
                    append(repo.updatedAt.ifBlank { "更新时间未知" })
                    if (isTaskRepo) {
                        append(" · 可直接编辑")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isTaskRepo) MaterialTheme.colorScheme.primary else mutedTextColor
            )
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneReleaseSection(
    releases: List<ProjectGitHubReleaseUi>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onOpenReleasePage: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleaseAssets: (ProjectGitHubReleaseUi) -> Unit
) {
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = surfaceColor.copy(alpha = 0.58f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Release", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onRefresh, enabled = !isLoading) {
                    Text("刷新")
                }
            }
            if (isLoading) {
                ProjectGitHubStandaloneLoadingCard("正在读取 Release...")
            } else if (releases.isEmpty()) {
                Text(
                    text = "当前仓库还没有 Release。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            } else {
                releases.forEach { release ->
                    ProjectInsetCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.42f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = release.name.ifBlank { release.tagName },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${release.tagName} · 产物 ${release.assets.size} · ${release.publishedAt.ifBlank { "发布时间未知" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onOpenReleasePage(release) }) {
                                    Text("网页")
                                }
                                OutlinedButton(onClick = { onOpenReleaseAssets(release) }) {
                                    Text("产物")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneReadmeSection(
    readme: ProjectGitHubReadmeUi?,
    isLoading: Boolean,
    errorMessage: String?,
    onOpenRepoPage: () -> Unit
) {
    val chromeColor = rememberReasonixChromeColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.28f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("README", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onOpenRepoPage) {
                    Text("仓库页")
                }
            }
            when {
                isLoading -> ProjectGitHubStandaloneLoadingCard("正在读取 README...")
                !errorMessage.isNullOrBlank() -> Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                readme == null -> Text(
                    text = "当前仓库还没有 README。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                else -> {
                    Text(
                        text = readme.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedTextColor
                    )
                    MarkdownText(text = readme.content)
                }
            }
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneLoadingCard(message: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
