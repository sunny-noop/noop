package com.noop.calibration

import kotlin.math.exp
import kotlin.math.ln

/**
 * Calibration — the shared per-user calibration seam every metric ("the personal touch") calls into.
 *
 * Holds the immutable, shipped pieces of a metric's personalization: the standardize definition, the
 * per-hardware cold-start defaults, and the windowing/stat helpers. Per-user baselines themselves are
 * built by [CalBaseline] from the user's own recent history (recomputed each pass — nothing persisted here).
 *
 * A metric supplies a [MetricRecipe] (its universal formula constants) and a resolved [Baseline] per input
 * feature, then scores its own series. See StressEngine for the first consumer.
 *
 * Outputs are APPROXIMATE personalization, not medical advice.
 */
object Calibration {

    /**
     * Where a resolved baseline came from — drives the UI "calibrating…" copy.
     * - TRAINED:    the wearer's own baseline, with enough nights to trust it.
     * - USER_SEED:  a per-account override prior (e.g. the wearer's own short/stored history),
     *               used in place of the population default before TRAINED maturity. Opt-in seam;
     *               off by default (see [ColdStart.resolve] / [StressEngine.resolveBaselines]).
     * - COLD_START: the per-hardware population default (no per-user signal yet).
     */
    enum class BaselineSource { TRAINED, USER_SEED, COLD_START }

    /** A resolved per-feature centre + scale, plus where it came from. */
    data class Resolved(val baseline: Baseline, val source: BaselineSource, val nNights: Int)

    /** z-score against a baseline. The ONE standardize definition (fit-time and apply-time share it). */
    fun standardize(x: Double, b: Baseline): Double = (x - b.centre) / b.scale

    /**
     * Band label for a value given lower-closed cut-points and matching labels (label-agnostic, so a 0–3
     * stress metric and a 0–100 sleep-performance metric both fit). Requires labels.size == cuts.size + 1.
     */
    fun bandOf(v: Double, cuts: List<Double>, labels: List<String>): String {
        var i = 0
        while (i < cuts.size && v >= cuts[i]) i++
        return labels[i]
    }

    /**
     * Causal, gap-aware EWMA over irregular minute timestamps. `values` may contain null (missing) ->
     * state is carried (read out, not updated). A large gap drives the blend weight toward 1 so state
     * resets to the new value. Output is NaN until the first real value. `minutes` are unix SECONDS
     * (minute-aligned).
     */
    fun timeEwma(minutes: LongArray, values: List<Double?>, halflifeMin: Double): DoubleArray {
        val out = DoubleArray(minutes.size) { Double.NaN }
        var s: Double? = null
        var tPrev = 0L
        val decay = ln(2.0) / halflifeMin
        for (i in minutes.indices) {
            val x = values[i]
            if (x != null && !x.isNaN()) {
                s = if (s == null) {
                    x
                } else {
                    val dt = (minutes[i] - tPrev) / 60.0
                    val a = 1.0 - exp(-dt * decay)
                    (1.0 - a) * s + a * x
                }
                tPrev = minutes[i]
            }
            out[i] = s ?: Double.NaN
        }
        return out
    }
}

/** A per-feature centre + scale (e.g. resting-HR centre and its spread). */
data class Baseline(val centre: Double, val scale: Double)
