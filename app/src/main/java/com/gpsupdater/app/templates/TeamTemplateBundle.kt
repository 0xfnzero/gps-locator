package com.gpsupdater.app.templates

import android.content.Context
import com.gpsupdater.app.favorites.FavoriteLocation
import com.gpsupdater.app.favorites.FavoriteLocationRepository
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.routes.RouteJsonCodec
import com.gpsupdater.app.routes.RouteRepository
import com.gpsupdater.app.routes.SimulationRoute
import com.gpsupdater.app.sync.SyncDeletionRepository
import com.gpsupdater.app.sync.SyncedEntityType
import org.json.JSONArray
import org.json.JSONObject

data class TeamTemplateBundle(
    val version: Int,
    val exportedAt: Long,
    val favorites: List<FavoriteLocation>,
    val routes: List<SimulationRoute>,
)

data class TeamTemplateImportPreview(
    val favoriteCount: Int,
    val favoriteReplacementCount: Int,
    val routeCount: Int,
    val routeReplacementCount: Int,
)

class TeamTemplateBundleRepository(context: Context) {
    private val favorites = FavoriteLocationRepository(context)
    private val routes = RouteRepository(context)
    private val deletions = SyncDeletionRepository(context)

    fun export(): TeamTemplateBundle = TeamTemplateBundle(
        version = TeamTemplateBundleCodec.CURRENT_VERSION,
        exportedAt = System.currentTimeMillis(),
        favorites = favorites.all(),
        routes = routes.all(),
    )

    fun export(favoriteIds: Set<String>, routeIds: Set<String>): TeamTemplateBundle =
        TeamTemplateBundle(
            version = TeamTemplateBundleCodec.CURRENT_VERSION,
            exportedAt = System.currentTimeMillis(),
            favorites = favorites.all().filter { it.id in favoriteIds },
            routes = routes.all().filter { it.id in routeIds },
        )

    fun previewImport(bundle: TeamTemplateBundle): TeamTemplateImportPreview {
        val existingFavoriteIds = favorites.all().map(FavoriteLocation::id).toSet()
        val existingRouteIds = routes.all().map(SimulationRoute::id).toSet()
        return TeamTemplateImportPreview(
            favoriteCount = bundle.favorites.size,
            favoriteReplacementCount = bundle.favorites.count { it.id in existingFavoriteIds },
            routeCount = bundle.routes.size,
            routeReplacementCount = bundle.routes.count { it.id in existingRouteIds },
        )
    }

    fun merge(bundle: TeamTemplateBundle) {
        favorites.replaceAll(mergeById(favorites.all(), bundle.favorites, FavoriteLocation::id))
        routes.replaceAll(mergeById(routes.all(), bundle.routes, SimulationRoute::id))
        deletions.removeAll(SyncedEntityType.FAVORITE, bundle.favorites.map(FavoriteLocation::id))
        deletions.removeAll(SyncedEntityType.ROUTE, bundle.routes.map(SimulationRoute::id))
    }

    private fun <T> mergeById(current: List<T>, incoming: List<T>, id: (T) -> String): List<T> =
        (current + incoming).associateBy(id).values.toList()
}

object TeamTemplateBundleCodec {
    const val CURRENT_VERSION = 1
    const val MAX_DOCUMENT_BYTES = 5 * 1024 * 1024
    private const val FORMAT = "gps-locator-team-templates"
    private const val MAX_FAVORITES = 5_000
    private const val MAX_ROUTES = 500
    private const val MAX_WAYPOINTS_PER_ROUTE = 1_000
    private const val MAX_ID_LENGTH = 128
    private const val MAX_NAME_LENGTH = 100
    private const val MAX_GROUP_LENGTH = 40

    fun encode(bundle: TeamTemplateBundle): String {
        validate(bundle)
        val document = JSONObject()
            .put("format", FORMAT)
            .put("version", bundle.version)
            .put("exportedAt", bundle.exportedAt)
            .put(
                "favorites",
                JSONArray().apply {
                    bundle.favorites.forEach { favorite ->
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
                },
            )
            .put("routes", JSONArray(RouteJsonCodec.encodeList(bundle.routes)))
            .toString(2)
        require(document.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "Team template document is too large"
        }
        return document
    }

    fun decode(value: String): TeamTemplateBundle {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "Team template document is too large"
        }
        val document = JSONObject(value)
        require(document.getString("format") == FORMAT) { "Unsupported template format" }
        require(document.getInt("version") == CURRENT_VERSION) { "Unsupported template version" }
        val exportedAt = document.getLong("exportedAt")
        require(exportedAt >= 0) { "Invalid template export time" }
        val favoritesJson = document.optJSONArray("favorites") ?: JSONArray()
        val routesJson = document.optJSONArray("routes") ?: JSONArray()
        require(favoritesJson.length() <= MAX_FAVORITES) { "Too many saved-location templates" }
        require(routesJson.length() <= MAX_ROUTES) { "Too many route templates" }

        val favorites = buildList {
            for (index in 0 until favoritesJson.length()) {
                val item = favoritesJson.getJSONObject(index)
                add(
                    FavoriteLocation(
                        id = requiredText(item.getString("id"), "Favorite id", MAX_ID_LENGTH),
                        name = requiredText(item.getString("name"), "Favorite name", MAX_NAME_LENGTH),
                        group = item.optString("group").trim().also {
                            require(it.length <= MAX_GROUP_LENGTH) { "Favorite group is too long" }
                        },
                        coordinates = Coordinates(
                            item.getDouble("latitude"),
                            item.getDouble("longitude"),
                        ),
                        createdAt = item.getLong("createdAt").also {
                            require(it >= 0) { "Invalid favorite creation time" }
                        },
                    ),
                )
            }
        }
        val routes = buildList {
            for (index in 0 until routesJson.length()) {
                val route = RouteJsonCodec.decode(routesJson.getJSONObject(index).toString())
                    ?: error("Invalid route template at index $index")
                add(route)
            }
        }
        return TeamTemplateBundle(CURRENT_VERSION, exportedAt, favorites, routes).also(::validate)
    }

    private fun validate(bundle: TeamTemplateBundle) {
        require(bundle.version == CURRENT_VERSION) { "Unsupported template version" }
        require(bundle.exportedAt >= 0) { "Invalid template export time" }
        require(bundle.favorites.size <= MAX_FAVORITES) { "Too many saved-location templates" }
        require(bundle.routes.size <= MAX_ROUTES) { "Too many route templates" }
        bundle.favorites.forEach { favorite ->
            requireCanonicalText(favorite.id, "Favorite id", MAX_ID_LENGTH)
            requireCanonicalText(favorite.name, "Favorite name", MAX_NAME_LENGTH)
            require(favorite.group == favorite.group.trim()) {
                "Favorite group has surrounding whitespace"
            }
            require(favorite.group.length <= MAX_GROUP_LENGTH) { "Favorite group is too long" }
            require(favorite.createdAt >= 0) { "Invalid favorite creation time" }
        }
        bundle.routes.forEach { route ->
            requireCanonicalText(route.id, "Route id", MAX_ID_LENGTH)
            requireCanonicalText(route.name, "Route name", MAX_NAME_LENGTH)
            require(route.createdAt >= 0) { "Invalid route creation time" }
            require(route.waypoints.size <= MAX_WAYPOINTS_PER_ROUTE) {
                "Route template has too many waypoints"
            }
            route.waypoints.forEach { waypoint ->
                requireCanonicalText(waypoint.id, "Waypoint id", MAX_ID_LENGTH)
                requireCanonicalText(waypoint.name, "Waypoint name", MAX_NAME_LENGTH)
            }
            require(route.waypoints.map { it.id }.distinct().size == route.waypoints.size) {
                "Duplicate waypoint template id"
            }
        }
        require(bundle.favorites.map(FavoriteLocation::id).distinct().size == bundle.favorites.size) {
            "Duplicate saved-location template id"
        }
        require(bundle.routes.map(SimulationRoute::id).distinct().size == bundle.routes.size) {
            "Duplicate route template id"
        }
    }

    private fun requiredText(value: String, label: String, maximumLength: Int): String {
        val text = value.trim()
        require(text.isNotEmpty()) { "$label is required" }
        require(text.length <= maximumLength) { "$label is too long" }
        return text
    }

    private fun requireCanonicalText(value: String, label: String, maximumLength: Int) {
        require(value == requiredText(value, label, maximumLength)) {
            "$label has surrounding whitespace"
        }
    }
}
