package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import java.util.UUID

data class RouteWaypoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val coordinates: Coordinates,
    val stopDurationSeconds: Long = 0,
) {
    init {
        require(name.isNotBlank())
        require(stopDurationSeconds in 0..MAX_STOP_DURATION_SECONDS)
    }

    companion object {
        private const val MAX_STOP_DURATION_SECONDS = 86_400L
    }
}

data class SimulationRoute(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val waypoints: List<RouteWaypoint>,
    val speedKph: Double,
    val loop: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
) {
    init {
        require(name.isNotBlank())
        require(waypoints.size >= 2)
        require(speedKph.isFinite() && speedKph in MIN_SPEED_KPH..MAX_SPEED_KPH)
        require(waypoints.map { it.coordinates }.distinct().size >= 2)
    }

    companion object {
        const val MIN_SPEED_KPH = 0.5
        const val MAX_SPEED_KPH = 500.0
    }
}
