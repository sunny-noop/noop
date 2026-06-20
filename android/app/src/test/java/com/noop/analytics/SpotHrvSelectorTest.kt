package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.PpgWaveformSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

/**
 * [SpotHrvSelector] — picks the burst inside the longest sustained low-HR SLEEP block (preferring deep
 * overlap, then nearest the resting floor), and returns a GOOD reading or null (honest empty state).
 */
class SpotHrvSelectorTest {

    private val fs = 24

    /** A clean [bpm] PPG burst over [seconds] starting at [base]. */
    private fun burst(base: Long, bpm: Double, seconds: Int = 40): List<PpgWaveformSample> {
        val freqHz = bpm / 60.0
        val out = ArrayList<PpgWaveformSample>()
        for (s in 0 until seconds) for (i in 0 until fs) {
            val tSec = s + i.toDouble() / fs
            out.add(PpgWaveformSample("d", base + s, i, (1000.0 * sin(2.0 * PI * freqHz * tSec)).toInt()))
        }
        return out
    }

    private fun hrSeries(from: Long, to: Long, bpm: Int): List<HrSample> =
        (from until to).map { HrSample("d", it, bpm) }

    @Test
    fun selectsBurstInsideLowHrSleepBlock() {
        // Two bursts: one inside a wake block (must be ignored), one inside a low-HR sleep block.
        val wakeBurst = burst(2_000, bpm = 100.0)         // [2000, 2040) under "wake"
        val sleepBurst = burst(10_000, bpm = 55.0)        // [10000, 10040) under "light" sleep
        val waveform = wakeBurst + sleepBurst

        val stages = listOf(
            StageSegment(start = 1_000, end = 5_000, stage = "wake"),
            StageSegment(start = 5_000, end = 20_000, stage = "light"),
        )
        // HR low during sleep, high during wake.
        val hr = hrSeries(1_000, 5_000, 100) + hrSeries(5_000, 20_000, 55)

        val sel = SpotHrvSelector.select(stages, hr, waveform)
        assertNotNull("a sleep-window burst should be selected", sel)
        sel!!
        assertEquals("the SLEEP burst, not the wake one, must win", 10_000L, sel.windowStart)
        assertEquals(SpotHrv.Quality.GOOD, sel.result.quality)
        assertEquals("HR ≈ 55 bpm in the chosen sleep burst", 55.0, sel.result.hr, 3.0)
        assertTrue("light sleep ⇒ not labelled deep", !sel.deep)
    }

    @Test
    fun deepOverlapIsPreferredAndLabelledDeep() {
        // A light-sleep burst and a deep-sleep burst, both GOOD; deep must win and be labelled deep.
        val lightBurst = burst(6_000, bpm = 58.0)         // inside "light"
        val deepBurst = burst(12_000, bpm = 54.0)         // fully inside "deep"
        val waveform = lightBurst + deepBurst
        val stages = listOf(
            StageSegment(start = 5_000, end = 11_000, stage = "light"),
            StageSegment(start = 11_000, end = 20_000, stage = "deep"),
        )
        val hr = hrSeries(5_000, 20_000, 56)
        val sel = SpotHrvSelector.select(stages, hr, waveform)
        assertNotNull(sel)
        assertEquals("deep-overlapping burst preferred", 12_000L, sel!!.windowStart)
        assertTrue("burst fully inside a deep segment ⇒ labelled deep", sel.deep)
    }

    @Test
    fun noBurstInSleepYieldsNull() {
        // The only burst sits in a wake block → honest empty state.
        val waveform = burst(2_000, bpm = 90.0)
        val stages = listOf(
            StageSegment(start = 1_000, end = 5_000, stage = "wake"),
            StageSegment(start = 5_000, end = 20_000, stage = "light"),
        )
        val hr = hrSeries(1_000, 20_000, 80)
        assertNull(SpotHrvSelector.select(stages, hr, waveform))
    }

    @Test
    fun emptyInputsYieldNull() {
        assertNull(SpotHrvSelector.select(emptyList(), emptyList(), emptyList()))
        assertNull(
            SpotHrvSelector.select(
                listOf(StageSegment(0, 100, "light")), emptyList(), emptyList(),
            ),
        )
    }
}
