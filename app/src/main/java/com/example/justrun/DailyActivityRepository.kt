package com.example.justrun

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

class DailyActivityRepository(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("daily_activity", Context.MODE_PRIVATE)
    private var state = loadState().resetIfNotToday(todayKey())

    private val _snapshot = MutableStateFlow(state.snapshot)
    val snapshot: StateFlow<DailyActivitySnapshot> = _snapshot.asStateFlow()

    fun startMonitoring() {
        resetIfDayRolledOver()
    }

    fun mergeExternalSnapshot(snapshot: DailyActivitySyncPayload) {
        val currentDayKey = todayKey()
        val accepted = acceptWatchSnapshot(
            current = state,
            incoming = snapshot,
            currentDayKey = currentDayKey
        )
        if (accepted == state) {
            AppDiagnostics.log(
                "daily activity watch snapshot ignored day=${snapshot.dayKey} seq=${snapshot.sequenceNumber} updated=${snapshot.snapshotUpdatedAtMillis}"
            )
            return
        }
        state = accepted
        persistAndPublish()
        AppDiagnostics.log(
            "daily activity watch snapshot accepted day=${state.dayKey} seq=${state.sequenceNumber} steps=${state.snapshot.steps} calories=${"%.1f".format(state.snapshot.calories)}"
        )
    }

    private fun loadState(): DailyActivityState =
        DailyActivityState(
            dayKey = prefs.getString(KEY_DAY, todayKey()) ?: todayKey(),
            sequenceNumber = prefs.getLong(KEY_SEQUENCE_NUMBER, 0L),
            snapshot = DailyActivitySnapshot(
                dayKey = prefs.getString(KEY_SNAPSHOT_DAY, prefs.getString(KEY_DAY, todayKey()) ?: todayKey()) ?: todayKey(),
                steps = prefs.getLong(KEY_STEPS, 0L),
                calories = prefs.getFloat(KEY_CALORIES, 0f),
                heartRateBpm = prefs.getInt(KEY_HEART_RATE_BPM, -1).takeIf { it > 0 },
                updatedAtMillis = prefs.getLong(KEY_UPDATED_AT, 0L),
                heartRateUpdatedAtMillis = prefs.getLong(KEY_HEART_RATE_UPDATED_AT, 0L),
                sequenceNumber = prefs.getLong(KEY_SEQUENCE_NUMBER, 0L)
            )
        )

    private fun persistAndPublish() {
        prefs.edit()
            .putString(KEY_DAY, state.dayKey)
            .putString(KEY_SNAPSHOT_DAY, state.snapshot.dayKey)
            .putLong(KEY_SEQUENCE_NUMBER, state.sequenceNumber)
            .putLong(KEY_STEPS, state.snapshot.steps)
            .putFloat(KEY_CALORIES, state.snapshot.calories)
            .putInt(KEY_HEART_RATE_BPM, state.snapshot.heartRateBpm ?: -1)
            .putLong(KEY_HEART_RATE_UPDATED_AT, state.snapshot.heartRateUpdatedAtMillis)
            .putLong(KEY_UPDATED_AT, state.snapshot.updatedAtMillis)
            .apply()
        _snapshot.value = state.snapshot
        DailyActivityWidgetUpdater.updateAll(context)
    }

    private fun resetIfDayRolledOver(targetDayKey: String = todayKey()) {
        val reset = state.resetIfNotToday(targetDayKey)
        if (reset == state) return
        state = reset
        persistAndPublish()
    }

    private companion object {
        const val KEY_DAY = "day"
        const val KEY_SNAPSHOT_DAY = "snapshot_day"
        const val KEY_SEQUENCE_NUMBER = "sequence_number"
        const val KEY_STEPS = "watch_steps"
        const val KEY_CALORIES = "watch_calories"
        const val KEY_HEART_RATE_BPM = "watch_heart_rate_bpm"
        const val KEY_HEART_RATE_UPDATED_AT = "watch_heart_rate_updated_at"
        const val KEY_UPDATED_AT = "watch_updated_at"
    }
}

data class DailyActivitySnapshot(
    val dayKey: String = dayKey(System.currentTimeMillis()),
    val steps: Long = 0L,
    val calories: Float = 0f,
    val heartRateBpm: Int? = null,
    val updatedAtMillis: Long = 0L,
    val heartRateUpdatedAtMillis: Long = 0L,
    val sequenceNumber: Long = 0L
)

data class DailyActivitySyncPayload(
    val dayKey: String,
    val steps: Long,
    val stepsUpdatedAtMillis: Long,
    val calories: Float,
    val caloriesUpdatedAtMillis: Long,
    val heartRateBpm: Int?,
    val heartRateUpdatedAtMillis: Long,
    val snapshotUpdatedAtMillis: Long = maxOf(stepsUpdatedAtMillis, caloriesUpdatedAtMillis, heartRateUpdatedAtMillis),
    val sequenceNumber: Long = 0L
)

internal data class DailyActivityState(
    val dayKey: String,
    val sequenceNumber: Long = 0L,
    val snapshot: DailyActivitySnapshot = DailyActivitySnapshot(dayKey = dayKey)
) {
    fun resetIfNotToday(todayKey: String): DailyActivityState =
        if (dayKey == todayKey && snapshot.dayKey == todayKey) {
            this
        } else {
            DailyActivityState(dayKey = todayKey, snapshot = DailyActivitySnapshot(dayKey = todayKey))
        }
}

internal fun acceptWatchSnapshot(
    current: DailyActivityState,
    incoming: DailyActivitySyncPayload,
    currentDayKey: String
): DailyActivityState {
    if (incoming.dayKey != currentDayKey) return current.resetIfNotToday(currentDayKey)
    val normalizedCurrent = current.resetIfNotToday(currentDayKey)
    val incomingUpdatedAt = incoming.snapshotUpdatedAtMillis.takeIf { it > 0L } ?: maxOf(
        incoming.stepsUpdatedAtMillis,
        incoming.caloriesUpdatedAtMillis,
        incoming.heartRateUpdatedAtMillis
    )
    val incomingSequence = incoming.sequenceNumber
    val hasSequence = incomingSequence > 0L && normalizedCurrent.sequenceNumber > 0L
    val shouldAccept = when {
        hasSequence && incomingSequence > normalizedCurrent.sequenceNumber -> true
        hasSequence && incomingSequence <= normalizedCurrent.sequenceNumber -> false
        incomingUpdatedAt >= normalizedCurrent.snapshot.updatedAtMillis -> true
        else -> false
    }
    if (!shouldAccept) return normalizedCurrent
    return DailyActivityState(
        dayKey = currentDayKey,
        sequenceNumber = incomingSequence,
        snapshot = DailyActivitySnapshot(
            dayKey = currentDayKey,
            steps = incoming.steps.coerceAtLeast(0L),
            calories = incoming.calories.coerceAtLeast(0f),
            heartRateBpm = incoming.heartRateBpm,
            heartRateUpdatedAtMillis = incoming.heartRateUpdatedAtMillis,
            updatedAtMillis = incomingUpdatedAt,
            sequenceNumber = incomingSequence
        )
    )
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

private fun todayKey(): String = dayKey(System.currentTimeMillis())
