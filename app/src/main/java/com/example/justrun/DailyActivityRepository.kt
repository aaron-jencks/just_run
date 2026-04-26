package com.example.justrun

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DailyActivityRepository(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("daily_activity", Context.MODE_PRIVATE)

    private val _snapshot = MutableStateFlow(load())
    val snapshot: StateFlow<DailyActivitySnapshot> = _snapshot.asStateFlow()

    fun update(snapshot: DailyActivitySnapshot) {
        prefs.edit()
            .putLong(KEY_STEPS, snapshot.steps)
            .putFloat(KEY_CALORIES, snapshot.calories)
            .putInt(KEY_HEART_RATE_BPM, snapshot.heartRateBpm ?: -1)
            .putLong(KEY_UPDATED_AT, snapshot.updatedAtMillis)
            .apply()
        _snapshot.value = snapshot
        DailyActivityWidgetUpdater.updateAll(context)
    }

    private fun load(): DailyActivitySnapshot =
        DailyActivitySnapshot(
            steps = prefs.getLong(KEY_STEPS, 0L),
            calories = prefs.getFloat(KEY_CALORIES, 0f),
            heartRateBpm = prefs.getInt(KEY_HEART_RATE_BPM, -1).takeIf { it > 0 },
            updatedAtMillis = prefs.getLong(KEY_UPDATED_AT, 0L)
        )

    private companion object {
        const val KEY_STEPS = "steps"
        const val KEY_CALORIES = "calories"
        const val KEY_HEART_RATE_BPM = "heart_rate_bpm"
        const val KEY_UPDATED_AT = "updated_at"
    }
}

data class DailyActivitySnapshot(
    val steps: Long = 0L,
    val calories: Float = 0f,
    val heartRateBpm: Int? = null,
    val updatedAtMillis: Long = 0L
)
