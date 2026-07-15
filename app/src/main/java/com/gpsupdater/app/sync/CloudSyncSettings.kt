package com.gpsupdater.app.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CloudSyncConfiguration(
    val endpoint: String,
    val syncKey: String,
)

class CloudSyncSettings(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): CloudSyncConfiguration = CloudSyncConfiguration(
        endpoint = preferences.getString(KEY_ENDPOINT, "").orEmpty(),
        syncKey = decryptStoredKey(),
    )

    fun save(configuration: CloudSyncConfiguration) {
        val encrypted = encryptKey(configuration.syncKey)
        preferences.edit()
            .putString(KEY_ENDPOINT, configuration.endpoint.trim().trimEnd('/'))
            .putString(KEY_SYNC_KEY, encrypted.first)
            .putString(KEY_SYNC_KEY_NONCE, encrypted.second)
            .apply()
    }

    private fun encryptKey(value: String): Pair<String, String> {
        if (value.isBlank()) return "" to ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return encode(cipher.doFinal(value.toByteArray(Charsets.UTF_8))) to encode(cipher.iv)
    }

    private fun decryptStoredKey(): String {
        val encrypted = preferences.getString(KEY_SYNC_KEY, null)?.takeIf { it.isNotBlank() }
            ?: return ""
        val nonce = preferences.getString(KEY_SYNC_KEY_NONCE, null)?.takeIf { it.isNotBlank() }
            ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, decode(nonce)),
            )
            cipher.doFinal(decode(encrypted)).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encode(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    companion object {
        private const val FILE_NAME = "cloud_sync"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_SYNC_KEY = "encrypted_sync_key"
        private const val KEY_SYNC_KEY_NONCE = "sync_key_nonce"
        private const val KEY_ALIAS = "gps_updater_cloud_sync"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
