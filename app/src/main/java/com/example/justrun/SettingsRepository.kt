package com.example.justrun

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    fun update(settings: SettingsState) {
        prefs.edit()
            .putFloat(KEY_WEIGHT, settings.weightKg)
            .putFloat(KEY_HEIGHT, settings.heightCm)
            .putFloat(KEY_AGE, settings.age)
            .putString(KEY_UNIT_SYSTEM, settings.unitSystem.name)
            .putBoolean(KEY_GPS, settings.gpsTrackingEnabled)
            .putBoolean(KEY_HR, settings.heartRateTrackingEnabled)
            .putBoolean(KEY_BACKGROUND_HR, settings.backgroundHeartMonitoringEnabled)
            .putBoolean(KEY_AUTO_PAUSE, settings.autoPause)
            .putString(KEY_LAP_MODE, settings.lapMode.name)
            .putString(KEY_LAP_TRIGGER, settings.lapTrigger.name)
            .putFloat(KEY_LAP_DISTANCE, settings.lapDistanceKm)
            .putInt(KEY_LAP_TIME, settings.lapTimeSeconds)
            .putBoolean(KEY_VOICE, settings.voiceCues)
            .putString(KEY_VOICE_INTERVAL_TYPE, settings.voiceCueIntervalType.name)
            .putInt(KEY_VOICE_TIME_INTERVAL, settings.voiceCueTimeIntervalSeconds)
            .putFloat(KEY_VOICE_DISTANCE_INTERVAL, settings.voiceCueDistanceIntervalKm)
            .putString(KEY_VOICE_CUE_LEAD_IN, settings.voiceCueLeadIn.name)
            .putString(KEY_VOICE_METRIC_ORDER, settings.voiceCueMetricOrder.joinToString(",") { it.name })
            .putString(KEY_VOICE_ENABLED_METRICS, settings.voiceCueEnabledMetrics.joinToString(",") { it.name })
            .putInt(KEY_DAILY_STEP_GOAL, settings.dailyStepGoal)
            .putInt(KEY_DAILY_CALORIE_GOAL, settings.dailyCalorieGoal)
            .putBoolean(KEY_WATCH, settings.watchMirroring)
            .apply()
        _settings.value = settings
        DailyActivityWidgetUpdater.updateAll(appContext)
    }

    private fun load(): SettingsState {
        val gpsEnabled = prefs.getBoolean(KEY_GPS, true)
        val savedVoiceCueMetricOrder = prefs.getString(KEY_VOICE_METRIC_ORDER, null)
            ?.split(',')
            ?.mapNotNull { raw -> VoiceCueMetric.entries.firstOrNull { it.name == raw } }
            .orEmpty()
        val normalizedVoiceCueMetricOrder = normalizeVoiceCueMetricOrder(savedVoiceCueMetricOrder)
        val savedEnabledVoiceCueMetrics = prefs.getString(KEY_VOICE_ENABLED_METRICS, null)
            ?.split(',')
            ?.mapNotNull { raw -> VoiceCueMetric.entries.firstOrNull { it.name == raw } }
            .orEmpty()
        val normalizedEnabledVoiceCueMetrics = normalizeEnabledVoiceCueMetrics(
            savedMetrics = savedEnabledVoiceCueMetrics,
            normalizedOrder = normalizedVoiceCueMetricOrder
        )
        return SettingsState(
            weightKg = prefs.getFloat(KEY_WEIGHT, 72f),
            heightCm = prefs.getFloat(KEY_HEIGHT, 178f),
            age = prefs.getFloat(KEY_AGE, 31f),
            unitSystem = prefs.getString(KEY_UNIT_SYSTEM, UnitSystem.SI.name)?.let(UnitSystem::valueOf)
                ?: UnitSystem.SI,
            gpsTrackingEnabled = gpsEnabled,
            heartRateTrackingEnabled = prefs.getBoolean(KEY_HR, true),
            backgroundHeartMonitoringEnabled = prefs.getBoolean(KEY_BACKGROUND_HR, true),
            autoPause = sanitizeAutoPause(prefs.getBoolean(KEY_AUTO_PAUSE, true), gpsEnabled),
            lapMode = sanitizeLapMode(
                prefs.getString(KEY_LAP_MODE, LapMode.AUTOMATIC.name)?.let(LapMode::valueOf) ?: LapMode.AUTOMATIC,
                gpsEnabled
            ),
            lapTrigger = sanitizeLapTrigger(
                prefs.getString(KEY_LAP_TRIGGER, LapTrigger.DISTANCE.name)?.let(LapTrigger::valueOf) ?: LapTrigger.DISTANCE,
                gpsEnabled
            ),
            lapDistanceKm = sanitizeLapDistanceKm(prefs.getFloat(KEY_LAP_DISTANCE, 1.60934f)),
            lapTimeSeconds = sanitizeLapTimeSeconds(prefs.getInt(KEY_LAP_TIME, 10 * 60)),
            voiceCues = prefs.getBoolean(KEY_VOICE, true),
            voiceCueIntervalType = prefs.getString(KEY_VOICE_INTERVAL_TYPE, VoiceCueIntervalType.DISTANCE.name)
                ?.let(VoiceCueIntervalType::valueOf)
                ?: VoiceCueIntervalType.DISTANCE,
            voiceCueTimeIntervalSeconds = sanitizeVoiceCueTimeIntervalSeconds(prefs.getInt(KEY_VOICE_TIME_INTERVAL, 5 * 60)),
            voiceCueDistanceIntervalKm = sanitizeVoiceCueDistanceIntervalKm(prefs.getFloat(KEY_VOICE_DISTANCE_INTERVAL, 1f)),
            voiceCueLeadIn = prefs.getString(KEY_VOICE_CUE_LEAD_IN, ProgressCueLeadIn.DISTANCE_COMPLETED.name)
                ?.let(ProgressCueLeadIn::valueOf)
                ?: ProgressCueLeadIn.DISTANCE_COMPLETED,
            voiceCueMetricOrder = normalizedVoiceCueMetricOrder,
            voiceCueEnabledMetrics = normalizedEnabledVoiceCueMetrics,
            dailyStepGoal = prefs.getInt(KEY_DAILY_STEP_GOAL, 5_000),
            dailyCalorieGoal = prefs.getInt(KEY_DAILY_CALORIE_GOAL, 2_000),
            watchMirroring = prefs.getBoolean(KEY_WATCH, true)
        )
    }

    private fun normalizeVoiceCueMetricOrder(savedMetrics: List<VoiceCueMetric>): List<VoiceCueMetric> {
        val uniqueSavedMetrics = savedMetrics.distinct()
        return if (uniqueSavedMetrics.isEmpty()) {
            VoiceCueMetric.entries
        } else {
            uniqueSavedMetrics + VoiceCueMetric.entries.filterNot { it in uniqueSavedMetrics }
        }
    }

    private fun normalizeEnabledVoiceCueMetrics(
        savedMetrics: List<VoiceCueMetric>,
        normalizedOrder: List<VoiceCueMetric>
    ): List<VoiceCueMetric> =
        if (savedMetrics.isEmpty()) {
            normalizedOrder
        } else {
            savedMetrics.distinct().filter { it in normalizedOrder }
        }

    private companion object {
        const val KEY_WEIGHT = "weight"
        const val KEY_HEIGHT = "height"
        const val KEY_AGE = "age"
        const val KEY_UNIT_SYSTEM = "unit_system"
        const val KEY_GPS = "gps"
        const val KEY_HR = "heart_rate"
        const val KEY_BACKGROUND_HR = "background_heart_rate"
        const val KEY_AUTO_PAUSE = "auto_pause"
        const val KEY_LAP_MODE = "lap_mode"
        const val KEY_LAP_TRIGGER = "lap_trigger"
        const val KEY_LAP_DISTANCE = "lap_distance"
        const val KEY_LAP_TIME = "lap_time"
        const val KEY_VOICE = "voice"
        const val KEY_VOICE_INTERVAL_TYPE = "voice_interval_type"
        const val KEY_VOICE_TIME_INTERVAL = "voice_time_interval"
        const val KEY_VOICE_DISTANCE_INTERVAL = "voice_distance_interval"
        const val KEY_VOICE_CUE_LEAD_IN = "voice_cue_lead_in"
        const val KEY_VOICE_METRIC_ORDER = "voice_metric_order"
        const val KEY_VOICE_ENABLED_METRICS = "voice_enabled_metrics"
        const val KEY_DAILY_STEP_GOAL = "daily_step_goal"
        const val KEY_DAILY_CALORIE_GOAL = "daily_calorie_goal"
        const val KEY_WATCH = "watch"
    }
}
