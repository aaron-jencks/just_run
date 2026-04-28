package com.example.justrun

import com.example.justrun.wear.WearSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSyncManagerTest {

    @Test
    fun `build watch sync state mirrors active run and unit preferences`() {
        val session = TrackingSession(
            activeRun = ActiveRunState(
                goal = RunGoal.DISTANCE,
                targetDistanceKm = 10f,
                targetDurationSeconds = null,
                startedAtMillis = 123L,
                elapsedSeconds = 900,
                distanceKm = 5f,
                avgPaceMinPerKm = 6f,
                currentPaceMinPerKm = 5.5f,
                calories = 120,
                heartRate = null,
                cadence = 0,
                elevationGainM = 0,
                lapSplits = listOf(
                    LapSplit(index = 1, elapsedSeconds = 900, durationSeconds = 900, distanceKm = 5f, calories = 120, avgPaceMinPerKm = 6f)
                ),
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
        )
        val settings = SettingsState(unitSystem = UnitSystem.IMPERIAL)
        val snapshot = WearSyncManager.buildWatchSyncState(session, settings)

        assertTrue(snapshot.active)
        assertEquals(123L, snapshot.startedAtMillis)
        assertEquals(UnitSystem.IMPERIAL, snapshot.unitSystem)
        assertEquals(1, snapshot.lapCount)
        assertEquals("6.2 mi", snapshot.goalLabel)
        assertEquals("3.1 mi", snapshot.remainingLabel)
    }

    @Test
    fun `build watch sync state clears values when no active run exists`() {
        val snapshot = WearSyncManager.buildWatchSyncState(TrackingSession(), SettingsState())

        assertFalse(snapshot.active)
        assertEquals(0, snapshot.elapsedSeconds)
        assertEquals("", snapshot.goalLabel)
    }
}
