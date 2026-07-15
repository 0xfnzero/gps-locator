package com.gpsupdater.app.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductAccessPolicyTest {
    @Test
    fun freeTier_keepsCoreLocationFeatures() {
        assertTrue(ProductAccessPolicy.canUse(ProductTier.FREE, ProductFeature.BASIC_LOCATION))
        assertTrue(ProductAccessPolicy.canUse(ProductTier.FREE, ProductFeature.REGION_SEARCH))
        assertTrue(ProductAccessPolicy.canUse(ProductTier.FREE, ProductFeature.FAVORITES))
        assertEquals(5, ProductAccessPolicy.favoriteLimit(ProductTier.FREE))
    }

    @Test
    fun proFeatures_areUnavailableToFreeAndAvailableToPro() {
        val proFeatures = listOf(
            ProductFeature.FAVORITE_GROUPS,
            ProductFeature.LOCATION_HISTORY,
            ProductFeature.ROUTE_SIMULATION,
            ProductFeature.ROUTE_LOOP,
            ProductFeature.FILE_IMPORT_EXPORT,
            ProductFeature.JOYSTICK,
            ProductFeature.CLOUD_SYNC,
        )

        proFeatures.forEach { feature ->
            assertFalse("$feature must not be available to Free", ProductAccessPolicy.canUse(ProductTier.FREE, feature))
            assertTrue("$feature must be available to Pro", ProductAccessPolicy.canUse(ProductTier.PRO, feature))
        }
        assertEquals(Int.MAX_VALUE, ProductAccessPolicy.favoriteLimit(ProductTier.PRO))
    }

    @Test
    fun enterpriseFeatures_areUnavailableToProAndAvailableToEnterprise() {
        val enterpriseFeatures = listOf(
            ProductFeature.TEAM_SCENARIOS,
            ProductFeature.DEVICE_FLEET,
            ProductFeature.TEST_REPORTS,
        )

        enterpriseFeatures.forEach { feature ->
            assertFalse("$feature must not be available to Pro", ProductAccessPolicy.canUse(ProductTier.PRO, feature))
            assertTrue(
                "$feature must be available to Enterprise",
                ProductAccessPolicy.canUse(ProductTier.ENTERPRISE, feature),
            )
        }
        assertEquals(Int.MAX_VALUE, ProductAccessPolicy.favoriteLimit(ProductTier.ENTERPRISE))
    }

    @Test
    fun verifiedProOwnership_updatesFreeAndProTiers() {
        assertEquals(
            StoredEntitlementState(ProductTier.PRO),
            EntitlementOwnershipPolicy.withVerifiedProOwnership(
                StoredEntitlementState(ProductTier.FREE),
                owned = true,
            ),
        )
        assertEquals(
            StoredEntitlementState(ProductTier.FREE),
            EntitlementOwnershipPolicy.withVerifiedProOwnership(
                StoredEntitlementState(ProductTier.PRO),
                owned = false,
            ),
        )
    }

    @Test
    fun verifiedProOwnership_preservesEnterpriseAndUpdatesFallback() {
        assertEquals(
            StoredEntitlementState(ProductTier.ENTERPRISE, ProductTier.PRO),
            EntitlementOwnershipPolicy.withVerifiedProOwnership(
                StoredEntitlementState(ProductTier.ENTERPRISE, ProductTier.FREE),
                owned = true,
            ),
        )
        assertEquals(
            StoredEntitlementState(ProductTier.ENTERPRISE, ProductTier.FREE),
            EntitlementOwnershipPolicy.withVerifiedProOwnership(
                StoredEntitlementState(ProductTier.ENTERPRISE, ProductTier.PRO),
                owned = false,
            ),
        )
    }

    @Test
    fun enterpriseRenewal_preservesPreviouslyVerifiedProFallback() {
        assertEquals(
            ProductTier.PRO,
            EntitlementOwnershipPolicy.enterpriseFallbackForActivation(
                StoredEntitlementState(ProductTier.ENTERPRISE, ProductTier.PRO),
            ),
        )
        assertEquals(
            ProductTier.PRO,
            EntitlementOwnershipPolicy.enterpriseFallbackForActivation(
                StoredEntitlementState(ProductTier.PRO),
            ),
        )
    }

    @Test
    fun enterpriseStatus_supportsPerpetualActiveAndExpiredLicenses() {
        assertTrue(EnterpriseEntitlementStatus("QA", 0L).isActive(now = 100L))
        assertTrue(EnterpriseEntitlementStatus("QA", 200L).isActive(now = 100L))
        assertFalse(EnterpriseEntitlementStatus("QA", 100L).isActive(now = 100L))
    }
}
