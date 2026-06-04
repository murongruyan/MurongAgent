package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.ReasonixGlassSurface
import dev.reasonix.mobile.ui.rememberReasonixChromeColor
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor

@Composable
internal fun ProjectGitFileGroup(
    title: String,
    items: List<ProjectGitFileChangeUi>,
    actionLabel: String,
    emptyText: String,
    onAction: (ProjectGitFileChangeUi) -> Unit,
    onOpenDiff: (ProjectGitFileChangeUi) -> Unit
) {
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
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
                    shape = RoundedCornerShape(12.dp),
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
    val chromeColor = rememberReasonixChromeColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    ReasonixGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
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
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    val transportHint = remember(remoteUrl, tokenConfigured) {
        summarizeProjectGitRemoteTransport(remoteUrl, tokenConfigured)
    }
    ReasonixGlassSurface(
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
internal fun ProjectGitHistorySection(
    commits: List<ProjectGitCommitUi>,
    onOpenCommit: (ProjectGitCommitUi) -> Unit
) {
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
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
                    shape = RoundedCornerShape(12.dp),
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
