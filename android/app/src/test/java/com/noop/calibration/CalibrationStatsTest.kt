package com.noop.calibration

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationStatsTest {
    @Test fun standardize_isZScore() {
        val b = Baseline(centre = 60.0, scale = 10.0)
        assertEquals(0.0, Calibration.standardize(60.0, b), 1e-9)
        assertEquals(1.0, Calibration.standardize(70.0, b), 1e-9)
        assertEquals(-2.0, Calibration.standardize(40.0, b), 1e-9)
    }

    @Test fun bandOf_cutPoints() {
        val cuts = listOf(0.9, 1.9)
        val labels = listOf("LOW", "MEDIUM", "HIGH")
        assertEquals("LOW", Calibration.bandOf(0.0, cuts, labels))
        assertEquals("LOW", Calibration.bandOf(0.89, cuts, labels))
        assertEquals("MEDIUM", Calibration.bandOf(0.9, cuts, labels))
        assertEquals("MEDIUM", Calibration.bandOf(1.89, cuts, labels))
        assertEquals("HIGH", Calibration.bandOf(1.9, cuts, labels))
        assertEquals("HIGH", Calibration.bandOf(3.0, cuts, labels))
    }

    @Test fun bandOf_isLabelAgnostic() {
        val cuts = listOf(70.0, 85.0)
        val labels = listOf("POOR", "FAIR", "GOOD")
        assertEquals("POOR", Calibration.bandOf(50.0, cuts, labels))
        assertEquals("GOOD", Calibration.bandOf(90.0, cuts, labels))
    }
}
