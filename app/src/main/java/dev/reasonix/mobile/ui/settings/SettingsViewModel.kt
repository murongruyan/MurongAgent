package dev.reasonix.mobile.ui.settings

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
    val deviceUserCode: String? = null,
    val verificationUri: String? = null,
    val expiresInSeconds: Int? = null,
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
                    error = "请先填写 GitHub Token，或使用设备码登录。"
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

    fun startGitHubDeviceLogin() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfig = configRepository.getConfig()
            val clientId = currentConfig.getGitHubClientId().trim()
            if (clientId.isBlank()) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    error = "请先填写 GitHub Client ID。"
                )
                return@launch
            }
            _gitHubAuthState.value = GitHubAuthUiState(isLoading = true, message = "正在申请设备码...")
            val deviceCodeResult = requestGitHubDeviceCode(clientId)
            if (!deviceCodeResult.success) {
                _gitHubAuthState.value = GitHubAuthUiState(
                    error = deviceCodeResult.error ?: "申请设备码失败"
                )
                return@launch
            }
            _gitHubAuthState.value = GitHubAuthUiState(
                isLoading = true,
                deviceUserCode = deviceCodeResult.userCode,
                verificationUri = deviceCodeResult.verificationUri,
                expiresInSeconds = deviceCodeResult.expiresInSeconds,
                message = "请复制设备码并在浏览器完成授权，应用会自动轮询。"
            )
            val deadline = System.currentTimeMillis() + deviceCodeResult.expiresInSeconds * 1000L
            var pollIntervalSeconds = deviceCodeResult.intervalSeconds.coerceAtLeast(5)
            while (System.currentTimeMillis() < deadline) {
                delay(pollIntervalSeconds * 1000L)
                val tokenResult = pollGitHubDeviceToken(
                    clientId = clientId,
                    deviceCode = deviceCodeResult.deviceCode
                )
                when {
                    tokenResult.success && !tokenResult.accessToken.isNullOrBlank() -> {
                        configRepository.saveConfig(currentConfig.copy(githubToken = tokenResult.accessToken))
                        val viewer = fetchGitHubViewer(
                            apiBaseUrl = currentConfig.getGitHubApiBaseUrl(),
                            token = tokenResult.accessToken
                        )
                        _gitHubAuthState.value = GitHubAuthUiState(
                            isLoading = false,
                            viewerLogin = viewer.viewerLogin,
                            viewerName = viewer.viewerName,
                            message = "GitHub 登录成功",
                            error = viewer.error
                        )
                        return@launch
                    }
                    tokenResult.pollPending -> {
                        _gitHubAuthState.value = _gitHubAuthState.value.copy(
                            isLoading = true,
                            message = "等待 GitHub 授权中..."
                        )
                    }
                    tokenResult.shouldSlowDown -> {
                        pollIntervalSeconds += 5
                    }
                    else -> {
                        _gitHubAuthState.value = GitHubAuthUiState(
                            isLoading = false,
                            error = tokenResult.error ?: "GitHub 登录失败"
                        )
                        return@launch
                    }
                }
            }
            _gitHubAuthState.value = GitHubAuthUiState(
                isLoading = false,
                error = "设备码已过期，请重新发起登录。"
            )
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

    private fun requestGitHubDeviceCode(clientId: String): GitHubDeviceCodeResult {
        val requestBody = "client_id=$clientId&scope=repo%20read:user"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url("https://github.com/login/device/code")
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "Reasonix-Mobile/1.0")
            .post(requestBody)
            .build()
        return runCatching {
            githubClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return GitHubDeviceCodeResult(
                        success = false,
                        deviceCode = "",
                        userCode = "",
                        verificationUri = "",
                        expiresInSeconds = 0,
                        intervalSeconds = 5,
                        error = parseGitHubJsonMessage(body) ?: "申请设备码失败，HTTP ${response.code}"
                    )
                }
                val obj = githubJson.parseToJsonElement(body).jsonObject
                GitHubDeviceCodeResult(
                    success = true,
                    deviceCode = obj["device_code"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    userCode = obj["user_code"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    verificationUri = obj["verification_uri"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.intOrNull ?: 900,
                    intervalSeconds = obj["interval"]?.jsonPrimitive?.intOrNull ?: 5,
                    error = null
                )
            }
        }.getOrElse { error ->
            GitHubDeviceCodeResult(false, "", "", "", 0, 5, error.message ?: "申请设备码失败")
        }
    }

    private fun pollGitHubDeviceToken(clientId: String, deviceCode: String): GitHubDeviceTokenResult {
        val requestBody = buildString {
            append("client_id=")
            append(clientId)
            append("&device_code=")
            append(deviceCode)
            append("&grant_type=urn:ietf:params:oauth:grant-type:device_code")
        }.toRequestBody("application/x-www-form-urlencoded".toMediaType())
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
                    return GitHubDeviceTokenResult(
                        success = false,
                        accessToken = null,
                        pollPending = false,
                        shouldSlowDown = false,
                        error = parseGitHubJsonMessage(body) ?: "轮询 GitHub Token 失败，HTTP ${response.code}"
                    )
                }
                val obj = githubJson.parseToJsonElement(body).jsonObject
                val accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
                val errorCode = obj["error"]?.jsonPrimitive?.contentOrNull
                when {
                    !accessToken.isNullOrBlank() -> GitHubDeviceTokenResult(
                        success = true,
                        accessToken = accessToken,
                        pollPending = false,
                        shouldSlowDown = false,
                        error = null
                    )
                    errorCode == "authorization_pending" -> GitHubDeviceTokenResult(
                        success = false,
                        accessToken = null,
                        pollPending = true,
                        shouldSlowDown = false,
                        error = null
                    )
                    errorCode == "slow_down" -> GitHubDeviceTokenResult(
                        success = false,
                        accessToken = null,
                        pollPending = true,
                        shouldSlowDown = true,
                        error = null
                    )
                    else -> GitHubDeviceTokenResult(
                        success = false,
                        accessToken = null,
                        pollPending = false,
                        shouldSlowDown = false,
                        error = errorCode ?: parseGitHubJsonMessage(body) ?: "GitHub 登录失败"
                    )
                }
            }
        }.getOrElse { error ->
            GitHubDeviceTokenResult(
                success = false,
                accessToken = null,
                pollPending = false,
                shouldSlowDown = false,
                error = error.message ?: "GitHub 登录失败"
            )
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

private data class GitHubDeviceCodeResult(
    val success: Boolean,
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
    val error: String?
)

private data class GitHubDeviceTokenResult(
    val success: Boolean,
    val accessToken: String?,
    val pollPending: Boolean,
    val shouldSlowDown: Boolean,
    val error: String?
)
