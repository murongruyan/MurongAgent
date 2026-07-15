package com.murong.agent.ui.project

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongLargeDialogScaffold
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor

@Composable
internal fun ProjectGitHubCommentThreadSection(
    title: String,
    comments: List<ProjectGitHubCommentUi>,
    isLoading: Boolean,
    isActionRunning: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSubmit: () -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val summaryText = remember(comments, isLoading) {
        when {
            isLoading -> "正在同步评论数据"
            comments.isEmpty() -> "当前还没有评论"
            else -> "共 ${comments.size} 条评论 · 最近更新 ${comments.firstOrNull()?.timeLabel ?: "时间未知"}"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onRefresh, enabled = !isLoading && !isActionRunning) {
                Text("刷新")
            }
        }
        Text(
            text = summaryText,
            style = MaterialTheme.typography.bodySmall,
            color = mutedTextColor
        )
        if (isLoading) {
            Text(
                text = "正在读取评论……",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else if (comments.isEmpty()) {
            Text(
                text = "当前还没有评论。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else {
            comments.forEach { comment ->
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.56f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "${comment.authorLabel} · ${comment.timeLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        SelectionContainer {
                            Text(
                                text = comment.body.ifBlank { "(空评论)" },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("回复") },
            placeholder = { Text("直接在这里补充讨论内容") },
            minLines = 4,
            maxLines = 8
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSubmit,
                enabled = draft.isNotBlank() && !isActionRunning
            ) {
                Text("发送回复")
            }
        }
    }
}

@Composable
internal fun ProjectGitHubPullRequestReviewSection(
    reviews: List<ProjectGitHubPullRequestReviewUi>,
    isLoading: Boolean,
    isActionRunning: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSubmitReview: (String) -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val reviewSummary = remember(reviews, isLoading) {
        ProjectGitHubReviewSummaryUi(
            totalCount = reviews.size,
            approvedCount = reviews.count { it.state.equals("APPROVED", ignoreCase = true) },
            requestedChangesCount = reviews.count {
                it.state.equals("CHANGES_REQUESTED", ignoreCase = true)
            },
            commentedCount = reviews.count { it.state.equals("COMMENTED", ignoreCase = true) },
            latestTimeLabel = reviews.firstOrNull()?.timeLabel ?: "时间未知",
            isLoading = isLoading
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("评审", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onRefresh, enabled = !isLoading && !isActionRunning) {
                Text("刷新")
            }
        }
        Text(
            text = when {
                reviewSummary.isLoading -> "正在同步评审状态"
                reviewSummary.totalCount == 0 -> "当前还没有评审记录"
                else -> buildString {
                    append("共 ${reviewSummary.totalCount} 条评审")
                    append(" · 批准 ${reviewSummary.approvedCount}")
                    append(" · 请求修改 ${reviewSummary.requestedChangesCount}")
                    append(" · 评论 ${reviewSummary.commentedCount}")
                    append(" · 最近 ${reviewSummary.latestTimeLabel}")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = mutedTextColor
        )
        if (isLoading) {
            Text(
                text = "正在读取评审……",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else if (reviews.isEmpty()) {
            Text(
                text = "当前还没有评审记录。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else {
            reviews.forEach { review ->
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.56f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "${review.authorLabel} · ${review.stateLabel} · ${review.timeLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        review.body.takeIf { it.isNotBlank() }?.let { body ->
                            SelectionContainer {
                                Text(
                                    text = body,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } ?: Text(
                            text = "这个评审没有附带正文说明。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("评审意见") },
            placeholder = { Text("可填写通过原因、修改意见或补充说明") },
            minLines = 4,
            maxLines = 8
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSubmitReview("APPROVE") },
                enabled = !isActionRunning
            ) {
                Text("批准")
            }
            OutlinedButton(
                onClick = { onSubmitReview("REQUEST_CHANGES") },
                enabled = !isActionRunning
            ) {
                Text("请求修改")
            }
            TextButton(
                onClick = { onSubmitReview("COMMENT") },
                enabled = !isActionRunning
            ) {
                Text("评审评论")
            }
        }
    }
}

@Composable
internal fun ProjectGitHubPullRequestReviewCommentSection(
    files: List<ProjectGitHubPullRequestFileUi>,
    comments: List<ProjectGitHubPullRequestReviewCommentUi>,
    isFilesLoading: Boolean,
    isCommentsLoading: Boolean,
    isActionRunning: Boolean,
    bodyDraft: String,
    onBodyDraftChange: (String) -> Unit,
    pathDraft: String,
    onPathDraftChange: (String) -> Unit,
    lineDraft: String,
    onLineDraftChange: (String) -> Unit,
    onPickPath: (String) -> Unit,
    onPickLine: (String) -> Unit,
    onRefresh: () -> Unit,
    onSubmit: () -> Unit,
    onReplyToComment: (Long, String, () -> Unit) -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val selectedFile = files.firstOrNull { it.path == pathDraft }
    val selectedLine = lineDraft.toIntOrNull()
    val suggestedLines = remember(selectedFile?.path, selectedFile?.patch) {
        extractProjectGitHubReviewLineSuggestions(selectedFile?.patch).take(18)
    }
    val commentsByPathAndLine = remember(comments) {
        comments
            .filter { it.path.isNotBlank() && it.line != null }
            .groupBy { it.path to (it.line ?: -1) }
    }
    var expandedFilePath by remember(files, pathDraft) {
        mutableStateOf(pathDraft.ifBlank { files.firstOrNull()?.path.orEmpty() })
    }
    var expandedPatchLineLimit by remember(expandedFilePath) { mutableStateOf(160) }
    var focusedHunkIndex by remember(expandedFilePath) { mutableStateOf<Int?>(null) }
    var lineCommentDialogTarget by remember {
        mutableStateOf<Pair<String, Int>?>(null)
    }
    var lineCommentReplyTargetId by remember(lineCommentDialogTarget) { mutableStateOf<Long?>(null) }
    var lineCommentReplyDraft by remember(lineCommentDialogTarget) { mutableStateOf("") }
    var localLineCommentReplies by remember {
        mutableStateOf<Map<Pair<String, Int>, List<ProjectGitHubPullRequestReviewCommentUi>>>(emptyMap())
    }
    LaunchedEffect(comments) {
        localLineCommentReplies = emptyMap()
    }
    val reviewCommentSummary = remember(files, comments, pathDraft, lineDraft, isFilesLoading, isCommentsLoading) {
        ProjectGitHubReviewCommentSummaryUi(
            fileCount = files.size,
            commentCount = comments.size,
            selectedPath = pathDraft,
            selectedLine = lineDraft,
            isFilesLoading = isFilesLoading,
            isCommentsLoading = isCommentsLoading
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("代码评审评论", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onRefresh, enabled = !isFilesLoading && !isCommentsLoading && !isActionRunning) {
                Text("刷新")
            }
        }
        Text(
            text = when {
                reviewCommentSummary.isFilesLoading -> "正在同步变更文件"
                reviewCommentSummary.isCommentsLoading -> "正在同步代码评审评论"
                else -> buildString {
                    append("变更文件 ${reviewCommentSummary.fileCount}")
                    append(" · 代码评论 ${reviewCommentSummary.commentCount}")
                    reviewCommentSummary.selectedPath.takeIf { it.isNotBlank() }?.let { path ->
                        append(" · 当前文件 ${path.substringAfterLast('/')}")
                    }
                    reviewCommentSummary.selectedLine.takeIf { it.isNotBlank() }?.let { line ->
                        append(" · L$line")
                    }
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = mutedTextColor
        )
        if (isFilesLoading) {
            Text(
                text = "正在读取变更文件……",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else if (files.isEmpty()) {
            Text(
                text = "当前还没有读取到 PR 变更文件，你也可以手动填写文件路径。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                files.take(12).forEach { file ->
                    FilterChip(
                        selected = pathDraft == file.path,
                        onClick = {
                            onPickPath(file.path)
                            expandedFilePath = file.path
                            expandedPatchLineLimit = 160
                            focusedHunkIndex = null
                        },
                        label = {
                            Text(
                                file.path.substringAfterLast('/'),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = pathDraft,
            onValueChange = onPathDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("文件路径") },
            placeholder = { Text("例如：app/src/main/java/.../ProjectScreen.kt") },
            singleLine = true
        )
        OutlinedTextField(
            value = lineDraft,
            onValueChange = onLineDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("行号（可选）") },
            placeholder = { Text("留空则作为文件级评论") },
            singleLine = true
        )
        if (suggestedLines.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestedLines.forEach { line ->
                    FilterChip(
                        selected = lineDraft == line.toString(),
                        onClick = { onPickLine(line.toString()) },
                        label = { Text("L$line") }
                    )
                }
            }
        }
        OutlinedTextField(
            value = bodyDraft,
            onValueChange = onBodyDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("评论内容") },
            placeholder = { Text("说明具体问题、建议或上下文") },
            minLines = 4,
            maxLines = 8
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSubmit,
                enabled = bodyDraft.isNotBlank() && pathDraft.isNotBlank() && !isActionRunning
            ) {
                Text("发送代码评论")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("变更文件", style = MaterialTheme.typography.labelLarge)
            files.take(24).forEach { file ->
                val isExpanded = expandedFilePath == file.path
                val patchHunks = remember(file.path, file.patch) {
                    parseProjectGitHubPatchHunks(file.patch)
                }
                val visiblePatchHunks = remember(file.path, file.patch, expandedPatchLineLimit) {
                    takeProjectGitHubVisiblePatchHunks(patchHunks, expandedPatchLineLimit)
                }
                val patchTruncated = patchHunks.sumOf { it.lines.size } > visiblePatchHunks.sumOf { it.lines.size }
                val reviewLines = remember(file.path, file.patch) {
                    extractProjectGitHubReviewLineSuggestions(file.patch).take(18)
                }
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.56f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = file.summaryLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    onPickPath(file.path)
                                    expandedFilePath = file.path
                                    expandedPatchLineLimit = 160
                                    focusedHunkIndex = null
                                }
                            ) {
                                Text(if (pathDraft == file.path) "已选中" else "用于评论")
                            }
                            TextButton(
                                onClick = {
                                    if (isExpanded) {
                                        expandedFilePath = ""
                                    } else {
                                        expandedFilePath = file.path
                                        expandedPatchLineLimit = 160
                                        focusedHunkIndex = null
                                    }
                                }
                            ) {
                                Text(if (isExpanded) "收起 diff" else "展开 diff")
                            }
                        }
                        if (isExpanded) {
                            val visibleHunkOptions = visiblePatchHunks
                            val displayedPatchHunks = focusedHunkIndex
                                ?.takeIf { it in visibleHunkOptions.indices }
                                ?.let { listOf(visibleHunkOptions[it]) }
                                ?: visibleHunkOptions
                            val selectedLineComments = remember(commentsByPathAndLine, file.path, selectedLine) {
                                if (selectedLine == null) emptyList()
                                else commentsByPathAndLine[file.path to selectedLine].orEmpty()
                            }
                            if (visibleHunkOptions.size > 1) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = focusedHunkIndex == null,
                                        onClick = { focusedHunkIndex = null },
                                        label = { Text("全部 hunk") }
                                    )
                                    visibleHunkOptions.forEachIndexed { index, hunk ->
                                        FilterChip(
                                            selected = focusedHunkIndex == index,
                                            onClick = { focusedHunkIndex = index },
                                            label = { Text(buildProjectGitHubPatchHunkLabel(index, hunk)) }
                                        )
                                    }
                                }
                            }
                            if (reviewLines.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    reviewLines.forEach { line ->
                                        FilterChip(
                                            selected = pathDraft == file.path && lineDraft == line.toString(),
                                            onClick = {
                                                onPickPath(file.path)
                                                onPickLine(line.toString())
                                            },
                                            label = { Text("L$line") }
                                        )
                                    }
                                }
                            }
                            if (visiblePatchHunks.isEmpty()) {
                                Text(
                                    text = "当前文件没有可直接显示的 diff 片段，可能是二进制文件或 patch 被 GitHub 省略。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 360.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    displayedPatchHunks.forEach { hunk ->
                                        ProjectInsetCard(
                                            shape = RoundedCornerShape(10.dp),
                                            surfaceColorOverride = surfaceColor.copy(alpha = 0.42f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    text = hunk.header,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                    color = mutedTextColor
                                                )
                                                hunk.lines.forEach { diffLine ->
                                                    val lineComments = remember(commentsByPathAndLine, file.path, diffLine.rightLineNumber) {
                                                        diffLine.rightLineNumber?.let { line ->
                                                            commentsByPathAndLine[file.path to line].orEmpty()
                                                        }.orEmpty()
                                                    }
                                                    val isHighlightedLine =
                                                        pathDraft == file.path &&
                                                            selectedLine == diffLine.rightLineNumber
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(
                                                                if (isHighlightedLine) {
                                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                                } else {
                                                                    Color.Transparent
                                                                }
                                                            ),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.Top
                                                    ) {
                                                        TextButton(
                                                            onClick = {
                                                                diffLine.rightLineNumber?.let { line ->
                                                                    onPickPath(file.path)
                                                                    onPickLine(line.toString())
                                                                }
                                                            },
                                                            enabled = diffLine.isCommentable,
                                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                                        ) {
                                                            Text(
                                                                text = diffLine.rightLineNumber?.let { "L$it" } ?: "·",
                                                                style = MaterialTheme.typography.bodySmall
                                                            )
                                                        }
                                                        SelectionContainer {
                                                            Text(
                                                                text = diffLine.displayText,
                                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                                color = when (diffLine.prefix) {
                                                                    "+" -> Color(0xFF2E7D32)
                                                                    "-" -> Color(0xFFC62828)
                                                                    else -> MaterialTheme.colorScheme.onSurface
                                                                },
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                        }
                                                        if (lineComments.isNotEmpty()) {
                                                            AssistChip(
                                                                onClick = {
                                                                    diffLine.rightLineNumber?.let { line ->
                                                                        onPickPath(file.path)
                                                                        onPickLine(line.toString())
                                                                        lineCommentDialogTarget = file.path to line
                                                                    }
                                                                },
                                                                label = { Text("${lineComments.size}评") }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (selectedLineComments.isNotEmpty()) {
                                    ProjectInsetCard(
                                        shape = RoundedCornerShape(10.dp),
                                        surfaceColorOverride = surfaceColor.copy(alpha = 0.42f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = "当前行评论 · ${file.path.substringAfterLast('/')} · L$selectedLine",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = mutedTextColor
                                            )
                                            selectedLineComments.forEach { comment ->
                                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(
                                                        text = "${comment.authorLabel} · ${comment.timeLabel}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = mutedTextColor
                                                    )
                                                    SelectionContainer {
                                                        Text(
                                                            text = comment.body.ifBlank { "(空评论)" },
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (patchTruncated) {
                                TextButton(
                                    onClick = { expandedPatchLineLimit += 160 }
                                ) {
                                    Text("展开更多")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (isCommentsLoading) {
            Text(
                text = "正在读取代码评审评论……",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else if (comments.isEmpty()) {
            Text(
                text = "当前还没有代码评审评论。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else {
            comments.forEach { comment ->
                val canLocateComment = comment.path.isNotBlank() && comment.line != null
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.56f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "${comment.authorLabel} · ${comment.pathLabel} · ${comment.positionLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Text(
                            text = comment.timeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        SelectionContainer {
                            Text(
                                text = comment.body.ifBlank { "(空评论)" },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (canLocateComment) {
                            TextButton(
                                onClick = {
                                    val targetFile = files.firstOrNull { it.path == comment.path }
                                    val targetLine = comment.line
                                    onPickPath(comment.path)
                                    onPickLine(targetLine.toString())
                                    expandedFilePath = comment.path
                                    val targetHunks = parseProjectGitHubPatchHunks(targetFile?.patch)
                                    val targetHunkIndex = findProjectGitHubPatchHunkIndexForLine(targetHunks, targetLine)
                                    focusedHunkIndex = targetHunkIndex
                                    expandedPatchLineLimit = maxOf(
                                        expandedPatchLineLimit,
                                        requiredProjectGitHubPatchLineLimitForHunk(targetHunks, targetHunkIndex)
                                    )
                                }
                            ) {
                                Text("定位到 diff")
                            }
                        }
                    }
                }
            }
        }
    }
    lineCommentDialogTarget?.let { (dialogPath, dialogLine) ->
        val dialogComments = commentsByPathAndLine[dialogPath to dialogLine].orEmpty() +
            localLineCommentReplies[dialogPath to dialogLine].orEmpty()
        val threadedDialogComments = remember(dialogComments) {
            buildProjectGitHubReviewCommentThread(dialogComments)
        }
        val dialogScrollState = rememberScrollState()
        LaunchedEffect(lineCommentDialogTarget, threadedDialogComments.size) {
            if (threadedDialogComments.isNotEmpty()) {
                dialogScrollState.animateScrollTo(dialogScrollState.maxValue)
            }
        }
        MurongLargeDialogScaffold(
            onDismissRequest = {
                lineCommentDialogTarget = null
                lineCommentReplyTargetId = null
                lineCommentReplyDraft = ""
            }
        ) {
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
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${dialogPath.substringAfterLast('/')} · L$dialogLine",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "行评论线程共 ${threadedDialogComments.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }
                    TextButton(onClick = {
                        lineCommentDialogTarget = null
                        lineCommentReplyTargetId = null
                        lineCommentReplyDraft = ""
                    }) {
                        Text("关闭")
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .heightIn(max = 420.dp)
                    .verticalScroll(dialogScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (threadedDialogComments.isEmpty()) {
                    Text(
                        text = "当前行暂时没有可显示的评论。",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                } else {
                    threadedDialogComments.forEach { threadItem ->
                        val comment = threadItem.comment
                        ProjectInsetCard(
                            shape = RoundedCornerShape(10.dp),
                            surfaceColorOverride = surfaceColor.copy(alpha = 0.42f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (threadItem.depth * 14).dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "${comment.authorLabel} · ${comment.timeLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                                SelectionContainer {
                                    Text(
                                        text = comment.body.ifBlank { "(空评论)" },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                threadItem.replyToAuthorLabel?.let { replyTo ->
                                    Text(
                                        text = "回复 $replyTo",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = mutedTextColor
                                    )
                                }
                                TextButton(
                                    onClick = { lineCommentReplyTargetId = comment.id },
                                    enabled = !isActionRunning
                                ) {
                                    Text(if (lineCommentReplyTargetId == comment.id) "正在回复此条" else "回复此条")
                                }
                            }
                        }
                    }
                    lineCommentReplyTargetId?.let { replyTargetId ->
                        val replyTarget = dialogComments.firstOrNull { it.id == replyTargetId }
                        ProjectInsetCard(
                            shape = RoundedCornerShape(10.dp),
                            surfaceColorOverride = surfaceColor.copy(alpha = 0.42f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = buildString {
                                        append("回复当前行评论")
                                        replyTarget?.authorLabel?.takeIf { it.isNotBlank() }?.let {
                                            append(" · ")
                                            append(it)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                                OutlinedTextField(
                                    value = lineCommentReplyDraft,
                                    onValueChange = { lineCommentReplyDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("回复内容") },
                                    placeholder = { Text("补充修改说明、答复或后续处理") },
                                    minLines = 3,
                                    maxLines = 6
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val body = lineCommentReplyDraft.trim()
                                            if (body.isBlank()) return@Button
                                            onReplyToComment(replyTargetId, body) {
                                                localLineCommentReplies =
                                                    localLineCommentReplies.toMutableMap().apply {
                                                        val key = dialogPath to dialogLine
                                                        val existing = get(key).orEmpty()
                                                        put(
                                                            key,
                                                            existing + ProjectGitHubPullRequestReviewCommentUi(
                                                                id = -System.currentTimeMillis(),
                                                                authorLogin = "我",
                                                                body = body,
                                                                path = dialogPath,
                                                                line = dialogLine,
                                                                side = "RIGHT",
                                                                parentCommentId = replyTargetId,
                                                                createdAt = "",
                                                                updatedAt = "刚刚",
                                                                htmlUrl = null
                                                            )
                                                        )
                                                    }
                                                lineCommentReplyDraft = ""
                                                lineCommentReplyTargetId = null
                                            }
                                        },
                                        enabled = lineCommentReplyDraft.isNotBlank() && !isActionRunning
                                    ) {
                                        Text("发送回复")
                                    }
                                    TextButton(
                                        onClick = {
                                            lineCommentReplyDraft = ""
                                            lineCommentReplyTargetId = null
                                        },
                                        enabled = !isActionRunning
                                    ) {
                                        Text("取消")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


private data class ProjectGitHubReviewCommentThreadItemUi(
    val comment: ProjectGitHubPullRequestReviewCommentUi,
    val depth: Int,
    val replyToAuthorLabel: String?
)

private data class ProjectGitHubReviewSummaryUi(
    val totalCount: Int,
    val approvedCount: Int,
    val requestedChangesCount: Int,
    val commentedCount: Int,
    val latestTimeLabel: String,
    val isLoading: Boolean
)

private data class ProjectGitHubReviewCommentSummaryUi(
    val fileCount: Int,
    val commentCount: Int,
    val selectedPath: String,
    val selectedLine: String,
    val isFilesLoading: Boolean,
    val isCommentsLoading: Boolean
)

private fun buildProjectGitHubReviewCommentThread(
    comments: List<ProjectGitHubPullRequestReviewCommentUi>
): List<ProjectGitHubReviewCommentThreadItemUi> {
    if (comments.isEmpty()) return emptyList()
    val byId = comments.associateBy { it.id }
    val childrenByParent = comments
        .mapNotNull { comment -> comment.parentCommentId?.let { it to comment } }
        .groupBy({ it.first }, { it.second })
    val roots = comments.filter { comment ->
        val parentId = comment.parentCommentId
        parentId == null || byId[parentId] == null
    }
    val result = mutableListOf<ProjectGitHubReviewCommentThreadItemUi>()
    fun appendThread(comment: ProjectGitHubPullRequestReviewCommentUi, depth: Int) {
        val parentAuthorLabel = comment.parentCommentId
            ?.let(byId::get)
            ?.authorLabel
        result += ProjectGitHubReviewCommentThreadItemUi(
            comment = comment,
            depth = depth,
            replyToAuthorLabel = parentAuthorLabel
        )
        childrenByParent[comment.id].orEmpty().forEach { child ->
            appendThread(child, depth + 1)
        }
    }
    roots.forEach { root -> appendThread(root, 0) }
    return result
}

private data class ProjectGitHubPatchHunkUi(
    val header: String,
    val lines: List<ProjectGitHubPatchLineUi>
)

private data class ProjectGitHubPatchLineUi(
    val prefix: String,
    val text: String,
    val rightLineNumber: Int?
) {
    val isCommentable: Boolean get() = rightLineNumber != null && prefix != "-"
    val displayText: String get() = prefix + text
}

private fun parseProjectGitHubPatchHunks(patch: String?): List<ProjectGitHubPatchHunkUi> {
    if (patch.isNullOrBlank()) return emptyList()
    val hunkHeader = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*$""")
    val hunks = mutableListOf<ProjectGitHubPatchHunkUi>()
    var currentHeader: String? = null
    var currentLines = mutableListOf<ProjectGitHubPatchLineUi>()
    var nextRightLine: Int? = null
    fun flush() {
        val header = currentHeader ?: return
        hunks += ProjectGitHubPatchHunkUi(header = header, lines = currentLines.toList())
    }
    patch.lineSequence().forEach { rawLine ->
        val headerMatch = hunkHeader.find(rawLine)
        if (headerMatch != null) {
            flush()
            currentHeader = rawLine
            currentLines = mutableListOf()
            nextRightLine = headerMatch.groupValues.getOrNull(2)?.toIntOrNull()
            return@forEach
        }
        val prefix = rawLine.firstOrNull()?.toString().orEmpty()
        val text = rawLine.drop(if (rawLine.isNotEmpty()) 1 else 0)
        val rightLineNumber = when {
            prefix == "+" -> nextRightLine
            prefix == " " -> nextRightLine
            else -> null
        }
        if (currentHeader == null) {
            currentHeader = "@@ patch @@"
        }
        currentLines += ProjectGitHubPatchLineUi(
            prefix = prefix,
            text = text,
            rightLineNumber = rightLineNumber
        )
        when (prefix) {
            "+", " " -> nextRightLine = (nextRightLine ?: 0) + 1
        }
    }
    flush()
    return hunks
}

private fun takeProjectGitHubVisiblePatchHunks(
    hunks: List<ProjectGitHubPatchHunkUi>,
    maxLineCount: Int
): List<ProjectGitHubPatchHunkUi> {
    if (maxLineCount <= 0) return emptyList()
    val visible = mutableListOf<ProjectGitHubPatchHunkUi>()
    var remaining = maxLineCount
    hunks.forEach { hunk ->
        if (remaining <= 0) return@forEach
        if (hunk.lines.size <= remaining) {
            visible += hunk
            remaining -= hunk.lines.size
        } else {
            visible += hunk.copy(lines = hunk.lines.take(remaining))
            remaining = 0
        }
    }
    return visible
}

private fun buildProjectGitHubPatchHunkLabel(index: Int, hunk: ProjectGitHubPatchHunkUi): String {
    val rightStart = Regex("""\+(\d+)""").find(hunk.header)?.groupValues?.getOrNull(1)
    return if (rightStart != null) "H${index + 1} · L$rightStart" else "H${index + 1}"
}

private fun findProjectGitHubPatchHunkIndexForLine(
    hunks: List<ProjectGitHubPatchHunkUi>,
    line: Int
): Int? {
    return hunks.indexOfFirst { hunk ->
        hunk.lines.any { diffLine -> diffLine.rightLineNumber == line }
    }.takeIf { it >= 0 }
}

private fun requiredProjectGitHubPatchLineLimitForHunk(
    hunks: List<ProjectGitHubPatchHunkUi>,
    index: Int?
): Int {
    if (index == null || index !in hunks.indices) return 160
    return hunks.take(index + 1).sumOf { it.lines.size }.coerceAtLeast(160)
}

private fun extractProjectGitHubReviewLineSuggestions(patch: String?): List<Int> {
    if (patch.isNullOrBlank()) return emptyList()
    val hunkHeader = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
    var nextRightLine: Int? = null
    val lines = LinkedHashSet<Int>()
    patch.lineSequence().forEach { rawLine ->
        val match = hunkHeader.find(rawLine)
        if (match != null) {
            nextRightLine = match.groupValues.getOrNull(1)?.toIntOrNull()
            return@forEach
        }
        val current = nextRightLine ?: return@forEach
        when {
            rawLine.startsWith("+") && !rawLine.startsWith("+++") -> {
                lines += current
                nextRightLine = current + 1
            }
            rawLine.startsWith(" ") -> {
                nextRightLine = current + 1
            }
            rawLine.startsWith("-") && !rawLine.startsWith("---") -> Unit
        }
    }
    return lines.toList()
}
