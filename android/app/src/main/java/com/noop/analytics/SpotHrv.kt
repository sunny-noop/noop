package com.noop.analytics

import com.noop.data.PpgWaveformSample
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Spot HRV (RMSSD) from the strap's sparse 24 Hz optical PPG bursts (the v26 waveform).
 *
 * The historical offload's per-second packed R-R underestimates HRV, but the strap also banks a real
 * 24 Hz optical PPG waveform in its sparse bursts (stored as [PpgWaveformSample], one row per sample).
 * That waveform is genuine cardiac PPG — its fundamental tracks the heart rate — so beats can be
 * detected and RMSSD computed. Where a burst covers a deep-sleep window the PPG-derived RMSSD is a
 * proper beat-to-beat figure, well above the offload R-R field's saturated number.
 *
 * Honest limits (surfaced in the UI, not hidden here):
 *  - SPARSE — bursts are short and infrequent (a few percent coverage), so a window only gets HRV if a
 *    burst lands in it. This is a *spot* reading, not continuous overnight HRV.
 *  - COARSE — 24 Hz sampling quantises beat timing (~42 ms/sample); sub-sample interpolation + glitch
 *    rejection help, but the RMSSD is approximate; require enough clean beats ([Quality]).
 *  - HRV only, not SpO2 (the waveform is AC-coupled — no DC red/IR channel).
 *
 * Pure arithmetic, no I/O — a byte-for-byte port of the validated Linux reference DSP so the same
 * captured burst yields the same RMSSD on both. Unit-tested on synthetic signals + a golden burst.
 */
object SpotHrv {

    /** RMSSD trust grade — enough CLEAN consecutive beats to believe the number. */
    enum class Quality { POOR, COARSE, GOOD }

    /**
     * One spot reading over a burst window.
     * [hr] = median-RR HR (bpm), [rmssd] = ms (null when too few clean diffs), [nBeats] detected peaks,
     * [nRr] accepted R-R intervals, [nClean] consecutive RR within the ectopic threshold (drives
     * [quality]), [spanS] the window's covered span (s), [fs] samples/second the grid reconstructed at.
     */
    data class Result(
        val hr: Double,
        val rmssd: Double?,
        val nBeats: Int,
        val nRr: Int,
        val nClean: Int,
        val spanS: Double,
        val fs: Double,
        val quality: Quality,
    )

    /**
     * Reconstruct a clean per-second grid from raw waveform rows (sorted by ts, then sampleIdx).
     * One record == one second; fs = the max samples-per-second seen, and sample i of second k lands
     * at `k + i/fs`. Returns (times in seconds relative to the first second, values, fs).
     */
    fun reconstruct(rows: List<PpgWaveformSample>): Triple<DoubleArray, DoubleArray, Double> {
        if (rows.isEmpty()) return Triple(DoubleArray(0), DoubleArray(0), 0.0)
        // max samples in any one second (== the per-second grid size, normally 24).
        val perSecond = HashMap<Long, Int>()
        for (r in rows) perSecond[r.ts] = (perSecond[r.ts] ?: 0) + 1
        val n = perSecond.values.max()
        val base = rows.first().ts
        val t = DoubleArray(rows.size)
        val v = DoubleArray(rows.size)
        for (i in rows.indices) {
            val r = rows[i]
            t[i] = (r.ts - base).toDouble() + (r.sampleIdx.toDouble() / n)
            v[i] = r.value.toDouble()
        }
        return Triple(t, v, n.toDouble())
    }

    /** Subtract a centred moving average of width [win] (removes PPG baseline wander). */
    fun detrend(v: DoubleArray, win: Int): DoubleArray {
        val n = v.size
        val out = DoubleArray(n)
        val h = maxOf(1, win / 2)
        for (i in 0 until n) {
            val lo = maxOf(0, i - h)
            val hi = minOf(n, i + h + 1)
            var s = 0.0
            for (j in lo until hi) s += v[j]
            out[i] = v[i] - s / (hi - lo)
        }
        return out
    }

    /** Local maxima `> left`, `>= right`, above [minProm], kept [minDist] apart (taller wins). */
    fun findPeaks(v: DoubleArray, minDist: Int, minProm: Double): List<Int> {
        val cand = ArrayList<Int>()
        for (i in 1 until v.size - 1) {
            if (v[i] > v[i - 1] && v[i] >= v[i + 1] && v[i] > minProm) cand.add(i)
        }
        // Taller candidates win conflicts (descending value).
        cand.sortByDescending { v[it] }
        val kept = ArrayList<Int>()
        for (i in cand) {
            if (kept.all { abs(i - it) >= minDist }) kept.add(i)
        }
        kept.sort()
        return kept
    }

    /** Parabolic sub-sample peak offset (fractional samples) around index [p]. */
    fun parabolicInterp(v: DoubleArray, p: Int): Double {
        if (p in 1 until v.size - 1) {
            val a = v[p - 1]
            val b = v[p]
            val c = v[p + 1]
            val den = a - 2 * b + c
            return if (den != 0.0) (a - c) / (2 * den) else 0.0
        }
        return 0.0
    }

    /** Population standard deviation (mirrors Python `pstdev`). */
    private fun pstdev(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        var mean = 0.0
        for (x in v) mean += x
        mean /= v.size
        var s = 0.0
        for (x in v) {
            val d = x - mean
            s += d * d
        }
        return sqrt(s / v.size)
    }

    /** Median of a list (mirrors Python `statistics.median`: mean of the two middle values when even). */
    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /**
     * RMSSD over consecutive R-R, skipping any pair where either RR jumped > [thr] of the previous
     * (ectopic/artifact). Needs >= 2 surviving diffs, else null. Port of `rmssd_sequential`.
     */
    fun rmssdSequential(rr: List<Double>, thr: Double = 0.30): Double? {
        if (rr.size < 2) return null
        val glitch = BooleanArray(rr.size)
        for (i in 1 until rr.size) {
            if (abs(rr[i] - rr[i - 1]) > thr * rr[i - 1]) glitch[i] = true
        }
        val d = ArrayList<Double>()
        for (i in 1 until rr.size) {
            if (!glitch[i - 1] && !glitch[i]) d.add(rr[i] - rr[i - 1])
        }
        if (d.size < 2) return null
        var s = 0.0
        for (x in d) s += x * x
        return sqrt(s / d.size)
    }

    /**
     * Detect beats in a reconstructed (times, values, fs) window and compute the spot reading, or null
     * when there's too little signal. Port of `spot_hrv`.
     */
    fun spotHrv(t: DoubleArray, v: DoubleArray, fs: Double): Result? {
        if (v.size < 30 || fs <= 0.0) return null
        val vv = detrend(v, fs.toInt())
        val sd = pstdev(vv).let { if (it == 0.0) 1.0 else it }
        val peaks = findPeaks(vv, minDist = (0.4 * fs).toInt(), minProm = 0.3 * sd) // >= 0.4 s apart
        val bt = DoubleArray(peaks.size)
        for (i in peaks.indices) {
            val p = peaks[i]
            bt[i] = t[p] + parabolicInterp(vv, p) / fs
        }
        val rr = ArrayList<Double>()
        for (i in 0 until bt.size - 1) {
            val ms = (bt[i + 1] - bt[i]) * 1000.0
            if (ms in 300.0..2000.0) rr.add(ms)
        }
        if (rr.size < 2) return null
        val hr = 60000.0 / median(rr)
        val rmssd = rmssdSequential(rr)
        var nClean = 0
        for (i in 1 until rr.size) {
            if (abs(rr[i] - rr[i - 1]) <= 0.30 * rr[i - 1]) nClean++
        }
        val span = if (t.isNotEmpty()) t[t.size - 1] - t[0] else 0.0
        val q = when {
            rmssd == null || nClean < 10 -> Quality.POOR
            nClean >= 25 -> Quality.GOOD
            else -> Quality.COARSE
        }
        return Result(
            hr = hr, rmssd = rmssd, nBeats = bt.size, nRr = rr.size,
            nClean = nClean, spanS = span, fs = fs, quality = q,
        )
    }

    /** Convenience: reconstruct + spotHrv over a set of raw waveform rows. */
    fun spotHrv(rows: List<PpgWaveformSample>): Result? {
        val (t, v, fs) = reconstruct(rows)
        return spotHrv(t, v, fs)
    }

    /**
     * Contiguous runs of >= [minRun] consecutive seconds that carry waveform (each ~= one burst),
     * as [start, end) unix-second pairs (end exclusive). Port of `_covered_windows`. Used to enumerate
     * candidate burst windows for selection, and as the "any burst" fallback.
     */
    fun coveredWindows(rows: List<PpgWaveformSample>, minRun: Int = 20): List<Pair<Long, Long>> {
        if (rows.isEmpty()) return emptyList()
        val secs = rows.map { it.ts }.distinct().sorted()
        val runs = ArrayList<Pair<Long, Long>>()
        var cur = ArrayList<Long>()
        for (s in secs) {
            if (cur.isNotEmpty() && s == cur.last() + 1) {
                cur.add(s)
            } else {
                if (cur.size >= minRun) runs.add(cur.first() to cur.last() + 1)
                cur = arrayListOf(s)
            }
        }
        if (cur.size >= minRun) runs.add(cur.first() to cur.last() + 1)
        return runs
    }
}
