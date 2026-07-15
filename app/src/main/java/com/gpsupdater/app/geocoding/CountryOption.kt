package com.gpsupdater.app.geocoding

import java.text.Collator
import java.util.Locale

data class CountryOption(
    val code: String,
    val name: String,
) {
    override fun toString(): String = name

    companion object {
        fun localizedCountries(locale: Locale = Locale.getDefault()): List<CountryOption> {
            val collator = Collator.getInstance(locale)
            return Locale.getISOCountries()
                .map { code -> CountryOption(code, Locale("", code).getDisplayCountry(locale)) }
                .filter { it.name.isNotBlank() }
                .sortedWith { first, second -> collator.compare(first.name, second.name) }
        }
    }
}
