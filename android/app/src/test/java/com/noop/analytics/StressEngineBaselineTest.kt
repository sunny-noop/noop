package com.noop.analytics

import com.noop.calibration.Calibration
import org.junit.Assert.assertEquals
import org.junit.Test

class StressEngineBaselineTest {
    @Test fun maturyTrainedHrBaselineFromNightSummaries() {
        val hrByNight = listOf(1L to 60.0, 2L to 62.0, 3L to 58.0, 4L to 61.0, 5L to 59.0)
        val hr = StressEngine.resolveBaselines(hrByNight, modelKey = "whoop5")
        assertEquals(Calibration.BaselineSource.TRAINED, hr.source)
        assertEquals(60.0, hr.baseline.centre, 1e-9)   // median of the 5
    }

    @Test fun immatureFallsBackToColdStart() {
        val hr = StressEngine.resolveBaselines(
            hrByNight = listOf(1L to 60.0, 2L to 62.0),   // 2 nights < seed 4
            modelKey = "whoop5",
        )
        assertEquals(Calibration.BaselineSource.COLD_START, hr.source)
        assertEquals(78.0, hr.baseline.centre, 1e-9)      // whoop5 cold-start hr centre
    }
}
