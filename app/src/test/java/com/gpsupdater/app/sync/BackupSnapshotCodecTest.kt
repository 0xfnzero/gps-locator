package com.gpsupdater.app.sync

import com.gpsupdater.app.favorites.FavoriteLocation
import com.gpsupdater.app.history.HistoryMode
import com.gpsupdater.app.history.LocationHistoryEntry
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.routes.RouteWaypoint
import com.gpsupdater.app.routes.SimulationRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class BackupSnapshotCodecTest {
    @Test
    fun snapshotRoundTrip_preservesFavoritesRoutesAndHistory() {
        val coordinates = Coordinates(31.2304, 121.4737)
        val route = SimulationRoute(
            name = "QA route",
            waypoints = listOf(
                RouteWaypoint(name = "A", coordinates = coordinates),
                RouteWaypoint(name = "B", coordinates = Coordinates(31.2404, 121.4837)),
            ),
            speedKph = 30.0,
            loop = false,
        )
        val snapshot = BackupSnapshot(
            version = 2,
            exportedAt = 1234,
            favorites = listOf(FavoriteLocation("favorite", "Office", "QA", coordinates, 100)),
            routes = listOf(route),
            history = listOf(
                LocationHistoryEntry(
                    "history",
                    "Office",
                    coordinates,
                    HistoryMode.ROUTE,
                    route.id,
                    200,
                ),
            ),
            deletions = listOf(
                SyncDeletionMarker(SyncedEntityType.FAVORITE, "deleted-favorite", 300),
            ),
        )

        val restored = BackupSnapshotCodec.decode(BackupSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, restored)
    }

    @Test
    fun decode_acceptsLegacyVersionOneWithoutDeletionMarkers() {
        val legacy = JSONObject()
            .put("version", 1)
            .put("exportedAt", 1234)
            .put("favorites", org.json.JSONArray())
            .put("routes", org.json.JSONArray())
            .put("history", org.json.JSONArray())
            .toString()

        val restored = BackupSnapshotCodec.decode(legacy)

        assertEquals(1, restored.version)
        assertTrue(restored.deletions.isEmpty())
    }

    @Test
    fun merge_keepsDeletionMarkersFromRevivingOldDeviceData() {
        val coordinates = Coordinates(52.52, 13.405)
        val deletedFavorite = FavoriteLocation("favorite", "Berlin", "QA", coordinates, 100)
        val preservedRoute = SimulationRoute(
            id = "favorite",
            name = "Same ID, different entity type",
            waypoints = listOf(
                RouteWaypoint(name = "A", coordinates = coordinates),
                RouteWaypoint(name = "B", coordinates = Coordinates(52.53, 13.415)),
            ),
            speedKph = 20.0,
            loop = false,
        )
        val deletion = SyncDeletionMarker(SyncedEntityType.FAVORITE, deletedFavorite.id, 500)
        val current = BackupSnapshot(2, 600, emptyList(), listOf(preservedRoute), emptyList(), listOf(deletion))
        val staleDevice = BackupSnapshot(1, 100, listOf(deletedFavorite), emptyList(), emptyList())

        val merged = BackupSnapshotMerger.merge(current, staleDevice)

        assertTrue(merged.favorites.isEmpty())
        assertEquals(listOf(preservedRoute), merged.routes)
        assertEquals(listOf(deletion), merged.deletions)
    }
}
