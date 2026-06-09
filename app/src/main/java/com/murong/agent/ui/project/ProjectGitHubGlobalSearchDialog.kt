package com.murong.agent.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongLargeDialogScaffold

@Composable
internal fun ProjectGitHubGlobalSearchDialog(
    store: ProjectGitHubGlobalSearchStore,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
    onResultClick: (ProjectGitHubGlobalSearchResultUi) -> Unit,
    chromeColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    MurongLargeDialogScaffold(
        onDismissRequest = onClose
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MurongGlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                    OutlinedTextField(
                        value = store.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("搜索所有仓库中的 Issue, PR 或文件...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (store.query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "清除")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = chromeColor.copy(alpha = 0.1f),
                            unfocusedContainerColor = chromeColor.copy(alpha = 0.05f)
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onSearch,
                        enabled = store.query.isNotBlank() && !store.isLoading,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (store.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("搜索")
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = chromeColor.copy(alpha = 0.16f)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (store.results.isEmpty() && !store.isLoading && store.errorMessage == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = chromeColor.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (store.query.isBlank()) "输入关键字开始全局搜索" else "未找到匹配项",
                        style = MaterialTheme.typography.bodyLarge,
                        color = chromeColor.copy(alpha = 0.6f)
                    )
                }
            } else if (store.errorMessage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "!",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = store.errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onSearch) {
                        Text("重试")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(store.results) { result ->
                        GlobalSearchResultItem(
                            result = result,
                            onClick = { onResultClick(result) },
                            chromeColor = chromeColor
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = chromeColor.copy(alpha = 0.1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalSearchResultItem(
    result: ProjectGitHubGlobalSearchResultUi,
    onClick: () -> Unit,
    chromeColor: Color
) {
    val iconLabel = when (result.type) {
        ProjectGitHubGlobalSearchResultType.ISSUE -> "I"
        ProjectGitHubGlobalSearchResultType.PULL_REQUEST -> "PR"
        ProjectGitHubGlobalSearchResultType.FILE -> "F"
    }

    val iconTint = when (result.type) {
        ProjectGitHubGlobalSearchResultType.ISSUE -> Color(0xFF238636)
        ProjectGitHubGlobalSearchResultType.PULL_REQUEST -> Color(0xFF8957E5)
        ProjectGitHubGlobalSearchResultType.FILE -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                text = result.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = result.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = ">",
            color = chromeColor.copy(alpha = 0.45f),
            style = MaterialTheme.typography.titleMedium
        )
    }
}
