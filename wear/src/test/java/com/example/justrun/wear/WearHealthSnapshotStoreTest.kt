package com.example.justrun.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class WearHealthSnapshotStoreTest {
    @Test
    fun `compact count formats small and large values cleanly`() {
        assertEquals("987", formatCompactCount(987f))
        assertEquals("1.5k", formatCompactCount(1534f))
        assertEquals("12k", formatCompactCount(12_340f))
    }

    @Test
    fun `daily goals default to ten thousand steps and two thousand calories`() {
        val snapshot = WearHealthSnapshot()
        assertEquals(5_000f, WearHealthSnapshotStore.stepGoal(snapshot))
        assertEquals(2_000f, WearHealthSnapshotStore.calorieGoal(snapshot))
    }

    @Test
    fun `background heart interval backs off when bpm is stable and resets on jumps`() {
        val tracker = BackgroundHeartRateCadenceTracker()

        assertEquals(5_000L, tracker.updateAndGetInterval(80))
        assertEquals(5_000L, tracker.updateAndGetInterval(81))
        assertEquals(10_000L, tracker.updateAndGetInterval(80))
        assertEquals(10_000L, tracker.updateAndGetInterval(81))
        assertEquals(20_000L, tracker.updateAndGetInterval(80))
        assertEquals(5_000L, tracker.updateAndGetInterval(89))
        assertEquals(10_000L, tracker.updateAndGetInterval(85))
    }
}
