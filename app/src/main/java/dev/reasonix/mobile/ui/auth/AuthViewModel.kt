package dev.reasonix.mobile.ui.auth

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.reasonix.mobile.core.config.ConfigRepository
import dev.reasonix.mobile.core.config.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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

data class AuthUiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val authorizationUrl: String? = null,
    val viewerLogin: String? = null,
    val viewerName: String? = null,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val config: StateFlow<ProviderConfig> = configRepository.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProviderConfig())

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var lastHandledCallback: String? = null

    init {
        viewModelScope.launch {
            configRepository.configFlow.collect { current ->
                _uiState.value = _uiState.value.copy(
                    isAuthenticated = current.isGitHubSignedIn(),
                    viewerLogin = current.githubViewerLogin.ifBlank { null },
                    viewerName = current.githubViewerName.ifBlank { null }
                )
            }
        }
    }

    fun startGitHubLogin() {
        viewModelScope.launch {
            val current = configRepository.getConfig()
            val authorizationUrl = GitHubAuthFlow.buildAuthorizationUrl(
                config = current,
                clientState = UUID.randomUUID().toString()
            )

            _uiState.value = _uiState.value.copy(
                isLoading = true,
                authorizationUrl = authorizationUrl,
                message = "正在打开 GitHub 授权页...",
                error = null
            )
        }
    }

    fun handleGitHubCallback(rawCallbackUri: String) {
        val resolution = GitHubAuthFlow.resolveCallback(rawCallbackUri, lastHandledCallback)
        when (resolution) {
            GitHubCallbackResolution.IgnoreBlank,
            GitHubCallbackResolution.IgnoreDuplicate -> return
            is GitHubCallbackResolution.Invalid -> lastHandledCallback = resolution.normalizedCallback
            is GitHubCallbackResolution.Error -> lastHandledCallback = resolution.normalizedCallback
            is GitHubCallbackResolution.MissingExchangeCode -> {
                lastHandledCallback = resolution.normalizedCallback
            }
            is GitHubCallbackResolution.ExchangeCode -> lastHandledCallback = resolution.normalizedCallback
        }

        viewModelScope.launch(Dispatchers.IO) {
            when (resolution) {
                is GitHubCallbackResolution.Invalid -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = resolution.message
                    )
                    return@launch
                }
                is GitHubCallbackResolution.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = resolution.message
                    )
                    return@launch
                }
                is GitHubCallbackResolution.MissingExchangeCode -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = resolution.message
                    )
                    return@launch
                }
                GitHubCallbackResolution.IgnoreBlank,
                GitHubCallbackResolution.IgnoreDuplicate -> return@launch
                is GitHubCallbackResolution.ExchangeCode -> Unit
            }

            _uiState.value = _uiState.value.copy(
                isLoading = true,
                message = "正在完成 GitHub 登录...",
                error = null
            )

            val result = exchangeLoginCode(resolution.exchangeCode)
            if (result.success) {
                val current = configRepository.getConfig()
                configRepository.saveConfig(
                    current.copy(
                        githubBackendSessionToken = result.sessionToken.orEmpty(),
                        githubToken = result.githubToken.orEmpty(),
                        githubViewerLogin = result.githubLogin.orEmpty(),
                        githubViewerName = result.githubName.orEmpty(),
                        githubViewerAvatarUrl = result.avatarUrl.orEmpty()
                    )
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isAuthenticated = true,
                    authorizationUrl = null,
                    viewerLogin = result.githubLogin,
                    viewerName = result.githubName,
                    message = "GitHub 登录成功",
                    error = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.error ?: "GitHub 登录失败"
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = configRepository.getConfig()
            if (current.githubBackendSessionToken.isNotBlank()) {
                notifyBackendLogout(
                    apiUrl = current.getReasonixBackendAuthApiUrl(),
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
            _uiState.value = AuthUiState(message = "已退出 GitHub 登录")
        }
    }

    private suspend fun exchangeLoginCode(exchangeCode: String): AuthExchangeResult {
        val current = runCatching { configRepository.getConfig() }.getOrElse {
            return AuthExchangeResult(success = false, error = it.message ?: "读取配置失败")
        }

        val requestBody = "exchange_code=${Uri.encode(exchangeCode)}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url(
                Uri.parse(current.getReasonixBackendAuthApiUrl())
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
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return AuthExchangeResult(
                        success = false,
                        error = parseJsonField(body, "message") ?: "登录票据交换失败，HTTP ${response.code}"
                    )
                }

                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                    ?: return AuthExchangeResult(success = false, error = "服务器没有返回登录结果")

                AuthExchangeResult(
                    success = true,
                    sessionToken = data["session_token"]?.jsonPrimitive?.contentOrNull,
                    githubToken = data["github_token"]?.jsonPrimitive?.contentOrNull,
                    githubLogin = data["github_login"]?.jsonPrimitive?.contentOrNull,
                    githubName = data["github_name"]?.jsonPrimitive?.contentOrNull,
                    avatarUrl = data["avatar_url"]?.jsonPrimitive?.contentOrNull,
                    error = null
                )
            }
        }.getOrElse { error ->
            AuthExchangeResult(success = false, error = error.message ?: "登录票据交换失败")
        }
    }

    private fun parseJsonField(raw: String, field: String): String? {
        return runCatching {
            json.parseToJsonElement(raw).jsonObject[field]?.jsonPrimitive?.contentOrNull
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
            client.newCall(request).execute().use { }
        }
    }
}

private data class AuthExchangeResult(
    val success: Boolean,
    val sessionToken: String? = null,
    val githubToken: String? = null,
    val githubLogin: String? = null,
    val githubName: String? = null,
    val avatarUrl: String? = null,
    val error: String? = null,
)
