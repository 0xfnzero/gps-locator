package com.gpsupdater.app.sync

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CloudCipher {
    private val secureRandom = SecureRandom()

    fun generateSyncKey(): String {
        val bytes = ByteArray(32).also(secureRandom::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun syncId(syncKey: String): String = sha256("sync:$syncKey")

    fun authorization(syncKey: String): String = sha256("auth:$syncKey")

    fun encrypt(plainText: String, syncKey: String): String {
        require(syncKey.length >= MIN_SYNC_KEY_LENGTH)
        val salt = ByteArray(16).also(secureRandom::nextBytes)
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(syncKey, salt), GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("version", 1)
            .put("salt", encode(salt))
            .put("nonce", encode(nonce))
            .put("ciphertext", encode(encrypted))
            .toString()
    }

    fun decrypt(payload: String, syncKey: String): String {
        val json = JSONObject(payload)
        require(json.getInt("version") == 1)
        val salt = decode(json.getString("salt"))
        val nonce = decode(json.getString("nonce"))
        val encrypted = decode(json.getString("ciphertext"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(syncKey, salt), GCMParameterSpec(128, nonce))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun deriveKey(syncKey: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(syncKey.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private const val PBKDF2_ITERATIONS = 120_000
    private const val MIN_SYNC_KEY_LENGTH = 20
}
