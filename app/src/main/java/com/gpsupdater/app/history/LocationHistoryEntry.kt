package com.gpsupdater.app.history

import com.gpsupdater.app.model.Coordinates

enum class HistoryMode {
    STATIC,
    ROUTE,
}

data class LocationHistoryEntry(
    val id: String,
    val name: String,
    val coordinates: Coordinates,
    val mode: HistoryMode,
    val routeId: String?,
    val createdAt: Long,
)
