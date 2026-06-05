package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.ReasonixGlassSurface
import dev.reasonix.mobile.ui.rememberReasonixChromeColor
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor

@Composable
internal fun ProjectGitHubRemoteRepositorySection(
    state: ProjectGitHubRemoteBrowserState,
    isLoading: Boolean,
    isActionRunning: Boolean,
    tokenConfigured: Boolean,
    refDraft: String,
    onRefDraftChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onApplyRef: () -> Unit,
    onOpenRepo: () -> Unit,
    onOpenParent: () -> Unit,
    onOpenRoot: () -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenEntry: (ProjectGitHubRemoteEntryUi) -> Unit,
    onOpenEntryPage: (ProjectGitHubRemoteEntryUi) -> Unit
) {
    val chromeColor = rememberReasonixChromeColor()
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    val directorySummary = remember(state.entries) {
        buildProjectGitHubRemoteDirectorySummary(state.entries)
    }
    val breadcrumbSegments = remember(state.currentPath) {
        buildProjectGitHubRemotePathSegments(state.currentPath)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReasonixGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(12.dp),
            surfaceColorOverride = chromeColor.copy(alpha = 0.58f)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("远端仓库", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = state.repo?.let { "${it.owner}/${it.repo}" } ?: "当前远端还没有解析到 owner/repo",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Text(
                            text = "引用: ${state.currentRef.ifBlank { "未指定" }} · 路径: ${displayProjectGitHubRepoPath(state.currentPath)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        if (state.entries.isNotEmpty()) {
                            Text(
                                text = buildString {
                                    append("目录 ${directorySummary.directoryCount}")
                                    append(" · 文件 ${directorySummary.fileCount}")
                                    if (directorySummary.otherCount > 0) {
                                        append(" · 其他 ${directorySummary.otherCount}")
                                    }
                                    if (directorySummary.totalFileSize > 0L) {
                                        append(" · 约 ")
                                        append(formatProjectByteSize(directorySummary.totalFileSize))
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !isLoading && !isActionRunning
                    ) {
                        Text("刷新")
                    }
                }
                OutlinedTextField(
                    value = refDraft,
                    onValueChange = onRefDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分支 / Tag / 提交") },
                    placeholder = { Text("默认读取当前分支或远端默认分支") },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onApplyRef,
                        enabled = !isLoading && !isActionRunning
                    ) {
                        Text("切换引用")
                    }
                    OutlinedButton(
                        onClick = onOpenRoot,
                        enabled = !isLoading && !isActionRunning
                    ) {
                        Text("根目录")
                    }
                    if (state.currentPath.isNotBlank()) {
                        OutlinedButton(
                            onClick = onOpenParent,
                            enabled = !isLoading && !isActionRunning
                        ) {
                            Text("上级")
                        }
                    }
                    directorySummary.readmeEntry?.let { readmeEntry ->
                        OutlinedButton(
                            onClick = { onOpenEntry(readmeEntry) },
                            enabled = !isLoading && !isActionRunning
                        ) {
                            Text("README")
                        }
                    }
                    state.repoHtmlUrl?.takeIf { it.isNotBlank() }?.let {
                        TextButton(
                            onClick = onOpenRepo,
                            enabled = !isLoading && !isActionRunning
                        ) {
                            Text("仓库页")
                        }
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = true,
                        onClick = { onOpenRoot() },
                        label = { Text(state.currentRef.ifBlank { "默认引用" }) }
                    )
                    FilterChip(
                        selected = state.currentPath.isBlank(),
                        onClick = { onOpenRoot() },
                        label = { Text(displayProjectGitHubRepoPath(state.currentPath)) }
                    )
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("条目 ${state.entries.size}") }
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.currentPath.isBlank(),
                        onClick = { onOpenRoot() },
                        label = { Text("/") }
                    )
                    breadcrumbSegments.forEach { segment ->
                        FilterChip(
                            selected = segment.path == state.currentPath,
                            onClick = { onOpenPath(segment.path) },
                            label = { Text(segment.label) }
                        )
                    }
                }
                when {
                    isLoading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = "正在读取远端仓库内容...",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                    }
                    state.errorMessage?.isNotBlank() == true -> {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    !tokenConfigured -> {
                        Text(
                            text = "先去设置页填 GitHub Token，远端仓库浏览和直接改远端文件才能生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }
                }
            }
        }
        if (state.entries.isEmpty()) {
            Text(
                text = if (state.errorMessage.isNullOrBlank()) {
                    "当前目录 ${displayProjectGitHubRepoPath(state.currentPath)} 还没有读取到远端文件。"
                } else {
                    "远端目录暂时不可用。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else {
            state.entries.forEach { entry ->
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.58f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(entry.typeLabel)
                                append(" · ")
                                append(entry.path)
                                if (!entry.isDirectory && entry.size > 0) {
                                    append(" · ")
                                    append(formatProjectByteSize(entry.size))
                                }
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
                                label = { Text(entry.typeLabel) }
                            )
                            if (!entry.isDirectory && entry.size > 0) {
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text(formatProjectByteSize(entry.size)) }
                                )
                            }
                            if (entry.isDirectory) {
                                FilterChip(
                                    selected = false,
                                    onClick = { onOpenPath(entry.path) },
                                    label = { Text("进入目录") }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onOpenEntry(entry) },
                                enabled = !isActionRunning
                            ) {
                                Text(if (entry.isDirectory) "进入" else "打开")
                            }
                            entry.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                TextButton(
                                    onClick = { onOpenEntryPage(entry) },
                                    enabled = !isActionRunning
                                ) {
                                    Text("网页")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ProjectGitHubRemoteDirectorySummaryUi(
    val directoryCount: Int,
    val fileCount: Int,
    val otherCount: Int,
    val totalFileSize: Long,
    val readmeEntry: ProjectGitHubRemoteEntryUi?
)

private data class ProjectGitHubRemotePathSegmentUi(
    val label: String,
    val path: String
)

private fun buildProjectGitHubRemoteDirectorySummary(
    entries: List<ProjectGitHubRemoteEntryUi>
): ProjectGitHubRemoteDirectorySummaryUi {
    return ProjectGitHubRemoteDirectorySummaryUi(
        directoryCount = entries.count { it.isDirectory },
        fileCount = entries.count { it.type.equals("file", ignoreCase = true) },
        otherCount = entries.count {
            !it.isDirectory && !it.type.equals("file", ignoreCase = true)
        },
        totalFileSize = entries
            .filterNot { it.isDirectory }
            .sumOf { it.size.coerceAtLeast(0L) },
        readmeEntry = entries.firstOrNull { entry ->
            entry.type.equals("file", ignoreCase = true) &&
                entry.name.equals("README.md", ignoreCase = true)
        }
    )
}

private fun buildProjectGitHubRemotePathSegments(
    currentPath: String
): List<ProjectGitHubRemotePathSegmentUi> {
    val normalizedPath = normalizeProjectGitHubRepoPath(currentPath)
    if (normalizedPath.isBlank()) return emptyList()
    val segments = mutableListOf<ProjectGitHubRemotePathSegmentUi>()
    var current = ""
    normalizedPath.split('/').filter { it.isNotBlank() }.forEach { segment ->
        current = if (current.isBlank()) segment else "$current/$segment"
        segments += ProjectGitHubRemotePathSegmentUi(
            label = segment,
            path = current
        )
    }
    return segments
}

internal fun composeProjectGitHubRemoteDirectoryPathOpener(
    onOpenEntry: (ProjectGitHubRemoteEntryUi) -> Unit
): (String) -> Unit = { path ->
    onOpenEntry(
        ProjectGitHubRemoteEntryUi(
            name = path.substringAfterLast('/', "").ifBlank { "/" },
            path = path,
            type = "dir",
            sha = null,
            size = 0L,
            htmlUrl = null
        )
    )
}
