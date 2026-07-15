package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimulatorTest {
    @Test
    fun advance_movesAtConfiguredSpeed() {
        val route = route(
            start = Coordinates(0.0, 0.0),
            end = Coordinates(1.0, 0.0),
            speedKph = 36.0,
        )
        val simulator = RouteSimulator(route)

        val position = simulator.advance(1_000.0)

        assertEquals(10_000.0, GeoMath.distanceMeters(route.waypoints[0].coordinates, position.coordinates), 2.0)
        assertEquals(10f, position.speedMetersPerSecond, 0.01f)
        assertFalse(position.isComplete)
    }

    @Test
    fun waypointStop_isAppliedBeforeCompletion() {
        val route = SimulationRoute(
            name = "Stop test",
            waypoints = listOf(
                waypoint("Start", Coordinates(0.0, 0.0)),
                waypoint("End", Coordinates(0.001, 0.0), stopSeconds = 60),
            ),
            speedKph = 36.0,
            loop = false,
        )
        val simulator = RouteSimulator(route)

        val stopped = simulator.advance(12.0)
        assertTrue(stopped.isStopped)
        assertFalse(stopped.isComplete)

        val completed = simulator.advance(60.0)
        assertTrue(completed.isComplete)
        assertEquals(route.waypoints.last().coordinates, completed.coordinates)
    }

    @Test
    fun loop_includesReturnLegAndIncrementsLap() {
        val route = route(
            start = Coordinates(0.0, 0.0),
            end = Coordinates(0.001, 0.0),
            speedKph = 36.0,
            loop = true,
        )
        val lapSeconds = GeoMath.distanceMeters(
            route.waypoints[0].coordinates,
            route.waypoints[1].coordinates,
        ) * 2 / 10.0
        val position = RouteSimulator(route).advance(lapSeconds + 1.0)

        assertEquals(1, position.lap)
        assertFalse(position.isComplete)
        assertTrue(position.progress > 0)
    }

    @Test
    fun interpolation_usesShortestPathAcrossDateLine() {
        val midpoint = GeoMath.interpolate(
            Coordinates(0.0, 179.0),
            Coordinates(0.0, -179.0),
            0.5,
        )

        assertTrue(kotlin.math.abs(midpoint.longitude) > 179.9)
    }

    @Test
    fun destination_movesByRequestedDistanceAndBearing() {
        val start = Coordinates(31.2304, 121.4737)
        val destination = GeoMath.destination(start, 10.0, 90.0)

        assertEquals(10.0, GeoMath.distanceMeters(start, destination), 0.01)
        assertEquals(90f, GeoMath.initialBearingDegrees(start, destination), 0.1f)
    }

    private fun route(
        start: Coordinates,
        end: Coordinates,
        speedKph: Double,
        loop: Boolean = false,
    ) = SimulationRoute(
        name = "Test route",
        waypoints = listOf(waypoint("Start", start), waypoint("End", end)),
        speedKph = speedKph,
        loop = loop,
    )

    private fun waypoint(name: String, coordinates: Coordinates, stopSeconds: Long = 0) =
        RouteWaypoint(name = name, coordinates = coordinates, stopDurationSeconds = stopSeconds)
}
