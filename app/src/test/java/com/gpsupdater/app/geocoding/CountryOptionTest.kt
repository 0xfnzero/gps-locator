package com.gpsupdater.app.geocoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CountryOptionTest {
    @Test
    fun localizedCountries_containsEveryIsoCountryOnce() {
        val countries = CountryOption.localizedCountries(Locale.ENGLISH)

        assertTrue(countries.size > 200)
        assertEquals(countries.size, countries.map { it.code }.toSet().size)
    }

    @Test
    fun localizedCountries_usesRequestedLanguage() {
        val english = CountryOption.localizedCountries(Locale.ENGLISH)
            .first { it.code == "CN" }
        val chinese = CountryOption.localizedCountries(Locale.SIMPLIFIED_CHINESE)
            .first { it.code == "CN" }

        assertEquals("China", english.name)
        assertEquals("中国", chinese.name)
    }

    @Test
    fun localizedCountries_supportsJapaneseAndSpanish() {
        val japanese = CountryOption.localizedCountries(Locale.JAPANESE)
            .first { it.code == "JP" }
        val spanish = CountryOption.localizedCountries(Locale.forLanguageTag("es"))
            .first { it.code == "ES" }

        assertEquals("日本", japanese.name)
        assertEquals("España", spanish.name)
    }
}
