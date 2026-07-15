package com.gpsupdater.app.geocoding

import com.gpsupdater.app.model.Coordinates

data class GeocodingResult(
    val name: String,
    val displayName: String,
    val coordinates: Coordinates,
    val zoomLevel: Double,
) {
    override fun toString(): String = name
}
