package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.ReasonixGlassSurface
import dev.reasonix.mobile.ui.ReasonixLargeDialogScaffold

@Composable
internal fun ProjectGitHubRemoteFileDialog(
    file: ProjectGitHubRemoteFileUi,
    contentDraft: String,
    onContentDraftChange: (String) -> Unit,
    commitMessageDraft: String,
    onCommitMessageDraftChange: (String) -> Unit,
    canSubmit: Boolean,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    ReasonixLargeDialogScaffold(onDismissRequest = onDismiss) {
        ReasonixGlassSurface(
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("远端文件编辑", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = file.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "分支/引用: ${file.ref} · 大小 ${formatProjectByteSize(file.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (file.truncated) {
                Text(
                    text = "当前文件内容已被 GitHub 截断，提交前请注意核对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            OutlinedTextField(
                value = commitMessageDraft,
                onValueChange = onCommitMessageDraftChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("提交说明") },
                singleLine = true
            )
            OutlinedTextField(
                value = contentDraft,
                onValueChange = onContentDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp),
                label = { Text("文件内容") },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                minLines = 14
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            Button(
                onClick = onSubmit,
                enabled = canSubmit
            ) {
                Text("提交到远端")
            }
        }
    }
}
