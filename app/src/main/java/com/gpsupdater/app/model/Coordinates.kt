package com.gpsupdater.app.model

import java.util.Locale

data class Coordinates(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude.isFinite() && latitude in LATITUDE_RANGE)
        require(longitude.isFinite() && longitude in LONGITUDE_RANGE)
    }

    fun formattedLatitude(): String = String.format(Locale.US, "%.6f", latitude)

    fun formattedLongitude(): String = String.format(Locale.US, "%.6f", longitude)

    companion object {
        private val LATITUDE_RANGE = -90.0..90.0
        private val LONGITUDE_RANGE = -180.0..180.0

        val DEFAULT = Coordinates(39.904200, 116.407400)

        fun parse(latitude: String, longitude: String): Coordinates? {
            val parsedLatitude = latitude.trim().toDoubleOrNull() ?: return null
            val parsedLongitude = longitude.trim().toDoubleOrNull() ?: return null
            if (!parsedLatitude.isFinite() || parsedLatitude !in LATITUDE_RANGE) return null
            if (!parsedLongitude.isFinite() || parsedLongitude !in LONGITUDE_RANGE) return null
            return Coordinates(parsedLatitude, parsedLongitude)
        }
    }
}
