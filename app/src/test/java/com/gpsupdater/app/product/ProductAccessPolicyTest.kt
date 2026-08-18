package com.gpsupdater.app.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductAccessPolicyTest {
    @Test
    fun everyTier_hasFullAccess() {
        ProductTier.entries.forEach { tier ->
            ProductFeature.entries.forEach { feature ->
                assertTrue("$feature must be available to $tier", ProductAccessPolicy.canUse(tier, feature))
            }
            assertEquals(Int.MAX_VALUE, ProductAccessPolicy.favoriteLimit(tier))
        }
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
