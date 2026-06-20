package com.noop.analytics

import com.noop.data.PpgWaveformSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

/**
 * [SpotHrv] — spot RMSSD from the 24 Hz optical PPG waveform. Mirrors [PpgHrTest]'s synthetic-signal
 * style for the pure DSP, plus a GOLDEN test that pins the Kotlin port to the validated Linux
 * reference on a real captured burst (identifier-free PPG ADC counts) — the same burst, the same
 * RMSSD within ±2 ms.
 */
class SpotHrvTest {

    private val fs = 24

    /** [seconds] of a [bpm] sine on the 24 Hz grid, as raw waveform rows starting at [baseTs]. */
    private fun sine(bpm: Double, seconds: Int, baseTs: Long = 1_000_000L, amp: Double = 1000.0): List<PpgWaveformSample> {
        val freqHz = bpm / 60.0
        val out = ArrayList<PpgWaveformSample>()
        for (s in 0 until seconds) {
            for (i in 0 until fs) {
                val tSec = s + i.toDouble() / fs
                val v = (amp * sin(2.0 * PI * freqHz * tSec)).toInt()
                out.add(PpgWaveformSample(deviceId = "d", ts = baseTs + s, sampleIdx = i, value = v))
            }
        }
        return out
    }

    @Test
    fun cleanSineRecoversHrAndGoodQuality() {
        // A clean 60 bpm sine at 24 Hz over 40 s: HR ≈ 60, a small finite RMSSD, quality GOOD.
        val r = SpotHrv.spotHrv(sine(bpm = 60.0, seconds = 40))
        assertNotNull("expected a reading from a clean 40 s sine", r)
        r!!
        assertEquals("HR should be ≈ 60 bpm", 60.0, r.hr, 2.0)
        assertNotNull("RMSSD should be finite", r.rmssd)
        assertTrue("a pure sine has near-zero beat-to-beat variability", r.rmssd!! < 15.0)
        assertEquals("clean periodic signal → GOOD", SpotHrv.Quality.GOOD, r.quality)
    }

    @Test
    fun flatSignalYieldsNoBeats() {
        // A constant signal detrends to ~0 everywhere, so no peak clears the prominence gate → no RR →
        // null. (The quality gate alone does NOT reject white noise — neither here nor in the Linux
        // reference, which grades structureless noise GOOD too; the upstream guard is that the DSP only
        // runs on real optical bursts. See `noiseFixtureMatchesOracle` for the faithful parity check.)
        val rows = (0 until 40).flatMap { s -> (0 until fs).map { PpgWaveformSample("d", 1_000_000L + s, it, 500) } }
        assertEquals(null, SpotHrv.spotHrv(rows))
    }

    @Test
    fun noiseFixtureMatchesOracle() {
        // White-noise PPG, the IDENTICAL 960 samples the Linux reference scored (its `random` stream,
        // committed as a fixture). The honest property is bit-for-bit parity with the oracle on the same
        // data — NOT that noise is rejected (it isn't, by either implementation): the oracle grades this
        // GOOD with RMSSD ≈ 104.66 ms / HR ≈ 117.24 / 38 clean. Kotlin must reproduce those numbers.
        val rows = loadFixture("/com/noop/analytics/golden_v26_noise.csv", baseTs = 0L)
        val r = SpotHrv.spotHrv(rows)
        assertNotNull(r)
        r!!
        assertEquals(104.6593, r.rmssd!!, 2.0)
        assertEquals(117.239, r.hr, 1.0)
        assertEquals(38, r.nClean)
        assertEquals(SpotHrv.Quality.GOOD, r.quality)
    }

    @Test
    fun tooShortYieldsNull() {
        // < 30 samples ⇒ null (matches the Python guard).
        val rows = (0 until 20).map { PpgWaveformSample("d", 1_000_000L, it, it) }
        assertEquals(null, SpotHrv.spotHrv(rows))
    }

    @Test
    fun reconstructGivesCleanGridAndFs() {
        val rows = sine(bpm = 60.0, seconds = 3)
        val (t, v, fsOut) = SpotHrv.reconstruct(rows)
        assertEquals(24.0, fsOut, 0.0)
        assertEquals(72, t.size)
        assertEquals(72, v.size)
        // sample i of second k lands at k + i/24; first three are 0, 1/24, 2/24.
        assertEquals(0.0, t[0], 1e-9)
        assertEquals(1.0 / 24.0, t[1], 1e-9)
        assertEquals(2.0 / 24.0, t[2], 1e-9)
    }

    @Test
    fun coveredWindowsFindsContiguousRuns() {
        // Two runs of >= 20 s with a gap; a 5 s stub is dropped.
        val rows = ArrayList<PpgWaveformSample>()
        fun run(base: Long, n: Int) { for (s in 0 until n) for (i in 0 until 2) rows.add(PpgWaveformSample("d", base + s, i, 0)) }
        run(1000, 25)   // [1000, 1025)
        run(2000, 22)   // [2000, 2022)
        run(3000, 5)    // dropped (< 20)
        val w = SpotHrv.coveredWindows(rows)
        assertEquals(listOf(1000L to 1025L, 2000L to 2022L), w)
    }

    // ── GOLDEN: a captured 24 Hz burst vs the Linux oracle ──────────────────────────────────────
    // A 40 s, 960-sample burst. The Linux reference (tools/linux-capture/whoop_spot_hrv.py) grades it
    // GOOD with HR ≈ 100.15 bpm, RMSSD ≈ 69.386 ms, 59 clean beats. The CSV holds identifier-free PPG
    // ADC counts (rel_ts,sampleIdx,value).
    private val goldenBaseTs = 0L
    private val oracleRmssd = 69.38573893694004
    private val oracleHr = 100.15342686411265
    private val oracleNClean = 59

    /** Load a `relTs,sampleIdx,value` CSV fixture (PPG ADC counts) as waveform rows. */
    private fun loadFixture(path: String, baseTs: Long): List<PpgWaveformSample> {
        val stream = javaClass.getResourceAsStream(path) ?: error("fixture not found: $path")
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val (relTs, idx, value) = line.split(",").map { it.trim() }
                PpgWaveformSample(
                    deviceId = "fixture",
                    ts = baseTs + relTs.toLong(),
                    sampleIdx = idx.toInt(),
                    value = value.toInt(),
                )
            }.toList()
        }
    }

    @Test
    fun goldenBurstMatchesLinuxOracleRmssd() {
        val rows = loadFixture("/com/noop/analytics/golden_v26_ppg_burst.csv", baseTs = goldenBaseTs)
        assertEquals("expected the full 40 s × 24 Hz golden grid", 960, rows.size)
        val r = SpotHrv.spotHrv(rows)
        assertNotNull("golden burst must produce a reading", r)
        r!!
        // ±2 ms is the gate; the port is actually exact to < 1e-4 ms (verified in-session) — the
        // tolerance is platform-float headroom, not slop.
        assertEquals("Kotlin RMSSD must match the Linux oracle within ±2 ms", oracleRmssd, r.rmssd!!, 2.0)
        assertEquals("HR must match the oracle", oracleHr, r.hr, 1.0)
        assertEquals("clean-beat count must match the oracle", oracleNClean, r.nClean)
        assertEquals("oracle graded this burst GOOD", SpotHrv.Quality.GOOD, r.quality)
    }
}
