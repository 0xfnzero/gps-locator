package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates

object SampleRouteFactory {
    private const val PREVIEW_RADIUS_METERS = 250.0

    fun createWaypoints(center: Coordinates, names: List<String>): List<RouteWaypoint> {
        require(names.size == 4)
        require(names.all { it.isNotBlank() })
        return listOf(
            RouteWaypoint(name = names[0], coordinates = center),
            RouteWaypoint(
                name = names[1],
                coordinates = GeoMath.destination(center, PREVIEW_RADIUS_METERS, 45.0),
            ),
            RouteWaypoint(
                name = names[2],
                coordinates = GeoMath.destination(center, PREVIEW_RADIUS_METERS, 135.0),
            ),
            RouteWaypoint(name = names[3], coordinates = center),
        )
    }
}
