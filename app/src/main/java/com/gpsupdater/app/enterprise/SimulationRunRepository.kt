package com.gpsupdater.app.enterprise

import android.content.Context
import com.gpsupdater.app.history.HistoryMode
import com.gpsupdater.app.model.Coordinates
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID

enum class SimulationOutcome {
    RUNNING,
    COMPLETED,
    STOPPED,
    FAILED,
}

data class SimulationRun(
    val id: String,
    val name: String,
    val mode: HistoryMode,
    val routeId: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val startCoordinates: Coordinates,
    val endCoordinates: Coordinates?,
    val outcome: SimulationOutcome,
    val timeZoneId: String,
    val utcOffsetSeconds: Int?,
)

class SimulationRunRepository(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun all(): List<SimulationRun> {
        val value = preferences.getString(KEY_RUNS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    parse(array.getJSONObject(index))?.let(::add)
                }
            }.sortedByDescending { it.startedAt }
        }.getOrDefault(emptyList())
    }

    fun active(): SimulationRun? = all().firstOrNull { it.outcome == SimulationOutcome.RUNNING }

    fun clear() {
        preferences.edit().remove(KEY_RUNS).apply()
    }

    fun begin(name: String, mode: HistoryMode, coordinates: Coordinates, routeId: String?): SimulationRun {
        finishActive(SimulationOutcome.STOPPED, coordinates)
        val startedAt = System.currentTimeMillis()
        val timeZone = TimeZone.getDefault()
        val run = SimulationRun(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "${coordinates.formattedLatitude()}, ${coordinates.formattedLongitude()}" },
            mode = mode,
            routeId = routeId,
            startedAt = startedAt,
            finishedAt = null,
            startCoordinates = coordinates,
            endCoordinates = null,
            outcome = SimulationOutcome.RUNNING,
            timeZoneId = timeZone.id,
            utcOffsetSeconds = timeZone.getOffset(startedAt) / 1_000,
        )
        persist((listOf(run) + all()).take(MAX_RUNS))
        return run
    }

    fun finishActive(outcome: SimulationOutcome, coordinates: Coordinates) {
        require(outcome != SimulationOutcome.RUNNING)
        val runs = all().toMutableList()
        val index = runs.indexOfFirst { it.outcome == SimulationOutcome.RUNNING }
        if (index < 0) return
        runs[index] = runs[index].copy(
            finishedAt = System.currentTimeMillis(),
            endCoordinates = coordinates,
            outcome = outcome,
        )
        persist(runs)
    }

    private fun persist(runs: List<SimulationRun>) {
        val array = JSONArray()
        runs.take(MAX_RUNS).forEach { run ->
            array.put(
                JSONObject()
                    .put("id", run.id)
                    .put("name", run.name)
                    .put("mode", run.mode.name)
                    .put("routeId", run.routeId)
                    .put("startedAt", run.startedAt)
                    .put("finishedAt", run.finishedAt)
                    .put("startLatitude", run.startCoordinates.latitude)
                    .put("startLongitude", run.startCoordinates.longitude)
                    .put("endLatitude", run.endCoordinates?.latitude)
                    .put("endLongitude", run.endCoordinates?.longitude)
                    .put("outcome", run.outcome.name)
                    .put("timeZoneId", run.timeZoneId)
                    .put("utcOffsetSeconds", run.utcOffsetSeconds),
            )
        }
        preferences.edit().putString(KEY_RUNS, array.toString()).apply()
    }

    private fun parse(json: JSONObject): SimulationRun? = runCatching {
        val endLatitude = json.optDouble("endLatitude", Double.NaN)
        val endLongitude = json.optDouble("endLongitude", Double.NaN)
        SimulationRun(
            id = json.getString("id"),
            name = json.getString("name"),
            mode = HistoryMode.valueOf(json.getString("mode")),
            routeId = json.optString("routeId").takeIf { it.isNotBlank() && it != "null" },
            startedAt = json.getLong("startedAt"),
            finishedAt = json.optLong("finishedAt").takeIf { it > 0 },
            startCoordinates = Coordinates(
                json.getDouble("startLatitude"),
                json.getDouble("startLongitude"),
            ),
            endCoordinates = if (endLatitude.isFinite() && endLongitude.isFinite()) {
                Coordinates(endLatitude, endLongitude)
            } else {
                null
            },
            outcome = SimulationOutcome.valueOf(json.getString("outcome")),
            timeZoneId = json.optString("timeZoneId").ifBlank { "unknown" },
            utcOffsetSeconds = json.optInt("utcOffsetSeconds").takeIf {
                json.has("utcOffsetSeconds") && !json.isNull("utcOffsetSeconds")
            },
        )
    }.getOrNull()

    companion object {
        private const val FILE_NAME = "simulation_runs"
        private const val KEY_RUNS = "runs"
        private const val MAX_RUNS = 1_000
    }
}
