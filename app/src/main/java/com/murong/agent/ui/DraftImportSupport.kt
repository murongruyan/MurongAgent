package com.murong.agent.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.GlobalRule
import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.SkillRunAs
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpConfigSource
import com.murong.agent.core.mcp.McpTransportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

internal data class DraftImportParseResult<T>(
    val items: List<T>,
    val feedback: String? = null
)

private val draftImportJson = Json { ignoreUnknownKeys = true }
private val fencedJsonRegex = Regex("```(?:[a-zA-Z0-9_-]+)?\\s*([\\s\\S]*?)```")
private val allowedToolFileOperationOrder = listOf("read", "list", "exists", "write", "delete", "chmod")
private val allowedToolFileOperationAliases = mapOf(
    "read" to "read",
    "cat" to "read",
    "view" to "read",
    "open" to "read",
    "list" to "list",
    "ls" to "list",
    "dir" to "list",
    "exists" to "exists",
    "exist" to "exists",
    "stat" to "exists",
    "write" to "write",
    "create" to "write",
    "append" to "write",
    "delete" to "delete",
    "remove" to "delete",
    "rm" to "delete",
    "chmod" to "chmod"
)
private val allowedToolFileAliases = setOf("file", "files", "filesystem", "fs", "local-files")
private val allowedToolCodeEditAliases = setOf("code_edit", "code-edit", "codeedit", "edit", "patch", "apply_patch")
private val allowedToolShellAliases = setOf("shell", "bash", "sh", "zsh", "pwsh", "powershell", "terminal", "command", "cmd")
private val allowedToolWebAliases = setOf(
    "web",
    "browser",
    "browse",
    "search",
    "fetch",
    "web_search",
    "web-search",
    "websearch",
    "web_fetch",
    "web-fetch",
    "webfetch"
)

data class SkillAllowedToolsPresentation(
    val normalized: List<String>,
    val builtin: List<String>,
    val mcpTools: List<String>
)

fun normalizeSkillAllowedTools(raw: String): List<String> {
    return normalizeAllowedToolsCompat(splitAllowedToolsCompat(raw))
}

fun sanitizeSkillAllowedTools(rawTokens: List<String>): List<String> {
    return normalizeAllowedToolsCompat(rawTokens)
}

fun buildSkillAllowedToolsPresentation(allowedTools: List<String>): SkillAllowedToolsPresentation {
    val normalized = sanitizeSkillAllowedTools(allowedTools)
    return SkillAllowedToolsPresentation(
        normalized = normalized,
        builtin = normalized.filter(::isBuiltinSkillAllowedToolToken),
        mcpTools = normalized.filterNot(::isBuiltinSkillAllowedToolToken)
    )
}

fun formatSkillAllowedToolsSummary(allowedTools: List<String>): String {
    val presentation = buildSkillAllowedToolsPresentation(allowedTools)
    if (presentation.normalized.isEmpty()) return "默认读写预算"
    return buildList {
        if (presentation.builtin.isNotEmpty()) {
            add("内置=${presentation.builtin.joinToString(",")}")
        }
        if (presentation.mcpTools.isNotEmpty()) {
            add("MCP=${presentation.mcpTools.joinToString(",")}")
        }
    }.joinToString(" · ")
}

@Composable
fun SkillAllowedToolsBudgetView(
    allowedTools: List<String>,
    modifier: Modifier = Modifier,
    title: String = "归一化预算",
    fontSize: TextUnit = 10.sp
) {
    val presentation = buildSkillAllowedToolsPresentation(allowedTools)
    if (presentation.normalized.isEmpty()) {
        Text(
            text = "$title: 默认读写预算",
            modifier = modifier,
            fontSize = fontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            fontSize = fontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (presentation.builtin.isNotEmpty()) {
            SkillAllowedToolsBudgetGroup(
                label = "内置预算",
                items = presentation.builtin,
                fontSize = fontSize,
                emphasize = false
            )
        }
        if (presentation.mcpTools.isNotEmpty()) {
            SkillAllowedToolsBudgetGroup(
                label = "MCP 工具",
                items = presentation.mcpTools,
                fontSize = fontSize,
                emphasize = true
            )
        }
    }
}

@Composable
private fun SkillAllowedToolsBudgetGroup(
    label: String,
    items: List<String>,
    fontSize: TextUnit,
    emphasize: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            fontSize = fontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEach { item ->
                SkillAllowedToolsBudgetChip(
                    label = item,
                    fontSize = fontSize,
                    emphasize = emphasize
                )
            }
        }
    }
}

@Composable
private fun SkillAllowedToolsBudgetChip(
    label: String,
    fontSize: TextUnit,
    emphasize: Boolean
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (emphasize) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        }
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = fontSize,
            color = if (emphasize) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun RuleDraftImportCard(
    onImportDrafts: (List<GlobalRule>) -> Unit,
    modifier: Modifier = Modifier,
    buttonLabel: String = "导入规则"
) {
    var expanded by remember { mutableStateOf(false) }
    var draftText by remember { mutableStateOf("") }
    var previewItems by remember { mutableStateOf<List<GlobalRule>>(emptyList()) }
    var feedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起导入" else buttonLabel, fontSize = 12.sp)
        }

        AnimatedVisibility(visible = expanded) {
            DraftImportCardShell(
                title = "规则导入",
                hint = "支持 Markdown frontmatter + 正文，也支持对象数组 JSON 和单个对象。",
                draftText = draftText,
                onDraftTextChange = {
                    draftText = it
                    previewItems = emptyList()
                    feedback = null
                },
                feedback = feedback,
                feedbackIsError = previewItems.isEmpty(),
                previewLabels = previewItems.map(::buildRuleDraftPreviewLabel),
                importButtonLabel = "确认导入 ${previewItems.size} 条规则",
                onParse = {
                    val result = parseRuleDrafts(draftText)
                    previewItems = result.items
                    feedback = result.feedback
                },
                onImport = {
                    if (previewItems.isNotEmpty()) {
                        onImportDrafts(previewItems)
                        draftText = ""
                        previewItems = emptyList()
                        feedback = "已导入规则"
                        expanded = false
                    }
                }
            )
        }
    }
}

@Composable
fun MemoryDraftImportCard(
    onImportDrafts: (List<GlobalMemory>) -> Unit,
    modifier: Modifier = Modifier,
    buttonLabel: String = "导入记忆"
) {
    var expanded by remember { mutableStateOf(false) }
    var draftText by remember { mutableStateOf("") }
    var previewItems by remember { mutableStateOf<List<GlobalMemory>>(emptyList()) }
    var feedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起导入" else buttonLabel, fontSize = 12.sp)
        }

        AnimatedVisibility(visible = expanded) {
            DraftImportCardShell(
                title = "记忆导入",
                hint = "支持 Markdown frontmatter + 正文，也支持对象数组 JSON 和单个对象。",
                draftText = draftText,
                onDraftTextChange = {
                    draftText = it
                    previewItems = emptyList()
                    feedback = null
                },
                feedback = feedback,
                feedbackIsError = previewItems.isEmpty(),
                previewLabels = previewItems.map(::buildMemoryDraftPreviewLabel),
                importButtonLabel = "确认导入 ${previewItems.size} 条记忆",
                onParse = {
                    val result = parseMemoryDrafts(draftText)
                    previewItems = result.items
                    feedback = result.feedback
                },
                onImport = {
                    if (previewItems.isNotEmpty()) {
                        onImportDrafts(previewItems)
                        draftText = ""
                        previewItems = emptyList()
                        feedback = "已导入记忆"
                        expanded = false
                    }
                }
            )
        }
    }
}

@Composable
fun McpDraftImportCard(
    onImportDrafts: (List<McpServerConfig>) -> Unit,
    modifier: Modifier = Modifier,
    buttonLabel: String = "导入 MCP 草案"
) {
    var expanded by remember { mutableStateOf(false) }
    var draftText by remember { mutableStateOf("") }
    var previewItems by remember { mutableStateOf<List<McpServerConfig>>(emptyList()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var importedSourcePath by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起草案导入" else buttonLabel, fontSize = 12.sp)
        }

        AnimatedVisibility(visible = expanded) {
            DraftImportCardShell(
                title = "MCP 草案导入",
                hint = "支持 Markdown frontmatter、桌面端 `.mcp.json` / `mcpServers` JSON，也兼容原始 spec 字符串，如 `fs=npx -y @modelcontextprotocol/server-filesystem .`。",
                draftText = draftText,
                onDraftTextChange = {
                    draftText = it
                    importedSourcePath = null
                    previewItems = emptyList()
                    feedback = null
                },
                onDocumentImported = { importedText, sourcePath ->
                    draftText = importedText
                    importedSourcePath = sourcePath
                    previewItems = emptyList()
                    feedback = null
                },
                feedback = feedback,
                feedbackIsError = previewItems.isEmpty(),
                previewLabels = previewItems.map(::buildMcpDraftPreviewLabel),
                importButtonLabel = "确认导入 ${previewItems.size} 个 MCP",
                onParse = {
                    val result = parseMcpServerDrafts(draftText, importedSourcePath)
                    previewItems = result.items
                    feedback = result.feedback
                },
                onImport = {
                    if (previewItems.isNotEmpty()) {
                        onImportDrafts(previewItems)
                        draftText = ""
                        importedSourcePath = null
                        previewItems = emptyList()
                        feedback = "已导入草案"
                        expanded = false
                    }
                }
            )
        }
    }
}

@Composable
fun SkillDraftImportCard(
    onImportDrafts: (List<GlobalSkill>) -> Unit,
    modifier: Modifier = Modifier,
    buttonLabel: String = "导入 Skill 草案"
) {
    var expanded by remember { mutableStateOf(false) }
    var draftText by remember { mutableStateOf("") }
    var previewItems by remember { mutableStateOf<List<GlobalSkill>>(emptyList()) }
    var feedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起草案导入" else buttonLabel, fontSize = 12.sp)
        }

        AnimatedVisibility(visible = expanded) {
            DraftImportCardShell(
                title = "Skill 草案导入",
                hint = "支持 Skill Markdown frontmatter + 正文，也兼容对象数组 JSON 和单个对象。",
                draftText = draftText,
                onDraftTextChange = {
                    draftText = it
                    previewItems = emptyList()
                    feedback = null
                },
                feedback = feedback,
                feedbackIsError = previewItems.isEmpty(),
                previewLabels = emptyList(),
                previewContent = {
                    previewItems.forEach { skill ->
                        SkillDraftPreviewCard(skill)
                    }
                },
                importButtonLabel = "确认导入 ${previewItems.size} 个 Skill",
                onParse = {
                    val result = parseSkillDrafts(draftText)
                    previewItems = result.items
                    feedback = result.feedback
                },
                onImport = {
                    if (previewItems.isNotEmpty()) {
                        onImportDrafts(previewItems)
                        draftText = ""
                        previewItems = emptyList()
                        feedback = "已导入草案"
                        expanded = false
                    }
                }
            )
        }
    }
}

@Composable
private fun DraftImportCardShell(
    title: String,
    hint: String,
    draftText: String,
    onDraftTextChange: (String) -> Unit,
    onDocumentImported: ((String, String?) -> Unit)? = null,
    feedback: String?,
    feedbackIsError: Boolean,
    previewLabels: List<String>,
    previewContent: (@Composable () -> Unit)? = null,
    importButtonLabel: String,
    onParse: () -> Unit,
    onImport: () -> Unit
) {
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val importedText = uri?.let { selectedUri ->
            runCatching {
                context.contentResolver.openInputStream(selectedUri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
        }.orEmpty()
        if (importedText.isNotBlank()) {
            val sourcePath = uri?.let { resolveImportedDocumentSourcePath(context, it) }
            onDocumentImported?.invoke(importedText, sourcePath) ?: onDraftTextChange(importedText)
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = draftText,
                onValueChange = onDraftTextChange,
                label = { Text("草案内容（支持 Markdown / JSON）") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                minLines = 6,
                maxLines = 12,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onParse, enabled = draftText.isNotBlank()) {
                    Text("解析草案", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    filePicker.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
                }) {
                    Text("选择文件", fontSize = 12.sp)
                }
                if (draftText.isNotBlank() || previewLabels.isNotEmpty() || feedback != null) {
                    TextButton(onClick = onDraftTextChange.clearingAction()) {
                        Text("清空", fontSize = 12.sp)
                    }
                }
            }
            feedback?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (feedbackIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (previewLabels.isNotEmpty() || previewContent != null) {
                Text(
                    text = "导入预览",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (previewContent != null) {
                    previewContent()
                } else {
                    previewLabels.forEach { label ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                FilledTonalButton(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(importButtonLabel, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun ((String) -> Unit).clearingAction(): () -> Unit = { invoke("") }

internal fun parseRuleDrafts(raw: String): DraftImportParseResult<GlobalRule> {
    val markdownRule = parseMarkdownRule(raw)
    if (markdownRule != null) {
        return DraftImportParseResult(
            items = listOf(markdownRule),
            feedback = "已按 Markdown 解析 1 条规则。"
        )
    }
    val parsed = parseDraftRoot(raw) ?: return DraftImportParseResult(
        items = emptyList(),
        feedback = "草案不是合法 JSON，也不是可识别的 Markdown 规则。"
    )
    val entries = unwrapDraftEntries(parsed, "rules", "items", "drafts")
    var invalidCount = 0
    val items = entries.mapNotNull { element ->
        parseRuleDraft(element).getOrElse {
            invalidCount += 1
            null
        }
    }.distinctBy { it.id }
    return buildParseResult(
        items = items,
        invalidCount = invalidCount,
        successLabel = "规则"
    )
}

internal fun parseMemoryDrafts(raw: String): DraftImportParseResult<GlobalMemory> {
    val markdownMemory = parseMarkdownMemory(raw)
    if (markdownMemory != null) {
        return DraftImportParseResult(
            items = listOf(markdownMemory),
            feedback = "已按 Markdown 解析 1 条记忆。"
        )
    }
    val parsed = parseDraftRoot(raw) ?: return DraftImportParseResult(
        items = emptyList(),
        feedback = "草案不是合法 JSON，也不是可识别的 Markdown 记忆。"
    )
    val entries = unwrapDraftEntries(parsed, "memories", "items", "drafts")
    var invalidCount = 0
    val items = entries.mapNotNull { element ->
        parseMemoryDraft(element).getOrElse {
            invalidCount += 1
            null
        }
    }.distinctBy { it.id }
    return buildParseResult(
        items = items,
        invalidCount = invalidCount,
        successLabel = "记忆"
    )
}

internal fun parseMcpServerDrafts(raw: String): DraftImportParseResult<McpServerConfig> {
    return parseMcpServerDrafts(raw, importedSourcePath = null)
}

internal fun parseMcpServerDrafts(
    raw: String,
    importedSourcePath: String?
): DraftImportParseResult<McpServerConfig> {
    val markdownMcp = parseMarkdownMcpServer(raw)
    if (markdownMcp != null) {
        val normalized = applyImportedMcpSourceFallbacks(listOf(markdownMcp), importedSourcePath)
        return DraftImportParseResult(
            items = normalized,
            feedback = "已按 Markdown 解析 1 条 MCP 草案。"
        )
    }
    val rawSpecItems = parseMcpSpecLines(raw)
    if (rawSpecItems.isNotEmpty()) {
        val normalized = applyImportedMcpSourceFallbacks(rawSpecItems.distinctBy { it.name }, importedSourcePath)
        return DraftImportParseResult(
            items = normalized,
            feedback = "已按桌面端 MCP spec 解析 ${rawSpecItems.size} 条草案。"
        )
    }
    val parsed = parseDraftRoot(raw) ?: return DraftImportParseResult(
        items = emptyList(),
        feedback = "草案不是合法 JSON，也不是可识别的桌面端 MCP spec。"
    )
    val entries = unwrapMcpDraftEntries(parsed)
    var invalidCount = 0
    val items = entries.mapNotNull { element ->
        parseMcpServerDraft(element).getOrElse {
            invalidCount += 1
            null
        }
    }.distinctBy { it.name }
    return buildParseResult(
        items = applyImportedMcpSourceFallbacks(items, importedSourcePath),
        invalidCount = invalidCount,
        successLabel = "MCP 草案"
    )
}

internal fun parseSkillDrafts(raw: String): DraftImportParseResult<GlobalSkill> {
    val markdownSkill = parseDesktopSkillMarkdown(raw)
    if (markdownSkill != null) {
        return DraftImportParseResult(
            items = listOf(markdownSkill),
            feedback = "已按桌面端 Skill markdown 解析 1 条草案。"
        )
    }
    val parsed = parseDraftRoot(raw) ?: return DraftImportParseResult(
        items = emptyList(),
        feedback = "草案不是合法 JSON，也不是可识别的桌面端 Skill markdown。"
    )
    val entries = unwrapDraftEntries(parsed, "skills", "items", "drafts")
    var invalidCount = 0
    val items = entries.mapNotNull { element ->
        parseSkillDraft(element).getOrElse {
            invalidCount += 1
            null
        }
    }.distinctBy { it.id }
    return buildParseResult(
        items = items,
        invalidCount = invalidCount,
        successLabel = "Skill 草案"
    )
}

private fun unwrapMcpDraftEntries(root: JsonElement): List<JsonElement> {
    if (root !is JsonObject) {
        return unwrapDraftEntries(root, "servers", "mcpServers", "items", "drafts")
    }
    val grouped = sequenceOf("servers", "mcpServers", "items", "drafts")
        .mapNotNull { key -> root[key] }
        .firstOrNull()
    return when (grouped) {
        is JsonArray -> grouped.toList()
        is JsonObject -> grouped.entries.map { (name, value) ->
            if (value is JsonObject && "name" !in value) {
                JsonObject(value + ("name" to JsonPrimitive(name)))
            } else {
                value
            }
        }
        null -> listOf(root)
        else -> emptyList()
    }
}

private fun <T> buildParseResult(
    items: List<T>,
    invalidCount: Int,
    successLabel: String
): DraftImportParseResult<T> {
    if (items.isEmpty()) {
        return DraftImportParseResult(
            items = emptyList(),
            feedback = "没有解析到可导入的 $successLabel，请检查必填字段是否完整。"
        )
    }
    val feedback = when {
        invalidCount > 0 -> "已解析 ${items.size} 条 $successLabel，忽略 $invalidCount 条格式不完整草案。"
        else -> "已解析 ${items.size} 条 $successLabel。"
    }
    return DraftImportParseResult(items = items, feedback = feedback)
}

private fun parseDraftRoot(raw: String): JsonElement? {
    val sanitized = sanitizeDraftPayload(raw)
    if (sanitized.isBlank()) return null
    return runCatching { draftImportJson.parseToJsonElement(sanitized) }.getOrNull()
}

private fun sanitizeDraftPayload(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    val fencedMatch = fencedJsonRegex.find(trimmed)
    return fencedMatch?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { trimmed }
}

private fun parseDesktopMcpSpec(input: String): Result<McpServerConfig> = runCatching {
    val trimmed = input.trim()
    require(trimmed.isNotBlank()) { "empty spec" }
    val nameMatch = Regex("^([a-zA-Z_][a-zA-Z0-9_-]*)=(.*)$").find(trimmed)
    val name = nameMatch?.groupValues?.getOrNull(1)
    val body = (nameMatch?.groupValues?.getOrNull(2) ?: trimmed).trim()
    require(body.isNotBlank()) { "missing spec body" }
    val streamableMatch = Regex("^streamable\\+(https?://.+)$", RegexOption.IGNORE_CASE).find(body)
    if (streamableMatch != null) {
        McpServerConfig(
            name = name ?: inferMcpNameFromUrl(streamableMatch.groupValues[1]) ?: "imported-mcp",
            transport = McpTransportType.STREAMABLE_HTTP,
            url = streamableMatch.groupValues[1],
            source = McpConfigSource.IMPORTED_DRAFT
        )
    } else if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
        McpServerConfig(
            name = name ?: inferMcpNameFromUrl(body) ?: "imported-mcp",
            transport = McpTransportType.SSE,
            url = body,
            source = McpConfigSource.IMPORTED_DRAFT
        )
    } else {
        val argv = splitCommandLine(body)
        require(argv.isNotEmpty()) { "missing command" }
        McpServerConfig(
            name = name ?: argv.first(),
            transport = McpTransportType.STDIO,
            command = argv.first(),
            args = argv.drop(1),
            source = McpConfigSource.IMPORTED_DRAFT
        )
    }
}

private fun parseMcpSpecLines(raw: String): List<McpServerConfig> {
    val sanitized = sanitizeDraftPayload(raw)
    val lines = sanitized
        .split(Regex("\\r?\\n"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()
    if (lines.any { it.startsWith("{") || it.startsWith("[") }) return emptyList()
    return lines.mapNotNull { parseDesktopMcpSpec(it).getOrNull() }
}

private fun unwrapDraftEntries(root: JsonElement, vararg collectionKeys: String): List<JsonElement> {
    return when (root) {
        is JsonArray -> root
        is JsonObject -> {
            val groupedItems = collectionKeys.asSequence()
                .mapNotNull { key -> root[key] }
                .firstNotNullOfOrNull { element ->
                    when (element) {
                        is JsonArray -> element.toList()
                        is JsonObject -> listOf(element)
                        else -> null
                    }
                }
            groupedItems ?: listOf(root)
        }

        else -> emptyList()
    }
}

private fun parseMcpServerDraft(element: JsonElement): Result<McpServerConfig> = runCatching {
    val obj = element.jsonObject
    val name = obj.string("name")
        ?: obj.string("serverName")
        ?: error("missing name")
    val transport = parseTransport(
        obj.string("transport")
            ?: obj.string("transportType")
            ?: obj.string("type")
    )
    val command = obj.string("command").orEmpty()
    val args = obj.stringList("args")
        ?: obj.string("arguments")
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
        ?: emptyList()
    val cwd = obj.string("cwd").orEmpty()
    val env = obj.stringMap("env")
    val url = obj.string("url")
        ?: obj.string("sseUrl")
        ?: obj.string("endpoint")
        ?: ""
    val headers = obj.stringMap("headers")
    val requestTimeoutMs = obj.long("requestTimeoutMs")
    val source = parseMcpConfigSource(
        obj.string("source"),
        fallback = if (obj.containsKey("mcpServers")) McpConfigSource.MCP_JSON else McpConfigSource.IMPORTED_DRAFT
    )
    val sourcePath = obj.string("sourcePath")
        ?: obj.string("source_path")
        ?: obj.string("file")
        ?: ""
    val trustedReadOnlyTools = normalizeMcpTrustedReadOnlyTools(obj)
    val autoStart = obj.boolean("autoStart")
        ?: obj.boolean("autostart")
        ?: obj.boolean("auto_start")
        ?: true
    when (transport) {
        McpTransportType.STDIO -> require(command.isNotBlank()) { "stdio command required" }
        McpTransportType.SSE,
        McpTransportType.STREAMABLE_HTTP -> require(url.isNotBlank()) { "remote url required" }
    }
    McpServerConfig(
        name = name,
        transport = transport,
        command = command,
        args = args,
        cwd = cwd,
        env = env,
        url = url,
        headers = headers,
        requestTimeoutMs = requestTimeoutMs,
        source = source,
        sourcePath = sourcePath,
        trustedReadOnlyTools = trustedReadOnlyTools,
        autoStart = autoStart,
        enabled = obj.boolean("enabled") ?: true
    )
}

private fun parseRuleDraft(element: JsonElement): Result<GlobalRule> = runCatching {
    val obj = element.jsonObject
    val title = obj.string("title")
        ?: obj.string("name")
        ?: "导入规则"
    val content = obj.string("content")
        ?: obj.string("body")
        ?: obj.string("instruction")
        ?: obj.string("prompt")
        ?: error("missing content")
    GlobalRule(
        id = obj.string("id").orEmpty().ifBlank { UUID.randomUUID().toString().take(8) },
        title = title,
        content = content,
        enabled = obj.boolean("enabled") ?: true
    )
}

private fun parseMemoryDraft(element: JsonElement): Result<GlobalMemory> = runCatching {
    val obj = element.jsonObject
    val title = obj.string("title")
        ?: obj.string("name")
        ?: "导入记忆"
    val content = obj.string("content")
        ?: obj.string("body")
        ?: obj.string("note")
        ?: error("missing content")
    GlobalMemory(
        id = obj.string("id").orEmpty().ifBlank { UUID.randomUUID().toString().take(8) },
        title = title,
        content = content,
        enabled = obj.boolean("enabled") ?: true
    )
}

private fun parseSkillDraft(element: JsonElement): Result<GlobalSkill> = runCatching {
    val obj = element.jsonObject
    val title = obj.string("title").orEmpty()
    val description = obj.string("description").orEmpty()
    val content = obj.string("content")
        ?: obj.string("prompt")
        ?: obj.string("prompt")
        ?: obj.string("template")
        ?: ""
    require(title.isNotBlank() || description.isNotBlank() || content.isNotBlank()) {
        "skill content required"
    }
    GlobalSkill(
        id = obj.string("id").orEmpty().ifBlank { UUID.randomUUID().toString().take(8) },
        title = title.ifBlank { "导入 Skill" },
        description = description,
        content = content,
        runAs = if (obj.string("runAs")?.trim()?.equals("subagent", ignoreCase = true) == true) {
            SkillRunAs.SUBAGENT
        } else {
            SkillRunAs.INLINE
        },
        allowedTools = normalizeAllowedToolsCompat(
            obj.stringList("allowedTools").orEmpty() +
                obj.stringList("allowed-tools").orEmpty() +
                listOfNotNull(
                    obj.string("allowedTools"),
                    obj.string("allowed-tools"),
                    obj.string("allowed_tools")
                )
        ),
        preferredModel = obj.string("preferredModel")
            ?: obj.string("model")
            ?: "",
        enabled = obj.boolean("enabled") ?: true
    )
}

private fun parseMarkdownRule(raw: String): GlobalRule? {
    return parseMarkdownTextEntry(
        raw = raw,
        defaultTitle = "导入规则"
    )?.let { parsed ->
        GlobalRule(
            id = UUID.randomUUID().toString().take(8),
            title = parsed.title,
            content = parsed.content,
            enabled = parsed.enabled
        )
    }
}

private fun parseMarkdownMemory(raw: String): GlobalMemory? {
    return parseMarkdownTextEntry(
        raw = raw,
        defaultTitle = "导入记忆"
    )?.let { parsed ->
        GlobalMemory(
            id = UUID.randomUUID().toString().take(8),
            title = parsed.title,
            content = parsed.content,
            enabled = parsed.enabled
        )
    }
}

private fun parseDesktopSkillMarkdown(raw: String): GlobalSkill? {
    val sanitized = raw.trimStart('\uFEFF').trim()
    if (sanitized.isBlank()) return null
    if (sanitized.startsWith("{") || sanitized.startsWith("[")) return null
    val parsed = parseFrontmatterCompat(sanitized)
    val body = parsed.body.trim()
    val title = parsed.data["title"]
        ?.takeIf { it.isNotBlank() }
        ?: parsed.data["name"]
            ?.takeIf { it.isNotBlank() }
    val runAs = parseRunAsCompat(parsed.data["runAs"], parsed.data["context"], parsed.data["agent"])
    val allowedTools = parseAllowedToolsCompat(parsed.data["allowed-tools"])
    val preferredModel = parsed.data["model"].orEmpty()
    val description = buildSkillDescription(
        baseDescription = parsed.data["description"].orEmpty(),
        runAs = runAs,
        allowedTools = allowedTools,
        model = preferredModel
    )
    if (title == null && description.isBlank() && body.isBlank()) return null
    return GlobalSkill(
        id = UUID.randomUUID().toString().take(8),
        title = title ?: "导入 Skill",
        description = description,
        content = body,
        runAs = if (runAs == "subagent") SkillRunAs.SUBAGENT else SkillRunAs.INLINE,
        allowedTools = allowedTools,
        preferredModel = preferredModel,
        enabled = true
    )
}

private fun parseMarkdownMcpServer(raw: String): McpServerConfig? {
    val sanitized = raw.trimStart('\uFEFF').trim()
    if (sanitized.isBlank()) return null
    if (sanitized.startsWith("{") || sanitized.startsWith("[")) return null
    val parsed = parseFrontmatterCompat(sanitized)
    val body = parsed.body.trim()
    val title = parsed.data["title"]
        ?.takeIf { it.isNotBlank() }
        ?: parsed.data["name"]?.takeIf { it.isNotBlank() }
    val transport = parseTransportOrNull(
        parsed.data["transport"]
            ?: parsed.data["transportType"]
            ?: parsed.data["type"]
    )
    val explicitUrl = parsed.data["url"]
        ?: parsed.data["sseUrl"]
        ?: parsed.data["endpoint"]
    val commandLine = when {
        parsed.data["command"].isNullOrBlank().not() -> listOfNotNull(
            parsed.data["command"],
            parsed.data["args"]
        ).joinToString(" ")
        body.isNotBlank() -> body.lineSequence().firstOrNull()?.trim().orEmpty()
        else -> ""
    }
    val config = when {
        explicitUrl.orEmpty().isNotBlank() -> {
            val normalizedTransport = transport ?: inferRemoteTransport(explicitUrl.orEmpty())
            McpServerConfig(
                name = title ?: inferMcpNameFromUrl(explicitUrl.orEmpty()) ?: "imported-mcp",
                transport = normalizedTransport,
                url = explicitUrl.orEmpty(),
                headers = parseKeyValueLines(parsed.data["headers"]),
                requestTimeoutMs = parsed.data["requestTimeoutMs"]?.toLongOrNull(),
                source = parseMcpConfigSource(parsed.data["source"], McpConfigSource.IMPORTED_DRAFT),
                sourcePath = parsed.data["sourcePath"].orEmpty(),
                trustedReadOnlyTools = parseAllowedToolsCompat(parsed.data["trusted_read_only_tools"]),
                autoStart = parsed.data["autoStart"]?.toBooleanStrictOrNull() ?: true,
                enabled = parsed.data["enabled"]?.toBooleanStrictOrNull() ?: true
            )
        }

        commandLine.isNotBlank() -> {
            val parsedSpec = parseDesktopMcpSpec(commandLine).getOrNull() ?: return null
            parsedSpec.copy(
                name = title ?: parsedSpec.name,
                cwd = parsed.data["cwd"].orEmpty().ifBlank { parsedSpec.cwd },
                env = parseKeyValueLines(parsed.data["env"]).ifEmpty { parsedSpec.env },
                headers = parseKeyValueLines(parsed.data["headers"]).ifEmpty { parsedSpec.headers },
                requestTimeoutMs = parsed.data["requestTimeoutMs"]?.toLongOrNull() ?: parsedSpec.requestTimeoutMs,
                source = parseMcpConfigSource(parsed.data["source"], McpConfigSource.IMPORTED_DRAFT),
                sourcePath = parsed.data["sourcePath"].orEmpty(),
                trustedReadOnlyTools = parseAllowedToolsCompat(parsed.data["trusted_read_only_tools"]),
                autoStart = parsed.data["autoStart"]?.toBooleanStrictOrNull() ?: parsedSpec.autoStart,
                enabled = parsed.data["enabled"]?.toBooleanStrictOrNull() ?: parsedSpec.enabled
            )
        }

        else -> null
    }
    return config
}

private fun parseTransport(raw: String?): McpTransportType {
    return when (raw?.trim()?.lowercase()) {
        null, "", "stdio" -> McpTransportType.STDIO
        "sse" -> McpTransportType.SSE
        "streamable-http", "http" -> McpTransportType.STREAMABLE_HTTP
        else -> error("unknown transport")
    }
}

private fun parseTransportOrNull(raw: String?): McpTransportType? {
    return when (raw?.trim()?.lowercase()) {
        null, "" -> null
        "stdio" -> McpTransportType.STDIO
        "sse" -> McpTransportType.SSE
        "streamable-http", "streamable_http", "http" -> McpTransportType.STREAMABLE_HTTP
        else -> null
    }
}

private fun inferRemoteTransport(url: String): McpTransportType {
    return if (url.contains("/mcp", ignoreCase = true) || url.contains("streamable+", ignoreCase = true)) {
        McpTransportType.STREAMABLE_HTTP
    } else {
        McpTransportType.SSE
    }
}

private fun parseMcpConfigSource(raw: String?, fallback: McpConfigSource): McpConfigSource {
    return when (raw?.trim()?.lowercase()) {
        "manual" -> McpConfigSource.MANUAL
        "mcp_json", "mcp-json", ".mcp.json" -> McpConfigSource.MCP_JSON
        "imported_draft", "imported-draft", "draft" -> McpConfigSource.IMPORTED_DRAFT
        else -> fallback
    }
}

private fun normalizeMcpTrustedReadOnlyTools(obj: JsonObject): List<String> {
    return normalizeAllowedToolsCompat(
        obj.stringList("trustedReadOnlyTools").orEmpty() +
            obj.stringList("trusted_read_only_tools").orEmpty() +
            listOfNotNull(
                obj.string("trustedReadOnlyTools"),
                obj.string("trusted_read_only_tools")
            )
    )
}

private data class ParsedMarkdownTextEntry(
    val title: String,
    val content: String,
    val enabled: Boolean
)

private fun parseMarkdownTextEntry(raw: String, defaultTitle: String): ParsedMarkdownTextEntry? {
    val sanitized = raw.trimStart('\uFEFF').trim()
    if (sanitized.isBlank()) return null
    if (sanitized.startsWith("{") || sanitized.startsWith("[")) return null
    val parsed = parseFrontmatterCompat(sanitized)
    val body = parsed.body.trim()
    val title = parsed.data["title"]
        ?.takeIf { it.isNotBlank() }
        ?: parsed.data["name"]?.takeIf { it.isNotBlank() }
        ?: sanitized.lineSequence()
            .firstOrNull()
            ?.removePrefix("#")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "---" }
        ?: defaultTitle
    val enabled = parsed.data["enabled"]?.toBooleanStrictOrNull() ?: true
    if (body.isBlank() && title == defaultTitle) return null
    val content = body.ifBlank {
        sanitized.lineSequence()
            .drop(1)
            .joinToString("\n")
            .trim()
    }
    if (content.isBlank()) return null
    return ParsedMarkdownTextEntry(
        title = title,
        content = content,
        enabled = enabled
    )
}

private fun parseKeyValueLines(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim().removePrefix("-").trim()
            val delimiterIndex = trimmed.indexOf(':').takeIf { it >= 0 } ?: trimmed.indexOf('=').takeIf { it >= 0 }
            if (delimiterIndex == null) {
                null
            } else {
                val key = trimmed.substring(0, delimiterIndex).trim()
                val value = trimmed.substring(delimiterIndex + 1).trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }
        }
        .toMap()
}

private fun buildRuleDraftPreviewLabel(rule: GlobalRule): String {
    return buildString {
        append(rule.title.ifBlank { "未命名规则" })
        append(" · ")
        append(if (rule.enabled) "已启用" else "已停用")
        rule.content.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let {
            append(" · ")
            append(it)
        }
    }
}

private fun buildMemoryDraftPreviewLabel(memory: GlobalMemory): String {
    return buildString {
        append(memory.title.ifBlank { "未命名记忆" })
        append(" · ")
        append(if (memory.enabled) "已启用" else "已停用")
        memory.content.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let {
            append(" · ")
            append(it)
        }
    }
}

private fun buildMcpDraftPreviewLabel(config: McpServerConfig): String {
    val target = when (config.transport) {
        McpTransportType.STDIO -> listOf(config.command, config.args.joinToString(" "))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        McpTransportType.SSE -> config.url
        McpTransportType.STREAMABLE_HTTP -> config.url
    }
    return buildString {
        append(config.name)
        append(" · ")
        append(
            when (config.transport) {
                McpTransportType.STDIO -> "stdio"
                McpTransportType.SSE -> "SSE"
                McpTransportType.STREAMABLE_HTTP -> "streamable-http"
            }
        )
        target.takeIf { it.isNotBlank() }?.let {
            append(" · ")
            append(it)
        }
        if (config.cwd.isNotBlank()) {
            append(" · cwd=")
            append(config.cwd)
        }
        if (config.headers.isNotEmpty()) {
            append(" · headers=")
            append(config.headers.size)
        }
        if (config.env.isNotEmpty()) {
            append(" · env=")
            append(config.env.size)
        }
        if (config.sourcePath.isNotBlank()) {
            append(" · src=")
            append(config.sourcePath.replace('\\', '/').substringAfterLast('/'))
        }
    }
}

private fun applyImportedMcpSourceFallbacks(
    items: List<McpServerConfig>,
    importedSourcePath: String?
): List<McpServerConfig> {
    val normalizedSourcePath = importedSourcePath?.trim().orEmpty()
    if (normalizedSourcePath.isBlank()) return items
    val inferredSource = inferSourceFromImportedDocument(normalizedSourcePath)
    return items.map { config ->
        config.copy(
            source = if (config.source == McpConfigSource.IMPORTED_DRAFT && inferredSource == McpConfigSource.MCP_JSON) {
                McpConfigSource.MCP_JSON
            } else {
                config.source
            },
            sourcePath = config.sourcePath.ifBlank { normalizedSourcePath }
        )
    }
}

private fun inferSourceFromImportedDocument(sourcePath: String): McpConfigSource {
    val normalized = sourcePath.replace('\\', '/')
    return if (normalized.endsWith("/.mcp.json", ignoreCase = true) || normalized.equals(".mcp.json", ignoreCase = true)) {
        McpConfigSource.MCP_JSON
    } else {
        McpConfigSource.IMPORTED_DRAFT
    }
}

private fun resolveImportedDocumentSourcePath(context: Context, uri: Uri): String? {
    if (uri.scheme.equals("file", ignoreCase = true)) {
        return uri.path?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment
    }
    val cursor = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    ) ?: return uri.lastPathSegment
    cursor.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                return it.getString(index)
            }
        }
    }
    return uri.lastPathSegment
}

@Composable
private fun SkillDraftPreviewCard(skill: GlobalSkill) {
    val summary = skill.description.ifBlank {
        skill.content.lineSequence().firstOrNull()?.trim().orEmpty()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = skill.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (skill.enabled) "已启用" else "已停用",
                    fontSize = 11.sp,
                    color = if (skill.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (skill.runAs == SkillRunAs.SUBAGENT) {
                SkillAllowedToolsBudgetChip(
                    label = "子代理执行",
                    fontSize = 11.sp,
                    emphasize = true
                )
            }
            SkillAllowedToolsBudgetView(
                allowedTools = skill.allowedTools,
                title = "工具预算",
                fontSize = 11.sp
            )
        }
    }
}

private fun JsonObject.string(key: String): String? {
    return runCatching { get(key)?.jsonPrimitive?.contentOrNull?.trim() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

private fun JsonObject.boolean(key: String): Boolean? {
    return runCatching { get(key)?.jsonPrimitive?.booleanOrNull }.getOrNull()
}

private fun JsonObject.stringList(key: String): List<String>? {
    return runCatching {
        get(key)?.jsonArray?.mapNotNull { item ->
            item.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

private fun JsonObject.stringMap(key: String): Map<String, String> {
    return runCatching {
        get(key)?.jsonObject?.mapNotNull { (mapKey, value) ->
            value.jsonPrimitive.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { mapKey to it }
        }?.toMap()
    }.getOrNull().orEmpty()
}

private fun JsonObject.long(key: String): Long? {
    return runCatching { get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull() }.getOrNull()
}

private data class ParsedFrontmatter(
    val data: Map<String, String>,
    val body: String
)

private fun parseFrontmatterCompat(raw: String): ParsedFrontmatter {
    val stripped = raw.trimStart('\uFEFF')
    val lines = stripped.split(Regex("\\r?\\n"))
    if (lines.firstOrNull() != "---") {
        return ParsedFrontmatter(emptyMap(), stripped)
    }
    val endIndex = lines.drop(1).indexOfFirst { it == "---" }.let { if (it < 0) -1 else it + 1 }
    if (endIndex < 0) {
        return ParsedFrontmatter(emptyMap(), stripped)
    }
    val data = linkedMapOf<String, String>()
    var currentKey: String? = null
    val keyRegex = Regex("^([a-zA-Z_][a-zA-Z0-9_-]*):\\s*(.*)$")
    for (index in 1 until endIndex) {
        val line = lines[index]
        if (line.isBlank()) {
            currentKey = null
            continue
        }
        val match = keyRegex.find(line)
        if (match != null) {
            val key = match.groupValues[1]
            val value = stripWrappedQuotes(match.groupValues[2].trim())
            data[key] = value
            currentKey = key
        } else if (currentKey != null) {
            data[currentKey] = listOf(data[currentKey].orEmpty(), line.trim())
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }
    }
    val body = lines.drop(endIndex + 1).joinToString("\n").trimStart('\n')
    return ParsedFrontmatter(data, body)
}

private fun stripWrappedQuotes(value: String): String {
    if (value.length < 2) return value
    val first = value.first()
    val last = value.last()
    return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        value.substring(1, value.length - 1)
    } else {
        value
    }
}

private fun parseAllowedToolsCompat(raw: String?): List<String> {
    return normalizeAllowedToolsCompat(splitAllowedToolsCompat(raw))
}

private fun isBuiltinSkillAllowedToolToken(token: String): Boolean {
    val normalized = token.trim().lowercase()
    return normalized == "code_edit" ||
        normalized == "shell" ||
        normalized == "web_search" ||
        normalized == "web_fetch" ||
        normalized.startsWith("file(")
}

private fun normalizeAllowedToolsCompat(rawTokens: List<String>): List<String> {
    if (rawTokens.isEmpty()) return emptyList()
    val normalizedTokens = linkedSetOf<String>()
    val normalizedFileOperations = linkedSetOf<String>()
    rawTokens.forEach { rawToken ->
        val token = stripWrappedQuotes(rawToken.trim())
            .trim()
            .trim(',', ';')
            .removePrefix("-")
            .removePrefix("*")
            .trim()
        if (token.isBlank()) return@forEach
        when {
            isAllowedToolCodeEditAlias(token) -> normalizedTokens += "code_edit"
            isAllowedToolShellAlias(token) -> normalizedTokens += "shell"
            isAllowedToolWebAlias(token) -> {
                normalizedTokens += "web_search"
                normalizedTokens += "web_fetch"
            }
            isAllowedToolFileToken(token) || isAllowedToolFileOperationAlias(token) -> {
                normalizedFileOperations += parseAllowedToolFileOperations(token)
            }
            else -> normalizedTokens += token.lowercase()
        }
    }
    val orderedFileOperations = allowedToolFileOperationOrder.filter { it in normalizedFileOperations }
    return buildList {
        if (orderedFileOperations.isNotEmpty()) {
            add("file(${orderedFileOperations.joinToString("/")})")
        }
        addAll(normalizedTokens)
    }.distinct()
}

private fun splitAllowedToolsCompat(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val sanitized = raw.trim()
        .removePrefix("[")
        .removeSuffix("]")
        .replace("\r\n", "\n")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "")
        .replace(Regex("\\n\\s*[-*]\\s+"), "\n")
        .replace(Regex("\\s+-\\s+"), "\n")
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    var inSingleQuotes = false
    var inDoubleQuotes = false

    fun flushCurrent() {
        current.toString()
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let(result::add)
        current.clear()
    }

    sanitized.forEach { ch ->
        when {
            ch == '"' && !inSingleQuotes -> {
                inDoubleQuotes = !inDoubleQuotes
                current.append(ch)
            }
            ch == '\'' && !inDoubleQuotes -> {
                inSingleQuotes = !inSingleQuotes
                current.append(ch)
            }
            !inSingleQuotes && !inDoubleQuotes && (ch == '(' || ch == '[') -> {
                depth += 1
                current.append(ch)
            }
            !inSingleQuotes && !inDoubleQuotes && (ch == ')' || ch == ']') -> {
                depth = (depth - 1).coerceAtLeast(0)
                current.append(ch)
            }
            !inSingleQuotes && !inDoubleQuotes && depth == 0 && (ch == ',' || ch == ';' || ch == '；' || ch == '\n') -> {
                flushCurrent()
            }
            else -> current.append(ch)
        }
    }
    flushCurrent()
    return result
}

private fun isAllowedToolCodeEditAlias(token: String): Boolean {
    return token.trim().lowercase() in allowedToolCodeEditAliases
}

private fun isAllowedToolShellAlias(token: String): Boolean {
    return token.trim().lowercase() in allowedToolShellAliases
}

private fun isAllowedToolWebAlias(token: String): Boolean {
    return token.trim().lowercase() in allowedToolWebAliases
}

private fun isAllowedToolFileToken(token: String): Boolean {
    val normalized = token.trim().lowercase()
    return normalized in allowedToolFileAliases || normalized.startsWith("file(")
}

private fun isAllowedToolFileOperationAlias(token: String): Boolean {
    return token.trim().lowercase() in allowedToolFileOperationAliases
}

private fun parseAllowedToolFileOperations(token: String): List<String> {
    val normalized = token.trim().lowercase()
    if (normalized in allowedToolFileAliases) {
        return listOf("read", "list", "exists")
    }
    allowedToolFileOperationAliases[normalized]?.let { canonical ->
        return listOf(canonical)
    }
    if (!normalized.startsWith("file(") || !normalized.endsWith(")")) return emptyList()
    return normalized
        .removePrefix("file(")
        .removeSuffix(")")
        .split('/', ',', ';', ' ', '\n', '\t')
        .mapNotNull { segment -> allowedToolFileOperationAliases[segment.trim().lowercase()] }
        .distinct()
}

private fun parseRunAsCompat(runAs: String?, context: String?, agent: String?): String? {
    val normalizedRunAs = runAs?.trim()?.lowercase()
    if (normalizedRunAs == "subagent") return "subagent"
    if (context?.trim()?.lowercase() == "fork") return "subagent"
    if (!agent.isNullOrBlank()) return "subagent"
    return null
}

private fun buildSkillDescription(
    baseDescription: String,
    runAs: String?,
    allowedTools: List<String>,
    model: String?
): String {
    val parts = buildList {
        baseDescription.trim().takeIf { it.isNotBlank() }?.let { add(it) }
        if (runAs == "subagent") add("兼容信息: 子代理运行")
        if (allowedTools.isNotEmpty()) add("工具范围: ${allowedTools.joinToString(", ")}")
        model?.trim()?.takeIf { it.isNotBlank() }?.let { add("模型: $it") }
    }
    return parts.joinToString(" | ")
}

private fun inferMcpNameFromUrl(url: String): String? {
    return url
        .substringAfter("://", "")
        .substringBefore("/")
        .substringBefore("?")
        .substringBefore(":")
        .trim()
        .takeIf { it.isNotBlank() }
}

private fun splitCommandLine(input: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inSingleQuotes = false
    var inDoubleQuotes = false
    var escaping = false

    fun flushCurrent() {
        if (current.isNotEmpty()) {
            result += current.toString()
            current.clear()
        }
    }

    input.forEach { ch ->
        when {
            escaping -> {
                current.append(ch)
                escaping = false
            }

            ch == '\\' && !inSingleQuotes -> escaping = true
            ch == '"' && !inSingleQuotes -> inDoubleQuotes = !inDoubleQuotes
            ch == '\'' && !inDoubleQuotes -> inSingleQuotes = !inSingleQuotes
            ch.isWhitespace() && !inSingleQuotes && !inDoubleQuotes -> flushCurrent()
            else -> current.append(ch)
        }
    }
    flushCurrent()
    return result
}
