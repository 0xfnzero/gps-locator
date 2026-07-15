package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationDocumentCodecTest {
    private val route = SimulationRoute(
        name = "Delivery & QA",
        waypoints = listOf(
            RouteWaypoint(
                name = "Start",
                coordinates = Coordinates(31.2304, 121.4737),
                stopDurationSeconds = 5,
            ),
            RouteWaypoint(
                name = "Finish",
                coordinates = Coordinates(31.2404, 121.4837),
                stopDurationSeconds = 30,
            ),
        ),
        speedKph = 42.0,
        loop = true,
    )

    @Test
    fun gpxRoundTrip_preservesRouteSettings() {
        val imported = LocationDocumentCodec.import(
            LocationDocumentCodec.export(route, LocationDocumentFormat.GPX),
        )

        assertRouteMatches(imported)
    }

    @Test
    fun kmlRoundTrip_preservesRouteSettings() {
        val imported = LocationDocumentCodec.import(
            LocationDocumentCodec.export(route, LocationDocumentFormat.KML),
        )

        assertRouteMatches(imported)
    }

    @Test
    fun importsGenericKmlLineString() {
        val imported = LocationDocumentCodec.import(
            """<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Line</name><Placemark><LineString><coordinates>10,20,0 11,21,0</coordinates></LineString></Placemark></Document></kml>""",
        )

        assertEquals(2, imported.waypoints.size)
        assertEquals(Coordinates(20.0, 10.0), imported.waypoints.first().coordinates)
    }

    @Test
    fun import_rejectsDocumentsWithExternalEntities() {
        val document = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY xxe SYSTEM="file:///etc/passwd">]><gpx version="1.1"><wpt lat="1" lon="2"><name>&xxe;</name></wpt><wpt lat="2" lon="3"/></gpx>"""

        assertThrows(IllegalArgumentException::class.java) {
            LocationDocumentCodec.import(document)
        }
    }

    private fun assertRouteMatches(imported: SimulationRoute) {
        assertEquals(route.name, imported.name)
        assertEquals(route.speedKph, imported.speedKph, 0.001)
        assertEquals(route.loop, imported.loop)
        assertEquals(route.waypoints.map { it.name }, imported.waypoints.map { it.name })
        assertEquals(
            route.waypoints.map { it.stopDurationSeconds },
            imported.waypoints.map { it.stopDurationSeconds },
        )
        assertTrue(
            imported.waypoints.zip(route.waypoints).all { (first, second) ->
                GeoMath.distanceMeters(first.coordinates, second.coordinates) < 0.1
            },
        )
    }
}
