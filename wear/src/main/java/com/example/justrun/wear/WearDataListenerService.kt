package com.example.justrun.wear

import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.justrun.UnitSystem
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearDataListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.dataItem.uri.path != PATH_LIVE_RUN) return@forEach
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            WearSyncStore.publish(
                WearRunState(
                    active = dataMap.getBoolean(KEY_ACTIVE, false),
                    goal = dataMap.getString(KEY_GOAL).orEmpty(),
                    paused = dataMap.getBoolean(KEY_PAUSED, false),
                    autoPaused = dataMap.getBoolean(KEY_AUTO_PAUSED, false),
                    elapsedSeconds = dataMap.getInt(KEY_ELAPSED_SECONDS, 0),
                    distanceKm = dataMap.getFloat(KEY_DISTANCE_KM, 0f),
                    avgPaceMinPerKm = dataMap.getFloat(KEY_AVG_PACE_MIN_PER_KM, -1f).takeIf { it >= 0f },
                    currentPaceMinPerKm = dataMap.getFloat(KEY_CURRENT_PACE_MIN_PER_KM, -1f).takeIf { it >= 0f },
                    calories = dataMap.getInt(KEY_CALORIES, 0),
                    lapCount = dataMap.getInt(KEY_LAP_COUNT, 0),
                    gpsEnabled = dataMap.getBoolean(KEY_GPS_ENABLED, true),
                    heartRateEnabled = dataMap.getBoolean(KEY_HEART_RATE_ENABLED, false),
                    heartRate = dataMap.getInt(KEY_HEART_RATE_BPM, -1).takeIf { it >= 0 },
                    unitSystem = dataMap.getString(KEY_UNIT_SYSTEM)?.let(UnitSystem::valueOf) ?: UnitSystem.SI,
                    goalLabel = dataMap.getString(KEY_GOAL_LABEL).orEmpty(),
                    remainingLabel = dataMap.getString(KEY_REMAINING_LABEL).orEmpty()
                )
            )
            WearHealthSnapshotStore.updateGoals(
                context = applicationContext,
                dailyStepGoal = dataMap.getInt(KEY_DAILY_STEP_GOAL, 5_000),
                dailyCalorieGoal = dataMap.getInt(KEY_DAILY_CALORIE_GOAL, 2_000)
            )
            syncHeartRateService()
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != PATH_OPEN_LIVE_RUN) return
        val intent = Intent(this, WearMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        ContextCompat.startActivity(this, intent, null)
    }

    private fun syncHeartRateService() {
        val state = WearSyncStore.state.value
        if (state.active && state.heartRateEnabled && !state.paused && !state.autoPaused) {
            ContextCompat.startForegroundService(this, Intent(this, WearHeartRateService::class.java))
        } else {
            stopService(Intent(this, WearHeartRateService::class.java))
        }
    }
}

const val PATH_LIVE_RUN = "/live_run"
const val PATH_OPEN_LIVE_RUN = "/open_live_run"
const val PATH_PAUSE = "/pause"
const val PATH_RESUME = "/resume"
const val PATH_STOP = "/stop"
const val PATH_MARK_LAP = "/mark_lap"
const val PATH_HEART_RATE = "/heart_rate"

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
