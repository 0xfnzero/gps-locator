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
    private val freeFeatures = setOf(
        ProductFeature.BASIC_LOCATION,
        ProductFeature.REGION_SEARCH,
        ProductFeature.FAVORITES,
    )
    private val proFeatures = freeFeatures + setOf(
        ProductFeature.FAVORITE_GROUPS,
        ProductFeature.LOCATION_HISTORY,
        ProductFeature.ROUTE_SIMULATION,
        ProductFeature.ROUTE_LOOP,
        ProductFeature.FILE_IMPORT_EXPORT,
        ProductFeature.JOYSTICK,
        ProductFeature.CLOUD_SYNC,
    )

    fun canUse(tier: ProductTier, feature: ProductFeature): Boolean = when (tier) {
        ProductTier.FREE -> feature in freeFeatures
        ProductTier.PRO -> feature in proFeatures
        ProductTier.ENTERPRISE -> true
    }

    fun favoriteLimit(tier: ProductTier): Int = when (tier) {
        ProductTier.FREE -> 5
        ProductTier.PRO, ProductTier.ENTERPRISE -> Int.MAX_VALUE
    }
}
