package com.gpsupdater.app

import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageSwitchTest {
    private lateinit var activityScenario: ActivityScenario<MainActivity>

    @Before
    fun resetLanguage() {
        setApplicationLanguage("")
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun restoreSystemLanguage() {
        activityScenario.close()
        setApplicationLanguage("")
    }

    @Test
    fun switchesBetweenJapaneseAndSpanish() {
        onView(withId(R.id.developerSettingsButton)).perform(click())
        onView(withText("Language")).perform(click())
        onView(withText("日本語")).perform(click())
        onView(withText("GPSロケーター")).check(matches(isDisplayed()))

        onView(withId(R.id.developerSettingsButton)).perform(click())
        onView(withText("言語")).perform(click())
        onView(withText("Español")).perform(click())
        onView(withText("Localizador GPS")).check(matches(isDisplayed()))
    }

    @Test
    fun routeActionLabelsStayOnOneLineInJapaneseAndSpanish() {
        for (languageTag in listOf("ja", "es")) {
            activityScenario.close()
            setApplicationLanguage(languageTag)
            activityScenario = ActivityScenario.launch(MainActivity::class.java)

            onView(withId(R.id.routeButton)).perform(click())
            listOf(R.id.addWaypointButton, R.id.undoButton).forEach { buttonId ->
                onView(withId(buttonId)).check { view, error ->
                    if (error != null) throw error
                    assertEquals(1, (view as TextView).lineCount)
                }
            }
            pressBack()
        }
    }

    private fun setApplicationLanguage(languageTag: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageTag),
            )
        }
    }
}
