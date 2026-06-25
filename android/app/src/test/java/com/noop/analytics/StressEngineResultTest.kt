package com.noop.analytics

import com.noop.calibration.Baseline
import com.noop.calibration.Calibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StressEngineResultTest {
    private fun flatDay(startMin: Long, n: Int, bpm: Double) = StressEngine.Features(
        minutes = LongArray(n) { (startMin + it) * 60 },
        hr = Array(n) { bpm as Double? },
    )

    @Test fun reducesToDayMeanBandsAndTrend() {
        val today = flatDay(0, 10, 90.0)                 // +3 sd hr -> elevated (MEDIUM)
        val history = listOf(
            "2026-06-21" to flatDay(0, 10, 60.0),        // baseline hr -> ~b0 (MEDIUM)
            "2026-06-22" to flatDay(0, 10, 90.0),
        )
        val res = StressEngine.analyze(
            today = today,
            historyByDay = history,
            hr = StressEngine.ResolvedBaseline(Baseline(60.0, 10.0), Calibration.BaselineSource.TRAINED, 7),
        )
        assertTrue(res.series.isNotEmpty())
        assertEquals(res.series.map { it.level }.average(), res.dayScore, 1e-9)
        assertEquals(res.series.size, res.bandMinutes.values.sum())
        assertEquals(2, res.trend.size)
        assertEquals("2026-06-22", res.trend.last().day)
        assertEquals(Calibration.BaselineSource.TRAINED, res.baselineSource)
        assertEquals(res.series.last().level, res.currentLevel, 1e-9)
        assertEquals(res.series.last().band, res.currentBand)
        assertEquals(res.series.last().ts, res.currentTs)
    }

    @Test fun emptyTodayGivesEmptyResultButTrendStands() {
        val res = StressEngine.analyze(
            today = StressEngine.Features(LongArray(0), arrayOf()),
            historyByDay = emptyList(),
            hr = StressEngine.ResolvedBaseline(Baseline(60.0, 10.0), Calibration.BaselineSource.COLD_START, 0),
        )
        assertTrue(res.series.isEmpty())
        assertTrue(res.dayScore.isNaN())
        assertTrue(res.currentLevel.isNaN())
    }
}
