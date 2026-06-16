package com.murong.agent.ui.project

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.MarkdownText
import com.murong.agent.ui.MurongOutlinedActionButton
import com.murong.agent.ui.rememberMurongChromeColor
import com.murong.agent.ui.rememberMurongMutedTextColor

private data class ProjectGitHubReadmeSummaryUi(
    val lineCount: Int,
    val sizeLabel: String
)

@Composable
internal fun ProjectGitHubReadmeSection(
    readme: ProjectGitHubReadmeUi?,
    isLoading: Boolean,
    errorMessage: String?,
    compactPreview: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onOpenRepoPage: (() -> Unit)? = null,
    onEditReadme: ((ProjectGitHubReadmeUi) -> Unit)? = null
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val expandedReadme = remember(readme?.path, readme?.content) { mutableStateOf(false) }

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    readme?.let { currentReadme ->
                        onEditReadme?.let { onEdit ->
                            MurongOutlinedActionButton(
                                text = "编辑 README",
                                onClick = { onEdit(currentReadme) }
                            )
                        }
                    }
                    onRefresh?.let { refresh ->
                        MurongOutlinedActionButton(
                            text = "刷新",
                            onClick = refresh,
                            enabled = !isLoading
                        )
                    }
                    onOpenRepoPage?.let { openRepo ->
                        MurongOutlinedActionButton(
                            text = "仓库页",
                            onClick = openRepo
                        )
                    }
                }
            }
            when {
                isLoading -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text("正在读取 README...", style = MaterialTheme.typography.bodySmall)
                    }
                }
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
                    val shouldCollapse = readme.content.length > 4200
                    val readmeSummary = remember(readme.path, readme.content) {
                        buildProjectGitHubReadmeSummary(readme)
                    }
                    val markdownContent = if (shouldCollapse && !expandedReadme.value) {
                        readme.content.take(4200).trimEnd() + "\n\n..."
                    } else {
                        readme.content
                    }
                    Text(
                        text = "按 Markdown 渲染，图片、链接和代码块会一并显示。",
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
                            label = { Text(readme.path) }
                        )
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text("约 ${readmeSummary.sizeLabel}") }
                        )
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text("${readmeSummary.lineCount} 行") }
                        )
                    }
                    Text(
                        text = if (shouldCollapse) {
                            "当前按长文模式展示，默认先显示前 4200 个字符。"
                        } else {
                            "当前文档长度适中，已完整展示。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                    if (compactPreview) {
                        Text(
                            text = "返回预览阶段暂时不渲染整段 Markdown，落回仓库页后再完整显示 README。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    } else if (shouldCollapse) {
                        MurongOutlinedActionButton(
                            text = if (expandedReadme.value) "收起 README" else "展开 README",
                            onClick = { expandedReadme.value = !expandedReadme.value }
                        )
                    }
                    ProjectInsetCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = chromeColor.copy(alpha = 0.22f)
                    ) {
                        if (compactPreview) {
                            Text(
                                text = readme.content
                                    .lineSequence()
                                    .filter { it.isNotBlank() }
                                    .take(6)
                                    .joinToString("\n")
                                    .ifBlank { "README 将在返回结束后完整显示。" },
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        } else {
                            MarkdownText(text = markdownContent)
                        }
                    }
                }
            }
        }
    }
}

private fun buildProjectGitHubReadmeSummary(
    readme: ProjectGitHubReadmeUi
): ProjectGitHubReadmeSummaryUi {
    return ProjectGitHubReadmeSummaryUi(
        lineCount = readme.content.lines().size,
        sizeLabel = formatProjectByteSize(readme.content.toByteArray().size.toLong())
    )
}
