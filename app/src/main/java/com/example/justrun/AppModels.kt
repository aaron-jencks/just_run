package com.example.justrun

data class SettingsState(
    val weightKg: Float = 72f,
    val heightCm: Float = 178f,
    val age: Float = 31f,
    val unitSystem: UnitSystem = UnitSystem.SI,
    val gpsTrackingEnabled: Boolean = true,
    val heartRateTrackingEnabled: Boolean = true,
    val backgroundHeartMonitoringEnabled: Boolean = true,
    val autoPause: Boolean = true,
    val lapMode: LapMode = LapMode.AUTOMATIC,
    val lapTrigger: LapTrigger = LapTrigger.DISTANCE,
    val lapDistanceKm: Float = 1.60934f,
    val lapTimeSeconds: Int = 10 * 60,
    val voiceCues: Boolean = true,
    val voiceCueIntervalType: VoiceCueIntervalType = VoiceCueIntervalType.DISTANCE,
    val voiceCueTimeIntervalSeconds: Int = 5 * 60,
    val voiceCueDistanceIntervalKm: Float = 1f,
    val voiceCueLeadIn: ProgressCueLeadIn = ProgressCueLeadIn.DISTANCE_COMPLETED,
    val voiceCueMetricOrder: List<VoiceCueMetric> = VoiceCueMetric.entries,
    val voiceCueEnabledMetrics: List<VoiceCueMetric> = VoiceCueMetric.entries,
    val dailyStepGoal: Int = 5_000,
    val dailyCalorieGoal: Int = 2_000,
    val watchMirroring: Boolean = true
)

enum class VoiceCueIntervalType {
    TIME,
    DISTANCE,
    LAP
}

enum class ProgressCueLeadIn {
    NONE,
    LAP_NUMBER,
    DISTANCE_COMPLETED,
    TIME_COMPLETED
}

enum class VoiceCueMetric {
    ELAPSED_TIME,
    REMAINING_TIME,
    AVERAGE_PACE,
    TOTAL_DISTANCE,
    LAP_DISTANCE
}

data class LapSplit(
    val index: Int,
    val elapsedSeconds: Int,
    val durationSeconds: Int,
    val distanceKm: Float,
    val calories: Int,
    val avgPaceMinPerKm: Float?
)

data class LocationPoint(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float
)

data class RunRecord(
    val id: Long,
    val title: String,
    val dateLabel: String,
    val startedAtMillis: Long,
    val goal: RunGoal,
    val durationSeconds: Int,
    val distanceKm: Float,
    val avgPaceMinPerKm: Float?,
    val calories: Int,
    val elevationGainM: Int,
    val notes: String,
    val paceSeries: List<Float>,
    val elevationSeries: List<Float>,
    val lapSplits: List<LapSplit>,
    val routePoints: List<LocationPoint>,
    val hadGpsTracking: Boolean,
    val hadHeartRateTracking: Boolean
)

data class ActiveRunState(
    val goal: RunGoal,
    val targetDistanceKm: Float?,
    val targetDurationSeconds: Int?,
    val startedAtMillis: Long,
    val elapsedSeconds: Int,
    val distanceKm: Float,
    val avgPaceMinPerKm: Float?,
    val currentPaceMinPerKm: Float?,
    val calories: Int,
    val heartRate: Int?,
    val cadence: Int,
    val elevationGainM: Int,
    val lapSplits: List<LapSplit>,
    val lapMode: LapMode,
    val lapTrigger: LapTrigger,
    val lapDistanceKm: Float?,
    val lapTimeSeconds: Int?,
    val paused: Boolean,
    val autoPaused: Boolean,
    val gpsEnabled: Boolean,
    val heartRateEnabled: Boolean,
    val routePoints: List<LocationPoint>
)

data class RunSetupState(
    val goal: RunGoal = RunGoal.DISTANCE,
    val durationSeconds: Int = 45 * 60,
    val distanceKm: Float = 10f
)

data class TrackingSession(
    val activeRun: ActiveRunState? = null,
    val permissionRequired: Boolean = false,
    val completedRunId: Long? = null
)
