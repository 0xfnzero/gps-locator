package com.gpsupdater.app.favorites

import android.content.Context
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.sync.SyncDeletionRepository
import com.gpsupdater.app.sync.SyncedEntityType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class FavoriteLocationRepository(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val deletions = SyncDeletionRepository(context)

    fun all(): List<FavoriteLocation> {
        val stored = preferences.getString(KEY_FAVORITES, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(stored)
            buildList {
                for (index in 0 until json.length()) {
                    parse(json.optJSONObject(index) ?: continue)?.let(::add)
                }
            }.sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
    }

    fun save(name: String, group: String, coordinates: Coordinates): FavoriteLocation {
        val favorites = all().toMutableList()
        val existingIndex = favorites.indexOfFirst {
            it.coordinates.latitude == coordinates.latitude &&
                it.coordinates.longitude == coordinates.longitude
        }
        val favorite = if (existingIndex >= 0) {
            favorites[existingIndex].copy(
                name = name.trim().take(MAX_NAME_LENGTH),
                group = group.trim().take(MAX_GROUP_LENGTH),
            )
        } else {
            FavoriteLocation(
                id = UUID.randomUUID().toString(),
                name = name.trim().take(MAX_NAME_LENGTH),
                group = group.trim().take(MAX_GROUP_LENGTH),
                coordinates = coordinates,
                createdAt = System.currentTimeMillis(),
            )
        }
        if (existingIndex >= 0) favorites[existingIndex] = favorite else favorites.add(0, favorite)
        persist(favorites)
        deletions.remove(SyncedEntityType.FAVORITE, favorite.id)
        return favorite
    }

    fun delete(id: String) {
        persist(all().filterNot { it.id == id })
        deletions.record(SyncedEntityType.FAVORITE, id)
    }

    fun clear() {
        deletions.recordAll(SyncedEntityType.FAVORITE, all().map(FavoriteLocation::id))
        preferences.edit().remove(KEY_FAVORITES).apply()
    }

    fun replaceAll(favorites: List<FavoriteLocation>) {
        persist(favorites.sortedByDescending { it.createdAt })
    }

    private fun persist(favorites: List<FavoriteLocation>) {
        val json = JSONArray()
        favorites.forEach { favorite ->
            json.put(
                JSONObject()
                    .put("id", favorite.id)
                    .put("name", favorite.name)
                    .put("group", favorite.group)
                    .put("latitude", favorite.coordinates.latitude)
                    .put("longitude", favorite.coordinates.longitude)
                    .put("createdAt", favorite.createdAt),
            )
        }
        preferences.edit().putString(KEY_FAVORITES, json.toString()).apply()
    }

    private fun parse(json: JSONObject): FavoriteLocation? = runCatching {
        FavoriteLocation(
            id = json.getString("id"),
            name = json.getString("name"),
            group = json.optString("group"),
            coordinates = Coordinates(
                json.getDouble("latitude"),
                json.getDouble("longitude"),
            ),
            createdAt = json.getLong("createdAt"),
        )
    }.getOrNull()

    companion object {
        private const val FILE_NAME = "favorite_locations"
        private const val KEY_FAVORITES = "favorites"
        private const val MAX_NAME_LENGTH = 80
        private const val MAX_GROUP_LENGTH = 40
    }
}
