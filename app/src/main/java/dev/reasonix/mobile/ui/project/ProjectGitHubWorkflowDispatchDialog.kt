package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.ReasonixGlassSurface
import dev.reasonix.mobile.ui.ReasonixLargeDialogScaffold

private enum class ProjectGitHubWorkflowInputSourceFilter {
    ALL,
    AUTO,
    MANUAL
}

private data class ProjectGitHubWorkflowInputSectionSummary(
    val totalCount: Int,
    val filledCount: Int,
    val missingRequiredCount: Int,
    val missingRequiredKeys: List<String>
)

private data class ProjectGitHubWorkflowInputQuickFillSuggestion(
    val value: String,
    val actionLabel: String
)

@Composable
internal fun ProjectGitHubWorkflowDispatchDialog(
    workflow: ProjectGitHubWorkflowUi,
    refDraft: String,
    quickRefs: List<String>,
    branchRefs: List<String>,
    isBranchRefsLoading: Boolean,
    inputs: List<ProjectGitHubWorkflowDispatchInputUi>,
    isSchemaLoading: Boolean,
    schemaMessage: String?,
    isActionRunning: Boolean,
    onRefDraftChange: (String) -> Unit,
    onInputKeyChange: (Int, String) -> Unit,
    onInputValueChange: (Int, String) -> Unit,
    onAddInput: () -> Unit,
    onRemoveInput: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var branchSearchQuery by remember(branchRefs) { mutableStateOf("") }
    var inputSearchQuery by remember(inputs) { mutableStateOf("") }
    var showRequiredOnly by remember(inputs) { mutableStateOf(false) }
    var inputSourceFilter by remember(inputs) {
        mutableStateOf(ProjectGitHubWorkflowInputSourceFilter.ALL)
    }
    var autoDetectedSectionExpanded by remember(inputs) { mutableStateOf(true) }
    var manualSectionExpanded by remember(inputs) { mutableStateOf(true) }
    val filteredBranchRefs = remember(branchRefs, branchSearchQuery) {
        val query = branchSearchQuery.trim()
        if (query.isBlank()) {
            branchRefs
        } else {
            branchRefs.filter { ref ->
                ref.contains(query, ignoreCase = true)
            }
        }
    }
    val filteredInputs = remember(inputs, inputSearchQuery, showRequiredOnly, inputSourceFilter) {
        val query = inputSearchQuery.trim()
        inputs.mapIndexed { index, input -> index to input }.filter { (_, input) ->
            val matchesRequired = !showRequiredOnly || input.required
            val matchesQuery = query.isBlank() ||
                input.key.contains(query, ignoreCase = true) ||
                input.description?.contains(query, ignoreCase = true) == true ||
                input.value.contains(query, ignoreCase = true) ||
                input.defaultValue?.contains(query, ignoreCase = true) == true
            val matchesSource = when (inputSourceFilter) {
                ProjectGitHubWorkflowInputSourceFilter.ALL -> true
                ProjectGitHubWorkflowInputSourceFilter.AUTO -> input.autoDetected
                ProjectGitHubWorkflowInputSourceFilter.MANUAL -> !input.autoDetected
            }
            matchesRequired && matchesQuery && matchesSource
        }
    }
    val autoDetectedInputs = remember(filteredInputs) {
        filteredInputs.filter { (_, input) -> input.autoDetected }
    }
    val manualInputs = remember(filteredInputs) {
        filteredInputs.filter { (_, input) -> !input.autoDetected }
    }
    val missingRequiredInputs = remember(inputs) {
        inputs.filter { input ->
            input.autoDetected &&
                input.required &&
                input.key.isNotBlank() &&
                input.value.trim().ifBlank { input.defaultValue?.trim().orEmpty() }.isBlank()
        }
    }
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
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("配置工作流", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = workflow.name.ifBlank { workflow.path },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = workflow.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = refDraft.isNotBlank() &&
                            missingRequiredInputs.isEmpty() &&
                            !isActionRunning
                    ) {
                        Text("运行")
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
                OutlinedTextField(
                    value = refDraft,
                    onValueChange = onRefDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分支 / Ref") },
                    placeholder = { Text("main") },
                    singleLine = true
                )
                if (quickRefs.isNotEmpty()) {
                    Text(
                        text = "常用分支",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
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
                when {
                    isBranchRefsLoading -> Text(
                        text = "正在读取仓库分支...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    branchRefs.isNotEmpty() -> {
                        if (branchRefs.size > 8) {
                            OutlinedTextField(
                                value = branchSearchQuery,
                                onValueChange = { branchSearchQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("筛选仓库分支") },
                                placeholder = { Text("输入分支名关键字") },
                                singleLine = true
                            )
                        }
                        Text(
                            text = if (branchSearchQuery.isBlank()) {
                                "仓库分支"
                            } else {
                                "仓库分支 · 命中 ${filteredBranchRefs.size}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (filteredBranchRefs.isEmpty()) {
                            Text(
                                text = "没有匹配的仓库分支，可继续手动输入 Ref。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 164.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                filteredBranchRefs.take(40).forEach { ref ->
                                    OutlinedButton(
                                        onClick = { onRefDraftChange(ref) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(ref)
                                    }
                                }
                                if (filteredBranchRefs.size > 40) {
                                    Text(
                                        text = "还有 ${filteredBranchRefs.size - 40} 个分支未显示，请继续筛选。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            filteredBranchRefs.take(12).forEach { ref ->
                                FilterChip(
                                    selected = refDraft == ref,
                                    onClick = { onRefDraftChange(ref) },
                                    label = { Text(ref) }
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "自定义参数会按 workflow_dispatch.inputs 方式发送。当前会优先自动解析工作流参数，仍保留手动补充入口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    isSchemaLoading -> Text(
                        text = "正在解析 workflow_dispatch 参数...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    !schemaMessage.isNullOrBlank() -> Text(
                        text = schemaMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (inputs.isEmpty()) {
                    Text(
                        text = "当前没有自定义参数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (inputs.size > 5) {
                        OutlinedTextField(
                            value = inputSearchQuery,
                            onValueChange = { inputSearchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("筛选工作流参数") },
                            placeholder = { Text("输入参数名或说明关键字") },
                            singleLine = true
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !showRequiredOnly,
                            onClick = { showRequiredOnly = false },
                            label = { Text("全部参数") }
                        )
                        FilterChip(
                            selected = showRequiredOnly,
                            onClick = { showRequiredOnly = true },
                            label = { Text("仅必填") }
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = inputSourceFilter == ProjectGitHubWorkflowInputSourceFilter.ALL,
                            onClick = {
                                inputSourceFilter = ProjectGitHubWorkflowInputSourceFilter.ALL
                            },
                            label = { Text("全部来源") }
                        )
                        FilterChip(
                            selected = inputSourceFilter == ProjectGitHubWorkflowInputSourceFilter.AUTO,
                            onClick = {
                                inputSourceFilter = ProjectGitHubWorkflowInputSourceFilter.AUTO
                            },
                            label = { Text("自动解析") }
                        )
                        FilterChip(
                            selected = inputSourceFilter == ProjectGitHubWorkflowInputSourceFilter.MANUAL,
                            onClick = {
                                inputSourceFilter = ProjectGitHubWorkflowInputSourceFilter.MANUAL
                            },
                            label = { Text("手动补充") }
                        )
                    }
                    Text(
                        text = if (inputSearchQuery.isBlank() && !showRequiredOnly) {
                            "共 ${inputs.size} 个参数，自动解析 ${inputs.count { it.autoDetected }} 个，手动补充 ${inputs.count { !it.autoDetected }} 个"
                        } else {
                            "当前显示 ${filteredInputs.size} / ${inputs.size} 个参数"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (missingRequiredInputs.isNotEmpty()) {
                        Text(
                            text = "还有 ${missingRequiredInputs.size} 个必填参数未填写：${
                                missingRequiredInputs.joinToString("、") { it.key }
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (filteredInputs.isEmpty()) {
                        Text(
                            text = "没有匹配的参数，可切回全部参数或修改筛选关键字。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (autoDetectedInputs.isNotEmpty()) {
                        ProjectGitHubWorkflowInputSection(
                            title = "自动解析参数",
                            subtitle = "来自 workflow_dispatch.inputs，共 ${autoDetectedInputs.size} 个",
                            inputs = autoDetectedInputs,
                            expanded = autoDetectedSectionExpanded,
                            onExpandedChange = { autoDetectedSectionExpanded = it },
                            onInputKeyChange = onInputKeyChange,
                            onInputValueChange = onInputValueChange,
                            onRemoveInput = onRemoveInput
                        )
                    }
                    if (manualInputs.isNotEmpty()) {
                        ProjectGitHubWorkflowInputSection(
                            title = "手动补充参数",
                            subtitle = "用于补充未识别参数或临时覆盖，共 ${manualInputs.size} 个",
                            inputs = manualInputs,
                            expanded = manualSectionExpanded,
                            onExpandedChange = { manualSectionExpanded = it },
                            onInputKeyChange = onInputKeyChange,
                            onInputValueChange = onInputValueChange,
                            onRemoveInput = onRemoveInput
                        )
                    }
                }
            OutlinedButton(onClick = onAddInput, enabled = !isActionRunning) {
                Text("新增参数")
            }
        }
    }
}

@Composable
private fun ProjectGitHubWorkflowInputSection(
    title: String,
    subtitle: String,
    inputs: List<Pair<Int, ProjectGitHubWorkflowDispatchInputUi>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onInputKeyChange: (Int, String) -> Unit,
    onInputValueChange: (Int, String) -> Unit,
    onRemoveInput: (Int) -> Unit
) {
    val orderedInputs = remember(inputs) {
        inputs.sortedBy { (_, input) ->
            when {
                buildProjectGitHubWorkflowInputIsMissingRequiredValue(input) -> 0
                input.required -> 1
                else -> 2
            }
        }
    }
    val summary = remember(inputs) {
        buildProjectGitHubWorkflowInputSectionSummary(inputs.map { it.second })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = buildString {
                        append("已填写 ")
                        append(summary.filledCount)
                        append(" / ")
                        append(summary.totalCount)
                        if (summary.missingRequiredCount > 0) {
                            append(" · 缺少必填 ")
                            append(summary.missingRequiredCount)
                            append(" 项")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (summary.missingRequiredCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (summary.missingRequiredKeys.isNotEmpty()) {
                    Text(
                        text = "待补参数：${
                            summary.missingRequiredKeys.take(3).joinToString("、")
                        }${
                            if (summary.missingRequiredKeys.size > 3) {
                                " 等 ${summary.missingRequiredKeys.size} 项"
                            } else {
                                ""
                            }
                        }",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            TextButton(onClick = { onExpandedChange(!expanded) }) {
                Text(if (expanded) "收起" else "展开")
            }
        }
        if (expanded) {
            orderedInputs.forEach { (originalIndex, input) ->
                ProjectGitHubWorkflowInputCard(
                    input = input,
                    originalIndex = originalIndex,
                    onInputKeyChange = onInputKeyChange,
                    onInputValueChange = onInputValueChange,
                    onRemoveInput = onRemoveInput
                )
            }
        } else {
            Text(
                text = buildString {
                    append("已收起")
                    if (summary.totalCount > 0) {
                        append("，当前共有 ")
                        append(summary.totalCount)
                        append(" 个参数")
                    }
                    if (summary.missingRequiredCount > 0) {
                        append("，其中 ")
                        append(summary.missingRequiredCount)
                        append(" 个必填项仍未填写")
                        summary.missingRequiredKeys.take(3).takeIf { it.isNotEmpty() }?.let { keys ->
                            append("：")
                            append(keys.joinToString("、"))
                            if (summary.missingRequiredKeys.size > keys.size) {
                                append(" 等")
                            }
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProjectGitHubWorkflowInputCard(
    input: ProjectGitHubWorkflowDispatchInputUi,
    originalIndex: Int,
    onInputKeyChange: (Int, String) -> Unit,
    onInputValueChange: (Int, String) -> Unit,
    onRemoveInput: (Int) -> Unit
) {
    val hasMissingRequiredValue = remember(input) {
        buildProjectGitHubWorkflowInputIsMissingRequiredValue(input)
    }
    val quickOptions = if (input.options.isNotEmpty()) {
        input.options
    } else if (input.type.equals("boolean", ignoreCase = true)) {
        listOf("true", "false")
    } else {
        emptyList()
    }
    val quickFillSuggestion = remember(input, quickOptions) {
        buildProjectGitHubWorkflowInputQuickFillSuggestion(
            input = input,
            quickOptions = quickOptions
        )
    }
    ProjectInsetCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input.key,
                onValueChange = { onInputKeyChange(originalIndex, it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (input.autoDetected) "参数名" else "自定义参数名") },
                placeholder = { Text("例如 environment") },
                enabled = !input.autoDetected,
                singleLine = true
            )
            input.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = input.value,
                onValueChange = { onInputValueChange(originalIndex, it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("参数值") },
                isError = hasMissingRequiredValue,
                placeholder = {
                    Text(
                        input.defaultValue?.takeIf { it.isNotBlank() }
                            ?: if (quickOptions.isNotEmpty()) {
                                quickOptions.first()
                            } else {
                                "例如 production"
                            }
                    )
                },
                singleLine = true
            )
            if (hasMissingRequiredValue) {
                Text(
                    text = "这是必填参数，请填写后再运行工作流。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                quickFillSuggestion?.let { suggestion ->
                    TextButton(onClick = { onInputValueChange(originalIndex, suggestion.value) }) {
                        Text(suggestion.actionLabel)
                    }
                }
            }
            if (quickOptions.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickOptions.forEach { option ->
                        FilterChip(
                            selected = input.value == option,
                            onClick = { onInputValueChange(originalIndex, option) },
                            label = { Text(option) }
                        )
                    }
                }
            }
            Text(
                text = buildString {
                    append(if (input.required) "必填" else "可选")
                    if (input.type.isNotBlank()) {
                        append(" · 类型 ")
                        append(input.type)
                    }
                    input.defaultValue?.takeIf { it.isNotBlank() }?.let {
                        append(" · 默认值 ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (input.required) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!input.autoDetected) {
                    OutlinedButton(onClick = { onRemoveInput(originalIndex) }) {
                        Text("删除参数")
                    }
                }
            }
        }
    }
}

private fun buildProjectGitHubWorkflowInputSectionSummary(
    inputs: List<ProjectGitHubWorkflowDispatchInputUi>
): ProjectGitHubWorkflowInputSectionSummary {
    val filledCount = inputs.count { input ->
        buildProjectGitHubWorkflowInputHasEffectiveValue(input)
    }
    val missingRequiredInputs = inputs.filter { input ->
        buildProjectGitHubWorkflowInputIsMissingRequiredValue(input)
    }
    return ProjectGitHubWorkflowInputSectionSummary(
        totalCount = inputs.size,
        filledCount = filledCount,
        missingRequiredCount = missingRequiredInputs.size,
        missingRequiredKeys = missingRequiredInputs.map { it.key }
    )
}

private fun buildProjectGitHubWorkflowInputHasEffectiveValue(
    input: ProjectGitHubWorkflowDispatchInputUi
): Boolean {
    return input.value.trim().ifBlank { input.defaultValue?.trim().orEmpty() }.isNotBlank()
}

private fun buildProjectGitHubWorkflowInputIsMissingRequiredValue(
    input: ProjectGitHubWorkflowDispatchInputUi
): Boolean {
    return input.required &&
        input.key.isNotBlank() &&
        !buildProjectGitHubWorkflowInputHasEffectiveValue(input)
}

private fun buildProjectGitHubWorkflowInputQuickFillSuggestion(
    input: ProjectGitHubWorkflowDispatchInputUi,
    quickOptions: List<String>
): ProjectGitHubWorkflowInputQuickFillSuggestion? {
    val defaultValue = input.defaultValue?.trim().orEmpty()
    if (defaultValue.isNotBlank()) {
        return ProjectGitHubWorkflowInputQuickFillSuggestion(
            value = defaultValue,
            actionLabel = "使用默认值"
        )
    }
    val firstOption = quickOptions.firstOrNull()?.trim().orEmpty()
    if (firstOption.isNotBlank()) {
        return ProjectGitHubWorkflowInputQuickFillSuggestion(
            value = firstOption,
            actionLabel = "使用首个选项"
        )
    }
    return null
}
