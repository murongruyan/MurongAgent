package com.murong.agent.ui.project

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongLargeDialogScaffold
import com.murong.agent.ui.rememberMurongChromeColor
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor

@Composable
internal fun ProjectGitHubWorkflowRunDetailDialog(
    detail: ProjectGitHubWorkflowRunDetailUi,
    onDismiss: () -> Unit,
    onOpenRunPage: (String?) -> Unit,
    onRefreshDetail: (ProjectGitHubWorkflowRunDetailUi) -> Unit,
    onRerun: (ProjectGitHubWorkflowRunDetailUi) -> Unit,
    onDownloadLogs: (ProjectGitHubWorkflowRunDetailUi) -> Unit,
    onDownloadArtifact: (ProjectGitHubWorkflowRunDetailUi, ProjectGitHubArtifactUi) -> Unit
) {
    val context = LocalContext.current

    MurongLargeDialogScaffold(onDismissRequest = onDismiss) {
        MurongGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("运行详情", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.padding(vertical = 2.dp))
                    Text(
                        text = "${detail.repo.owner}/${detail.repo.repo}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    detail.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                        TextButton(onClick = { onOpenRunPage(detail.htmlUrl) }) {
                            Text("网页")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }

        val surfaceColor = rememberMurongSurfaceColor()
        val chromeColor = rememberMurongChromeColor()
        val mutedTextColor = rememberMurongMutedTextColor()
        val issueJobs = remember(detail.jobs) { detail.jobs.filter { it.hasIssue } }
        var selectedIssueJobName by remember(detail.id) {
            mutableStateOf(issueJobs.firstOrNull()?.name)
        }
        var selectedIssueStepName by remember(detail.id) {
            mutableStateOf<String?>(null)
        }
        var showOnlyIssueLogs by remember(detail.id) {
            mutableStateOf(issueJobs.isNotEmpty())
        }
        var showOnlySelectedStepLogs by remember(detail.id) {
            mutableStateOf(false)
        }
        var logSearchQuery by remember(detail.id) {
            mutableStateOf("")
        }
        var selectedLogSearchPresets by remember(detail.id) {
            mutableStateOf(setOf<String>())
        }
        var logSearchRequireAllTerms by remember(detail.id) {
            mutableStateOf(false)
        }
        var showOnlyMatchedLogs by remember(detail.id) {
            mutableStateOf(false)
        }
        var activeMatchedLogEntryName by remember(detail.id) {
            mutableStateOf<String?>(null)
        }
        val activeMatchedLineIndexByEntry = remember(detail.id) {
            mutableStateMapOf<String, Int>()
        }
        var expandedLogEntries by remember(detail.id) {
            mutableStateOf(setOf<String>())
        }
        val autoExpandedLogEntries = remember(
            detail.status,
            detail.jobs,
            detail.logEntries
        ) {
            buildProjectGitHubAutoExpandedLogEntries(detail)
        }
        val autoExpandHint = remember(
            detail.status,
            detail.jobs,
            detail.logEntries
        ) {
            buildProjectGitHubAutoExpandHint(detail)
        }
        val filteredLogEntries = remember(
            detail.logEntries,
            selectedIssueJobName,
            showOnlyIssueLogs,
            issueJobs
        ) {
            detail.logEntries.filter { entry ->
                val matchesSelected = selectedIssueJobName.isNullOrBlank() ||
                    projectGitHubLogMatchesJob(entry, selectedIssueJobName.orEmpty())
                val matchesIssueOnly = !showOnlyIssueLogs ||
                    issueJobs.any { job -> projectGitHubLogMatchesJob(entry, job.name) }
                matchesSelected && matchesIssueOnly
            }
        }
            val focusedStepMatchedEntries = remember(filteredLogEntries, selectedIssueStepName) {
                filteredLogEntries
                    .filter { entry ->
                        !selectedIssueStepName.isNullOrBlank() &&
                            projectGitHubLogMentionsStep(entry, selectedIssueStepName.orEmpty())
                    }
                    .map { it.entryName }
                    .toSet()
            }
            val scopedLogEntries = remember(
                filteredLogEntries,
                selectedIssueStepName,
                showOnlySelectedStepLogs,
                focusedStepMatchedEntries
            ) {
                val stepFocused = !selectedIssueStepName.isNullOrBlank()
                val hasStepMatches = focusedStepMatchedEntries.isNotEmpty()
                val base = if (stepFocused && showOnlySelectedStepLogs && hasStepMatches) {
                    filteredLogEntries.filter { entry -> focusedStepMatchedEntries.contains(entry.entryName) }
                } else {
                    filteredLogEntries
                }
                if (stepFocused && hasStepMatches) {
                    base.sortedWith(
                        compareByDescending<ProjectGitHubWorkflowLogEntryUi> { entry ->
                            focusedStepMatchedEntries.contains(entry.entryName)
                        }.thenBy { it.displayName }
                    )
                } else {
                    base
                }
            }
            val logSearchTerms = remember(logSearchQuery, selectedLogSearchPresets) {
                buildProjectGitHubWorkflowLogSearchTerms(
                    query = logSearchQuery,
                    selectedPresets = selectedLogSearchPresets
                )
            }
            val searchActive = logSearchTerms.isNotEmpty()
            val logSearchHits = remember(scopedLogEntries, logSearchTerms, logSearchRequireAllTerms) {
                scopedLogEntries.associate { entry ->
                    entry.entryName to buildProjectGitHubWorkflowLogSearchHit(
                        entry = entry,
                        queries = logSearchTerms,
                        requireAllTerms = logSearchRequireAllTerms
                    )
                }
            }
            val visibleLogEntries = remember(
                scopedLogEntries,
                logSearchHits,
                searchActive,
                showOnlyMatchedLogs
            ) {
                val base = if (searchActive && showOnlyMatchedLogs) {
                    scopedLogEntries.filter { entry ->
                        logSearchHits[entry.entryName]?.hasMatch == true
                    }
                } else {
                    scopedLogEntries
                }
                if (searchActive) {
                    base.sortedWith(
                        compareByDescending<ProjectGitHubWorkflowLogEntryUi> { entry ->
                            logSearchHits[entry.entryName]?.hasMatch == true
                        }.thenByDescending { entry ->
                            logSearchHits[entry.entryName]?.matchedLineCount ?: 0
                        }.thenBy { it.displayName }
                    )
                } else {
                    base
                }
            }
            val matchedVisibleLogEntries = remember(
                visibleLogEntries,
                logSearchHits,
                searchActive
            ) {
                if (!searchActive) {
                    emptyList()
                } else {
                    visibleLogEntries.filter { entry ->
                        logSearchHits[entry.entryName]?.hasMatch == true
                    }
                }
            }
            val activeMatchedLogEntryIndex = remember(
                matchedVisibleLogEntries,
                activeMatchedLogEntryName
            ) {
                matchedVisibleLogEntries.indexOfFirst { it.entryName == activeMatchedLogEntryName }
            }
            val visibleExpandedCount = remember(visibleLogEntries, expandedLogEntries) {
                visibleLogEntries.count { entry -> expandedLogEntries.contains(entry.entryName) }
            }
            val logScopeLabel = remember(
                selectedIssueJobName,
                selectedIssueStepName,
                showOnlyIssueLogs,
                showOnlySelectedStepLogs
            ) {
                buildProjectGitHubWorkflowLogScopeLabel(
                    selectedJobName = selectedIssueJobName,
                    selectedStepName = selectedIssueStepName,
                    showOnlyIssueLogs = showOnlyIssueLogs,
                    showOnlySelectedStepLogs = showOnlySelectedStepLogs
                )
            }
            val visibleLogSummaryText = remember(
                visibleLogEntries,
                detail.logEntries,
                matchedVisibleLogEntries,
                logSearchTerms,
                visibleExpandedCount
            ) {
                buildProjectGitHubWorkflowVisibleLogSummary(
                    visibleCount = visibleLogEntries.size,
                    totalCount = detail.logEntries.size,
                    matchedCount = matchedVisibleLogEntries.size,
                    searchTermCount = logSearchTerms.size,
                    expandedCount = visibleExpandedCount
                )
            }
            val logSectionBringIntoViewRequester = remember(detail.id) {
                BringIntoViewRequester()
            }
            var logSectionScrollRequest by remember(detail.id) {
                mutableStateOf(0)
            }
            val logListScrollState = rememberScrollState()
            fun focusJobLogs(jobName: String) {
                selectedIssueJobName = jobName
                selectedIssueStepName = null
                showOnlyIssueLogs = false
                showOnlySelectedStepLogs = false
                expandedLogEntries = expandedLogEntries +
                    detail.logEntries
                        .filter { entry -> projectGitHubLogMatchesJob(entry, jobName) }
                        .map { it.entryName }
                        .toSet()
                logSectionScrollRequest += 1
            }
            fun clearJobLogFocus(resetExpandedLogs: Boolean = false) {
                selectedIssueJobName = null
                selectedIssueStepName = null
                showOnlyIssueLogs = false
                showOnlySelectedStepLogs = false
                if (resetExpandedLogs) {
                    expandedLogEntries = autoExpandedLogEntries
                }
            }
            fun clearStepLogFocus(resetExpandedLogs: Boolean = false) {
                selectedIssueStepName = null
                showOnlySelectedStepLogs = false
                if (resetExpandedLogs) {
                    expandedLogEntries = autoExpandedLogEntries
                }
            }
            fun focusJobStepLogs(jobName: String, stepName: String) {
                selectedIssueJobName = jobName
                selectedIssueStepName = stepName
                showOnlyIssueLogs = false
                showOnlySelectedStepLogs = true
                expandedLogEntries = expandedLogEntries +
                    detail.logEntries
                        .filter { entry ->
                            projectGitHubLogMatchesJob(entry, jobName) &&
                                projectGitHubLogMentionsStep(entry, stepName)
                        }
                        .map { it.entryName }
                        .toSet()
                logSectionScrollRequest += 1
            }
            LaunchedEffect(detail.status, detail.jobs, detail.logEntries) {
                expandedLogEntries = autoExpandedLogEntries
            }
            LaunchedEffect(logSectionScrollRequest) {
                if (logSectionScrollRequest > 0) {
                    logSectionBringIntoViewRequester.bringIntoView()
                }
            }
            LaunchedEffect(logSearchTerms, logSearchRequireAllTerms, matchedVisibleLogEntries) {
                activeMatchedLineIndexByEntry.clear()
                if (!searchActive || matchedVisibleLogEntries.isEmpty()) {
                    activeMatchedLogEntryName = null
                } else {
                    val topMatchedEntryName = matchedVisibleLogEntries.first().entryName
                    activeMatchedLogEntryName = topMatchedEntryName
                    activeMatchedLineIndexByEntry[topMatchedEntryName] = 0
                    expandedLogEntries = expandedLogEntries + topMatchedEntryName
                }
            }
            val visibleJobs = remember(detail.jobs, selectedIssueJobName) {
                val filtered = if (selectedIssueJobName.isNullOrBlank()) {
                    detail.jobs
                } else {
                    detail.jobs.filter { it.name == selectedIssueJobName }
                }
                filtered.sortedWith(
                    compareByDescending<ProjectGitHubWorkflowJobUi> { it.hasIssue }.thenBy { it.name }
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(logListScrollState),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${detail.repo.owner}/${detail.repo.repo} / ${detail.workflowName.ifBlank { detail.title }}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "第 ${detail.runNumber} 次运行",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                    Text(
                        text = "触发方式: ${detail.eventLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                    Text(
                        text = "当前状态: ${detail.statusLabel} · 持续时间: ${detail.durationLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (detail.hasIssue) {
                            MaterialTheme.colorScheme.error
                        } else {
                            mutedTextColor
                        }
                    )
                    Text(
                        text = "分支 ${detail.headBranch.ifBlank { "未知" }} · 开始 ${detail.createdAtLabel} · 更新 ${detail.updatedAtLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                    Text(
                        text = buildString {
                            append("作业列表: ")
                            if (detail.jobs.isEmpty()) {
                                append("GitHub 正在返回作业信息")
                            } else {
                                append(
                                    detail.jobs.take(3).joinToString(" / ") { job ->
                                        "${job.name}(${job.durationLabel})"
                                    }
                                )
                                if (detail.jobs.size > 3) {
                                    append(" 等 ${detail.jobs.size} 个")
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                    Text(
                        text = "日志 ${detail.logEntries.size} · 产物 ${detail.artifacts.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (detail.jobs.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedIssueJobName == null,
                                onClick = { clearJobLogFocus() },
                                label = { Text("全部作业") }
                            )
                            detail.jobs.forEach { job ->
                                FilterChip(
                                    selected = selectedIssueJobName == job.name,
                                    onClick = { focusJobLogs(job.name) },
                                    label = {
                                        Text(
                                            buildString {
                                                append(job.name.take(18))
                                                append(" · ")
                                                append(job.durationLabel)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }
                detail.issueSummaries.takeIf { it.isNotEmpty() }?.let { issues ->
                    ProjectInsetCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = chromeColor.copy(alpha = 0.56f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "日志摘要",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            issues.forEach { issue ->
                                Text(
                                    text = issue,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                if (issueJobs.isNotEmpty()) {
                    ProjectInsetCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = chromeColor.copy(alpha = 0.48f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "异常定位",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = selectedIssueJobName == null,
                                    onClick = {
                                        selectedIssueJobName = null
                                        selectedIssueStepName = null
                                        showOnlySelectedStepLogs = false
                                    },
                                    label = { Text("全部 Job") }
                                )
                                issueJobs.forEach { job ->
                                    FilterChip(
                                        selected = selectedIssueJobName == job.name,
                                        onClick = {
                                            selectedIssueJobName =
                                                if (selectedIssueJobName == job.name) null else job.name
                                            selectedIssueStepName = null
                                            showOnlySelectedStepLogs = false
                                        },
                                        label = { Text(job.name.take(24)) }
                                    )
                                }
                                FilterChip(
                                    selected = showOnlyIssueLogs,
                                    onClick = { showOnlyIssueLogs = !showOnlyIssueLogs },
                                    label = { Text("只看异常日志") }
                                )
                            }
                            issueJobs.forEach { job ->
                                ProjectInsetCard(
                                    shape = RoundedCornerShape(10.dp),
                                    surfaceColorOverride = surfaceColor.copy(alpha = 0.46f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                        text = job.name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    Text(
                                        text = "${job.statusLabel} · ${job.durationLabel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (job.hasIssue) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            mutedTextColor
                                        }
                                    )
                                        if (job.failedSteps.isEmpty()) {
                                            Text(
                                                text = "这个 Job 状态异常，但 GitHub 还没有返回具体失败步骤。",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = mutedTextColor
                                            )
                                        } else {
                                            Row(
                                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                job.failedSteps.take(4).forEach { step ->
                                                    FilterChip(
                                                        selected = selectedIssueJobName == job.name &&
                                                            selectedIssueStepName == step.name,
                                                        onClick = {
                                                            val nextSelectedStep =
                                                                if (
                                                                    selectedIssueJobName == job.name &&
                                                                    selectedIssueStepName == step.name
                                                                ) {
                                                                    null
                                                                } else {
                                                                    step.name
                                                                }
                                                            selectedIssueJobName = job.name
                                                            selectedIssueStepName = nextSelectedStep
                                                            showOnlySelectedStepLogs = nextSelectedStep != null
                                                            if (nextSelectedStep != null) {
                                                                expandedLogEntries =
                                                                    expandedLogEntries +
                                                                        detail.logEntries
                                                                            .filter { entry ->
                                                                                projectGitHubLogMatchesJob(
                                                                                    entry,
                                                                                    job.name
                                                                                ) && projectGitHubLogMentionsStep(
                                                                                    entry,
                                                                                    nextSelectedStep
                                                                                )
                                                                            }
                                                                            .map { it.entryName }
                                                                            .toSet()
                                                            }
                                                        },
                                                        label = {
                                                            Text(
                                                                "${step.name.take(28)} · ${step.statusLabel}"
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                when {
                    detail.logsError?.isNotBlank() == true -> {
                        ProjectInsetCard(
                            shape = RoundedCornerShape(12.dp),
                            surfaceColorOverride = MaterialTheme.colorScheme.errorContainer.copy(
                                alpha = 0.52f
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = detail.logsError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    detail.logEntries.isEmpty() -> {
                        Text(
                            text = "GitHub 还没有返回可预览的日志，通常是运行刚启动、还没产生日志文件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }

                    else -> {
                        Text(
                            text = buildString {
                                append("日志预览")
                                if (showOnlyIssueLogs) {
                                    append(" · 当前仅显示异常相关")
                                }
                                if (!selectedIssueStepName.isNullOrBlank()) {
                                    append(" · 已聚焦步骤 ${selectedIssueStepName.orEmpty()}")
                                }
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (selectedIssueJobName.isNullOrBlank()) {
                                "点击上方 Job 可直接定位对应日志。"
                            } else {
                                "当前聚焦 Job: ${selectedIssueJobName.orEmpty()}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor,
                            modifier = Modifier.bringIntoViewRequester(logSectionBringIntoViewRequester)
                        )
                        Text(
                            text = logScopeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Text(
                            text = visibleLogSummaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (
                            !selectedIssueJobName.isNullOrBlank() ||
                            !selectedIssueStepName.isNullOrBlank() ||
                            showOnlyIssueLogs ||
                            showOnlySelectedStepLogs ||
                            searchActive
                        ) {
                            ProjectInsetCard(
                                shape = RoundedCornerShape(12.dp),
                                surfaceColorOverride = chromeColor.copy(alpha = 0.46f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "当前视图状态",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        selectedIssueJobName?.takeIf { it.isNotBlank() }?.let { jobName ->
                                            FilterChip(
                                                selected = true,
                                                onClick = {},
                                                label = { Text("Job: $jobName") }
                                            )
                                        }
                                        selectedIssueStepName?.takeIf { it.isNotBlank() }?.let { stepName ->
                                            FilterChip(
                                                selected = true,
                                                onClick = {},
                                                label = { Text("步骤: ${stepName.take(22)}") }
                                            )
                                        }
                                        if (showOnlyIssueLogs) {
                                            FilterChip(
                                                selected = true,
                                                onClick = {},
                                                label = { Text("仅异常日志") }
                                            )
                                        }
                                        if (showOnlySelectedStepLogs) {
                                            FilterChip(
                                                selected = true,
                                                onClick = {},
                                                label = { Text("仅当前步骤") }
                                            )
                                        }
                                        if (searchActive) {
                                            FilterChip(
                                                selected = true,
                                                onClick = {},
                                                label = { Text("搜索中 ${logSearchTerms.size} 项") }
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (!selectedIssueStepName.isNullOrBlank()) {
                                            OutlinedButton(
                                                onClick = { clearStepLogFocus(resetExpandedLogs = true) }
                                            ) {
                                                Text("清除步骤聚焦")
                                            }
                                        }
                                        if (!selectedIssueJobName.isNullOrBlank()) {
                                            OutlinedButton(
                                                onClick = { clearJobLogFocus(resetExpandedLogs = true) }
                                            ) {
                                                Text("清除 Job 聚焦")
                                            }
                                        }
                                        if (showOnlyIssueLogs) {
                                            OutlinedButton(onClick = { showOnlyIssueLogs = false }) {
                                                Text("显示全部日志")
                                            }
                                        }
                                        if (searchActive) {
                                            OutlinedButton(
                                                onClick = {
                                                    logSearchQuery = ""
                                                    selectedLogSearchPresets = emptySet()
                                                    logSearchRequireAllTerms = false
                                                    showOnlyMatchedLogs = false
                                                    activeMatchedLogEntryName = null
                                                }
                                            ) {
                                                Text("清空搜索")
                                            }
                                        }
                                    }
                                    Text(
                                        text = visibleLogSummaryText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = mutedTextColor
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    expandedLogEntries = expandedLogEntries +
                                        visibleLogEntries.map { it.entryName }.toSet()
                                }
                            ) {
                                Text("展开当前范围")
                            }
                            OutlinedButton(
                                onClick = {
                                    expandedLogEntries = expandedLogEntries -
                                        visibleLogEntries.map { it.entryName }.toSet()
                                }
                            ) {
                                Text("收起当前范围")
                            }
                            OutlinedButton(onClick = { expandedLogEntries = autoExpandedLogEntries }) {
                                Text("恢复自动展开")
                            }
                        }
                        autoExpandHint?.let { hint ->
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (detail.status.equals("completed", ignoreCase = true)) {
                                    mutedTextColor
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                        OutlinedTextField(
                            value = logSearchQuery,
                            onValueChange = { logSearchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("搜索日志关键字") },
                            placeholder = { Text("空格、/、, 分词，或用引号保留完整短语") }
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PROJECT_GITHUB_WORKFLOW_LOG_SEARCH_PRESETS.forEach { preset ->
                                val presetSelected = selectedLogSearchPresets.any {
                                    it.equals(preset, ignoreCase = true)
                                }
                                FilterChip(
                                    selected = presetSelected,
                                    onClick = {
                                        selectedLogSearchPresets = if (presetSelected) {
                                            selectedLogSearchPresets.filterNot {
                                                it.equals(preset, ignoreCase = true)
                                            }.toSet()
                                        } else {
                                            selectedLogSearchPresets + preset
                                        }
                                    },
                                    label = { Text(preset) }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!selectedIssueStepName.isNullOrBlank()) {
                                FilterChip(
                                    selected = logSearchQuery == selectedIssueStepName,
                                    onClick = { logSearchQuery = selectedIssueStepName.orEmpty() },
                                    label = { Text("用当前步骤名") }
                                )
                            }
                            if (logSearchTerms.size > 1) {
                                FilterChip(
                                    selected = !logSearchRequireAllTerms,
                                    onClick = { logSearchRequireAllTerms = false },
                                    label = { Text("任一命中") }
                                )
                                FilterChip(
                                    selected = logSearchRequireAllTerms,
                                    onClick = { logSearchRequireAllTerms = true },
                                    label = { Text("全部命中") }
                                )
                            }
                            if (searchActive) {
                                FilterChip(
                                    selected = showOnlyMatchedLogs,
                                    onClick = { showOnlyMatchedLogs = !showOnlyMatchedLogs },
                                    label = { Text("只看搜索命中") }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        logSearchQuery = ""
                                        selectedLogSearchPresets = emptySet()
                                        logSearchRequireAllTerms = false
                                        showOnlyMatchedLogs = false
                                        activeMatchedLogEntryName = null
                                    },
                                    label = { Text("清空搜索") }
                                )
                            }
                        }
                        if (selectedLogSearchPresets.isNotEmpty()) {
                            Text(
                                text = "已选预设 ${selectedLogSearchPresets.size} 个：${
                                    selectedLogSearchPresets.joinToString(" / ")
                                }",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                        if (searchActive) {
                            Text(
                                text = "当前关键词：${logSearchTerms.joinToString(" / ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                        if (!selectedIssueStepName.isNullOrBlank()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = showOnlySelectedStepLogs,
                                    onClick = {
                                        val nextValue = !showOnlySelectedStepLogs
                                        showOnlySelectedStepLogs = nextValue
                                        if (nextValue) {
                                            expandedLogEntries = focusedStepMatchedEntries
                                        }
                                    },
                                    label = { Text("只看该步骤日志") }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = { clearStepLogFocus(resetExpandedLogs = true) },
                                    label = { Text("清除步骤聚焦") }
                                )
                            }
                            if (focusedStepMatchedEntries.isEmpty()) {
                                Text(
                                    text = "当前步骤名还没有直接命中日志文件，已回退为显示当前 Job 的全部日志。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            }
                        }
                        if (searchActive) {
                            val matchedCount = scopedLogEntries.count { entry ->
                                logSearchHits[entry.entryName]?.hasMatch == true
                            }
                            Text(
                                text = buildString {
                                    append("搜索命中 $matchedCount / ${scopedLogEntries.size} 个日志文件")
                                    append(" · 关键词 ${logSearchTerms.size} 个")
                                    if (logSearchTerms.size > 1) {
                                        append(
                                            if (logSearchRequireAllTerms) {
                                                " · 全部命中"
                                            } else {
                                                " · 任一命中"
                                            }
                                        )
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                            if (matchedVisibleLogEntries.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val totalMatches = matchedVisibleLogEntries.size
                                            if (totalMatches == 0) return@OutlinedButton
                                            val currentIndex = activeMatchedLogEntryIndex
                                                .takeIf { it >= 0 } ?: 0
                                            val nextIndex =
                                                (currentIndex - 1 + totalMatches) % totalMatches
                                            val targetEntryName =
                                                matchedVisibleLogEntries[nextIndex].entryName
                                            activeMatchedLogEntryName = targetEntryName
                                            activeMatchedLineIndexByEntry[targetEntryName] =
                                                activeMatchedLineIndexByEntry[targetEntryName] ?: 0
                                            expandedLogEntries = expandedLogEntries + targetEntryName
                                        }
                                    ) {
                                        Text("上一处")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val totalMatches = matchedVisibleLogEntries.size
                                            if (totalMatches == 0) return@OutlinedButton
                                            val currentIndex = activeMatchedLogEntryIndex
                                                .takeIf { it >= 0 } ?: -1
                                            val nextIndex =
                                                (currentIndex + 1 + totalMatches) % totalMatches
                                            val targetEntryName =
                                                matchedVisibleLogEntries[nextIndex].entryName
                                            activeMatchedLogEntryName = targetEntryName
                                            activeMatchedLineIndexByEntry[targetEntryName] =
                                                activeMatchedLineIndexByEntry[targetEntryName] ?: 0
                                            expandedLogEntries = expandedLogEntries + targetEntryName
                                        }
                                    ) {
                                        Text("下一处")
                                    }
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = "第 ${
                                                    (activeMatchedLogEntryIndex.takeIf { it >= 0 } ?: 0) + 1
                                                } / ${matchedVisibleLogEntries.size} 个命中"
                                            )
                                        }
                                    )
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text("最相关日志已自动展开") }
                                    )
                                }
                            }
                        }
                        if (visibleLogEntries.isEmpty()) {
                            Text(
                                text = buildProjectGitHubWorkflowEmptyLogMessage(
                                    searchActive = searchActive,
                                    showOnlyMatchedLogs = showOnlyMatchedLogs,
                                    selectedJobName = selectedIssueJobName,
                                    selectedStepName = selectedIssueStepName,
                                    showOnlyIssueLogs = showOnlyIssueLogs
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                        visibleLogEntries.forEach { entry ->
                            val isExpanded = expandedLogEntries.contains(entry.entryName)
                            val matchesFocusedStep = !selectedIssueStepName.isNullOrBlank() &&
                                focusedStepMatchedEntries.contains(entry.entryName)
                            val matchesFocusedJob = !selectedIssueJobName.isNullOrBlank() &&
                                projectGitHubLogMatchesJob(entry, selectedIssueJobName.orEmpty())
                            val searchHit = logSearchHits[entry.entryName]
                            val matchesSearch = searchHit?.hasMatch == true
                            val isActiveSearchMatch = matchesSearch &&
                                activeMatchedLogEntryName == entry.entryName
                            val activeMatchedLineIndex = searchHit
                                ?.matchedLineIndices
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { matchedLineIndices ->
                                    activeMatchedLineIndexByEntry[entry.entryName]
                                        ?.coerceIn(0, matchedLineIndices.lastIndex) ?: 0
                                }
                                ?: 0
                            val activeMatchedLineNumber = searchHit
                                ?.matchedLineNumbers
                                ?.getOrNull(activeMatchedLineIndex)
                            val previewText = if (isExpanded) {
                                entry.preview
                            } else if (searchActive && matchesSearch) {
                                projectGitHubCollapsedLogPreviewAroundMatch(
                                    preview = entry.preview,
                                    matchedLineIndices = searchHit.matchedLineIndices,
                                    activeMatchIndex = activeMatchedLineIndex
                                )
                            } else {
                                projectGitHubCollapsedLogPreview(entry.preview)
                            }
                            ProjectGitHubWorkflowLogEntryCard(
                                entry = entry,
                                previewText = previewText,
                                searchTerms = logSearchTerms,
                                searchHit = searchHit,
                                mutedTextColor = mutedTextColor,
                                chromeAlphaSurfaceColor = chromeColor.copy(alpha = 0.56f),
                                defaultSurfaceColor = surfaceColor.copy(alpha = 0.58f),
                                matchesFocusedJob = matchesFocusedJob,
                                matchesFocusedStep = matchesFocusedStep,
                                matchesSearch = matchesSearch,
                                isActiveSearchMatch = isActiveSearchMatch,
                                activeMatchedLineIndex = activeMatchedLineIndex,
                                activeMatchedLineNumber = activeMatchedLineNumber,
                                isExpanded = isExpanded,
                                onToggleExpanded = {
                                    expandedLogEntries = if (isExpanded) {
                                        expandedLogEntries - entry.entryName
                                    } else {
                                        expandedLogEntries + entry.entryName
                                    }
                                },
                                onCopyPreview = {
                                    copyWorkflowDetailText(context, entry.preview)
                                },
                                onJumpToPreviousMatch = {
                                    val totalMatches = searchHit?.matchedLineIndices?.size ?: 0
                                    if (totalMatches == 0) return@ProjectGitHubWorkflowLogEntryCard
                                    val nextIndex =
                                        (activeMatchedLineIndex - 1 + totalMatches) % totalMatches
                                    activeMatchedLogEntryName = entry.entryName
                                    activeMatchedLineIndexByEntry[entry.entryName] = nextIndex
                                    expandedLogEntries = expandedLogEntries + entry.entryName
                                },
                                onJumpToNextMatch = {
                                    val totalMatches = searchHit?.matchedLineIndices?.size ?: 0
                                    if (totalMatches == 0) return@ProjectGitHubWorkflowLogEntryCard
                                    val nextIndex = (activeMatchedLineIndex + 1) % totalMatches
                                    activeMatchedLogEntryName = entry.entryName
                                    activeMatchedLineIndexByEntry[entry.entryName] = nextIndex
                                    expandedLogEntries = expandedLogEntries + entry.entryName
                                },
                                onFocusCurrentMatch = {
                                    activeMatchedLogEntryName = entry.entryName
                                    expandedLogEntries = expandedLogEntries + entry.entryName
                                }
                            )
                        }
                    }
                }
                if (detail.artifacts.isNotEmpty() || !detail.artifactsError.isNullOrBlank()) {
                    Text("运行产物", style = MaterialTheme.typography.titleSmall)
                    detail.artifactsError?.takeIf { it.isNotBlank() }?.let { artifactError ->
                        Text(
                            text = artifactError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (detail.artifacts.isEmpty()) {
                        Text(
                            text = "这次运行暂时还没有可下载的产物。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    } else {
                        detail.artifacts.forEach { artifact ->
                            ProjectInsetCard(
                                shape = RoundedCornerShape(12.dp),
                                surfaceColorOverride = chromeColor.copy(alpha = 0.52f),
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
                                        color = mutedTextColor
                                    )
                                    if (artifact.expired) {
                                        Text(
                                            text = "该产物已过期，GitHub 不再提供下载。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    } else {
                                        OutlinedButton(
                                            onClick = { onDownloadArtifact(detail, artifact) }
                                        ) {
                                            Text("下载产物")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                    if (detail.jobs.isEmpty()) {
                        Text(
                            text = "这次运行还没有返回 job 详情，可能仍在初始化。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    } else {
                        visibleJobs.forEach { job ->
                            ProjectInsetCard(
                                shape = RoundedCornerShape(12.dp),
                                surfaceColorOverride = if (job.hasIssue) {
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.40f)
                                } else {
                                    surfaceColor.copy(alpha = 0.58f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { focusJobLogs(job.name) }
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(job.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = job.statusLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (job.hasIssue) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            mutedTextColor
                                        }
                                    )
                                    if (job.startedAt.isNotBlank() || job.completedAt.isNotBlank()) {
                                        Text(
                                            text = buildString {
                                                if (job.startedAt.isNotBlank()) {
                                                    append("开始 ${formatProjectGitHubIsoDateTime(job.startedAt)}")
                                                }
                                                if (job.completedAt.isNotBlank()) {
                                                    if (isNotEmpty()) append(" · ")
                                                    append("结束 ${formatProjectGitHubIsoDateTime(job.completedAt)}")
                                                }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = mutedTextColor
                                        )
                                    }
                                    if (job.failedSteps.isNotEmpty()) {
                                        Text(
                                            text = "失败步骤 ${job.failedSteps.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilterChip(
                                            selected = true,
                                            onClick = {},
                                            label = { Text(job.statusLabel) }
                                        )
                                        FilterChip(
                                            selected = true,
                                            onClick = {},
                                            label = { Text(job.durationLabel) }
                                        )
                                        if (job.failedSteps.isNotEmpty()) {
                                            FilterChip(
                                                selected = true,
                                                onClick = {},
                                                label = { Text("失败 ${job.failedSteps.size} 步") }
                                            )
                                        }
                                        if (job.steps.isNotEmpty()) {
                                            FilterChip(
                                                selected = true,
                                                onClick = {},
                                                label = { Text("步骤 ${job.steps.size}") }
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(onClick = { focusJobLogs(job.name) }) {
                                            Text("查看日志")
                                        }
                                        if (selectedIssueJobName == job.name) {
                                            FilterChip(
                                                selected = true,
                                                onClick = {},
                                                label = { Text("当前已聚焦") }
                                            )
                                        }
                                    }
                                    val highlightedSteps = remember(job.steps) {
                                        val failingSteps = job.steps.filter { it.hasIssue }
                                        if (failingSteps.isNotEmpty()) {
                                            failingSteps
                                        } else {
                                            job.steps.filterNot {
                                                it.status.equals("completed", ignoreCase = true)
                                            }.ifEmpty { job.steps.take(3) }
                                        }
                                    }
                                    if (highlightedSteps.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            highlightedSteps.take(5).forEach { step ->
                                                FilterChip(
                                                    selected = selectedIssueJobName == job.name &&
                                                        selectedIssueStepName == step.name,
                                                    onClick = { focusJobStepLogs(job.name, step.name) },
                                                    label = {
                                                        Text(
                                                            buildString {
                                                                append(step.name.take(22))
                                                                append(" · ")
                                                                append(step.durationLabel)
                                                            }
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    if (job.steps.isNotEmpty()) {
                                        Text(
                                            text = "步骤列表",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = mutedTextColor
                                        )
                                        job.steps.forEach { step ->
                                            ProjectGitHubWorkflowStepCard(
                                                step = step,
                                                isFocused = selectedIssueJobName == job.name &&
                                                    selectedIssueStepName == step.name,
                                                mutedTextColor = mutedTextColor,
                                                onFocusLogs = { focusJobStepLogs(job.name, step.name) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = chromeColor.copy(alpha = 0.48f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (detail.status.equals("completed", ignoreCase = true)) {
                                "底部常驻操作：可直接重跑、刷新详情，或补拉当前运行的日志 ZIP。"
                            } else {
                                "底部常驻操作：运行中也可随时刷新详情，完成后可直接重新运行同一工作流。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = { onRefreshDetail(detail) }) {
                                Text("刷新详情")
                            }
                            OutlinedButton(onClick = { onDownloadLogs(detail) }) {
                                Text("日志 ZIP")
                            }
                            detail.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                TextButton(onClick = { onOpenRunPage(detail.htmlUrl) }) {
                                    Text("网页")
                                }
                            }
                            Button(onClick = { onRerun(detail) }) {
                                Text("重新运行")
                            }
                        }
                    }
                }
            }
    }
}

private fun copyWorkflowDetailText(context: Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
}

@Composable
private fun ProjectGitHubWorkflowStepCard(
    step: ProjectGitHubWorkflowStepUi,
    isFocused: Boolean,
    mutedTextColor: androidx.compose.ui.graphics.Color,
    onFocusLogs: () -> Unit
) {
    val surfaceColor = when {
        isFocused -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
        step.hasIssue -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.36f)
        step.status.equals("completed", ignoreCase = true) -> MaterialTheme.colorScheme.surfaceVariant.copy(
            alpha = 0.32f
        )
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f)
    }
    val statusColor = when {
        isFocused -> MaterialTheme.colorScheme.primary
        step.hasIssue -> MaterialTheme.colorScheme.error
        else -> mutedTextColor
    }
    ProjectInsetCard(
        shape = RoundedCornerShape(10.dp),
        surfaceColorOverride = surfaceColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onFocusLogs)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = buildString {
                    if (step.number > 0) {
                        append("#${step.number} ")
                    }
                    append(step.name)
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(step.statusLabel) }
                )
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(step.durationLabel) }
                )
                if (isFocused) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("当前聚焦") }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onFocusLogs) {
                    Text("查看步骤日志")
                }
                Text(
                    text = if (step.startedAt.isNotBlank() || step.completedAt.isNotBlank()) {
                        buildString {
                            if (step.startedAt.isNotBlank()) {
                                append("开始 ${formatProjectGitHubIsoDateTime(step.startedAt)}")
                            }
                            if (step.completedAt.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append("结束 ${formatProjectGitHubIsoDateTime(step.completedAt)}")
                            }
                        }
                    } else {
                        "等待 GitHub 返回该步骤时间"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }
        }
    }
}

@Composable
private fun ProjectGitHubWorkflowLogEntryCard(
    entry: ProjectGitHubWorkflowLogEntryUi,
    previewText: String,
    searchTerms: List<String>,
    searchHit: ProjectGitHubWorkflowLogSearchHitUi?,
    mutedTextColor: androidx.compose.ui.graphics.Color,
    chromeAlphaSurfaceColor: androidx.compose.ui.graphics.Color,
    defaultSurfaceColor: androidx.compose.ui.graphics.Color,
    matchesFocusedJob: Boolean,
    matchesFocusedStep: Boolean,
    matchesSearch: Boolean,
    isActiveSearchMatch: Boolean,
    activeMatchedLineIndex: Int,
    activeMatchedLineNumber: Int?,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCopyPreview: () -> Unit,
    onJumpToPreviousMatch: () -> Unit,
    onJumpToNextMatch: () -> Unit,
    onFocusCurrentMatch: () -> Unit
) {
    SelectionContainer {
        val highlightedSnippet = remember(searchHit?.snippet, searchTerms) {
            highlightProjectGitHubLogText(
                text = searchHit?.snippet?.ifBlank { "文件名命中搜索关键字" } ?: "文件名命中搜索关键字",
                queries = searchTerms
            )
        }
        ProjectInsetCard(
            shape = RoundedCornerShape(12.dp),
            surfaceColorOverride = if (matchesFocusedStep && isActiveSearchMatch) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            } else if (matchesFocusedStep) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f)
            } else if (matchesFocusedJob) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else if (isActiveSearchMatch) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.66f)
            } else if (matchesSearch) {
                chromeAlphaSurfaceColor
            } else {
                defaultSurfaceColor
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = buildString {
                        append("共 ${entry.totalLineCount} 行")
                        if (entry.truncated) {
                            append(" · 预览已截断")
                        }
                        if (matchesFocusedJob) {
                            append(" · 命中当前 Job")
                        }
                        if (matchesFocusedStep) {
                            append(" · 命中当前步骤")
                        }
                        if (searchTerms.isNotEmpty() && matchesSearch) {
                            append(" · 搜索命中 ${searchHit?.matchedLineCount ?: 0} 行")
                        }
                        if (isActiveSearchMatch) {
                            append(" · 当前搜索焦点")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (matchesFocusedJob) {
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text("当前 Job") }
                        )
                    }
                    if (matchesFocusedStep) {
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text("当前步骤") }
                        )
                    }
                    if (matchesSearch) {
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text("搜索命中 ${searchHit?.matchedLineCount ?: 0}") }
                        )
                    }
                    if (isActiveSearchMatch) {
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text("当前搜索焦点") }
                        )
                    }
                }
                if (searchTerms.isNotEmpty() && matchesSearch) {
                    Text(
                        text = highlightedSnippet,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (searchTerms.isNotEmpty() && (searchHit?.matchedLineIndices?.isNotEmpty() == true)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onJumpToPreviousMatch) {
                            Text("上一行")
                        }
                        OutlinedButton(onClick = onJumpToNextMatch) {
                            Text("下一行")
                        }
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = {
                                Text(
                                    "第 ${activeMatchedLineIndex + 1} / ${searchHit.matchedLineIndices.size} 个命中行"
                                )
                            }
                        )
                        activeMatchedLineNumber?.let { lineNumber ->
                            FilterChip(
                                selected = true,
                                onClick = onFocusCurrentMatch,
                                label = { Text("L$lineNumber") }
                            )
                        }
                        if (!isExpanded) {
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text("折叠态显示命中片段") }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onToggleExpanded) {
                        Text(if (isExpanded) "收起" else "展开")
                    }
                    Button(onClick = onCopyPreview) {
                        Text("复制预览")
                    }
                }
                ProjectGitHubWorkflowLogPreviewBody(
                    preview = previewText,
                    queries = searchTerms,
                    expanded = isExpanded,
                    activeLineNumber = activeMatchedLineNumber?.takeIf { isActiveSearchMatch }
                )
            }
        }
    }
}
