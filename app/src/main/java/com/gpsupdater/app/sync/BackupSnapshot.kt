package com.gpsupdater.app.sync

import android.content.Context
import com.gpsupdater.app.favorites.FavoriteLocation
import com.gpsupdater.app.favorites.FavoriteLocationRepository
import com.gpsupdater.app.history.HistoryMode
import com.gpsupdater.app.history.LocationHistoryEntry
import com.gpsupdater.app.history.LocationHistoryRepository
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.routes.RouteJsonCodec
import com.gpsupdater.app.routes.RouteRepository
import com.gpsupdater.app.routes.SimulationRoute
import org.json.JSONArray
import org.json.JSONObject

data class BackupSnapshot(
    val version: Int,
    val exportedAt: Long,
    val favorites: List<FavoriteLocation>,
    val routes: List<SimulationRoute>,
    val history: List<LocationHistoryEntry>,
    val deletions: List<SyncDeletionMarker> = emptyList(),
)

class BackupSnapshotRepository(context: Context) {
    private val favorites = FavoriteLocationRepository(context)
    private val routes = RouteRepository(context)
    private val history = LocationHistoryRepository(context)
    private val deletions = SyncDeletionRepository(context)

    fun export(): BackupSnapshot = BackupSnapshot(
        version = CURRENT_VERSION,
        exportedAt = System.currentTimeMillis(),
        favorites = favorites.all(),
        routes = routes.all(),
        history = history.all(),
        deletions = deletions.all(),
    )

    fun merge(snapshot: BackupSnapshot) {
        val merged = BackupSnapshotMerger.merge(export(), snapshot)
        favorites.replaceAll(merged.favorites)
        routes.replaceAll(merged.routes)
        history.replaceAll(merged.history)
        deletions.replaceAll(merged.deletions)
    }

    companion object {
        private const val CURRENT_VERSION = 2
    }
}

object BackupSnapshotMerger {
    fun merge(current: BackupSnapshot, incoming: BackupSnapshot): BackupSnapshot {
        val deletions = (current.deletions + incoming.deletions)
            .groupBy { it.type to it.id }
            .mapNotNull { (_, versions) -> versions.maxByOrNull(SyncDeletionMarker::deletedAt) }
        return BackupSnapshot(
            version = 2,
            exportedAt = maxOf(current.exportedAt, incoming.exportedAt),
            favorites = mergeById(current.favorites, incoming.favorites) { it.id }
                .filterNot { favorite -> deletions.deletes(SyncedEntityType.FAVORITE, favorite.id) },
            routes = mergeById(current.routes, incoming.routes) { it.id }
                .filterNot { route -> deletions.deletes(SyncedEntityType.ROUTE, route.id) },
            history = mergeById(current.history, incoming.history) { it.id }
                .filterNot { entry -> deletions.deletes(SyncedEntityType.HISTORY, entry.id) },
            deletions = deletions,
        )
    }

    private fun List<SyncDeletionMarker>.deletes(type: SyncedEntityType, id: String): Boolean =
        any { it.type == type && it.id == id }

    private fun <T> mergeById(current: List<T>, incoming: List<T>, id: (T) -> String): List<T> =
        (current + incoming).associateBy(id).values.toList()
}

object BackupSnapshotCodec {
    fun encode(snapshot: BackupSnapshot): String = JSONObject()
        .put("version", snapshot.version)
        .put("exportedAt", snapshot.exportedAt)
        .put("favorites", JSONArray().apply {
            snapshot.favorites.forEach { favorite ->
                put(
                    JSONObject()
                        .put("id", favorite.id)
                        .put("name", favorite.name)
                        .put("group", favorite.group)
                        .put("latitude", favorite.coordinates.latitude)
                        .put("longitude", favorite.coordinates.longitude)
                        .put("createdAt", favorite.createdAt),
                )
            }
        })
        .put("routes", JSONArray(RouteJsonCodec.encodeList(snapshot.routes)))
        .put("history", JSONArray().apply {
            snapshot.history.forEach { entry ->
                put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("name", entry.name)
                        .put("latitude", entry.coordinates.latitude)
                        .put("longitude", entry.coordinates.longitude)
                        .put("mode", entry.mode.name)
                        .put("routeId", entry.routeId)
                        .put("createdAt", entry.createdAt),
                )
            }
        })
        .put("deletions", JSONArray().apply {
            snapshot.deletions.forEach { marker ->
                put(
                    JSONObject()
                        .put("type", marker.type.name)
                        .put("id", marker.id)
                        .put("deletedAt", marker.deletedAt),
                )
            }
        })
        .toString()

    fun decode(value: String): BackupSnapshot {
        val json = JSONObject(value)
        val version = json.getInt("version")
        require(version in 1..2) { "Unsupported backup snapshot version" }
        val favoritesJson = json.optJSONArray("favorites") ?: JSONArray()
        val favorites = buildList {
            for (index in 0 until favoritesJson.length()) {
                val item = favoritesJson.getJSONObject(index)
                add(
                    FavoriteLocation(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        group = item.optString("group"),
                        coordinates = Coordinates(
                            item.getDouble("latitude"),
                            item.getDouble("longitude"),
                        ),
                        createdAt = item.getLong("createdAt"),
                    ),
                )
            }
        }
        val historyJson = json.optJSONArray("history") ?: JSONArray()
        val history = buildList {
            for (index in 0 until historyJson.length()) {
                val item = historyJson.getJSONObject(index)
                add(
                    LocationHistoryEntry(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        coordinates = Coordinates(
                            item.getDouble("latitude"),
                            item.getDouble("longitude"),
                        ),
                        mode = HistoryMode.valueOf(item.getString("mode")),
                        routeId = item.optString("routeId").takeIf {
                            it.isNotBlank() && it != "null"
                        },
                        createdAt = item.getLong("createdAt"),
                    ),
                )
            }
        }
        val deletionsJson = json.optJSONArray("deletions") ?: JSONArray()
        val deletions = buildList {
            for (index in 0 until deletionsJson.length()) {
                val item = deletionsJson.getJSONObject(index)
                add(
                    SyncDeletionMarker(
                        type = SyncedEntityType.valueOf(item.getString("type")),
                        id = item.getString("id"),
                        deletedAt = item.getLong("deletedAt"),
                    ),
                )
            }
        }
        return BackupSnapshot(
            version = version,
            exportedAt = json.getLong("exportedAt"),
            favorites = favorites,
            routes = RouteJsonCodec.decodeList(json.optJSONArray("routes")?.toString().orEmpty()),
            history = history,
            deletions = deletions,
        )
    }
}
