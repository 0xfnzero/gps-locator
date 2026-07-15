package com.gpsupdater.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gpsupdater.app.favorites.FavoriteLocation
import com.gpsupdater.app.favorites.FavoriteLocationRepository
import com.gpsupdater.app.history.HistoryMode
import com.gpsupdater.app.history.LocationHistoryRepository
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.routes.RouteRepository
import com.gpsupdater.app.routes.RouteWaypoint
import com.gpsupdater.app.routes.SimulationRoute
import com.gpsupdater.app.sync.CloudSyncConfiguration
import com.gpsupdater.app.sync.CloudSyncSettings
import com.gpsupdater.app.sync.SyncDeletionRepository
import com.gpsupdater.app.sync.SyncedEntityType
import com.gpsupdater.app.templates.TeamTemplateBundle
import com.gpsupdater.app.templates.TeamTemplateBundleCodec
import com.gpsupdater.app.templates.TeamTemplateBundleRepository
import com.gpsupdater.app.templates.TeamTemplateImportPreview
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeamTemplateBundleRepositoryTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearFixturesBeforeTest() = clearFixtureStores()

    @After
    fun clearFixturesAfterTest() = clearFixtureStores()

    @Test
    fun importedTemplatesMergeAndReplaceWithoutChangingHistoryOrSyncConfiguration() {
        val favorites = FavoriteLocationRepository(context)
        val routes = RouteRepository(context)
        val history = LocationHistoryRepository(context)
        val deletions = SyncDeletionRepository(context)
        val sync = CloudSyncSettings(context)
        val originalFavorite = favorite("shared-favorite", "Old Berlin", 52.52, 13.405, 100)
        val localOnlyFavorite = favorite("local-favorite", "Local only", 48.8566, 2.3522, 200)
        val originalRoute = route("shared-route", "Old commute", 40.7128, -74.006, 100)
        val localOnlyRoute = route("local-route", "Local route", 51.5074, -0.1278, 200)
        favorites.replaceAll(listOf(originalFavorite, localOnlyFavorite))
        routes.replaceAll(listOf(originalRoute, localOnlyRoute))
        history.record("Personal history", Coordinates(35.6762, 139.6503), HistoryMode.STATIC)
        val historyBeforeImport = history.all()
        val syncConfiguration = CloudSyncConfiguration(
            endpoint = "https://sync.example.test",
            syncKey = "team-template-test-key-1234567890",
        )
        sync.save(syncConfiguration)
        deletions.record(SyncedEntityType.FAVORITE, originalFavorite.id, 1)
        deletions.record(SyncedEntityType.ROUTE, originalRoute.id, 2)
        deletions.record(SyncedEntityType.HISTORY, historyBeforeImport.single().id, 3)

        val incomingFavorite = favorite("shared-favorite", "New Berlin", 52.5201, 13.4051, 300)
        val incomingRoute = route("shared-route", "New commute", 40.7138, -74.005, 300)
        val newFavorite = favorite("team-favorite", "Team Tokyo", 35.6762, 139.6503, 400)
        val newRoute = route("team-route", "Team route", 1.3521, 103.8198, 400)
        val importedDocument = TeamTemplateBundleCodec.encode(
            TeamTemplateBundle(
                version = TeamTemplateBundleCodec.CURRENT_VERSION,
                exportedAt = 500,
                favorites = listOf(incomingFavorite, newFavorite),
                routes = listOf(incomingRoute, newRoute),
            ),
        )

        TeamTemplateBundleCodec.decode(importedDocument)
            .also(TeamTemplateBundleRepository(context)::merge)

        val mergedFavorites = favorites.all().associateBy(FavoriteLocation::id)
        val mergedRoutes = routes.all().associateBy(SimulationRoute::id)
        assertEquals(3, mergedFavorites.size)
        assertEquals(incomingFavorite, mergedFavorites[originalFavorite.id])
        assertEquals(localOnlyFavorite, mergedFavorites[localOnlyFavorite.id])
        assertEquals(newFavorite, mergedFavorites[newFavorite.id])
        assertEquals(3, mergedRoutes.size)
        assertEquals(incomingRoute, mergedRoutes[originalRoute.id])
        assertEquals(localOnlyRoute, mergedRoutes[localOnlyRoute.id])
        assertEquals(newRoute, mergedRoutes[newRoute.id])

        val remainingDeletions = deletions.all()
        assertFalse(
            remainingDeletions.any {
                it.type == SyncedEntityType.FAVORITE && it.id in setOf(originalFavorite.id, newFavorite.id)
            },
        )
        assertFalse(
            remainingDeletions.any {
                it.type == SyncedEntityType.ROUTE && it.id in setOf(originalRoute.id, newRoute.id)
            },
        )
        assertTrue(
            remainingDeletions.any {
                it.type == SyncedEntityType.HISTORY && it.id == historyBeforeImport.single().id
            },
        )
        assertEquals(historyBeforeImport, history.all())
        assertEquals(syncConfiguration, sync.load())
    }

    @Test
    fun exportSelectionAndImportPreviewUseOnlyExplicitTemplateIds() {
        val favorites = FavoriteLocationRepository(context)
        val routes = RouteRepository(context)
        val selectedFavorite = favorite("selected-favorite", "Selected", 52.52, 13.405, 100)
        val otherFavorite = favorite("other-favorite", "Other", 48.8566, 2.3522, 200)
        val selectedRoute = route("selected-route", "Selected route", 40.7128, -74.006, 100)
        val otherRoute = route("other-route", "Other route", 51.5074, -0.1278, 200)
        favorites.replaceAll(listOf(selectedFavorite, otherFavorite))
        routes.replaceAll(listOf(selectedRoute, otherRoute))

        val exported = TeamTemplateBundleRepository(context).export(
            favoriteIds = setOf(selectedFavorite.id),
            routeIds = setOf(selectedRoute.id),
        )

        assertEquals(listOf(selectedFavorite), exported.favorites)
        assertEquals(listOf(selectedRoute), exported.routes)

        val preview = TeamTemplateBundleRepository(context).previewImport(
            TeamTemplateBundle(
                version = TeamTemplateBundleCodec.CURRENT_VERSION,
                exportedAt = 300,
                favorites = listOf(
                    selectedFavorite.copy(name = "Replacement"),
                    favorite("new-favorite", "New", 35.6762, 139.6503, 300),
                ),
                routes = listOf(
                    selectedRoute.copy(name = "Replacement route"),
                    route("new-route", "New route", 1.3521, 103.8198, 300),
                ),
            ),
        )
        assertEquals(
            TeamTemplateImportPreview(
                favoriteCount = 2,
                favoriteReplacementCount = 1,
                routeCount = 2,
                routeReplacementCount = 1,
            ),
            preview,
        )
    }

    private fun favorite(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        createdAt: Long,
    ) = FavoriteLocation(
        id = id,
        name = name,
        group = "QA",
        coordinates = Coordinates(latitude, longitude),
        createdAt = createdAt,
    )

    private fun route(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        createdAt: Long,
    ) = SimulationRoute(
        id = id,
        name = name,
        waypoints = listOf(
            RouteWaypoint(id = "$id-start", name = "Start", coordinates = Coordinates(latitude, longitude)),
            RouteWaypoint(
                id = "$id-finish",
                name = "Finish",
                coordinates = Coordinates(latitude + 0.001, longitude + 0.001),
            ),
        ),
        speedKph = 30.0,
        loop = false,
        createdAt = createdAt,
    )

    private fun clearFixtureStores() {
        listOf(
            "favorite_locations",
            "simulation_routes",
            "location_history",
            "sync_deletions",
            "cloud_sync",
        ).forEach { fileName ->
            context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
