package com.gpsupdater.app.product

import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class EnterpriseLicense(
    val licenseId: String,
    val organization: String,
    val expiresAt: Long,
    val schemaVersion: Int = EnterpriseLicenseVerifier.LEGACY_SCHEMA_VERSION,
    val deviceId: String? = null,
)

object EnterpriseLicenseVerifier {
    fun verify(
        document: String,
        publicKeyBase64: String,
        now: Long = System.currentTimeMillis(),
        deviceId: String? = null,
    ): Result<EnterpriseLicense> = runCatching {
        require(publicKeyBase64.isNotBlank()) { "Enterprise public key is not configured" }
        val json = JSONObject(document)
        val license = EnterpriseLicense(
            licenseId = json.getString("licenseId").trim().also { require(it.isNotBlank()) },
            organization = json.getString("organization").trim().also { require(it.isNotBlank()) },
            expiresAt = json.optLong("expiresAt", 0),
            schemaVersion = json.optInt("schemaVersion", LEGACY_SCHEMA_VERSION),
            deviceId = json.optString("deviceId").trim().takeIf { it.isNotBlank() },
        )
        require(license.schemaVersion in LEGACY_SCHEMA_VERSION..DEVICE_SCHEMA_VERSION) {
            "Unsupported license schema"
        }
        require(license.schemaVersion == DEVICE_SCHEMA_VERSION || license.deviceId == null) {
            "Legacy licenses cannot be device-bound"
        }
        if (license.schemaVersion == DEVICE_SCHEMA_VERSION) {
            require(license.deviceId?.matches(ANDROID_ID_PATTERN) == true) {
                "Invalid licensed device ID"
            }
        }
        require(license.expiresAt == 0L || license.expiresAt > now) { "License has expired" }
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)),
        )
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initVerify(publicKey)
            update(canonicalPayload(license).toByteArray(Charsets.UTF_8))
        }
        require(signature.verify(Base64.getDecoder().decode(json.getString("signature")))) {
            "Invalid license signature"
        }
        license.deviceId?.let { licensedDeviceId ->
            require(deviceId?.equals(licensedDeviceId, ignoreCase = true) == true) {
                "License is bound to another device"
            }
        }
        license
    }

    fun canonicalPayload(license: EnterpriseLicense): String = when (license.schemaVersion) {
        LEGACY_SCHEMA_VERSION ->
            "${license.licenseId}\n${license.organization}\n${license.expiresAt}"

        DEVICE_SCHEMA_VERSION -> listOf(
            LICENSE_DOMAIN,
            DEVICE_SCHEMA_VERSION,
            license.licenseId,
            license.organization,
            license.expiresAt,
            requireNotNull(license.deviceId).lowercase(),
        ).joinToString("\n")

        else -> error("Unsupported license schema")
    }

    private val ANDROID_ID_PATTERN = Regex("^[a-fA-F0-9]{16}$")
    private const val LICENSE_DOMAIN = "gps-updater-enterprise-license"
    const val LEGACY_SCHEMA_VERSION = 1
    const val DEVICE_SCHEMA_VERSION = 2
}
