package com.murong.agent.core.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "murong_settings")

/**
 * 配置持久化——通过 DataStore 存储 ProviderConfig
 */
class ConfigRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val secureSecretStore = SecureConfigSecretStore(context)

    companion object {
        private val CONFIG_KEY = stringPreferencesKey("provider_config")
        private val CONFIG_REVISION_KEY = longPreferencesKey("provider_config_revision")
        private const val SECRET_DEEPSEEK_API_KEY = "deepseek_api_key"
        private const val SECRET_OPENAI_API_KEY = "openai_api_key"
        private const val SECRET_CLAUDE_API_KEY = "claude_api_key"
        private const val SECRET_GITHUB_TOKEN = "github_token"
        private const val SECRET_GITHUB_CLIENT_SECRET = "github_client_secret"
        private const val SECRET_GITHUB_BACKEND_SESSION_TOKEN = "github_backend_session_token"
        private const val SECRET_WEB_SEARCH_BING_API_KEY = "web_search_bing_api_key"
        private val INPUT_HISTORY_KEY = stringPreferencesKey("chat_input_history")
    }

    private var legacyMigrationDone = false

    private suspend fun ensureLegacyMigration() {
        if (!legacyMigrationDone) {
            legacyMigrationDone = true
            migrateLegacyPlaintextSecretsIfNeeded()
        }
    }

    val configFlow: Flow<ProviderConfig> = context.dataStore.data.map { prefs ->
        decodeConfig(prefs[CONFIG_KEY])
            .withLegacyRelayConfigurations()
            .withCurrentAgentBehaviorDefaults()
            .withSensitiveSecrets(readSensitiveSecrets())
    }

    suspend fun getConfig(): ProviderConfig {
        ensureLegacyMigration()
        return configFlow.first()
    }

    suspend fun saveConfig(config: ProviderConfig) {
        val migratedConfig = config
            .withLegacyRelayConfigurations()
            .withCurrentAgentBehaviorDefaults()
        writeSensitiveSecrets(migratedConfig)
        val sanitizedConfig = migratedConfig.withSensitiveSecretsCleared()
        context.dataStore.edit { prefs ->
            prefs[CONFIG_KEY] = json.encodeToString(sanitizedConfig)
            prefs[CONFIG_REVISION_KEY] = System.currentTimeMillis()
        }
    }

    suspend fun updateApiKey(providerId: String, apiKey: String) {
        updateActiveRelay(providerId) { it.copy(apiKey = apiKey) }
    }

    suspend fun updateBaseUrl(providerId: String, baseUrl: String) {
        updateActiveRelay(providerId) { it.copy(baseUrl = baseUrl) }
    }

    suspend fun updateModel(providerId: String, model: String) {
        updateActiveRelay(providerId) { it.copy(model = model) }
    }

    suspend fun addRelay(providerId: String, relay: RelayConfig) {
        val config = getConfig()
        val normalized = relay.copy(id = relay.id.trim().ifBlank { "relay-${System.currentTimeMillis()}" })
        val relays = config.getRelayConfigs(providerId).filterNot { it.id == normalized.id } + normalized
        saveConfig(config.withRelayConfigs(providerId, relays, normalized.id))
    }

    suspend fun selectRelay(providerId: String, relayId: String) {
        val config = getConfig()
        saveConfig(config.selectConfiguration(providerId, relayId))
    }

    private suspend fun updateActiveRelay(providerId: String, transform: (RelayConfig) -> RelayConfig) {
        val config = getConfig()
        val active = config.getActiveRelay(providerId) ?: return
        saveConfig(config.updateActiveRelay(providerId, transform))
    }

    suspend fun setActiveProvider(providerId: String) {
        val config = getConfig()
        saveConfig(config.copy(activeProviderId = providerId))
    }

    suspend fun getInputHistory(): List<String> {
        val prefs = context.dataStore.data.first()
        val raw = prefs[INPUT_HISTORY_KEY] ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveInputHistory(history: List<String>) {
        val sanitized = history.takeLast(50)
        context.dataStore.edit { prefs ->
            prefs[INPUT_HISTORY_KEY] = json.encodeToString(sanitized)
        }
    }

    fun listDurableGlobalMemories(): List<GlobalMemory> {
        return loadDurableGlobalMemorySnapshots()
            .asSequence()
            .mapNotNull { entry ->
                val title = entry.title.trim()
                val content = entry.content.trim()
                if (title.isBlank() || content.isBlank()) {
                    null
                } else {
                    GlobalMemory(
                        id = entry.id,
                        title = title,
                        content = content,
                        enabled = entry.enabled
                    )
                }
            }
            .sortedWith(
                compareByDescending<GlobalMemory> { it.enabled }
                    .thenByDescending { memory ->
                        loadDurableGlobalMemorySnapshots().firstOrNull { it.id == memory.id }?.updatedAt ?: 0L
                    }
            )
            .toList()
    }

    suspend fun updateDurableGlobalMemory(memory: GlobalMemory): Boolean {
        val title = memory.title.trim()
        val content = memory.content.trim()
        if (memory.id.isBlank() || title.isBlank() || content.isBlank()) return false
        val existing = loadDurableGlobalMemorySnapshots()
        var updatedAny = false
        val updated = existing.map { entry ->
            if (entry.id != memory.id) {
                entry
            } else {
                updatedAny = true
                entry.copy(
                    title = title,
                    content = content,
                    enabled = memory.enabled,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        if (!updatedAny) return false
        saveDurableGlobalMemorySnapshots(updated)
        return true
    }

    suspend fun deleteDurableGlobalMemory(memoryId: String): Boolean {
        val normalizedId = memoryId.trim()
        if (normalizedId.isBlank()) return false
        val existing = loadDurableGlobalMemorySnapshots()
        val updated = existing.filterNot { it.id == normalizedId }
        if (existing.size == updated.size) return false
        saveDurableGlobalMemorySnapshots(updated)
        return true
    }

    private suspend fun migrateLegacyPlaintextSecretsIfNeeded() {
        val prefs = context.dataStore.data.first()
        val rawConfig = decodeConfig(prefs[CONFIG_KEY])
        val requiresRelayMigration = rawConfig.deepseekRelays.isEmpty() ||
            rawConfig.openaiRelays.isEmpty() ||
            rawConfig.claudeRelays.isEmpty()
        val config = rawConfig
            .withLegacyRelayConfigurations()
            .withCurrentAgentBehaviorDefaults()
            .withSensitiveSecrets(readSensitiveSecrets())
        if (!requiresRelayMigration && !config.hasPlaintextSensitiveSecrets()) return
        writeSensitiveSecrets(config)
        context.dataStore.edit { mutablePrefs ->
            mutablePrefs[CONFIG_KEY] = json.encodeToString(config.withSensitiveSecretsCleared())
        }
    }

    private fun decodeConfig(raw: String?): ProviderConfig {
        if (raw == null) return ProviderConfig()
        return try {
            json.decodeFromString<ProviderConfig>(raw)
        } catch (_: Exception) {
            ProviderConfig()
        }
    }

    private fun durableGlobalMemoryFile(): File {
        return File(context.filesDir, "memories/global_memories.json")
    }

    private fun loadDurableGlobalMemorySnapshots(): List<PersistedMemoryEntrySnapshot> {
        val file = durableGlobalMemoryFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<PersistedMemoryEntrySnapshot>>(file.readText())
        }.getOrDefault(emptyList())
    }

    private fun saveDurableGlobalMemorySnapshots(entries: List<PersistedMemoryEntrySnapshot>) {
        val file = durableGlobalMemoryFile()
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(entries))
    }

    private fun readSensitiveSecrets(): SensitiveSecrets {
        return SensitiveSecrets(
            deepseekApiKey = secureSecretStore.read(SECRET_DEEPSEEK_API_KEY),
            openaiApiKey = secureSecretStore.read(SECRET_OPENAI_API_KEY),
            claudeApiKey = secureSecretStore.read(SECRET_CLAUDE_API_KEY),
            githubToken = secureSecretStore.read(SECRET_GITHUB_TOKEN),
            githubClientSecret = secureSecretStore.read(SECRET_GITHUB_CLIENT_SECRET),
            githubBackendSessionToken = secureSecretStore.read(SECRET_GITHUB_BACKEND_SESSION_TOKEN),
            webSearchBingApiKey = secureSecretStore.read(SECRET_WEB_SEARCH_BING_API_KEY)
        )
    }

    private fun writeSensitiveSecrets(config: ProviderConfig) {
        secureSecretStore.write(SECRET_DEEPSEEK_API_KEY, config.deepseekApiKey)
        secureSecretStore.write(SECRET_OPENAI_API_KEY, config.openaiApiKey)
        secureSecretStore.write(SECRET_CLAUDE_API_KEY, config.claudeApiKey)
        listOf("deepseek", "openai-compatible", "claude").forEach { providerId ->
            config.getRelayConfigs(providerId).forEach { relay ->
                secureSecretStore.write(relaySecretKey(providerId, relay.id), relay.apiKey)
            }
        }
        secureSecretStore.write(SECRET_GITHUB_TOKEN, config.githubToken)
        secureSecretStore.write(SECRET_GITHUB_CLIENT_SECRET, config.githubClientSecret)
        secureSecretStore.write(SECRET_GITHUB_BACKEND_SESSION_TOKEN, config.githubBackendSessionToken)
        secureSecretStore.write(SECRET_WEB_SEARCH_BING_API_KEY, config.webSearchBingApiKey)
    }

    private data class SensitiveSecrets(
        val deepseekApiKey: String = "",
        val openaiApiKey: String = "",
        val claudeApiKey: String = "",
        val githubToken: String = "",
        val githubClientSecret: String = "",
        val githubBackendSessionToken: String = "",
        val webSearchBingApiKey: String = ""
    )

    private fun ProviderConfig.withSensitiveSecrets(secrets: SensitiveSecrets): ProviderConfig {
        val restoredDeepseekKey = secrets.deepseekApiKey.ifBlank { deepseekApiKey }
        val restoredOpenaiKey = secrets.openaiApiKey.ifBlank { openaiApiKey }
        val restoredClaudeKey = secrets.claudeApiKey.ifBlank { claudeApiKey }
        fun restoreRelaySecrets(
            providerId: String,
            relays: List<RelayConfig>,
            legacyRelayId: String,
            legacyApiKey: String
        ): List<RelayConfig> = relays.map { relay ->
            val relayApiKey = secureSecretStore.read(relaySecretKey(providerId, relay.id))
                .ifBlank { relay.apiKey }
                .ifBlank { if (relay.id == legacyRelayId) legacyApiKey else "" }
            relay.copy(apiKey = relayApiKey)
        }
        return copy(
            deepseekApiKey = restoredDeepseekKey,
            openaiApiKey = restoredOpenaiKey,
            claudeApiKey = restoredClaudeKey,
            deepseekRelays = restoreRelaySecrets("deepseek", deepseekRelays, "legacy-deepseek", restoredDeepseekKey),
            openaiRelays = restoreRelaySecrets("openai-compatible", openaiRelays, "legacy-openai-compatible", restoredOpenaiKey),
            claudeRelays = restoreRelaySecrets("claude", claudeRelays, "legacy-claude", restoredClaudeKey),
            githubToken = secrets.githubToken.ifBlank { githubToken },
            githubClientSecret = secrets.githubClientSecret.ifBlank { githubClientSecret },
            githubBackendSessionToken = secrets.githubBackendSessionToken.ifBlank { githubBackendSessionToken },
            webSearchBingApiKey = secrets.webSearchBingApiKey.ifBlank { webSearchBingApiKey }
        )
    }

    private fun ProviderConfig.withSensitiveSecretsCleared(): ProviderConfig {
        fun clearRelaySecrets(relays: List<RelayConfig>) = relays.map { it.copy(apiKey = "") }
        return copy(
            deepseekApiKey = "",
            openaiApiKey = "",
            claudeApiKey = "",
            deepseekRelays = clearRelaySecrets(deepseekRelays),
            openaiRelays = clearRelaySecrets(openaiRelays),
            claudeRelays = clearRelaySecrets(claudeRelays),
            githubToken = "",
            githubClientSecret = "",
            githubBackendSessionToken = "",
            webSearchBingApiKey = ""
        )
    }

    private fun relaySecretKey(providerId: String, relayId: String): String = "relay_api_key_${providerId}_${relayId}"

    private fun ProviderConfig.hasPlaintextSensitiveSecrets(): Boolean {
        return deepseekApiKey.isNotBlank() ||
            openaiApiKey.isNotBlank() ||
            claudeApiKey.isNotBlank() ||
            deepseekRelays.any { it.apiKey.isNotBlank() } ||
            openaiRelays.any { it.apiKey.isNotBlank() } ||
            claudeRelays.any { it.apiKey.isNotBlank() } ||
            githubToken.isNotBlank() ||
            githubClientSecret.isNotBlank() ||
            githubBackendSessionToken.isNotBlank() ||
            webSearchBingApiKey.isNotBlank()
    }

    @Serializable
    private data class PersistedMemoryEntrySnapshot(
        val id: String,
        val title: String,
        val content: String,
        val enabled: Boolean = true,
        val updatedAt: Long = 0L
    )
}
