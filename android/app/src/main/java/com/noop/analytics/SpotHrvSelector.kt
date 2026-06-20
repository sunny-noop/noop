package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.PpgWaveformSample

/**
 * Pick which sparse PPG burst to report a spot RMSSD for, sleep-window aware.
 *
 * The sleep stager's "deep" segment is its weakest output, so we do NOT trust deep alone: we select the
 * burst inside the longest sustained low-HR SLEEP block (any non-wake stage), preferring one that also
 * overlaps a deep segment, and label the reading "sleep HRV" (only "deep" when the chosen burst sits
 * fully inside a deep segment). This avoids inheriting the deep-seam error while staying physiological,
 * and improves automatically if the deep detection is fixed later.
 */
object SpotHrvSelector {

    /** The chosen reading + how it should be labelled. [deep] true only when the burst sits fully
     *  inside a "deep" stage segment; otherwise label it "sleep". */
    data class Selection(
        val result: SpotHrv.Result,
        val windowStart: Long,
        val windowEnd: Long,
        val deep: Boolean,
    )

    /**
     * @param stages the night's stage segments (from [SleepStager.detectSleep]); empty ⇒ no selection.
     * @param hr     the night's HR samples (for the resting-floor preference). May be empty.
     * @param waveform the device's raw PPG waveform rows over the night window.
     * @return the selected GOOD reading, or null (honest empty state) when no burst in any sleep block
     *         yields a GOOD spot RMSSD.
     */
    fun select(
        stages: List<StageSegment>,
        hr: List<HrSample>,
        waveform: List<PpgWaveformSample>,
    ): Selection? {
        if (stages.isEmpty() || waveform.isEmpty()) return null

        val sleepSegs = stages.filter { it.stage != "wake" }
        if (sleepSegs.isEmpty()) return null
        val deepSegs = stages.filter { it.stage == "deep" }

        // Candidate burst windows = covered runs that overlap a sleep segment.
        val candidates = SpotHrv.coveredWindows(waveform).filter { (a, b) ->
            sleepSegs.any { overlaps(a, b, it.start, it.end) }
        }
        if (candidates.isEmpty()) return null

        // Resting floor = p25 of sleep HR; used to prefer the lowest-HR burst (closest to resting).
        val sleepHr = hr.filter { s -> sleepSegs.any { it.start <= s.ts && s.ts < it.end } }.map { it.bpm }
        val restFloor = percentile(sleepHr.map { it.toDouble() }, 0.25)

        // Score each candidate that produces a GOOD reading. Prefer deep overlap, then HR near the
        // resting floor (smaller distance wins), then more clean beats.
        var best: Selection? = null
        var bestKey: Triple<Int, Double, Int>? = null // (deepRank desc, -hrDist, nClean) compared below
        for ((a, b) in candidates) {
            val rows = waveform.filter { it.ts in a until b }
            val res = SpotHrv.spotHrv(rows) ?: continue
            if (res.quality != SpotHrv.Quality.GOOD) continue

            val deepOverlap = deepSegs.any { overlaps(a, b, it.start, it.end) }
            val fullyDeep = deepSegs.any { it.start <= a && b <= it.end }
            // Mean HR of the candidate's own seconds (fallback to the reading's HR).
            val winHr = hr.filter { it.ts in a until b }.map { it.bpm }
            val hrHere = if (winHr.isNotEmpty()) winHr.average() else res.hr
            val hrDist = if (restFloor != null) kotlin.math.abs(hrHere - restFloor) else 0.0

            val key = Triple(if (deepOverlap) 1 else 0, -hrDist, res.nClean)
            if (bestKey == null || better(key, bestKey!!)) {
                bestKey = key
                best = Selection(result = res, windowStart = a, windowEnd = b, deep = fullyDeep)
            }
        }
        return best
    }

    /** Lexicographic preference: higher deepRank, then larger -hrDist (i.e. nearer floor), then nClean. */
    private fun better(x: Triple<Int, Double, Int>, y: Triple<Int, Double, Int>): Boolean {
        if (x.first != y.first) return x.first > y.first
        if (x.second != y.second) return x.second > y.second
        return x.third > y.third
    }

    private fun overlaps(a: Long, b: Long, c: Long, d: Long): Boolean = a < d && c < b

    /** Linear-interpolation percentile (q in 0..1) over a value list; null when empty. */
    private fun percentile(xs: List<Double>, q: Double): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        if (s.size == 1) return s[0]
        val pos = q * (s.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        val frac = pos - lo
        return s[lo] + (s[hi] - s[lo]) * frac
    }
}
