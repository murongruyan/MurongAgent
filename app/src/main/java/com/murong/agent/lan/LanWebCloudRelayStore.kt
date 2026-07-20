package com.murong.agent.lan

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class LanWebCloudRelayConfig(
    val schemaVersion: Int = 1,
    val enabled: Boolean = false,
    val relayUrl: String = LanWebCloudRelayProtocol.OFFICIAL_RELAY_URL,
    val roomId: String = "",
)

internal interface LanWebCloudRelaySecretStore {
    fun put(secret: ByteArray)
    fun read(): ByteArray?
    fun clear()
}

@Singleton
internal class LanWebCloudRelayConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val stateFile = File(context.noBackupFilesDir, STATE_FILE_NAME)
    private val secretStore: LanWebCloudRelaySecretStore = AndroidLanWebCloudRelaySecretStore(context)
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        prettyPrint = true
    }

    @Synchronized
    fun load(): LanWebCloudRelayConfig {
        if (!stateFile.isFile) return LanWebCloudRelayConfig()
        return runCatching {
            json.decodeFromString<LanWebCloudRelayConfig>(stateFile.readText())
                .withOfficialRelayDefault()
                .also(::validatePersisted)
        }.getOrElse {
            secretStore.clear()
            stateFile.delete()
            LanWebCloudRelayConfig()
        }
    }

    @Synchronized
    fun configure(enabled: Boolean, relayUrl: String): LanWebCloudRelayConfig {
        val current = load()
        val normalizedUrl = LanWebCloudRelayProtocol.normalizeRelayUrl(
            relayUrl.trim().ifEmpty { LanWebCloudRelayProtocol.OFFICIAL_RELAY_URL }
        )
        require(!enabled || normalizedUrl.isNotEmpty()) { "启用云中继前请填写 wss:// 地址" }
        val withShare = if (enabled && !hasUsableShare(current)) regenerateLocked(current) else current
        return withShare.copy(enabled = enabled, relayUrl = normalizedUrl).also(::write)
    }

    @Synchronized
    fun regenerate(): Pair<LanWebCloudRelayConfig, String> {
        val updated = regenerateLocked(load())
        return updated to requireNotNull(shareCode(updated))
    }

    @Synchronized
    fun shareCode(config: LanWebCloudRelayConfig = load()): String? {
        val secret = secretStore.read() ?: return null
        return try {
            LanWebCloudRelayProtocol.formatShareCode(config.roomId, secret)
        } catch (_: Throwable) {
            null
        } finally {
            secret.fill(0)
        }
    }

    @Synchronized
    fun secret(config: LanWebCloudRelayConfig = load()): ByteArray? {
        if (config.roomId.isBlank()) return null
        return secretStore.read()
    }

    private fun hasUsableShare(config: LanWebCloudRelayConfig): Boolean =
        config.roomId.isNotBlank() && shareCode(config) != null

    private fun regenerateLocked(current: LanWebCloudRelayConfig): LanWebCloudRelayConfig {
        val previousSecret = secretStore.read()
        val share = LanWebCloudRelayProtocol.generateShare()
        return try {
            secretStore.put(share.secret)
            current.copy(roomId = share.roomId).also(::write)
        } catch (error: Throwable) {
            runCatching {
                if (previousSecret != null) secretStore.put(previousSecret) else secretStore.clear()
            }.onFailure { secretStore.clear() }
            throw error
        } finally {
            previousSecret?.fill(0)
            share.secret.fill(0)
        }
    }

    private fun validatePersisted(config: LanWebCloudRelayConfig) {
        require(config.schemaVersion == 1) { "云中继配置版本不受支持" }
        require(!config.enabled || config.relayUrl.isNotBlank()) { "云中继地址缺失" }
        if (config.relayUrl.isNotBlank()) LanWebCloudRelayProtocol.normalizeRelayUrl(config.relayUrl)
        require(config.roomId.isBlank() || runCatching {
            LanWebCloudRelayProtocol.formatShareCode(config.roomId, ByteArray(32))
        }.isSuccess) { "云中继房间 ID 无效" }
    }

    private fun LanWebCloudRelayConfig.withOfficialRelayDefault(): LanWebCloudRelayConfig =
        if (relayUrl.isBlank()) copy(relayUrl = LanWebCloudRelayProtocol.OFFICIAL_RELAY_URL) else this

    private fun write(config: LanWebCloudRelayConfig) {
        validatePersisted(config)
        stateFile.parentFile?.mkdirs()
        val temporary = File(stateFile.parentFile, ".${stateFile.name}.tmp")
        temporary.writeText(json.encodeToString(config))
        check(temporary.renameTo(stateFile) || runCatching {
            temporary.copyTo(stateFile, overwrite = true)
            temporary.delete()
        }.isSuccess) { "无法保存云中继配置" }
    }

    private companion object {
        const val STATE_FILE_NAME = "lan_cloud_relay.json"
    }
}

private class AndroidLanWebCloudRelaySecretStore(context: Context) : LanWebCloudRelaySecretStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun put(secret: ByteArray) {
        require(secret.size == 32) { "云中继端到端密钥长度无效" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(secret)
        val encoded = listOf(
            FORMAT_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(":")
        check(prefs.edit().putString(SECRET_KEY, encoded).commit()) { "无法安全保存云中继密钥" }
    }

    override fun read(): ByteArray? {
        val encoded = prefs.getString(SECRET_KEY, null).orEmpty()
        if (encoded.isBlank()) return null
        return runCatching {
            val parts = encoded.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == FORMAT_VERSION)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD)
            cipher.doFinal(ciphertext).also { require(it.size == 32) }
        }.getOrElse {
            clear()
            null
        }
    }

    override fun clear() {
        prefs.edit().remove(SECRET_KEY).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "murong_lan_cloud_relay"
        const val SECRET_KEY = "relay_secret"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "murong_lan_cloud_relay_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = "v1"
        const val GCM_TAG_BITS = 128
        val AAD: ByteArray = "murong-cloud-relay-secret-v1".toByteArray(StandardCharsets.UTF_8)
    }
}
