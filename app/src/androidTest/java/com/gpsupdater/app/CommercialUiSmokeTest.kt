package com.gpsupdater.app

import android.widget.AutoCompleteTextView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.routes.RouteRepository
import com.gpsupdater.app.routes.RouteWaypoint
import com.gpsupdater.app.routes.SimulationRoute
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommercialUiSmokeTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun seedRoute() {
        clearEnterpriseEntitlement()
        RouteRepository(InstrumentationRegistry.getInstrumentation().targetContext).save(
            SimulationRoute(
                id = "commercial-ui-smoke",
                name = "UI smoke route",
                waypoints = listOf(
                    RouteWaypoint(name = "Start", coordinates = Coordinates(31.2304, 121.4737)),
                    RouteWaypoint(name = "Finish", coordinates = Coordinates(31.2314, 121.4737)),
                ),
                speedKph = 30.0,
                loop = false,
                createdAt = Long.MAX_VALUE,
            ),
        )
    }

    @After
    fun clearEnterpriseFixture() {
        clearEnterpriseEntitlement()
    }

    private fun clearEnterpriseEntitlement() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("product_entitlement", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun savedRouteShowsAllRouteActions() {
        onView(withId(R.id.routeButton)).perform(click())
        onView(withId(R.id.savedRoutesButton)).perform(click())
        onView(withText(containsString("UI smoke route"))).perform(click())

        listOf(
            R.string.start_route,
            R.string.load_route,
            R.string.export_gpx,
            R.string.export_kml,
            R.string.delete_route,
        ).forEach { label ->
            onView(withText(label)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun routeEditorIsVisibleFromPrimaryToolbar() {
        onView(withId(R.id.routeButton)).check(matches(isDisplayed())).perform(click())

        onView(withId(R.id.routeNameInput)).check(matches(isDisplayed()))
        onView(withId(R.id.sampleRouteButton)).check(matches(isDisplayed()))
        onView(withId(R.id.addWaypointButton)).check(matches(isDisplayed()))
        onView(withId(R.id.saveStartButton)).check(matches(isDisplayed()))
    }

    @Test
    fun sampleRouteCreatesImmediateRoutePreview() {
        onView(withId(R.id.routeButton)).perform(click())
        onView(withId(R.id.sampleRouteButton)).perform(click())

        onView(withId(R.id.routeNameInput)).check(matches(withText(R.string.sample_route_name)))
        onView(withId(R.id.waypointCountText)).check(matches(withText(containsString("4"))))
    }

    @Test
    fun regionPickerExposesGlobalCountryCityAndDistrictControls() {
        onView(withId(R.id.regionSearchButton)).perform(click())

        onView(withId(R.id.countryInput)).check { view, error ->
            if (error != null) throw error
            assertTrue((view as AutoCompleteTextView).adapter.count > 200)
        }
        onView(withId(R.id.cityInput)).check(matches(isDisplayed()))
        onView(withId(R.id.districtInput)).check(matches(isDisplayed()))
        onView(withId(R.id.searchButton)).check(matches(isDisplayed()))
    }

    @Test
    fun proToolDialogsOpenFromSettings() {
        openSettingsItem(R.string.joystick)
        onView(withId(R.id.northButton)).check(matches(isDisplayed()))
        onView(withId(R.id.eastButton)).check(matches(isDisplayed()))
        onView(withId(R.id.southButton)).check(matches(isDisplayed()))
        onView(withId(R.id.westButton)).check(matches(isDisplayed()))
        pressBack()

        openSettingsItem(R.string.history)
        onView(withText(R.string.history)).check(matches(isDisplayed()))
        pressBack()

        openSettingsItem(R.string.cloud_sync)
        onView(withId(R.id.endpointInput)).check(matches(isDisplayed()))
        onView(withId(R.id.syncKeyInput)).check(matches(isDisplayed()))
        onView(withId(R.id.backupButton)).check(matches(isDisplayed()))
        onView(withId(R.id.restoreButton)).check(matches(isDisplayed()))
    }

    @Test
    fun teamTemplatesExposeImportAndExportActions() {
        openSettingsItem(R.string.team_templates)

        onView(withText(R.string.import_team_templates)).check(matches(isDisplayed()))
        onView(withText(R.string.export_team_templates)).check(matches(isDisplayed())).perform(click())
        onView(withText(containsString("UI smoke route")))
            .check(matches(isDisplayed()))
            .check(matches(isChecked()))
    }

    @Test
    fun enterpriseLicenseEntryExplainsHowToActivate() {
        openSettingsItem(R.string.enterprise_license)

        val message = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.enterprise_license_missing_message)
        onView(withText(containsString(message))).check(matches(isDisplayed()))
        onView(withText(R.string.copy_device_id)).check(matches(isDisplayed()))
        onView(withText(R.string.import_license)).check(matches(isDisplayed()))
    }

    @Test
    fun enterpriseLicenseEntryShowsOrganizationExpiryAndDeviceBinding() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("product_entitlement", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("verified_tier", "ENTERPRISE")
            .putString("enterprise_organization", "Fleet QA")
            .putLong("enterprise_expires_at", 4_102_444_800_000)
            .putString("enterprise_device_id", "a1b2c3d4e5f60718")
            .commit()

        openSettingsItem(R.string.enterprise_license)

        onView(withText(containsString("Fleet QA"))).check(matches(isDisplayed()))
        onView(withText(containsString("a1b2c3d4e5f60718"))).check(matches(isDisplayed()))
        onView(withText(R.string.replace_license)).check(matches(isDisplayed()))
    }

    @Test
    fun privacyEntryExplainsLocalNetworkBillingAndEnterpriseDataHandling() {
        openSettingsItem(R.string.privacy_and_data)

        onView(withText(R.string.privacy_and_data)).check(matches(isDisplayed()))
        onView(withText(R.string.privacy_and_data_summary)).check(matches(isDisplayed()))
        onView(withText(R.string.close)).check(matches(isDisplayed()))
        onView(withText(R.string.clear_local_test_data)).check(matches(isDisplayed())).perform(click())
        onView(withText(R.string.clear_local_test_data_title)).check(matches(isDisplayed()))
        onView(withText(R.string.clear_local_test_data_message)).check(matches(isDisplayed()))
        pressBack()
        if (BuildConfig.PRIVACY_POLICY_URL.isBlank()) {
            onView(withText(R.string.open_privacy_policy)).check(doesNotExist())
        } else {
            onView(withText(R.string.open_privacy_policy)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun aboutEntryShowsVersionEditionAndConfiguredSupportState() {
        openSettingsItem(R.string.about_and_support)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        onView(withText(R.string.about_and_support)).check(matches(isDisplayed()))
        onView(
            withText(
                containsString(context.getString(R.string.about_version, BuildConfig.VERSION_NAME)),
            ),
        )
            .check(matches(isDisplayed()))
        onView(
            withText(
                containsString(
                    context.getString(
                        R.string.about_edition,
                        context.getString(R.string.edition_open_source),
                    ),
                ),
            ),
        ).check(matches(isDisplayed()))
        if (BuildConfig.SUPPORT_EMAIL.isBlank()) {
            onView(withText(containsString(context.getString(R.string.support_contact_unavailable))))
                .check(matches(isDisplayed()))
            onView(withText(R.string.contact_support)).check(doesNotExist())
        } else {
            onView(
                withText(
                    containsString(
                        context.getString(R.string.support_contact, BuildConfig.SUPPORT_EMAIL),
                    ),
                ),
            )
                .check(matches(isDisplayed()))
            onView(withText(R.string.contact_support)).check(matches(isDisplayed()))
        }
    }

    private fun openSettingsItem(label: Int) {
        onView(withId(R.id.developerSettingsButton)).perform(click())
        val text = InstrumentationRegistry.getInstrumentation().targetContext.getString(label)
        onData(equalTo(text)).inRoot(isDialog()).perform(click())
    }
}
