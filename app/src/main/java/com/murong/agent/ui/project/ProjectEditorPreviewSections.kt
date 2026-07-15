package com.murong.agent.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.murong.agent.ui.MurongReadOnlyCodeBlock
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor
import java.util.Locale

@Composable
internal fun EmptyEditorState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (
                (!actionLabel.isNullOrBlank() && onAction != null) ||
                (!secondaryActionLabel.isNullOrBlank() && onSecondaryAction != null)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!actionLabel.isNullOrBlank() && onAction != null) {
                        OutlinedButton(onClick = onAction) {
                            Text(actionLabel)
                        }
                    }
                    if (!secondaryActionLabel.isNullOrBlank() && onSecondaryAction != null) {
                        OutlinedButton(onClick = onSecondaryAction) {
                            Text(secondaryActionLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectSearchResultRow(
    hit: ProjectSearchHitUi,
    query: String,
    repoLabel: String? = null,
    onOpen: () -> Unit
) {
    val mutedTextColor = rememberMurongMutedTextColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = highlightSearchKeyword(hit.relativePath, query),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = buildString {
                append(projectSearchTypeLabel(hit.fileType))
                hit.lineNumber?.let { append(" · 第 $it 行") }
                repoLabel?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = highlightSearchKeyword(hit.preview.ifBlank { "文件名匹配" }, query),
            style = MaterialTheme.typography.bodySmall,
            color = mutedTextColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ProjectFoldableCodePreview(
    content: String,
    language: String,
    query: String?,
    foldRegions: List<ProjectFoldRegion>,
    collapsedStartLines: Set<Int>,
    onToggleFold: (Int) -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val lines = remember(content) { content.lines() }
    val lineNumberWidth = lines.size.toString().length.coerceAtLeast(2)
    val regionsByStart = remember(foldRegions) { foldRegions.associateBy { it.startLine } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor.copy(alpha = 0.58f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            var lineNumber = 1
            while (lineNumber <= lines.size) {
                val region = regionsByStart[lineNumber]
                if (region != null && collapsedStartLines.contains(region.startLine)) {
                    FoldedPreviewRow(
                        region = region,
                        lineNumberWidth = lineNumberWidth,
                        language = language,
                        query = query,
                        onToggle = { onToggleFold(region.startLine) }
                    )
                    lineNumber = region.endLine + 1
                } else {
                    PreviewCodeLine(
                        lineNumber = lineNumber,
                        lineText = lines[lineNumber - 1],
                        lineNumberWidth = lineNumberWidth,
                        language = language,
                        query = query,
                        onClick = region?.let { { onToggleFold(it.startLine) } }
                    )
                    lineNumber++
                }
            }
        }
    }
}

@Composable
private fun PreviewCodeLine(
    lineNumber: Int,
    lineText: String,
    lineNumberWidth: Int,
    language: String,
    query: String?,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { base ->
                if (onClick != null) {
                    base.clickable(onClick = onClick)
                } else {
                    base
                }
            }
            .padding(vertical = 1.dp)
    ) {
        Text(
            text = lineNumber.toString().padStart(lineNumberWidth, ' ') + " |",
            modifier = Modifier.width((lineNumberWidth * 9 + 18).dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Color(0xFF6A9955)
        )
        MurongReadOnlyCodeBlock(
            code = lineText.ifEmpty { " " },
            language = language,
            searchQuery = query.orEmpty(),
            backgroundColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun FoldedPreviewRow(
    region: ProjectFoldRegion,
    lineNumberWidth: Int,
    language: String,
    query: String?,
    onToggle: () -> Unit
) {
    val mutedTextColor = rememberMurongMutedTextColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 1.dp)
    ) {
        PreviewCodeLine(
            lineNumber = region.startLine,
            lineText = region.headerLine,
            lineNumberWidth = lineNumberWidth,
            language = language,
            query = query,
            onClick = null
        )
        Text(
            text = " ".repeat(lineNumberWidth + 3) + "... 已折叠 ${region.endLine - region.startLine} 行，点击展开",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = mutedTextColor
        )
    }
}

private fun projectSearchTypeLabel(type: String): String = when (type) {
    "文件名" -> "文件名命中"
    "内容" -> "内容命中"
    else -> type
}

private fun highlightSearchKeyword(text: String, query: String) = buildAnnotatedString {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    val lowerText = text.lowercase(Locale.getDefault())
    val lowerQuery = normalizedQuery.lowercase(Locale.getDefault())
    var startIndex = 0
    while (startIndex < text.length) {
        val matchIndex = lowerText.indexOf(lowerQuery, startIndex)
        if (matchIndex < 0) {
            append(text.substring(startIndex))
            break
        }
        append(text.substring(startIndex, matchIndex))
        pushStyle(
            SpanStyle(
                background = Color(0x55FFD54F),
                color = Color(0xFF111111),
                fontWeight = FontWeight.SemiBold
            )
        )
        append(text.substring(matchIndex, matchIndex + normalizedQuery.length))
        pop()
        startIndex = matchIndex + normalizedQuery.length
    }
}
