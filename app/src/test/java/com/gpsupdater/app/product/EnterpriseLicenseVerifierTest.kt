package com.gpsupdater.app.product

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class EnterpriseLicenseVerifierTest {
    @Test
    fun verify_acceptsValidSignatureAndRejectsTampering() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val license = EnterpriseLicense("license-1", "QA Team", 4_102_444_800_000)
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(EnterpriseLicenseVerifier.canonicalPayload(license).toByteArray())
        }.sign()
        val document = JSONObject()
            .put("licenseId", license.licenseId)
            .put("organization", license.organization)
            .put("expiresAt", license.expiresAt)
            .put("signature", Base64.getEncoder().encodeToString(signature))
            .toString()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        assertEquals(license, EnterpriseLicenseVerifier.verify(document, publicKey).getOrThrow())
        assertTrue(
            EnterpriseLicenseVerifier.verify(
                JSONObject(document).put("organization", "Other team").toString(),
                publicKey,
            ).isFailure,
        )
    }

    @Test
    fun verify_acceptsDeviceLicenseOnlyOnBoundAndroidDevice() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val license = EnterpriseLicense(
            licenseId = "device-license-1",
            organization = "Device QA",
            expiresAt = 4_102_444_800_000,
            schemaVersion = EnterpriseLicenseVerifier.DEVICE_SCHEMA_VERSION,
            deviceId = "a1b2c3d4e5f60718",
        )
        assertEquals(
            "gps-updater-enterprise-license\n2\ndevice-license-1\nDevice QA\n" +
                "4102444800000\na1b2c3d4e5f60718",
            EnterpriseLicenseVerifier.canonicalPayload(license),
        )
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(EnterpriseLicenseVerifier.canonicalPayload(license).toByteArray())
        }.sign()
        val document = JSONObject()
            .put("schemaVersion", license.schemaVersion)
            .put("licenseId", license.licenseId)
            .put("organization", license.organization)
            .put("expiresAt", license.expiresAt)
            .put("deviceId", license.deviceId)
            .put("signature", Base64.getEncoder().encodeToString(signature))
            .toString()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        assertEquals(
            license,
            EnterpriseLicenseVerifier.verify(
                document = document,
                publicKeyBase64 = publicKey,
                deviceId = "A1B2C3D4E5F60718",
            ).getOrThrow(),
        )
        assertTrue(
            EnterpriseLicenseVerifier.verify(
                document = document,
                publicKeyBase64 = publicKey,
                deviceId = "1111222233334444",
            ).isFailure,
        )
        assertTrue(
            EnterpriseLicenseVerifier.verify(
                document = JSONObject(document).put("deviceId", "1111222233334444").toString(),
                publicKeyBase64 = publicKey,
                deviceId = "1111222233334444",
            ).isFailure,
        )
    }
}
