package com.noop.analytics

import com.noop.calibration.Baseline
import com.noop.calibration.CalBaseline
import com.noop.calibration.Calibration
import com.noop.calibration.ColdStart
import com.noop.calibration.MetricRecipe
import com.noop.data.HrSample

/**
 * StressEngine — a calibrated 0–3 daytime stress reconstruction.
 *
 * Per-minute mean HR -> gap-aware EWMA window -> standardize against the user's own resting-HR baseline
 * (centre/scale, with a per-hardware cold-start fallback) -> linear clip(b0 + b_hr·z_HR, 0, 3) ->
 * LOW/MEDIUM/HIGH. Drives the whole Stress screen from one series. APPROXIMATE; not medical advice.
 *
 * HR-only: HRV (RMSSD) was dropped — it adds only ~0.02 r vs the reference, is sparse (~9% of daytime seconds)
 * and PPG-floored, and does not generalize per-night. See MetricRecipe.STRESS.
 */
object StressEngine {

    /** Per-minute features, parallel arrays keyed by [minutes] (unix seconds, minute-aligned, ascending). */
    data class Features(val minutes: LongArray, val hr: Array<Double?>)

    /** One scored minute. */
    data class MinutePoint(val ts: Long, val level: Double, val band: String)

    private val RECIPE = MetricRecipe.STRESS
    private val B0 = RECIPE.params.getValue("b0")
    private val B_HR = RECIPE.params.getValue("b_hr")
    private val HALFLIFE = RECIPE.params.getValue("halflife")
    private val BANDS = RECIPE.bands
    private val BAND_LABELS = RECIPE.bandLabels

    /** A baseline already resolved (trained-or-cold-start) for one feature. */
    data class ResolvedBaseline(val baseline: Baseline, val source: Calibration.BaselineSource, val nNights: Int)

    /** One historical day's mean 0–3 (for the trend chart). */
    data class DayMean(val day: String, val mean: Double)

    /** Trailing minutes that must all be HIGH to flag sustained stress (Breathe nudge). */
    const val SUSTAINED_HIGH_MINUTES: Int = 30

    /** Everything the Stress screen renders from — one math, one source. */
    data class Result(
        val series: List<MinutePoint>,
        val dayScore: Double,                 // NaN when no scored minutes today
        val currentLevel: Double,            // latest scored minute's level (NaN when empty)
        val currentBand: String?,            // band of the latest minute
        val currentTs: Long?,                // unix seconds of the latest scored minute
        val band: String?,
        val bandMinutes: Map<String, Int>,    // LOW/MEDIUM/HIGH -> count
        val peak: MinutePoint?,
        val sustainedHigh: Boolean,
        val sustainedRun: Int,
        val trend: List<DayMean>,             // oldest -> newest
        val baselineSource: Calibration.BaselineSource,
        val baselineNights: Int,
    ) {
        companion object {
            val EMPTY = Result(emptyList(), Double.NaN, Double.NaN, null, null, null, emptyMap(), null,
                false, 0, emptyList(), Calibration.BaselineSource.COLD_START, 0)
        }
    }

    /** Seed (mature) night count for graduating from cold-start to a trained baseline. */
    private const val SEED_NIGHTS = com.noop.analytics.Baselines.minNightsSeed

    /**
     * Resolve the HR baseline from per-night summary samples (dayId -> mean per-minute HR/night), with a
     * per-hardware cold-start fallback.
     *
     * @param userHr optional per-account override prior (the opt-in calibration seam). When supplied it
     *   replaces the per-hardware population default *before* the wearer reaches TRAINED maturity, so a
     *   new/short-history account starts from its own signal. `null` (the default) keeps current behaviour
     *   exactly — nothing in the shipped path supplies it yet. Build one with [ColdStart.userSeedFrom].
     */
    fun resolveBaselines(
        hrByNight: List<Pair<Long, Double>>,
        modelKey: String,
        userHr: Baseline? = null,
    ): ResolvedBaseline {
        val hrTrained = CalBaseline.build(hrByNight)
        val hr = ColdStart.resolve(hrTrained, hrByNight.size, SEED_NIGHTS, modelKey, "hr", userHr)
        return ResolvedBaseline(hr.baseline, hr.source, hr.nNights)
    }

    fun analyze(
        today: Features,
        historyByDay: List<Pair<String, Features>>,
        hr: ResolvedBaseline,
    ): Result {
        val series = scoreSeries(today, hr.baseline)
        val trend = historyByDay.mapNotNull { (day, f) ->
            val s = scoreSeries(f, hr.baseline)
            if (s.isEmpty()) null else DayMean(day, s.map { it.level }.average())
        }
        if (series.isEmpty()) {
            return Result.EMPTY.copy(trend = trend, baselineSource = hr.source, baselineNights = hr.nNights)
        }
        val dayScore = series.map { it.level }.average()
        val bandMinutes = series.groupingBy { it.band }.eachCount()
        val peak = series.maxByOrNull { it.level }
        var run = 0
        for (p in series.asReversed()) { if (p.band == "HIGH") run++ else break }
        val last = series.last()
        return Result(
            series = series,
            dayScore = dayScore,
            currentLevel = last.level,
            currentBand = last.band,
            currentTs = last.ts,
            band = Calibration.bandOf(dayScore, BANDS, BAND_LABELS),
            bandMinutes = bandMinutes,
            peak = peak,
            sustainedHigh = run >= SUSTAINED_HIGH_MINUTES,
            sustainedRun = run,
            trend = trend,
            baselineSource = hr.source,
            baselineNights = hr.nNights,
        )
    }

    /** EWMA -> standardize -> linear clip -> band, for each minute with a defined HR EWMA. */
    fun scoreSeries(feats: Features, hrBase: Baseline): List<MinutePoint> {
        val hrE = Calibration.timeEwma(feats.minutes, feats.hr.toList(), HALFLIFE)
        val out = ArrayList<MinutePoint>(feats.minutes.size)
        for (i in feats.minutes.indices) {
            if (hrE[i].isNaN()) continue
            val zHr = Calibration.standardize(hrE[i], hrBase)
            val level = (B0 + B_HR * zHr).coerceIn(0.0, 3.0)
            out.add(MinutePoint(feats.minutes[i], level, Calibration.bandOf(level, BANDS, BAND_LABELS)))
        }
        return out
    }

    /** Bucket raw HR samples into per-minute mean HR. */
    fun minuteFeatures(hr: List<HrSample>): Features {
        val hrByMin = sortedMapOf<Long, MutableList<Double>>()
        for (s in hr) hrByMin.getOrPut(s.ts / 60 * 60) { ArrayList() }.add(s.bpm.toDouble())
        val mins = hrByMin.keys.toLongArray()
        val hrArr = Array<Double?>(mins.size) { null }
        for (i in mins.indices) {
            hrByMin[mins[i]]?.let { if (it.isNotEmpty()) hrArr[i] = it.average() }
        }
        return Features(mins, hrArr)
    }
}
