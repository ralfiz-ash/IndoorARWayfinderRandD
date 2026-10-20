package com.indoorar.wayfinder.domain

import com.indoorar.wayfinder.domain.accuracy.AccuracyCheckKind
import com.indoorar.wayfinder.domain.accuracy.AccuracyCsv
import com.indoorar.wayfinder.domain.accuracy.AccuracySample
import com.indoorar.wayfinder.domain.accuracy.AccuracyStats
import com.indoorar.wayfinder.domain.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccuracyStatsTest {
    private fun sample(name: String, mx: Double, mz: Double, est: Double = 0.3, kind: AccuracyCheckKind = AccuracyCheckKind.ABSOLUTE) =
        AccuracySample(0, kind, name, Vec3(0.0, -1.3, 5.0), Vec3(mx, -1.25, mz), est, "HIGH", 5.0, 90.0, 3.0, 4.0, 1, "s1")

    @Test
    fun errorsAndCalibrationFraction() {
        val s = sample("A", 0.3, 5.4)
        assertEquals(0.5, s.horizontalError, 1e-9)
        assertEquals(0.05, s.verticalError, 1e-9)
        assertEquals(false, s.withinEstimate)
        val summary = AccuracyStats.summarize(listOf(sample("A", 0.1, 5.0), sample("A", 0.0, 5.2), s, sample("A", 0.0, 4.0)))!!
        assertEquals(4, summary.count)
        assertEquals((0.1 + 0.2 + 0.5 + 1.0) / 4, summary.meanMeters, 1e-9)
        assertEquals(0.2, summary.medianMeters, 1e-9)
        assertEquals(1.0, summary.p90Meters, 1e-9)
        assertEquals(1.0, summary.maxMeters, 1e-9)
        assertEquals(0.5, summary.withinEstimateFraction, 1e-9)
        assertNull(AccuracyStats.summarize(emptyList()))
    }

    @Test
    fun spreadOfRepeatedMeasurementsPerReference() {
        val spreads = AccuracyStats.spreadByReference(
            listOf(sample("A", 0.0, 5.1), sample("A", 0.3, 5.5), sample("A", 0.0, 4.9), sample("B", 1.0, 5.0, kind = AccuracyCheckKind.REPEATABILITY)),
        )
        assertEquals(listOf("A", "B"), spreads.map { it.referenceName })
        assertEquals(kotlin.math.hypot(0.3, 0.6), spreads[0].maxSpreadMeters, 1e-9)
        assertEquals(0.0, spreads[1].maxSpreadMeters, 1e-9)
    }

    @Test
    fun csvRowMatchesHeader() {
        val row = AccuracyCsv.row(sample("Door \"A\"", 0.3, 5.4))
        assertTrue(row.contains("\"Door \"\"A\"\"\""))
        assertTrue(row.contains(",0.500,"))
        // Same number of columns (the quoted name contains no commas).
        assertEquals(AccuracyCsv.HEADER.split(",").size, row.split(",").size)
    }
}
