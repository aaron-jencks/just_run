package com.example.justrun.wear

import com.example.justrun.AppGraph
import com.example.justrun.DailyActivitySyncPayload
import android.content.Intent
import com.example.justrun.tracking.RunTrackingService
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearCommandListenerService : WearableListenerService() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.dataItem.uri.path != PATH_DAILY_HEALTH) return@forEach
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            AppGraph.dailyActivityRepository.mergeExternalSnapshot(
                DailyActivitySyncPayload(
                    dayKey = dataMap.getString(KEY_DAY_DATA).orEmpty(),
                    steps = dataMap.getLong(KEY_DAILY_STEPS_DATA, 0L),
                    stepsUpdatedAtMillis = dataMap.getLong(KEY_DAILY_STEPS_UPDATED_AT_DATA, 0L),
                    calories = dataMap.getFloat(KEY_DAILY_CALORIES_DATA, 0f),
                    caloriesUpdatedAtMillis = dataMap.getLong(KEY_DAILY_CALORIES_UPDATED_AT_DATA, 0L),
                    heartRateBpm = dataMap.getInt(KEY_HEART_RATE_DATA, -1).takeIf { it > 0 },
                    heartRateUpdatedAtMillis = dataMap.getLong(KEY_HEART_RATE_UPDATED_AT_DATA, 0L)
                )
            )
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearSyncManager.PATH_PAUSE -> AppGraph.trackingController.pauseRun()
            WearSyncManager.PATH_RESUME -> AppGraph.trackingController.resumeRun()
            WearSyncManager.PATH_STOP -> AppGraph.trackingController.stopRun()
            WearSyncManager.PATH_MARK_LAP -> AppGraph.trackingController.markLap()
            WearSyncManager.PATH_HEART_RATE -> {
                val bpm = messageEvent.data.toString(Charsets.UTF_8).toIntOrNull() ?: return
                startService(
                    Intent(this, RunTrackingService::class.java)
                        .setAction(RunTrackingService.ACTION_UPDATE_HEART_RATE)
                        .putExtra(RunTrackingService.EXTRA_HEART_RATE_BPM, bpm)
                )
            }
            else -> return
        }
    }

    private companion object {
        const val PATH_DAILY_HEALTH = "/daily_health"
        const val KEY_DAY_DATA = "day"
        const val KEY_DAILY_STEPS_DATA = "daily_steps"
        const val KEY_DAILY_STEPS_UPDATED_AT_DATA = "daily_steps_updated_at"
        const val KEY_DAILY_CALORIES_DATA = "daily_calories"
        const val KEY_DAILY_CALORIES_UPDATED_AT_DATA = "daily_calories_updated_at"
        const val KEY_HEART_RATE_DATA = "heart_rate_bpm"
        const val KEY_HEART_RATE_UPDATED_AT_DATA = "heart_rate_updated_at"
    }
}
