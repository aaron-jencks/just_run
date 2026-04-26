package com.example.justrun

import com.example.justrun.tracking.shouldTriggerTargetReachedCue
import com.example.justrun.tracking.shouldTriggerTurnAroundCue
import com.example.justrun.tracking.caloriesPerSecond
import com.example.justrun.tracking.estimatedMetFromHeartRate
import com.example.justrun.tracking.buildVoiceCueMetricReport
import com.example.justrun.tracking.buildProgressCueText
import com.example.justrun.tracking.calculateCadenceSpm
import com.example.justrun.tracking.isMovementSample
import com.example.justrun.tracking.ProgressCueEvent
import com.example.justrun.DailyActivityWidgetUpdater.progressCycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunConfigTest {

    @Test
    fun `distance run is unavailable when gps tracking is off`() {
        val availableGoals = availableRunGoals(gpsEnabled = false)

        assertFalse(availableGoals.contains(RunGoal.DISTANCE))
        assertEquals(listOf(RunGoal.ENDLESS, RunGoal.DURATION), availableGoals)
        assertFalse(canStartRun(RunGoal.DISTANCE, gpsEnabled = false))
    }

    @Test
    fun `distance run remains available when gps tracking is on`() {
        val availableGoals = availableRunGoals(gpsEnabled = true)

        assertEquals(RunGoal.entries.toList(), availableGoals)
        assertTrue(canStartRun(RunGoal.DISTANCE, gpsEnabled = true))
    }

    @Test
    fun `distance selection is sanitized when gps gets disabled`() {
        assertEquals(RunGoal.DURATION, sanitizeRunGoal(RunGoal.DISTANCE, gpsEnabled = false))
        assertEquals(RunGoal.ENDLESS, sanitizeRunGoal(RunGoal.ENDLESS, gpsEnabled = false))
    }

    @Test
    fun `auto pause is disabled when gps tracking is disabled`() {
        assertFalse(sanitizeAutoPause(autoPauseEnabled = true, gpsEnabled = false))
        assertFalse(sanitizeAutoPause(autoPauseEnabled = false, gpsEnabled = false))
        assertTrue(sanitizeAutoPause(autoPauseEnabled = true, gpsEnabled = true))
    }

    @Test
    fun `automatic lap mode is disabled when gps tracking is disabled`() {
        assertEquals(LapMode.MANUAL, sanitizeLapMode(LapMode.AUTOMATIC, gpsEnabled = false))
        assertEquals(LapMode.MANUAL, sanitizeLapMode(LapMode.MANUAL, gpsEnabled = false))
        assertEquals(LapMode.AUTOMATIC, sanitizeLapMode(LapMode.AUTOMATIC, gpsEnabled = true))
    }

    @Test
    fun `lap intervals are clamped into safe bounds`() {
        assertEquals(0.1f, sanitizeLapDistanceKm(0.01f))
        assertEquals(160.9f, sanitizeLapDistanceKm(300f))
        assertEquals(60, sanitizeLapTimeSeconds(10))
        assertEquals(24 * 3600, sanitizeLapTimeSeconds(999999))
        assertEquals(60, sanitizeVoiceCueTimeIntervalSeconds(10))
        assertEquals(24 * 3600, sanitizeVoiceCueTimeIntervalSeconds(999999))
        assertEquals(0.1f, sanitizeVoiceCueDistanceIntervalKm(0.01f))
        assertEquals(160.9f, sanitizeVoiceCueDistanceIntervalKm(300f))
    }

    @Test
    fun `distance formatting follows selected unit system`() {
        assertEquals("10.0 km", formatDistance(10f, UnitSystem.SI))
        assertEquals("6.2 mi", formatDistance(10f, UnitSystem.IMPERIAL))
    }

    @Test
    fun `pace formatting follows selected unit system`() {
        assertEquals("5:30 /km", formatPace(5.5f, UnitSystem.SI))
        assertEquals("8:51 /mi", formatPace(5.5f, UnitSystem.IMPERIAL))
        assertEquals("∞ /mi", formatPace(40f, UnitSystem.IMPERIAL))
        assertEquals("--:-- /mi", formatPace(null, UnitSystem.IMPERIAL))
    }

    @Test
    fun `elevation and body metrics formatting follows selected unit system`() {
        assertEquals("86 m", formatElevation(86, UnitSystem.SI))
        assertEquals("282 ft", formatElevation(86, UnitSystem.IMPERIAL))
        assertEquals("72 kg", formatWeight(72f, UnitSystem.SI))
        assertEquals("158 lb", formatWeight(72f, UnitSystem.IMPERIAL))
        assertEquals("178 cm", formatHeight(178f, UnitSystem.SI))
        assertEquals("5 ft 10 in", formatHeight(178f, UnitSystem.IMPERIAL))
    }

    @Test
    fun `duration formatting stays zero padded`() {
        assertEquals("00:00:00", formatDurationHms(0))
        assertEquals("01:01:01", formatDurationHms(3661))
        assertEquals("24:00:00", formatDurationHms(24 * 3600))
    }

    @Test
    fun `voice cue metric report follows requested order`() {
        val run = ActiveRunState(
            goal = RunGoal.DURATION,
            targetDistanceKm = null,
            targetDurationSeconds = 1800,
            startedAtMillis = 0L,
            elapsedSeconds = 600,
            distanceKm = 2f,
            avgPaceMinPerKm = 5.5f,
            currentPaceMinPerKm = 5.3f,
            calories = 0,
            heartRate = null,
            cadence = 0,
            elevationGainM = 0,
            lapSplits = emptyList(),
            lapMode = LapMode.MANUAL,
            lapTrigger = LapTrigger.TIME,
            lapDistanceKm = null,
            lapTimeSeconds = null,
            paused = false,
            autoPaused = false,
            gpsEnabled = true,
            heartRateEnabled = false,
            routePoints = emptyList()
        )

        val report = buildVoiceCueMetricReport(
            run,
            metricOrder = listOf(VoiceCueMetric.TOTAL_DISTANCE, VoiceCueMetric.ELAPSED_TIME),
            unitSystem = UnitSystem.IMPERIAL
        )

        assertTrue(report.startsWith("Total distance"))
        assertTrue(report.contains("Elapsed time"))
    }

    @Test
    fun `voice cue report omits disabled metrics when order is filtered`() {
        val run = ActiveRunState(
            goal = RunGoal.DURATION,
            targetDistanceKm = null,
            targetDurationSeconds = 1800,
            startedAtMillis = 0L,
            elapsedSeconds = 600,
            distanceKm = 2f,
            avgPaceMinPerKm = 5.5f,
            currentPaceMinPerKm = 5.3f,
            calories = 0,
            heartRate = null,
            cadence = 0,
            elevationGainM = 0,
            lapSplits = emptyList(),
            lapMode = LapMode.MANUAL,
            lapTrigger = LapTrigger.TIME,
            lapDistanceKm = null,
            lapTimeSeconds = null,
            paused = false,
            autoPaused = false,
            gpsEnabled = true,
            heartRateEnabled = false,
            routePoints = emptyList()
        )

        val report = buildVoiceCueMetricReport(
            run,
            metricOrder = listOf(VoiceCueMetric.ELAPSED_TIME, VoiceCueMetric.AVERAGE_PACE),
            unitSystem = UnitSystem.SI
        )

        assertTrue(report.contains("Elapsed time"))
        assertTrue(report.contains("Average pace"))
        assertFalse(report.contains("Remaining time"))
        assertFalse(report.contains("Total distance"))
    }

    @Test
    fun `progress cue can use lap trigger with distance completed lead in`() {
        val run = ActiveRunState(
            goal = RunGoal.DISTANCE,
            targetDistanceKm = 5f,
            targetDurationSeconds = null,
            startedAtMillis = 0L,
            elapsedSeconds = 900,
            distanceKm = 3.21868f,
            avgPaceMinPerKm = 5.5f,
            currentPaceMinPerKm = 5.3f,
            calories = 0,
            heartRate = null,
            cadence = 0,
            elevationGainM = 0,
            lapSplits = emptyList(),
            lapMode = LapMode.AUTOMATIC,
            lapTrigger = LapTrigger.DISTANCE,
            lapDistanceKm = 1.60934f,
            lapTimeSeconds = null,
            paused = false,
            autoPaused = false,
            gpsEnabled = true,
            heartRateEnabled = false,
            routePoints = emptyList()
        )

        val cue = buildProgressCueText(
            run = run,
            event = ProgressCueEvent(
                trigger = VoiceCueIntervalType.LAP,
                lapNumber = 2,
                elapsedSeconds = run.elapsedSeconds,
                distanceKm = run.distanceKm
            ),
            leadIn = ProgressCueLeadIn.DISTANCE_COMPLETED,
            metricOrder = listOf(VoiceCueMetric.AVERAGE_PACE),
            unitSystem = UnitSystem.IMPERIAL
        )

        assertTrue(cue.startsWith("2.0 miles completed"))
        assertTrue(cue.contains("Average pace"))
        assertFalse(cue.contains("Lap 2 completed"))
    }

    @Test
    fun `lap distance metric only speaks for lap-triggered progress cues`() {
        val run = ActiveRunState(
            goal = RunGoal.DISTANCE,
            targetDistanceKm = 5f,
            targetDurationSeconds = null,
            startedAtMillis = 0L,
            elapsedSeconds = 900,
            distanceKm = 3.21868f,
            avgPaceMinPerKm = 5.5f,
            currentPaceMinPerKm = 5.3f,
            calories = 0,
            heartRate = null,
            cadence = 0,
            elevationGainM = 0,
            lapSplits = emptyList(),
            lapMode = LapMode.AUTOMATIC,
            lapTrigger = LapTrigger.DISTANCE,
            lapDistanceKm = 1.60934f,
            lapTimeSeconds = null,
            paused = false,
            autoPaused = false,
            gpsEnabled = true,
            heartRateEnabled = false,
            routePoints = emptyList()
        )

        val lapReport = buildVoiceCueMetricReport(
            run,
            event = ProgressCueEvent(
                trigger = VoiceCueIntervalType.LAP,
                lapNumber = 2,
                elapsedSeconds = run.elapsedSeconds,
                distanceKm = run.distanceKm,
                lapDistanceKm = 1.60934f
            ),
            metricOrder = listOf(VoiceCueMetric.LAP_DISTANCE),
            unitSystem = UnitSystem.IMPERIAL
        )
        val distanceReport = buildVoiceCueMetricReport(
            run,
            event = ProgressCueEvent(
                trigger = VoiceCueIntervalType.DISTANCE,
                elapsedSeconds = run.elapsedSeconds,
                distanceKm = run.distanceKm
            ),
            metricOrder = listOf(VoiceCueMetric.LAP_DISTANCE),
            unitSystem = UnitSystem.IMPERIAL
        )

        assertTrue(lapReport.contains("Lap distance"))
        assertTrue(lapReport.contains("1.0 miles"))
        assertEquals("", distanceReport)
    }

    @Test
    fun `cadence uses recent step window and drops to zero when stale`() {
        val now = 20_000L
        val cadence = calculateCadenceSpm(
            nowMillis = now,
            stepTimestampsMillis = listOf(1_000L, 5_000L, 10_000L, 15_000L, 19_000L)
        )
        val staleCadence = calculateCadenceSpm(
            nowMillis = 60_000L,
            stepTimestampsMillis = listOf(1_000L, 5_000L, 10_000L)
        )

        assertTrue(cadence > 0)
        assertEquals(0, staleCadence)
    }

    @Test
    fun `heart rate raises calorie burn estimate during active runs`() {
        val lowEffort = caloriesPerSecond(
            weightKg = 72f,
            ageYears = 31f,
            heartRateBpm = 110,
            currentPaceMinPerKm = 6.0f,
            gpsEnabled = true
        )
        val highEffort = caloriesPerSecond(
            weightKg = 72f,
            ageYears = 31f,
            heartRateBpm = 165,
            currentPaceMinPerKm = 6.0f,
            gpsEnabled = true
        )

        assertTrue(highEffort > lowEffort)
    }

    @Test
    fun `heart rate met estimate stays within a sane running range`() {
        val met = estimatedMetFromHeartRate(
            heartRateBpm = 150,
            ageYears = 31f,
            gpsEnabled = true
        )

        assertTrue(met != null && met in 4f..14f)
    }

    @Test
    fun `missing heart rate falls back to the pace based calorie estimate`() {
        val withoutHeartRate = caloriesPerSecond(
            weightKg = 72f,
            ageYears = 31f,
            heartRateBpm = null,
            currentPaceMinPerKm = 6.0f,
            gpsEnabled = true
        )
        val explicitFallback = (8.3f * 3.5f * 72f / 200f) / 60f

        assertEquals(explicitFallback, withoutHeartRate, 0.0001f)
    }

    @Test
    fun `widget progress wraps after the goal and fills on exact multiples`() {
        assertEquals(500, progressCycle(totalValue = 7_500f, goalValue = 5_000f))
        assertEquals(1000, progressCycle(totalValue = 5_000f, goalValue = 5_000f))
        assertEquals(0, progressCycle(totalValue = 0f, goalValue = 5_000f))
    }

    @Test
    fun `summary pace series prefers route data over coarse lap data`() {
        val run = RunRecord(
            id = 1L,
            title = "Test",
            dateLabel = "Apr 22",
            startedAtMillis = 0L,
            goal = RunGoal.DISTANCE,
            durationSeconds = 3600,
            distanceKm = 10f,
            avgPaceMinPerKm = 6f,
            calories = 500,
            elevationGainM = 50,
            notes = "",
            paceSeries = listOf(99f),
            elevationSeries = emptyList(),
            lapSplits = listOf(
                LapSplit(index = 1, elapsedSeconds = 600, durationSeconds = 600, distanceKm = 1.6f, calories = 80, avgPaceMinPerKm = 6.1f),
                LapSplit(index = 2, elapsedSeconds = 1200, durationSeconds = 600, distanceKm = 1.6f, calories = 78, avgPaceMinPerKm = 6.0f)
            ),
            routePoints = listOf(
                LocationPoint(0L, 40.0, -74.0, 10.0, 0f, 0f),
                LocationPoint(60_000L, 40.001, -74.0, 12.0, 0f, 0f)
            ),
            hadGpsTracking = true,
            hadHeartRateTracking = false
        )

        assertEquals(1, buildSummaryPaceSeries(run).size)
        assertTrue(buildSummaryPaceSeries(run).first() > 0f)
    }

    @Test
    fun `summary pace series falls back to persisted pace series when no lap data exists`() {
        val run = RunRecord(
            id = 1L,
            title = "Test",
            dateLabel = "Apr 22",
            startedAtMillis = 0L,
            goal = RunGoal.DISTANCE,
            durationSeconds = 3600,
            distanceKm = 10f,
            avgPaceMinPerKm = 6f,
            calories = 500,
            elevationGainM = 50,
            notes = "",
            paceSeries = listOf(5.9f, 6.2f),
            elevationSeries = emptyList(),
            lapSplits = emptyList(),
            routePoints = emptyList(),
            hadGpsTracking = true,
            hadHeartRateTracking = false
        )

        assertEquals(listOf(5.9f, 6.2f), buildSummaryPaceSeries(run))
    }

    @Test
    fun `summary elevation series prefers route points over truncated stored series`() {
        val run = RunRecord(
            id = 1L,
            title = "Test",
            dateLabel = "Apr 22",
            startedAtMillis = 0L,
            goal = RunGoal.DISTANCE,
            durationSeconds = 3600,
            distanceKm = 10f,
            avgPaceMinPerKm = 6f,
            calories = 500,
            elevationGainM = 50,
            notes = "",
            paceSeries = emptyList(),
            elevationSeries = listOf(1f, 2f),
            lapSplits = emptyList(),
            routePoints = listOf(
                LocationPoint(0L, 40.0, -74.0, 10.0, 0f, 0f),
                LocationPoint(1_000L, 40.001, -74.0, 12.0, 0f, 0f),
                LocationPoint(2_000L, 40.002, -74.0, 14.0, 0f, 0f)
            ),
            hadGpsTracking = true,
            hadHeartRateTracking = false
        )

        assertEquals(listOf(10f, 12f, 14f), buildSummaryElevationSeries(run))
    }

    @Test
    fun `turn around cue triggers halfway through duration run`() {
        val run = ActiveRunState(
            goal = RunGoal.DURATION,
            targetDistanceKm = null,
            targetDurationSeconds = 1800,
            startedAtMillis = 0L,
            elapsedSeconds = 900,
            distanceKm = 0f,
            avgPaceMinPerKm = null,
            currentPaceMinPerKm = null,
            calories = 0,
            heartRate = null,
            cadence = 0,
            elevationGainM = 0,
            lapSplits = emptyList(),
            lapMode = LapMode.MANUAL,
            lapTrigger = LapTrigger.TIME,
            lapDistanceKm = null,
            lapTimeSeconds = null,
            paused = false,
            autoPaused = false,
            gpsEnabled = false,
            heartRateEnabled = false,
            routePoints = emptyList()
        )

        assertTrue(shouldTriggerTurnAroundCue(run))
    }

    @Test
    fun `turn around cue triggers halfway through distance run`() {
        val run = ActiveRunState(
            goal = RunGoal.DISTANCE,
            targetDistanceKm = 10f,
            targetDurationSeconds = null,
            startedAtMillis = 0L,
            elapsedSeconds = 0,
            distanceKm = 5f,
            avgPaceMinPerKm = null,
            currentPaceMinPerKm = null,
            calories = 0,
            heartRate = null,
            cadence = 0,
            elevationGainM = 0,
            lapSplits = emptyList(),
            lapMode = LapMode.AUTOMATIC,
            lapTrigger = LapTrigger.DISTANCE,
            lapDistanceKm = 1.60934f,
            lapTimeSeconds = null,
            paused = false,
            autoPaused = false,
            gpsEnabled = true,
            heartRateEnabled = false,
            routePoints = emptyList()
        )

        assertTrue(shouldTriggerTurnAroundCue(run))
    }

    @Test
    fun `turn around cue never triggers for endless run`() {
        val run = ActiveRunState(
            goal = RunGoal.ENDLESS,
            targetDistanceKm = null,
            targetDurationSeconds = null,
            startedAtMillis = 0L,
            elapsedSeconds = 7200,
            distanceKm = 20f,
            avgPaceMinPerKm = null,
            currentPaceMinPerKm = null,
            calories = 0,
            heartRate = null,
            cadence = 0,
            elevationGainM = 0,
            lapSplits = emptyList(),
            lapMode = LapMode.AUTOMATIC,
            lapTrigger = LapTrigger.DISTANCE,
            lapDistanceKm = 1.60934f,
            lapTimeSeconds = null,
            paused = false,
            autoPaused = false,
            gpsEnabled = true,
            heartRateEnabled = false,
            routePoints = emptyList()
        )

        assertFalse(shouldTriggerTurnAroundCue(run))
    }

    @Test
    fun `target reached cue triggers at duration goal`() {
        val run = ActiveRunState(
            goal = RunGoal.DURATION,
            targetDistanceKm = null,
            targetDurationSeconds = 1800,
            startedAtMillis = 0L,
            elapsedSeconds = 1800,
            distanceKm = 0f,
            avgPaceMinPerKm = null,
            currentPaceMinPerKm = null,
            calories = 0,
            heartRate = null,
            cadence = 0,
            elevationGainM = 0,
            lapSplits = emptyList(),
            lapMode = LapMode.MANUAL,
            lapTrigger = LapTrigger.TIME,
            lapDistanceKm = null,
            lapTimeSeconds = null,
            paused = false,
            autoPaused = false,
            gpsEnabled = false,
            heartRateEnabled = false,
            routePoints = emptyList()
        )

        assertTrue(shouldTriggerTargetReachedCue(run))
    }

    @Test
    fun `target reached cue triggers at distance goal`() {
        val run = ActiveRunState(
            goal = RunGoal.DISTANCE,
            targetDistanceKm = 10f,
            targetDurationSeconds = null,
            startedAtMillis = 0L,
            elapsedSeconds = 0,
            distanceKm = 10f,
            avgPaceMinPerKm = null,
            currentPaceMinPerKm = null,
            calories = 0,
            heartRate = null,
            cadence = 0,
            elevationGainM = 0,
            lapSplits = emptyList(),
            lapMode = LapMode.AUTOMATIC,
            lapTrigger = LapTrigger.DISTANCE,
            lapDistanceKm = 1.60934f,
            lapTimeSeconds = null,
            paused = false,
            autoPaused = false,
            gpsEnabled = true,
            heartRateEnabled = false,
            routePoints = emptyList()
        )

        assertTrue(shouldTriggerTargetReachedCue(run))
    }

    @Test
    fun `target reached cue never triggers for endless run`() {
        val run = ActiveRunState(
            goal = RunGoal.ENDLESS,
            targetDistanceKm = null,
            targetDurationSeconds = null,
            startedAtMillis = 0L,
            elapsedSeconds = 9999,
            distanceKm = 42f,
            avgPaceMinPerKm = null,
            currentPaceMinPerKm = null,
            calories = 0,
            heartRate = null,
            cadence = 0,
            elevationGainM = 0,
            lapSplits = emptyList(),
            lapMode = LapMode.AUTOMATIC,
            lapTrigger = LapTrigger.DISTANCE,
            lapDistanceKm = 1.60934f,
            lapTimeSeconds = null,
            paused = false,
            autoPaused = false,
            gpsEnabled = true,
            heartRateEnabled = false,
            routePoints = emptyList()
        )

        assertFalse(shouldTriggerTargetReachedCue(run))
    }

    @Test
    fun `movement sample stays active during slow but continuous running`() {
        val previous = LocationPoint(
            timestampMillis = 0L,
            latitude = 40.0,
            longitude = -74.0,
            altitudeMeters = 10.0,
            accuracyMeters = 5f,
            speedMetersPerSecond = 0f
        )
        val candidate = LocationPoint(
            timestampMillis = 3_000L,
            latitude = 40.00004,
            longitude = -74.00002,
            altitudeMeters = 10.0,
            accuracyMeters = 5f,
            speedMetersPerSecond = 1.4f
        )

        assertTrue(isMovementSample(candidate, previous))
    }
}
