package com.gpsupdater.app.privacy

import android.content.Context
import com.gpsupdater.app.enterprise.SimulationRunRepository
import com.gpsupdater.app.favorites.FavoriteLocationRepository
import com.gpsupdater.app.history.LocationHistoryRepository
import com.gpsupdater.app.location.MockLocationPreferences
import com.gpsupdater.app.routes.RouteRepository
import java.io.File

class LocalTestDataCleaner(context: Context) {
    private val applicationContext = context.applicationContext

    fun clear() {
        FavoriteLocationRepository(applicationContext).clear()
        RouteRepository(applicationContext).clear()
        LocationHistoryRepository(applicationContext).clear()
        SimulationRunRepository(applicationContext).clear()
        MockLocationPreferences.clear(applicationContext)
        reportsDirectory()?.let { directory ->
            check(directory.deleteRecursively()) { "Could not delete local Enterprise reports" }
        }
    }

    private fun reportsDirectory(): File? = applicationContext
        .getExternalFilesDir(null)
        ?.resolve(REPORTS_DIRECTORY)
        ?.takeIf(File::exists)

    companion object {
        private const val REPORTS_DIRECTORY = "reports"
    }
}
