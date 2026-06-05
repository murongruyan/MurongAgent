package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.ReasonixAlertDialog

@Composable
internal fun ProjectGitHubWorkflowDispatchDialog(
    workflow: ProjectGitHubWorkflowUi,
    refDraft: String,
    quickRefs: List<String>,
    inputs: List<ProjectGitHubWorkflowDispatchInputUi>,
    isActionRunning: Boolean,
    onRefDraftChange: (String) -> Unit,
    onInputKeyChange: (Int, String) -> Unit,
    onInputValueChange: (Int, String) -> Unit,
    onAddInput: () -> Unit,
    onRemoveInput: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ReasonixAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = refDraft.isNotBlank() && !isActionRunning
            ) {
                Text("运行")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        title = { Text("配置工作流") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = workflow.name.ifBlank { workflow.path },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = workflow.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = refDraft,
                    onValueChange = onRefDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分支 / Ref") },
                    placeholder = { Text("main") },
                    singleLine = true
                )
                if (quickRefs.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickRefs.forEach { ref ->
                            FilterChip(
                                selected = refDraft == ref,
                                onClick = { onRefDraftChange(ref) },
                                label = { Text(ref) }
                            )
                        }
                    }
                }
                Text(
                    text = "自定义参数会按 workflow_dispatch.inputs 方式发送。当前先提供通用键值对表单，后续再补自动解析工作流参数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (inputs.isEmpty()) {
                    Text(
                        text = "当前没有自定义参数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    inputs.forEachIndexed { index, input ->
                        ProjectInsetCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = input.key,
                                    onValueChange = { onInputKeyChange(index, it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("参数名") },
                                    placeholder = { Text("例如 environment") },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = input.value,
                                    onValueChange = { onInputValueChange(index, it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("参数值") },
                                    placeholder = { Text("例如 production") },
                                    singleLine = true
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onRemoveInput(index) }) {
                                        Text("删除参数")
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onAddInput, enabled = !isActionRunning) {
                    Text("新增参数")
                }
            }
        }
    )
}
