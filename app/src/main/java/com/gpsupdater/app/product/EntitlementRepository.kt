package com.gpsupdater.app.product

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.gpsupdater.app.BuildConfig

data class EnterpriseEntitlementStatus(
    val organization: String,
    val expiresAt: Long,
    val deviceId: String? = null,
) {
    fun isActive(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt == 0L || expiresAt > now
}

class EntitlementRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun currentTier(): ProductTier {
        if (BuildConfig.DEBUG) return ProductTier.PRO
        val activeTier = storedTier(KEY_TIER, ProductTier.FREE)
        if (activeTier != ProductTier.ENTERPRISE) return activeTier
        val expiresAt = preferences.getLong(KEY_ENTERPRISE_EXPIRES_AT, 0)
        if (expiresAt == 0L || expiresAt > System.currentTimeMillis()) return activeTier
        return storedTier(KEY_ENTERPRISE_FALLBACK_TIER, ProductTier.FREE)
    }

    fun canUse(feature: ProductFeature): Boolean =
        ProductAccessPolicy.canUse(currentTier(), feature)

    fun favoriteLimit(): Int = ProductAccessPolicy.favoriteLimit(currentTier())

    @SuppressLint("HardwareIds")
    fun currentDeviceId(): String = Settings.Secure.getString(
        applicationContext.contentResolver,
        Settings.Secure.ANDROID_ID,
    ).orEmpty()

    fun enterpriseStatus(): EnterpriseEntitlementStatus? {
        if (storedTier(KEY_TIER, ProductTier.FREE) != ProductTier.ENTERPRISE) return null
        val organization = preferences.getString(KEY_ENTERPRISE_ORGANIZATION, "")
            .orEmpty()
            .trim()
        if (organization.isBlank()) return null
        return EnterpriseEntitlementStatus(
            organization = organization,
            expiresAt = preferences.getLong(KEY_ENTERPRISE_EXPIRES_AT, 0L),
            deviceId = preferences.getString(KEY_ENTERPRISE_DEVICE_ID, null)
                ?.takeIf { it.isNotBlank() },
        )
    }

    internal fun updateVerifiedProOwnership(owned: Boolean) {
        val updated = EntitlementOwnershipPolicy.withVerifiedProOwnership(
            storedEntitlementState(),
            owned,
        )
        preferences.edit()
            .putString(KEY_TIER, updated.tier.name)
            .putString(KEY_ENTERPRISE_FALLBACK_TIER, updated.enterpriseFallbackTier.name)
            .apply()
    }

    fun activateEnterpriseLicense(document: String): Result<EnterpriseLicense> =
        EnterpriseLicenseVerifier.verify(
            document = document,
            publicKeyBase64 = BuildConfig.ENTERPRISE_LICENSE_PUBLIC_KEY,
            deviceId = currentDeviceId(),
        )
            .onSuccess { license ->
                val fallback = EntitlementOwnershipPolicy.enterpriseFallbackForActivation(
                    storedEntitlementState(),
                )
                preferences.edit()
                    .putString(KEY_TIER, ProductTier.ENTERPRISE.name)
                    .putString(KEY_ENTERPRISE_FALLBACK_TIER, fallback.name)
                    .putLong(KEY_ENTERPRISE_EXPIRES_AT, license.expiresAt)
                    .putString(KEY_ENTERPRISE_ORGANIZATION, license.organization)
                    .putString(KEY_ENTERPRISE_DEVICE_ID, license.deviceId)
                    .apply()
            }

    private fun storedTier(key: String, default: ProductTier): ProductTier = runCatching {
        ProductTier.valueOf(preferences.getString(key, default.name).orEmpty())
    }.getOrDefault(default)

    private fun storedEntitlementState(): StoredEntitlementState = StoredEntitlementState(
        tier = storedTier(KEY_TIER, ProductTier.FREE),
        enterpriseFallbackTier = storedTier(
            KEY_ENTERPRISE_FALLBACK_TIER,
            ProductTier.FREE,
        ),
    )

    companion object {
        private const val FILE_NAME = "product_entitlement"
        private const val KEY_TIER = "verified_tier"
        private const val KEY_ENTERPRISE_FALLBACK_TIER = "enterprise_fallback_tier"
        private const val KEY_ENTERPRISE_EXPIRES_AT = "enterprise_expires_at"
        private const val KEY_ENTERPRISE_ORGANIZATION = "enterprise_organization"
        private const val KEY_ENTERPRISE_DEVICE_ID = "enterprise_device_id"
    }
}
