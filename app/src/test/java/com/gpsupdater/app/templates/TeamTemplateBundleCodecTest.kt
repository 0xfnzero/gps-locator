package com.gpsupdater.app.templates

import com.gpsupdater.app.favorites.FavoriteLocation
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.routes.RouteWaypoint
import com.gpsupdater.app.routes.SimulationRoute
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TeamTemplateBundleCodecTest {
    @Test
    fun roundTrip_preservesSavedLocationsAndRoutesWithoutPersonalHistory() {
        val coordinates = Coordinates(35.6762, 139.6503)
        val bundle = TeamTemplateBundle(
            version = 1,
            exportedAt = 1234,
            favorites = listOf(
                FavoriteLocation("tokyo", "Tokyo weather", "Localization", coordinates, 100),
            ),
            routes = listOf(
                SimulationRoute(
                    id = "delivery",
                    name = "Tokyo delivery",
                    waypoints = listOf(
                        RouteWaypoint(id = "a", name = "A", coordinates = coordinates),
                        RouteWaypoint(
                            id = "b",
                            name = "B",
                            coordinates = Coordinates(35.6862, 139.6603),
                        ),
                    ),
                    speedKph = 30.0,
                    loop = false,
                    createdAt = 200,
                ),
            ),
        )

        val encoded = TeamTemplateBundleCodec.encode(bundle)
        val decoded = TeamTemplateBundleCodec.decode(encoded)

        assertEquals(bundle, decoded)
        val json = JSONObject(encoded)
        assertEquals(false, json.has("history"))
        assertEquals(false, json.has("deletions"))
    }

    @Test
    fun decode_rejectsWrongFormatVersionAndDuplicateIds() {
        val base = JSONObject()
            .put("format", "gps-locator-team-templates")
            .put("version", 1)
            .put("exportedAt", 1)
            .put("favorites", JSONArray())
            .put("routes", JSONArray())

        assertThrows(IllegalArgumentException::class.java) {
            TeamTemplateBundleCodec.decode(JSONObject(base.toString()).put("format", "other").toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TeamTemplateBundleCodec.decode(JSONObject(base.toString()).put("version", 2).toString())
        }

        val favorite = JSONObject()
            .put("id", "duplicate")
            .put("name", "Berlin")
            .put("group", "QA")
            .put("latitude", 52.52)
            .put("longitude", 13.405)
            .put("createdAt", 1)
        val duplicates = JSONObject(base.toString()).put(
            "favorites",
            JSONArray().put(favorite).put(JSONObject(favorite.toString())),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TeamTemplateBundleCodec.decode(duplicates.toString())
        }
    }
}
