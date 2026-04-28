package com.example.justrun.wear

import com.example.justrun.UnitSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object WearSyncStore {
    private val _state = MutableStateFlow(WearRunState())
    val state: StateFlow<WearRunState> = _state.asStateFlow()

    fun publish(state: WearRunState) {
        _state.value = state
    }
}

data class WearRunState(
    val active: Boolean = false,
    val goal: String = "",
    val paused: Boolean = false,
    val autoPaused: Boolean = false,
    val elapsedSeconds: Int = 0,
    val distanceKm: Float = 0f,
    val avgPaceMinPerKm: Float? = null,
    val currentPaceMinPerKm: Float? = null,
    val calories: Int = 0,
    val lapCount: Int = 0,
    val gpsEnabled: Boolean = true,
    val heartRateEnabled: Boolean = false,
    val backgroundHeartMonitoringEnabled: Boolean = true,
    val heartRate: Int? = null,
    val unitSystem: UnitSystem = UnitSystem.SI,
    val goalLabel: String = "",
    val remainingLabel: String = ""
)
