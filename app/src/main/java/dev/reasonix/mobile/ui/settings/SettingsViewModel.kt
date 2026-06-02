package dev.reasonix.mobile.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.reasonix.mobile.common.shell.KeepShellPublic
import dev.reasonix.mobile.core.config.ConfigRepository
import dev.reasonix.mobile.core.config.ProviderConfig
import dev.reasonix.mobile.core.config.ProviderBalanceService
import dev.reasonix.mobile.core.loop.ChatSessionManager
import dev.reasonix.mobile.core.loop.SessionSummary
import dev.reasonix.mobile.core.mcp.McpRegistry
import dev.reasonix.mobile.core.mcp.McpServerConfig
import dev.reasonix.mobile.core.mcp.McpServerStatus
import kotlinx.coroutines.Dispatchers
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
import java.util.UUID
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val providerBalanceService: ProviderBalanceService,
    private val mcpRegistry: McpRegistry,
    private val chatSessionManager: ChatSessionManager
) : ViewModel() {
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

    init {
        // 加载已保存的 MCP 配置
        _mcpServers.value = mcpRegistry.loadConfigs()
        _sessions.value = chatSessionManager.listSessions()
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
            val token = currentConfig.githubToken.trim()
            if (token.isBlank()) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    error = "请先填写 GitHub Token，或使用浏览器授权登录。"
                )
                return@launch
            }
            _gitHubAuthState.value = _gitHubAuthState.value.copy(isLoading = true, error = null, message = null)
            val result = fetchGitHubViewer(
                apiBaseUrl = currentConfig.getGitHubApiBaseUrl(),
                token = token
            )
            _gitHubAuthState.value = if (result.success) {
                _gitHubAuthState.value.copy(
                    isLoading = false,
                    viewerLogin = result.viewerLogin,
                    viewerName = result.viewerName,
                    message = "GitHub 账号校验成功",
                    error = null
                )
            } else {
                GitHubAuthUiState(
                    isLoading = false,
                    error = result.error ?: "GitHub 账号校验失败"
                )
            }
        }
    }

    fun clearGitHubToken() {
        viewModelScope.launch {
            val current = configRepository.getConfig()
            configRepository.saveConfig(current.copy(githubToken = ""))
            _gitHubAuthState.value = GitHubAuthUiState(message = "已清除 GitHub Token")
        }
    }

    fun startGitHubOAuthLogin() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfig = configRepository.getConfig()
            val clientId = currentConfig.getGitHubClientId().trim()
            val clientSecret = currentConfig.getGitHubClientSecret().trim()
            if (clientId.isBlank()) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    error = "请先填写 GitHub Client ID。"
                )
                return@launch
            }
            if (clientSecret.isBlank()) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    error = "请先填写 GitHub Client Secret。"
                )
                return@launch
            }
            val redirectUri = currentConfig.getGitHubOAuthRedirectUri()
            val state = UUID.randomUUID().toString()
            _gitHubAuthState.value = GitHubAuthUiState(
                isLoading = true,
                authorizationUrl = buildGitHubOAuthAuthorizeUrl(
                    clientId = clientId,
                    redirectUri = redirectUri,
                    state = state
                ),
                callbackUri = redirectUri,
                pendingState = state,
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
            val clientId = currentConfig.getGitHubClientId().trim()
            val clientSecret = currentConfig.getGitHubClientSecret().trim()
            val expectedState = _gitHubAuthState.value.pendingState
            if (clientId.isBlank() || clientSecret.isBlank()) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    error = "请先补全 GitHub Client ID 和 Client Secret。"
                )
                return@launch
            }
            val callbackUri = runCatching { Uri.parse(trimmedUri) }.getOrNull()
            if (callbackUri == null) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    error = "GitHub 回调地址无效。"
                )
                return@launch
            }
            val returnedState = callbackUri.getQueryParameter("state")
            val errorCode = callbackUri.getQueryParameter("error")
            val errorDescription = callbackUri.getQueryParameter("error_description")
            if (!errorCode.isNullOrBlank()) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    callbackUri = trimmedUri,
                    error = errorDescription ?: errorCode
                )
                return@launch
            }
            if (!expectedState.isNullOrBlank() && returnedState != expectedState) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    callbackUri = trimmedUri,
                    error = "GitHub 登录状态校验失败，请重新发起授权。"
                )
                return@launch
            }
            val code = callbackUri.getQueryParameter("code").orEmpty()
            if (code.isBlank()) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    callbackUri = trimmedUri,
                    error = "GitHub 回调里没有拿到授权码。"
                )
                return@launch
            }
            _gitHubAuthState.value = GitHubAuthUiState(
                isLoading = true,
                callbackUri = trimmedUri,
                message = "正在交换 GitHub Token..."
            )
            val tokenResult = exchangeGitHubOAuthCode(
                clientId = clientId,
                clientSecret = clientSecret,
                redirectUri = currentConfig.getGitHubOAuthRedirectUri(),
                code = code
            )
            if (tokenResult.success && !tokenResult.accessToken.isNullOrBlank()) {
                configRepository.saveConfig(currentConfig.copy(githubToken = tokenResult.accessToken))
                val viewer = fetchGitHubViewer(
                    apiBaseUrl = currentConfig.getGitHubApiBaseUrl(),
                    token = tokenResult.accessToken
                )
                _gitHubAuthState.value = GitHubAuthUiState(
                    isLoading = false,
                    viewerLogin = viewer.viewerLogin,
                    viewerName = viewer.viewerName,
                    callbackUri = trimmedUri,
                    message = "GitHub 登录成功",
                    error = viewer.error
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

    private fun fetchGitHubViewer(apiBaseUrl: String, token: String): GitHubViewerResult {
        val request = Request.Builder()
            .url(apiBaseUrl.trimEnd('/') + "/user")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "Reasonix-Mobile/1.0")
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
                        error = parseGitHubJsonMessage(body) ?: "GitHub 账号校验失败，HTTP ${response.code}"
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

    private fun buildGitHubOAuthAuthorizeUrl(
        clientId: String,
        redirectUri: String,
        state: String
    ): String {
        return Uri.parse("https://github.com/login/oauth/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", "repo read:user")
            .appendQueryParameter("state", state)
            .appendQueryParameter("allow_signup", "true")
            .build()
            .toString()
    }

    private fun exchangeGitHubOAuthCode(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        code: String
    ): GitHubOAuthTokenResult {
        val requestBody = buildFormBody(
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "code" to code,
            "redirect_uri" to redirectUri
        ).toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url("https://github.com/login/oauth/access_token")
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "Reasonix-Mobile/1.0")
            .post(requestBody)
            .build()
        return runCatching {
            githubClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return GitHubOAuthTokenResult(
                        success = false,
                        accessToken = null,
                        error = parseGitHubJsonMessage(body) ?: "交换 GitHub Token 失败，HTTP ${response.code}"
                    )
                }
                val obj = githubJson.parseToJsonElement(body).jsonObject
                val accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
                if (accessToken.isNullOrBlank()) {
                    return GitHubOAuthTokenResult(
                        success = false,
                        accessToken = null,
                        error = parseGitHubJsonMessage(body) ?: "GitHub 没有返回 access token"
                    )
                }
                GitHubOAuthTokenResult(
                    success = true,
                    accessToken = accessToken,
                    error = null
                )
            }
        }.getOrElse { error ->
            GitHubOAuthTokenResult(
                success = false,
                accessToken = null,
                error = error.message ?: "交换 GitHub Token 失败"
            )
        }
    }

    private fun buildFormBody(vararg pairs: Pair<String, String>): String {
        return pairs.joinToString("&") { (key, value) ->
            "${Uri.encode(key)}=${Uri.encode(value)}"
        }
    }

    private fun parseGitHubJsonMessage(body: String): String? {
        return runCatching {
            githubJson.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
                ?: githubJson.parseToJsonElement(body).jsonObject["error_description"]?.jsonPrimitive?.contentOrNull
                ?: githubJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
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
    val error: String?
)
