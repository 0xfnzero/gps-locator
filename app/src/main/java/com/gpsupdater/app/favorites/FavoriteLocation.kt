package com.gpsupdater.app.favorites

import com.gpsupdater.app.model.Coordinates

data class FavoriteLocation(
    val id: String,
    val name: String,
    val group: String,
    val coordinates: Coordinates,
    val createdAt: Long,
)
