package com.gpsupdater.app.history

import android.content.Context
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.sync.SyncDeletionRepository
import com.gpsupdater.app.sync.SyncedEntityType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LocationHistoryRepository(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val deletions = SyncDeletionRepository(context)

    fun all(): List<LocationHistoryEntry> {
        val stored = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            buildList {
                for (index in 0 until array.length()) {
                    parse(array.optJSONObject(index) ?: continue)?.let(::add)
                }
            }.sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
    }

    fun record(
        name: String,
        coordinates: Coordinates,
        mode: HistoryMode,
        routeId: String? = null,
    ) {
        val entry = LocationHistoryEntry(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank {
                "${coordinates.formattedLatitude()}, ${coordinates.formattedLongitude()}"
            }.take(MAX_NAME_LENGTH),
            coordinates = coordinates,
            mode = mode,
            routeId = routeId,
            createdAt = System.currentTimeMillis(),
        )
        persist((listOf(entry) + all()).take(MAX_HISTORY))
    }

    fun delete(id: String) {
        persist(all().filterNot { it.id == id })
        deletions.record(SyncedEntityType.HISTORY, id)
    }

    fun clear() {
        deletions.recordAll(SyncedEntityType.HISTORY, all().map(LocationHistoryEntry::id))
        preferences.edit().remove(KEY_HISTORY).apply()
    }

    fun replaceAll(entries: List<LocationHistoryEntry>) {
        persist(entries.sortedByDescending { it.createdAt }.take(MAX_HISTORY))
    }

    private fun persist(entries: List<LocationHistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
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
        preferences.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun parse(json: JSONObject): LocationHistoryEntry? = runCatching {
        LocationHistoryEntry(
            id = json.getString("id"),
            name = json.getString("name"),
            coordinates = Coordinates(
                json.getDouble("latitude"),
                json.getDouble("longitude"),
            ),
            mode = HistoryMode.valueOf(json.getString("mode")),
            routeId = json.optString("routeId").takeIf { it.isNotBlank() && it != "null" },
            createdAt = json.getLong("createdAt"),
        )
    }.getOrNull()

    companion object {
        private const val FILE_NAME = "location_history"
        private const val KEY_HISTORY = "entries"
        private const val MAX_HISTORY = 500
        private const val MAX_NAME_LENGTH = 100
    }
}
