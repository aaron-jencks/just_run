package com.example.justrun

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.justrun.tracking.RunTrackingService

class TrackingController(
    private val context: Context,
    private val runRepository: RunRepository,
    private val settingsRepository: SettingsRepository
) {
    private val _trackingSession = MutableStateFlow(TrackingSession())
    val trackingSession: StateFlow<TrackingSession> = _trackingSession.asStateFlow()

    fun startRun(setup: RunSetupState, settings: SettingsState) {
        val intent = Intent(context, RunTrackingService::class.java).apply {
            action = RunTrackingService.ACTION_START
            putExtra(RunTrackingService.EXTRA_GOAL, setup.goal.name)
            putExtra(RunTrackingService.EXTRA_DISTANCE_GOAL_KM, setup.distanceKm)
            putExtra(RunTrackingService.EXTRA_DURATION_GOAL_SECONDS, setup.durationSeconds)
            putExtra(RunTrackingService.EXTRA_GPS_ENABLED, settings.gpsTrackingEnabled)
            putExtra(RunTrackingService.EXTRA_HR_ENABLED, settings.heartRateTrackingEnabled)
            putExtra(RunTrackingService.EXTRA_AUTO_PAUSE_ENABLED, settings.autoPause)
            putExtra(RunTrackingService.EXTRA_LAP_MODE, settings.lapMode.name)
            putExtra(RunTrackingService.EXTRA_LAP_TRIGGER, settings.lapTrigger.name)
            putExtra(RunTrackingService.EXTRA_LAP_DISTANCE_KM, settings.lapDistanceKm)
            putExtra(RunTrackingService.EXTRA_LAP_TIME_SECONDS, settings.lapTimeSeconds)
            putExtra(RunTrackingService.EXTRA_VOICE_CUES_ENABLED, settings.voiceCues)
            putExtra(RunTrackingService.EXTRA_VOICE_CUE_INTERVAL_TYPE, settings.voiceCueIntervalType.name)
            putExtra(RunTrackingService.EXTRA_VOICE_CUE_TIME_INTERVAL_SECONDS, settings.voiceCueTimeIntervalSeconds)
            putExtra(RunTrackingService.EXTRA_VOICE_CUE_DISTANCE_INTERVAL_KM, settings.voiceCueDistanceIntervalKm)
            putExtra(RunTrackingService.EXTRA_VOICE_CUE_LEAD_IN, settings.voiceCueLeadIn.name)
            putExtra(RunTrackingService.EXTRA_VOICE_CUE_METRIC_ORDER, settings.voiceCueMetricOrder.joinToString(",") { it.name })
            putExtra(RunTrackingService.EXTRA_VOICE_CUE_ENABLED_METRICS, settings.voiceCueEnabledMetrics.joinToString(",") { it.name })
            putExtra(RunTrackingService.EXTRA_UNIT_SYSTEM, settings.unitSystem.name)
            putExtra(RunTrackingService.EXTRA_WEIGHT_KG, settings.weightKg)
            putExtra(RunTrackingService.EXTRA_AGE_YEARS, settings.age)
        }
        context.startForegroundService(intent)
    }

    fun pauseRun() {
        context.startService(Intent(context, RunTrackingService::class.java).setAction(RunTrackingService.ACTION_PAUSE))
    }

    fun resumeRun() {
        context.startService(Intent(context, RunTrackingService::class.java).setAction(RunTrackingService.ACTION_RESUME))
    }

    fun stopRun() {
        context.startService(Intent(context, RunTrackingService::class.java).setAction(RunTrackingService.ACTION_STOP))
    }

    fun markLap() {
        context.startService(Intent(context, RunTrackingService::class.java).setAction(RunTrackingService.ACTION_MARK_LAP))
    }

    fun publish(session: TrackingSession) {
        _trackingSession.value = session
    }
}
