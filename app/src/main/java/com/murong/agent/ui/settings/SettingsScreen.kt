package com.murong.agent.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.GlobalRule
import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.AgentBackendKind
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.RelayConfig
import com.murong.agent.core.config.RelayKind
import com.murong.agent.core.config.ResponseVerbosity
import com.murong.agent.core.config.SkillRunAs
import com.murong.agent.core.loop.SessionSummary
import com.murong.agent.core.loop.formatCurrencyAmount
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpConfigSource
import com.murong.agent.core.mcp.McpServerStatus
import com.murong.agent.core.provider.ModelProvider
import com.murong.agent.core.provider.ProviderRegistry
import com.murong.agent.ui.MemoryDraftImportCard
import com.murong.agent.ui.McpDraftImportCard
import com.murong.agent.ui.MurongDialog
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongInfoCard
import com.murong.agent.ui.MurongOutlinedActionButton
import com.murong.agent.ui.MurongInteractionPerformanceHint
import com.murong.agent.ui.MurongPrimaryPageSurface
import com.murong.agent.ui.MurongSectionCard
import com.murong.agent.ui.RuleDraftImportCard
import com.murong.agent.ui.rememberMurongBottomBarScrollPadding
import com.murong.agent.ui.normalizeSkillAllowedTools
import com.murong.agent.ui.sanitizeSkillAllowedTools
import com.murong.agent.ui.SkillAllowedToolsBudgetView
import com.murong.agent.ui.SkillDraftImportCard
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun SettingsScreen(
    config: ProviderConfig,
    durableGlobalMemories: List<GlobalMemory> = emptyList(),
    onConfigChanged: (ProviderConfig) -> Unit,
    onUpdateApiKey: (String, String) -> Unit = { _, _ -> },
    onUpdateBaseUrl: (String, String) -> Unit = { _, _ -> },
    onUpdateModel: (String, String) -> Unit = { _, _ -> },
    onAddRelay: (String) -> Unit = {},
    onSelectRelay: (String, String) -> Unit = { _, _ -> },
    onSetActiveProvider: (String) -> Unit = {},
    gitHubAuthState: GitHubAuthUiState = GitHubAuthUiState(),
    codexChatGptState: CodexChatGptUiState = CodexChatGptUiState(),
    onSelectAgentBackend: (AgentBackendKind) -> Unit = {},
    onRefreshCodexChatGptStatus: () -> Unit = {},
    onStartCodexChatGptLogin: () -> Unit = {},
    onCancelCodexChatGptLogin: () -> Unit = {},
    onLogoutCodexChatGpt: () -> Unit = {},
    rootStatus: Boolean? = null,
    isCheckingRoot: Boolean = false,
    onCheckRoot: () -> Unit = {},
    sessions: List<SessionSummary> = emptyList(),
    balanceSyncStates: Map<String, BalanceSyncUiState> = emptyMap(),
    providerModelCatalogs: Map<String, ProviderModelCatalogUiState> = emptyMap(),
    mcpServers: List<McpServerConfig> = emptyList(),
    mcpStatuses: List<McpServerStatus> = emptyList(),
    mcpConnectError: String? = null,
    onRefreshProviderBalance: (String) -> Unit = {},
    onRefreshProviderModels: (String) -> Unit = {},
    supportsBalanceFetch: (String) -> Boolean = { false },
    onAddMcpServer: (McpServerConfig) -> Unit = {},
    onImportMcpDrafts: (List<McpServerConfig>) -> Unit = {},
    onRemoveMcpServer: (String) -> Unit = {},
    onConnectMcpServers: () -> Unit = {},
    onRefreshMcpStatus: () -> Unit = {},
    onRefreshGitHubAuthStatus: () -> Unit = {},
    onRefreshDurableGlobalMemories: () -> Unit = {},
    onUpdateDurableGlobalMemory: (GlobalMemory) -> Unit = {},
    onDeleteDurableGlobalMemory: (String) -> Unit = {},
    onStartGitHubOAuthLogin: () -> Unit = {},
    onClearGitHubToken: () -> Unit = {},
    onOpenThemePage: () -> Unit = {},
    onOpenAboutPage: () -> Unit = {}
) {
    val bottomBarScrollPadding = rememberMurongBottomBarScrollPadding()
    val settingsScrollState = rememberScrollState()
    val context = LocalContext.current
    var hasExternalStorageAccess by remember {
        mutableStateOf(MurongExternalStorageAccess.hasAccess(context))
    }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasExternalStorageAccess = MurongExternalStorageAccess.hasAccess(context)
    }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        hasExternalStorageAccess = MurongExternalStorageAccess.hasAccess(context)
    }
    MurongInteractionPerformanceHint(active = settingsScrollState.isScrollInProgress)
    val providers = remember { ProviderRegistry.getAllProviders() }
    var showApiKey by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    var lastOpenedGitHubAuthUrl by remember { mutableStateOf<String?>(null) }
    // Persist across the Activity leaving for a browser and returning. Without
    // saveable state, Android recreation can immediately launch the same browser
    // page again before the user has a chance to read the result.
    var lastOpenedCodexAuthAttempt by rememberSaveable { mutableStateOf<String?>(null) }
    var providerSectionExpanded by rememberSaveable { mutableStateOf(false) }
    var chatAndSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var systemPromptExpanded by rememberSaveable { mutableStateOf(false) }
    var rulesExpanded by rememberSaveable { mutableStateOf(false) }
    var memoriesExpanded by rememberSaveable { mutableStateOf(false) }
    var skillsExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(gitHubAuthState.authorizationUrl) {
        val uri = gitHubAuthState.authorizationUrl?.trim().orEmpty()
        if (uri.isBlank() || lastOpenedGitHubAuthUrl == uri) return@LaunchedEffect
        runCatching { uriHandler.openUri(uri) }
        lastOpenedGitHubAuthUrl = uri
    }

    LaunchedEffect(
        codexChatGptState.loginId,
        codexChatGptState.verificationUrl,
        codexChatGptState.userCode,
    ) {
        val uri = codexChatGptState.verificationUrl?.trim().orEmpty()
        // OpenAI currently reuses the same verification URL for successive device
        // codes. The login id (with the code as a fallback for older servers) is
        // the attempt identity, not the URL itself.
        val attempt = codexChatGptState.loginId ?: codexChatGptState.userCode ?: uri
        if (uri.isBlank() || lastOpenedCodexAuthAttempt == attempt) return@LaunchedEffect
        runCatching { uriHandler.openUri(uri) }
        lastOpenedCodexAuthAttempt = attempt
    }

    LaunchedEffect(Unit) {
        onCheckRoot()
        onRefreshDurableGlobalMemories()
        onRefreshCodexChatGptStatus()
    }

    LaunchedEffect(config.githubToken) {
        if (config.isGitHubSignedIn()) {
            onRefreshGitHubAuthStatus()
        }
    }

    LaunchedEffect(config.activeProviderId) {
        if (supportsBalanceFetch(config.activeProviderId)) {
            onRefreshProviderBalance(config.activeProviderId)
        }
    }

    MurongPrimaryPageSurface(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(settingsScrollState)
                .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = bottomBarScrollPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MurongOutlinedActionButton(
                        text = "主题界面",
                        onClick = onOpenThemePage,
                        modifier = Modifier.weight(1f)
                    )
                    MurongOutlinedActionButton(
                        text = "关于界面",
                        onClick = onOpenAboutPage,
                        modifier = Modifier.weight(1f)
                    )
                }

            // ═══════════════════════════════════════
            // Root 权限检测
            // ═══════════════════════════════════════
            Text(
                text = "设备权限",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            MurongGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                                else -> "自动检测中…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                rootStatus == true -> Color(0xFF4CAF50)
                                rootStatus == false -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (isCheckingRoot) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            MurongGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "文件访问",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (hasExternalStorageAccess) {
                            "✅ 已可读取共享存储中的普通文件"
                        } else {
                            "⚠️ 未授予全部文件访问；终端和 Agent 只能看到部分目录"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasExternalStorageAccess) {
                            Color(0xFF4CAF50)
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    if (!hasExternalStorageAccess) {
                        Text(
                            text = MurongExternalStorageAccess.missingAccessSummary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                allFilesAccessLauncher.launch(
                                    MurongExternalStorageAccess.settingsIntent(context)
                                )
                            } else {
                                MurongExternalStorageAccess.permissionToRequest()?.let(
                                    legacyStoragePermissionLauncher::launch
                                )
                            }
                        },
                        enabled = !hasExternalStorageAccess,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (hasExternalStorageAccess) "全部文件访问已授权" else "授权全部文件访问")
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
        val totalEstimatedCost = activeSessions.sumOf { it.usageSummary.resolvedEstimatedCostAmount() }
        val estimatedCostCurrency = config.getPriceCurrency()
        val activeBalanceCurrency = config.getBalanceCurrency()
        val remainingBalance = config.getBalanceAmount() - totalEstimatedCost

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
                    text = "累计预估成本 ${formatCurrencyAmount(totalEstimatedCost, estimatedCostCurrency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "当前余额 ${formatBalance(config.getBalanceAmount(), activeBalanceCurrency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "剩余额度估算 ${formatCurrencyAmount(remainingBalance, activeBalanceCurrency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remainingBalance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }

        ChatAndSearchSection(
            config = config,
            showApiKey = showApiKey,
            onToggleShowApiKey = { showApiKey = !showApiKey },
            expanded = chatAndSearchExpanded,
            onExpandedChange = { chatAndSearchExpanded = it },
            onConfigChanged = onConfigChanged
        )

        // ═══════════════════════════════════════
        // AI 模型提供商
        // ═══════════════════════════════════════
        CodexChatGptBackendCard(
            config = config,
            state = codexChatGptState,
            onSelectBackend = onSelectAgentBackend,
            onRefreshStatus = onRefreshCodexChatGptStatus,
            onStartLogin = onStartCodexChatGptLogin,
            onCancelLogin = onCancelCodexChatGptLogin,
            onLogout = onLogoutCodexChatGpt
        )

            SettingsExpandableSectionCard(
                title = "AI 连接配置",
                subtitle = if (config.usesCodexChatGptBackend()) {
                    "当前由官方 Codex / ChatGPT 后端处理"
                } else {
                    config.getActiveRelay()?.let {
                        config.configuredConnectionLabel(config.activeProviderId, it)
                    } ?: "未选择配置"
                },
                expanded = providerSectionExpanded,
                onExpandedChange = { providerSectionExpanded = it }
            ) {
                ProviderConfigurationSection(
                    providers = providers,
                    config = config,
                    onConfigChanged = onConfigChanged
                )
            }

        SettingsExpandableSectionCard(
            title = "系统提示词",
            subtitle = "控制所有会话的默认系统行为、回答风格和基础约束。",
            expanded = systemPromptExpanded,
            onExpandedChange = { systemPromptExpanded = it }
        ) {
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
                        onClick = { onConfigChanged(config.copy(responseVerbosity = verbosity)) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
            Text(
                text = when (config.responseVerbosity) {
                    ResponseVerbosity.CONCISE -> "更像命令式助手，默认少说废话，工具执行后只做简短总结。"
                    ResponseVerbosity.BALANCED -> "结论、关键点和下一步都会说，但不会展开得太长。"
                    ResponseVerbosity.DETAILED -> "更像桌面端长说明风格，会更主动解释过程、结果和后续建议。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        }

        SettingsExpandableSectionCard(
            title = "全局规则",
            subtitle = "启用后自动注入到每次对话，适合放硬约束。",
            expanded = rulesExpanded,
            onExpandedChange = { rulesExpanded = it }
        ) {
            RuleSection(
                rules = config.globalRules,
                onRulesChanged = { rules ->
                    onConfigChanged(config.copy(globalRules = rules))
                },
                showHeader = false,
                showDivider = false
            )
        }

        SettingsExpandableSectionCard(
            title = "全局记忆",
            subtitle = "保存长期偏好和稳定上下文，不必每次重说。",
            expanded = memoriesExpanded,
            onExpandedChange = { memoriesExpanded = it }
        ) {
            MemorySection(
                memories = config.globalMemories,
                durableMemories = durableGlobalMemories,
                onDurableMemoryChanged = onUpdateDurableGlobalMemory,
                onDeleteDurableMemory = onDeleteDurableGlobalMemory,
                onMemoriesChanged = { memories ->
                    onConfigChanged(config.copy(globalMemories = memories))
                },
                showHeader = false,
                showDivider = false
            )
        }

        SettingsExpandableSectionCard(
            title = "全局 Skills",
            subtitle = "保存可复用模板和工作流能力，按需展开编辑。",
            expanded = skillsExpanded,
            onExpandedChange = { skillsExpanded = it }
        ) {
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
                },
                showHeader = false,
                showDivider = false
            )
        }

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
                InfoRow("版本", "0.9.0-preview")
                Spacer(modifier = Modifier.height(4.dp))
                InfoRow("引擎", "Murong Agent Core")
                Spacer(modifier = Modifier.height(4.dp))
                InfoRow("支持的 Provider", "${providers.size} 个")
                Spacer(modifier = Modifier.height(4.dp))
                InfoRow("借鉴", "详见 README 中的借鉴项目说明")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "💡 中转站用法：选「OpenAI Compatible」→ 填 Base URL → API Key → 模型名",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        McpSettingsSection(
            mcpServers = mcpServers,
            mcpStatuses = mcpStatuses,
            mcpConnectError = mcpConnectError,
            onAddMcpServer = onAddMcpServer,
            onImportMcpDrafts = onImportMcpDrafts,
            onRemoveMcpServer = onRemoveMcpServer,
            onConnectMcpServers = onConnectMcpServers,
            onRefreshMcpStatus = onRefreshMcpStatus
        )

        Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun CodexChatGptBackendCard(
    config: ProviderConfig,
    state: CodexChatGptUiState,
    onSelectBackend: (AgentBackendKind) -> Unit,
    onRefreshStatus: () -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showCancelConfirmation by rememberSaveable { mutableStateOf(false) }
    var copiedLoginId by remember { mutableStateOf<String?>(null) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("运行后端", style = MaterialTheme.typography.titleMedium)
            Text(
                "ChatGPT / Codex 使用官方设备码登录和 Plus / Pro 的 Codex 权益；不会读取或使用 API Key。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.activeAgentBackend == AgentBackendKind.PROVIDER_API,
                    onClick = { onSelectBackend(AgentBackendKind.PROVIDER_API) },
                    label = { Text("API Key / 中转") }
                )
                FilterChip(
                    selected = config.activeAgentBackend == AgentBackendKind.CODEX_CHATGPT,
                    onClick = { onSelectBackend(AgentBackendKind.CODEX_CHATGPT) },
                    label = { Text("ChatGPT / Codex") }
                )
            }
            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.isLoggedIn) {
                Text(
                    "已登录${state.accountEmail?.let { "：$it" }.orEmpty()}${state.planType?.let { " · $it" }.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshStatus, enabled = !state.isLoading) {
                        Text("刷新状态")
                    }
                    TextButton(onClick = onLogout, enabled = !state.isLoading) {
                        Text("退出登录")
                    }
                }
            } else {
                state.userCode?.takeIf { it.isNotBlank() }?.let { code ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "设备码：$code",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedButton(
                            onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("Codex device code", code))
                                copiedLoginId = state.loginId ?: code
                            },
                        ) {
                            Text(if (copiedLoginId == (state.loginId ?: code)) "已复制" else "复制设备码")
                        }
                    }
                    Text(
                        "浏览器会自动打开。页面显示“已登录 Codex”后可关闭；本应用会在后台完成确认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            state.verificationUrl?.takeIf { it.isNotBlank() }?.let { uri ->
                                runCatching { uriHandler.openUri(uri) }
                            }
                        },
                        enabled = !state.verificationUrl.isNullOrBlank(),
                    ) {
                        Text("打开授权页面")
                    }
                }
                Button(
                    onClick = onStartLogin,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.userCode.isNullOrBlank()) "登录 ChatGPT" else "重新发起登录")
                }
                if (!state.loginId.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = onRefreshStatus,
                            enabled = !state.isLoading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("检查登录状态")
                        }
                        OutlinedButton(
                            onClick = { showCancelConfirmation = true },
                            enabled = !state.isLoading,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("取消本次登录")
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onRefreshStatus,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("检查登录状态")
                    }
                }
            }
            state.message?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.error?.takeIf { it.isNotBlank() }?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            title = { Text("取消本次 ChatGPT 登录？") },
            text = { Text("当前设备码会立即失效。若要继续，请重新发起登录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelConfirmation = false
                        onCancelLogin()
                    },
                ) {
                    Text("确认取消", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmation = false }) {
                    Text("继续等待")
                }
            },
        )
    }
}

@Composable
private fun SettingsExpandableSectionCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    MurongSectionCard(
        title = title,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "收起$title" else "展开$title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ChatAndSearchSection(
    config: ProviderConfig,
    showApiKey: Boolean,
    onToggleShowApiKey: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onConfigChanged: (ProviderConfig) -> Unit
) {
    SettingsExpandableSectionCard(
        title = "聊天与搜索",
        subtitle = "把聊天开关和搜索后端收在一起，默认折叠减少设置页噪音。",
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        SettingsToggleCard(
            title = "流式输出",
            description = if (config.isStreamingResponsesEnabled()) {
                "回复会边生成边显示，更适合观察工具调用和长回答过程。"
            } else {
                "改为整段返回，适合想减少 UI 抖动或排查兼容性问题时使用。"
            },
            checked = config.isStreamingResponsesEnabled(),
            onCheckedChange = { checked ->
                onConfigChanged(config.copy(enableStreamingResponses = checked))
            }
        )
        SettingsToggleCard(
            title = "多模态",
            description = if (config.isMultimodalEnabled()) {
                "允许发送图片给模型；关闭后聊天输入区会直接隐藏拍照和选图。"
            } else {
                "关闭后只发送文本，拍照和选图入口也会一起隐藏。"
            },
            checked = config.isMultimodalEnabled(),
            onCheckedChange = { checked ->
                onConfigChanged(config.copy(enableMultimodalMessages = checked))
            }
        )
        Text(
            text = "搜索引擎",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "默认按 Bing 抓取 -> Google 抓取 -> 百度抓取 回退；也可以额外配置自定义搜索后端和 API Key。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = config.webSearchSearxngBaseUrl,
            onValueChange = { value ->
                onConfigChanged(config.copy(webSearchSearxngBaseUrl = value))
            },
            label = { Text("自定义搜索后端") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            placeholder = { Text("例如 https://your-search.example 或 https://api.anysearch.com/mcp", fontSize = 12.sp) },
            supportingText = {
                Text("留空则走内置回退顺序；填写后会先尝试这里，再回退到 Bing / Google / 百度。", fontSize = 10.sp)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )
        OutlinedTextField(
            value = config.webSearchBingApiKey,
            onValueChange = { value ->
                onConfigChanged(config.copy(webSearchBingApiKey = value))
            },
            label = { Text("搜索 API Key（可选）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = onToggleShowApiKey) {
                    Text(if (showApiKey) "隐藏" else "显示", fontSize = 12.sp)
                }
            },
            textStyle = MaterialTheme.typography.bodySmall,
            placeholder = { Text("用于 AnySearch 或其他需要 Bearer Token 的后端", fontSize = 12.sp) },
            supportingText = {
                Text("默认抓取链不需要 Key；填写后会在请求自定义搜索后端时附带 Authorization 头。", fontSize = 10.sp)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

private fun hasConfiguredProvider(config: ProviderConfig): Boolean {
    return listOf(
        config.deepseekApiKey.trim(),
        config.openaiApiKey.trim(),
        config.claudeApiKey.trim(),
        config.deepseekBaseUrl.trim(),
        config.openaiBaseUrl.trim(),
        config.claudeBaseUrl.trim(),
        config.deepseekModel.trim(),
        config.openaiModel.trim(),
        config.claudeModel.trim()
    ).any { it.isNotBlank() }
}

@Composable
private fun McpSettingsSection(
    mcpServers: List<McpServerConfig>,
    mcpStatuses: List<McpServerStatus>,
    mcpConnectError: String?,
    onAddMcpServer: (McpServerConfig) -> Unit,
    onImportMcpDrafts: (List<McpServerConfig>) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onConnectMcpServers: () -> Unit,
    onRefreshMcpStatus: () -> Unit
) {
    var showAddMcp by remember { mutableStateOf(false) }
    var editingMcp by remember { mutableStateOf<McpServerConfig?>(null) }
    val allMcpNames = remember(mcpServers, mcpStatuses) {
        (mcpServers.map { it.name } + mcpStatuses.map { it.name }).distinct().sorted()
    }

    MurongSectionCard(
        title = "MCP 集成",
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MCP 服务器和连接状态统一放这里管理，工具页不再重复展示一遍。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = onConnectMcpServers, modifier = Modifier.height(32.dp)) {
                    Text("连接", fontSize = 12.sp)
                }
                FilledTonalButton(onClick = onRefreshMcpStatus, modifier = Modifier.height(32.dp)) {
                    Text("刷新", fontSize = 12.sp)
                }
            }
        }
        if (mcpConnectError != null) {
            Spacer(modifier = Modifier.height(10.dp))
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
        Spacer(modifier = Modifier.height(10.dp))
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
                    Column(modifier = Modifier.weight(1f)) {
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
            Spacer(modifier = Modifier.height(8.dp))
        }
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
        Spacer(modifier = Modifier.height(8.dp))
        McpDraftImportCard(
            onImportDrafts = onImportMcpDrafts,
            buttonLabel = "手动导入 MCP"
        )
    }
}

@Composable
private fun ProviderConfigurationSection(
    providers: List<ModelProvider>,
    config: ProviderConfig,
    onConfigChanged: (ProviderConfig) -> Unit
) {
    var editorTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var creationProviderId by remember { mutableStateOf<String?>(null) }
    var creationKind by remember { mutableStateOf(RelayKind.CUSTOM) }
    var showProtocolPicker by remember { mutableStateOf(false) }
    var showOfficialProviderPicker by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val activeKey = config.activeProviderId to config.getActiveRelayId(config.activeProviderId)
    val configurations = providers.flatMap { provider ->
        config.getRelayConfigs(provider.id).map { relay -> provider to relay }
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "官方预设只需填写 API Key；自定义项支持 OpenAI Compatible 和 Claude 协议。点卡片切换，点编辑修改详情。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            configurations.forEach { (provider, relay) ->
                val selected = activeKey.first == provider.id && activeKey.second == relay.id
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    modifier = Modifier.fillMaxWidth().clickable {
                        onConfigChanged(config.selectConfiguration(provider.id, relay.id))
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = {
                            onConfigChanged(config.selectConfiguration(provider.id, relay.id))
                        })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                config.configuredConnectionLabel(provider.id, relay),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "${provider.name} · ${relay.model.ifBlank { provider.defaultModel }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { editorTarget = provider.id to relay.id }) {
                            Text("编辑", fontSize = 12.sp)
                        }
                        TextButton(onClick = { deleteTarget = provider.id to relay.id }) {
                            Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                }
            }
            Button(onClick = { showProtocolPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("新增配置")
            }
        }
    }
    val deletion = deleteTarget
    if (deletion != null) {
        val provider = ProviderRegistry.getActiveProvider(deletion.first)
        val relay = config.getRelayConfigs(deletion.first).firstOrNull { it.id == deletion.second }
        if (relay != null) {
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除 ${config.configuredConnectionLabel(provider.id, relay)}？") },
                text = { Text("将删除此连接及其保存的配置与 API Key。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onConfigChanged(config.removeRelay(provider.id, relay.id))
                            deleteTarget = null
                        }
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
            )
        }
    }
    if (showProtocolPicker) {
        AlertDialog(
            onDismissRequest = { showProtocolPicker = false },
            title = { Text("选择协议") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showProtocolPicker = false; showOfficialProviderPicker = true }, modifier = Modifier.fillMaxWidth()) { Text("官方 API") }
                    TextButton(onClick = { showProtocolPicker = false; creationKind = RelayKind.CUSTOM; creationProviderId = "openai-compatible" }, modifier = Modifier.fillMaxWidth()) { Text("OpenAI Compatible") }
                    TextButton(onClick = { showProtocolPicker = false; creationKind = RelayKind.CUSTOM; creationProviderId = "claude" }, modifier = Modifier.fillMaxWidth()) { Text("Claude") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showProtocolPicker = false }) { Text("取消") } }
        )
    }
    if (showOfficialProviderPicker) {
        AlertDialog(
            onDismissRequest = { showOfficialProviderPicker = false },
            title = { Text("选择官方 API") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("deepseek" to "官方 DeepSeek", "openai-compatible" to "官方 OpenAI", "claude" to "官方 Claude").forEach { (id, label) ->
                        TextButton(onClick = { showOfficialProviderPicker = false; creationKind = RelayKind.OFFICIAL; creationProviderId = id }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showOfficialProviderPicker = false }) { Text("取消") } }
        )
    }
    val target = editorTarget
    if (target != null) {
        val provider = ProviderRegistry.getActiveProvider(target.first)
        val relay = config.getRelayConfigs(target.first).firstOrNull { it.id == target.second }
        if (relay != null) {
            RelayConfigurationDialog(
                provider = provider,
                config = config.selectConfiguration(target.first, relay.id),
                onDismiss = { editorTarget = null },
                onConfigChanged = onConfigChanged
            )
        }
    }
    creationProviderId?.let { providerId ->
        val provider = ProviderRegistry.getActiveProvider(providerId)
        NewRelayConfigurationDialog(
            provider = provider,
            kind = creationKind,
            onDismiss = { creationProviderId = null },
            onSave = { relay ->
                onConfigChanged(
                    config.withRelayConfigs(
                        providerId,
                        config.getRelayConfigs(providerId) + relay,
                        relay.id
                    ).copy(activeProviderId = providerId)
                )
                creationProviderId = null
            }
        )
    }
}

@Composable
private fun NewRelayConfigurationDialog(
    provider: ModelProvider,
    kind: RelayKind,
    onDismiss: () -> Unit,
    onSave: (RelayConfig) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(provider.defaultModel) }
    var contextWindowTokens by remember { mutableStateOf("") }
    MurongDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(if (kind == RelayKind.OFFICIAL) "新增官方 ${provider.name}" else "新增 ${provider.name} 配置", style = MaterialTheme.typography.titleLarge)
                if (kind == RelayKind.CUSTOM) {
                    OutlinedTextField(name, { name = it }, label = { Text("配置名称（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(model, { model = it }, label = { Text("模型") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    value = contextWindowTokens,
                    onValueChange = { value ->
                        if (value.all(Char::isDigit)) contextWindowTokens = value
                    },
                    label = { Text("上下文窗口 tokens（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("留空自动识别；用于决定何时压缩上下文。", fontSize = 10.sp) }
                )
                Text("取消不会创建配置，也不会保存 API Key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(
                        onClick = {
                            onSave(
                                RelayConfig(
                                    id = "relay-${UUID.randomUUID()}",
                                    name = name.trim(),
                                    baseUrl = if (kind == RelayKind.OFFICIAL) "" else baseUrl.trim(),
                                    apiKey = apiKey,
                                    model = model.trim().ifBlank { provider.defaultModel },
                                    contextWindowTokens = contextWindowTokens.toIntOrNull()
                                        ?.coerceIn(4_096, 2_000_000),
                                    kind = kind
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存配置") }
                }
            }
        }
    }
}

@Composable
private fun RelayConfigurationDialog(
    provider: ModelProvider,
    config: ProviderConfig,
    onDismiss: () -> Unit,
    onConfigChanged: (ProviderConfig) -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }
    MurongDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(config.configuredConnectionLabel(provider.id, config.getActiveRelay(provider.id)!!), style = MaterialTheme.typography.titleLarge)
                        Text("完整连接、模型、推理、预算和执行 Profile 设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
                LegacyProviderSettingsSection(
                    providers = listOf(provider),
                    config = config,
                    showApiKey = showApiKey,
                    onToggleShowApiKey = { showApiKey = !showApiKey },
                    providerSectionExpanded = true,
                    onProviderSectionExpandedChange = {},
                    balanceSyncStates = emptyMap(),
                    providerModelCatalogs = emptyMap(),
                    onConfigChanged = onConfigChanged,
                    onUpdateApiKey = { providerId, value -> onConfigChanged(config.updateActiveRelay(providerId) { it.copy(apiKey = value) }) },
                    onUpdateBaseUrl = { providerId, value -> onConfigChanged(config.updateActiveRelay(providerId) { it.copy(baseUrl = value) }) },
                    onUpdateModel = { providerId, value -> onConfigChanged(config.updateActiveRelay(providerId) { it.copy(model = value) }) },
                    onAddRelay = {},
                    onSelectRelay = { _, _ -> },
                    showRelayManagement = false,
                    onSetActiveProvider = { providerId -> onConfigChanged(config.copy(activeProviderId = providerId)) },
                    onRefreshProviderBalance = {},
                    onRefreshProviderModels = {},
                    supportsBalanceFetch = { false }
                )
            }
        }
    }
}

@Composable
private fun LegacyProviderSettingsSection(
    providers: List<ModelProvider>,
    config: ProviderConfig,
    showApiKey: Boolean,
    onToggleShowApiKey: () -> Unit,
    providerSectionExpanded: Boolean,
    onProviderSectionExpandedChange: (Boolean) -> Unit,
    balanceSyncStates: Map<String, BalanceSyncUiState>,
    providerModelCatalogs: Map<String, ProviderModelCatalogUiState>,
    onConfigChanged: (ProviderConfig) -> Unit,
    onUpdateApiKey: (String, String) -> Unit,
    onUpdateBaseUrl: (String, String) -> Unit,
    onUpdateModel: (String, String) -> Unit,
    onAddRelay: (String) -> Unit,
    onSelectRelay: (String, String) -> Unit,
    showRelayManagement: Boolean = true,
    onSetActiveProvider: (String) -> Unit,
    onRefreshProviderBalance: (String) -> Unit,
    onRefreshProviderModels: (String) -> Unit,
    supportsBalanceFetch: (String) -> Boolean
) {
    val focusManager = LocalFocusManager.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onProviderSectionExpandedChange(!providerSectionExpanded) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "AI 模型提供商",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "展开后可切换 Provider、模型、推理深度和预算配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (providerSectionExpanded) {
                        Icons.Outlined.KeyboardArrowUp
                    } else {
                        Icons.Outlined.KeyboardArrowDown
                    },
                    contentDescription = if (providerSectionExpanded) "收起模型供应商" else "展开模型供应商",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = providerSectionExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    providers.forEach { provider ->
                        val isActive = config.activeProviderId == provider.id
                        val apiKey = when (provider.id) {
                            "deepseek" -> config.deepseekApiKey
                            "openai-compatible" -> config.openaiApiKey
                            "claude" -> config.claudeApiKey
                            else -> ""
                        }
                        val activeRelay = config.getActiveRelay(provider.id)
                        val relays = config.getRelayConfigs(provider.id)
                        val activeRelayId = config.getActiveRelayId(provider.id)
                        val baseUrl = activeRelay?.baseUrl.orEmpty()
                        val model = activeRelay?.model.orEmpty()
                        val resolvedModel = config.getResolvedModel(provider.id)
                        val contextWindowTokens = activeRelay?.contextWindowTokens
                        val supportsAutoModelSelection = provider.id == "deepseek"
                        val modelAutoSelectionEnabled = supportsAutoModelSelection &&
                            config.isModelAutoSelectionEnabled(provider.id)
                        val reasoningEffort = activeRelay?.reasoningEffort.orEmpty()
                        val reasoningAutoSelectionEnabled = config.isReasoningAutoSelectionEnabled(provider.id)
                        val mainProfileSummary = buildMainExecutionProfileSummary(
                            provider = provider,
                            resolvedModel = resolvedModel,
                            reasoningEffort = reasoningEffort,
                            modelAutoSelectionEnabled = modelAutoSelectionEnabled,
                            reasoningAutoSelectionEnabled = reasoningAutoSelectionEnabled,
                            supportsAutoModelSelection = supportsAutoModelSelection
                        )
                        val promptPricePer1M = config.getConfiguredPromptPricePer1M(provider.id)
                        val completionPricePer1M = config.getConfiguredCompletionPricePer1M(provider.id)
                        val officialPromptPricePer1M = config.getOfficialPromptPricePer1M(
                            providerId = provider.id,
                            modelId = resolvedModel
                        )
                        val officialCompletionPricePer1M = config.getOfficialCompletionPricePer1M(
                            providerId = provider.id,
                            modelId = resolvedModel
                        )
                        val effectivePromptPricePer1M = config.getPromptPricePer1M(provider.id)
                        val effectiveCompletionPricePer1M = config.getCompletionPricePer1M(provider.id)
                        val balanceUsd = when (provider.id) {
                            "deepseek" -> config.deepseekBalanceUsd
                            "openai-compatible" -> config.openaiBalanceUsd
                            "claude" -> config.claudeBalanceUsd
                            else -> 0.0
                        }
                        val balanceApiPath = config.getBalanceApiPath(provider.id)
                        val balanceCurrency = config.getBalanceCurrency(provider.id)
                        val priceCurrency = config.getPriceCurrency(provider.id)
                        val balanceSyncedAt = config.getBalanceSyncedAt(provider.id)
                        val balanceSyncState = balanceSyncStates[provider.id] ?: BalanceSyncUiState()
                        val modelCatalogState = providerModelCatalogs[provider.id] ?: ProviderModelCatalogUiState(
                            providerId = provider.id,
                            models = mergeProviderModelCandidates(
                                providerId = provider.id,
                                currentModel = resolvedModel
                            )
                        )
                        val canFetchBalance = supportsBalanceFetch(provider.id)
                        var apiKeyDraft by rememberSaveable(provider.id, "apiKey") { mutableStateOf(apiKey) }
                        var baseUrlDraft by rememberSaveable(provider.id, "baseUrl") { mutableStateOf(baseUrl) }
                        var modelDraft by rememberSaveable(provider.id, "model") { mutableStateOf(model) }
                        var isApiKeyFocused by remember { mutableStateOf(false) }
                        var isBaseUrlFocused by remember { mutableStateOf(false) }
                        var isModelFocused by remember { mutableStateOf(false) }
                        val commitApiKey = {
                            if (apiKeyDraft != apiKey) {
                                onUpdateApiKey(provider.id, apiKeyDraft)
                            }
                        }
                        val commitBaseUrl = {
                            if (baseUrlDraft != baseUrl) {
                                onUpdateBaseUrl(provider.id, baseUrlDraft)
                            }
                        }
                        val commitModel = {
                            if (modelDraft != model) {
                                onUpdateModel(provider.id, modelDraft)
                            }
                        }

                        LaunchedEffect(provider.id, apiKey, isApiKeyFocused) {
                            if (!isApiKeyFocused && apiKeyDraft != apiKey) {
                                apiKeyDraft = apiKey
                            }
                        }
                        LaunchedEffect(provider.id, baseUrl, isBaseUrlFocused) {
                            if (!isBaseUrlFocused && baseUrlDraft != baseUrl) {
                                baseUrlDraft = baseUrl
                            }
                        }
                        LaunchedEffect(provider.id, model, isModelFocused) {
                            if (!isModelFocused && modelDraft != model) {
                                modelDraft = model
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isActive) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSetActiveProvider(provider.id) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isActive,
                                        onClick = { onSetActiveProvider(provider.id) },
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

                                AnimatedVisibility(visible = isActive) {
                                    Column(
                                        modifier = Modifier.padding(start = 44.dp, top = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (showRelayManagement) {
                                            Text(
                                                text = "中转站",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                relays.forEachIndexed { index, relay ->
                                                    FilterChip(
                                                        selected = relay.id == activeRelayId,
                                                        onClick = { onSelectRelay(provider.id, relay.id) },
                                                        label = { Text(relay.displayName(index), fontSize = 12.sp) }
                                                    )
                                                }
                                                AssistChip(
                                                    onClick = { onAddRelay(provider.id) },
                                                    label = { Text("新增中转站", fontSize = 12.sp) }
                                                )
                                            }
                                            Text(
                                                text = "切换仅改变当前使用项，已保存的中转站不会被删除。",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 10.sp
                                            )
                                        }
                                        OutlinedTextField(
                                            value = apiKeyDraft,
                                            onValueChange = { key ->
                                                apiKeyDraft = key
                                            },
                                            label = { Text("API Key") },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged {
                                                    val wasFocused = isApiKeyFocused
                                                    isApiKeyFocused = it.isFocused
                                                    if (wasFocused && !it.isFocused) {
                                                        commitApiKey()
                                                    }
                                                },
                                            visualTransformation = if (showApiKey) {
                                                VisualTransformation.None
                                            } else {
                                                PasswordVisualTransformation()
                                            },
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Password,
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    commitApiKey()
                                                    focusManager.clearFocus()
                                                }
                                            ),
                                            trailingIcon = {
                                                TextButton(onClick = onToggleShowApiKey) {
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

                                        OutlinedTextField(
                                            value = baseUrlDraft,
                                            onValueChange = { url ->
                                                baseUrlDraft = url
                                            },
                                            label = { Text("Base URL（中转站地址）") },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged {
                                                    val wasFocused = isBaseUrlFocused
                                                    isBaseUrlFocused = it.isFocused
                                                    if (wasFocused && !it.isFocused) {
                                                        commitBaseUrl()
                                                    }
                                                },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Uri,
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    commitBaseUrl()
                                                    focusManager.clearFocus()
                                                }
                                            ),
                                            textStyle = MaterialTheme.typography.bodySmall,
                                            placeholder = { Text(provider.defaultBaseUrl, fontSize = 12.sp) },
                                            supportingText = { Text("留空 = 官方 API；点键盘完成后保存", fontSize = 10.sp) },
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
                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                FilterChip(
                                                    selected = modelAutoSelectionEnabled,
                                                    onClick = {
                                                        onConfigChanged(config.withModelAutoSelection(provider.id, true))
                                                    },
                                                    label = { Text("自动", fontSize = 12.sp) }
                                                )
                                                FilterChip(
                                                    selected = !modelAutoSelectionEnabled && config.deepseekModelPreset == "flash",
                                                    onClick = {
                                                        onConfigChanged(
                                                            config.copy(
                                                                deepseekModelPreset = "flash",
                                                                deepseekModel = "deepseek-v4-flash"
                                                            ).withModelAutoSelection(provider.id, false)
                                                        )
                                                    },
                                                    label = { Text("Flash", fontSize = 12.sp) }
                                                )
                                                FilterChip(
                                                    selected = !modelAutoSelectionEnabled && config.deepseekModelPreset == "pro",
                                                    onClick = {
                                                        onConfigChanged(
                                                            config.copy(
                                                                deepseekModelPreset = "pro",
                                                                deepseekModel = "deepseek-v4-pro"
                                                            ).withModelAutoSelection(provider.id, false)
                                                        )
                                                    },
                                                    label = { Text("Pro", fontSize = 12.sp) }
                                                )
                                                FilterChip(
                                                    selected = !modelAutoSelectionEnabled && config.deepseekModelPreset == "custom",
                                                    onClick = {
                                                        onConfigChanged(
                                                            config.copy(deepseekModelPreset = "custom")
                                                                .withModelAutoSelection(provider.id, false)
                                                        )
                                                    },
                                                    label = { Text("自定义", fontSize = 12.sp) }
                                                )
                                            }
                                            Text(
                                                text = if (modelAutoSelectionEnabled) {
                                                    "当前基础模型: $resolvedModel"
                                                } else {
                                                    "当前固定模型: $resolvedModel"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp
                                            )
                                        } else {
                                            Text(
                                                text = "模型档位",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                FilterChip(
                                                    selected = model == provider.defaultModel,
                                                    onClick = {
                                                        onConfigChanged(config.withProviderModelSelection(provider.id, provider.defaultModel))
                                                    },
                                                    label = { Text(provider.formatModelDisplayName(provider.defaultModel), fontSize = 12.sp) }
                                                )
                                                FilterChip(
                                                    selected = model != provider.defaultModel,
                                                    onClick = {
                                                        onConfigChanged(
                                                            when (provider.id) {
                                                                "openai-compatible" -> config.copy(openaiModel = "")
                                                                "claude" -> config.copy(claudeModel = "")
                                                                else -> config
                                                            }.withModelAutoSelection(provider.id, false)
                                                        )
                                                    },
                                                    label = { Text("自定义", fontSize = 12.sp) }
                                                )
                                            }
                                            Text(
                                                text = "当前固定模型: $resolvedModel",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp
                                            )
                                        }

                                        OutlinedTextField(
                                            value = modelDraft,
                                            onValueChange = { m ->
                                                modelDraft = m
                                                if (provider.id == "deepseek" && config.deepseekModelPreset != "custom") {
                                                    onConfigChanged(
                                                        config.copy(deepseekModelPreset = "custom")
                                                            .withModelAutoSelection(provider.id, false)
                                                    )
                                                }
                                            },
                                            label = {
                                                Text(if (supportsAutoModelSelection && modelAutoSelectionEnabled) "基础模型名称" else "模型名称")
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged {
                                                    val wasFocused = isModelFocused
                                                    isModelFocused = it.isFocused
                                                    if (wasFocused && !it.isFocused) {
                                                        commitModel()
                                                    }
                                                },
                                            enabled = if (provider.id == "deepseek") {
                                                config.deepseekModelPreset == "custom"
                                            } else {
                                                model != provider.defaultModel
                                            },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    commitModel()
                                                    focusManager.clearFocus()
                                                }
                                            ),
                                            textStyle = MaterialTheme.typography.bodySmall,
                                            placeholder = { Text(provider.defaultModel, fontSize = 12.sp) },
                                            supportingText = {
                                                if (provider.id == "deepseek" && config.deepseekModelPreset != "custom") {
                                                    Text("切换到“自定义”后可手动输入模型 ID", fontSize = 10.sp)
                                                } else if (supportsAutoModelSelection && modelAutoSelectionEnabled) {
                                                    Text("复杂任务会在这个基础上自动升到更强模型。", fontSize = 10.sp)
                                                } else if (provider.id != "deepseek" && model == provider.defaultModel) {
                                                    Text("如需其他模型，先点上面的“自定义”。", fontSize = 10.sp)
                                                }
                                            },
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                                cursorColor = MaterialTheme.colorScheme.primary
                                            )
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedButton(
                                                onClick = { onRefreshProviderModels(provider.id) },
                                                enabled = !modelCatalogState.isLoading
                                            ) {
                                                Text(
                                                    if (modelCatalogState.isLoading) "同步中..." else "从上游获取模型列表",
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Text(
                                                text = "来源: ${modelCatalogState.sourceLabel}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        modelCatalogState.message?.takeIf { it.isNotBlank() }?.let { message ->
                                            Text(
                                                text = message,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF2E7D32)
                                            )
                                        }
                                        modelCatalogState.error?.takeIf { it.isNotBlank() }?.let { error ->
                                            Text(
                                                text = error,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        if (modelCatalogState.models.isNotEmpty()) {
                                            Text(
                                                text = "可选模型",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                modelCatalogState.models.take(18).forEach { modelId ->
                                                    FilterChip(
                                                        selected = resolvedModel == modelId,
                                                        onClick = {
                                                            onConfigChanged(config.withProviderModelSelection(provider.id, modelId))
                                                        },
                                                        label = {
                                                            Text(
                                                                provider.formatModelDisplayName(modelId),
                                                                fontSize = 12.sp
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                        }

                                        DeferredOptionalIntField(
                                            fieldKey = "${provider.id}-${activeRelay?.id}-context-window",
                                            currentValue = contextWindowTokens,
                                            label = "上下文窗口 tokens（可选）",
                                            supportingText = "留空时按模型自动选择安全水位；自建中转或受限模型可填写实际上限。",
                                            minValue = 4_096,
                                            maxValue = 2_000_000,
                                            onCommit = { value ->
                                                onConfigChanged(
                                                    config.updateActiveRelay(provider.id) {
                                                        it.copy(contextWindowTokens = value)
                                                    }
                                                )
                                            }
                                        )

                                        if (provider.supportsReasoning && provider.supportedReasoningEfforts.isNotEmpty()) {
                                            Text(
                                                text = "推理深度",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                FilterChip(
                                                    selected = reasoningAutoSelectionEnabled,
                                                    onClick = {
                                                        onConfigChanged(config.withReasoningAutoSelection(provider.id, true))
                                                    },
                                                    label = { Text("自动", fontSize = 12.sp) }
                                                )
                                            }
                                            Text(
                                                text = if (reasoningAutoSelectionEnabled) {
                                                    "复杂任务会自动抬高推理深度；下面的档位作为当前默认值保留。"
                                                } else {
                                                    "关闭自动后，始终使用下面选定的推理档位。"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp
                                            )
                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                provider.supportedReasoningEfforts.forEach { effort ->
                                                    FilterChip(
                                                        selected = !reasoningAutoSelectionEnabled && reasoningEffort == effort,
                                                        onClick = {
                                                            onConfigChanged(
                                                                when (provider.id) {
                                                                    "deepseek" -> config.copy(deepseekReasoningEffort = effort)
                                                                    "openai-compatible" -> config.copy(openaiReasoningEffort = effort)
                                                                    "claude" -> config.copy(claudeReasoningEffort = effort)
                                                                    else -> config
                                                                }.withReasoningAutoSelection(provider.id, false)
                                                            )
                                                        },
                                                        label = {
                                                            Text(
                                                                provider.formatReasoningDisplayName(effort) ?: effort,
                                                                fontSize = 12.sp
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                            Text(
                                                text = provider.buildReasoningHint(resolvedModel, reasoningEffort)
                                                    ?: "当前请求: model=$resolvedModel, effort=$reasoningEffort",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp
                                            )
                                        }

                                        Text(
                                            text = "执行 Profile",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        ProfileSummaryCard(
                                            title = "主聊天",
                                            description = "普通聊天、直接执行和最终真正动手的这一轮，默认都走这一层。",
                                            summary = mainProfileSummary
                                        )
                                        ProfileOverrideCard(
                                            title = "计划 / 分流",
                                            description = "自动分流、生成计划和澄清问题走这一层。关闭时直接继承主聊天。",
                                            enabled = config.plannerProfileEnabled,
                                            summary = buildProfileOverrideSummary(
                                                provider = provider,
                                                inheritedModel = resolvedModel,
                                                inheritedReasoningEffort = reasoningEffort,
                                                overrideModel = config.plannerModel,
                                                overrideReasoningEffort = config.plannerReasoningEffort
                                            ),
                                            model = config.plannerModel,
                                            onEnabledChange = { enabled ->
                                                onConfigChanged(config.copy(plannerProfileEnabled = enabled))
                                            },
                                            onModelChange = { value ->
                                                onConfigChanged(config.copy(plannerModel = value))
                                            },
                                            reasoningEffort = config.plannerReasoningEffort,
                                            onReasoningEffortChange = { effort ->
                                                onConfigChanged(config.copy(plannerReasoningEffort = effort))
                                            },
                                            supportedReasoningEfforts = provider.supportedReasoningEfforts,
                                            reasoningSupported = provider.supportsReasoning
                                        )
                                        ProfileOverrideCard(
                                            title = "子代理默认",
                                            description = "子代理默认先继承这一层；项目模板里若另设 model / effort，会继续覆盖默认子代理 Profile。",
                                            enabled = config.subagentDefaultProfileEnabled,
                                            summary = buildProfileOverrideSummary(
                                                provider = provider,
                                                inheritedModel = resolvedModel,
                                                inheritedReasoningEffort = reasoningEffort,
                                                overrideModel = config.subagentDefaultModel,
                                                overrideReasoningEffort = config.subagentDefaultReasoningEffort
                                            ),
                                            model = config.subagentDefaultModel,
                                            onEnabledChange = { enabled ->
                                                onConfigChanged(config.copy(subagentDefaultProfileEnabled = enabled))
                                            },
                                            onModelChange = { value ->
                                                onConfigChanged(config.copy(subagentDefaultModel = value))
                                            },
                                            reasoningEffort = config.subagentDefaultReasoningEffort,
                                            onReasoningEffortChange = { effort ->
                                                onConfigChanged(config.copy(subagentDefaultReasoningEffort = effort))
                                            },
                                            supportedReasoningEfforts = provider.supportedReasoningEfforts,
                                            reasoningSupported = provider.supportsReasoning
                                        )

                                        Text(
                                            text = "价格与预算",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        DeferredDoubleField(
                                            fieldKey = "${provider.id}-prompt-price",
                                            currentValue = promptPricePer1M,
                                            label = if (officialPromptPricePer1M > 0.0) {
                                                "输入倍率（相对官方价）"
                                            } else {
                                                "输入价格（$priceCurrency / 1M tokens）"
                                            },
                                            supportingText = buildPricingFieldSupportingText(
                                                officialPricePer1M = officialPromptPricePer1M,
                                                effectivePricePer1M = effectivePromptPricePer1M,
                                                configuredValue = promptPricePer1M,
                                                currency = priceCurrency
                                            ),
                                            onCommit = { parsed ->
                                                onConfigChanged(
                                                    config.updateActiveRelay(provider.id) {
                                                        it.copy(promptPricePer1M = parsed)
                                                    }
                                                )
                                            }
                                        )
                                        DeferredDoubleField(
                                            fieldKey = "${provider.id}-completion-price",
                                            currentValue = completionPricePer1M,
                                            label = if (officialCompletionPricePer1M > 0.0) {
                                                "输出倍率（相对官方价）"
                                            } else {
                                                "输出价格（$priceCurrency / 1M tokens）"
                                            },
                                            supportingText = buildPricingFieldSupportingText(
                                                officialPricePer1M = officialCompletionPricePer1M,
                                                effectivePricePer1M = effectiveCompletionPricePer1M,
                                                configuredValue = completionPricePer1M,
                                                currency = priceCurrency
                                            ),
                                            onCommit = { parsed ->
                                                onConfigChanged(
                                                    config.updateActiveRelay(provider.id) {
                                                        it.copy(completionPricePer1M = parsed)
                                                    }
                                                )
                                            }
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
                                            DeferredDoubleField(
                                                fieldKey = "${provider.id}-balance",
                                                currentValue = balanceUsd,
                                                label = "本地预算余额（估算，$balanceCurrency）",
                                                supportingText = if (provider.id == "deepseek") {
                                                    "该 Provider 当前没有统一余额接口，先用本地预算做剩余额度估算。"
                                                } else {
                                                    "未配置余额接口路径时，先用本地预算做剩余额度估算。"
                                                },
                                                onCommit = { parsed ->
                                                    onConfigChanged(
                                                        when (provider.id) {
                                                            "deepseek" -> config.copy(deepseekBalanceUsd = parsed)
                                                            "openai-compatible" -> config.copy(openaiBalanceUsd = parsed)
                                                            "claude" -> config.copy(claudeBalanceUsd = parsed)
                                                            else -> config
                                                        }
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
    }
}

private fun buildMainExecutionProfileSummary(
    provider: ModelProvider,
    resolvedModel: String,
    reasoningEffort: String,
    modelAutoSelectionEnabled: Boolean,
    reasoningAutoSelectionEnabled: Boolean,
    supportsAutoModelSelection: Boolean
): String {
    val modelPart = if (supportsAutoModelSelection && modelAutoSelectionEnabled) {
        "模型自动（基础 ${provider.formatModelDisplayName(resolvedModel)}）"
    } else {
        "模型固定（${provider.formatModelDisplayName(resolvedModel)}）"
    }
    val reasoningLabel = provider.formatReasoningDisplayName(reasoningEffort) ?: reasoningEffort
    val reasoningPart = if (provider.supportsReasoning) {
        if (reasoningAutoSelectionEnabled) {
            "推理自动（默认 $reasoningLabel）"
        } else {
            "推理固定（$reasoningLabel）"
        }
    } else {
        "无独立推理档位"
    }
    return "$modelPart · $reasoningPart"
}

private fun buildProfileOverrideSummary(
    provider: ModelProvider,
    inheritedModel: String,
    inheritedReasoningEffort: String,
    overrideModel: String,
    overrideReasoningEffort: String
): String {
    val resolvedModel = overrideModel.trim().ifBlank { inheritedModel }
    val resolvedReasoning = overrideReasoningEffort.trim().ifBlank { inheritedReasoningEffort }
    val profileLabel = provider.buildExecutionProfileLabel(resolvedModel, resolvedReasoning)
    val overrideParts = buildList {
        if (overrideModel.isNotBlank()) add("模型")
        if (overrideReasoningEffort.isNotBlank()) add("推理")
    }
    return if (overrideParts.isEmpty()) {
        "已开启，但当前仍完全继承主聊天 · $profileLabel"
    } else {
        "独立 ${overrideParts.joinToString(" + ")} · $profileLabel"
    }
}

private fun buildPricingFieldSupportingText(
    officialPricePer1M: Double,
    effectivePricePer1M: Double,
    configuredValue: Double,
    currency: String
): String {
    return when {
        officialPricePer1M > 0.0 -> {
            val effectiveMultiplier = configuredValue.takeIf { it > 0.0 } ?: 1.0
            "已内置官方参考价 ${formatCurrencyAmount(officialPricePer1M, currency)} / 1M；" +
                "这里填写倍率，留空或 0 按 1.0x 计算；当前生效 ${effectiveMultiplier}x = " +
                "${formatCurrencyAmount(effectivePricePer1M, currency)} / 1M。"
        }
        else -> "这里填写绝对价格，用于本地成本估算。"
    }
}

@Composable
private fun ProfileSummaryCard(
    title: String,
    description: String,
    summary: String
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProfileOverrideCard(
    title: String,
    description: String,
    enabled: Boolean,
    summary: String,
    model: String,
    onEnabledChange: (Boolean) -> Unit,
    onModelChange: (String) -> Unit,
    reasoningEffort: String,
    onReasoningEffortChange: (String) -> Unit,
    supportedReasoningEfforts: List<String>,
    reasoningSupported: Boolean
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (enabled) summary else "继承主聊天",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = onModelChange,
                        label = { Text("模型覆盖（可留空）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        placeholder = { Text("留空 = 继承主聊天模型", fontSize = 12.sp) }
                    )
                    if (reasoningSupported && supportedReasoningEfforts.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = reasoningEffort.isBlank(),
                                onClick = { onReasoningEffortChange("") },
                                label = { Text("继承", fontSize = 12.sp) }
                            )
                            supportedReasoningEfforts.forEach { effort ->
                                FilterChip(
                                    selected = reasoningEffort == effort,
                                    onClick = { onReasoningEffortChange(effort) },
                                    label = { Text(effort, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDoubleInput(value: Double): String {
    return if (value == 0.0) "" else value.toString()
}

private fun isPartialDoubleInput(value: String): Boolean {
    if (value.isEmpty()) return true
    if (value.count { it == '.' } > 1) return false
    return value.all { it.isDigit() || it == '.' }
}

private fun parseCommittedDoubleInput(value: String): Double? {
    val normalized = value.trim()
    if (normalized.isBlank() || normalized == ".") return 0.0
    return normalized.toDoubleOrNull()
}

@Composable
private fun DeferredDoubleField(
    fieldKey: String,
    currentValue: Double,
    label: String,
    supportingText: String? = null,
    onCommit: (Double) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var isFocused by remember(fieldKey) { mutableStateOf(false) }
    var draft by rememberSaveable(fieldKey) { mutableStateOf(formatDoubleInput(currentValue)) }

    LaunchedEffect(fieldKey, currentValue, isFocused) {
        if (!isFocused) {
            draft = formatDoubleInput(currentValue)
        }
    }

    val commitValue = {
        val parsed = parseCommittedDoubleInput(draft)
        if (parsed != null && parsed != currentValue) {
            onCommit(parsed)
        } else {
            draft = formatDoubleInput(currentValue)
        }
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { value ->
            if (isPartialDoubleInput(value)) {
                draft = value
            }
        },
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                val wasFocused = isFocused
                isFocused = it.isFocused
                if (wasFocused && !it.isFocused) {
                    commitValue()
                }
            },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                commitValue()
                focusManager.clearFocus()
            }
        ),
        textStyle = MaterialTheme.typography.bodySmall,
        supportingText = supportingText?.let { text ->
            { Text(text, fontSize = 10.sp) }
        }
    )
}

@Composable
private fun DeferredOptionalIntField(
    fieldKey: String,
    currentValue: Int?,
    label: String,
    supportingText: String? = null,
    minValue: Int,
    maxValue: Int,
    onCommit: (Int?) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var isFocused by remember(fieldKey) { mutableStateOf(false) }
    var draft by rememberSaveable(fieldKey) { mutableStateOf(currentValue?.toString().orEmpty()) }

    LaunchedEffect(fieldKey, currentValue, isFocused) {
        if (!isFocused) draft = currentValue?.toString().orEmpty()
    }

    val commitValue = {
        val parsed = draft.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        val resolved = when {
            draft.isBlank() -> null
            parsed != null -> parsed.coerceIn(minValue, maxValue)
            else -> currentValue
        }
        if (resolved != currentValue) onCommit(resolved)
        draft = resolved?.toString().orEmpty()
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { value ->
            if (value.all(Char::isDigit)) draft = value
        },
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                val wasFocused = isFocused
                isFocused = it.isFocused
                if (wasFocused && !it.isFocused) commitValue()
            },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                commitValue()
                focusManager.clearFocus()
            }
        ),
        textStyle = MaterialTheme.typography.bodySmall,
        supportingText = supportingText?.let { text ->
            { Text(text, fontSize = 10.sp) }
        }
    )
}

@Composable
private fun SettingsToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
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
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
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
                horizontalArrangement = Arrangement.Start,
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
                if (syncState.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
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
    return formatCurrencyAmount(balance, currency)
}

private fun formatTimestamp(timestamp: Long): String {
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(timestamp))
}

@Composable
private fun SkillSection(
    skills: List<GlobalSkill>,
    onSkillsChanged: (List<GlobalSkill>) -> Unit,
    onImportSkills: (List<GlobalSkill>) -> Unit,
    showHeader: Boolean = true,
    showDivider: Boolean = true
) {
    if (showDivider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }

    if (showHeader) {
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
    }

    if (!showHeader) {
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
            Text("新增 Skill", fontSize = 12.sp)
        }
    }

    SkillDraftImportCard(
        onImportDrafts = onImportSkills,
        buttonLabel = "手动导入 Skill"
    )

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
    // Local state decouples cursor from parent list updates.
    var titleText by remember(skill.id) { mutableStateOf(skill.title) }
    var descriptionText by remember(skill.id) { mutableStateOf(skill.description) }
    var allowedToolsText by remember(skill.id) { mutableStateOf(skill.allowedTools.joinToString(", ")) }
    var preferredModelText by remember(skill.id) { mutableStateOf(skill.preferredModel) }
    var contentText by remember(skill.id) { mutableStateOf(skill.content) }
    LaunchedEffect(skill.title) { if (titleText != skill.title) titleText = skill.title }
    LaunchedEffect(skill.description) { if (descriptionText != skill.description) descriptionText = skill.description }
    LaunchedEffect(skill.allowedTools) {
        val normalizedText = skill.allowedTools.joinToString(", ")
        if (allowedToolsText != normalizedText) allowedToolsText = normalizedText
    }
    LaunchedEffect(skill.preferredModel) { if (preferredModelText != skill.preferredModel) preferredModelText = skill.preferredModel }
    LaunchedEffect(skill.content) { if (contentText != skill.content) contentText = skill.content }

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
                value = titleText,
                onValueChange = {
                    titleText = it
                    onChanged(skill.copy(title = it))
                },
                label = { Text("Skill 标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = descriptionText,
                onValueChange = {
                    descriptionText = it
                    onChanged(skill.copy(description = it))
                },
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
                value = allowedToolsText,
                onValueChange = { raw ->
                    allowedToolsText = raw
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
                value = preferredModelText,
                onValueChange = {
                    preferredModelText = it
                    onChanged(skill.copy(preferredModel = it))
                },
                label = { Text("默认模型（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = contentText,
                onValueChange = {
                    contentText = it
                    onChanged(skill.copy(content = it))
                },
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
    durableMemories: List<GlobalMemory> = emptyList(),
    onDurableMemoryChanged: (GlobalMemory) -> Unit = {},
    onDeleteDurableMemory: (String) -> Unit = {},
    onMemoriesChanged: (List<GlobalMemory>) -> Unit,
    showHeader: Boolean = true,
    showDivider: Boolean = true
) {
    val visibleDurableMemories = remember(durableMemories, memories) {
        durableMemories.filterNot { durable ->
            memories.any { legacy ->
                legacy.title.trim() == durable.title.trim() &&
                    legacy.content.trim() == durable.content.trim()
            }
        }
    }
    if (showDivider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }

    if (showHeader) {
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
    }

    if (!showHeader) {
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
            Text("新增记忆", fontSize = 12.sp)
        }
    }

    MemoryDraftImportCard(
        onImportDrafts = { imported ->
            if (imported.isNotEmpty()) {
                onMemoriesChanged(memories + imported)
            }
        },
        buttonLabel = "手动导入记忆"
    )

    if (visibleDurableMemories.isNotEmpty()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "模型保存的全局记忆",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "这里显示通过对话工具写入的 durable memory，已和工具侧真实存储对齐。现在支持直接编辑和删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        visibleDurableMemories.forEach { memory ->
            PersistedMemoryCard(
                memory = memory,
                onChanged = onDurableMemoryChanged,
                onDelete = { onDeleteDurableMemory(memory.id) }
            )
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
    // Local state decouples cursor from parent list updates.
    var titleText by remember(memory.id) { mutableStateOf(memory.title) }
    var contentText by remember(memory.id) { mutableStateOf(memory.content) }
    LaunchedEffect(memory.title) { if (titleText != memory.title) titleText = memory.title }
    LaunchedEffect(memory.content) { if (contentText != memory.content) contentText = memory.content }

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
                value = titleText,
                onValueChange = {
                    titleText = it
                    onChanged(memory.copy(title = it))
                },
                label = { Text("记忆标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = contentText,
                onValueChange = {
                    contentText = it
                    onChanged(memory.copy(content = it))
                },
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
private fun PersistedMemoryCard(
    memory: GlobalMemory,
    onChanged: (GlobalMemory) -> Unit,
    onDelete: () -> Unit
) {
    // Local state decouples cursor from async persistence
    var titleText by remember(memory.id) { mutableStateOf(memory.title) }
    var contentText by remember(memory.id) { mutableStateOf(memory.content) }
    LaunchedEffect(memory.title) { if (titleText != memory.title) titleText = memory.title }
    LaunchedEffect(memory.content) { if (contentText != memory.content) contentText = memory.content }

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
                value = titleText,
                onValueChange = {
                    titleText = it
                    onChanged(memory.copy(title = it))
                },
                label = { Text("记忆标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = contentText,
                onValueChange = {
                    contentText = it
                    onChanged(memory.copy(content = it))
                },
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
    onRulesChanged: (List<GlobalRule>) -> Unit,
    showHeader: Boolean = true,
    showDivider: Boolean = true
) {
    if (showDivider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }

    if (showHeader) {
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
    }

    if (!showHeader) {
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
            Text("新增规则", fontSize = 12.sp)
        }
    }

    RuleDraftImportCard(
        onImportDrafts = { imported ->
            if (imported.isNotEmpty()) {
                onRulesChanged(rules + imported)
            }
        },
        buttonLabel = "手动导入规则"
    )

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
    // Local state decouples cursor from parent list updates.
    var titleText by remember(rule.id) { mutableStateOf(rule.title) }
    var contentText by remember(rule.id) { mutableStateOf(rule.content) }
    LaunchedEffect(rule.title) { if (titleText != rule.title) titleText = rule.title }
    LaunchedEffect(rule.content) { if (contentText != rule.content) contentText = rule.content }

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
                value = titleText,
                onValueChange = {
                    titleText = it
                    onChanged(rule.copy(title = it))
                },
                label = { Text("规则标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = contentText,
                onValueChange = {
                    contentText = it
                    onChanged(rule.copy(content = it))
                },
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
    initialConfig: com.murong.agent.core.mcp.McpServerConfig? = null,
    onAdd: (com.murong.agent.core.mcp.McpServerConfig) -> Unit,
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
                com.murong.agent.core.mcp.McpTransportType.STDIO -> 0
                com.murong.agent.core.mcp.McpTransportType.SSE -> 1
                com.murong.agent.core.mcp.McpTransportType.STREAMABLE_HTTP -> 2
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
                        Text("Authorization=Bearer xxx\nX-Client=murong-agent", fontSize = 12.sp)
                    }
                )
            }

            OutlinedTextField(
                value = requestTimeoutMs,
                onValueChange = { requestTimeoutMs = it },
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
                            0 -> com.murong.agent.core.mcp.McpTransportType.STDIO
                            1 -> com.murong.agent.core.mcp.McpTransportType.SSE
                            else -> com.murong.agent.core.mcp.McpTransportType.STREAMABLE_HTTP
                        }
                        val config = com.murong.agent.core.mcp.McpServerConfig(
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

internal fun buildMcpConfigSummary(config: McpServerConfig): String {
    val transportLabel = when (config.transport) {
        com.murong.agent.core.mcp.McpTransportType.STDIO -> "stdio"
        com.murong.agent.core.mcp.McpTransportType.SSE -> "SSE"
        com.murong.agent.core.mcp.McpTransportType.STREAMABLE_HTTP -> "streamable-http"
    }
    val details = buildList {
        add(transportLabel)
        add(
            when (config.source) {
                McpConfigSource.MANUAL -> "manual"
                McpConfigSource.IMPORTED_DRAFT -> "draft"
                McpConfigSource.MCP_JSON -> ".mcp.json"
            }
        )
        when (config.transport) {
            com.murong.agent.core.mcp.McpTransportType.STDIO -> {
                config.command.takeIf { it.isNotBlank() }?.let { add(it) }
                if (config.cwd.isNotBlank()) add("cwd")
                if (config.env.isNotEmpty()) add("env ${config.env.size}")
            }

            com.murong.agent.core.mcp.McpTransportType.SSE,
            com.murong.agent.core.mcp.McpTransportType.STREAMABLE_HTTP -> {
                config.url.takeIf { it.isNotBlank() }?.let { add(it) }
                if (config.headers.isNotEmpty()) add("headers ${config.headers.size}")
            }
        }
        config.requestTimeoutMs?.let { add("${it}ms") }
        if (config.sourcePath.isNotBlank()) {
            add("src ${config.sourcePath.replace('\\', '/').substringAfterLast('/')}")
        }
        if (config.trustedReadOnlyTools.isNotEmpty()) add("ro ${config.trustedReadOnlyTools.size}")
        if (!config.autoStart) add("手动连接")
    }
    return details.joinToString(" · ")
}

internal fun buildMcpStatusSummary(
    status: McpServerStatus?,
    hasSavedConfig: Boolean
): String {
    val toolCount = status?.toolCount ?: 0
    val connectionLabel = when {
        status?.connected == true -> "✅ 已连接"
        hasSavedConfig -> "⏸ 未连接"
        else -> "❌ 未知"
    }
    val failureSummary = status?.failureRecord?.let { " · 最近 ${it.stage.name.lowercase()} 失败，但配置已保留" }.orEmpty()
    return "$toolCount 个工具 · $connectionLabel$failureSummary"
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
