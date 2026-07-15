package com.gpsupdater.app.geocoding

interface GeocodingService {
    fun search(
        country: CountryOption,
        city: String,
        district: String,
        languageTag: String,
        callback: (Result<List<GeocodingResult>>) -> Unit,
    )

    fun suggestCities(
        country: CountryOption,
        cityQuery: String,
        languageTag: String,
        callback: (Result<List<GeocodingResult>>) -> Unit,
    )

    fun suggestDistricts(
        country: CountryOption,
        city: String,
        districtQuery: String,
        languageTag: String,
        callback: (Result<List<GeocodingResult>>) -> Unit,
    )
}
