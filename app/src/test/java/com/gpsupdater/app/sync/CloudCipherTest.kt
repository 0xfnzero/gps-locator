package com.gpsupdater.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudCipherTest {
    @Test
    fun encryptDecrypt_roundTripsWithoutExposingPlainText() {
        val key = CloudCipher.generateSyncKey()
        val plainText = "{\"favorite\":\"Shanghai\"}"

        val encrypted = CloudCipher.encrypt(plainText, key)

        assertNotEquals(plainText, encrypted)
        assertEquals(plainText, CloudCipher.decrypt(encrypted, key))
    }

    @Test
    fun decrypt_rejectsWrongSyncKey() {
        val encrypted = CloudCipher.encrypt("secret", CloudCipher.generateSyncKey())

        assertThrows(Exception::class.java) {
            CloudCipher.decrypt(encrypted, CloudCipher.generateSyncKey())
        }
    }

    @Test
    fun identifiers_areStableAndPurposeSeparated() {
        val key = CloudCipher.generateSyncKey()

        assertEquals(CloudCipher.syncId(key), CloudCipher.syncId(key))
        assertNotEquals(CloudCipher.syncId(key), CloudCipher.authorization(key))
    }
}
