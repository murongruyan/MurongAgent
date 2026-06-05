package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import java.util.Locale

internal val PROJECT_GITHUB_WORKFLOW_LOG_SEARCH_PRESETS = listOf(
    "error",
    "exception",
    "failed",
    "timeout",
    "cancelled"
)

private fun normalizeProjectGitHubConsoleToken(value: String): String {
    return value
        .lowercase(Locale.getDefault())
        .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "")
}

internal fun projectGitHubLogMatchesJob(
    entry: ProjectGitHubWorkflowLogEntryUi,
    jobName: String
): Boolean {
    val normalizedJob = normalizeProjectGitHubConsoleToken(jobName)
    if (normalizedJob.isBlank()) return false
    val candidates = listOf(entry.displayName, entry.entryName)
    return candidates.any { candidate ->
        val normalizedCandidate = normalizeProjectGitHubConsoleToken(candidate)
        normalizedCandidate.contains(normalizedJob) || normalizedJob.contains(normalizedCandidate)
    }
}

internal fun projectGitHubLogMentionsStep(
    entry: ProjectGitHubWorkflowLogEntryUi,
    stepName: String
): Boolean {
    val normalizedStep = normalizeProjectGitHubConsoleToken(stepName)
    if (normalizedStep.isBlank()) return false
    val candidates = listOf(entry.displayName, entry.entryName, entry.preview)
    return candidates.any { candidate ->
        normalizeProjectGitHubConsoleToken(candidate).contains(normalizedStep)
    }
}

internal fun buildProjectGitHubAutoExpandedLogEntries(
    detail: ProjectGitHubWorkflowRunDetailUi
): Set<String> {
    if (detail.status.equals("completed", ignoreCase = true)) return emptySet()
    val activeJob = findProjectGitHubActiveJob(detail.jobs) ?: return emptySet()
    val activeStep = findProjectGitHubActiveStep(activeJob.steps)
    val stepMatches = if (activeStep == null) {
        emptySet()
    } else {
        detail.logEntries
            .filter { entry ->
                projectGitHubLogMatchesJob(entry, activeJob.name) &&
                    projectGitHubLogMentionsStep(entry, activeStep.name)
            }
            .map { it.entryName }
            .toSet()
    }
    if (stepMatches.isNotEmpty()) return stepMatches
    return detail.logEntries
        .filter { entry -> projectGitHubLogMatchesJob(entry, activeJob.name) }
        .map { it.entryName }
        .toSet()
}

internal fun buildProjectGitHubAutoExpandHint(
    detail: ProjectGitHubWorkflowRunDetailUi
): String? {
    if (detail.status.equals("completed", ignoreCase = true)) {
        return "当前运行已结束，日志默认全部折叠。"
    }
    val activeJob = findProjectGitHubActiveJob(detail.jobs) ?: return null
    val activeStep = findProjectGitHubActiveStep(activeJob.steps)
    return if (activeStep != null) {
        "运行中自动展开: ${activeJob.name} / ${activeStep.name}"
    } else {
        "运行中自动展开: ${activeJob.name}"
    }
}

internal fun buildProjectGitHubWorkflowLogScopeLabel(
    selectedJobName: String?,
    selectedStepName: String?,
    showOnlyIssueLogs: Boolean,
    showOnlySelectedStepLogs: Boolean
): String {
    val labels = buildList {
        selectedJobName?.takeIf { it.isNotBlank() }?.let { add("Job $it") }
        selectedStepName?.takeIf { it.isNotBlank() }?.let { add("步骤 $it") }
        if (showOnlyIssueLogs) add("仅异常日志")
        if (showOnlySelectedStepLogs) add("仅当前步骤")
    }
    return if (labels.isEmpty()) {
        "当前范围: 全部日志"
    } else {
        "当前范围: ${labels.joinToString(" / ")}"
    }
}

internal fun buildProjectGitHubWorkflowVisibleLogSummary(
    visibleCount: Int,
    totalCount: Int,
    matchedCount: Int = 0,
    searchTermCount: Int = 0,
    expandedCount: Int = 0
): String {
    return buildString {
        append("当前显示 $visibleCount / $totalCount 个日志文件")
        if (expandedCount > 0) {
            append(" · 已展开 $expandedCount 个")
        }
        if (searchTermCount > 0) {
            append(" · 搜索词 $searchTermCount 个")
            append(" · 命中 $matchedCount 个")
        }
    }
}

internal fun buildProjectGitHubWorkflowEmptyLogMessage(
    searchActive: Boolean,
    showOnlyMatchedLogs: Boolean,
    selectedJobName: String?,
    selectedStepName: String?,
    showOnlyIssueLogs: Boolean
): String {
    return when {
        searchActive && showOnlyMatchedLogs -> {
            "当前筛选范围内没有搜索命中的日志文件，可清空搜索、关闭“只看搜索命中”，或放宽 Job / 步骤范围。"
        }
        searchActive -> {
            "当前搜索没有命中，可修改关键字、切回全部日志，或重新定位 Job / 步骤。"
        }
        !selectedStepName.isNullOrBlank() -> {
            "当前步骤范围内没有可预览日志，可清除步骤聚焦，回退到 Job 或全部日志。"
        }
        !selectedJobName.isNullOrBlank() -> {
            "当前 Job 范围内没有可预览日志，可清除 Job 聚焦后查看全部日志。"
        }
        showOnlyIssueLogs -> {
            "当前没有异常相关日志，可关闭“只看异常日志”查看全部内容。"
        }
        else -> {
            "当前范围内没有可预览日志，可重新刷新详情或切换日志范围。"
        }
    }
}

private fun findProjectGitHubActiveJob(
    jobs: List<ProjectGitHubWorkflowJobUi>
): ProjectGitHubWorkflowJobUi? {
    return jobs
        .filterNot { it.status.equals("completed", ignoreCase = true) }
        .minByOrNull { projectGitHubWorkflowStatusRank(it.status) }
        ?: jobs.minByOrNull { projectGitHubWorkflowStatusRank(it.status) }
}

private fun findProjectGitHubActiveStep(
    steps: List<ProjectGitHubWorkflowStepUi>
): ProjectGitHubWorkflowStepUi? {
    return steps
        .filterNot { it.status.equals("completed", ignoreCase = true) }
        .minByOrNull { projectGitHubWorkflowStatusRank(it.status) }
}

private fun projectGitHubWorkflowStatusRank(status: String): Int {
    return when (status.trim().lowercase(Locale.getDefault())) {
        "in_progress", "running" -> 0
        "queued", "pending", "waiting", "requested" -> 1
        "completed" -> 3
        else -> 2
    }
}

private fun projectGitHubConsoleContainsToken(value: String, query: String): Boolean {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank()) return false
    if (value.contains(trimmedQuery, ignoreCase = true)) return true
    val normalizedQuery = normalizeProjectGitHubConsoleToken(trimmedQuery)
    if (normalizedQuery.isBlank()) return false
    return normalizeProjectGitHubConsoleToken(value).contains(normalizedQuery)
}

internal fun buildProjectGitHubWorkflowLogSearchTerms(
    query: String,
    selectedPresets: Set<String>
): List<String> {
    return normalizeProjectGitHubWorkflowLogSearchTerms(
        buildList {
            addAll(parseProjectGitHubWorkflowLogSearchQuery(query))
            selectedPresets.forEach(::add)
        }
    )
}

private fun parseProjectGitHubWorkflowLogSearchQuery(
    query: String
): List<String> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return emptyList()
    val terms = mutableListOf<String>()
    val current = StringBuilder()
    var quoteChar: Char? = null
    fun flushCurrent() {
        val token = current.toString().trim()
        if (token.isNotBlank()) {
            terms += token
        }
        current.clear()
    }
    trimmed.forEach { char ->
        when {
            quoteChar != null && char == quoteChar -> {
                flushCurrent()
                quoteChar = null
            }

            quoteChar != null -> current.append(char)
            char == '"' || char == '\'' -> {
                flushCurrent()
                quoteChar = char
            }

            char.isWhitespace() || char == ',' || char == ';' || char == '/' ||
                char == '|' || char == '，' || char == '；' || char == '、' || char == '｜' -> {
                flushCurrent()
            }

            else -> current.append(char)
        }
    }
    flushCurrent()
    return if (terms.isNotEmpty()) terms else listOf(trimmed)
}

private fun normalizeProjectGitHubWorkflowLogSearchTerms(
    queries: List<String>
): List<String> {
    val normalized = mutableListOf<String>()
    queries.forEach { query ->
        val trimmed = query.trim()
        if (trimmed.isNotBlank() && normalized.none { it.equals(trimmed, ignoreCase = true) }) {
            normalized += trimmed
        }
    }
    return normalized
}

private fun matchesProjectGitHubWorkflowSearchTerms(
    text: String,
    queries: List<String>,
    requireAllTerms: Boolean
): Boolean {
    if (queries.isEmpty()) return false
    return if (requireAllTerms) {
        queries.all { query -> projectGitHubConsoleContainsToken(text, query) }
    } else {
        queries.any { query -> projectGitHubConsoleContainsToken(text, query) }
    }
}

private fun buildProjectGitHubWorkflowHighlightRanges(
    line: String,
    queries: List<String>
): List<IntRange> {
    if (line.isEmpty() || queries.isEmpty()) return emptyList()
    val lowerLine = line.lowercase(Locale.getDefault())
    val candidates = queries
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.getDefault()) }
        .sortedByDescending { it.length }
        .flatMap { query ->
            val lowerQuery = query.lowercase(Locale.getDefault())
            buildList {
                var startIndex = 0
                while (startIndex < line.length) {
                    val matchIndex = lowerLine.indexOf(lowerQuery, startIndex)
                    if (matchIndex < 0) break
                    add(matchIndex until (matchIndex + query.length))
                    startIndex = matchIndex + query.length
                }
            }
        }
        .sortedWith(compareBy<IntRange> { it.first }.thenByDescending { it.last - it.first })

    val merged = mutableListOf<IntRange>()
    candidates.forEach { candidate ->
        val overlapsExisting = merged.any { existing ->
            candidate.first <= existing.last && existing.first <= candidate.last
        }
        if (!overlapsExisting) {
            merged += candidate
        }
    }
    return merged
}

internal fun buildProjectGitHubWorkflowLogSearchHit(
    entry: ProjectGitHubWorkflowLogEntryUi,
    queries: List<String>,
    requireAllTerms: Boolean
): ProjectGitHubWorkflowLogSearchHitUi {
    val normalizedQueries = normalizeProjectGitHubWorkflowLogSearchTerms(queries)
    if (normalizedQueries.isEmpty()) {
        return ProjectGitHubWorkflowLogSearchHitUi(
            hasMatch = false,
            matchedLineCount = 0,
            snippet = "",
            matchedLineIndices = emptyList()
        )
    }
    val previewLines = entry.preview.lines()
    val matchedLineIndices = previewLines
        .mapIndexedNotNull { index, line ->
            index.takeIf {
                matchesProjectGitHubWorkflowSearchTerms(
                    text = line,
                    queries = normalizedQueries,
                    requireAllTerms = requireAllTerms
                )
            }
        }
    val matchedLineLabels = matchedLineIndices
        .take(6)
        .joinToString("\n") { index ->
            "L${index + 1}: ${previewLines.getOrElse(index) { "" }}"
        }
    val fileNameMatched = matchesProjectGitHubWorkflowSearchTerms(
        text = entry.displayName,
        queries = normalizedQueries,
        requireAllTerms = requireAllTerms
    ) || matchesProjectGitHubWorkflowSearchTerms(
        text = entry.entryName,
        queries = normalizedQueries,
        requireAllTerms = requireAllTerms
    )
    val snippet = matchedLineLabels
        .ifBlank { if (fileNameMatched) "文件名命中搜索关键字" else "" }
    return ProjectGitHubWorkflowLogSearchHitUi(
        hasMatch = fileNameMatched || matchedLineIndices.isNotEmpty(),
        matchedLineCount = matchedLineIndices.size,
        snippet = snippet,
        matchedLineIndices = matchedLineIndices
    )
}

internal fun highlightProjectGitHubLogText(
    text: String,
    query: String
): AnnotatedString {
    return highlightProjectGitHubLogText(
        text = text,
        queries = listOf(query)
    )
}

internal fun highlightProjectGitHubLogText(
    text: String,
    queries: List<String>
): AnnotatedString = buildAnnotatedString {
    val normalizedQueries = normalizeProjectGitHubWorkflowLogSearchTerms(queries)
    if (normalizedQueries.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    text.lines().forEachIndexed { index, line ->
        val highlightRanges = buildProjectGitHubWorkflowHighlightRanges(
            line = line,
            queries = normalizedQueries
        )
        when {
            highlightRanges.isNotEmpty() -> {
                var startIndex = 0
                highlightRanges.forEach { range ->
                    append(line.substring(startIndex, range.first))
                    pushStyle(
                        SpanStyle(
                            background = Color(0x88FFB300),
                            color = Color(0xFF111111),
                            fontWeight = FontWeight.Bold
                        )
                    )
                    append(line.substring(range.first, range.last + 1))
                    pop()
                    startIndex = range.last + 1
                }
                append(line.substring(startIndex))
            }

            normalizedQueries.any { queryToken ->
                projectGitHubConsoleContainsToken(line, queryToken)
            } -> {
                pushStyle(
                    SpanStyle(
                        background = Color(0x33FFD54F),
                        fontWeight = FontWeight.Medium
                    )
                )
                append(line)
                pop()
            }

            else -> append(line)
        }
        if (index < text.lines().lastIndex) {
            append('\n')
        }
    }
}

@Composable
internal fun ProjectGitHubWorkflowLogPreviewBody(
    preview: String,
    queries: List<String>,
    expanded: Boolean,
    activeLineNumber: Int?,
    modifier: Modifier = Modifier
) {
    val bodyTextStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        lineHeight = 18.sp
    )
    if (!expanded) {
        val highlightedPreviewText = remember(preview, queries) {
            highlightProjectGitHubLogText(
                text = preview,
                queries = queries
            )
        }
        SelectionContainer(modifier = modifier) {
            Text(
                text = highlightedPreviewText,
                style = bodyTextStyle
            )
        }
        return
    }

    val mutedTextColor = rememberReasonixMutedTextColor()
    val lines = remember(preview) { preview.lines() }
    val activeLineIndex = activeLineNumber?.minus(1)
    val lineNumberWidth = remember(lines.size) {
        maxOf(3, lines.size.toString().length)
    }
    val lineBringIntoViewRequesters = remember(preview) {
        List(lines.size) { BringIntoViewRequester() }
    }
    val expandedScrollState = rememberScrollState()
    LaunchedEffect(expanded, activeLineIndex, lines.size) {
        if (expanded && activeLineIndex != null && activeLineIndex in lines.indices) {
            lineBringIntoViewRequesters[activeLineIndex].bringIntoView()
        }
    }
    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(expandedScrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            lines.forEachIndexed { index, line ->
                val annotatedLine = remember(line, queries, index, lineNumberWidth, mutedTextColor) {
                    buildProjectGitHubWorkflowAnnotatedLogLine(
                        lineNumber = index + 1,
                        line = line,
                        queries = queries,
                        lineNumberWidth = lineNumberWidth,
                        lineNumberColor = mutedTextColor
                    )
                }
                val isActiveLine = activeLineIndex == index
                Text(
                    text = annotatedLine,
                    style = bodyTextStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(lineBringIntoViewRequesters[index])
                        .background(
                            if (isActiveLine) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.56f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
    }
}

private fun buildProjectGitHubWorkflowAnnotatedLogLine(
    lineNumber: Int,
    line: String,
    queries: List<String>,
    lineNumberWidth: Int,
    lineNumberColor: Color
): AnnotatedString = buildAnnotatedString {
    pushStyle(
        SpanStyle(
            color = lineNumberColor,
            fontWeight = FontWeight.Medium
        )
    )
    append("L${lineNumber.toString().padStart(lineNumberWidth, ' ')} | ")
    pop()
    append(highlightProjectGitHubLogText(line, queries))
}

internal fun projectGitHubCollapsedLogPreview(
    preview: String,
    maxLines: Int = 18
): String {
    val lines = preview.lines()
    if (lines.size <= maxLines) return preview
    return (lines.take(maxLines) + "... 已折叠 ${lines.size - maxLines} 行 ...").joinToString("\n")
}

internal fun projectGitHubCollapsedLogPreviewAroundMatch(
    preview: String,
    matchedLineIndices: List<Int>,
    activeMatchIndex: Int,
    contextLines: Int = 4,
    maxLines: Int = 18
): String {
    if (matchedLineIndices.isEmpty()) {
        return projectGitHubCollapsedLogPreview(preview, maxLines)
    }
    val lines = preview.lines()
    if (lines.isEmpty()) return preview
    val safeMatchIndex = matchedLineIndices
        .getOrNull(activeMatchIndex.coerceIn(0, matchedLineIndices.lastIndex))
        ?: matchedLineIndices.first()
    var start = (safeMatchIndex - contextLines).coerceAtLeast(0)
    var end = (safeMatchIndex + contextLines).coerceAtMost(lines.lastIndex)
    val visibleBudget = maxLines - 2
    while (end - start + 1 < visibleBudget && (start > 0 || end < lines.lastIndex)) {
        if (start > 0) start--
        if (end - start + 1 >= visibleBudget) break
        if (end < lines.lastIndex) end++
    }
    val segment = mutableListOf<String>()
    if (start > 0) {
        segment += "... 前面省略 $start 行 ..."
    }
    segment += lines.subList(start, end + 1)
    if (end < lines.lastIndex) {
        segment += "... 后面省略 ${lines.lastIndex - end} 行 ..."
    }
    return segment.joinToString("\n")
}
