package com.murong.agent.lan

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface LanWebSyncKeyStore {
    fun put(clientId: String, key: ByteArray)
    fun read(clientId: String): ByteArray?
    fun remove(clientId: String)
    fun clear()
}

/** Device-sync keys are hardware/OS wrapped and are never stored in lan_web_access.json. */
internal class AndroidLanWebSyncKeyStore(context: Context) : LanWebSyncKeyStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun put(clientId: String, key: ByteArray) {
        require(LanWebContract.requestIdPattern.matches(clientId)) { "客户端 ID 无效" }
        require(key.size == SYNC_KEY_BYTES) { "设备同步密钥长度无效" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(clientId.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(key)
        val value = listOf(
            FORMAT_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(":")
        check(prefs.edit().putString(clientId, value).commit()) { "无法安全保存设备同步密钥" }
    }

    override fun read(clientId: String): ByteArray? {
        if (!LanWebContract.requestIdPattern.matches(clientId)) return null
        val encoded = prefs.getString(clientId, null).orEmpty()
        if (encoded.isBlank()) return null
        return runCatching {
            val parts = encoded.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == FORMAT_VERSION)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(clientId.toByteArray(StandardCharsets.UTF_8))
            cipher.doFinal(ciphertext).also { require(it.size == SYNC_KEY_BYTES) }
        }.getOrElse {
            prefs.edit().remove(clientId).commit()
            null
        }
    }

    override fun remove(clientId: String) {
        prefs.edit().remove(clientId).commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
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
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "murong_lan_sync_keys"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "murong_lan_sync_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = "v1"
        const val GCM_TAG_BITS = 128
        const val SYNC_KEY_BYTES = 32
    }
}
