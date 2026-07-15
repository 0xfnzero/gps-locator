package com.gpsupdater.app.geocoding

import com.gpsupdater.app.model.Coordinates
import org.json.JSONArray

internal object GeocodingResponseParser {
    fun parse(body: String): List<GeocodingResult> {
        val json = JSONArray(body)
        val results = mutableListOf<GeocodingResult>()
        val seen = mutableSetOf<String>()
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            val latitude = item.optString("lat").toDoubleOrNull() ?: continue
            val longitude = item.optString("lon").toDoubleOrNull() ?: continue
            val coordinates = runCatching { Coordinates(latitude, longitude) }.getOrNull() ?: continue
            val displayName = item.optString("display_name").trim()
            if (displayName.isBlank()) continue
            val key = "${coordinates.formattedLatitude()},${coordinates.formattedLongitude()}"
            if (!seen.add(key)) continue
            val addressType = item.optString("addresstype", item.optString("type"))
            results += GeocodingResult(
                name = item.optString("name").ifBlank { displayName.substringBefore(',') },
                displayName = displayName,
                coordinates = coordinates,
                zoomLevel = zoomFor(addressType),
            )
        }
        return results
    }

    private fun zoomFor(addressType: String): Double = when (addressType.lowercase()) {
        "country" -> 5.0
        "state", "province", "region" -> 7.0
        "county" -> 9.0
        "city", "town", "municipality" -> 12.0
        "borough", "district", "suburb", "quarter" -> 14.0
        "village", "neighbourhood" -> 15.0
        else -> 16.0
    }
}
