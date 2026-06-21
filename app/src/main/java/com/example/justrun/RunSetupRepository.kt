package com.example.justrun

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RunSetupRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("run_setup", Context.MODE_PRIVATE)

    private val _setup = MutableStateFlow(load())
    val setup: StateFlow<RunSetupState> = _setup.asStateFlow()

    fun update(setup: RunSetupState) {
        val sanitized = sanitizeRunSetup(setup)
        prefs.edit()
            .putString(KEY_GOAL, sanitized.goal.name)
            .putInt(KEY_DURATION_SECONDS, sanitized.durationSeconds)
            .putFloat(KEY_DISTANCE_KM, sanitized.distanceKm)
            .apply()
        _setup.value = sanitized
    }

    private fun load(): RunSetupState {
        val defaults = RunSetupState()
        val goal = prefs.getString(KEY_GOAL, defaults.goal.name)
            ?.let { saved -> RunGoal.entries.firstOrNull { it.name == saved } }
            ?: defaults.goal
        return sanitizeRunSetup(
            RunSetupState(
                goal = goal,
                durationSeconds = prefs.getInt(KEY_DURATION_SECONDS, defaults.durationSeconds),
                distanceKm = prefs.getFloat(KEY_DISTANCE_KM, defaults.distanceKm)
            )
        )
    }

    private companion object {
        const val KEY_GOAL = "goal"
        const val KEY_DURATION_SECONDS = "duration_seconds"
        const val KEY_DISTANCE_KM = "distance_km"
    }
}

internal fun sanitizeRunSetup(setup: RunSetupState): RunSetupState = setup.copy(
    durationSeconds = setup.durationSeconds.coerceIn(0, MAX_RUN_DURATION_SECONDS),
    distanceKm = setup.distanceKm.takeIf { it.isFinite() }?.coerceIn(0f, MAX_RUN_DISTANCE_KM)
        ?: RunSetupState().distanceKm
)

internal const val MAX_RUN_DURATION_SECONDS = 24 * 60 * 60
internal const val MAX_RUN_DISTANCE_KM = 160.9f
