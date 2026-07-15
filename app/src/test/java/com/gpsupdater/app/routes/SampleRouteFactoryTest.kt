package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Test

class SampleRouteFactoryTest {
    @Test
    fun sampleRouteCreatesAValidLoopAroundTheMapCenter() {
        val center = Coordinates(31.2304, 121.4737)
        val names = listOf("Start", "A", "B", "Finish")

        val waypoints = SampleRouteFactory.createWaypoints(center, names)

        assertEquals(names, waypoints.map { it.name })
        assertEquals(center, waypoints.first().coordinates)
        assertEquals(center, waypoints.last().coordinates)
        assertEquals(3, waypoints.map { it.coordinates }.distinct().size)
        SimulationRoute(
            name = "Sample",
            waypoints = waypoints,
            speedKph = 24.0,
            loop = false,
        )
    }
}
