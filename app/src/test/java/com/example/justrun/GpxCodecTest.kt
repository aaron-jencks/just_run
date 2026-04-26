package com.example.justrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class GpxCodecTest {
    @Test
    fun `import gpx builds a real run record`() {
        val gpx = """
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <name>Morning Run</name>
                <trkseg>
                  <trkpt lat="40.0000" lon="-74.0000">
                    <ele>10.0</ele>
                    <time>2026-04-23T10:00:00Z</time>
                  </trkpt>
                  <trkpt lat="40.0010" lon="-74.0010">
                    <ele>12.0</ele>
                    <time>2026-04-23T10:02:00Z</time>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val run = GpxCodec.importRun(ByteArrayInputStream(gpx.toByteArray()), "morning.gpx")

        assertEquals("Morning Run", run.title)
        assertEquals(2, run.routePoints.size)
        assertEquals(120, run.durationSeconds)
        assertTrue(run.distanceKm > 0f)
        assertTrue(run.hadGpsTracking)
    }

    @Test
    fun `exported gpx can be imported again`() {
        val run = RunRecord(
            id = 1L,
            title = "Round Trip",
            dateLabel = "Apr 23",
            startedAtMillis = 1_745_404_800_000L,
            goal = RunGoal.ENDLESS,
            durationSeconds = 120,
            distanceKm = 0.25f,
            avgPaceMinPerKm = 8f,
            calories = 25,
            elevationGainM = 4,
            notes = "Tracked on-device with precise GPS.",
            paceSeries = listOf(8f),
            elevationSeries = listOf(10f, 12f),
            lapSplits = emptyList(),
            routePoints = listOf(
                LocationPoint(1_745_404_800_000L, 40.0, -74.0, 10.0, 5f, 2f),
                LocationPoint(1_745_404_920_000L, 40.001, -74.001, 12.0, 5f, 2f)
            ),
            hadGpsTracking = true,
            hadHeartRateTracking = false
        )
        val output = ByteArrayOutputStream()

        GpxCodec.exportRun(run, output)
        val imported = GpxCodec.importRun(ByteArrayInputStream(output.toByteArray()), "round-trip.gpx")

        assertEquals("Round Trip", imported.title)
        assertEquals(2, imported.routePoints.size)
        assertEquals(120, imported.durationSeconds)
        assertTrue(output.toString().contains("<trkpt"))
    }
}
