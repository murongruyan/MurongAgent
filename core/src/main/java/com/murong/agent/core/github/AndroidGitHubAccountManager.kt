package com.murong.agent.core.github

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val DEFAULT_GITHUB_ACCOUNT_ID = "github-account-default"
private const val DEFAULT_GITHUB_API_BASE_URL = "https://api.github.com"

data class GitHubAccountCredentials(
    val accountId: String,
    val token: String,
    val backendSessionToken: String = "",
    val login: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val apiBaseUrl: String = DEFAULT_GITHUB_API_BASE_URL,
)

data class GitHubManagedAccount(
    val id: String,
    val label: String,
    val login: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val apiBaseUrl: String = DEFAULT_GITHUB_API_BASE_URL,
    val active: Boolean = false,
    val loggedIn: Boolean = false,
    val lastCheckedAt: Long = 0L,
    val error: String? = null,
)

data class GitHubAccountPoolSnapshot(
    val activeAccountId: String = DEFAULT_GITHUB_ACCOUNT_ID,
    val accounts: List<GitHubManagedAccount> = emptyList(),
)

data class GitHubAccountTransfer(
    val id: String,
    val label: String,
    val login: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val apiBaseUrl: String = DEFAULT_GITHUB_API_BASE_URL,
    val token: String? = null,
    val lastUsedAt: Long = 0L,
)

data class GitHubAccountPoolTransfer(
    val activeAccountId: String,
    val accounts: List<GitHubAccountTransfer>,
)

@Serializable
private data class StoredGitHubAccount(
    val id: String,
    val label: String,
    val login: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val apiBaseUrl: String = DEFAULT_GITHUB_API_BASE_URL,
    val protectedToken: String = "",
    val protectedBackendSessionToken: String = "",
    val lastCheckedAt: Long = 0L,
    val lastUsedAt: Long = 0L,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
private data class StoredGitHubAccountDocument(
    val schemaVersion: Int = 1,
    val activeAccountId: String = DEFAULT_GITHUB_ACCOUNT_ID,
    val accounts: List<StoredGitHubAccount> = listOf(
        StoredGitHubAccount(id = DEFAULT_GITHUB_ACCOUNT_ID, label = "GitHub 账号 1"),
    ),
)

/**
 * Stores multiple GitHub accounts without leaving OAuth tokens in the account
 * document. The active account is mirrored into ProviderConfig by the UI layer
 * so existing Git and MCP consumers keep using their current configuration API.
 */
class AndroidGitHubAccountManager(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val documentFile = File(appContext.filesDir, DOCUMENT_FILE)
    private val protector = AndroidGitHubCredentialProtector()
    private var document = normalize(loadDocument())
    private val mutableState = MutableStateFlow(GitHubAccountPoolSnapshot())

    val state: StateFlow<GitHubAccountPoolSnapshot> = mutableState.asStateFlow()

    init {
        synchronized(this) {
            saveLocked()
            publishLocked()
        }
    }

    /** Imports the legacy single-token configuration once and returns the active credentials. */
    @Synchronized
    fun migrateLegacyAccount(
        token: String,
        backendSessionToken: String = "",
        login: String = "",
        name: String = "",
        avatarUrl: String = "",
        apiBaseUrl: String = DEFAULT_GITHUB_API_BASE_URL,
    ): GitHubAccountCredentials? {
        if (token.isBlank()) return activeCredentials()
        activeCredentials()?.let { active ->
            if (
                active.token == token.trim() &&
                (login.isBlank() || active.login.equals(login.trim(), ignoreCase = true))
            ) {
                return active
            }
        }
        saveAuthenticatedAccount(
            token = token,
            backendSessionToken = backendSessionToken,
            login = login,
            name = name,
            avatarUrl = avatarUrl,
            apiBaseUrl = apiBaseUrl,
        )
        return activeCredentials()
    }

    @Synchronized
    fun saveAuthenticatedAccount(
        token: String,
        backendSessionToken: String = "",
        login: String = "",
        name: String = "",
        avatarUrl: String = "",
        apiBaseUrl: String = DEFAULT_GITHUB_API_BASE_URL,
    ): GitHubManagedAccount {
        val normalizedToken = token.trim()
        require(normalizedToken.isNotBlank()) { "GitHub Token 不能为空" }
        require(normalizedToken.length <= MAX_TOKEN_LENGTH) { "GitHub Token 长度无效" }
        val normalizedLogin = login.trim().take(MAX_LOGIN_LENGTH)
        val matchIndex = findMatchingAccountIndex(normalizedLogin, normalizedToken)
        val targetIndex = when {
            matchIndex >= 0 -> matchIndex
            document.accounts.size == 1 && !document.accounts.first().hasToken() -> 0
            else -> {
                require(document.accounts.size < MAX_ACCOUNTS) { "最多可保存 $MAX_ACCOUNTS 个 GitHub 账号" }
                document = document.copy(
                    accounts = document.accounts + StoredGitHubAccount(
                        id = "github-account-${UUID.randomUUID()}",
                        label = accountLabel(normalizedLogin, document.accounts.size + 1),
                    ),
                )
                document.accounts.lastIndex
            }
        }
        val now = System.currentTimeMillis()
        val targetId = document.accounts[targetIndex].id
        val updated = document.accounts[targetIndex].copy(
            label = accountLabel(normalizedLogin, targetIndex + 1),
            login = normalizedLogin,
            name = name.trim().take(MAX_NAME_LENGTH),
            avatarUrl = avatarUrl.trim().take(MAX_URL_LENGTH),
            apiBaseUrl = normalizeApiBaseUrl(apiBaseUrl),
            protectedToken = protector.encrypt(normalizedToken),
            protectedBackendSessionToken = backendSessionToken.trim()
                .take(MAX_TOKEN_LENGTH)
                .takeIf { it.isNotBlank() }
                ?.let(protector::encrypt)
                .orEmpty(),
            lastCheckedAt = now,
            lastUsedAt = now,
            error = null,
        )
        document = document.copy(
            activeAccountId = targetId,
            accounts = document.accounts.mapIndexed { index, account ->
                if (index == targetIndex) updated else account
            },
        )
        saveLocked()
        publishLocked()
        return updated.toPublic(active = true)
    }

    @Synchronized
    fun activateAccount(accountId: String): GitHubAccountCredentials {
        val account = document.accounts.firstOrNull { it.id == accountId }
            ?: error("GitHub 账号不存在")
        require(account.hasToken()) { "该 GitHub 账号尚未登录" }
        document = document.copy(
            activeAccountId = accountId,
            accounts = document.accounts.map {
                if (it.id == accountId) it.copy(lastUsedAt = System.currentTimeMillis(), error = null) else it
            },
        )
        saveLocked()
        publishLocked()
        return credentialsForLocked(account)
    }

    @Synchronized
    fun updateActiveIdentity(
        login: String,
        name: String,
        avatarUrl: String = "",
    ) {
        val activeId = document.activeAccountId
        val now = System.currentTimeMillis()
        document = document.copy(
            accounts = document.accounts.mapIndexed { index, account ->
                if (account.id != activeId) account else account.copy(
                    label = accountLabel(login.trim(), index + 1),
                    login = login.trim().take(MAX_LOGIN_LENGTH),
                    name = name.trim().take(MAX_NAME_LENGTH),
                    avatarUrl = avatarUrl.trim().take(MAX_URL_LENGTH).ifBlank { account.avatarUrl },
                    lastCheckedAt = now,
                    error = null,
                )
            },
        )
        saveLocked()
        publishLocked()
    }

    @Synchronized
    fun markActiveCheckFailure(message: String) {
        val activeId = document.activeAccountId
        document = document.copy(
            accounts = document.accounts.map { account ->
                if (account.id == activeId) account.copy(
                    lastCheckedAt = System.currentTimeMillis(),
                    error = message.trim().take(MAX_ERROR_LENGTH),
                ) else account
            },
        )
        saveLocked()
        publishLocked()
    }

    /** Logs out one slot only; credentials belonging to other accounts are untouched. */
    @Synchronized
    fun logoutAccount(accountId: String = document.activeAccountId) {
        require(document.accounts.any { it.id == accountId }) { "GitHub 账号不存在" }
        document = document.copy(
            accounts = document.accounts.map { account ->
                if (account.id == accountId) account.copy(
                    protectedToken = "",
                    protectedBackendSessionToken = "",
                    lastCheckedAt = System.currentTimeMillis(),
                    error = null,
                ) else account
            },
        )
        saveLocked()
        publishLocked()
    }

    @Synchronized
    fun removeAccount(accountId: String) {
        require(document.accounts.any { it.id == accountId }) { "GitHub 账号不存在" }
        val remaining = document.accounts.filterNot { it.id == accountId }.ifEmpty {
            listOf(StoredGitHubAccount(DEFAULT_GITHUB_ACCOUNT_ID, "GitHub 账号 1"))
        }
        val nextActive = if (accountId == document.activeAccountId) {
            remaining.firstOrNull { it.hasToken() }?.id ?: remaining.first().id
        } else {
            document.activeAccountId
        }
        document = normalize(document.copy(activeAccountId = nextActive, accounts = remaining))
        saveLocked()
        publishLocked()
    }

    @Synchronized
    fun exportAccountPool(): GitHubAccountPoolTransfer = GitHubAccountPoolTransfer(
        activeAccountId = document.activeAccountId,
        accounts = document.accounts.map { account ->
            GitHubAccountTransfer(
                id = account.id,
                label = account.label,
                login = account.login,
                name = account.name,
                avatarUrl = account.avatarUrl,
                apiBaseUrl = account.apiBaseUrl,
                token = account.protectedToken.takeIf(String::isNotBlank)?.let(protector::decrypt),
                lastUsedAt = account.lastUsedAt,
            )
        },
    )

    @Synchronized
    fun importAccountPool(pool: GitHubAccountPoolTransfer, replaceExisting: Boolean = false): Int {
        require(pool.accounts.isNotEmpty() && pool.accounts.size <= MAX_ACCOUNTS) { "同步的 GitHub 账号数量无效" }
        val merged = if (replaceExisting) mutableListOf() else document.accounts.toMutableList()
        val remoteToLocal = mutableMapOf<String, String>()
        pool.accounts.forEach { incoming ->
            require(SAFE_ID.matches(incoming.id)) { "同步的 GitHub 账号 ID 无效" }
            require(incoming.label.length <= MAX_LABEL_LENGTH && incoming.label.none(Char::isISOControl)) { "同步的 GitHub 账号名称无效" }
            require(incoming.login.length <= MAX_LOGIN_LENGTH && incoming.login.none(Char::isISOControl)) { "同步的 GitHub 用户名无效" }
            require(incoming.name.length <= MAX_NAME_LENGTH && incoming.name.none(Char::isISOControl)) { "同步的 GitHub 姓名无效" }
            require(incoming.avatarUrl.length <= MAX_URL_LENGTH && incoming.avatarUrl.none(Char::isISOControl)) { "同步的 GitHub 头像地址无效" }
            val apiBaseUrl = normalizeApiBaseUrl(incoming.apiBaseUrl)
            val token = incoming.token?.trim()?.takeIf(String::isNotBlank)
            require(token == null || token.length <= MAX_TOKEN_LENGTH) { "同步的 GitHub Token 长度无效" }
            var index = merged.indexOfFirst { it.id == incoming.id }
            if (index < 0 && incoming.login.isNotBlank()) {
                index = merged.indexOfFirst { it.login.equals(incoming.login, ignoreCase = true) }
            }
            if (index < 0) {
                require(merged.size < MAX_ACCOUNTS) { "本机 GitHub 账号已达到 $MAX_ACCOUNTS 个上限" }
                merged += StoredGitHubAccount(id = incoming.id, label = incoming.label.ifBlank { "GitHub 账号 ${merged.size + 1}" })
                index = merged.lastIndex
            }
            val existing = merged[index]
            remoteToLocal[incoming.id] = existing.id
            merged[index] = existing.copy(
                label = incoming.label.trim().take(MAX_LABEL_LENGTH).ifBlank { accountLabel(incoming.login, index + 1) },
                login = incoming.login.trim().take(MAX_LOGIN_LENGTH),
                name = incoming.name.trim().take(MAX_NAME_LENGTH),
                avatarUrl = incoming.avatarUrl.trim().take(MAX_URL_LENGTH),
                apiBaseUrl = apiBaseUrl,
                protectedToken = token?.let(protector::encrypt) ?: existing.protectedToken,
                lastUsedAt = incoming.lastUsedAt,
                error = null,
            )
        }
        val targetId = remoteToLocal[pool.activeAccountId]
            ?.takeIf { id -> merged.firstOrNull { it.id == id }?.hasToken() == true }
            ?: document.activeAccountId.takeIf { id -> merged.any { it.id == id } }
            ?: merged.firstOrNull { it.hasToken() }?.id
            ?: merged.first().id
        document = normalize(document.copy(activeAccountId = targetId, accounts = merged))
        saveLocked()
        publishLocked()
        return pool.accounts.size
    }

    @Synchronized
    fun activeCredentials(): GitHubAccountCredentials? {
        val account = document.accounts.firstOrNull { it.id == document.activeAccountId } ?: return null
        if (!account.hasToken()) return null
        return credentialsForLocked(account)
    }

    @Synchronized
    fun credentialsFor(accountId: String): GitHubAccountCredentials? {
        val account = document.accounts.firstOrNull { it.id == accountId } ?: return null
        if (!account.hasToken()) return null
        return credentialsForLocked(account)
    }

    private fun credentialsForLocked(account: StoredGitHubAccount): GitHubAccountCredentials =
        GitHubAccountCredentials(
            accountId = account.id,
            token = protector.decrypt(account.protectedToken),
            backendSessionToken = account.protectedBackendSessionToken
                .takeIf { it.isNotBlank() }
                ?.let(protector::decrypt)
                .orEmpty(),
            login = account.login,
            name = account.name,
            avatarUrl = account.avatarUrl,
            apiBaseUrl = account.apiBaseUrl,
        )

    private fun findMatchingAccountIndex(login: String, token: String): Int {
        if (login.isNotBlank()) {
            document.accounts.indexOfFirst { it.login.equals(login, ignoreCase = true) }
                .takeIf { it >= 0 }
                ?.let { return it }
        }
        return document.accounts.indexOfFirst { account ->
            account.protectedToken.isNotBlank() && runCatching {
                protector.decrypt(account.protectedToken) == token
            }.getOrDefault(false)
        }
    }

    private fun loadDocument(): StoredGitHubAccountDocument {
        if (!documentFile.isFile) return StoredGitHubAccountDocument()
        return runCatching { json.decodeFromString<StoredGitHubAccountDocument>(documentFile.readText()) }
            .getOrElse { StoredGitHubAccountDocument() }
    }

    private fun normalize(source: StoredGitHubAccountDocument): StoredGitHubAccountDocument {
        val unique = source.accounts
            .filter { SAFE_ID.matches(it.id) }
            .distinctBy { it.id }
            .take(MAX_ACCOUNTS)
            .mapIndexed { index, account ->
                account.copy(
                    label = account.label.trim().take(MAX_LABEL_LENGTH)
                        .ifBlank { accountLabel(account.login, index + 1) },
                    login = account.login.trim().take(MAX_LOGIN_LENGTH),
                    name = account.name.trim().take(MAX_NAME_LENGTH),
                    avatarUrl = account.avatarUrl.trim().take(MAX_URL_LENGTH),
                    apiBaseUrl = normalizeApiBaseUrl(account.apiBaseUrl),
                )
            }
            .ifEmpty { listOf(StoredGitHubAccount(DEFAULT_GITHUB_ACCOUNT_ID, "GitHub 账号 1")) }
        val active = source.activeAccountId.takeIf { id -> unique.any { it.id == id } } ?: unique.first().id
        return source.copy(schemaVersion = 1, activeAccountId = active, accounts = unique)
    }

    private fun saveLocked() {
        writeAtomic(documentFile, json.encodeToString(document))
    }

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "无法原子保存 ${target.name}" }
        }
    }

    private fun publishLocked() {
        mutableState.value = GitHubAccountPoolSnapshot(
            activeAccountId = document.activeAccountId,
            accounts = document.accounts.map { it.toPublic(it.id == document.activeAccountId) },
        )
    }

    private fun StoredGitHubAccount.toPublic(active: Boolean): GitHubManagedAccount = GitHubManagedAccount(
        id = id,
        label = label,
        login = login,
        name = name,
        avatarUrl = avatarUrl,
        apiBaseUrl = apiBaseUrl,
        active = active,
        loggedIn = hasToken(),
        lastCheckedAt = lastCheckedAt,
        error = error,
    )

    private fun StoredGitHubAccount.hasToken(): Boolean = protectedToken.isNotBlank()

    companion object {
        const val DEFAULT_ACCOUNT_ID = DEFAULT_GITHUB_ACCOUNT_ID
        const val DOCUMENT_FILE = "github-accounts-v1.json"
        private const val MAX_ACCOUNTS = 20
        private const val MAX_TOKEN_LENGTH = 8_192
        private const val MAX_LABEL_LENGTH = 80
        private const val MAX_LOGIN_LENGTH = 80
        private const val MAX_NAME_LENGTH = 160
        private const val MAX_URL_LENGTH = 2_048
        private const val MAX_ERROR_LENGTH = 500
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,96}")
    }
}

internal fun accountLabel(login: String, position: Int): String = login.trim()
    .takeIf { it.isNotBlank() }
    ?.let { "@$it" }
    ?: "GitHub 账号 $position"

internal fun normalizeApiBaseUrl(value: String): String {
    val normalized = value.trim().trimEnd('/')
    return normalized.takeIf { it.startsWith("https://", ignoreCase = true) }
        ?: DEFAULT_GITHUB_API_BASE_URL
}

private class AndroidGitHubCredentialProtector {
    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return listOf(
            FORMAT,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP),
        ).joinToString(":")
    }

    fun decrypt(value: String): String {
        val parts = value.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == FORMAT) { "GitHub 凭据密文版本无效" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "murong_github_account_vault_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT = "v1"
    }
}
