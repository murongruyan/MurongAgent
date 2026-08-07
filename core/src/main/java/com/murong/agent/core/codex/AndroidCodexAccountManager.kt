package com.murong.agent.core.codex

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DEFAULT_CODEX_ACCOUNT_ID = "codex-account-default"

fun interface CodexAccountHomeProvider {
    fun activeCodexHome(): File
}

@Serializable
data class CodexAccountPoolSettings(
    val autoSwitch: Boolean = true,
    /** Remaining percentage kept in reserve. */
    val reservePercent: Int = 10,
    val cooldownMinutes: Int = 15,
)

@Serializable
data class CodexAccountQuotaSnapshot(
    val primaryUsedPercent: Int? = null,
    val primaryResetsAt: Long? = null,
    val secondaryUsedPercent: Int? = null,
    val secondaryResetsAt: Long? = null,
)

data class CodexManagedAccount(
    val id: String,
    val label: String,
    val email: String? = null,
    val planType: String? = null,
    val enabled: Boolean = true,
    val active: Boolean = false,
    val loggedIn: Boolean = false,
    val lowQuota: Boolean = false,
    val quota: CodexAccountQuotaSnapshot = CodexAccountQuotaSnapshot(),
    val lastCheckedAt: Long = 0L,
    val cooldownUntil: Long = 0L,
    val error: String? = null,
)

data class CodexAccountPoolSnapshot(
    val activeAccountId: String = "",
    val settings: CodexAccountPoolSettings = CodexAccountPoolSettings(),
    val accounts: List<CodexManagedAccount> = emptyList(),
    val lastSwitchMessage: String? = null,
)

data class CodexPreparedAccount(
    val accountId: String,
    val switched: Boolean,
    val restoredThreadRequired: Boolean,
    val message: String? = null,
)

data class CodexAccountTransfer(
    val id: String,
    val label: String,
    val email: String? = null,
    val planType: String? = null,
    val enabled: Boolean = true,
    val authJson: String? = null,
    val lastUsedAt: Long = 0L,
)

data class CodexAccountPoolTransfer(
    val activeAccountId: String,
    val settings: CodexAccountPoolSettings,
    val accounts: List<CodexAccountTransfer>,
)

@Serializable
private data class StoredCodexAccount(
    val id: String,
    val label: String,
    val email: String? = null,
    val planType: String? = null,
    val enabled: Boolean = true,
    val protectedAuth: String = "",
    val quota: CodexAccountQuotaSnapshot = CodexAccountQuotaSnapshot(),
    val lastCheckedAt: Long = 0L,
    val lastUsedAt: Long = 0L,
    val cooldownUntil: Long = 0L,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
private data class StoredCodexAccountDocument(
    val schemaVersion: Int = 1,
    val activeAccountId: String = DEFAULT_CODEX_ACCOUNT_ID,
    val settings: CodexAccountPoolSettings = CodexAccountPoolSettings(),
    val accounts: List<StoredCodexAccount> = listOf(
        StoredCodexAccount(id = DEFAULT_CODEX_ACCOUNT_ID, label = "账号 1"),
    ),
    val sessionBindings: Map<String, String> = emptyMap(),
    val pinnedSessions: Set<String> = emptySet(),
    val lastSwitchMessage: String? = null,
)

/**
 * Android account vault for the official Codex app-server.
 *
 * Every account owns an isolated CODEX_HOME. The official file credential store
 * writes auth.json; non-active copies are wrapped with an AES-GCM key held by
 * Android Keystore. Email and plan snapshots are display metadata, never proof
 * that an account can be restored after the app-server process exits.
 */
class AndroidCodexAccountManager(context: Context) : CodexAccountHomeProvider {
    private val appContext = context.applicationContext ?: context
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val documentFile = File(appContext.filesDir, DOCUMENT_FILE)
    private val legacyHome = File(appContext.filesDir, LEGACY_HOME)
    private val accountHomes = File(appContext.filesDir, ACCOUNT_HOME_ROOT)
    private val protector = AndroidCodexCredentialProtector()
    private var document = loadDocument()
    private val mutableState = MutableStateFlow(CodexAccountPoolSnapshot())

    val state: StateFlow<CodexAccountPoolSnapshot> = mutableState.asStateFlow()

    init {
        synchronized(this) {
            legacyHome.mkdirs()
            accountHomes.mkdirs()
            document = normalize(document)
            document.accounts.forEach { account ->
                captureAuthLocked(
                    account.id,
                    removePlaintext = account.id != document.activeAccountId,
                    ignoreMissing = true,
                )
            }
            materializeAuthLocked(document.activeAccountId)
            saveLocked()
            publishLocked()
        }
    }

    @Synchronized
    override fun activeCodexHome(): File = homeFor(document.activeAccountId)

    @Synchronized
    fun createAccount(label: String = ""): CodexManagedAccount {
        require(document.accounts.size < MAX_ACCOUNTS) { "最多可保存 $MAX_ACCOUNTS 个 Codex 账号" }
        val id = "codex-account-${UUID.randomUUID()}"
        val resolvedLabel = label.trim().take(80).ifBlank { "账号 ${document.accounts.size + 1}" }
        val account = StoredCodexAccount(id = id, label = resolvedLabel)
        homeFor(id).mkdirs()
        document = document.copy(accounts = document.accounts + account)
        saveLocked()
        publishLocked()
        return account.toPublic(active = false, document.settings)
    }

    /** Caller must stop the active app-server before switching homes. */
    @Synchronized
    fun activateAccount(accountId: String) {
        val target = document.accounts.firstOrNull { it.id == accountId }
            ?: error("Codex 账号不存在")
        require(target.enabled) { "Codex 账号已停用" }
        if (accountId == document.activeAccountId) {
            materializeAuthLocked(accountId)
            return
        }
        val currentId = document.activeAccountId
        captureAuthLocked(currentId, removePlaintext = true, ignoreMissing = true)
        runCatching { materializeAuthLocked(accountId) }
            .onFailure {
                materializeAuthLocked(currentId)
                throw it
            }
        val now = System.currentTimeMillis()
        document = document.copy(
            activeAccountId = accountId,
            accounts = document.accounts.map { account ->
                if (account.id == accountId) account.copy(lastUsedAt = now) else account
            },
        )
        saveLocked()
        publishLocked()
    }

    @Synchronized
    fun removeAccount(accountId: String) {
        require(accountId != document.activeAccountId) { "不能删除当前账号，请先切换" }
        require(document.accounts.any { it.id == accountId }) { "Codex 账号不存在" }
        document = document.copy(
            accounts = document.accounts.filterNot { it.id == accountId },
            sessionBindings = document.sessionBindings.filterValues { it != accountId },
        )
        saveLocked()
        homeFor(accountId).deleteRecursively()
        publishLocked()
    }

    @Synchronized
    fun setAccountEnabled(accountId: String, enabled: Boolean) {
        require(accountId != document.activeAccountId || enabled) { "当前账号不能停用" }
        require(document.accounts.any { it.id == accountId }) { "Codex 账号不存在" }
        document = document.copy(
            accounts = document.accounts.map { account ->
                if (account.id == accountId) account.copy(enabled = enabled) else account
            },
        )
        saveLocked()
        publishLocked()
    }

    @Synchronized
    fun updateSettings(settings: CodexAccountPoolSettings) {
        require(settings.reservePercent in 1..50) { "保留额度必须在 1% 到 50% 之间" }
        require(settings.cooldownMinutes in 1..1440) { "冷却时间必须在 1 到 1440 分钟之间" }
        document = document.copy(settings = settings)
        saveLocked()
        publishLocked()
    }

    @Synchronized
    fun bindSession(sessionId: String, accountId: String = document.activeAccountId) {
        if (sessionId.isBlank()) return
        document = document.copy(sessionBindings = document.sessionBindings + (sessionId to accountId))
        saveLocked()
    }

    @Synchronized
    fun setSessionPinned(sessionId: String, pinned: Boolean) {
        if (sessionId.isBlank()) return
        bindSession(sessionId)
        document = document.copy(
            pinnedSessions = if (pinned) document.pinnedSessions + sessionId else document.pinnedSessions - sessionId,
        )
        saveLocked()
        publishLocked()
    }

    @Synchronized
    fun isSessionPinned(sessionId: String): Boolean = sessionId in document.pinnedSessions

    @Synchronized
    fun preferredAccountForSession(sessionId: String): String? = document.sessionBindings[sessionId]

    @Synchronized
    /**
     * Captures account metadata and, once Codex has flushed it, wraps the active
     * auth file with the Android Keystore key. The app-server sends the login
     * completion event slightly before its auth file is guaranteed to be visible,
     * so a missing file here is recoverable and must never terminate the app.
     */
    fun captureRuntime(account: CodexAccount?, rates: CodexRateLimitsSnapshot?): Boolean {
        val activeId = document.activeAccountId
        val now = System.currentTimeMillis()
        val quota = rates?.rateLimits?.let {
            CodexAccountQuotaSnapshot(
                primaryUsedPercent = it.primary?.usedPercent,
                primaryResetsAt = it.primary?.resetsAt,
                secondaryUsedPercent = it.secondary?.usedPercent,
                secondaryResetsAt = it.secondary?.resetsAt,
            )
        }
        if (account != null) {
            captureAuthIfPresentLocked(activeId)
        }
        document = document.copy(
            accounts = document.accounts.map { stored ->
                if (stored.id != activeId) {
                    stored
                } else if (account == null) {
                    // A transient app-server restart or token refresh failure
                    // must not erase the last known account. Explicit logout is
                    // the only operation that clears durable login metadata.
                    val hasDurableLogin = codexAccountHasDurableLogin(
                        protectedAuth = stored.protectedAuth,
                        authFilePresent = File(homeFor(stored.id), AUTH_FILE).isFile,
                    )
                    stored.copy(
                        lastCheckedAt = now,
                        error = if (hasDurableLogin) {
                            "官方服务暂时未确认登录状态"
                        } else {
                            stored.error
                        },
                    )
	                } else {
	                    stored.copy(
	                        email = account.email?.trim()?.takeIf { it.isNotBlank() }?.take(320)
	                            ?: stored.email,
	                        planType = account.planType?.trim()?.takeIf { it.isNotBlank() }?.take(80)
	                            ?: stored.planType,
                        quota = quota ?: stored.quota,
                        lastCheckedAt = now,
                        lastUsedAt = now,
                        cooldownUntil = 0L,
                        error = null,
                    )
                }
            },
        )
        saveLocked()
        publishLocked()
        val active = document.accounts.firstOrNull { it.id == activeId }
        return account != null && active != null && codexAccountHasDurableLogin(
            protectedAuth = active.protectedAuth,
            authFilePresent = File(homeFor(active.id), AUTH_FILE).isFile,
        )
    }

    @Synchronized
    fun clearActiveLogin() {
        val activeId = document.activeAccountId
        File(homeFor(activeId), AUTH_FILE).delete()
        document = document.copy(
            accounts = document.accounts.map { account ->
                if (account.id == activeId) account.copy(
                    protectedAuth = "",
                    email = null,
                    planType = null,
                    quota = CodexAccountQuotaSnapshot(),
                    lastCheckedAt = System.currentTimeMillis(),
                    error = null,
                ) else account
            },
        )
        saveLocked()
        publishLocked()
    }

    @Synchronized
    fun activeAccountId(): String = document.activeAccountId

    @Synchronized
    fun settings(): CodexAccountPoolSettings = document.settings

    @Synchronized
    fun exportAccountPool(): CodexAccountPoolTransfer = CodexAccountPoolTransfer(
        activeAccountId = document.activeAccountId,
        settings = document.settings,
        accounts = document.accounts.map { account ->
            val authFile = File(homeFor(account.id), AUTH_FILE)
            val auth = when {
                authFile.isFile -> authFile.readText().also(::validateAuth)
                account.protectedAuth.isNotBlank() -> protector.decrypt(account.protectedAuth).also(::validateAuth)
                else -> null
            }
            CodexAccountTransfer(
                id = account.id,
                label = account.label,
                email = account.email,
                planType = account.planType,
                enabled = account.enabled,
                authJson = auth,
                lastUsedAt = account.lastUsedAt,
            )
        },
    )

    @Synchronized
    fun importAccountPool(pool: CodexAccountPoolTransfer, replaceExisting: Boolean = false): Int {
        require(pool.accounts.isNotEmpty() && pool.accounts.size <= MAX_ACCOUNTS) { "同步的 Codex 账号数量无效" }
        require(pool.settings.reservePercent in 1..50) { "同步的 Codex 保留额度无效" }
        require(pool.settings.cooldownMinutes in 1..1440) { "同步的 Codex 冷却时间无效" }
        val currentId = document.activeAccountId
        captureAuthIfPresentLocked(currentId)
        saveLocked()
        val original = document
        val merged = if (replaceExisting) mutableListOf() else document.accounts.toMutableList()
        val remoteToLocal = mutableMapOf<String, String>()
        pool.accounts.forEach { incoming ->
            require(SAFE_ID.matches(incoming.id)) { "同步的 Codex 账号 ID 无效" }
            require(incoming.label.length <= 80 && incoming.label.none(Char::isISOControl)) { "同步的 Codex 账号名称无效" }
            require(incoming.email.orEmpty().length <= 320 && incoming.email.orEmpty().none(Char::isISOControl)) { "同步的 Codex 邮箱无效" }
            require(incoming.planType.orEmpty().length <= 80 && incoming.planType.orEmpty().none(Char::isISOControl)) { "同步的 Codex套餐无效" }
            var index = merged.indexOfFirst { it.id == incoming.id }
            if (index < 0 && !incoming.email.isNullOrBlank()) {
                index = merged.indexOfFirst { it.email.equals(incoming.email, ignoreCase = true) }
            }
            if (index < 0) {
                require(merged.size < MAX_ACCOUNTS) { "本机 Codex 账号已达到 $MAX_ACCOUNTS 个上限" }
                merged += StoredCodexAccount(id = incoming.id, label = incoming.label.ifBlank { "账号 ${merged.size + 1}" })
                index = merged.lastIndex
            }
            val existing = merged[index]
            remoteToLocal[incoming.id] = existing.id
            val protectedAuth = incoming.authJson
                ?.takeIf { it.isNotBlank() }
                ?.also(::validateAuth)
                ?.let(protector::encrypt)
                ?: existing.protectedAuth
            merged[index] = existing.copy(
                label = incoming.label.trim().take(80).ifBlank { "账号 ${index + 1}" },
                email = incoming.email?.trim()?.take(320)?.takeIf(String::isNotBlank) ?: existing.email,
                planType = incoming.planType?.trim()?.take(80)?.takeIf(String::isNotBlank) ?: existing.planType,
                enabled = incoming.enabled,
                protectedAuth = protectedAuth,
                lastUsedAt = incoming.lastUsedAt,
            )
        }
        val requestedActive = remoteToLocal[pool.activeAccountId]
        val targetId = requestedActive
            ?.takeIf { candidate ->
                merged.firstOrNull { it.id == candidate }?.let(::isAccountMaterialized) == true
            }
            ?: currentId.takeIf { id -> merged.any { it.id == id } }
            ?: merged.firstOrNull { it.protectedAuth.isNotBlank() }?.id
            ?: merged.first().id
        val next = normalize(
            document.copy(
                activeAccountId = targetId,
                settings = pool.settings,
                accounts = merged.map { if (it.id == targetId) it.copy(enabled = true) else it },
            ),
        )
        document = next
        try {
            saveLocked()
            if (targetId != currentId) {
                val oldAuth = File(homeFor(currentId), AUTH_FILE)
                check(!oldAuth.exists() || oldAuth.delete()) { "无法封存当前 Codex 账号" }
            }
            materializeAuthLocked(targetId)
            saveLocked()
            publishLocked()
            return pool.accounts.size
        } catch (error: Throwable) {
            document = original
            runCatching { saveLocked() }
            if (targetId != original.activeAccountId) {
                runCatching { File(homeFor(targetId), AUTH_FILE).delete() }
            }
            runCatching { materializeAuthLocked(original.activeAccountId) }
            publishLocked()
            throw error
        }
    }

    @Synchronized
    fun isLowQuota(accountId: String): Boolean = document.accounts
        .firstOrNull { it.id == accountId }
        ?.quota
        ?.isBelowReserve(document.settings.reservePercent)
        ?: false

    @Synchronized
    fun bestAccount(preferredId: String? = null, excluded: Set<String> = emptySet(), allowLowQuota: Boolean = false): String? {
        val now = System.currentTimeMillis()
        val eligible = document.accounts.filter { account ->
            account.enabled && account.id !in excluded && account.cooldownUntil <= now &&
                isAccountMaterialized(account) &&
                (allowLowQuota || !account.quota.isBelowReserve(document.settings.reservePercent))
        }
        preferredId?.let { preferred -> eligible.firstOrNull { it.id == preferred }?.let { return it.id } }
        return eligible.maxWithOrNull(
            compareBy<StoredCodexAccount> { it.quota.remainingScore() }
                .thenByDescending { -it.lastUsedAt },
        )?.id
    }

    @Synchronized
    fun markFailure(accountId: String, message: String) {
        val until = System.currentTimeMillis() + document.settings.cooldownMinutes * 60_000L
        document = document.copy(
            accounts = document.accounts.map { account ->
                if (account.id == accountId) account.copy(
                    cooldownUntil = until,
                    error = message.trim().take(500),
                ) else account
            },
        )
        saveLocked()
        publishLocked()
    }

    @Synchronized
    fun recordSwitchMessage(message: String?) {
        document = document.copy(lastSwitchMessage = message?.trim()?.take(300))
        saveLocked()
        publishLocked()
    }

    @Synchronized
    fun activeAuthFile(): File = File(activeCodexHome(), AUTH_FILE)

    private fun loadDocument(): StoredCodexAccountDocument {
        if (!documentFile.isFile) return StoredCodexAccountDocument()
        return runCatching { json.decodeFromString<StoredCodexAccountDocument>(documentFile.readText()) }
            .getOrElse { StoredCodexAccountDocument() }
    }

    private fun normalize(source: StoredCodexAccountDocument): StoredCodexAccountDocument {
        val unique = source.accounts
            .filter { SAFE_ID.matches(it.id) }
            .distinctBy { it.id }
            .take(MAX_ACCOUNTS)
            .mapIndexed { index, account ->
                account.copy(label = account.label.trim().take(80).ifBlank { "账号 ${index + 1}" })
            }
            .ifEmpty { listOf(StoredCodexAccount(DEFAULT_ACCOUNT_ID, "账号 1")) }
        val active = source.activeAccountId.takeIf { id -> unique.any { it.id == id } } ?: unique.first().id
        val settings = source.settings.copy(
            reservePercent = source.settings.reservePercent.takeIf { it in 1..50 } ?: 10,
            cooldownMinutes = source.settings.cooldownMinutes.takeIf { it in 1..1440 } ?: 15,
        )
        return source.copy(
            schemaVersion = 1,
            activeAccountId = active,
            settings = settings,
            accounts = unique.map { if (it.id == active) it.copy(enabled = true) else it },
            sessionBindings = source.sessionBindings.filter { (session, account) ->
                session.isNotBlank() && unique.any { it.id == account }
            },
            pinnedSessions = source.pinnedSessions.filterTo(linkedSetOf()) { it in source.sessionBindings },
        )
    }

    private fun homeFor(accountId: String): File = if (accountId == DEFAULT_ACCOUNT_ID) {
        legacyHome
    } else {
        File(accountHomes, accountId)
    }

    private fun captureAuthLocked(accountId: String, removePlaintext: Boolean, ignoreMissing: Boolean): Boolean {
        val index = document.accounts.indexOfFirst { it.id == accountId }
        if (index < 0) return false
        val authFile = File(homeFor(accountId), AUTH_FILE)
        if (!authFile.isFile) {
            if (ignoreMissing) return false
            error("Codex 登录文件不存在")
        }
        val raw = authFile.readText()
        validateAuth(raw)
        val protected = protector.encrypt(raw)
        document = document.copy(
            accounts = document.accounts.mapIndexed { current, account ->
                if (current == index) account.copy(protectedAuth = protected) else account
            },
        )
        if (removePlaintext) {
            // Commit the Keystore-wrapped copy before deleting auth.json.
            saveLocked()
            if (!authFile.delete() && authFile.exists()) {
                error("无法移除非活动账号的明文登录文件")
            }
        }
        return true
    }

    private fun captureAuthIfPresentLocked(accountId: String): Boolean {
        val authFile = File(homeFor(accountId), AUTH_FILE)
        if (!authFile.isFile) return false
        return runCatching {
            captureAuthLocked(accountId, removePlaintext = false, ignoreMissing = false)
        }.getOrDefault(false)
    }

    private fun isAccountMaterialized(account: StoredCodexAccount): Boolean {
        return codexAccountHasDurableLogin(
            protectedAuth = account.protectedAuth,
            authFilePresent = File(homeFor(account.id), AUTH_FILE).isFile,
        )
    }

    private fun materializeAuthLocked(accountId: String) {
        val account = document.accounts.firstOrNull { it.id == accountId } ?: error("Codex 账号不存在")
        val home = homeFor(accountId)
        check(home.isDirectory || home.mkdirs()) { "无法创建 Codex 账号目录" }
        val authFile = File(home, AUTH_FILE)
        if (authFile.isFile || account.protectedAuth.isBlank()) return
        val raw = protector.decrypt(account.protectedAuth)
        validateAuth(raw)
        writeAtomic(authFile, raw)
    }

    private fun validateAuth(raw: String) {
        require(raw.toByteArray().size in 1..MAX_AUTH_BYTES) { "Codex 登录文件大小无效" }
        val root = json.parseToJsonElement(raw).jsonObject
        require(root["auth_mode"]?.jsonPrimitive?.content?.isNotBlank() == true) { "Codex 登录缺少 auth_mode" }
        require(root["tokens"] is JsonObject || root["OPENAI_API_KEY"] != null) { "Codex 登录缺少凭据" }
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
        mutableState.value = CodexAccountPoolSnapshot(
            activeAccountId = document.activeAccountId,
            settings = document.settings,
            accounts = document.accounts.map { it.toPublic(it.id == document.activeAccountId, document.settings) },
            lastSwitchMessage = document.lastSwitchMessage,
        )
    }

    private fun StoredCodexAccount.toPublic(
        active: Boolean,
        settings: CodexAccountPoolSettings,
    ): CodexManagedAccount = CodexManagedAccount(
        id = id,
        label = label,
        email = email,
        planType = planType,
        enabled = enabled,
        active = active,
        loggedIn = codexAccountHasDurableLogin(
            protectedAuth = protectedAuth,
            authFilePresent = File(homeFor(id), AUTH_FILE).isFile,
        ),
        lowQuota = quota.isBelowReserve(settings.reservePercent),
        quota = quota,
        lastCheckedAt = lastCheckedAt,
        cooldownUntil = cooldownUntil,
        error = error,
    )

    companion object {
        const val DEFAULT_ACCOUNT_ID = DEFAULT_CODEX_ACCOUNT_ID
        private const val DOCUMENT_FILE = "codex-accounts-v1.json"
        private const val LEGACY_HOME = "codex-home"
        private const val ACCOUNT_HOME_ROOT = "codex-accounts"
        private const val AUTH_FILE = "auth.json"
        private const val MAX_ACCOUNTS = 20
        private const val MAX_AUTH_BYTES = 256 * 1024
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,96}")
    }
}

/** Only credential material is durable; an email snapshot cannot restore a session. */
internal fun codexAccountHasDurableLogin(
    protectedAuth: String,
    authFilePresent: Boolean,
): Boolean = protectedAuth.isNotBlank() || authFilePresent

internal fun CodexAccountQuotaSnapshot.isBelowReserve(reservePercent: Int): Boolean {
    val usedThreshold = 100 - reservePercent
    return listOfNotNull(primaryUsedPercent, secondaryUsedPercent).any { it >= usedThreshold }
}

internal fun CodexAccountQuotaSnapshot.remainingScore(): Int {
    val used = listOfNotNull(primaryUsedPercent, secondaryUsedPercent)
    return if (used.isEmpty()) -1 else 100 - used.max()
}

private class AndroidCodexCredentialProtector {
    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return listOf(
            FORMAT,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(payload, Base64.NO_WRAP),
        ).joinToString(":")
    }

    fun decrypt(value: String): String {
        val parts = value.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == FORMAT) { "Codex 凭据密文版本无效" }
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
        const val KEY_ALIAS = "murong_codex_account_vault_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT = "v1"
    }
}
