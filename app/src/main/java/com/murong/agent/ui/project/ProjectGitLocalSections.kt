package com.murong.agent.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongProjectInsetCardShape
import com.murong.agent.ui.MurongProjectSectionCardShape
import com.murong.agent.ui.rememberMurongChromeColor
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor

@Composable
internal fun ProjectGitFileGroup(
    title: String,
    items: List<ProjectGitFileChangeUi>,
    actionLabel: String,
    emptyText: String,
    onAction: (ProjectGitFileChangeUi) -> Unit,
    onOpenDiff: (ProjectGitFileChangeUi) -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (items.isEmpty()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else {
            items.forEach { change ->
                ProjectInsetCard(
                    shape = MurongProjectInsetCardShape,
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.58f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(change.displayPath, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            change.statusLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onOpenDiff(change) }) {
                                Text("差异")
                            }
                            OutlinedButton(onClick = { onAction(change) }) {
                                Text(actionLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectGitBranchSummaryCard(
    currentBranch: String?,
    branchCount: Int,
    onManageBranches: () -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    MurongGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MurongProjectInsetCardShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.62f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("分支", style = MaterialTheme.typography.titleSmall)
                Text(
                    "当前: ${currentBranch ?: "未知"} · 本地分支: $branchCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            OutlinedButton(onClick = onManageBranches) {
                Text("管理")
            }
        }
    }
}

@Composable
internal fun ProjectGitRemoteSummaryCard(
    remoteUrl: String?,
    upstreamBranch: String?,
    aheadCount: Int,
    behindCount: Int,
    remoteBranchCount: Int,
    tokenConfigured: Boolean
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val transportHint = remember(remoteUrl, tokenConfigured) {
        summarizeProjectGitRemoteTransport(remoteUrl, tokenConfigured)
    }
    MurongGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        surfaceColorOverride = surfaceColor.copy(alpha = 0.62f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("远端", style = MaterialTheme.typography.titleSmall)
            Text(
                remoteUrl ?: "未配置 origin 远端",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Text(
                "上游: ${upstreamBranch ?: "未设置"} · ahead $aheadCount / behind $behindCount · 远端分支 $remoteBranchCount",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            transportHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
        }
    }
}

@Composable
internal fun ProjectGitLocalOverviewCard(
    repoLabel: String,
    repoRoot: String?,
    currentBranch: String?,
    branchCount: Int,
    remoteUrl: String?,
    upstreamBranch: String?,
    aheadCount: Int,
    behindCount: Int,
    conflictedCount: Int,
    stagedCount: Int,
    modifiedCount: Int,
    untrackedCount: Int,
    tokenConfigured: Boolean,
    lastCommitSummary: String?,
    onManageBranches: () -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val transportHint = remember(remoteUrl, tokenConfigured) {
        summarizeProjectGitRemoteTransport(remoteUrl, tokenConfigured)
    }
    val totalChanges = conflictedCount + stagedCount + modifiedCount + untrackedCount

    ProjectSectionCard(
        shape = MurongProjectSectionCardShape,
        surfaceColorOverride = chromeColor.copy(alpha = 0.36f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("仓库总览", style = MaterialTheme.typography.titleSmall)
                    Text(repoLabel, style = MaterialTheme.typography.bodyMedium)
                    repoRoot?.takeIf { it.isNotBlank() }?.let { root ->
                        Text(
                            text = root,
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                OutlinedButton(onClick = onManageBranches) {
                    Text("管理分支")
                }
            }
            Text(
                text = buildString {
                    append("当前分支 ")
                    append(currentBranch ?: "未知")
                    append(" · 本地分支 ")
                    append(branchCount)
                    append(" · 变更 ")
                    append(totalChanges)
                },
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text("ahead $aheadCount") }
                )
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text("behind $behindCount") }
                )
                if (conflictedCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("冲突 $conflictedCount") }
                    )
                }
                if (stagedCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("已暂存 $stagedCount") }
                    )
                }
                if (modifiedCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("已修改 $modifiedCount") }
                    )
                }
                if (untrackedCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("未跟踪 $untrackedCount") }
                    )
                }
            }
            Text(
                text = buildString {
                    append("上游: ")
                    append(upstreamBranch?.takeIf { it.isNotBlank() } ?: "未设置")
                    append(" · 远端分支 ")
                    append(if (remoteUrl.isNullOrBlank()) "未绑定 origin" else "已绑定")
                },
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Text(
                text = remoteUrl ?: "未配置 origin 远端",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            transportHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            lastCommitSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = "最近提交: $summary",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun ProjectGitLocalPrioritySection(
    hasRemote: Boolean,
    conflictedFiles: List<ProjectGitFileChangeUi>,
    stagedFiles: List<ProjectGitFileChangeUi>,
    modifiedFiles: List<ProjectGitFileChangeUi>,
    untrackedFiles: List<ProjectGitFileChangeUi>,
    aheadCount: Int,
    behindCount: Int,
    canStageAll: Boolean,
    canCommit: Boolean,
    canPull: Boolean,
    canPush: Boolean,
    lastCommitSummary: String?,
    isBusy: Boolean,
    onOpenConflict: ((ProjectGitFileChangeUi) -> Unit)?,
    onStageAll: (() -> Unit)?,
    onOpenCommitDialog: (() -> Unit)?,
    onPull: (() -> Unit)?,
    onPush: (() -> Unit)?
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val focusItems = remember(
        hasRemote,
        conflictedFiles,
        stagedFiles,
        modifiedFiles,
        untrackedFiles,
        aheadCount,
        behindCount,
        canStageAll,
        canCommit,
        canPull,
        canPush,
        lastCommitSummary,
        onOpenConflict,
        onStageAll,
        onOpenCommitDialog,
        onPull,
        onPush
    ) {
        buildList {
            if (conflictedFiles.isNotEmpty()) {
                add(
                    ProjectGitPriorityItemUi(
                        title = "先处理冲突",
                        detail = "当前有 ${conflictedFiles.size} 个冲突文件，优先确认合并结果后再继续提交或同步。",
                        highlighted = true,
                        actionLabel = "查看冲突",
                        action = onOpenConflict?.let { handler ->
                            { conflictedFiles.firstOrNull()?.let(handler) }
                        }
                    )
                )
            }
            val unstagedCount = modifiedFiles.size + untrackedFiles.size
            if (unstagedCount > 0) {
                add(
                    ProjectGitPriorityItemUi(
                        title = "整理工作区",
                        detail = "还有 $unstagedCount 个文件未整理，其中已修改 ${modifiedFiles.size}、未跟踪 ${untrackedFiles.size}。",
                        highlighted = false,
                        actionLabel = if (canStageAll) "全部暂存" else null,
                        action = if (canStageAll) onStageAll else null
                    )
                )
            }
            if (stagedFiles.isNotEmpty()) {
                add(
                    ProjectGitPriorityItemUi(
                        title = "准备提交",
                        detail = buildString {
                            append("已有 ${stagedFiles.size} 个文件进入暂存区")
                            lastCommitSummary?.takeIf { it.isNotBlank() }?.let {
                                append("，最近提交为「")
                                append(it)
                                append("」")
                            }
                            append("。")
                        },
                        highlighted = false,
                        actionLabel = if (canCommit) "提交" else null,
                        action = if (canCommit) onOpenCommitDialog else null
                    )
                )
            }
            if (!hasRemote) {
                add(
                    ProjectGitPriorityItemUi(
                        title = "还没绑定远端",
                        detail = "当前仓库只有本地 Git 状态，后续推送、拉取和 GitHub 工作台能力都还不可用。",
                        highlighted = false,
                        actionLabel = null,
                        action = null
                    )
                )
            } else {
                if (behindCount > 0) {
                    add(
                        ProjectGitPriorityItemUi(
                            title = "需要同步远端",
                            detail = "当前分支落后远端 $behindCount 个提交，建议先拉取再继续开发。",
                            highlighted = false,
                            actionLabel = if (canPull) "全部同步" else null,
                            action = if (canPull) onPull else null
                        )
                    )
                }
                if (aheadCount > 0) {
                    add(
                        ProjectGitPriorityItemUi(
                            title = "还有提交未推送",
                            detail = "当前分支领先远端 $aheadCount 个提交，确认无误后可以推送到 origin。",
                            highlighted = false,
                            actionLabel = if (canPush) "推送" else null,
                            action = if (canPush) onPush else null
                        )
                    )
                }
            }
        }
    }

    ProjectSectionCard(
        shape = MurongProjectSectionCardShape,
        surfaceColorOverride = chromeColor.copy(alpha = 0.28f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("当前重点", style = MaterialTheme.typography.titleSmall)
            if (focusItems.isEmpty()) {
                Text(
                    text = "工作区目前比较干净，没有需要优先处理的本地 Git 异常。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            } else {
                focusItems.forEach { item ->
                    ProjectInsetCard(
                        shape = MurongProjectInsetCardShape,
                        surfaceColorOverride = if (item.highlighted) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.38f)
                        } else {
                            chromeColor.copy(alpha = 0.20f)
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (item.highlighted) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    text = item.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            }
                            if (!item.actionLabel.isNullOrBlank() && item.action != null) {
                                OutlinedButton(
                                    onClick = item.action,
                                    enabled = !isBusy
                                ) {
                                    Text(item.actionLabel)
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
internal fun ProjectGitWorkingTreeSection(
    conflictedFiles: List<ProjectGitFileChangeUi>,
    stagedFiles: List<ProjectGitFileChangeUi>,
    modifiedFiles: List<ProjectGitFileChangeUi>,
    untrackedFiles: List<ProjectGitFileChangeUi>,
    isBusy: Boolean,
    onOpenDiff: (ProjectGitFileChangeUi) -> Unit,
    onStagePath: (ProjectGitFileChangeUi) -> Unit,
    onUnstagePath: (ProjectGitFileChangeUi) -> Unit,
    onStageAll: (() -> Unit)?,
    onSyncAll: (() -> Unit)?
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val totalChanges = conflictedFiles.size + stagedFiles.size + modifiedFiles.size + untrackedFiles.size

    ProjectSectionCard(
        shape = MurongProjectSectionCardShape,
        surfaceColorOverride = chromeColor.copy(alpha = 0.32f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("工作区变更", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (totalChanges == 0) {
                            "当前工作区没有待处理文件。"
                        } else {
                            "共 $totalChanges 个文件处于变更状态，按冲突、暂存、修改和未跟踪统一整理。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onStageAll?.invoke() },
                        enabled = onStageAll != null && !isBusy
                    ) {
                        Text("全部暂存")
                    }
                    OutlinedButton(
                        onClick = { onSyncAll?.invoke() },
                        enabled = onSyncAll != null && !isBusy
                    ) {
                        Text("全部同步")
                    }
                }
            }
            if (totalChanges == 0) {
                Text(
                    text = "可以直接查看历史记录，或者继续在当前分支开发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            } else {
                if (conflictedFiles.isNotEmpty()) {
                    ProjectGitWorkingTreeGroup(
                        title = "冲突文件",
                        summary = "这些文件需要先确认合并结果。",
                        items = conflictedFiles,
                        badgeLabel = "存在冲突",
                        actionLabel = "查看差异",
                        actionEnabled = !isBusy,
                        onAction = onOpenDiff,
                        onOpenDiff = onOpenDiff,
                        highlighted = true
                    )
                }
                if (stagedFiles.isNotEmpty()) {
                    ProjectGitWorkingTreeGroup(
                        title = "已暂存",
                        summary = "这些文件已准备进入下一次提交。",
                        items = stagedFiles,
                        badgeLabel = "已暂存",
                        actionLabel = "取消暂存",
                        actionEnabled = !isBusy,
                        onAction = onUnstagePath,
                        onOpenDiff = onOpenDiff,
                        highlighted = false
                    )
                }
                if (modifiedFiles.isNotEmpty()) {
                    ProjectGitWorkingTreeGroup(
                        title = "已修改",
                        summary = "这些文件已有本地改动但还没进入暂存区。",
                        items = modifiedFiles,
                        badgeLabel = "已修改",
                        actionLabel = "暂存",
                        actionEnabled = !isBusy,
                        onAction = onStagePath,
                        onOpenDiff = onOpenDiff,
                        highlighted = false
                    )
                }
                if (untrackedFiles.isNotEmpty()) {
                    ProjectGitWorkingTreeGroup(
                        title = "未跟踪",
                        summary = "这些文件尚未被 Git 追踪。",
                        items = untrackedFiles,
                        badgeLabel = "未跟踪",
                        actionLabel = "暂存",
                        actionEnabled = !isBusy,
                        onAction = onStagePath,
                        onOpenDiff = onOpenDiff,
                        highlighted = false
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectGitWorkingTreeGroup(
    title: String,
    summary: String,
    items: List<ProjectGitFileChangeUi>,
    badgeLabel: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: (ProjectGitFileChangeUi) -> Unit,
    onOpenDiff: (ProjectGitFileChangeUi) -> Unit,
    highlighted: Boolean
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()

    ProjectInsetCard(
        shape = MurongProjectInsetCardShape,
        surfaceColorOverride = if (highlighted) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.30f)
        } else {
            chromeColor.copy(alpha = 0.20f)
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "$title (${items.size})",
                style = MaterialTheme.typography.bodyMedium,
                color = if (highlighted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            items.forEach { change ->
                ProjectInsetCard(
                    shape = MurongProjectInsetCardShape,
                    surfaceColorOverride = chromeColor.copy(alpha = 0.18f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = change.displayPath,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = mutedTextColor
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onOpenDiff(change) }) {
                                Text("差异")
                            }
                            OutlinedButton(
                                onClick = { onAction(change) },
                                enabled = actionEnabled
                            ) {
                                Text(actionLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ProjectGitPriorityItemUi(
    val title: String,
    val detail: String,
    val highlighted: Boolean,
    val actionLabel: String?,
    val action: (() -> Unit)?
)

@Composable
internal fun ProjectGitLocalGitHubBridgeCard(
    hasRemote: Boolean,
    repoLabel: String?,
    repoSummary: String?,
    helperMessage: String,
    errorMessage: String?,
    taskRepositoryLabel: String?,
    taskRepositoryMessage: String?,
    taskRepositoryHighlighted: Boolean,
    quickLinks: List<ProjectGitQuickLinkUi>,
    isBusy: Boolean,
    onOpenWorkbench: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    onOpenRepoPage: (() -> Unit)?
) {
    val chromeColor = rememberMurongChromeColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()

    ProjectSectionCard(
        shape = MurongProjectSectionCardShape,
        surfaceColorOverride = chromeColor.copy(alpha = 0.30f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GitHub 工作台", style = MaterialTheme.typography.titleSmall)
            if (!repoLabel.isNullOrBlank()) {
                ProjectInsetCard(
                    shape = MurongProjectInsetCardShape,
                    surfaceColorOverride = chromeColor.copy(alpha = 0.18f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(repoLabel, style = MaterialTheme.typography.bodyMedium)
                        repoSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                    }
                }
            }
            Text(
                text = helperMessage,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            taskRepositoryLabel?.let { label ->
                ProjectInsetCard(
                    shape = MurongProjectInsetCardShape,
                    surfaceColorOverride = if (taskRepositoryHighlighted) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
                    } else {
                        surfaceColor.copy(alpha = 0.52f)
                    }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "当前任务仓库: $label",
                            style = MaterialTheme.typography.labelMedium
                        )
                        taskRepositoryMessage?.takeIf { it.isNotBlank() }?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (taskRepositoryHighlighted) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    mutedTextColor
                                }
                            )
                        }
                    }
                }
            }
            if (quickLinks.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickLinks.forEach { link ->
                        FilterChip(
                            selected = true,
                            onClick = link.onClick,
                            label = { Text(link.label) }
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpenWorkbench != null) {
                    Button(onClick = onOpenWorkbench) {
                        Text(if (hasRemote) "进入工作台" else "打开工作台入口")
                    }
                }
                if (onRefresh != null) {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !isBusy
                    ) {
                        Text("刷新 GitHub")
                    }
                }
                if (onOpenRepoPage != null) {
                    TextButton(
                        onClick = onOpenRepoPage,
                        enabled = !isBusy
                    ) {
                        Text("仓库页")
                    }
                }
            }
        }
    }
}

internal data class ProjectGitQuickLinkUi(
    val label: String,
    val onClick: () -> Unit
)

@Composable
internal fun ProjectGitHistorySection(
    commits: List<ProjectGitCommitUi>,
    onOpenCommit: (ProjectGitCommitUi) -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    ProjectSectionCard(
        shape = MurongProjectSectionCardShape,
        surfaceColorOverride = chromeColor.copy(alpha = 0.24f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最近提交", style = MaterialTheme.typography.titleSmall)
            if (commits.isEmpty()) {
                Text(
                    "当前仓库还没有可显示的提交记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            } else {
                commits.forEach { commit ->
                    ProjectInsetCard(
                        shape = MurongProjectInsetCardShape,
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.58f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(commit.subject, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${commit.shortHash} · ${commit.relativeTime}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                            commit.author.takeIf { it.isNotBlank() }?.let { author ->
                                Text(
                                    author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            }
                            TextButton(onClick = { onOpenCommit(commit) }) {
                                Text("查看详情")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectGitOperationHistorySection(
    records: List<ProjectGitOperationRecordUi>
) {
    val chromeColor = rememberMurongChromeColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val summary = remember(records) {
        buildProjectGitOperationSummary(records)
    }
    ProjectSectionCard(
        shape = MurongProjectSectionCardShape,
        surfaceColorOverride = chromeColor.copy(alpha = 0.24f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最近操作", style = MaterialTheme.typography.titleSmall)
            if (records.isEmpty()) {
                Text(
                    "当前还没有可显示的 Git 操作记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            } else {
                Text(
                    text = buildString {
                        append("共 ${summary.totalCount} 条")
                        append(" · 成功 ${summary.successCount}")
                        append(" · 失败 ${summary.failureCount}")
                        append(" · 涉及仓库 ${summary.repoCount}")
                        summary.latestTimeLabel?.let { append(" · 最近 $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                records.forEach { record ->
                    ProjectInsetCard(
                        shape = MurongProjectInsetCardShape,
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.58f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(record.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${record.repoLabel} · ${record.timeLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text(record.categoryLabel) }
                                )
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text(if (record.isSuccess) "成功" else "失败") }
                                )
                            }
                            Text(
                                text = record.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (record.isSuccess) {
                                    mutedTextColor
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
