package com.example.justrun.wear

import android.content.Context
import com.example.justrun.ActiveRunState
import com.example.justrun.DailyActivitySyncPayload
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
import kotlinx.coroutines.delay
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
        settings: StateFlow<SettingsState>,
        dailyActivity: StateFlow<DailyActivitySyncPayload>
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
            scope.launch {
                var lastSettingsSnapshot: DailySettingsSnapshot? = null
                settings.collect { appSettings ->
                    val snapshot = DailySettingsSnapshot(
                        heartRateEnabled = appSettings.heartRateTrackingEnabled,
                        backgroundHeartMonitoringEnabled = appSettings.backgroundHeartMonitoringEnabled,
                        dailyStepGoal = appSettings.dailyStepGoal,
                        dailyCalorieGoal = appSettings.dailyCalorieGoal,
                        weightKg = appSettings.weightKg,
                        ageYears = appSettings.age
                    )
                    if (snapshot != lastSettingsSnapshot) {
                        lastSettingsSnapshot = snapshot
                        publishDailySettingsSnapshot(appContext, snapshot)
                    }
                }
            }
            scope.launch {
                requestDailyActivitySync(appContext, settings.value, dailyActivity.value)
                while (true) {
                    delay(60_000L)
                    requestDailyActivitySync(appContext, settings.value, dailyActivity.value)
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
            dataMap.putBoolean(KEY_BACKGROUND_HEART_MONITORING_ENABLED, snapshot.backgroundHeartMonitoringEnabled)
            dataMap.putInt(KEY_HEART_RATE_BPM, snapshot.heartRate ?: -1)
            dataMap.putString(KEY_GOAL_LABEL, snapshot.goalLabel)
            dataMap.putString(KEY_REMAINING_LABEL, snapshot.remainingLabel)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }

    private fun publishDailySettingsSnapshot(context: Context, snapshot: DailySettingsSnapshot) {
        val request = PutDataMapRequest.create(PATH_DAILY_ACTIVITY_SETTINGS).apply {
            dataMap.putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            dataMap.putBoolean(KEY_HEART_RATE_ENABLED, snapshot.heartRateEnabled)
            dataMap.putBoolean(KEY_BACKGROUND_HEART_MONITORING_ENABLED, snapshot.backgroundHeartMonitoringEnabled)
            dataMap.putInt(KEY_DAILY_STEP_GOAL, snapshot.dailyStepGoal)
            dataMap.putInt(KEY_DAILY_CALORIE_GOAL, snapshot.dailyCalorieGoal)
            dataMap.putFloat(KEY_WEIGHT_KG, snapshot.weightKg)
            dataMap.putFloat(KEY_AGE_YEARS, snapshot.ageYears)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }

    private fun requestDailyActivitySync(
        context: Context,
        settings: SettingsState,
        snapshot: DailyActivitySyncPayload
    ) {
        val request = PutDataMapRequest.create(PATH_DAILY_ACTIVITY_SYNC).apply {
            dataMap.putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            dataMap.putBoolean(KEY_HEART_RATE_ENABLED, settings.heartRateTrackingEnabled)
            dataMap.putBoolean(KEY_BACKGROUND_HEART_MONITORING_ENABLED, settings.backgroundHeartMonitoringEnabled)
            dataMap.putInt(KEY_DAILY_STEP_GOAL, settings.dailyStepGoal)
            dataMap.putInt(KEY_DAILY_CALORIE_GOAL, settings.dailyCalorieGoal)
            dataMap.putFloat(KEY_WEIGHT_KG, settings.weightKg)
            dataMap.putFloat(KEY_AGE_YEARS, settings.age)
            dataMap.putString(KEY_DAILY_DAY, snapshot.dayKey)
            dataMap.putLong(KEY_DAILY_STEPS, snapshot.steps)
            dataMap.putLong(KEY_DAILY_STEPS_UPDATED_AT, snapshot.stepsUpdatedAtMillis)
            dataMap.putFloat(KEY_DAILY_CALORIES, snapshot.calories)
            dataMap.putLong(KEY_DAILY_CALORIES_UPDATED_AT, snapshot.caloriesUpdatedAtMillis)
            dataMap.putInt(KEY_DAILY_HEART_RATE_BPM, snapshot.heartRateBpm ?: -1)
            dataMap.putLong(KEY_DAILY_HEART_RATE_UPDATED_AT, snapshot.heartRateUpdatedAtMillis)
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
            heartRateEnabled = settings.heartRateTrackingEnabled,
            backgroundHeartMonitoringEnabled = settings.backgroundHeartMonitoringEnabled,
            heartRate = run?.heartRate,
            unitSystem = settings.unitSystem,
            goalLabel = run?.let { buildGoalLabel(it, settings.unitSystem) }.orEmpty(),
            remainingLabel = run?.let { buildRemainingLabel(it, settings.unitSystem) }.orEmpty()
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
    const val PATH_DAILY_ACTIVITY_SYNC = "/daily_activity_sync"
    const val PATH_DAILY_ACTIVITY_SETTINGS = "/daily_activity_settings"
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
    const val KEY_BACKGROUND_HEART_MONITORING_ENABLED = "background_heart_monitoring_enabled"
    const val KEY_HEART_RATE_BPM = "heart_rate_bpm"
    const val KEY_GOAL_LABEL = "goal_label"
    const val KEY_REMAINING_LABEL = "remaining_label"
    const val KEY_DAILY_STEP_GOAL = "daily_step_goal"
    const val KEY_DAILY_CALORIE_GOAL = "daily_calorie_goal"
    const val KEY_DAILY_DAY = "daily_day"
    const val KEY_DAILY_STEPS = "daily_steps"
    const val KEY_DAILY_STEPS_UPDATED_AT = "daily_steps_updated_at"
    const val KEY_DAILY_CALORIES = "daily_calories"
    const val KEY_DAILY_CALORIES_UPDATED_AT = "daily_calories_updated_at"
    const val KEY_DAILY_HEART_RATE_BPM = "daily_heart_rate_bpm"
    const val KEY_DAILY_HEART_RATE_UPDATED_AT = "daily_heart_rate_updated_at"
    const val KEY_WEIGHT_KG = "weight_kg"
    const val KEY_AGE_YEARS = "age_years"
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
    val backgroundHeartMonitoringEnabled: Boolean,
    val heartRate: Int?,
    val unitSystem: UnitSystem,
    val goalLabel: String,
    val remainingLabel: String
)

private data class DailySettingsSnapshot(
    val heartRateEnabled: Boolean,
    val backgroundHeartMonitoringEnabled: Boolean,
    val dailyStepGoal: Int,
    val dailyCalorieGoal: Int,
    val weightKg: Float,
    val ageYears: Float
)
