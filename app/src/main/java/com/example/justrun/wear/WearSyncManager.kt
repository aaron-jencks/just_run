package com.example.justrun.wear

import android.content.Context
import com.example.justrun.ActiveRunState
import com.example.justrun.SettingsState
import com.example.justrun.TrackingSession
import com.example.justrun.UnitSystem
import com.example.justrun.formatDistance
import com.example.justrun.formatDurationHms
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

object WearSyncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var started = false
    private var lastOpenSignalRunId: Long? = null

    fun start(
        context: Context,
        trackingSession: StateFlow<TrackingSession>,
        settings: StateFlow<SettingsState>
    ) {
        if (started) return
        synchronized(this) {
            if (started) return
            val appContext = context.applicationContext
            scope.launch {
                combine(trackingSession, settings) { session, appSettings ->
                    buildWatchSyncState(session, appSettings)
                }.collect { snapshot ->
                    publishSnapshot(appContext, snapshot)
                    maybeOpenWatch(appContext, snapshot)
                }
            }
            started = true
        }
    }

    private fun publishSnapshot(context: Context, snapshot: WatchSyncState) {
        val request = PutDataMapRequest.create(PATH_LIVE_RUN).apply {
            dataMap.putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            dataMap.putBoolean(KEY_ACTIVE, snapshot.active)
            dataMap.putString(KEY_UNIT_SYSTEM, snapshot.unitSystem.name)
            dataMap.putString(KEY_GOAL, snapshot.goal)
            dataMap.putBoolean(KEY_PAUSED, snapshot.paused)
            dataMap.putBoolean(KEY_AUTO_PAUSED, snapshot.autoPaused)
            dataMap.putInt(KEY_ELAPSED_SECONDS, snapshot.elapsedSeconds)
            dataMap.putFloat(KEY_DISTANCE_KM, snapshot.distanceKm)
            dataMap.putFloat(KEY_AVG_PACE_MIN_PER_KM, snapshot.avgPaceMinPerKm ?: -1f)
            dataMap.putFloat(KEY_CURRENT_PACE_MIN_PER_KM, snapshot.currentPaceMinPerKm ?: -1f)
            dataMap.putInt(KEY_CALORIES, snapshot.calories)
            dataMap.putInt(KEY_LAP_COUNT, snapshot.lapCount)
            dataMap.putBoolean(KEY_GPS_ENABLED, snapshot.gpsEnabled)
            dataMap.putBoolean(KEY_HEART_RATE_ENABLED, snapshot.heartRateEnabled)
            dataMap.putInt(KEY_HEART_RATE_BPM, snapshot.heartRate ?: -1)
            dataMap.putString(KEY_GOAL_LABEL, snapshot.goalLabel)
            dataMap.putString(KEY_REMAINING_LABEL, snapshot.remainingLabel)
            dataMap.putInt(KEY_DAILY_STEP_GOAL, snapshot.dailyStepGoal)
            dataMap.putInt(KEY_DAILY_CALORIE_GOAL, snapshot.dailyCalorieGoal)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }

    private fun maybeOpenWatch(context: Context, snapshot: WatchSyncState) {
        if (!snapshot.active) {
            lastOpenSignalRunId = null
            return
        }
        val runId = snapshot.startedAtMillis
        if (lastOpenSignalRunId == runId) return
        lastOpenSignalRunId = runId
        scope.launch {
            val nodes = runCatching { Tasks.await(Wearable.getNodeClient(context).connectedNodes) }.getOrDefault(emptyList())
            nodes.forEach { node ->
                runCatching {
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, PATH_OPEN_LIVE_RUN, ByteArray(0)))
                }
            }
        }
    }

    internal fun buildWatchSyncState(
        session: TrackingSession,
        settings: SettingsState
    ): WatchSyncState {
        val run = session.activeRun
        return WatchSyncState(
            active = run != null,
            startedAtMillis = run?.startedAtMillis ?: 0L,
            goal = run?.goal?.name.orEmpty(),
            paused = run?.paused == true,
            autoPaused = run?.autoPaused == true,
            elapsedSeconds = run?.elapsedSeconds ?: 0,
            distanceKm = run?.distanceKm ?: 0f,
            avgPaceMinPerKm = run?.avgPaceMinPerKm,
            currentPaceMinPerKm = run?.currentPaceMinPerKm,
            calories = run?.calories ?: 0,
            lapCount = run?.lapSplits?.size ?: 0,
            gpsEnabled = run?.gpsEnabled == true,
            heartRateEnabled = run?.heartRateEnabled == true,
            heartRate = run?.heartRate,
            unitSystem = settings.unitSystem,
            goalLabel = run?.let { buildGoalLabel(it, settings.unitSystem) }.orEmpty(),
            remainingLabel = run?.let { buildRemainingLabel(it, settings.unitSystem) }.orEmpty(),
            dailyStepGoal = settings.dailyStepGoal,
            dailyCalorieGoal = settings.dailyCalorieGoal
        )
    }

    private fun buildGoalLabel(run: ActiveRunState, unitSystem: UnitSystem): String = when (run.goal) {
        com.example.justrun.RunGoal.ENDLESS -> "Open run"
        com.example.justrun.RunGoal.DURATION -> formatDurationHms(run.targetDurationSeconds ?: 0)
        com.example.justrun.RunGoal.DISTANCE -> formatDistance(run.targetDistanceKm ?: 0f, unitSystem)
    }

    private fun buildRemainingLabel(run: ActiveRunState, unitSystem: UnitSystem): String = when (run.goal) {
        com.example.justrun.RunGoal.ENDLESS -> "No finish target"
        com.example.justrun.RunGoal.DURATION -> {
            val remaining = ((run.targetDurationSeconds ?: 0) - run.elapsedSeconds).coerceAtLeast(0)
            formatDurationHms(remaining)
        }
        com.example.justrun.RunGoal.DISTANCE -> {
            val remaining = ((run.targetDistanceKm ?: 0f) - run.distanceKm).coerceAtLeast(0f)
            formatDistance(remaining, unitSystem)
        }
    }

    const val PATH_LIVE_RUN = "/live_run"
    const val PATH_OPEN_LIVE_RUN = "/open_live_run"
    const val PATH_PAUSE = "/pause"
    const val PATH_RESUME = "/resume"
    const val PATH_STOP = "/stop"
    const val PATH_MARK_LAP = "/mark_lap"
    const val PATH_HEART_RATE = "/heart_rate"

    const val KEY_UPDATED_AT = "updated_at"
    const val KEY_ACTIVE = "active"
    const val KEY_UNIT_SYSTEM = "unit_system"
    const val KEY_GOAL = "goal"
    const val KEY_PAUSED = "paused"
    const val KEY_AUTO_PAUSED = "auto_paused"
    const val KEY_ELAPSED_SECONDS = "elapsed_seconds"
    const val KEY_DISTANCE_KM = "distance_km"
    const val KEY_AVG_PACE_MIN_PER_KM = "avg_pace_min_per_km"
    const val KEY_CURRENT_PACE_MIN_PER_KM = "current_pace_min_per_km"
    const val KEY_CALORIES = "calories"
    const val KEY_LAP_COUNT = "lap_count"
    const val KEY_GPS_ENABLED = "gps_enabled"
    const val KEY_HEART_RATE_ENABLED = "heart_rate_enabled"
    const val KEY_HEART_RATE_BPM = "heart_rate_bpm"
    const val KEY_GOAL_LABEL = "goal_label"
    const val KEY_REMAINING_LABEL = "remaining_label"
    const val KEY_DAILY_STEP_GOAL = "daily_step_goal"
    const val KEY_DAILY_CALORIE_GOAL = "daily_calorie_goal"
}

internal data class WatchSyncState(
    val active: Boolean,
    val startedAtMillis: Long,
    val goal: String,
    val paused: Boolean,
    val autoPaused: Boolean,
    val elapsedSeconds: Int,
    val distanceKm: Float,
    val avgPaceMinPerKm: Float?,
    val currentPaceMinPerKm: Float?,
    val calories: Int,
    val lapCount: Int,
    val gpsEnabled: Boolean,
    val heartRateEnabled: Boolean,
    val heartRate: Int?,
    val unitSystem: UnitSystem,
    val goalLabel: String,
    val remainingLabel: String,
    val dailyStepGoal: Int,
    val dailyCalorieGoal: Int
)
