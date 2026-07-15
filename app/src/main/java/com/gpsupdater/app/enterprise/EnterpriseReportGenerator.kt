package com.gpsupdater.app.enterprise

import android.content.Context
import android.os.Build
import com.gpsupdater.app.BuildConfig
import com.gpsupdater.app.routes.RouteRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class EnterpriseReportGenerator(private val context: Context) {
    fun writeLatest(): File {
        val reportsDirectory = File(context.getExternalFilesDir(null), "reports").apply { mkdirs() }
        val reportFile = File(reportsDirectory, "latest-report.json")
        reportFile.writeText(generate(), Charsets.UTF_8)
        return reportFile
    }

    fun generate(): String {
        val routes = RouteRepository(context).all().associateBy { it.id }
        val runs = SimulationRunRepository(context).all()
        return JSONObject()
            .put("schemaVersion", 2)
            .put("generatedAt", System.currentTimeMillis())
            .put("applicationId", BuildConfig.APPLICATION_ID)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("sdk", Build.VERSION.SDK_INT),
            )
            .put(
                "events",
                JSONArray().apply {
                    runs.forEach { run ->
                        put(
                            JSONObject()
                                .put("id", run.id)
                                .put("name", run.name)
                                .put("mode", run.mode.name.lowercase())
                                .put("outcome", run.outcome.name.lowercase())
                                .put("startedAt", run.startedAt)
                                .put("finishedAt", run.finishedAt)
                                .put("durationMs", run.finishedAt?.minus(run.startedAt))
                                .put("startLatitude", run.startCoordinates.latitude)
                                .put("startLongitude", run.startCoordinates.longitude)
                                .put("endLatitude", run.endCoordinates?.latitude)
                                .put("endLongitude", run.endCoordinates?.longitude)
                                .put("routeId", run.routeId)
                                .put("routeName", run.routeId?.let(routes::get)?.name)
                                .put("timeZoneId", run.timeZoneId)
                                .put("utcOffsetSeconds", run.utcOffsetSeconds),
                        )
                    }
                },
            )
            .toString(2)
    }
}
