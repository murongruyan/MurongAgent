package com.murong.agent.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.murong.agent.common.shell.KeepShellPublic
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.ProviderBalanceService
import com.murong.agent.core.loop.ChatSessionManager
import com.murong.agent.core.loop.SessionSummary
import com.murong.agent.core.mcp.McpRegistry
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpServerStatus
import kotlinx.coroutines.Dispatchers
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
    val message: String? = null,
    val error: String? = null
)

data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val currentVersionName: String? = null,
    val currentVersionCode: Int? = null,
    val latestVersionName: String? = null,
    val latestVersionCode: Int? = null,
    val downloadUrl: String? = null,
    val updateMessage: String? = null,
    val publishedAt: String? = null,
    val message: String? = null,
    val error: String? = null
) {
    val isUpdateAvailable: Boolean
        get() = currentVersionCode != null &&
            latestVersionCode != null &&
            latestVersionCode > currentVersionCode
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val providerBalanceService: ProviderBalanceService,
    private val mcpRegistry: McpRegistry,
    private val chatSessionManager: ChatSessionManager
) : ViewModel() {
    private companion object {
        const val AUTO_BALANCE_SYNC_INTERVAL_MS = 10 * 60 * 1000L
    }

    private val githubJson = Json { ignoreUnknownKeys = true; isLenient = true }
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

    // ─── 会话列表 ───────────────────────────────
    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _balanceSyncStates = MutableStateFlow<Map<String, BalanceSyncUiState>>(emptyMap())
    val balanceSyncStates: StateFlow<Map<String, BalanceSyncUiState>> = _balanceSyncStates.asStateFlow()

    private val _gitHubAuthState = MutableStateFlow(GitHubAuthUiState())
    val gitHubAuthState: StateFlow<GitHubAuthUiState> = _gitHubAuthState.asStateFlow()
    private var lastHandledGitHubCallback: String? = null

    private val _appUpdateState = MutableStateFlow(AppUpdateUiState())
    val appUpdateState: StateFlow<AppUpdateUiState> = _appUpdateState.asStateFlow()

    init {
        // 加载已保存的 MCP 配置
        _mcpServers.value = mcpRegistry.loadConfigs()
        _sessions.value = chatSessionManager.listSessions()
        checkRoot()
        viewModelScope.launch {
            configRepository.configFlow.collect { currentConfig ->
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
    }

    fun updateConfig(newConfig: ProviderConfig) {
        viewModelScope.launch { configRepository.saveConfig(newConfig) }
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

    // ─── MCP 方法 ─────────────────────────────

    fun addMcpServer(config: McpServerConfig) {
        val updated = _mcpServers.value.toMutableList()
        val idx = updated.indexOfFirst { it.name == config.name }
        if (idx >= 0) updated[idx] = config else updated.add(config)
        _mcpServers.value = updated
        mcpRegistry.saveConfigs(updated)
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
                mcpRegistry.connectAll(_mcpServers.value)
                _mcpStatuses.value = mcpRegistry.getServerStatuses()
            } catch (e: Exception) {
                _mcpConnectError.value = e.message
            }
        }
    }

    fun refreshMcpStatus() {
        _mcpStatuses.value = mcpRegistry.getServerStatuses()
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
                            syncedAt = snapshot.syncedAt
                        )
                    )
                    _balanceSyncStates.value = _balanceSyncStates.value + (
                        providerId to BalanceSyncUiState(
                            isSyncing = false,
                            message = "余额已同步"
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
            val currentConfig = configRepository.getConfig()
            if (!currentConfig.isGitHubSignedIn()) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    error = "当前还没有登录 GitHub。"
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
                        githubViewerName = resolvedName
                    )
                )
                _gitHubAuthState.value = GitHubAuthUiState(
                    isLoading = false,
                    viewerLogin = viewerResult.viewerLogin,
                    viewerName = viewerResult.viewerName,
                    message = "GitHub 已连接",
                    error = null
                )
            } else {
                _gitHubAuthState.value = GitHubAuthUiState(
                    isLoading = false,
                    viewerLogin = currentConfig.githubViewerLogin.ifBlank { null },
                    viewerName = currentConfig.githubViewerName.ifBlank { null },
                    error = viewerResult.error ?: "GitHub 登录状态校验失败"
                )
            }
        }
    }

    fun clearGitHubToken() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = configRepository.getConfig()
            if (current.githubBackendSessionToken.isNotBlank()) {
                notifyBackendLogout(
                    apiUrl = current.getMurongBackendAuthApiUrl(),
                    sessionToken = current.githubBackendSessionToken
                )
            }
            configRepository.saveConfig(
                current.copy(
                    githubBackendSessionToken = "",
                    githubToken = "",
                    githubViewerLogin = "",
                    githubViewerName = "",
                    githubViewerAvatarUrl = ""
                )
            )
            _gitHubAuthState.value = GitHubAuthUiState(message = "已退出 GitHub 登录")
        }
    }

    fun startGitHubOAuthLogin() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfig = configRepository.getConfig()
            _gitHubAuthState.value = GitHubAuthUiState(
                isLoading = true,
                authorizationUrl = Uri.parse(currentConfig.getMurongBackendAuthApiUrl())
                    .buildUpon()
                    .appendQueryParameter("action", "start")
                    .appendQueryParameter("client_redirect_uri", currentConfig.getMurongGitHubRedirectUri())
                    .build()
                    .toString(),
                callbackUri = currentConfig.getMurongGitHubRedirectUri(),
                message = "正在打开 GitHub 授权页，授权完成后会自动回到应用。"
            )
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
                _gitHubAuthState.value = GitHubAuthUiState(
                    error = "GitHub 回调地址无效。"
                )
                return@launch
            }
            val errorCode = callbackUri.getQueryParameter("error")
            val errorDescription = callbackUri.getQueryParameter("error_description")
            if (!errorCode.isNullOrBlank()) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    callbackUri = trimmedUri,
                    error = errorDescription ?: errorCode
                )
                return@launch
            }
            val exchangeCode = callbackUri.getQueryParameter("exchange_code").orEmpty()
            if (exchangeCode.isBlank()) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    callbackUri = trimmedUri,
                    error = "GitHub 回调里没有拿到登录票据。"
                )
                return@launch
            }
            _gitHubAuthState.value = GitHubAuthUiState(
                isLoading = true,
                callbackUri = trimmedUri,
                message = "正在完成 GitHub 登录..."
            )
            val tokenResult = exchangeMurongLoginCode(
                apiUrl = currentConfig.getMurongBackendAuthApiUrl(),
                exchangeCode = exchangeCode
            )
            if (tokenResult.success && !tokenResult.accessToken.isNullOrBlank()) {
                configRepository.saveConfig(
                    currentConfig.copy(
                        githubBackendSessionToken = tokenResult.sessionToken.orEmpty(),
                        githubToken = tokenResult.accessToken,
                        githubViewerLogin = tokenResult.viewerLogin.orEmpty(),
                        githubViewerName = tokenResult.viewerName.orEmpty()
                    )
                )
                _gitHubAuthState.value = GitHubAuthUiState(
                    isLoading = false,
                    viewerLogin = tokenResult.viewerLogin,
                    viewerName = tokenResult.viewerName,
                    callbackUri = trimmedUri,
                    message = "GitHub 登录成功",
                    error = null
                )
            } else {
                _gitHubAuthState.value = GitHubAuthUiState(
                    isLoading = false,
                    callbackUri = trimmedUri,
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
                        error = parseApiJsonMessage(body) ?: "GitHub 账号校验失败，HTTP ${response.code}"
                    )
                }
                val obj = githubJson.parseToJsonElement(body).jsonObject
                GitHubViewerResult(
                    success = true,
                    viewerLogin = obj["login"]?.jsonPrimitive?.contentOrNull,
                    viewerName = obj["name"]?.jsonPrimitive?.contentOrNull,
                    error = null
                )
            }
        }.getOrElse { error ->
            GitHubViewerResult(false, null, null, error.message ?: "GitHub 账号校验失败")
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
            AppReleaseInfo(
                versionName = data["versionName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                versionCode = data["versionCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                downloadUrl = data["downloadUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                updateMessage = data["updateMessage"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                publishedAt = data["publishedAt"]?.jsonPrimitive?.contentOrNull.orEmpty()
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

private data class GitHubViewerResult(
    val success: Boolean,
    val viewerLogin: String?,
    val viewerName: String?,
    val error: String?
)

private data class GitHubOAuthTokenResult(
    val success: Boolean,
    val accessToken: String?,
    val sessionToken: String? = null,
    val viewerLogin: String? = null,
    val viewerName: String? = null,
    val error: String?
)

private data class AppReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val updateMessage: String,
    val publishedAt: String
)
