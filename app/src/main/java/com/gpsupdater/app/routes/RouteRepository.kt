package com.gpsupdater.app.routes

import android.content.Context
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.sync.SyncDeletionRepository
import com.gpsupdater.app.sync.SyncedEntityType
import org.json.JSONArray
import org.json.JSONObject

class RouteRepository(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val deletions = SyncDeletionRepository(context)

    fun all(): List<SimulationRoute> = RouteJsonCodec.decodeList(
        preferences.getString(KEY_ROUTES, null).orEmpty(),
    ).sortedByDescending { it.createdAt }

    fun find(id: String): SimulationRoute? = all().firstOrNull { it.id == id }

    fun save(route: SimulationRoute) {
        val routes = all().toMutableList()
        val existingIndex = routes.indexOfFirst { it.id == route.id }
        if (existingIndex >= 0) routes[existingIndex] = route else routes.add(0, route)
        preferences.edit()
            .putString(KEY_ROUTES, RouteJsonCodec.encodeList(routes.take(MAX_ROUTES)))
            .apply()
        deletions.remove(SyncedEntityType.ROUTE, route.id)
    }

    fun delete(id: String) {
        preferences.edit()
            .putString(KEY_ROUTES, RouteJsonCodec.encodeList(all().filterNot { it.id == id }))
            .apply()
        deletions.record(SyncedEntityType.ROUTE, id)
    }

    fun clear() {
        deletions.recordAll(SyncedEntityType.ROUTE, all().map(SimulationRoute::id))
        preferences.edit().remove(KEY_ROUTES).apply()
    }

    fun replaceAll(routes: List<SimulationRoute>) {
        preferences.edit()
            .putString(
                KEY_ROUTES,
                RouteJsonCodec.encodeList(routes.sortedByDescending { it.createdAt }.take(MAX_ROUTES)),
            )
            .apply()
    }

    companion object {
        private const val FILE_NAME = "simulation_routes"
        private const val KEY_ROUTES = "routes"
        private const val MAX_ROUTES = 500
    }
}

object RouteJsonCodec {
    fun encode(route: SimulationRoute): String = encodeObject(route).toString()

    fun decode(value: String): SimulationRoute? = runCatching {
        decodeObject(JSONObject(value))
    }.getOrNull()

    fun encodeList(routes: List<SimulationRoute>): String = JSONArray().apply {
        routes.forEach { put(encodeObject(it)) }
    }.toString()

    fun decodeList(value: String): List<SimulationRoute> {
        if (value.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    runCatching { decodeObject(array.getJSONObject(index)) }.getOrNull()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeObject(route: SimulationRoute): JSONObject = JSONObject()
        .put("id", route.id)
        .put("name", route.name)
        .put("speedKph", route.speedKph)
        .put("loop", route.loop)
        .put("createdAt", route.createdAt)
        .put(
            "waypoints",
            JSONArray().apply {
                route.waypoints.forEach { waypoint ->
                    put(
                        JSONObject()
                            .put("id", waypoint.id)
                            .put("name", waypoint.name)
                            .put("latitude", waypoint.coordinates.latitude)
                            .put("longitude", waypoint.coordinates.longitude)
                            .put("stopDurationSeconds", waypoint.stopDurationSeconds),
                    )
                }
            },
        )

    private fun decodeObject(json: JSONObject): SimulationRoute {
        val waypointsJson = json.getJSONArray("waypoints")
        val waypoints = buildList {
            for (index in 0 until waypointsJson.length()) {
                val waypoint = waypointsJson.getJSONObject(index)
                add(
                    RouteWaypoint(
                        id = waypoint.getString("id"),
                        name = waypoint.getString("name"),
                        coordinates = Coordinates(
                            waypoint.getDouble("latitude"),
                            waypoint.getDouble("longitude"),
                        ),
                        stopDurationSeconds = waypoint.optLong("stopDurationSeconds", 0),
                    ),
                )
            }
        }
        return SimulationRoute(
            id = json.getString("id"),
            name = json.getString("name"),
            waypoints = waypoints,
            speedKph = json.getDouble("speedKph"),
            loop = json.optBoolean("loop", false),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}
