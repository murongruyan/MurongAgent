package com.murong.agent.core.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.murong.agent.core.config.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "murong_settings")

/**
 * 配置持久化——通过 DataStore 存储 ProviderConfig
 */
class ConfigRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val CONFIG_KEY = stringPreferencesKey("provider_config")
    }

    val configFlow: Flow<ProviderConfig> = context.dataStore.data.map { prefs ->
        val raw = prefs[CONFIG_KEY]
        if (raw != null) {
            try {
                json.decodeFromString<ProviderConfig>(raw)
            } catch (e: Exception) {
                ProviderConfig()
            }
        } else {
            ProviderConfig()
        }
    }

    suspend fun getConfig(): ProviderConfig = configFlow.first()

    suspend fun saveConfig(config: ProviderConfig) {
        context.dataStore.edit { prefs ->
            prefs[CONFIG_KEY] = json.encodeToString(config)
        }
    }

    suspend fun updateApiKey(providerId: String, apiKey: String) {
        val config = getConfig()
        saveConfig(when (providerId) {
            "deepseek" -> config.copy(deepseekApiKey = apiKey)
            "openai-compatible" -> config.copy(openaiApiKey = apiKey)
            "claude" -> config.copy(claudeApiKey = apiKey)
            else -> config
        })
    }

    suspend fun updateBaseUrl(providerId: String, baseUrl: String) {
        val config = getConfig()
        saveConfig(when (providerId) {
            "deepseek" -> config.copy(deepseekBaseUrl = baseUrl)
            "openai-compatible" -> config.copy(openaiBaseUrl = baseUrl)
            "claude" -> config.copy(claudeBaseUrl = baseUrl)
            else -> config
        })
    }

    suspend fun updateModel(providerId: String, model: String) {
        val config = getConfig()
        saveConfig(when (providerId) {
            "deepseek" -> config.copy(deepseekModel = model)
            "openai-compatible" -> config.copy(openaiModel = model)
            "claude" -> config.copy(claudeModel = model)
            else -> config
        })
    }

    suspend fun setActiveProvider(providerId: String) {
        val config = getConfig()
        saveConfig(config.copy(activeProviderId = providerId))
    }
}
