package com.noop.analytics

import com.noop.calibration.Baseline
import com.noop.calibration.CalBaseline
import com.noop.calibration.ColdStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the cold-start <-> trained handoff: the population cold-start baseline and the wearer's own
 * CalBaseline must live on the SAME ruler (the between-night spread of per-night summaries), so a
 * population-typical wearer sees no score jump when they graduate from the population default to their
 * own baseline.
 *
 * Regression: cold-start was once authored in the within-day spread (~14 bpm) while CalBaseline produces
 * the between-night spread (single-digit bpm) — a ~3-6x rescale at graduation, only visible on a real
 * device as history accrued (LONO validation never exercises the cold-start branch). These tests fail if
 * that mismatch ever returns.
 */
class StressEngineContinuityTest {

    /** A realistic wearer's per-night mean HR: centred ~78, between-night spread ~4.5 bpm (population). */
    private val realisticNightlyHr =
        listOf(70.0, 73.0, 75.0, 77.0, 78.0, 79.0, 81.0, 83.0, 86.0, 78.0)

    private fun trainedHr(): Baseline =
        CalBaseline.build(realisticNightlyHr.mapIndexed { i, v -> i.toLong() to v })!!

    /** ~3 h of active daytime HR (~88-102 bpm). */
    private fun activeDay(): StressEngine.Features {
        val n = 180
        val mins = LongArray(n) { it.toLong() * 60 }
        val hr = Array<Double?>(n) { 88.0 + (it % 15) }   // sawtooth 88..102, mean ~95
        return StressEngine.Features(mins, hr)
    }

    @Test fun coldStartHrIsOnTheBetweenNightRuler() {
        val trained = trainedHr().scale                       // ~4.5 (between-night)
        for (model in listOf("whoop5", "whoop4")) {
            val cold = ColdStart.defaultFor(model, "hr").scale
            assertTrue(
                "cold-start HR scale $cold for $model is off the between-night ruler (~$trained); " +
                    "a within-day scale (~14) would rescale the score at graduation",
                cold in (trained * 0.5)..(trained * 2.5),
            )
        }
    }

    @Test fun noScoreJumpFromColdStartToTrainedForATypicalWearer() {
        val day = activeDay()
        val coldHr = ColdStart.defaultFor("whoop5", "hr")
        val coldMean = StressEngine.scoreSeries(day, coldHr).map { it.level }.average()
        val trainedMean = StressEngine.scoreSeries(day, trainedHr()).map { it.level }.average()
        // same ruler -> a population-typical wearer's score barely moves at the handoff.
        assertEquals(coldMean, trainedMean, 0.20)
    }
}
