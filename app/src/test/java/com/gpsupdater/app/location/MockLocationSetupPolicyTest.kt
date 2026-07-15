package com.gpsupdater.app.location

import org.junit.Assert.assertEquals
import org.junit.Test

class MockLocationSetupPolicyTest {
    @Test
    fun setupProgressesFromDeveloperOptionsToMockAppSelection() {
        assertEquals(
            MockLocationSetupStep.ENABLE_DEVELOPER_OPTIONS,
            MockLocationSetupPolicy.resolve(
                developerOptionsEnabled = false,
                mockLocationEnabled = false,
            ),
        )
        assertEquals(
            MockLocationSetupStep.SELECT_MOCK_LOCATION_APP,
            MockLocationSetupPolicy.resolve(
                developerOptionsEnabled = true,
                mockLocationEnabled = false,
            ),
        )
        assertEquals(
            MockLocationSetupStep.READY,
            MockLocationSetupPolicy.resolve(
                developerOptionsEnabled = true,
                mockLocationEnabled = true,
            ),
        )
    }
}
