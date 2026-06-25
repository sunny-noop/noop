package com.noop.calibration

import kotlin.math.sqrt

/**
 * CalBaseline — builds a per-user [Baseline] (centre + scale) from per-night samples: a robust median
 * centre + population-stdev scale over a trailing window of nights (day-deduped). One statistic, used
 * the same way at fit time and apply time so a metric's universal coefficients stay valid per user.
 *
 * Distinct from the recovery/HRV baselines elsewhere in the app: applying universal coefficients against a
 * different centre/scale would change the effective slope, so this builder is kept separate and exact.
 */
object CalBaseline {

    /**
     * @param samples per-night (dayId, value), any order. Deduped by dayId (first occurrence wins).
     * @param window keep only the most recent [window] nights.
     * @param q if non-null, centre = this quantile of the window (e.g. 0.9); else centre = median.
     * @return resolved Baseline, or null when there are no nights.
     */
    fun build(samples: List<Pair<Long, Double>>, window: Int = 28, q: Double? = null): Baseline? {
        if (samples.isEmpty()) return null
        val seen = HashMap<Long, Double>()
        for ((day, v) in samples.sortedBy { it.first }) seen.putIfAbsent(day, v)
        var hist = seen.entries.sortedBy { it.key }.map { it.value }
        if (hist.size > window) hist = hist.takeLast(window)
        val centre = if (q != null) quantile(hist, q) else median(hist)
        val scale = if (hist.size > 1) pstdev(hist) else 1.0
        return Baseline(centre, scale)
    }

    /** Median; even count averages the two middle values. */
    fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** Population standard deviation. */
    fun pstdev(xs: List<Double>): Double {
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size)
    }

    /** Linear-interpolated quantile of a list. */
    fun quantile(xs: List<Double>, q: Double): Double {
        val s = xs.sorted()
        if (s.isEmpty()) return 0.0
        val k = (s.size - 1) * q
        val f = k.toInt()
        return if (f + 1 >= s.size) s[f] else s[f] + (k - f) * (s[f + 1] - s[f])
    }
}
