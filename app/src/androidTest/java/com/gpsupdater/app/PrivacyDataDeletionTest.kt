package com.gpsupdater.app

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gpsupdater.app.enterprise.EnterpriseReportGenerator
import com.gpsupdater.app.enterprise.SimulationOutcome
import com.gpsupdater.app.enterprise.SimulationRunRepository
import com.gpsupdater.app.favorites.FavoriteLocationRepository
import com.gpsupdater.app.history.HistoryMode
import com.gpsupdater.app.history.LocationHistoryRepository
import com.gpsupdater.app.location.MockLocationPreferences
import com.gpsupdater.app.location.MockLocationService
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.privacy.LocalTestDataCleaner
import com.gpsupdater.app.routes.RouteRepository
import com.gpsupdater.app.routes.RouteWaypoint
import com.gpsupdater.app.routes.SimulationRoute
import com.gpsupdater.app.sync.CloudSyncConfiguration
import com.gpsupdater.app.sync.CloudSyncSettings
import com.gpsupdater.app.sync.SyncDeletionRepository
import com.gpsupdater.app.sync.SyncedEntityType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.equalTo

@RunWith(AndroidJUnit4::class)
class PrivacyDataDeletionTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanFixtures() {
        if (MockLocationPreferences.isActive(context)) {
            context.startService(MockLocationService.stopIntent(context))
            runCatching { waitUntil { !MockLocationPreferences.isActive(context) } }
        }
        LocalTestDataCleaner(context).clear()
        context.getSharedPreferences("product_entitlement", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sync_deletions", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun clearRemovesLocalLocationDataButPreservesEntitlementsAndSyncConfiguration() {
        LocalTestDataCleaner(context).clear()
        val coordinates = Coordinates(35.6762, 139.6503)
        FavoriteLocationRepository(context).save("Tokyo weather", "Localization", coordinates)
        RouteRepository(context).save(
            SimulationRoute(
                id = "privacy-route",
                name = "Privacy fixture",
                waypoints = listOf(
                    RouteWaypoint(name = "Start", coordinates = coordinates),
                    RouteWaypoint(name = "Finish", coordinates = Coordinates(35.6862, 139.6603)),
                ),
                speedKph = 30.0,
                loop = false,
            ),
        )
        LocationHistoryRepository(context).record(
            "Tokyo weather",
            coordinates,
            HistoryMode.STATIC,
        )
        val runs = SimulationRunRepository(context)
        runs.begin("Tokyo weather", HistoryMode.STATIC, coordinates, null)
        runs.finishActive(SimulationOutcome.COMPLETED, coordinates)
        MockLocationPreferences.saveCoordinates(context, coordinates)
        MockLocationPreferences.setRouteActive(context, "privacy-route")
        val report = EnterpriseReportGenerator(context).writeLatest()

        val entitlements = context.getSharedPreferences("product_entitlement", Context.MODE_PRIVATE)
        entitlements.edit()
            .putString("verified_tier", "ENTERPRISE")
            .putString("enterprise_organization", "Privacy QA")
            .commit()
        val sync = CloudSyncSettings(context)
        val syncConfiguration = CloudSyncConfiguration(
            endpoint = "https://sync.example.test",
            syncKey = "privacy-test-sync-key-1234567890",
        )
        sync.save(syncConfiguration)

        assertTrue(report.exists())
        LocalTestDataCleaner(context).clear()

        assertTrue(FavoriteLocationRepository(context).all().isEmpty())
        assertTrue(RouteRepository(context).all().isEmpty())
        assertTrue(LocationHistoryRepository(context).all().isEmpty())
        assertTrue(SimulationRunRepository(context).all().isEmpty())
        assertEquals(Coordinates.DEFAULT, MockLocationPreferences.coordinates(context))
        assertFalse(MockLocationPreferences.isActive(context))
        assertNull(MockLocationPreferences.activeRouteId(context))
        assertFalse(report.exists())
        val deletionTypes = SyncDeletionRepository(context).all().map { it.type }.toSet()
        assertEquals(
            setOf(SyncedEntityType.FAVORITE, SyncedEntityType.ROUTE, SyncedEntityType.HISTORY),
            deletionTypes,
        )
        assertEquals("ENTERPRISE", entitlements.getString("verified_tier", null))
        assertEquals("Privacy QA", entitlements.getString("enterprise_organization", null))
        assertEquals(syncConfiguration, sync.load())
    }

    @Test
    fun privacyActionStopsAnActiveSimulationBeforeDeletingItsFinalReport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION",
        ).close()
        instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} android:mock_location allow",
        ).close()
        val favoriteRepository = FavoriteLocationRepository(context)
        val coordinates = Coordinates(52.520008, 13.404954)
        favoriteRepository.save("Berlin login", "Login", coordinates)
        ActivityScenario.launch(MainActivity::class.java).use {
            it.onActivity { activity ->
                ContextCompat.startForegroundService(
                    activity,
                    MockLocationService.startIntent(activity, coordinates, "Berlin login"),
                )
            }
            waitUntil { MockLocationPreferences.isActive(context) }
            onView(withId(R.id.developerSettingsButton)).perform(click())
            val privacyLabel = context.getString(R.string.privacy_and_data)
            onData(equalTo(privacyLabel)).inRoot(isDialog()).perform(click())
            onView(withText(R.string.clear_local_test_data)).perform(click())
            onView(withText(R.string.delete)).perform(click())
            waitUntil {
                !MockLocationPreferences.isActive(context) &&
                    favoriteRepository.all().isEmpty() &&
                    SimulationRunRepository(context).all().isEmpty()
            }
        }

        val reportsDirectory = context.getExternalFilesDir(null)?.resolve("reports")
        assertFalse(MockLocationPreferences.isActive(context))
        assertTrue(favoriteRepository.all().isEmpty())
        assertTrue(SimulationRunRepository(context).all().isEmpty())
        assertFalse(reportsDirectory?.exists() == true)
    }

    private fun waitUntil(timeoutMilliseconds: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("Condition was not met within $timeoutMilliseconds ms", condition())
    }
}
