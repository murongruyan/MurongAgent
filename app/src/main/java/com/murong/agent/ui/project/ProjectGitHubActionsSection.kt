package com.murong.agent.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.rememberMurongChromeColor
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor

@Composable
internal fun ProjectGitHubActionsSection(
    state: ProjectGitHubActionsState,
    isLoading: Boolean,
    isActionRunning: Boolean,
    tokenConfigured: Boolean,
    onRefresh: () -> Unit,
    onOpenRepo: () -> Unit,
    onRunWorkflow: (ProjectGitHubWorkflowUi) -> Unit,
    onOpenWorkflowPage: (ProjectGitHubWorkflowUi) -> Unit,
    onOpenArtifacts: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRunPage: (ProjectGitHubWorkflowRunUi) -> Unit,
    onDownloadRunLogs: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRunDetail: (ProjectGitHubWorkflowRunUi) -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MurongGlassSurface(
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
                        Text("GitHub 工作流", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = state.repo?.let { "${it.owner}/${it.repo}" } ?: "当前远端还没有解析到 owner/repo",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        state.viewerLogin?.let { login ->
                            Text(
                                text = "已登录 Token: @$login",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                        state.defaultBranch?.let { branch ->
                            Text(
                                text = "默认分支: $branch · 工作流 ${state.workflows.size} · 最近运行 ${state.recentRuns.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
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
                state.repoHtmlUrl?.takeIf { it.isNotBlank() }?.let {
                    TextButton(
                        onClick = onOpenRepo,
                        enabled = !isLoading && !isActionRunning
                    ) {
                        Text("打开仓库")
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
                                text = "正在读取 GitHub 工作流...",
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
                            text = "先去设置页填 GitHub Token，工作流、Release 和产物下载才能真正联动。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }
                }
            }
        }
        if (state.workflows.isNotEmpty()) {
            Text("可执行工作流", style = MaterialTheme.typography.titleSmall)
            state.workflows.forEach { workflow ->
                MurongGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.58f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = workflow.name.ifBlank { workflow.path },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${workflow.path} · ${workflow.stateLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            workflow.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                TextButton(
                                    onClick = { onOpenWorkflowPage(workflow) },
                                    enabled = !isActionRunning
                                ) {
                                    Text("网页")
                                }
                            }
                            Button(
                                onClick = { onRunWorkflow(workflow) },
                                enabled = workflow.canDispatch && !isActionRunning
                            ) {
                                Text("运行")
                            }
                        }
                    }
                }
            }
        }
        if (state.recentRuns.isNotEmpty()) {
            Text("最近运行", style = MaterialTheme.typography.titleSmall)
            state.recentRuns.forEach { run ->
                MurongGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.58f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = run.displayTitle.ifBlank { run.name.ifBlank { "运行 #${run.runNumber}" } },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${run.statusLabel} · ${run.headBranch} · ${run.updatedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Text(
                            text = "事件 ${run.event} · #${run.runNumber}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            run.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                TextButton(
                                    onClick = { onOpenRunPage(run) },
                                    enabled = !isActionRunning
                                ) {
                                    Text("网页")
                                }
                            }
                            OutlinedButton(
                                onClick = { onDownloadRunLogs(run) },
                                enabled = !isActionRunning
                            ) {
                                Text("日志 ZIP")
                            }
                            TextButton(
                                onClick = { onOpenRunDetail(run) },
                                enabled = !isActionRunning
                            ) {
                                Text("控制台")
                            }
                            OutlinedButton(
                                onClick = { onOpenArtifacts(run) },
                                enabled = !isActionRunning
                            ) {
                                Text("产物")
                            }
                        }
                    }
                }
            }
        }
    }
}
