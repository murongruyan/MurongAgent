package com.murong.agent.ui.project

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongLargeDialogScaffold

@Composable
internal fun ProjectGitHubGlobalTaskCenterDialog(
    taskCenter: ProjectGitHubGlobalTaskCenterUi,
    onClose: () -> Unit,
    onTaskClick: (ProjectGitHubWorkspaceTaskUi) -> Unit,
    chromeColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    MurongLargeDialogScaffold(
        onDismissRequest = onClose
    ) {
        val totalTasks = taskCenter.criticalTasks.size + taskCenter.attentionTasks.size
        MurongGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "任务中心",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Badge(
                        containerColor = if (taskCenter.criticalTasks.isNotEmpty()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    ) {
                        Text("$totalTasks 个待处理")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = buildString {
                        append("优先 ${taskCenter.criticalTasks.size}")
                        append(" · 跟进 ${taskCenter.attentionTasks.size}")
                        if (taskCenter.criticalTasks.isEmpty() && taskCenter.attentionTasks.isEmpty()) {
                            append(" · 当前工作区状态平稳")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = chromeColor.copy(alpha = 0.16f)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (taskCenter.criticalTasks.isEmpty() && taskCenter.attentionTasks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = Color(0xFF238636).copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "暂无紧急任务，一切正常！",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (taskCenter.criticalTasks.isNotEmpty()) {
                item {
                    Text(
                        "紧急任务",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(taskCenter.criticalTasks) { task ->
                    GlobalTaskItem(
                        task = task,
                        onClick = { onTaskClick(task) },
                        chromeColor = chromeColor
                    )
                }
            }

            if (taskCenter.attentionTasks.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "建议处理",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(taskCenter.attentionTasks) { task ->
                    GlobalTaskItem(
                        task = task,
                        onClick = { onTaskClick(task) },
                        chromeColor = chromeColor
                    )
                }
            }
        }
    }
}

@Composable
private fun GlobalTaskItem(
    task: ProjectGitHubWorkspaceTaskUi,
    onClick: () -> Unit,
    chromeColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (task.isCritical) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
            } else {
                chromeColor.copy(alpha = 0.1f)
            }
        ),
        border = if (task.isCritical) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
        } else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconLabel = when {
                task.isCritical -> "!"
                task.targetPullRequest != null -> "PR"
                task.targetIssue != null -> "I"
                else -> "T"
            }
            
            val iconTint = when {
                task.isCritical -> MaterialTheme.colorScheme.error
                task.targetPullRequest != null -> Color(0xFF8957E5)
                task.targetIssue != null -> Color(0xFF238636)
                else -> MaterialTheme.colorScheme.primary
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(iconTint.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = iconLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = iconTint
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    task.repoTitle.takeIf { it.isNotBlank() }?.let { repoTitle ->
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text(repoTitle, maxLines = 1) }
                        )
                    }
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(task.kind.label) }
                    )
                    task.destinationLabel?.takeIf { it.isNotBlank() }?.let { destination ->
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text(destination) }
                        )
                    }
                }
                Text(
                    text = task.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Text(
                text = task.actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (task.isCritical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = ">",
                color = chromeColor.copy(alpha = 0.45f),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
