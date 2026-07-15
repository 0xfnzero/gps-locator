package com.gpsupdater.app.geocoding

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.gpsupdater.app.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NominatimGeocoder(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : GeocodingService, AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, List<GeocodingResult>>()
    private var lastRequestStartedAt = 0L

    override fun search(
        country: CountryOption,
        city: String,
        district: String,
        languageTag: String,
        callback: (Result<List<GeocodingResult>>) -> Unit,
    ) {
        searchQuery(
            country = country,
            queryParts = listOf(district, city),
            languageTag = languageTag,
            callback = callback,
        )
    }

    override fun suggestCities(
        country: CountryOption,
        cityQuery: String,
        languageTag: String,
        callback: (Result<List<GeocodingResult>>) -> Unit,
    ) {
        searchQuery(
            country = country,
            queryParts = listOf(cityQuery),
            languageTag = languageTag,
            resultLimit = SUGGESTION_LIMIT,
            callback = callback,
        )
    }

    override fun suggestDistricts(
        country: CountryOption,
        city: String,
        districtQuery: String,
        languageTag: String,
        callback: (Result<List<GeocodingResult>>) -> Unit,
    ) {
        searchQuery(
            country = country,
            queryParts = listOf(districtQuery, city),
            languageTag = languageTag,
            resultLimit = SUGGESTION_LIMIT,
            callback = callback,
        )
    }

    private fun searchQuery(
        country: CountryOption,
        queryParts: List<String>,
        languageTag: String,
        resultLimit: Int = RESULT_LIMIT,
        callback: (Result<List<GeocodingResult>>) -> Unit,
    ) {
        val query = (queryParts + country.name)
            .map(String::trim)
            .filter { it.isNotBlank() }
            .joinToString(", ")
        val cacheKey = "$languageTag|${country.code}|$resultLimit|${query.lowercase()}"
        cache[cacheKey]?.let {
            callback(Result.success(it))
            return
        }

        executor.execute {
            val result = runCatching {
                waitForRateLimit()
                request(country.code, query, languageTag, resultLimit)
            }
            result.getOrNull()?.let { cache[cacheKey] = it }
            mainHandler.post { callback(result) }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun request(
        countryCode: String,
        query: String,
        languageTag: String,
        resultLimit: Int,
    ): List<GeocodingResult> {
        val uri = Uri.parse(BuildConfig.GEOCODING_BASE_URL)
            .buildUpon()
            .appendPath("search")
            .appendQueryParameter("format", "jsonv2")
            .appendQueryParameter("addressdetails", "1")
            .appendQueryParameter("limit", resultLimit.toString())
            .appendQueryParameter("countrycodes", countryCode.lowercase())
            .appendQueryParameter("q", query)
            .build()
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty(
                "User-Agent",
                "GpsUpdater/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID})",
            )
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", "$languageTag,en;q=0.7")

            if (connection.responseCode !in 200..299) {
                throw IOException("Geocoder returned HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return GeocodingResponseParser.parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun waitForRateLimit() {
        val elapsed = System.currentTimeMillis() - lastRequestStartedAt
        if (elapsed < MIN_REQUEST_INTERVAL_MS) {
            Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed)
        }
        lastRequestStartedAt = System.currentTimeMillis()
    }

    companion object {
        private const val RESULT_LIMIT = 12
        private const val SUGGESTION_LIMIT = 8
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MIN_REQUEST_INTERVAL_MS = 1_000L
    }
}
