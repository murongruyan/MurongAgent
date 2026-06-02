package dev.reasonix.mobile.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.reasonix.mobile.core.config.GlobalMemory
import dev.reasonix.mobile.core.config.GlobalRule
import dev.reasonix.mobile.core.config.GlobalSkill
import dev.reasonix.mobile.core.config.ProviderConfig
import dev.reasonix.mobile.core.config.ResponseVerbosity
import dev.reasonix.mobile.core.config.SkillRunAs
import dev.reasonix.mobile.core.loop.SessionSummary
import dev.reasonix.mobile.core.mcp.McpServerConfig
import dev.reasonix.mobile.core.mcp.McpServerStatus
import dev.reasonix.mobile.core.provider.ProviderRegistry
import dev.reasonix.mobile.ui.McpDraftImportCard
import dev.reasonix.mobile.ui.normalizeSkillAllowedTools
import dev.reasonix.mobile.ui.sanitizeSkillAllowedTools
import dev.reasonix.mobile.ui.SkillAllowedToolsBudgetView
import dev.reasonix.mobile.ui.SkillDraftImportCard
import java.util.UUID

@Composable
fun SettingsScreen(
    config: ProviderConfig,
    onConfigChanged: (ProviderConfig) -> Unit,
    gitHubAuthState: GitHubAuthUiState = GitHubAuthUiState(),
    rootStatus: Boolean? = null,
    isCheckingRoot: Boolean = false,
    onCheckRoot: () -> Unit = {},
    sessions: List<SessionSummary> = emptyList(),
    balanceSyncStates: Map<String, BalanceSyncUiState> = emptyMap(),
    mcpServers: List<McpServerConfig> = emptyList(),
    mcpStatuses: List<McpServerStatus> = emptyList(),
    mcpConnectError: String? = null,
    onRefreshProviderBalance: (String) -> Unit = {},
    supportsBalanceFetch: (String) -> Boolean = { false },
    onAddMcpServer: (McpServerConfig) -> Unit = {},
    onRemoveMcpServer: (String) -> Unit = {},
    onConnectMcpServers: () -> Unit = {},
    onRefreshMcpStatus: () -> Unit = {},
    onRefreshGitHubAuthStatus: () -> Unit = {},
    onStartGitHubOAuthLogin: () -> Unit = {},
    onClearGitHubToken: () -> Unit = {}
) {
    val providers = remember { ProviderRegistry.getAllProviders() }
    var showApiKey by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    var lastOpenedGitHubAuthUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(gitHubAuthState.authorizationUrl) {
        val uri = gitHubAuthState.authorizationUrl?.trim().orEmpty()
        if (uri.isBlank() || lastOpenedGitHubAuthUrl == uri) return@LaunchedEffect
        runCatching { uriHandler.openUri(uri) }
        lastOpenedGitHubAuthUrl = uri
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ═══════════════════════════════════════
        // Root 权限检测
        // ═══════════════════════════════════════
        Text(
            text = "设备权限",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Root 权限",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            isCheckingRoot -> "检测中…"
                            rootStatus == true -> "✅ Root 可用"
                            rootStatus == false -> "❌ Root 不可用"
                            else -> "点击检测"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            rootStatus == true -> Color(0xFF4CAF50)
                            rootStatus == false -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                FilledTonalButton(
                    onClick = onCheckRoot,
                    enabled = !isCheckingRoot
                ) {
                    if (isCheckingRoot) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("检测")
                    }
                }
            }
        }

        Text(
            text = "界面与调试",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "显示调试细节",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "关闭时隐藏工具调用 ID 等内部字段；开启后会额外显示调用 ID 和等待中的工具细节。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = config.showDebugToolDetails,
                    onCheckedChange = { checked ->
                        onConfigChanged(config.copy(showDebugToolDetails = checked))
                    }
                )
            }
        }

        // ═══════════════════════════════════════
        // AI 模型提供商
        // ═══════════════════════════════════════
        Text(
            text = "AI 模型提供商",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        providers.forEach { provider ->
            val isActive = config.activeProviderId == provider.id

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onConfigChanged(config.copy(activeProviderId = provider.id)) }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Provider 标题行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = isActive,
                            onClick = { onConfigChanged(config.copy(activeProviderId = provider.id)) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = provider.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "默认模型: ${provider.defaultModel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // 展开配置
                    AnimatedVisibility(visible = isActive) {
                        Column(
                            modifier = Modifier.padding(start = 44.dp, top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val apiKey = when (provider.id) {
                                "deepseek" -> config.deepseekApiKey
                                "openai-compatible" -> config.openaiApiKey
                                "claude" -> config.claudeApiKey
                                else -> ""
                            }
                            val baseUrl = when (provider.id) {
                                "deepseek" -> config.deepseekBaseUrl
                                "openai-compatible" -> config.openaiBaseUrl
                                "claude" -> config.claudeBaseUrl
                                else -> ""
                            }
                            val model = when (provider.id) {
                                "deepseek" -> config.deepseekModel
                                "openai-compatible" -> config.openaiModel
                                "claude" -> config.claudeModel
                                else -> ""
                            }
                            val resolvedModel = config.getResolvedModel(provider.id)
                            val reasoningEffort = when (provider.id) {
                                "deepseek" -> config.deepseekReasoningEffort
                                "openai-compatible" -> config.openaiReasoningEffort
                                else -> ""
                            }
                            val promptPricePer1M = when (provider.id) {
                                "deepseek" -> config.deepseekPromptPricePer1M
                                "openai-compatible" -> config.openaiPromptPricePer1M
                                "claude" -> config.claudePromptPricePer1M
                                else -> 0.0
                            }
                            val completionPricePer1M = when (provider.id) {
                                "deepseek" -> config.deepseekCompletionPricePer1M
                                "openai-compatible" -> config.openaiCompletionPricePer1M
                                "claude" -> config.claudeCompletionPricePer1M
                                else -> 0.0
                            }
                            val balanceUsd = when (provider.id) {
                                "deepseek" -> config.deepseekBalanceUsd
                                "openai-compatible" -> config.openaiBalanceUsd
                                "claude" -> config.claudeBalanceUsd
                                else -> 0.0
                            }
                            val balanceApiPath = config.getBalanceApiPath(provider.id)
                            val balanceCurrency = config.getBalanceCurrency(provider.id)
                            val balanceSyncedAt = config.getBalanceSyncedAt(provider.id)
                            val balanceSyncState = balanceSyncStates[provider.id] ?: BalanceSyncUiState()
                            val canFetchBalance = supportsBalanceFetch(provider.id)

                            // API Key
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { key ->
                                    onConfigChanged(when (provider.id) {
                                        "deepseek" -> config.copy(deepseekApiKey = key)
                                        "openai-compatible" -> config.copy(openaiApiKey = key)
                                        "claude" -> config.copy(claudeApiKey = key)
                                        else -> config
                                    })
                                },
                                label = { Text("API Key") },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = if (showApiKey) VisualTransformation.None
                                    else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { showApiKey = !showApiKey }) {
                                        Text(if (showApiKey) "隐藏" else "显示", fontSize = 12.sp)
                                    }
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                    cursorColor = MaterialTheme.colorScheme.primary
                                )
                            )

                            // Base URL（中转站地址）
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { url ->
                                    onConfigChanged(when (provider.id) {
                                        "deepseek" -> config.copy(deepseekBaseUrl = url)
                                        "openai-compatible" -> config.copy(openaiBaseUrl = url)
                                        "claude" -> config.copy(claudeBaseUrl = url)
                                        else -> config
                                    })
                                },
                                label = { Text("Base URL（中转站地址）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                placeholder = { Text(provider.defaultBaseUrl, fontSize = 12.sp) },
                                supportingText = { Text("留空 = 官方 API", fontSize = 10.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                    cursorColor = MaterialTheme.colorScheme.primary
                                )
                            )

                            if (provider.id == "deepseek") {
                                Text(
                                    text = "模型档位",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = config.deepseekModelPreset == "flash",
                                        onClick = {
                                            onConfigChanged(config.copy(deepseekModelPreset = "flash"))
                                        },
                                        label = { Text("Flash", fontSize = 12.sp) }
                                    )
                                    FilterChip(
                                        selected = config.deepseekModelPreset == "pro",
                                        onClick = {
                                            onConfigChanged(config.copy(deepseekModelPreset = "pro"))
                                        },
                                        label = { Text("Pro", fontSize = 12.sp) }
                                    )
                                    FilterChip(
                                        selected = config.deepseekModelPreset == "custom",
                                        onClick = {
                                            onConfigChanged(config.copy(deepseekModelPreset = "custom"))
                                        },
                                        label = { Text("自定义", fontSize = 12.sp) }
                                    )
                                }
                                Text(
                                    text = "当前实际模型: $resolvedModel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }

                            // 模型名
                            OutlinedTextField(
                                value = model,
                                onValueChange = { m ->
                                    onConfigChanged(when (provider.id) {
                                        "deepseek" -> config.copy(deepseekModel = m)
                                        "openai-compatible" -> config.copy(openaiModel = m)
                                        "claude" -> config.copy(claudeModel = m)
                                        else -> config
                                    })
                                },
                                label = { Text("模型名称") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = provider.id != "deepseek" || config.deepseekModelPreset == "custom",
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                placeholder = { Text(provider.defaultModel, fontSize = 12.sp) },
                                supportingText = {
                                    if (provider.id == "deepseek" && config.deepseekModelPreset != "custom") {
                                        Text("切换到“自定义”后可手动输入模型 ID", fontSize = 10.sp)
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                    cursorColor = MaterialTheme.colorScheme.primary
                                )
                            )

                            if (provider.id == "deepseek" || provider.id == "openai-compatible") {
                                Text(
                                    text = "推理深度",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("low", "medium", "high").forEach { effort ->
                                        FilterChip(
                                            selected = reasoningEffort == effort,
                                            onClick = {
                                                onConfigChanged(
                                                    when (provider.id) {
                                                        "deepseek" -> config.copy(deepseekReasoningEffort = effort)
                                                        "openai-compatible" -> config.copy(openaiReasoningEffort = effort)
                                                        else -> config
                                                    }
                                                )
                                            },
                                            label = { Text(effort, fontSize = 12.sp) }
                                        )
                                    }
                                    if (provider.id == "deepseek") {
                                        FilterChip(
                                            selected = reasoningEffort == "max",
                                            onClick = {
                                                onConfigChanged(config.copy(deepseekReasoningEffort = "max"))
                                            },
                                            label = { Text("max", fontSize = 12.sp) }
                                        )
                                    }
                                }
                                Text(
                                    text = if (provider.id == "deepseek")
                                        "当前请求: model=$resolvedModel, effort=$reasoningEffort"
                                    else
                                        "当前请求: model=$resolvedModel, effort=$reasoningEffort。`max` 仅在 DeepSeek 档位显示。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }

                            Text(
                                text = "价格与预算",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = formatDoubleInput(promptPricePer1M),
                                onValueChange = { value ->
                                    parseDoubleInput(value)?.let { parsed ->
                                        onConfigChanged(
                                            when (provider.id) {
                                                "deepseek" -> config.copy(deepseekPromptPricePer1M = parsed)
                                                "openai-compatible" -> config.copy(openaiPromptPricePer1M = parsed)
                                                "claude" -> config.copy(claudePromptPricePer1M = parsed)
                                                else -> config
                                            }
                                        )
                                    }
                                },
                                label = { Text("输入价格（USD / 1M tokens）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            OutlinedTextField(
                                value = formatDoubleInput(completionPricePer1M),
                                onValueChange = { value ->
                                    parseDoubleInput(value)?.let { parsed ->
                                        onConfigChanged(
                                            when (provider.id) {
                                                "deepseek" -> config.copy(deepseekCompletionPricePer1M = parsed)
                                                "openai-compatible" -> config.copy(openaiCompletionPricePer1M = parsed)
                                                "claude" -> config.copy(claudeCompletionPricePer1M = parsed)
                                                else -> config
                                            }
                                        )
                                    }
                                },
                                label = { Text("输出价格（USD / 1M tokens）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            if (provider.id == "openai-compatible" || provider.id == "claude") {
                                OutlinedTextField(
                                    value = balanceApiPath,
                                    onValueChange = { path ->
                                        onConfigChanged(
                                            when (provider.id) {
                                                "openai-compatible" -> config.copy(openaiBalanceApiPath = path)
                                                "claude" -> config.copy(claudeBalanceApiPath = path)
                                                else -> config
                                            }
                                        )
                                    },
                                    label = { Text("余额接口路径") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    placeholder = {
                                        Text(
                                            "/user/balance 或 https://example.com/account/balance",
                                            fontSize = 12.sp
                                        )
                                    },
                                    supportingText = {
                                        Text(
                                            "可填相对路径或完整 URL。适用于支持兼容余额接口的中转站；留空时仍使用本地预算。",
                                            fontSize = 10.sp
                                        )
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                        cursorColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                            if (canFetchBalance) {
                                BalanceSyncCard(
                                    balanceUsd = balanceUsd,
                                    currency = balanceCurrency,
                                    syncedAt = balanceSyncedAt,
                                    syncState = balanceSyncState,
                                    onRefresh = { onRefreshProviderBalance(provider.id) }
                                )
                            } else {
                                OutlinedTextField(
                                    value = formatDoubleInput(balanceUsd),
                                    onValueChange = { value ->
                                        parseDoubleInput(value)?.let { parsed ->
                                            onConfigChanged(
                                                when (provider.id) {
                                                    "deepseek" -> config.copy(deepseekBalanceUsd = parsed)
                                                    "openai-compatible" -> config.copy(openaiBalanceUsd = parsed)
                                                    "claude" -> config.copy(claudeBalanceUsd = parsed)
                                                    else -> config
                                                }
                                            )
                                        }
                                    },
                                    label = { Text("本地预算余额（估算，USD）") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    supportingText = {
                                        Text(
                                            if (provider.id == "deepseek") {
                                                "该 Provider 当前没有统一余额接口，先用本地预算做剩余额度估算。"
                                            } else {
                                                "未配置余额接口路径时，先用本地预算做剩余额度估算。"
                                            },
                                            fontSize = 10.sp
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "GitHub 联动",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "这里的 GitHub 登录会给项目页的 push / pull、workflow 列表和手动触发使用。现在不再需要手动填写 OAuth 参数，直接点按钮就会跳浏览器登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "登录状态",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = if (config.isGitHubSignedIn()) "已连接 GitHub" else "未连接 GitHub",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (config.githubToken.isNotBlank()) {
                            Text(
                                text = "GitHub Token 已同步到应用内，可直接用于仓库与工作流功能。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                gitHubAuthState.viewerLogin?.let { login ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "当前账号",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = buildString {
                                    append("@")
                                    append(login)
                                    gitHubAuthState.viewerName?.takeIf { it.isNotBlank() }?.let {
                                        append(" · ")
                                        append(it)
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onStartGitHubOAuthLogin,
                        enabled = !gitHubAuthState.isLoading
                    ) {
                        Text(if (config.isGitHubSignedIn()) "重新登录" else "GitHub 登录", fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = onRefreshGitHubAuthStatus,
                        enabled = !gitHubAuthState.isLoading
                    ) {
                        Text("查看状态", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onClearGitHubToken,
                        enabled = config.isGitHubSignedIn() && !gitHubAuthState.isLoading
                    ) {
                        Text("退出登录", fontSize = 12.sp)
                    }
                }
                if (gitHubAuthState.isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = gitHubAuthState.message ?: "GitHub 登录处理中...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                gitHubAuthState.message?.takeIf { it.isNotBlank() && !gitHubAuthState.isLoading }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32)
                    )
                }
                gitHubAuthState.error?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        val activeSessions = sessions.filter { it.providerId == config.activeProviderId }
        val totalPromptTokens = activeSessions.sumOf { it.usageSummary.promptTokens }
        val totalCompletionTokens = activeSessions.sumOf { it.usageSummary.completionTokens }
        val totalTokens = activeSessions.sumOf { it.usageSummary.totalTokens }
        val totalEstimatedCost = activeSessions.sumOf { it.usageSummary.estimatedCostUsd }
        val activeBalanceCurrency = config.getBalanceCurrency()
        val remainingBalance = if (activeBalanceCurrency == "USD") {
            config.getBalanceUsd() - totalEstimatedCost
        } else {
            null
        }

        Text(
            text = "用量与成本",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "当前 Provider: ${ProviderRegistry.getActiveProvider(config.activeProviderId).name}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "累计会话 ${activeSessions.size} 个 · 总 Token $totalTokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "输入 $totalPromptTokens · 输出 $totalCompletionTokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "累计预估成本 \$${"%.6f".format(totalEstimatedCost)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (remainingBalance != null) {
                    Text(
                        text = "剩余额度估算 \$${"%.6f".format(remainingBalance)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (remainingBalance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "当前余额币种为 $activeBalanceCurrency，无法直接与 USD 成本相减。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ═══════════════════════════════════════
        // 高级设置
        // ═══════════════════════════════════════
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Text(
            text = "高级设置",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "回答风格",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                ResponseVerbosity.CONCISE to "简洁",
                ResponseVerbosity.BALANCED to "平衡",
                ResponseVerbosity.DETAILED to "详细"
            ).forEach { (verbosity, label) ->
                FilterChip(
                    selected = config.responseVerbosity == verbosity,
                    onClick = {
                        onConfigChanged(config.copy(responseVerbosity = verbosity))
                    },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }
        Text(
            text = when (config.responseVerbosity) {
                ResponseVerbosity.CONCISE ->
                    "更像命令式助手，默认少说废话，工具执行后只做简短总结。"
                ResponseVerbosity.BALANCED ->
                    "结论、关键点和下一步都会说，但不会展开得太长。"
                ResponseVerbosity.DETAILED ->
                    "更像桌面端长说明风格，会更主动解释过程、结果和后续建议。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 系统提示词
        OutlinedTextField(
            value = config.systemPrompt,
            onValueChange = { prompt ->
                onConfigChanged(config.copy(systemPrompt = prompt))
            },
            label = { Text("系统提示词 (System Prompt)") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 10,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )

        RuleSection(
            rules = config.globalRules,
            onRulesChanged = { rules ->
                onConfigChanged(config.copy(globalRules = rules))
            }
        )

        MemorySection(
            memories = config.globalMemories,
            onMemoriesChanged = { memories ->
                onConfigChanged(config.copy(globalMemories = memories))
            }
        )

        SkillSection(
            skills = config.globalSkills,
            onSkillsChanged = { skills ->
                onConfigChanged(config.copy(globalSkills = skills.sanitizedSkills()))
            },
            onImportSkills = { imported ->
                onConfigChanged(
                    config.copy(
                        globalSkills = mergeImportedSkills(config.globalSkills, imported).sanitizedSkills()
                    )
                )
            }
        )

        // 温度和最大 Token
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = config.temperature.toString(),
                onValueChange = { t ->
                    val temp = t.toDoubleOrNull() ?: config.temperature
                    onConfigChanged(config.copy(temperature = temp.coerceIn(0.0, 2.0)))
                },
                label = { Text("Temperature") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            OutlinedTextField(
                value = config.maxTokens.toString(),
                onValueChange = { t ->
                    val tokens = t.toIntOrNull() ?: config.maxTokens
                    onConfigChanged(config.copy(maxTokens = tokens.coerceIn(1, 128000)))
                },
                label = { Text("Max Tokens") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
        }

        // ═══════════════════════════════════════
        // 关于
        // ═══════════════════════════════════════
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Text(
            text = "关于",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow("版本", "1.0.0")
                Spacer(modifier = Modifier.height(4.dp))
                InfoRow("引擎", "Reasonix Mobile Core")
                Spacer(modifier = Modifier.height(4.dp))
                InfoRow("支持的 Provider", "${providers.size} 个")
                Spacer(modifier = Modifier.height(4.dp))
                InfoRow("代码", "基于 esengine/DeepSeek-Reasonix")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "💡 中转站用法：选「OpenAI Compatible」→ 填 Base URL → API Key → 模型名",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        // ═══════════════════════════════════════
        // MCP 服务器
        // ═══════════════════════════════════════
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MCP 服务器",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row {
                FilledTonalButton(
                    onClick = onConnectMcpServers,
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("连接", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(4.dp))
                FilledTonalButton(
                    onClick = onRefreshMcpStatus,
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("刷新", fontSize = 12.sp)
                }
            }
        }

        if (mcpConnectError != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚠️ $mcpConnectError",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp),
                    fontSize = 12.sp
                )
            }
        }

        // 显示已有 MCP 服务器状态
        var showAddMcp by remember { mutableStateOf(false) }
        var editingMcp by remember { mutableStateOf<McpServerConfig?>(null) }
        val allMcpNames = remember(mcpServers, mcpStatuses) {
            (mcpServers.map { it.name } + mcpStatuses.map { it.name }).distinct().sorted()
        }
        allMcpNames.forEach { serverName ->
            val status = mcpStatuses.firstOrNull { it.name == serverName }
            val savedConfig = mcpServers.firstOrNull { it.name == serverName }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = serverName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        savedConfig?.let { config ->
                            Text(
                                text = buildMcpConfigSummary(config),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = buildMcpStatusSummary(status, savedConfig != null),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        savedConfig?.let { config ->
                            TextButton(
                                onClick = {
                                    editingMcp = config
                                    showAddMcp = true
                                }
                            ) {
                                Text("编辑", fontSize = 12.sp)
                            }
                        }
                        IconButton(onClick = { onRemoveMcpServer(serverName) }) {
                            Text("✖️", fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        // 添加 MCP 服务器
        TextButton(
            onClick = {
                if (showAddMcp && editingMcp == null) {
                    showAddMcp = false
                } else {
                    editingMcp = null
                    showAddMcp = true
                }
            }
        ) {
            Text(
                if (showAddMcp && editingMcp == null) "收起"
                else if (editingMcp != null) "切换到新增"
                else "+ 添加 MCP 服务器",
                fontSize = 12.sp
            )
        }

        AnimatedVisibility(visible = showAddMcp) {
            AddMcpServerForm(
                initialConfig = editingMcp,
                onAdd = {
                    onAddMcpServer(it)
                    editingMcp = null
                    showAddMcp = false
                },
                onCancel = {
                    editingMcp = null
                    showAddMcp = false
                }
            )
        }

        McpDraftImportCard(
            onImportDrafts = { drafts ->
                drafts.forEach(onAddMcpServer)
            }
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private fun formatDoubleInput(value: Double): String {
    return if (value == 0.0) "" else value.toString()
}

private fun parseDoubleInput(value: String): Double? {
    if (value.isBlank()) return 0.0
    return value.toDoubleOrNull()
}

@Composable
private fun BalanceSyncCard(
    balanceUsd: Double,
    currency: String,
    syncedAt: Long?,
    syncState: BalanceSyncUiState,
    onRefresh: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "真实余额同步",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "当前余额 ${formatBalance(balanceUsd, currency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = syncedAt?.let { "最近同步 ${formatTimestamp(it)}" } ?: "还没有同步过余额",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onRefresh,
                    enabled = !syncState.isSyncing
                ) {
                    if (syncState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("同步", fontSize = 12.sp)
                    }
                }
            }
            syncState.message?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2E7D32)
                )
            }
            syncState.error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun formatBalance(balance: Double, currency: String): String {
    return when (currency.uppercase()) {
        "USD" -> "$${"%.6f".format(balance)}"
        else -> "${"%.6f".format(balance)} $currency"
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(timestamp))
}

@Composable
private fun SkillSection(
    skills: List<GlobalSkill>,
    onSkillsChanged: (List<GlobalSkill>) -> Unit,
    onImportSkills: (List<GlobalSkill>) -> Unit
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "全局 Skills",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "用于保存可复用的操作模板、工作流说明和固定能力描述",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilledTonalButton(
            onClick = {
                val newSkill = GlobalSkill(
                    id = UUID.randomUUID().toString().take(8),
                    title = "新 Skill",
                    content = ""
                )
                onSkillsChanged(skills + newSkill)
            }
        ) {
            Text("新增", fontSize = 12.sp)
        }
    }

    SkillDraftImportCard(onImportDrafts = onImportSkills)

    if (skills.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "暂无全局 Skills，可添加如“代码审查模板”“发布前检查流程”等可复用能力。",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    } else {
        skills.forEach { skill ->
            SkillCard(
                skill = skill,
                onChanged = { updatedSkill ->
                    onSkillsChanged(skills.map { if (it.id == updatedSkill.id) updatedSkill else it })
                },
                onDelete = {
                    onSkillsChanged(skills.filterNot { it.id == skill.id })
                }
            )
        }
    }
}

private fun mergeImportedSkills(
    current: List<GlobalSkill>,
    imported: List<GlobalSkill>
): List<GlobalSkill> {
    if (imported.isEmpty()) return current
    val merged = current.toMutableList()
    imported.forEach { item ->
        val matchIndex = merged.indexOfFirst { existing ->
            existing.title.trim().equals(item.title.trim(), ignoreCase = true)
        }
        if (matchIndex >= 0) {
            merged[matchIndex] = item.copy(id = merged[matchIndex].id)
        } else {
            merged += item
        }
    }
    return merged
}

private fun List<GlobalSkill>.sanitizedSkills(dropBlank: Boolean = false): List<GlobalSkill> {
    return map { item ->
        item.copy(
            title = item.title.trim(),
            description = item.description.trim(),
            content = item.content.trim(),
            allowedTools = sanitizeSkillAllowedTools(item.allowedTools),
            preferredModel = item.preferredModel.trim()
        )
    }.let { items ->
        if (!dropBlank) {
            items
        } else {
            items.filter {
                it.title.isNotBlank() || it.description.isNotBlank() || it.content.isNotBlank()
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: GlobalSkill,
    onChanged: (GlobalSkill) -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (skill.enabled) "已启用" else "已停用",
                    color = if (skill.enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { checked ->
                            onChanged(skill.copy(enabled = checked))
                        }
                    )
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }

            OutlinedTextField(
                value = skill.title,
                onValueChange = { onChanged(skill.copy(title = it)) },
                label = { Text("Skill 标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = skill.description,
                onValueChange = { onChanged(skill.copy(description = it)) },
                label = { Text("Skill 描述") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = skill.runAs == SkillRunAs.INLINE,
                    onClick = { onChanged(skill.copy(runAs = SkillRunAs.INLINE)) },
                    label = { Text("内联", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = skill.runAs == SkillRunAs.SUBAGENT,
                    onClick = { onChanged(skill.copy(runAs = SkillRunAs.SUBAGENT)) },
                    label = { Text("子代理", fontSize = 12.sp) }
                )
            }
            OutlinedTextField(
                value = skill.allowedTools.joinToString(", "),
                onValueChange = { raw ->
                    onChanged(
                        skill.copy(
                            allowedTools = normalizeSkillAllowedTools(raw)
                        )
                    )
                },
                label = { Text("允许工具（逗号分隔）") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 2,
                maxLines = 4
            )
            SkillAllowedToolsBudgetView(
                allowedTools = skill.allowedTools,
                fontSize = 10.sp
            )
            OutlinedTextField(
                value = skill.preferredModel,
                onValueChange = { onChanged(skill.copy(preferredModel = it)) },
                label = { Text("默认模型（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = skill.content,
                onValueChange = { onChanged(skill.copy(content = it)) },
                label = { Text("Skill 内容") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 4,
                maxLines = 8
            )
        }
    }
}

@Composable
private fun MemorySection(
    memories: List<GlobalMemory>,
    onMemoriesChanged: (List<GlobalMemory>) -> Unit
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "全局记忆",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "用于保存长期偏好和固定上下文，如“默认用中文回复”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilledTonalButton(
            onClick = {
                val newMemory = GlobalMemory(
                    id = UUID.randomUUID().toString().take(8),
                    title = "新记忆",
                    content = ""
                )
                onMemoriesChanged(memories + newMemory)
            }
        ) {
            Text("新增", fontSize = 12.sp)
        }
    }

    if (memories.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "暂无全局记忆，可添加如“默认用中文回复”“当前设备可使用 Root”等长期信息。",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    } else {
        memories.forEach { memory ->
            MemoryCard(
                memory = memory,
                onChanged = { updatedMemory ->
                    onMemoriesChanged(memories.map { if (it.id == updatedMemory.id) updatedMemory else it })
                },
                onDelete = {
                    onMemoriesChanged(memories.filterNot { it.id == memory.id })
                }
            )
        }
    }
}

@Composable
private fun MemoryCard(
    memory: GlobalMemory,
    onChanged: (GlobalMemory) -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (memory.enabled) "已启用" else "已停用",
                    color = if (memory.enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = memory.enabled,
                        onCheckedChange = { checked ->
                            onChanged(memory.copy(enabled = checked))
                        }
                    )
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }

            OutlinedTextField(
                value = memory.title,
                onValueChange = { onChanged(memory.copy(title = it)) },
                label = { Text("记忆标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = memory.content,
                onValueChange = { onChanged(memory.copy(content = it)) },
                label = { Text("记忆内容") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 4,
                maxLines = 8
            )
        }
    }
}

@Composable
private fun RuleSection(
    rules: List<GlobalRule>,
    onRulesChanged: (List<GlobalRule>) -> Unit
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "全局规则",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "启用后会自动注入到每次对话的系统上下文",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilledTonalButton(
            onClick = {
                val newRule = GlobalRule(
                    id = UUID.randomUUID().toString().take(8),
                    title = "新规则",
                    content = ""
                )
                onRulesChanged(rules + newRule)
            }
        ) {
            Text("新增", fontSize = 12.sp)
        }
    }

    if (rules.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "暂无全局规则，可添加如“始终用中文回复”“禁止直接删除文件”等规则。",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    } else {
        rules.forEach { rule ->
            RuleCard(
                rule = rule,
                onChanged = { updatedRule ->
                    onRulesChanged(rules.map { if (it.id == updatedRule.id) updatedRule else it })
                },
                onDelete = {
                    onRulesChanged(rules.filterNot { it.id == rule.id })
                }
            )
        }
    }
}

@Composable
private fun RuleCard(
    rule: GlobalRule,
    onChanged: (GlobalRule) -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (rule.enabled) "已启用" else "已停用",
                    color = if (rule.enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { checked ->
                            onChanged(rule.copy(enabled = checked))
                        }
                    )
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }

            OutlinedTextField(
                value = rule.title,
                onValueChange = { onChanged(rule.copy(title = it)) },
                label = { Text("规则标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = rule.content,
                onValueChange = { onChanged(rule.copy(content = it)) },
                label = { Text("规则内容") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 4,
                maxLines = 8
            )
        }
    }
}

@Composable
private fun AddMcpServerForm(
    initialConfig: dev.reasonix.mobile.core.mcp.McpServerConfig? = null,
    onAdd: (dev.reasonix.mobile.core.mcp.McpServerConfig) -> Unit,
    onCancel: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf("") }
    var transportType by remember { mutableStateOf(0) } // 0 = stdio, 1 = SSE, 2 = streamable-http
    var command by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("") }
    var envText by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var headersText by remember { mutableStateOf("") }
    var requestTimeoutMs by remember { mutableStateOf("") }

    LaunchedEffect(initialConfig) {
        if (initialConfig == null) {
            name = ""
            transportType = 0
            command = ""
            args = ""
            cwd = ""
            envText = ""
            url = ""
            headersText = ""
            requestTimeoutMs = ""
        } else {
            name = initialConfig.name
            transportType = when (initialConfig.transport) {
                dev.reasonix.mobile.core.mcp.McpTransportType.STDIO -> 0
                dev.reasonix.mobile.core.mcp.McpTransportType.SSE -> 1
                dev.reasonix.mobile.core.mcp.McpTransportType.STREAMABLE_HTTP -> 2
            }
            command = initialConfig.command
            args = initialConfig.args.joinToString(" ")
            cwd = initialConfig.cwd
            envText = formatKeyValueLines(initialConfig.env)
            url = initialConfig.url
            headersText = formatKeyValueLines(initialConfig.headers)
            requestTimeoutMs = initialConfig.requestTimeoutMs?.toString().orEmpty()
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (initialConfig == null) "新增 MCP 服务器" else "编辑 MCP 服务器",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("服务器名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            // 传输类型选择
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = transportType == 0,
                    onClick = { transportType = 0 },
                    label = { Text("stdio", fontSize = 12.sp) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = transportType == 1,
                    onClick = { transportType = 1 },
                    label = { Text("SSE", fontSize = 12.sp) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = transportType == 2,
                    onClick = { transportType = 2 },
                    label = { Text("HTTP", fontSize = 12.sp) }
                )
            }

            if (transportType == 0) {
                // stdio
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("命令 (如 npx)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("npx", fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text("参数 (空格分隔)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("-y @modelcontextprotocol/server-filesystem /sdcard", fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text("工作目录（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("如 /sdcard/project", fontSize = 12.sp) }
                )
                OutlinedTextField(
                    value = envText,
                    onValueChange = { envText = it },
                    label = { Text("环境变量（每行 KEY=value，可选）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp),
                    minLines = 3,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = {
                        Text("NODE_ENV=production\nAPI_KEY=xxx", fontSize = 12.sp)
                    }
                )
            } else {
                // SSE / streamable-http
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(if (transportType == 1) "SSE URL" else "HTTP URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = {
                        Text(
                            if (transportType == 1) "https://example.com/sse"
                            else "https://example.com/mcp",
                            fontSize = 12.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
                OutlinedTextField(
                    value = headersText,
                    onValueChange = { headersText = it },
                    label = { Text("请求头（每行 KEY=value，可选）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp),
                    minLines = 3,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = {
                        Text("Authorization=Bearer xxx\nX-Client=reasonix", fontSize = 12.sp)
                    }
                )
            }

            OutlinedTextField(
                value = requestTimeoutMs,
                onValueChange = { requestTimeoutMs = it.filter { ch -> ch.isDigit() } },
                label = { Text("超时（毫秒，可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("30000", fontSize = 12.sp) }
            )

            FilledTonalButton(
                onClick = {
                    if (name.isNotBlank()) {
                        val resolvedTransport = when (transportType) {
                            0 -> dev.reasonix.mobile.core.mcp.McpTransportType.STDIO
                            1 -> dev.reasonix.mobile.core.mcp.McpTransportType.SSE
                            else -> dev.reasonix.mobile.core.mcp.McpTransportType.STREAMABLE_HTTP
                        }
                        val config = dev.reasonix.mobile.core.mcp.McpServerConfig(
                            name = name,
                            transport = resolvedTransport,
                            command = command,
                            args = if (args.isNotBlank()) args.split(" ").filter { it.isNotBlank() } else emptyList(),
                            cwd = cwd.trim(),
                            env = parseKeyValueLines(envText),
                            url = url.trim(),
                            headers = parseKeyValueLines(headersText),
                            requestTimeoutMs = requestTimeoutMs.toLongOrNull()
                        )
                        onAdd(config)
                        if (initialConfig == null) {
                            name = ""
                            command = ""
                            args = ""
                            cwd = ""
                            envText = ""
                            url = ""
                            headersText = ""
                            requestTimeoutMs = ""
                        }
                    }
                },
                enabled = name.isNotBlank() && when (transportType) {
                    0 -> command.isNotBlank()
                    else -> url.isNotBlank()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (initialConfig == null) "添加服务器" else "保存修改", fontSize = 13.sp)
            }

            if (onCancel != null) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消", fontSize = 12.sp)
                }
            }
        }
    }
}

private fun parseKeyValueLines(raw: String): Map<String, String> {
    return raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) {
                null
            } else {
                val key = line.substring(0, index).trim()
                val value = line.substring(index + 1).trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }
        }
        .toMap()
}

private fun formatKeyValueLines(entries: Map<String, String>): String {
    return entries.entries.joinToString("\n") { (key, value) -> "$key=$value" }
}

private fun buildMcpConfigSummary(config: McpServerConfig): String {
    val transportLabel = when (config.transport) {
        dev.reasonix.mobile.core.mcp.McpTransportType.STDIO -> "stdio"
        dev.reasonix.mobile.core.mcp.McpTransportType.SSE -> "SSE"
        dev.reasonix.mobile.core.mcp.McpTransportType.STREAMABLE_HTTP -> "streamable-http"
    }
    val details = buildList {
        add(transportLabel)
        when (config.transport) {
            dev.reasonix.mobile.core.mcp.McpTransportType.STDIO -> {
                config.command.takeIf { it.isNotBlank() }?.let { add(it) }
                if (config.cwd.isNotBlank()) add("cwd")
                if (config.env.isNotEmpty()) add("env ${config.env.size}")
            }

            dev.reasonix.mobile.core.mcp.McpTransportType.SSE,
            dev.reasonix.mobile.core.mcp.McpTransportType.STREAMABLE_HTTP -> {
                config.url.takeIf { it.isNotBlank() }?.let { add(it) }
                if (config.headers.isNotEmpty()) add("headers ${config.headers.size}")
            }
        }
        config.requestTimeoutMs?.let { add("${it}ms") }
    }
    return details.joinToString(" · ")
}

private fun buildMcpStatusSummary(
    status: McpServerStatus?,
    hasSavedConfig: Boolean
): String {
    val toolCount = status?.toolCount ?: 0
    val connectionLabel = when {
        status?.connected == true -> "✅ 已连接"
        hasSavedConfig -> "⏸ 未连接"
        else -> "❌ 未知"
    }
    return "$toolCount 个工具 · $connectionLabel"
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp
        )
    }
}
