package com.gpsupdater.app.product

enum class ProductTier {
    FREE,
    PRO,
    ENTERPRISE,
}

enum class ProductFeature {
    BASIC_LOCATION,
    REGION_SEARCH,
    FAVORITES,
    FAVORITE_GROUPS,
    LOCATION_HISTORY,
    ROUTE_SIMULATION,
    ROUTE_LOOP,
    FILE_IMPORT_EXPORT,
    JOYSTICK,
    CLOUD_SYNC,
    TEAM_SCENARIOS,
    DEVICE_FLEET,
    TEST_REPORTS,
}

data class StoredEntitlementState(
    val tier: ProductTier,
    val enterpriseFallbackTier: ProductTier = ProductTier.FREE,
)

object EntitlementOwnershipPolicy {
    fun withVerifiedProOwnership(
        state: StoredEntitlementState,
        owned: Boolean,
    ): StoredEntitlementState {
        val verifiedTier = if (owned) ProductTier.PRO else ProductTier.FREE
        return if (state.tier == ProductTier.ENTERPRISE) {
            state.copy(enterpriseFallbackTier = verifiedTier)
        } else {
            state.copy(tier = verifiedTier)
        }
    }

    fun enterpriseFallbackForActivation(state: StoredEntitlementState): ProductTier = when (state.tier) {
        ProductTier.FREE -> ProductTier.FREE
        ProductTier.PRO -> ProductTier.PRO
        ProductTier.ENTERPRISE -> state.enterpriseFallbackTier.takeIf {
            it != ProductTier.ENTERPRISE
        } ?: ProductTier.FREE
    }
}

object ProductAccessPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun canUse(tier: ProductTier, feature: ProductFeature): Boolean = true

    @Suppress("UNUSED_PARAMETER")
    fun favoriteLimit(tier: ProductTier): Int = Int.MAX_VALUE
}
