package com.example.justrun

enum class RunGoal {
    ENDLESS,
    DURATION,
    DISTANCE
}

enum class UnitSystem {
    SI,
    IMPERIAL
}

enum class LapMode {
    AUTOMATIC,
    MANUAL
}

enum class LapTrigger {
    DISTANCE,
    TIME
}

data class TrackingCapabilities(
    val gpsEnabled: Boolean,
    val heartRateEnabled: Boolean,
    val autoPauseEnabled: Boolean
)

fun availableRunGoals(gpsEnabled: Boolean): List<RunGoal> =
    if (gpsEnabled) RunGoal.entries else RunGoal.entries.filterNot { it == RunGoal.DISTANCE }

fun sanitizeRunGoal(selectedGoal: RunGoal, gpsEnabled: Boolean): RunGoal =
    if (!gpsEnabled && selectedGoal == RunGoal.DISTANCE) RunGoal.DURATION else selectedGoal

fun canStartRun(goal: RunGoal, gpsEnabled: Boolean): Boolean =
    goal != RunGoal.DISTANCE || gpsEnabled

fun sanitizeAutoPause(autoPauseEnabled: Boolean, gpsEnabled: Boolean): Boolean =
    gpsEnabled && autoPauseEnabled

fun sanitizeLapMode(lapMode: LapMode, gpsEnabled: Boolean): LapMode =
    if (!gpsEnabled) LapMode.MANUAL else lapMode

fun sanitizeLapTrigger(lapTrigger: LapTrigger, gpsEnabled: Boolean): LapTrigger =
    if (!gpsEnabled) LapTrigger.TIME else lapTrigger

fun sanitizeLapDistanceKm(lapDistanceKm: Float): Float =
    lapDistanceKm.coerceIn(0.1f, 160.9f)

fun sanitizeLapTimeSeconds(lapTimeSeconds: Int): Int =
    lapTimeSeconds.coerceIn(60, 24 * 3600)

fun sanitizeVoiceCueTimeIntervalSeconds(intervalSeconds: Int): Int =
    intervalSeconds.coerceIn(60, 24 * 3600)

fun sanitizeVoiceCueDistanceIntervalKm(distanceKm: Float): Float =
    distanceKm.coerceIn(0.1f, 160.9f)
