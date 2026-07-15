package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(start: Coordinates, end: Coordinates): Double {
        val startLatitude = Math.toRadians(start.latitude)
        val endLatitude = Math.toRadians(end.latitude)
        val latitudeDelta = endLatitude - startLatitude
        val longitudeDelta = Math.toRadians(end.longitude - start.longitude)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(startLatitude) * cos(endLatitude) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun initialBearingDegrees(start: Coordinates, end: Coordinates): Float {
        val startLatitude = Math.toRadians(start.latitude)
        val endLatitude = Math.toRadians(end.latitude)
        val longitudeDelta = Math.toRadians(end.longitude - start.longitude)
        val y = sin(longitudeDelta) * cos(endLatitude)
        val x = cos(startLatitude) * sin(endLatitude) -
            sin(startLatitude) * cos(endLatitude) * cos(longitudeDelta)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    fun interpolate(start: Coordinates, end: Coordinates, fraction: Double): Coordinates {
        val clampedFraction = fraction.coerceIn(0.0, 1.0)
        if (clampedFraction == 0.0) return start
        if (clampedFraction == 1.0) return end
        val angularDistance = distanceMeters(start, end) / EARTH_RADIUS_METERS
        if (angularDistance < 1e-12) return start

        val startLatitude = Math.toRadians(start.latitude)
        val startLongitude = Math.toRadians(start.longitude)
        val endLatitude = Math.toRadians(end.latitude)
        val endLongitude = Math.toRadians(end.longitude)
        val denominator = sin(angularDistance)
        val startWeight = sin((1 - clampedFraction) * angularDistance) / denominator
        val endWeight = sin(clampedFraction * angularDistance) / denominator
        val x = startWeight * cos(startLatitude) * cos(startLongitude) +
            endWeight * cos(endLatitude) * cos(endLongitude)
        val y = startWeight * cos(startLatitude) * sin(startLongitude) +
            endWeight * cos(endLatitude) * sin(endLongitude)
        val z = startWeight * sin(startLatitude) + endWeight * sin(endLatitude)
        val latitude = atan2(z, sqrt(x * x + y * y))
        val longitude = atan2(y, x)
        return Coordinates(Math.toDegrees(latitude), Math.toDegrees(longitude))
    }

    fun destination(
        start: Coordinates,
        distanceMeters: Double,
        bearingDegrees: Double,
    ): Coordinates {
        require(distanceMeters.isFinite() && distanceMeters >= 0)
        require(bearingDegrees.isFinite())
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees)
        val latitude = Math.toRadians(start.latitude)
        val longitude = Math.toRadians(start.longitude)
        val destinationLatitude = kotlin.math.asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearing),
        )
        val destinationLongitude = longitude + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(destinationLatitude),
        )
        val normalizedLongitude = (Math.toDegrees(destinationLongitude) + 540.0) % 360.0 - 180.0
        return Coordinates(Math.toDegrees(destinationLatitude), normalizedLongitude)
    }
}
