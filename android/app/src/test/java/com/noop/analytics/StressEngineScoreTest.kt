package com.noop.analytics

import com.noop.calibration.Baseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StressEngineScoreTest {
    @Test fun scoresLinearClip() {
        // one minute, hr at +1 sd -> 1.11554 + 0.10643*1 = 1.22197 -> MEDIUM
        val feats = StressEngine.Features(
            minutes = longArrayOf(0),
            hr = arrayOf<Double?>(70.0),
        )
        val series = StressEngine.scoreSeries(feats, hrBase = Baseline(60.0, 10.0))
        assertEquals(1, series.size)
        assertEquals(1.22197, series[0].level, 1e-9)
        assertEquals("MEDIUM", series[0].band)
    }

    @Test fun skipsLeadingNanHrMinutes() {
        // hr null at minute 0 -> EWMA NaN there -> minute skipped
        val feats = StressEngine.Features(
            minutes = longArrayOf(0, 60),
            hr = arrayOf<Double?>(null, 60.0),
        )
        val series = StressEngine.scoreSeries(feats, Baseline(60.0, 10.0))
        assertEquals(1, series.size)
        assertEquals(60L, series[0].ts)
        assertTrue(series[0].level in 0.0..3.0)
    }
}
