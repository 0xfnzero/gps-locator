package com.gpsupdater.app

import android.os.SystemClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gpsupdater.app.geocoding.*
import org.hamcrest.Matchers.hasToString
import org.hamcrest.Matchers.not
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RegionDirectoryUiTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val selected = AtomicReference<GeocodingResult>()

    @Before fun launch() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                    android.os.LocaleList.forLanguageTags("en")
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            }
        }
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("region_directory", 0).edit().clear().commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { RegionSearchDialog(it, NoNetworkGeocoder()) { result -> selected.set(result) }.show() }
    }

    @After fun close() {
        scenario.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                    android.os.LocaleList.getEmptyLocaleList()
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            }
        }
    }

    @Test fun selectsCityWithoutTypingAnAddressOrUsingTheNetwork() {
        select(R.id.countryInput, "United States")
        awaitEnabled(R.id.stateInput)
        select(R.id.stateInput, "California")
        select(R.id.districtInput, "Los Angeles County")
        select(R.id.cityInput, "Los Angeles")
        onView(withId(R.id.cityInput)).check(matches(withText("Los Angeles")))
        onView(withId(R.id.searchButton)).perform(scrollTo(), click())
        assertEquals(34.05223, selected.get().coordinates.latitude, 0.00001)
        assertEquals(-118.24368, selected.get().coordinates.longitude, 0.00001)
    }

    @Test fun reopensLastConfirmedLocation() {
        select(R.id.countryInput, "United States")
        awaitEnabled(R.id.stateInput)
        select(R.id.stateInput, "California")
        select(R.id.districtInput, "Los Angeles County")
        select(R.id.cityInput, "Los Angeles")
        onView(withId(R.id.searchButton)).perform(scrollTo(), click())
        scenario.onActivity { RegionSearchDialog(it, NoNetworkGeocoder()) {}.show() }
        awaitEnabled(R.id.stateInput)
        onView(withId(R.id.stateInput)).check(matches(withText("California")))
        onView(withId(R.id.districtInput)).check(matches(withText("Los Angeles County")))
        onView(withId(R.id.cityInput)).check(matches(withText("Los Angeles")))
    }

    @Test fun changingCountryClearsDependentSelectionsAndCountryWithoutRegionsStillWorks() {
        select(R.id.countryInput, "United States")
        awaitEnabled(R.id.stateInput)
        select(R.id.stateInput, "California")
        select(R.id.cityInput, "Los Angeles")
        select(R.id.countryInput, "Vatican", "Vatican City")
        SystemClock.sleep(300)
        onView(withId(R.id.stateInput)).check(matches(withText("")))
        onView(withId(R.id.cityInput)).check(matches(withText("")))
        onView(withId(R.id.cityInput)).check(matches(not(isEnabled())))
        onView(withId(R.id.searchButton)).perform(scrollTo(), click())
        assertNotNull(selected.get())
    }

    @Test fun everyBundledCountryCanBeReadAndHasValidCoordinatesWhenPresent() {
        val directory = RegionDirectory(InstrumentationRegistry.getInstrumentation().targetContext)
        val countries = directory.countries()
        assertTrue(countries.size >= 249)
        var cityCount = 0
        countries.forEach { country ->
            directory.regions(country.code).forEach { region ->
                cityCount += region.cities.size
                (region.cities + region.districts + region.place).forEach { place ->
                    place.coordinates?.let {
                        assertTrue(it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0)
                    }
                }
            }
        }
        assertTrue(cityCount > 150_000)
    }

    private fun select(field: Int, filter: String, exact: String = filter) {
        onView(withId(field)).perform(scrollTo(), click())
        onView(withId(R.id.directoryFilter)).perform(replaceText(filter))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        onData(hasToString(exact)).inAdapterView(withId(R.id.directoryList)).perform(click())
    }

    private fun awaitEnabled(id: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            try { onView(withId(id)).check(matches(isEnabled())); return }
            catch (error: AssertionError) {
                if (SystemClock.uptimeMillis() >= deadline) throw error
                SystemClock.sleep(50)
            }
        }
    }

    private class NoNetworkGeocoder : GeocodingService {
        override fun search(country: CountryOption, city: String, district: String, languageTag: String,
            callback: (Result<List<GeocodingResult>>) -> Unit) { error("Offline selection must not geocode") }
        override fun suggestCities(country: CountryOption, cityQuery: String, languageTag: String,
            callback: (Result<List<GeocodingResult>>) -> Unit) { error("Offline selection must not geocode") }
        override fun suggestDistricts(country: CountryOption, city: String, districtQuery: String,
            languageTag: String, callback: (Result<List<GeocodingResult>>) -> Unit) {
            error("Offline selection must not geocode")
        }
    }
}
