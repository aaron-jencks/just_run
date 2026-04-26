package com.example.justrun

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RunRepository(private val context: Context) {
    private val file = File(context.filesDir, "runs.json")
    private val _runs = MutableStateFlow(loadRuns())
    val runs: StateFlow<List<RunRecord>> = _runs.asStateFlow()

    fun addRun(run: RunRecord) {
        val updated = listOf(run) + _runs.value
        _runs.value = updated
        persist(updated)
    }

    fun deleteRun(runId: Long) {
        val updated = _runs.value.filterNot { it.id == runId }
        _runs.value = updated
        persist(updated)
    }

    private fun loadRuns(): List<RunRecord> {
        if (!file.exists()) {
            return emptyList()
        }
        return runCatching {
            val raw = file.readText()
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    add(json.getJSONObject(index).toRunRecord())
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun persist(runs: List<RunRecord>) {
        val array = JSONArray()
        runs.forEach { array.put(it.toJson()) }
        file.writeText(array.toString())
    }
}

private fun RunRecord.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("dateLabel", dateLabel)
    put("startedAtMillis", startedAtMillis)
    put("goal", goal.name)
    put("durationSeconds", durationSeconds)
    put("distanceKm", distanceKm.toDouble())
    put("avgPaceMinPerKm", avgPaceMinPerKm?.toDouble())
    put("calories", calories)
    put("elevationGainM", elevationGainM)
    put("notes", notes)
    put("hadGpsTracking", hadGpsTracking)
    put("hadHeartRateTracking", hadHeartRateTracking)
    put("paceSeries", JSONArray().apply { paceSeries.forEach { put(it.toDouble()) } })
    put("elevationSeries", JSONArray().apply { elevationSeries.forEach { put(it.toDouble()) } })
    put("lapSplits", JSONArray().apply { lapSplits.forEach { put(it.toJson()) } })
    put("routePoints", JSONArray().apply { routePoints.forEach { put(it.toJson()) } })
}

private fun JSONObject.toRunRecord(): RunRecord = RunRecord(
    id = getLong("id"),
    title = getString("title"),
    dateLabel = getString("dateLabel"),
    startedAtMillis = getLong("startedAtMillis"),
    goal = RunGoal.valueOf(getString("goal")),
    durationSeconds = getInt("durationSeconds"),
    distanceKm = getDouble("distanceKm").toFloat(),
    avgPaceMinPerKm = if (isNull("avgPaceMinPerKm")) null else getDouble("avgPaceMinPerKm").toFloat(),
    calories = getInt("calories"),
    elevationGainM = getInt("elevationGainM"),
    notes = getString("notes"),
    paceSeries = getJSONArray("paceSeries").toFloatList(),
    elevationSeries = getJSONArray("elevationSeries").toFloatList(),
    lapSplits = optJSONArray("lapSplits")?.toLapSplits().orEmpty(),
    routePoints = getJSONArray("routePoints").toLocationPoints(),
    hadGpsTracking = optBoolean("hadGpsTracking", true),
    hadHeartRateTracking = optBoolean("hadHeartRateTracking", true)
)

private fun LapSplit.toJson(): JSONObject = JSONObject().apply {
    put("index", index)
    put("elapsedSeconds", elapsedSeconds)
    put("durationSeconds", durationSeconds)
    put("distanceKm", distanceKm.toDouble())
    put("calories", calories)
    put("avgPaceMinPerKm", avgPaceMinPerKm?.toDouble())
}

private fun LocationPoint.toJson(): JSONObject = JSONObject().apply {
    put("timestampMillis", timestampMillis)
    put("latitude", latitude)
    put("longitude", longitude)
    put("altitudeMeters", altitudeMeters)
    put("accuracyMeters", accuracyMeters.toDouble())
    put("speedMetersPerSecond", speedMetersPerSecond.toDouble())
}

private fun JSONArray.toFloatList(): List<Float> = buildList {
    for (index in 0 until length()) add(getDouble(index).toFloat())
}

private fun JSONArray.toLocationPoints(): List<LocationPoint> = buildList {
    for (index in 0 until length()) {
        val item = getJSONObject(index)
        add(
            LocationPoint(
                timestampMillis = item.getLong("timestampMillis"),
                latitude = item.getDouble("latitude"),
                longitude = item.getDouble("longitude"),
                altitudeMeters = item.getDouble("altitudeMeters"),
                accuracyMeters = item.getDouble("accuracyMeters").toFloat(),
                speedMetersPerSecond = item.getDouble("speedMetersPerSecond").toFloat()
            )
        )
    }
}

private fun JSONArray.toLapSplits(): List<LapSplit> = buildList {
    for (index in 0 until length()) {
        val item = getJSONObject(index)
        add(
            LapSplit(
                index = item.getInt("index"),
                elapsedSeconds = item.getInt("elapsedSeconds"),
                durationSeconds = item.getInt("durationSeconds"),
                distanceKm = item.getDouble("distanceKm").toFloat(),
                calories = item.getInt("calories"),
                avgPaceMinPerKm = if (item.isNull("avgPaceMinPerKm")) null else item.getDouble("avgPaceMinPerKm").toFloat()
            )
        )
    }
}
