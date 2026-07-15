package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates

data class RoutePosition(
    val coordinates: Coordinates,
    val speedMetersPerSecond: Float,
    val bearingDegrees: Float,
    val progress: Double,
    val currentWaypointIndex: Int,
    val lap: Int,
    val isStopped: Boolean,
    val isComplete: Boolean,
    val elapsedSeconds: Double,
)

class RouteSimulator(private val route: SimulationRoute) {
    private val speedMetersPerSecond = route.speedKph / 3.6
    private val legDistances = buildList {
        for (index in 0 until route.waypoints.lastIndex) {
            add(
                GeoMath.distanceMeters(
                    route.waypoints[index].coordinates,
                    route.waypoints[index + 1].coordinates,
                ),
            )
        }
        if (route.loop) {
            add(
                GeoMath.distanceMeters(
                    route.waypoints.last().coordinates,
                    route.waypoints.first().coordinates,
                ),
            )
        }
    }
    private val totalDistance = legDistances.sum()

    private var currentWaypointIndex = 0
    private var distanceIntoLeg = 0.0
    private var distanceCompletedInLap = 0.0
    private var stopRemainingSeconds = route.waypoints.first().stopDurationSeconds.toDouble()
    private var elapsedSeconds = 0.0
    private var lap = 0
    private var complete = false

    fun current(): RoutePosition = buildPosition()

    fun advance(deltaSeconds: Double): RoutePosition {
        require(deltaSeconds.isFinite() && deltaSeconds >= 0)
        var remainingSeconds = deltaSeconds
        var iterations = 0
        while (remainingSeconds > 1e-9 && !complete) {
            check(iterations++ < MAX_ADVANCE_ITERATIONS) { "Route contains too many zero-length steps" }

            if (stopRemainingSeconds > 0) {
                val consumed = minOf(stopRemainingSeconds, remainingSeconds)
                stopRemainingSeconds -= consumed
                remainingSeconds -= consumed
                elapsedSeconds += consumed
                if (stopRemainingSeconds > 1e-9) break
            }

            if (!route.loop && currentWaypointIndex == route.waypoints.lastIndex) {
                complete = true
                break
            }

            val targetWaypointIndex = (currentWaypointIndex + 1) % route.waypoints.size
            val legIndex = if (currentWaypointIndex == route.waypoints.lastIndex) {
                legDistances.lastIndex
            } else {
                currentWaypointIndex
            }
            val legDistance = legDistances[legIndex]
            val remainingLegDistance = (legDistance - distanceIntoLeg).coerceAtLeast(0.0)
            if (remainingLegDistance <= 1e-6) {
                arriveAt(targetWaypointIndex, legDistance)
                continue
            }

            val travelSeconds = remainingLegDistance / speedMetersPerSecond
            if (remainingSeconds < travelSeconds) {
                distanceIntoLeg += remainingSeconds * speedMetersPerSecond
                elapsedSeconds += remainingSeconds
                remainingSeconds = 0.0
            } else {
                remainingSeconds -= travelSeconds
                elapsedSeconds += travelSeconds
                arriveAt(targetWaypointIndex, legDistance)
            }
        }
        return buildPosition()
    }

    private fun arriveAt(targetWaypointIndex: Int, completedLegDistance: Double) {
        distanceCompletedInLap += completedLegDistance
        currentWaypointIndex = targetWaypointIndex
        distanceIntoLeg = 0.0
        stopRemainingSeconds = route.waypoints[targetWaypointIndex].stopDurationSeconds.toDouble()
        if (route.loop && targetWaypointIndex == 0) {
            lap += 1
            distanceCompletedInLap = 0.0
        } else if (!route.loop && targetWaypointIndex == route.waypoints.lastIndex &&
            stopRemainingSeconds == 0.0
        ) {
            complete = true
        }
    }

    private fun buildPosition(): RoutePosition {
        val currentWaypoint = route.waypoints[currentWaypointIndex]
        val atTerminal = !route.loop && currentWaypointIndex == route.waypoints.lastIndex
        val targetWaypointIndex = if (atTerminal) {
            currentWaypointIndex
        } else {
            (currentWaypointIndex + 1) % route.waypoints.size
        }
        val targetWaypoint = route.waypoints[targetWaypointIndex]
        val legIndex = when {
            atTerminal -> -1
            currentWaypointIndex == route.waypoints.lastIndex -> legDistances.lastIndex
            else -> currentWaypointIndex
        }
        val legDistance = if (legIndex >= 0) legDistances[legIndex] else 0.0
        val fraction = if (legDistance <= 1e-9) 0.0 else distanceIntoLeg / legDistance
        val coordinates = if (legIndex < 0 || stopRemainingSeconds > 0) {
            currentWaypoint.coordinates
        } else {
            GeoMath.interpolate(currentWaypoint.coordinates, targetWaypoint.coordinates, fraction)
        }
        val progress = when {
            complete -> 1.0
            totalDistance <= 1e-9 -> 0.0
            else -> ((distanceCompletedInLap + distanceIntoLeg) / totalDistance).coerceIn(0.0, 1.0)
        }
        val isStopped = stopRemainingSeconds > 1e-9 || complete
        return RoutePosition(
            coordinates = coordinates,
            speedMetersPerSecond = if (isStopped) 0f else speedMetersPerSecond.toFloat(),
            bearingDegrees = if (isStopped || legIndex < 0) {
                0f
            } else {
                GeoMath.initialBearingDegrees(currentWaypoint.coordinates, targetWaypoint.coordinates)
            },
            progress = progress,
            currentWaypointIndex = currentWaypointIndex,
            lap = lap,
            isStopped = isStopped,
            isComplete = complete,
            elapsedSeconds = elapsedSeconds,
        )
    }

    companion object {
        private const val MAX_ADVANCE_ITERATIONS = 10_000
    }
}
