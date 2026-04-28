package com.example.justrun

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class DailyActivityRepository(private val context: Context) : SensorEventListener {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("daily_activity", Context.MODE_PRIVATE)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var monitoringRegistered = false
    private var state = loadState()

    private val _snapshot = MutableStateFlow(state.resolved)
    val snapshot: StateFlow<DailyActivitySnapshot> = _snapshot.asStateFlow()

    private val _localSyncPayload = MutableStateFlow(state.toLocalSyncPayload())
    val localSyncPayload: StateFlow<DailyActivitySyncPayload> = _localSyncPayload.asStateFlow()

    fun startMonitoring() {
        if (monitoringRegistered) return
        resetIfDayRolledOver()
        if (!hasActivityRecognitionPermission() || stepCounterSensor == null) return
        sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_NORMAL)
        monitoringRegistered = true
    }

    fun mergeExternalSnapshot(snapshot: DailyActivitySyncPayload) {
        resetIfDayRolledOver(snapshot.dayKey)
        state = state.copy(
            remoteSteps = snapshot.steps,
            remoteStepsUpdatedAtMillis = snapshot.stepsUpdatedAtMillis,
            remoteCalories = snapshot.calories,
            remoteCaloriesUpdatedAtMillis = snapshot.caloriesUpdatedAtMillis,
            remoteHeartRateBpm = snapshot.heartRateBpm,
            remoteHeartRateUpdatedAtMillis = snapshot.heartRateUpdatedAtMillis
        )
        persistAndPublish()
    }

    fun updateDerivedCalories(calories: Float, nowMillis: Long = System.currentTimeMillis()) {
        resetIfDayRolledOver(dayKey(nowMillis))
        val normalizedCalories = calories.coerceAtLeast(0f)
        if (abs(state.localCalories - normalizedCalories) < 0.5f &&
            abs(nowMillis - state.localCaloriesUpdatedAtMillis) < 30_000L
        ) {
            return
        }
        state = state.copy(
            localCalories = normalizedCalories,
            localCaloriesUpdatedAtMillis = nowMillis
        )
        persistAndPublish()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER) return
        val counterValue = event.values.firstOrNull() ?: return
        updatePhoneStepCount(counterValue, System.currentTimeMillis())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    internal fun updatePhoneStepCount(counterValue: Float, nowMillis: Long) {
        resetIfDayRolledOver(dayKey(nowMillis))
        val baseline = when {
            shouldResetDailyStepBaseline(state.dayKey, state.dayKey, state.stepCounterBaseline, counterValue) -> {
                state = state.copy(stepCounterBaseline = counterValue)
                counterValue
            }
            else -> state.stepCounterBaseline
        }
        val stepsToday = calculateStepsToday(counterValue, baseline)
        state = state.copy(
            stepCounterBaseline = baseline,
            localSteps = stepsToday,
            localStepsUpdatedAtMillis = nowMillis
        )
        persistAndPublish()
    }

    private fun loadState(): DailyActivityState {
        val dayKey = prefs.getString(KEY_DAY, todayKey()) ?: todayKey()
        val loaded = DailyActivityState(
            dayKey = dayKey,
            localSteps = prefs.getLong(KEY_LOCAL_STEPS, 0L),
            localStepsUpdatedAtMillis = prefs.getLong(KEY_LOCAL_STEPS_UPDATED_AT, 0L),
            remoteSteps = prefs.getLong(KEY_REMOTE_STEPS, 0L),
            remoteStepsUpdatedAtMillis = prefs.getLong(KEY_REMOTE_STEPS_UPDATED_AT, 0L),
            localCalories = prefs.getFloat(KEY_LOCAL_CALORIES, 0f),
            localCaloriesUpdatedAtMillis = prefs.getLong(KEY_LOCAL_CALORIES_UPDATED_AT, 0L),
            remoteCalories = prefs.getFloat(KEY_REMOTE_CALORIES, 0f),
            remoteCaloriesUpdatedAtMillis = prefs.getLong(KEY_REMOTE_CALORIES_UPDATED_AT, 0L),
            remoteHeartRateBpm = prefs.getInt(KEY_REMOTE_HEART_RATE_BPM, -1).takeIf { it > 0 },
            remoteHeartRateUpdatedAtMillis = prefs.getLong(KEY_REMOTE_HEART_RATE_UPDATED_AT, 0L),
            stepCounterBaseline = prefs.getFloat(KEY_STEP_BASELINE, -1f)
        )
        return loaded.withResolved(previousResolved = DailyActivitySnapshot(
            steps = prefs.getLong(KEY_RESOLVED_STEPS, 0L),
            calories = prefs.getFloat(KEY_RESOLVED_CALORIES, 0f),
            heartRateBpm = prefs.getInt(KEY_RESOLVED_HEART_RATE_BPM, -1).takeIf { it > 0 },
            updatedAtMillis = prefs.getLong(KEY_RESOLVED_UPDATED_AT, 0L)
        ))
    }

    private fun persistAndPublish() {
        state = state.withResolved(previousResolved = state.resolved)
        prefs.edit()
            .putString(KEY_DAY, state.dayKey)
            .putLong(KEY_LOCAL_STEPS, state.localSteps)
            .putLong(KEY_LOCAL_STEPS_UPDATED_AT, state.localStepsUpdatedAtMillis)
            .putLong(KEY_REMOTE_STEPS, state.remoteSteps)
            .putLong(KEY_REMOTE_STEPS_UPDATED_AT, state.remoteStepsUpdatedAtMillis)
            .putFloat(KEY_LOCAL_CALORIES, state.localCalories)
            .putLong(KEY_LOCAL_CALORIES_UPDATED_AT, state.localCaloriesUpdatedAtMillis)
            .putFloat(KEY_REMOTE_CALORIES, state.remoteCalories)
            .putLong(KEY_REMOTE_CALORIES_UPDATED_AT, state.remoteCaloriesUpdatedAtMillis)
            .putInt(KEY_REMOTE_HEART_RATE_BPM, state.remoteHeartRateBpm ?: -1)
            .putLong(KEY_REMOTE_HEART_RATE_UPDATED_AT, state.remoteHeartRateUpdatedAtMillis)
            .putFloat(KEY_STEP_BASELINE, state.stepCounterBaseline)
            .putLong(KEY_RESOLVED_STEPS, state.resolved.steps)
            .putFloat(KEY_RESOLVED_CALORIES, state.resolved.calories)
            .putInt(KEY_RESOLVED_HEART_RATE_BPM, state.resolved.heartRateBpm ?: -1)
            .putLong(KEY_RESOLVED_UPDATED_AT, state.resolved.updatedAtMillis)
            .apply()
        _snapshot.value = state.resolved
        _localSyncPayload.value = state.toLocalSyncPayload()
        DailyActivityWidgetUpdater.updateAll(context)
    }

    private fun resetIfDayRolledOver(targetDayKey: String = todayKey()) {
        if (state.dayKey == targetDayKey) return
        state = DailyActivityState(
            dayKey = targetDayKey,
            stepCounterBaseline = -1f,
            resolved = DailyActivitySnapshot(updatedAtMillis = System.currentTimeMillis())
        )
        persistAndPublish()
    }

    private fun hasActivityRecognitionPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun todayKey(): String = dayKey(System.currentTimeMillis())

    private companion object {
        const val KEY_DAY = "day"
        const val KEY_LOCAL_STEPS = "local_steps"
        const val KEY_LOCAL_STEPS_UPDATED_AT = "local_steps_updated_at"
        const val KEY_REMOTE_STEPS = "remote_steps"
        const val KEY_REMOTE_STEPS_UPDATED_AT = "remote_steps_updated_at"
        const val KEY_LOCAL_CALORIES = "local_calories"
        const val KEY_LOCAL_CALORIES_UPDATED_AT = "local_calories_updated_at"
        const val KEY_REMOTE_CALORIES = "remote_calories"
        const val KEY_REMOTE_CALORIES_UPDATED_AT = "remote_calories_updated_at"
        const val KEY_REMOTE_HEART_RATE_BPM = "remote_heart_rate_bpm"
        const val KEY_REMOTE_HEART_RATE_UPDATED_AT = "remote_heart_rate_updated_at"
        const val KEY_STEP_BASELINE = "step_counter_baseline"
        const val KEY_RESOLVED_STEPS = "resolved_steps"
        const val KEY_RESOLVED_CALORIES = "resolved_calories"
        const val KEY_RESOLVED_HEART_RATE_BPM = "resolved_heart_rate_bpm"
        const val KEY_RESOLVED_UPDATED_AT = "resolved_updated_at"
    }
}

data class DailyActivitySnapshot(
    val steps: Long = 0L,
    val calories: Float = 0f,
    val heartRateBpm: Int? = null,
    val updatedAtMillis: Long = 0L
)

data class DailyActivitySyncPayload(
    val dayKey: String,
    val steps: Long,
    val stepsUpdatedAtMillis: Long,
    val calories: Float,
    val caloriesUpdatedAtMillis: Long,
    val heartRateBpm: Int?,
    val heartRateUpdatedAtMillis: Long
)

private data class DailyActivityState(
    val dayKey: String,
    val localSteps: Long = 0L,
    val localStepsUpdatedAtMillis: Long = 0L,
    val remoteSteps: Long = 0L,
    val remoteStepsUpdatedAtMillis: Long = 0L,
    val localCalories: Float = 0f,
    val localCaloriesUpdatedAtMillis: Long = 0L,
    val remoteCalories: Float = 0f,
    val remoteCaloriesUpdatedAtMillis: Long = 0L,
    val remoteHeartRateBpm: Int? = null,
    val remoteHeartRateUpdatedAtMillis: Long = 0L,
    val stepCounterBaseline: Float = -1f,
    val resolved: DailyActivitySnapshot = DailyActivitySnapshot()
) {
    fun toLocalSyncPayload(): DailyActivitySyncPayload =
        DailyActivitySyncPayload(
            dayKey = dayKey,
            steps = localSteps,
            stepsUpdatedAtMillis = localStepsUpdatedAtMillis,
            calories = localCalories,
            caloriesUpdatedAtMillis = localCaloriesUpdatedAtMillis,
            heartRateBpm = null,
            heartRateUpdatedAtMillis = 0L
        )

    fun withResolved(previousResolved: DailyActivitySnapshot): DailyActivityState {
        val resolvedSteps = resolveDailyMetric(
            localValue = localSteps,
            localUpdatedAtMillis = localStepsUpdatedAtMillis,
            remoteValue = remoteSteps,
            remoteUpdatedAtMillis = remoteStepsUpdatedAtMillis,
            preferRemoteWhenClose = true,
            previousResolved = previousResolved.steps
        )
        val resolvedCalories = resolveDailyMetric(
            localValue = localCalories,
            localUpdatedAtMillis = localCaloriesUpdatedAtMillis,
            remoteValue = remoteCalories,
            remoteUpdatedAtMillis = remoteCaloriesUpdatedAtMillis,
            preferRemoteWhenClose = true,
            previousResolved = previousResolved.calories
        )
        val resolvedHeartRate = resolveHeartRate(
            remoteHeartRateBpm = remoteHeartRateBpm,
            remoteHeartRateUpdatedAtMillis = remoteHeartRateUpdatedAtMillis
        )
        return copy(
            resolved = DailyActivitySnapshot(
                steps = resolvedSteps.value,
                calories = resolvedCalories.value,
                heartRateBpm = resolvedHeartRate.value,
                updatedAtMillis = maxOf(
                    resolvedSteps.updatedAtMillis,
                    resolvedCalories.updatedAtMillis,
                    resolvedHeartRate.updatedAtMillis
                )
            )
        )
    }
}

private data class ResolvedMetric<T>(
    val value: T,
    val updatedAtMillis: Long
)

private fun resolveHeartRate(
    remoteHeartRateBpm: Int?,
    remoteHeartRateUpdatedAtMillis: Long
): ResolvedMetric<Int?> =
    ResolvedMetric(remoteHeartRateBpm, remoteHeartRateUpdatedAtMillis)

private fun resolveDailyMetric(
    localValue: Long,
    localUpdatedAtMillis: Long,
    remoteValue: Long,
    remoteUpdatedAtMillis: Long,
    preferRemoteWhenClose: Boolean,
    previousResolved: Long
): ResolvedMetric<Long> {
    val chosen = chooseMetricCandidate(
        localValue = localValue,
        localUpdatedAtMillis = localUpdatedAtMillis,
        remoteValue = remoteValue,
        remoteUpdatedAtMillis = remoteUpdatedAtMillis,
        preferRemoteWhenClose = preferRemoteWhenClose
    )
    return ResolvedMetric(
        value = maxOf(previousResolved, chosen.value),
        updatedAtMillis = chosen.updatedAtMillis
    )
}

private fun resolveDailyMetric(
    localValue: Float,
    localUpdatedAtMillis: Long,
    remoteValue: Float,
    remoteUpdatedAtMillis: Long,
    preferRemoteWhenClose: Boolean,
    previousResolved: Float
): ResolvedMetric<Float> {
    val chosen = chooseMetricCandidate(
        localValue = localValue,
        localUpdatedAtMillis = localUpdatedAtMillis,
        remoteValue = remoteValue,
        remoteUpdatedAtMillis = remoteUpdatedAtMillis,
        preferRemoteWhenClose = preferRemoteWhenClose
    )
    return ResolvedMetric(
        value = maxOf(previousResolved, chosen.value),
        updatedAtMillis = chosen.updatedAtMillis
    )
}

private fun chooseMetricCandidate(
    localValue: Long,
    localUpdatedAtMillis: Long,
    remoteValue: Long,
    remoteUpdatedAtMillis: Long,
    preferRemoteWhenClose: Boolean
): ResolvedMetric<Long> {
    if (localUpdatedAtMillis <= 0L) return ResolvedMetric(remoteValue, remoteUpdatedAtMillis)
    if (remoteUpdatedAtMillis <= 0L) return ResolvedMetric(localValue, localUpdatedAtMillis)
    return if (abs(localUpdatedAtMillis - remoteUpdatedAtMillis) <= SIMILAR_FRESHNESS_MS) {
        if (preferRemoteWhenClose) ResolvedMetric(remoteValue, remoteUpdatedAtMillis)
        else ResolvedMetric(localValue, localUpdatedAtMillis)
    } else if (remoteUpdatedAtMillis > localUpdatedAtMillis) {
        ResolvedMetric(remoteValue, remoteUpdatedAtMillis)
    } else {
        ResolvedMetric(localValue, localUpdatedAtMillis)
    }
}

private fun chooseMetricCandidate(
    localValue: Float,
    localUpdatedAtMillis: Long,
    remoteValue: Float,
    remoteUpdatedAtMillis: Long,
    preferRemoteWhenClose: Boolean
): ResolvedMetric<Float> {
    if (localUpdatedAtMillis <= 0L) return ResolvedMetric(remoteValue, remoteUpdatedAtMillis)
    if (remoteUpdatedAtMillis <= 0L) return ResolvedMetric(localValue, localUpdatedAtMillis)
    return if (abs(localUpdatedAtMillis - remoteUpdatedAtMillis) <= SIMILAR_FRESHNESS_MS) {
        if (preferRemoteWhenClose) ResolvedMetric(remoteValue, remoteUpdatedAtMillis)
        else ResolvedMetric(localValue, localUpdatedAtMillis)
    } else if (remoteUpdatedAtMillis > localUpdatedAtMillis) {
        ResolvedMetric(remoteValue, remoteUpdatedAtMillis)
    } else {
        ResolvedMetric(localValue, localUpdatedAtMillis)
    }
}

internal fun shouldResetDailyStepBaseline(
    savedDayKey: String?,
    todayKey: String,
    savedBaseline: Float,
    counterValue: Float
): Boolean =
    savedDayKey != todayKey || savedBaseline < 0f || counterValue < savedBaseline

internal fun calculateStepsToday(counterValue: Float, baseline: Float): Long =
    (counterValue - baseline).coerceAtLeast(0f).roundToLong()

internal fun restingCaloriesForToday(
    weightKg: Float,
    nowMillis: Long
): Float {
    val secondsSinceMidnight = secondsSinceLocalMidnight(nowMillis)
    val caloriesPerSecond = (1.1f * 3.5f * weightKg / 200f) / 60f
    return caloriesPerSecond * secondsSinceMidnight
}

internal fun secondsSinceLocalMidnight(nowMillis: Long): Int {
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    return calendar.get(Calendar.HOUR_OF_DAY) * 3600 +
        calendar.get(Calendar.MINUTE) * 60 +
        calendar.get(Calendar.SECOND)
}

internal fun dayKey(timestampMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMillis))

private const val SIMILAR_FRESHNESS_MS = 90_000L
