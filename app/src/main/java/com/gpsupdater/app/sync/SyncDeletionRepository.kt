package com.gpsupdater.app.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class SyncedEntityType {
    FAVORITE,
    ROUTE,
    HISTORY,
}

data class SyncDeletionMarker(
    val type: SyncedEntityType,
    val id: String,
    val deletedAt: Long,
)

class SyncDeletionRepository(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun all(): List<SyncDeletionMarker> {
        val stored = preferences.getString(KEY_DELETIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        SyncDeletionMarker(
                            type = SyncedEntityType.valueOf(item.getString("type")),
                            id = item.getString("id"),
                            deletedAt = item.getLong("deletedAt"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun record(type: SyncedEntityType, id: String, deletedAt: Long = System.currentTimeMillis()) {
        if (id.isBlank()) return
        replaceAll(all() + SyncDeletionMarker(type, id, deletedAt))
    }

    fun recordAll(
        type: SyncedEntityType,
        ids: Collection<String>,
        deletedAt: Long = System.currentTimeMillis(),
    ) {
        replaceAll(all() + ids.filter(String::isNotBlank).map { SyncDeletionMarker(type, it, deletedAt) })
    }

    fun remove(type: SyncedEntityType, id: String) {
        replaceAll(all().filterNot { it.type == type && it.id == id })
    }

    fun removeAll(type: SyncedEntityType, ids: Collection<String>) {
        val removedIds = ids.toSet()
        if (removedIds.isEmpty()) return
        replaceAll(all().filterNot { it.type == type && it.id in removedIds })
    }

    fun replaceAll(markers: List<SyncDeletionMarker>) {
        val latest = markers
            .groupBy { it.type to it.id }
            .mapNotNull { (_, versions) -> versions.maxByOrNull(SyncDeletionMarker::deletedAt) }
            .sortedByDescending(SyncDeletionMarker::deletedAt)
        val array = JSONArray().apply {
            latest.forEach { marker ->
                put(
                    JSONObject()
                        .put("type", marker.type.name)
                        .put("id", marker.id)
                        .put("deletedAt", marker.deletedAt),
                )
            }
        }
        preferences.edit().putString(KEY_DELETIONS, array.toString()).apply()
    }

    companion object {
        private const val FILE_NAME = "sync_deletions"
        private const val KEY_DELETIONS = "deletions"
    }
}
