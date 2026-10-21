package com.gpsupdater.app

import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gpsupdater.app.geocoding.CountryOption
import com.gpsupdater.app.geocoding.GeocodingResult
import com.gpsupdater.app.geocoding.GeocodingService
import com.gpsupdater.app.geocoding.PlaceKeywordSearchDialog
import com.gpsupdater.app.model.Coordinates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.hasToString

@RunWith(AndroidJUnit4::class)
class RegionSearchAutocompleteTest {
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launchActivity() {
        setApplicationLanguage("en")
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeActivity() {
        scenario.close()
        setApplicationLanguage("")
    }

    @Test
    fun citySuggestionsAppearAndCanBeSelected() {
        scenario.onActivity { activity ->
            PlaceKeywordSearchDialog(activity, FakeGeocodingService(), {}).show()
        }

        onView(withId(R.id.cityInput)).perform(click(), typeText("Ber"))
        Thread.sleep(700)
        onView(withId(R.id.cityInput)).check { view, error ->
            if (error != null) throw error
            assertEquals(1, (view as AutoCompleteTextView).adapter.count)
        }
        onData(hasToString("Berlin")).inRoot(isPlatformPopup()).perform(click())
        onView(withId(R.id.cityInput)).check(matches(withText("Berlin")))
        onView(withId(R.id.districtInput)).check(matches(isDisplayed()))
    }

    private fun setApplicationLanguage(languageTag: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                    android.os.LocaleList.forLanguageTags(languageTag)
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
            }
        }
    }

    private class FakeGeocodingService : GeocodingService {
        override fun search(
            country: CountryOption,
            city: String,
            district: String,
            languageTag: String,
            callback: (Result<List<GeocodingResult>>) -> Unit,
        ) = callback(Result.success(emptyList()))

        override fun suggestCities(
            country: CountryOption,
            cityQuery: String,
            languageTag: String,
            callback: (Result<List<GeocodingResult>>) -> Unit,
        ) = callback(
            Result.success(
                listOf(
                    GeocodingResult(
                        name = "Berlin",
                        displayName = "Berlin, Germany",
                        coordinates = Coordinates(52.520008, 13.404954),
                        zoomLevel = 12.0,
                    ),
                ),
            ),
        )

        override fun suggestDistricts(
            country: CountryOption,
            city: String,
            districtQuery: String,
            languageTag: String,
            callback: (Result<List<GeocodingResult>>) -> Unit,
        ) = callback(Result.success(emptyList()))
    }
}
