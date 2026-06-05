package dev.reasonix.mobile.ui.project

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.ReasonixSecondaryPageFrame
import dev.reasonix.mobile.ui.rememberReasonixChromeColor
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor
import java.util.Locale

@Composable
internal fun ProjectGitHubWorkspaceDownloadCenterPage(
    downloads: List<ProjectGitHubDownloadRecordUi>,
    onOpenSystemDownloads: () -> Unit,
    onOpenDownloadSource: (ProjectGitHubDownloadRecordUi) -> Unit,
    onDeleteRecord: (String) -> Unit,
    onClearHistory: () -> Unit,
    backProgress: Float
) {
    val chromeColor = rememberReasonixChromeColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    var searchQuery by remember(downloads) { mutableStateOf("") }
    var selectedType by remember(downloads) { mutableStateOf(ProjectGitHubDownloadFilterType.ALL) }
    var selectedRepo by remember(downloads) { mutableStateOf<String?>(null) }
    var selectedStatus by remember(downloads) { mutableStateOf(ProjectGitHubDownloadStatusFilter.ALL) }
    var selectedSort by remember(downloads) { mutableStateOf(ProjectGitHubDownloadSortOrder.NEWEST_FIRST) }
    val repoOptions = remember(downloads) {
        downloads.mapNotNull { buildProjectGitHubDownloadMetadata(it).repoLabel }.distinct()
    }
    val filteredDownloads = remember(
        downloads,
        searchQuery,
        selectedType,
        selectedRepo,
        selectedStatus,
        selectedSort
    ) {
        filterProjectGitHubDownloads(
            downloads = downloads,
            query = searchQuery,
            filterType = selectedType,
            repoLabel = selectedRepo,
            statusFilter = selectedStatus,
            sortOrder = selectedSort
        )
    }
    val latestDownload = downloads.firstOrNull()
    ReasonixSecondaryPageFrame(
        title = "下载中心",
        subtitle = "工作区级统一查看日志、产物和 Release 下载记录。"
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                translationX = 180f * backProgress
                alpha = 1f - (backProgress * 0.08f)
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProjectSectionCard(
                shape = RoundedCornerShape(14.dp),
                surfaceColorOverride = chromeColor.copy(alpha = 0.38f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("工作区下载中心", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (latestDownload == null) {
                            "当前还没有下载记录。后续从工作流日志、产物和 Release 触发的下载都会统一收在这里。"
                        } else {
                            "共 ${downloads.size} 条记录，最近一条是 ${latestDownload.typeLabel} · ${latestDownload.fileName}。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenSystemDownloads) {
                            Text("打开系统下载")
                        }
                        if (downloads.isNotEmpty()) {
                            TextButton(
                                onClick = onClearHistory,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("清空历史")
                            }
                        }
                    }
                }
            }
            ProjectSectionCard(
                shape = RoundedCornerShape(14.dp),
                surfaceColorOverride = chromeColor.copy(alpha = 0.30f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索下载记录") },
                        placeholder = { Text("按仓库、标题、文件名筛选") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProjectGitHubDownloadFilterType.entries.forEach { type ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = { selectedType = type },
                                label = { Text(type.label) }
                            )
                        }
                    }
                    if (repoOptions.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedRepo == null,
                                onClick = { selectedRepo = null },
                                label = { Text("全部仓库") }
                            )
                            repoOptions.forEach { repoLabel ->
                                FilterChip(
                                    selected = selectedRepo == repoLabel,
                                    onClick = { selectedRepo = repoLabel },
                                    label = { Text(repoLabel) }
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProjectGitHubDownloadStatusFilter.entries.forEach { statusFilter ->
                            FilterChip(
                                selected = selectedStatus == statusFilter,
                                onClick = { selectedStatus = statusFilter },
                                label = { Text(statusFilter.label) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProjectGitHubDownloadSortOrder.entries.forEach { sortOrder ->
                            FilterChip(
                                selected = selectedSort == sortOrder,
                                onClick = { selectedSort = sortOrder },
                                label = { Text(sortOrder.label) }
                            )
                        }
                    }
                    Text(
                        text = "筛选后 ${filteredDownloads.size} / ${downloads.size} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (filteredDownloads.size < downloads.size) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            mutedTextColor
                        }
                    )
                }
            }
            ProjectGitHubDownloadHistorySection(
                downloads = filteredDownloads,
                onOpenSystemDownloads = onOpenSystemDownloads,
                onOpenSource = onOpenDownloadSource,
                onDeleteRecord = onDeleteRecord
            )
        }
    }
}

@Composable
internal fun ProjectGitHubDownloadCenterSummaryCard(
    downloads: List<ProjectGitHubDownloadRecordUi>,
    onOpenDownloadCenter: () -> Unit,
    onOpenSystemDownloads: () -> Unit
) {
    val chromeColor = rememberReasonixChromeColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    val latestDownload = downloads.firstOrNull()
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.30f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("下载中心", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (latestDownload == null) {
                    "详细下载记录已迁入仓库工作台概览，这里只保留工作区级入口。"
                } else {
                    "最近 ${downloads.size} 条下载记录已统一归到仓库工作台，方便和工作流、Release、远端操作放在一起回看。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            latestDownload?.let { item ->
                Text(
                    text = "${item.typeLabel} · ${item.fileName} · ${item.createdAtLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenDownloadCenter) {
                    Text("打开下载中心")
                }
                OutlinedButton(onClick = onOpenSystemDownloads) {
                    Text("系统下载")
                }
            }
        }
    }
}

@Composable
internal fun ProjectGitHubDownloadHistorySection(
    downloads: List<ProjectGitHubDownloadRecordUi>,
    onOpenSystemDownloads: () -> Unit,
    onOpenSource: (ProjectGitHubDownloadRecordUi) -> Unit,
    onDeleteRecord: (String) -> Unit
) {
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("下载记录", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onOpenSystemDownloads) {
                Text("系统下载")
            }
        }
        if (downloads.isEmpty()) {
            Text(
                text = "这里会显示本页触发的工作流日志、产物和 Release 资产下载记录。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else {
            downloads.forEach { item ->
                val metadata = remember(item) { buildProjectGitHubDownloadMetadata(item) }
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.58f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${item.typeLabel} · ${item.title}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        metadata.repoLabel?.let { repoLabel ->
                            Text(
                                text = repoLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "状态 ${metadata.statusLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (metadata.hasSourcePage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                mutedTextColor
                            }
                        )
                        Text(
                            text = "${item.fileName} · ${item.createdAtLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Text(
                            text = "下载 ID ${item.downloadId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onOpenSystemDownloads) {
                                    Text("查看下载")
                                }
                                item.sourceUrl?.takeIf { it.isNotBlank() }?.let {
                                    TextButton(onClick = { onOpenSource(item) }) {
                                        Text("来源")
                                    }
                                }
                            }
                            TextButton(onClick = { onDeleteRecord(item.id) }) {
                                Text(
                                    text = "删除",
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class ProjectGitHubDownloadFilterType(val label: String) {
    ALL("全部"),
    WORKFLOW_LOG("日志"),
    ARTIFACT("产物"),
    RELEASE_ASSET("Release");

    fun matches(item: ProjectGitHubDownloadRecordUi): Boolean {
        return when (this) {
            ALL -> true
            WORKFLOW_LOG -> item.typeLabel == "工作流日志"
            ARTIFACT -> item.typeLabel == "工作流产物"
            RELEASE_ASSET -> item.typeLabel == "Release 资产"
        }
    }
}

private enum class ProjectGitHubDownloadSortOrder(val label: String) {
    NEWEST_FIRST("最新优先"),
    OLDEST_FIRST("最早优先")
}

private enum class ProjectGitHubDownloadStatusFilter(val label: String) {
    ALL("全部状态"),
    SOURCE_AVAILABLE("可回跳"),
    LOCAL_ONLY("仅记录");

    fun matches(metadata: ProjectGitHubDownloadMetadataUi): Boolean {
        return when (this) {
            ALL -> true
            SOURCE_AVAILABLE -> metadata.hasSourcePage
            LOCAL_ONLY -> !metadata.hasSourcePage
        }
    }
}

private data class ProjectGitHubDownloadMetadataUi(
    val repoLabel: String?,
    val hasSourcePage: Boolean,
    val statusLabel: String
)

private fun inferProjectGitHubDownloadRepoLabelFromUrl(sourceUrl: String): String? {
    val pathSegments = Uri.parse(sourceUrl).path
        .orEmpty()
        .trim('/')
        .split('/')
        .filter { it.isNotBlank() }
    return when {
        pathSegments.firstOrNull() == "repos" && pathSegments.size >= 3 -> {
            "${pathSegments[1]}/${pathSegments[2]}"
        }
        pathSegments.size >= 2 -> "${pathSegments[0]}/${pathSegments[1]}"
        else -> null
    }
}

private fun buildProjectGitHubDownloadMetadata(
    item: ProjectGitHubDownloadRecordUi
): ProjectGitHubDownloadMetadataUi {
    val source = item.sourceUrl?.trim().orEmpty()
    val repoLabel = item.repoLabel
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: buildString {
            item.repoOwner?.trim()?.takeIf { it.isNotBlank() }?.let { owner ->
                item.repoName?.trim()?.takeIf { it.isNotBlank() }?.let { repoName ->
                    append(owner)
                    append("/")
                    append(repoName)
                }
            }
        }.takeIf { it.isNotBlank() }
        ?: source.takeIf { it.isNotBlank() }?.let(::inferProjectGitHubDownloadRepoLabelFromUrl)
    val hasSourcePage = source.isNotBlank()
    return ProjectGitHubDownloadMetadataUi(
        repoLabel = repoLabel,
        hasSourcePage = hasSourcePage,
        statusLabel = if (hasSourcePage) "可回跳" else "仅记录"
    )
}

private fun filterProjectGitHubDownloads(
    downloads: List<ProjectGitHubDownloadRecordUi>,
    query: String,
    filterType: ProjectGitHubDownloadFilterType,
    repoLabel: String?,
    statusFilter: ProjectGitHubDownloadStatusFilter,
    sortOrder: ProjectGitHubDownloadSortOrder
): List<ProjectGitHubDownloadRecordUi> {
    val normalizedQuery = query.trim().lowercase(Locale.getDefault())
    val filtered = downloads.filter { item ->
        val metadata = buildProjectGitHubDownloadMetadata(item)
        filterType.matches(item) &&
            statusFilter.matches(metadata) &&
            (repoLabel == null || metadata.repoLabel == repoLabel) &&
            (
                normalizedQuery.isBlank() ||
                    buildString {
                        append(item.typeLabel)
                        append('\n')
                        append(item.title)
                        append('\n')
                        append(item.fileName)
                        append('\n')
                        append(metadata.repoLabel.orEmpty())
                        append('\n')
                        append(metadata.statusLabel)
                        append('\n')
                        append(item.sourceUrl.orEmpty())
                    }.lowercase(Locale.getDefault()).contains(normalizedQuery)
                )
    }
    return when (sortOrder) {
        ProjectGitHubDownloadSortOrder.NEWEST_FIRST ->
            filtered.sortedByDescending(ProjectGitHubDownloadRecordUi::createdAtMillis)
        ProjectGitHubDownloadSortOrder.OLDEST_FIRST ->
            filtered.sortedBy(ProjectGitHubDownloadRecordUi::createdAtMillis)
    }
}
