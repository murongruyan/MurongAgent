package com.murong.agent.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongLargeDialogScaffold
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor

@Composable
internal fun ProjectGitHubArtifactDialog(
    dialog: ProjectGitHubArtifactDialogUi,
    onDismiss: () -> Unit,
    onOpenRunPage: (String?) -> Unit,
    onDownloadRunLogs: (ProjectGitHubArtifactDialogUi) -> Unit,
    onDownloadArtifact: (ProjectGitHubArtifactDialogUi, ProjectGitHubArtifactUi) -> Unit
) {
    MurongLargeDialogScaffold(onDismissRequest = onDismiss) {
        MurongGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("工作流产物", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = dialog.runTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dialog.runHtmlUrl?.takeIf { it.isNotBlank() }?.let {
                        TextButton(onClick = { onOpenRunPage(dialog.runHtmlUrl) }) {
                            Text("运行页")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { onDownloadRunLogs(dialog) }) {
                Text("日志 ZIP")
            }
            if (dialog.artifacts.isEmpty()) {
                Text(
                    text = "这次运行暂时没有可下载的产物。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                dialog.artifacts.forEach { artifact ->
                    ProjectInsetCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = artifact.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "大小 ${artifact.sizeLabel} · 更新 ${artifact.updatedAt}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (artifact.expired) {
                                Text(
                                    text = "该产物已过期，GitHub 不再提供下载。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                OutlinedButton(
                                    onClick = { onDownloadArtifact(dialog, artifact) }
                                ) {
                                    Text("下载")
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
internal fun ProjectGitHubReleaseAssetDialog(
    dialog: ProjectGitHubReleaseAssetDialogUi,
    onDismiss: () -> Unit,
    isActionRunning: Boolean,
    onOpenReleasePage: (String?) -> Unit,
    onDownloadAsset: (ProjectGitHubReleaseAssetDialogUi, ProjectGitHubReleaseAssetUi) -> Unit,
    onDeleteAsset: (ProjectGitHubReleaseAssetDialogUi, ProjectGitHubReleaseAssetUi) -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()

    MurongLargeDialogScaffold(onDismissRequest = onDismiss) {
        MurongGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Release 资产", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${dialog.releaseTitle} · 资产 ${dialog.assets.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dialog.releaseHtmlUrl?.takeIf { it.isNotBlank() }?.let {
                        TextButton(onClick = { onOpenReleasePage(dialog.releaseHtmlUrl) }) {
                            Text("Release 页面")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (dialog.assets.isEmpty()) {
                Text(
                    text = "这个 Release 还没有可下载资产。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            } else {
                dialog.assets.forEach { asset ->
                    ProjectInsetCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.58f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(asset.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "大小 ${asset.sizeLabel} · 更新 ${asset.updatedAt}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                asset.browserDownloadUrl?.takeIf { it.isNotBlank() }?.let {
                                    TextButton(
                                        onClick = { onOpenReleasePage(asset.browserDownloadUrl) }
                                    ) {
                                        Text("网页")
                                    }
                                }
                                OutlinedButton(
                                    onClick = { onDownloadAsset(dialog, asset) }
                                ) {
                                    Text("下载")
                                }
                                OutlinedButton(
                                    onClick = { onDeleteAsset(dialog, asset) },
                                    enabled = !isActionRunning
                                ) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
