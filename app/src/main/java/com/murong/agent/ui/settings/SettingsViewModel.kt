package com.murong.agent.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.murong.agent.automation.SavedWorkflowScheduler
import com.murong.agent.automation.ForegroundSavedWorkflowExecutor
import com.murong.agent.automation.ExternalWorkflowAccessStatus
import com.murong.agent.automation.ExternalWorkflowAccessStore
import com.murong.agent.backup.MurongBackupManager
import com.murong.agent.backup.MurongBackupSettingsSnapshot
import com.murong.agent.backup.MurongBackupStatus
import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.common.shell.KeepShellPublic
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.config.AgentBackendKind
import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.RelayConfig
import com.murong.agent.core.config.ProviderBalanceService
import com.murong.agent.core.loop.ChatSessionManager
import com.murong.agent.core.codex.CodexAppServerClient
import com.murong.agent.core.codex.AndroidCodexAccountManager
import com.murong.agent.core.codex.CodexAccountReadResult
import com.murong.agent.core.codex.CodexAccountPoolSettings
import com.murong.agent.core.codex.CodexAccountPoolSnapshot
import com.murong.agent.core.codex.CodexConnectionPhase
import com.murong.agent.core.codex.CodexLoginStatus
import com.murong.agent.core.codex.CodexUserMessageSanitizer
import com.murong.agent.core.codex.readCodexAccountWithRetry
import com.murong.agent.core.github.AndroidGitHubAccountManager
import com.murong.agent.core.github.GitHubAccountCredentials
import com.murong.agent.core.github.GitHubAccountPoolSnapshot
import com.murong.agent.core.loop.SessionSummary
import com.murong.agent.core.mcp.McpRegistry
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpServerStatus
import com.murong.agent.core.provider.ProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class BalanceSyncUiState(
    val isSyncing: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

data class GitHubAuthUiState(
    val isLoading: Boolean = false,
    val viewerLogin: String? = null,
    val viewerName: String? = null,
    val authorizationUrl: String? = null,
    val callbackUri: String? = null,
    val pendingState: String? = null,
    val accountPool: GitHubAccountPoolSnapshot = GitHubAccountPoolSnapshot(),
    val message: String? = null,
    val error: String? = null
)

/** Login information intentionally excludes the private CODEX_HOME auth file. */
data class CodexChatGptUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val accountEmail: String? = null,
    val planType: String? = null,
    val requiresOpenaiAuth: Boolean? = null,
    val verificationUrl: String? = null,
    val userCode: String? = null,
    val loginId: String? = null,
    val accountPool: CodexAccountPoolSnapshot = CodexAccountPoolSnapshot(),
    val sessionPinned: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val currentVersionName: String? = null,
    val currentVersionCode: Int? = null,
    val latestVersionName: String? = null,
    val latestVersionCode: Int? = null,
    val fileName: String? = null,
    val downloadUrl: String? = null,
    val directDownloadUrl: String? = null,
    val updateMessage: String? = null,
    val changelog: String? = null,
    val publishedAt: String? = null,
    val forceUpdate: Boolean = false,
    val message: String? = null,
    val error: String? = null
) {
    val isUpdateAvailable: Boolean
        get() = currentVersionCode != null &&
            latestVersionCode != null &&
            latestVersionCode > currentVersionCode

    val hasRemoteRelease: Boolean
        get() = latestVersionCode != null ||
            !latestVersionName.isNullOrBlank() ||
            !downloadUrl.isNullOrBlank()

    val isInstallOrUpdateAvailable: Boolean
        get() = when {
            currentVersionCode == null -> hasRemoteRelease
            latestVersionCode == null -> false
            else -> latestVersionCode > currentVersionCode
        }

    val preferredDownloadUrl: String?
        get() = directDownloadUrl?.takeIf { it.isNotBlank() }
            ?: downloadUrl?.takeIf { it.isNotBlank() }
}

data class BackupRestoreUiState(
    val status: MurongBackupStatus = MurongBackupStatus(),
    val isBusy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val restartRequired: Boolean = false
)

data class ExternalWorkflowAutomationUiState(
    val status: ExternalWorkflowAccessStatus = ExternalWorkflowAccessStatus(),
    /** Plaintext is intentionally held in memory only until this one-time dialog is dismissed. */
    val oneTimeToken: String? = null,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    val configRepository: ConfigRepository,
    private val providerBalanceService: ProviderBalanceService,
    private val mcpRegistry: McpRegistry,
    private val chatSessionManager: ChatSessionManager,
    private val codexAppServer: CodexAppServerClient,
    private val codexAccountManager: AndroidCodexAccountManager,
    private val githubAccountManager: AndroidGitHubAccountManager,
    private val backupManager: MurongBackupManager
) : ViewModel() {
    private companion object {
        const val AUTO_BALANCE_SYNC_INTERVAL_MS = 10 * 60 * 1000L
    }

    private val githubJson = Json { ignoreUnknownKeys = true; isLenient = true }
    private val savedWorkflowScheduler = SavedWorkflowScheduler(context)
    private val externalWorkflowAccessStore = ExternalWorkflowAccessStore(context)
    private val foregroundSavedWorkflowExecutor = ForegroundSavedWorkflowExecutor(context, chatSessionManager)
    private val githubClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val config: StateFlow<ProviderConfig> = configRepository.configFlow
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), ProviderConfig())

    // ─── Root 检测 ──────────────────────────────
    private val _rootStatus = MutableStateFlow<Boolean?>(null)
    val rootStatus: StateFlow<Boolean?> = _rootStatus.asStateFlow()

    private val _isCheckingRoot = MutableStateFlow(false)
    val isCheckingRoot: StateFlow<Boolean> = _isCheckingRoot.asStateFlow()

    // ─── MCP 配置 ───────────────────────────────
    private val _mcpServers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val mcpServers: StateFlow<List<McpServerConfig>> = _mcpServers.asStateFlow()

    private val _mcpStatuses = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val mcpStatuses: StateFlow<List<McpServerStatus>> = _mcpStatuses.asStateFlow()

    private val _mcpConnectError = MutableStateFlow<String?>(null)
    val mcpConnectError: StateFlow<String?> = _mcpConnectError.asStateFlow()

    private val _savedWorkflows = MutableStateFlow<List<SavedWorkflowDefinition>>(emptyList())
    val savedWorkflows: StateFlow<List<SavedWorkflowDefinition>> = _savedWorkflows.asStateFlow()

    private val _externalWorkflowAutomationState = MutableStateFlow(
        ExternalWorkflowAutomationUiState(status = externalWorkflowAccessStore.status())
    )
    val externalWorkflowAutomationState: StateFlow<ExternalWorkflowAutomationUiState> =
        _externalWorkflowAutomationState.asStateFlow()

    private val _backupRestoreState = MutableStateFlow(BackupRestoreUiState(status = backupManager.status()))
    val backupRestoreState: StateFlow<BackupRestoreUiState> = _backupRestoreState.asStateFlow()

    // ─── 会话列表 ───────────────────────────────
    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _durableGlobalMemories = MutableStateFlow<List<GlobalMemory>>(emptyList())
    val durableGlobalMemories: StateFlow<List<GlobalMemory>> = _durableGlobalMemories.asStateFlow()

    private val _balanceSyncStates = MutableStateFlow<Map<String, BalanceSyncUiState>>(emptyMap())
    val balanceSyncStates: StateFlow<Map<String, BalanceSyncUiState>> = _balanceSyncStates.asStateFlow()
    private val _providerModelCatalogs =
        MutableStateFlow<Map<String, ProviderModelCatalogUiState>>(emptyMap())
    val providerModelCatalogs: StateFlow<Map<String, ProviderModelCatalogUiState>> =
        _providerModelCatalogs.asStateFlow()

    private val _gitHubAuthState = MutableStateFlow(
        GitHubAuthUiState(accountPool = githubAccountManager.state.value),
    )
    val gitHubAuthState: StateFlow<GitHubAuthUiState> = _gitHubAuthState.asStateFlow()
    private var lastHandledGitHubCallback: String? = null

    private val _codexChatGptState = MutableStateFlow(CodexChatGptUiState())
    val codexChatGptState: StateFlow<CodexChatGptUiState> = _codexChatGptState.asStateFlow()
    private var lastCompletedCodexLoginId: String? = null
    private var codexLoginConfirmationId: String? = null
    private var codexLoginConfirmationJob: Job? = null

    private val _appUpdateState = MutableStateFlow(AppUpdateUiState())
    val appUpdateState: StateFlow<AppUpdateUiState> = _appUpdateState.asStateFlow()

    private val _extensionUpdateState = MutableStateFlow(AppUpdateUiState())
    val extensionUpdateState: StateFlow<AppUpdateUiState> = _extensionUpdateState.asStateFlow()

    init {
        _mcpServers.value = mcpRegistry.loadConfigs()
        _savedWorkflows.value = savedWorkflowScheduler.list()
        _sessions.value = chatSessionManager.listSessions()
        checkRoot()
        refreshDurableGlobalMemories()
        viewModelScope.launch(Dispatchers.IO) {
            configRepository.configFlow.collect { currentConfig ->
                if (currentConfig.githubToken.isNotBlank()) {
                    runCatching {
                        githubAccountManager.migrateLegacyAccount(
                            token = currentConfig.githubToken,
                            backendSessionToken = currentConfig.githubBackendSessionToken,
                            login = currentConfig.githubViewerLogin,
                            name = currentConfig.githubViewerName,
                            avatarUrl = currentConfig.githubViewerAvatarUrl,
                            apiBaseUrl = currentConfig.getGitHubApiBaseUrl(),
                        )
                    }.onFailure { error ->
                        _gitHubAuthState.value = _gitHubAuthState.value.copy(
                            error = "迁移 GitHub 账号失败：${error.message ?: "未知错误"}",
                        )
                    }
                }
                _providerModelCatalogs.value = ProviderRegistry.getAllProviders().associate { provider ->
                    val existing = _providerModelCatalogs.value[provider.id]
                    provider.id to (existing ?: ProviderModelCatalogUiState(providerId = provider.id)).copy(
                        models = mergeProviderModelCandidates(
                            providerId = provider.id,
                            currentModel = currentConfig.getResolvedModel(provider.id),
                            fetchedModels = existing?.models.orEmpty()
                        )
                    )
                }
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    viewerLogin = currentConfig.githubViewerLogin.ifBlank { null },
                    viewerName = currentConfig.githubViewerName.ifBlank { null }
                )
                if (
                    currentConfig.isGitHubSignedIn() &&
                    _gitHubAuthState.value.viewerLogin.isNullOrBlank() &&
                    !_gitHubAuthState.value.isLoading
                ) {
                    refreshGitHubAuthStatus()
                }
                val activeProviderId = currentConfig.activeProviderId
                val lastSyncedAt = currentConfig.getBalanceSyncedAt(activeProviderId) ?: 0L
                val shouldAutoSyncBalance = providerBalanceService.supportsBalanceFetch(activeProviderId, currentConfig) &&
                    (_balanceSyncStates.value[activeProviderId]?.isSyncing != true) &&
                    (lastSyncedAt <= 0L || System.currentTimeMillis() - lastSyncedAt >= AUTO_BALANCE_SYNC_INTERVAL_MS)
                if (shouldAutoSyncBalance) {
                    refreshProviderBalance(activeProviderId)
                }
            }
        }
        viewModelScope.launch {
            githubAccountManager.state.collect { pool ->
                val active = pool.accounts.firstOrNull { it.active }
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    accountPool = pool,
                    viewerLogin = active?.login?.ifBlank { null }
                        ?: _gitHubAuthState.value.viewerLogin,
                    viewerName = active?.name?.ifBlank { null }
                        ?: _gitHubAuthState.value.viewerName,
                )
            }
        }
        viewModelScope.launch {
            codexAccountManager.state.collect { pool ->
                _codexChatGptState.value = _codexChatGptState.value.copy(
                    accountPool = pool,
                    sessionPinned = codexAccountManager.isSessionPinned(
                        chatSessionManager.state.value.sessionId,
                    ),
                )
            }
        }
        viewModelScope.launch {
            var lastSessionId: String? = null
            chatSessionManager.state.collect { session ->
                if (session.sessionId != lastSessionId) {
                    lastSessionId = session.sessionId
                    _codexChatGptState.value = _codexChatGptState.value.copy(
                        sessionPinned = codexAccountManager.isSessionPinned(session.sessionId),
                    )
                }
            }
        }
        viewModelScope.launch {
            codexAppServer.state.collect { runtime ->
                val login = runtime.login
                _codexChatGptState.value = _codexChatGptState.value.copy(
                    isLoading = runtime.connectionPhase == CodexConnectionPhase.INITIALIZING,
                    isLoggedIn = runtime.account != null,
                    accountEmail = runtime.account?.email,
                    planType = runtime.account?.planType ?: runtime.planType,
                    requiresOpenaiAuth = runtime.requiresOpenaiAuth,
                    verificationUrl = login.verificationUrl,
                    userCode = login.userCode,
                    loginId = login.loginId,
                    message = when (login.status) {
                        CodexLoginStatus.WAITING_FOR_DEVICE_AUTHORIZATION -> "请在浏览器完成 ChatGPT 授权。"
                        CodexLoginStatus.SUCCEEDED -> "ChatGPT 登录已完成，正在读取账户信息。"
                        CodexLoginStatus.CANCEL_REQUESTED -> "已取消 ChatGPT 登录。"
                        else -> _codexChatGptState.value.message
                    },
                    error = login.error?.let(::sanitizeCodexMessage)
                )
                when (login.status) {
                    CodexLoginStatus.WAITING_FOR_DEVICE_AUTHORIZATION -> {
                        keepCheckingCodexLogin(login.loginId)
                    }
                    CodexLoginStatus.SUCCEEDED,
                    CodexLoginStatus.CANCEL_REQUESTED,
                    CodexLoginStatus.FAILED,
                    CodexLoginStatus.IDLE -> stopCheckingCodexLogin()
                }
                val completedLoginId = login.loginId
                if (
                    login.status == CodexLoginStatus.SUCCEEDED &&
                    completedLoginId != null &&
                    completedLoginId != lastCompletedCodexLoginId
                ) {
                    lastCompletedCodexLoginId = completedLoginId
                    refreshCodexChatGptStatus(forceRefreshToken = true)
                }
            }
        }
    }

    fun selectAgentBackend(kind: AgentBackendKind) {
        viewModelScope.launch {
            configRepository.saveConfig(configRepository.getConfig().copy(activeAgentBackend = kind))
        }
    }

    fun refreshCodexChatGptStatus(forceRefreshToken: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val confirmingLogin = forceRefreshToken || !_codexChatGptState.value.loginId.isNullOrBlank()
            _codexChatGptState.value = _codexChatGptState.value.copy(
                isLoading = true,
                error = null,
                message = "正在检查 Codex / ChatGPT 登录状态…"
            )
            runCatching {
                codexAppServer.start()
                val account = readCodexAccountWithRetry(confirmingLogin) { refreshToken ->
                    codexAppServer.accountRead(refreshToken = refreshToken)
                }
                val isChatGptAccount = account.account?.type.equals("chatgpt", ignoreCase = true)
                if (isChatGptAccount) {
                    // A successful official login must also make the chat surface
                    // use the official backend. Leaving the prior provider active
                    // makes the UI misleading and keeps consuming an API relay.
                    val currentConfig = configRepository.getConfig()
                    if (currentConfig.activeAgentBackend != AgentBackendKind.CODEX_CHATGPT) {
                        configRepository.saveConfig(
                            currentConfig.copy(activeAgentBackend = AgentBackendKind.CODEX_CHATGPT),
                        )
                    }
                    // Fetch the server-owned quota snapshot now. Failures here do
                    // not invalidate a successful login; ChatViewModel can retry.
                    val rates = runCatching { codexAppServer.accountRateLimitsRead() }.getOrNull()
                    CodexAccountRefreshResult(
                        account = account,
                        isChatGptAccount = true,
                        authCaptured = captureCodexRuntimeWithRetry(confirmingLogin) {
                            codexAccountManager.captureRuntime(account.account, rates)
                        },
                    )
                } else {
                    CodexAccountRefreshResult(
                        account = account,
                        isChatGptAccount = false,
                        authCaptured = captureCodexRuntimeWithRetry(confirmingLogin) {
                            codexAccountManager.captureRuntime(account.account, null)
                        },
                    )
                }
            }.onSuccess { refreshed ->
                val account = refreshed.account
                val durableLogin = codexAccountManager.state.value.accounts
                    .firstOrNull { it.active }
                    ?.loggedIn == true
                _codexChatGptState.value = _codexChatGptState.value.copy(
                    isLoading = false,
                    isLoggedIn = account.account != null,
                    accountEmail = account.account?.email,
                    planType = account.account?.planType,
                    requiresOpenaiAuth = account.requiresOpenaiAuth,
                    verificationUrl = if (account.account != null) null else _codexChatGptState.value.verificationUrl,
                    userCode = if (account.account != null) null else _codexChatGptState.value.userCode,
                    loginId = if (account.account != null) null else _codexChatGptState.value.loginId,
                    message = if (refreshed.isChatGptAccount && refreshed.authCaptured) {
                        "已连接 ChatGPT / Codex，聊天已切换到官方后端。"
                    } else if (refreshed.isChatGptAccount) {
                        "ChatGPT 已连接，但官方登录凭据尚未写入安全存储；请暂时不要切号或重启，并稍后刷新状态。"
                    } else if (account.account != null) {
                        "已连接 Codex 账户。"
                    } else if (durableLogin) {
                        "已保存 Codex 账号，但官方服务暂时未确认登录状态；请稍后重试。"
                    } else {
                        "尚未登录 ChatGPT。"
                    },
                    error = null
                )
            }.onFailure { error ->
                _codexChatGptState.value = _codexChatGptState.value.copy(
                    isLoading = false,
                    error = "无法刷新 ChatGPT 登录状态：${sanitizeCodexMessage(error.message)}",
                    message = "若浏览器已完成授权，请稍后重试；授权过程不需要跳回本应用。"
                )
            }
        }
    }

    private fun keepCheckingCodexLogin(loginId: String?) {
        val attemptId = loginId?.takeIf { it.isNotBlank() } ?: return
        if (codexLoginConfirmationId == attemptId && codexLoginConfirmationJob?.isActive == true) return
        stopCheckingCodexLogin()
        codexLoginConfirmationId = attemptId
        codexLoginConfirmationJob = viewModelScope.launch(Dispatchers.IO) {
            repeat(300) { index ->
                delay(if (index == 0) 1_000L else 2_000L)
                if (_codexChatGptState.value.loginId != attemptId) return@launch
                val account = runCatching {
                    codexAppServer.accountRead(refreshToken = false)
                }.getOrNull()
                if (account?.account != null) {
                    refreshCodexChatGptStatus(forceRefreshToken = true)
                    return@launch
                }
            }
        }
    }

    private fun stopCheckingCodexLogin() {
        codexLoginConfirmationJob?.cancel()
        codexLoginConfirmationJob = null
        codexLoginConfirmationId = null
    }

    private suspend fun captureCodexRuntimeWithRetry(
        confirmingLogin: Boolean,
        capture: () -> Boolean,
    ): Boolean {
        val delays = if (confirmingLogin) {
            listOf(0L, 250L, 750L, 1_500L, 3_000L, 5_000L)
        } else {
            listOf(0L)
        }
        delays.forEach { waitMillis ->
            if (waitMillis > 0L) delay(waitMillis)
            if (capture()) return true
        }
        return false
    }

    private data class CodexAccountRefreshResult(
        val account: CodexAccountReadResult,
        val isChatGptAccount: Boolean,
        val authCaptured: Boolean,
    )

    fun startCodexChatGptLogin() {
        viewModelScope.launch(Dispatchers.IO) {
            val previousLoginId = _codexChatGptState.value.loginId
            _codexChatGptState.value = _codexChatGptState.value.copy(
                isLoading = true,
                verificationUrl = null,
                userCode = null,
                loginId = null,
                error = null,
                message = "正在获取 ChatGPT 设备码…"
            )
            runCatching {
                // A failed or abandoned device-code exchange can leave the server
                // polling the old login id. A fresh process gives every retry a
                // clean request and, crucially, a fresh browser-open attempt.
                if (previousLoginId.isNullOrBlank()) {
                    codexAppServer.start()
                } else {
                    runCatching { codexAppServer.cancelLogin(previousLoginId) }
                    codexAppServer.restart()
                }
                codexAppServer.startDeviceCodeLogin()
            }.onSuccess { deviceCode ->
                _codexChatGptState.value = _codexChatGptState.value.copy(
                    isLoading = false,
                    verificationUrl = deviceCode.verificationUrl,
                    userCode = deviceCode.userCode,
                    loginId = deviceCode.loginId,
                    message = "请在浏览器输入设备码完成授权。",
                    error = null
                )
            }.onFailure { error ->
                _codexChatGptState.value = _codexChatGptState.value.copy(
                    isLoading = false,
                    verificationUrl = null,
                    userCode = null,
                    loginId = null,
                    error = "无法发起 ChatGPT 登录：${sanitizeCodexMessage(error.message)}"
                )
            }
        }
    }

    fun addCodexChatGptAccount() {
        if (chatSessionManager.state.value.isProcessing) {
            _codexChatGptState.value = _codexChatGptState.value.copy(
                error = "当前任务仍在执行，完成后才能切换或添加账号。",
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _codexChatGptState.value = _codexChatGptState.value.copy(isLoading = true, error = null)
            runCatching {
                codexAppServer.stop()
                val account = codexAccountManager.createAccount()
                codexAccountManager.activateAccount(account.id)
                codexAppServer.start()
                codexAppServer.startDeviceCodeLogin()
            }.onSuccess { deviceCode ->
                _codexChatGptState.value = _codexChatGptState.value.copy(
                    isLoading = false,
                    verificationUrl = deviceCode.verificationUrl,
                    userCode = deviceCode.userCode,
                    loginId = deviceCode.loginId,
                    message = "已创建独立账号槽位，请在浏览器完成授权。",
                    error = null,
                )
            }.onFailure { error ->
                _codexChatGptState.value = _codexChatGptState.value.copy(
                    isLoading = false,
                    error = "添加账号失败：${sanitizeCodexMessage(error.message)}",
                )
            }
        }
    }

    fun activateCodexChatGptAccount(accountId: String) {
        if (chatSessionManager.state.value.isProcessing) {
            _codexChatGptState.value = _codexChatGptState.value.copy(
                error = "当前任务仍在执行；为保护上下文，任务完成前不会切号。",
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _codexChatGptState.value = _codexChatGptState.value.copy(isLoading = true, error = null)
            runCatching {
                codexAppServer.stop()
                codexAccountManager.activateAccount(accountId)
                codexAppServer.start()
                val account = codexAppServer.accountRead()
                val rates = if (account.account != null) {
                    runCatching { codexAppServer.accountRateLimitsRead() }.getOrNull()
                } else null
                codexAccountManager.captureRuntime(account.account, rates)
                account
            }.onSuccess { account ->
                _codexChatGptState.value = _codexChatGptState.value.copy(
                    isLoading = false,
                    isLoggedIn = account.account != null,
                    accountEmail = account.account?.email,
                    planType = account.account?.planType,
                    message = "已切换 Codex 账号。",
                    error = null,
                )
            }.onFailure { error ->
                _codexChatGptState.value = _codexChatGptState.value.copy(
                    isLoading = false,
                    error = "切换账号失败：${sanitizeCodexMessage(error.message)}",
                )
            }
        }
    }

    fun removeCodexChatGptAccount(accountId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { codexAccountManager.removeAccount(accountId) }
                .onFailure { error ->
                    _codexChatGptState.value = _codexChatGptState.value.copy(
                        error = "删除账号失败：${sanitizeCodexMessage(error.message)}",
                    )
                }
        }
    }

    fun setCodexChatGptAccountEnabled(accountId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { codexAccountManager.setAccountEnabled(accountId, enabled) }
                .onFailure { error ->
                    _codexChatGptState.value = _codexChatGptState.value.copy(
                        error = "账号状态更新失败：${sanitizeCodexMessage(error.message)}",
                    )
                }
        }
    }

    fun updateCodexAccountPoolSettings(autoSwitch: Boolean, reservePercent: Int, cooldownMinutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                codexAccountManager.updateSettings(
                    CodexAccountPoolSettings(autoSwitch, reservePercent, cooldownMinutes),
                )
            }.onSuccess {
                _codexChatGptState.value = _codexChatGptState.value.copy(message = "无感切号策略已保存。", error = null)
            }.onFailure { error ->
                _codexChatGptState.value = _codexChatGptState.value.copy(
                    error = sanitizeCodexMessage(error.message),
                )
            }
        }
    }

    fun setCurrentSessionCodexAccountPinned(pinned: Boolean) {
        val sessionId = chatSessionManager.state.value.sessionId
        codexAccountManager.setSessionPinned(sessionId, pinned)
        _codexChatGptState.value = _codexChatGptState.value.copy(sessionPinned = pinned)
    }

    fun cancelCodexChatGptLogin() {
        val loginId = _codexChatGptState.value.loginId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { codexAppServer.cancelLogin(loginId) }
                .onSuccess {
                    _codexChatGptState.value = _codexChatGptState.value.copy(
                        isLoading = false,
                        verificationUrl = null,
                        userCode = null,
                        loginId = null,
                        message = "已取消本次 ChatGPT 登录。",
                        error = null,
                    )
                }
                .onFailure { error ->
                    _codexChatGptState.value = _codexChatGptState.value.copy(
                        error = "取消登录失败：${sanitizeCodexMessage(error.message)}"
                    )
                }
        }
    }

    fun logoutCodexChatGpt() {
        viewModelScope.launch(Dispatchers.IO) {
            _codexChatGptState.value = _codexChatGptState.value.copy(isLoading = true, error = null)
            runCatching { codexAppServer.logout() }
                .onSuccess {
                    codexAccountManager.clearActiveLogin()
                    _codexChatGptState.value = _codexChatGptState.value.copy(
                        isLoading = false,
                        isLoggedIn = false,
                        accountEmail = null,
                        planType = null,
                        verificationUrl = null,
                        userCode = null,
                        loginId = null,
                        accountPool = codexAccountManager.state.value,
                        message = "已退出 ChatGPT / Codex。",
                        error = null,
                    )
                }
                .onFailure { error ->
                    _codexChatGptState.value = _codexChatGptState.value.copy(
                        isLoading = false,
                        error = "退出失败：${sanitizeCodexMessage(error.message)}"
                    )
                }
        }
    }

    private fun sanitizeCodexMessage(value: String?): String = CodexUserMessageSanitizer.sanitize(value)

    fun updateConfig(newConfig: ProviderConfig) {
        viewModelScope.launch {
            configRepository.saveConfig(newConfig)
            refreshDurableGlobalMemories()
        }
    }

    fun updateApiKey(providerId: String, apiKey: String) {
        viewModelScope.launch { configRepository.updateApiKey(providerId, apiKey) }
    }

    fun updateBaseUrl(providerId: String, baseUrl: String) {
        viewModelScope.launch { configRepository.updateBaseUrl(providerId, baseUrl) }
    }

    fun updateModel(providerId: String, model: String) {
        viewModelScope.launch { configRepository.updateModel(providerId, model) }
    }

    fun addRelay(providerId: String) {
        viewModelScope.launch {
            configRepository.addRelay(
                providerId,
                RelayConfig(id = "relay-${System.currentTimeMillis()}")
            )
        }
    }

    fun selectRelay(providerId: String, relayId: String) {
        viewModelScope.launch { configRepository.selectRelay(providerId, relayId) }
    }

    fun setActiveProvider(providerId: String) {
        viewModelScope.launch { configRepository.setActiveProvider(providerId) }
    }

    fun checkRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            _isCheckingRoot.value = true
            try {
                _rootStatus.value = KeepShellPublic.checkRoot()
            } catch (e: Exception) {
                _rootStatus.value = false
            } finally {
                _isCheckingRoot.value = false
            }
        }
    }

    fun refreshDurableGlobalMemories() {
        viewModelScope.launch(Dispatchers.IO) {
            _durableGlobalMemories.value = configRepository.listDurableGlobalMemories()
        }
    }

    fun refreshProviderModels(providerId: String) {
        val provider = ProviderRegistry.getProvider(providerId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val config = configRepository.getConfig()
            val existing = _providerModelCatalogs.value[providerId] ?: ProviderModelCatalogUiState(
                providerId = providerId,
                models = mergeProviderModelCandidates(
                    providerId = providerId,
                    currentModel = config.getResolvedModel(providerId)
                )
            )
            _providerModelCatalogs.value = _providerModelCatalogs.value + (
                providerId to existing.copy(
                    isLoading = true,
                    message = null,
                    error = null
                )
            )
            fetchProviderModelCatalog(config, provider)
                .onSuccess { catalog ->
                    _providerModelCatalogs.value = _providerModelCatalogs.value + (
                        providerId to existing.copy(
                            models = mergeProviderModelCandidates(
                                providerId = providerId,
                                currentModel = config.getResolvedModel(providerId),
                                fetchedModels = catalog.models
                            ),
                            sourceLabel = catalog.sourceLabel,
                            isLoading = false,
                            message = "已同步 ${catalog.models.size} 个模型",
                            error = null,
                            syncedAt = catalog.syncedAt
                        )
                    )
                }
                .onFailure { error ->
                    _providerModelCatalogs.value = _providerModelCatalogs.value + (
                        providerId to existing.copy(
                            isLoading = false,
                            message = null,
                            error = error.message ?: "读取模型列表失败"
                        )
                    )
                }
        }
    }

    fun updateDurableGlobalMemory(memory: GlobalMemory) {
        viewModelScope.launch(Dispatchers.IO) {
            configRepository.updateDurableGlobalMemory(memory)
            _durableGlobalMemories.value = configRepository.listDurableGlobalMemories()
        }
    }

    fun deleteDurableGlobalMemory(memoryId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            configRepository.deleteDurableGlobalMemory(memoryId)
            _durableGlobalMemories.value = configRepository.listDurableGlobalMemories()
        }
    }

    // ─── MCP 方法 ─────────────────────────────

    fun addMcpServer(config: McpServerConfig) {
        val updated = _mcpServers.value.toMutableList()
        val idx = updated.indexOfFirst { it.name == config.name }
        if (idx >= 0) updated[idx] = config else updated.add(config)
        _mcpServers.value = updated
        mcpRegistry.saveConfigs(updated)
    }

    fun importMcpServers(configs: List<McpServerConfig>) {
        if (configs.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = _mcpServers.value.toMutableList()
            configs.forEach { config ->
                val idx = updated.indexOfFirst { it.name == config.name }
                if (idx >= 0) updated[idx] = config else updated.add(config)
            }
            _mcpServers.value = updated
            mcpRegistry.saveConfigs(updated)

            val autoStartNames = configs
                .asSequence()
                .filter { it.enabled && it.autoStart }
                .map { it.name }
                .toSet()

            if (autoStartNames.isNotEmpty()) {
                runCatching { mcpRegistry.connectAll(updated) }
            }
            val statuses = mcpRegistry.getServerStatuses()
            _mcpStatuses.value = statuses
            _mcpConnectError.value = buildMcpImportFailureMessage(
                importedCount = configs.size,
                failedCount = statuses.count { it.name in autoStartNames && !it.connected && it.failureRecord != null }
            )
        }
    }

    fun removeMcpServer(name: String) {
        mcpRegistry.disconnect(name)
        _mcpServers.value = _mcpServers.value.filter { it.name != name }
        mcpRegistry.saveConfigs(_mcpServers.value)
    }

    fun connectMcpServers() {
        viewModelScope.launch(Dispatchers.IO) {
            _mcpConnectError.value = null
            try {
                val currentConfigs = _mcpServers.value
                mcpRegistry.connectAll(currentConfigs)
                val statuses = mcpRegistry.getServerStatuses()
                _mcpStatuses.value = statuses
                _mcpConnectError.value = buildMcpConnectFailureMessage(
                    configs = currentConfigs,
                    statuses = statuses
                )
            } catch (e: Exception) {
                _mcpConnectError.value = e.message
            }
        }
    }

    fun refreshMcpStatus() {
        _mcpStatuses.value = mcpRegistry.getServerStatuses()
    }

    fun saveSavedWorkflow(workflow: SavedWorkflowDefinition) {
        viewModelScope.launch(Dispatchers.IO) {
            savedWorkflowScheduler.save(workflow)
            _savedWorkflows.value = savedWorkflowScheduler.list()
        }
    }

    fun deleteSavedWorkflow(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            savedWorkflowScheduler.delete(id)
            _savedWorkflows.value = savedWorkflowScheduler.list()
        }
    }

    fun runSavedWorkflowNow(id: String, foregroundConfirmed: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (foregroundConfirmed) {
                val workflow = savedWorkflowScheduler.beginForegroundRun(id)
                if (workflow != null) {
                    val startedAt = workflow.lastRun?.startedAt ?: System.currentTimeMillis()
                    val result = foregroundSavedWorkflowExecutor.execute(
                        workflow = workflow,
                        config = configRepository.getConfig()
                    )
                    savedWorkflowScheduler.finishForegroundRun(workflow.id, startedAt, result)
                }
            } else {
                savedWorkflowScheduler.runNow(id)
            }
            _savedWorkflows.value = savedWorkflowScheduler.list()
        }
    }

    fun refreshSavedWorkflows() {
        viewModelScope.launch(Dispatchers.IO) {
            _savedWorkflows.value = savedWorkflowScheduler.list()
            _externalWorkflowAutomationState.value = _externalWorkflowAutomationState.value.copy(
                status = externalWorkflowAccessStore.status()
            )
        }
    }

    fun enableExternalWorkflowAutomation() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { externalWorkflowAccessStore.enableWithNewToken() }
                .onSuccess { generated ->
                    _externalWorkflowAutomationState.value = ExternalWorkflowAutomationUiState(
                        status = generated.status,
                        oneTimeToken = generated.token,
                        message = "外部自动化已启用；请立即保存令牌。"
                    )
                }
                .onFailure { error ->
                    _externalWorkflowAutomationState.value = _externalWorkflowAutomationState.value.copy(
                        error = error.message ?: "无法启用外部自动化"
                    )
                }
        }
    }

    fun disableExternalWorkflowAutomation() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = externalWorkflowAccessStore.disable()
            _externalWorkflowAutomationState.value = ExternalWorkflowAutomationUiState(
                status = status,
                message = "外部自动化已关闭，旧令牌已作废。"
            )
        }
    }

    fun rotateExternalWorkflowToken() = enableExternalWorkflowAutomation()

    fun clearOneTimeExternalWorkflowToken() {
        _externalWorkflowAutomationState.value = _externalWorkflowAutomationState.value.copy(oneTimeToken = null)
    }

    fun refreshBackupStatus() {
        _backupRestoreState.value = _backupRestoreState.value.copy(status = backupManager.status())
    }

    fun suggestedBackupFileName(): String = backupManager.suggestedManualFileName()

    fun updateBackupSettings(settings: MurongBackupSettingsSnapshot) {
        if (_backupRestoreState.value.isBusy) return
        viewModelScope.launch {
            _backupRestoreState.value = _backupRestoreState.value.copy(isBusy = true, message = null, error = null)
            runCatching { backupManager.updateSettings(settings) }
                .onSuccess { status ->
                    _backupRestoreState.value = BackupRestoreUiState(
                        status = status,
                        message = if (status.settings.dailyBackupEnabled) "每日完整备份已启用" else "每日完整备份已关闭"
                    )
                }
                .onFailure { error ->
                    _backupRestoreState.value = _backupRestoreState.value.copy(
                        isBusy = false,
                        error = error.message ?: "无法更新备份设置"
                    )
                }
        }
    }

    fun exportManualBackup(uri: Uri) {
        if (_backupRestoreState.value.isBusy) return
        viewModelScope.launch {
            _backupRestoreState.value = _backupRestoreState.value.copy(
                isBusy = true,
                message = "正在创建完整备份…",
                error = null,
                restartRequired = false
            )
            runCatching { backupManager.exportManualBackup(uri) }
                .onSuccess { result ->
                    _backupRestoreState.value = BackupRestoreUiState(
                        status = backupManager.status(),
                        message = result.message
                    )
                }
                .onFailure { error ->
                    _backupRestoreState.value = BackupRestoreUiState(
                        status = backupManager.status(),
                        error = error.message ?: "完整备份失败"
                    )
                }
        }
    }

    fun restoreBackup(uri: Uri) {
        if (_backupRestoreState.value.isBusy) return
        viewModelScope.launch {
            _backupRestoreState.value = _backupRestoreState.value.copy(
                isBusy = true,
                message = "正在校验备份并创建恢复前快照…",
                error = null,
                restartRequired = false
            )
            runCatching { backupManager.restoreFromUri(uri) }
                .onSuccess { result ->
                    _backupRestoreState.value = BackupRestoreUiState(
                        status = backupManager.status(),
                        message = buildString {
                            append(result.message)
                            result.preRestoreSnapshotName?.let { append("\n恢复前快照：$it") }
                        },
                        restartRequired = result.restartRequired
                    )
                    _mcpServers.value = mcpRegistry.loadConfigs()
                    _savedWorkflows.value = savedWorkflowScheduler.list()
                }
                .onFailure { error ->
                    _backupRestoreState.value = BackupRestoreUiState(
                        status = backupManager.status(),
                        error = error.message ?: "恢复失败，当前数据未被替换"
                    )
                }
        }
    }

    // ─── 会话管理 ─────────────────────────────

    fun deleteSession(sessionId: String) {
        chatSessionManager.deleteSession(sessionId)
        _sessions.value = chatSessionManager.listSessions()
    }

    fun refreshSessions() {
        _sessions.value = chatSessionManager.listSessions()
    }

    fun refreshProviderBalance(providerId: String) {
        viewModelScope.launch {
            _balanceSyncStates.value = _balanceSyncStates.value + (
                providerId to BalanceSyncUiState(isSyncing = true)
            )

            val config = configRepository.getConfig()
            providerBalanceService.fetchBalance(config, providerId)
                .onSuccess { snapshot ->
                    configRepository.saveConfig(
                        config.withBalanceInfo(
                            providerId = snapshot.providerId,
                            balanceUsd = snapshot.balance,
                            balanceCurrency = snapshot.currency,
                            syncedAt = snapshot.syncedAt,
                            source = snapshot.source,
                        )
                    )
                    _balanceSyncStates.value = _balanceSyncStates.value + (
                        providerId to BalanceSyncUiState(
                            isSyncing = false,
                            message = when (snapshot.source) {
                                com.murong.agent.core.config.BalanceDataSource.OFFICIAL_API -> "官方余额已同步"
                                com.murong.agent.core.config.BalanceDataSource.CUSTOM_ENDPOINT -> "接口余额已同步"
                                else -> "余额已同步"
                            }
                        )
                    )
                }
                .onFailure { error ->
                    _balanceSyncStates.value = _balanceSyncStates.value + (
                        providerId to BalanceSyncUiState(
                            isSyncing = false,
                            error = error.message ?: "余额同步失败"
                        )
                    )
                }
        }
    }

    fun supportsBalanceFetch(providerId: String): Boolean {
        return providerBalanceService.supportsBalanceFetch(
            providerId = providerId,
            config = config.value
        )
    }

    fun refreshGitHubAuthStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            var currentConfig = configRepository.getConfig()
            if (!currentConfig.isGitHubSignedIn()) {
                githubAccountManager.activeCredentials()?.let { credentials ->
                    currentConfig = saveActiveGitHubCredentials(currentConfig, credentials)
                }
            }
            if (!currentConfig.isGitHubSignedIn()) {
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    isLoading = false,
                    viewerLogin = null,
                    viewerName = null,
                    accountPool = githubAccountManager.state.value,
                    message = null,
                    error = "当前账号还没有登录 GitHub。",
                )
                return@launch
            }
            _gitHubAuthState.value = _gitHubAuthState.value.copy(isLoading = true, error = null, message = "正在校验 GitHub 登录状态...")
            val viewerResult = fetchGitHubViewer(currentConfig.getGitHubApiBaseUrl(), currentConfig.githubToken)
            if (viewerResult.success) {
                val resolvedLogin = viewerResult.viewerLogin.orEmpty()
                val resolvedName = viewerResult.viewerName.orEmpty()
                configRepository.saveConfig(
                    currentConfig.copy(
                        githubViewerLogin = resolvedLogin,
                        githubViewerName = resolvedName,
                        githubViewerAvatarUrl = viewerResult.avatarUrl.orEmpty(),
                    )
                )
                githubAccountManager.updateActiveIdentity(
                    login = resolvedLogin,
                    name = resolvedName,
                    avatarUrl = viewerResult.avatarUrl.orEmpty(),
                )
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    isLoading = false,
                    viewerLogin = viewerResult.viewerLogin,
                    viewerName = viewerResult.viewerName,
                    authorizationUrl = null,
                    accountPool = githubAccountManager.state.value,
                    message = "GitHub 已连接",
                    error = null
                )
            } else {
                githubAccountManager.markActiveCheckFailure(viewerResult.error ?: "GitHub 登录状态校验失败")
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    isLoading = false,
                    viewerLogin = currentConfig.githubViewerLogin.ifBlank { null },
                    viewerName = currentConfig.githubViewerName.ifBlank { null },
                    authorizationUrl = null,
                    accountPool = githubAccountManager.state.value,
                    message = null,
                    error = viewerResult.error ?: "GitHub 登录状态校验失败"
                )
            }
        }
    }

    fun clearGitHubToken() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = configRepository.getConfig()
            val activeCredentials = githubAccountManager.activeCredentials()
            val backendSessionToken = activeCredentials?.backendSessionToken
                ?.takeIf { it.isNotBlank() }
                ?: current.githubBackendSessionToken
            if (backendSessionToken.isNotBlank()) {
                notifyBackendLogout(
                    apiUrl = current.getMurongBackendAuthApiUrl(),
                    sessionToken = backendSessionToken,
                )
            }
            githubAccountManager.logoutAccount()
            configRepository.saveConfig(
                current.withoutActiveGitHubCredentials()
            )
            _gitHubAuthState.value = _gitHubAuthState.value.copy(
                isLoading = false,
                viewerLogin = null,
                viewerName = null,
                authorizationUrl = null,
                callbackUri = null,
                pendingState = null,
                accountPool = githubAccountManager.state.value,
                message = "已退出当前 GitHub 账号，其他账号未受影响。",
                error = null,
            )
        }
    }

    fun startGitHubOAuthLogin() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfig = configRepository.getConfig()
            val clientState = UUID.randomUUID().toString()
            _gitHubAuthState.value = _gitHubAuthState.value.copy(
                isLoading = true,
                authorizationUrl = Uri.parse(currentConfig.getMurongBackendAuthApiUrl())
                    .buildUpon()
                    .appendQueryParameter("action", "start")
                    .appendQueryParameter("client_redirect_uri", currentConfig.getMurongGitHubRedirectUri())
                    .appendQueryParameter("client_state", clientState)
                    .build()
                    .toString(),
                callbackUri = currentConfig.getMurongGitHubRedirectUri(),
                pendingState = clientState,
                accountPool = githubAccountManager.state.value,
                message = "正在打开 GitHub 授权页，授权完成后会自动添加账号。",
                error = null,
            )
        }
    }

    fun activateGitHubAccount(accountId: String) {
        if (chatSessionManager.state.value.isProcessing) {
            _gitHubAuthState.value = _gitHubAuthState.value.copy(
                error = "当前任务仍在执行，任务完成后才能切换 GitHub 账号。",
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _gitHubAuthState.value = _gitHubAuthState.value.copy(
                isLoading = true,
                message = "正在切换 GitHub 账号...",
                error = null,
            )
            runCatching {
                val credentials = githubAccountManager.activateAccount(accountId)
                saveActiveGitHubCredentials(configRepository.getConfig(), credentials)
                credentials
            }.onSuccess { credentials ->
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    isLoading = false,
                    viewerLogin = credentials.login.ifBlank { null },
                    viewerName = credentials.name.ifBlank { null },
                    accountPool = githubAccountManager.state.value,
                    message = "已切换到 ${credentials.login.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "所选 GitHub 账号"}。",
                    error = null,
                )
            }.onFailure { error ->
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    isLoading = false,
                    accountPool = githubAccountManager.state.value,
                    message = null,
                    error = error.message ?: "切换 GitHub 账号失败",
                )
            }
        }
    }

    fun removeGitHubAccount(accountId: String) {
        if (accountId == githubAccountManager.state.value.activeAccountId) {
            _gitHubAuthState.value = _gitHubAuthState.value.copy(error = "请先切换账号，再删除当前账号。")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                githubAccountManager.credentialsFor(accountId)?.backendSessionToken
                    ?.takeIf { it.isNotBlank() }
                    ?.let { sessionToken ->
                        notifyBackendLogout(
                            apiUrl = configRepository.getConfig().getMurongBackendAuthApiUrl(),
                            sessionToken = sessionToken,
                        )
                    }
                githubAccountManager.removeAccount(accountId)
            }.onSuccess {
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    accountPool = githubAccountManager.state.value,
                    message = "已删除所选 GitHub 账号，其他账号未受影响。",
                    error = null,
                )
            }.onFailure { error ->
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    error = error.message ?: "删除 GitHub 账号失败",
                )
            }
        }
    }

    fun handleGitHubOAuthCallback(rawCallbackUri: String) {
        val trimmedUri = rawCallbackUri.trim()
        if (trimmedUri.isBlank() || lastHandledGitHubCallback == trimmedUri) return
        lastHandledGitHubCallback = trimmedUri
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfig = configRepository.getConfig()
            val callbackUri = runCatching { Uri.parse(trimmedUri) }.getOrNull()
            if (callbackUri == null) {
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    isLoading = false,
                    error = "GitHub 回调地址无效。",
                )
                return@launch
            }
            val returnedState = callbackUri.getQueryParameter("client_state")
                ?: callbackUri.getQueryParameter("state")
            val pendingState = _gitHubAuthState.value.pendingState
            if (!returnedState.isNullOrBlank() && !pendingState.isNullOrBlank() && returnedState != pendingState) {
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    isLoading = false,
                    callbackUri = trimmedUri,
                    error = "GitHub 登录校验失败，请重新发起授权。",
                )
                return@launch
            }
            val errorCode = callbackUri.getQueryParameter("error")
            val errorDescription = callbackUri.getQueryParameter("error_description")
            if (!errorCode.isNullOrBlank()) {
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    isLoading = false,
                    callbackUri = trimmedUri,
                    error = errorDescription ?: errorCode
                )
                return@launch
            }
            val exchangeCode = callbackUri.getQueryParameter("exchange_code").orEmpty()
            if (exchangeCode.isBlank()) {
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    isLoading = false,
                    callbackUri = trimmedUri,
                    error = "GitHub 回调里没有拿到登录票据。"
                )
                return@launch
            }
            _gitHubAuthState.value = _gitHubAuthState.value.copy(
                isLoading = true,
                callbackUri = trimmedUri,
                authorizationUrl = null,
                message = "正在完成 GitHub 登录..."
            )
            val tokenResult = exchangeMurongLoginCode(
                apiUrl = currentConfig.getMurongBackendAuthApiUrl(),
                exchangeCode = exchangeCode
            )
            if (tokenResult.success && !tokenResult.accessToken.isNullOrBlank()) {
                val viewerResult = if (tokenResult.viewerLogin.isNullOrBlank()) {
                    fetchGitHubViewer(currentConfig.getGitHubApiBaseUrl(), tokenResult.accessToken)
                } else null
                val resolvedLogin = tokenResult.viewerLogin?.takeIf { it.isNotBlank() }
                    ?: viewerResult?.viewerLogin.orEmpty()
                val resolvedName = tokenResult.viewerName?.takeIf { it.isNotBlank() }
                    ?: viewerResult?.viewerName.orEmpty()
                val resolvedAvatar = tokenResult.avatarUrl?.takeIf { it.isNotBlank() }
                    ?: viewerResult?.avatarUrl.orEmpty()
                val account = githubAccountManager.saveAuthenticatedAccount(
                    token = tokenResult.accessToken,
                    backendSessionToken = tokenResult.sessionToken.orEmpty(),
                    login = resolvedLogin,
                    name = resolvedName,
                    avatarUrl = resolvedAvatar,
                    apiBaseUrl = currentConfig.getGitHubApiBaseUrl(),
                )
                saveActiveGitHubCredentials(
                    currentConfig,
                    githubAccountManager.activeCredentials()
                        ?: error("GitHub 账号保存后无法读取"),
                )
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    isLoading = false,
                    viewerLogin = resolvedLogin.ifBlank { null },
                    viewerName = resolvedName.ifBlank { null },
                    authorizationUrl = null,
                    callbackUri = trimmedUri,
                    pendingState = null,
                    accountPool = githubAccountManager.state.value,
                    message = "GitHub 账号 ${account.login.takeIf { it.isNotBlank() }?.let { "@$it" } ?: account.label} 已添加。",
                    error = null
                )
            } else {
                _gitHubAuthState.value = _gitHubAuthState.value.copy(
                    isLoading = false,
                    authorizationUrl = null,
                    callbackUri = trimmedUri,
                    pendingState = null,
                    error = tokenResult.error ?: "GitHub 登录失败"
                )
            }
        }
    }

    fun checkAppUpdate(currentVersionCode: Int, currentVersionName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfig = configRepository.getConfig()
            _appUpdateState.value = _appUpdateState.value.copy(
                isChecking = true,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode,
                error = null,
                message = "正在检查更新..."
            )
            runCatching {
                fetchCurrentRelease(
                    apiUrl = currentConfig.getMurongReleasesApiUrl(),
                    artifactKey = currentConfig.getMurongAppReleaseArtifactKey()
                )
            }.onSuccess { release ->
                if (release == null) {
                    _appUpdateState.value = AppUpdateUiState(
                        isChecking = false,
                        currentVersionName = currentVersionName,
                        currentVersionCode = currentVersionCode,
                        message = "服务器暂未发布可用版本。"
                    )
                    return@onSuccess
                }
                val latestVersionCode = release.versionCode.takeIf { it > 0 }
                val latestVersionName = release.versionName.ifBlank { null }
                val updateAvailable = latestVersionCode != null && latestVersionCode > currentVersionCode
                val resolvedDownloadUrl = release.downloadUrl.ifBlank {
                    currentConfig.getMurongDownloadsPageUrl()
                }
                _appUpdateState.value = AppUpdateUiState(
                    isChecking = false,
                    currentVersionName = currentVersionName,
                    currentVersionCode = currentVersionCode,
                    latestVersionName = latestVersionName,
                    latestVersionCode = latestVersionCode,
                    downloadUrl = resolvedDownloadUrl,
                    updateMessage = release.updateMessage.ifBlank {
                        if (updateAvailable) {
                            "发现新版本，前往下载页安装即可。"
                        } else {
                            "当前已是最新版本。"
                        }
                    },
                    changelog = release.changelog.ifBlank { null },
                    publishedAt = release.publishedAt.ifBlank { null },
                    message = if (updateAvailable) {
                        "发现新版本 ${latestVersionName ?: latestVersionCode}"
                    } else {
                        "当前已是最新版本。"
                    },
                    error = null
                )
            }.onFailure { error ->
                _appUpdateState.value = AppUpdateUiState(
                    isChecking = false,
                    currentVersionName = currentVersionName,
                    currentVersionCode = currentVersionCode,
                    error = error.message ?: "检查更新失败"
                )
            }
        }
    }

    fun checkExtensionUpdate(currentVersionCode: Int?, currentVersionName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfig = configRepository.getConfig()
            _extensionUpdateState.value = _extensionUpdateState.value.copy(
                isChecking = true,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode,
                error = null,
                message = "正在检查扩展包更新..."
            )
            runCatching {
                fetchCurrentRelease(
                    apiUrl = currentConfig.getMurongReleasesApiUrl(),
                    artifactKey = currentConfig.getMurongExtensionReleaseArtifactKey()
                )
            }.onSuccess { release ->
                _extensionUpdateState.value = buildReleaseUiState(
                    release = release,
                    currentVersionCode = currentVersionCode,
                    currentVersionName = currentVersionName,
                    defaultDownloadUrl = currentConfig.getMurongDownloadsPageUrl(),
                    emptyReleaseMessage = if (currentVersionCode == null) {
                        "当前未安装扩展包，服务器也暂未发布可用版本。"
                    } else {
                        "服务器暂未发布可用扩展包。"
                    },
                    missingInstallMessage = "检测到可用扩展包，下载后即可启用终端增强环境。"
                )
            }.onFailure { error ->
                _extensionUpdateState.value = AppUpdateUiState(
                    isChecking = false,
                    currentVersionName = currentVersionName,
                    currentVersionCode = currentVersionCode,
                    error = error.message ?: "检查扩展包更新失败"
                )
            }
        }
    }

    fun checkAllUpdates(
        appVersionCode: Int,
        appVersionName: String,
        extensionVersionCode: Int?,
        extensionVersionName: String?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfig = configRepository.getConfig()
            _appUpdateState.value = _appUpdateState.value.copy(
                isChecking = true,
                currentVersionName = appVersionName,
                currentVersionCode = appVersionCode,
                error = null,
                message = "正在检查更新..."
            )
            _extensionUpdateState.value = _extensionUpdateState.value.copy(
                isChecking = true,
                currentVersionName = extensionVersionName,
                currentVersionCode = extensionVersionCode,
                error = null,
                message = "正在检查扩展包更新..."
            )

            val (appReleaseResult, extensionReleaseResult) = coroutineScope {
                val appDeferred = async {
                    runCatching {
                        fetchCurrentRelease(
                            apiUrl = currentConfig.getMurongReleasesApiUrl(),
                            artifactKey = currentConfig.getMurongAppReleaseArtifactKey()
                        )
                    }
                }
                val extensionDeferred = async {
                    runCatching {
                        fetchCurrentRelease(
                            apiUrl = currentConfig.getMurongReleasesApiUrl(),
                            artifactKey = currentConfig.getMurongExtensionReleaseArtifactKey()
                        )
                    }
                }
                appDeferred.await() to extensionDeferred.await()
            }

            _appUpdateState.value = appReleaseResult.fold(
                onSuccess = { release ->
                    buildReleaseUiState(
                        release = release,
                        currentVersionCode = appVersionCode,
                        currentVersionName = appVersionName,
                        defaultDownloadUrl = currentConfig.getMurongDownloadsPageUrl(),
                        emptyReleaseMessage = "服务器暂未发布可用版本。",
                        missingInstallMessage = "检测到可下载版本，前往下载页安装即可。"
                    )
                },
                onFailure = { error ->
                    AppUpdateUiState(
                        isChecking = false,
                        currentVersionName = appVersionName,
                        currentVersionCode = appVersionCode,
                        error = error.message ?: "检查更新失败"
                    )
                }
            )

            _extensionUpdateState.value = extensionReleaseResult.fold(
                onSuccess = { release ->
                    buildReleaseUiState(
                        release = release,
                        currentVersionCode = extensionVersionCode,
                        currentVersionName = extensionVersionName,
                        defaultDownloadUrl = currentConfig.getMurongDownloadsPageUrl(),
                        emptyReleaseMessage = if (extensionVersionCode == null) {
                            "当前未安装扩展包，服务器也暂未发布可用版本。"
                        } else {
                            "服务器暂未发布可用扩展包。"
                        },
                        missingInstallMessage = "检测到可用扩展包，下载后即可启用终端增强环境。"
                    )
                },
                onFailure = { error ->
                    AppUpdateUiState(
                        isChecking = false,
                        currentVersionName = extensionVersionName,
                        currentVersionCode = extensionVersionCode,
                        error = error.message ?: "检查扩展包更新失败"
                    )
                }
            )
        }
    }

    fun skipAppUpdateVersion(versionCode: Int?) {
        if (versionCode == null || versionCode <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfig = configRepository.getConfig()
            configRepository.saveConfig(
                currentConfig.copy(skippedAppUpdateVersionCode = versionCode)
            )
        }
    }

    fun clearSkippedAppUpdateVersion() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfig = configRepository.getConfig()
            if (currentConfig.skippedAppUpdateVersionCode == null) return@launch
            configRepository.saveConfig(
                currentConfig.copy(skippedAppUpdateVersionCode = null)
            )
        }
    }

    fun ignoreExtensionUpdateVersion(versionCode: Int?) {
        if (versionCode == null || versionCode <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfig = configRepository.getConfig()
            configRepository.saveConfig(
                currentConfig.copy(ignoredExtensionUpdateVersionCode = versionCode)
            )
        }
    }

    fun clearIgnoredExtensionUpdateVersion() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfig = configRepository.getConfig()
            if (currentConfig.ignoredExtensionUpdateVersionCode == null) return@launch
            configRepository.saveConfig(
                currentConfig.copy(ignoredExtensionUpdateVersionCode = null)
            )
        }
    }

    private suspend fun saveActiveGitHubCredentials(
        current: ProviderConfig,
        credentials: GitHubAccountCredentials,
    ): ProviderConfig {
        val updated = current.copy(
            githubToken = credentials.token,
            githubBackendSessionToken = credentials.backendSessionToken,
            githubViewerLogin = credentials.login,
            githubViewerName = credentials.name,
            githubViewerAvatarUrl = credentials.avatarUrl,
            githubApiBaseUrl = credentials.apiBaseUrl,
        )
        configRepository.saveConfig(updated)
        return updated
    }

    private fun ProviderConfig.withoutActiveGitHubCredentials(): ProviderConfig = copy(
        githubBackendSessionToken = "",
        githubToken = "",
        githubViewerLogin = "",
        githubViewerName = "",
        githubViewerAvatarUrl = "",
    )

    private fun fetchGitHubViewer(apiBaseUrl: String, token: String): GitHubViewerResult {
        val request = Request.Builder()
            .url(apiBaseUrl.trimEnd('/') + "/user")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "MurongAgent/1.0")
            .get()
            .build()
        return runCatching {
            githubClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return GitHubViewerResult(
                        success = false,
                        viewerLogin = null,
                        viewerName = null,
                        avatarUrl = null,
                        error = parseApiJsonMessage(body) ?: "GitHub 账号校验失败，HTTP ${response.code}"
                    )
                }
                val obj = githubJson.parseToJsonElement(body).jsonObject
                GitHubViewerResult(
                    success = true,
                    viewerLogin = obj["login"]?.jsonPrimitive?.contentOrNull,
                    viewerName = obj["name"]?.jsonPrimitive?.contentOrNull,
                    avatarUrl = obj["avatar_url"]?.jsonPrimitive?.contentOrNull,
                    error = null
                )
            }
        }.getOrElse { error ->
            GitHubViewerResult(false, null, null, null, error.message ?: "GitHub 账号校验失败")
        }
    }

    private fun exchangeMurongLoginCode(
        apiUrl: String,
        exchangeCode: String
    ): GitHubOAuthTokenResult {
        val requestBody = buildFormBody(
            "exchange_code" to exchangeCode
        ).toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url(
                Uri.parse(apiUrl)
                    .buildUpon()
                    .appendQueryParameter("action", "exchange")
                    .build()
                    .toString()
            )
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "MurongAgent/1.0")
            .post(requestBody)
            .build()
        return runCatching {
            githubClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return GitHubOAuthTokenResult(
                        success = false,
                        accessToken = null,
                        error = parseApiJsonMessage(body) ?: "完成 GitHub 登录失败，HTTP ${response.code}"
                    )
                }
                val obj = githubJson.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                    ?: return GitHubOAuthTokenResult(
                        success = false,
                        accessToken = null,
                        error = "服务器没有返回登录结果"
                    )
                val accessToken = obj["github_token"]?.jsonPrimitive?.contentOrNull
                if (accessToken.isNullOrBlank()) {
                    return GitHubOAuthTokenResult(
                        success = false,
                        accessToken = null,
                        error = parseApiJsonMessage(body) ?: "服务器没有返回 GitHub Token"
                    )
                }
                GitHubOAuthTokenResult(
                    success = true,
                    accessToken = accessToken,
                    sessionToken = obj["session_token"]?.jsonPrimitive?.contentOrNull,
                    viewerLogin = obj["github_login"]?.jsonPrimitive?.contentOrNull,
                    viewerName = obj["github_name"]?.jsonPrimitive?.contentOrNull,
                    avatarUrl = obj["github_avatar_url"]?.jsonPrimitive?.contentOrNull,
                    error = null
                )
            }
        }.getOrElse { error ->
            GitHubOAuthTokenResult(
                success = false,
                accessToken = null,
                error = error.message ?: "完成 GitHub 登录失败"
            )
        }
    }

    private fun buildFormBody(vararg pairs: Pair<String, String>): String {
        return pairs.joinToString("&") { (key, value) ->
            "${Uri.encode(key)}=${Uri.encode(value)}"
        }
    }

    private fun fetchCurrentRelease(apiUrl: String, artifactKey: String): AppReleaseInfo? {
        val request = Request.Builder()
            .url(
                Uri.parse(apiUrl)
                    .buildUpon()
                    .appendQueryParameter("action", "current")
                    .appendQueryParameter("artifact", artifactKey)
                    .build()
                    .toString()
            )
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "MurongAgent/1.0")
            .get()
            .build()
        return githubClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    parseApiJsonMessage(body) ?: "检查更新失败，HTTP ${response.code}"
                )
            }
            val root = githubJson.parseToJsonElement(body).jsonObject
            val success = root["success"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            if (!success) {
                throw IllegalStateException(parseApiJsonMessage(body) ?: "检查更新失败")
            }
            val data = root["data"]?.jsonObject ?: return null
            val updateMessage = data["updateMessage"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val changelog = data["changelog"]?.jsonPrimitive?.contentOrNull.orEmpty()
            AppReleaseInfo(
                versionName = data["versionName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                versionCode = data["versionCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                fileName = data["fileName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                downloadUrl = data["downloadUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                directDownloadUrl = data["directDownloadUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                updateMessage = updateMessage,
                changelog = changelog,
                publishedAt = data["publishedAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                forceUpdate = parseForceUpdateFlag(
                    explicitValue = data["forceUpdate"]?.jsonPrimitive?.contentOrNull,
                    updateMessage = updateMessage,
                    changelog = changelog
                )
            )
        }
    }

    private fun parseApiJsonMessage(body: String): String? {
        return runCatching {
            githubJson.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
                ?: githubJson.parseToJsonElement(body).jsonObject["error_description"]?.jsonPrimitive?.contentOrNull
                ?: githubJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private fun notifyBackendLogout(apiUrl: String, sessionToken: String) {
        val request = Request.Builder()
            .url(
                Uri.parse(apiUrl)
                    .buildUpon()
                    .appendQueryParameter("action", "logout")
                    .build()
                    .toString()
            )
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Bearer $sessionToken")
            .addHeader("User-Agent", "MurongAgent/1.0")
            .post(ByteArray(0).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        runCatching {
            githubClient.newCall(request).execute().use { }
        }
    }
}

internal fun buildMcpImportFailureMessage(
    importedCount: Int,
    failedCount: Int
): String? {
    if (importedCount <= 0 || failedCount <= 0) return null
    return "已保存 $importedCount 个 MCP 配置，其中 $failedCount 个连接失败；配置不会回滚，可稍后重试连接。"
}

internal fun buildMcpConnectFailureMessage(
    configs: List<McpServerConfig>,
    statuses: List<McpServerStatus>
): String? {
    val attemptedNames = configs
        .asSequence()
        .filter { it.enabled }
        .map { it.name }
        .toSet()
    if (attemptedNames.isEmpty()) return null
    val failedCount = statuses.count { status ->
        status.name in attemptedNames && !status.connected && status.failureRecord != null
    }
    if (failedCount <= 0) return null
    return "已尝试连接 ${attemptedNames.size} 个已保存 MCP，其中 $failedCount 个失败；配置已保留，可稍后重试连接。"
}

private data class GitHubViewerResult(
    val success: Boolean,
    val viewerLogin: String?,
    val viewerName: String?,
    val avatarUrl: String?,
    val error: String?
)

private data class GitHubOAuthTokenResult(
    val success: Boolean,
    val accessToken: String?,
    val sessionToken: String? = null,
    val viewerLogin: String? = null,
    val viewerName: String? = null,
    val avatarUrl: String? = null,
    val error: String?
)

private data class AppReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val fileName: String,
    val downloadUrl: String,
    val directDownloadUrl: String,
    val updateMessage: String,
    val changelog: String,
    val publishedAt: String,
    val forceUpdate: Boolean
)

private fun buildReleaseUiState(
    release: AppReleaseInfo?,
    currentVersionCode: Int?,
    currentVersionName: String?,
    defaultDownloadUrl: String,
    emptyReleaseMessage: String,
    missingInstallMessage: String
): AppUpdateUiState {
    if (release == null) {
        return AppUpdateUiState(
            isChecking = false,
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
            message = emptyReleaseMessage
        )
    }
    val latestVersionCode = release.versionCode.takeIf { it > 0 }
    val latestVersionName = release.versionName.ifBlank { null }
    val resolvedDownloadUrl = release.downloadUrl.ifBlank { defaultDownloadUrl }
    val isUpdateAvailable = latestVersionCode != null &&
        currentVersionCode != null &&
        latestVersionCode > currentVersionCode
    val isInstallAvailable = currentVersionCode == null &&
        (latestVersionCode != null || latestVersionName != null || resolvedDownloadUrl.isNotBlank())
    val fallbackMessage = when {
        isUpdateAvailable -> "发现新版本，前往下载页安装即可。"
        isInstallAvailable -> missingInstallMessage
        else -> "当前已是最新版本。"
    }
    val summaryMessage = when {
        isUpdateAvailable -> "发现新版本 ${latestVersionName ?: latestVersionCode}"
        isInstallAvailable -> "检测到可下载版本 ${latestVersionName ?: latestVersionCode ?: ""}".trim()
        else -> "当前已是最新版本。"
    }
    return AppUpdateUiState(
        isChecking = false,
        currentVersionName = currentVersionName,
        currentVersionCode = currentVersionCode,
        latestVersionName = latestVersionName,
        latestVersionCode = latestVersionCode,
        fileName = release.fileName.ifBlank { null },
        downloadUrl = resolvedDownloadUrl,
        directDownloadUrl = release.directDownloadUrl.ifBlank { null },
        updateMessage = release.updateMessage.ifBlank { fallbackMessage },
        changelog = release.changelog.ifBlank { null },
        publishedAt = release.publishedAt.ifBlank { null },
        forceUpdate = release.forceUpdate,
        message = summaryMessage,
        error = null
    )
}

private fun parseForceUpdateFlag(
    explicitValue: String?,
    updateMessage: String,
    changelog: String
): Boolean {
    val normalizedExplicit = explicitValue
        ?.trim()
        ?.lowercase()
        .orEmpty()
    if (normalizedExplicit in setOf("1", "true", "yes", "y", "on")) {
        return true
    }
    val combinedText = buildString {
        append(updateMessage.lowercase())
        if (changelog.isNotBlank()) {
            append('\n')
            append(changelog.lowercase())
        }
    }
    val forceMarkers = listOf(
        "[force]",
        "#force",
        "force=true",
        "强更",
        "强制更新",
        "必须更新"
    )
    return forceMarkers.any { marker -> marker in combinedText }
}

const val MURONG_EXTENSION_PACKAGE_NAME = "cc.rl1.murong.terminalextension"
