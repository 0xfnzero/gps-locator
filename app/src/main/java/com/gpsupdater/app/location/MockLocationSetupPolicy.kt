package com.gpsupdater.app.location

enum class MockLocationSetupStep {
    ENABLE_DEVELOPER_OPTIONS,
    SELECT_MOCK_LOCATION_APP,
    READY,
}

object MockLocationSetupPolicy {
    fun resolve(
        developerOptionsEnabled: Boolean,
        mockLocationEnabled: Boolean,
    ): MockLocationSetupStep = when {
        mockLocationEnabled -> MockLocationSetupStep.READY
        developerOptionsEnabled -> MockLocationSetupStep.SELECT_MOCK_LOCATION_APP
        else -> MockLocationSetupStep.ENABLE_DEVELOPER_OPTIONS
    }
}
