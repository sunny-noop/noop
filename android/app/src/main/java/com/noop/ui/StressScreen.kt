package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.DaytimeStress
import com.noop.analytics.StressEngine
import com.noop.ble.PuffinExperiment
import com.noop.data.DailyMetric
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

// MARK: - Stress Monitor (ported from Strand/Screens/StressView.swift)
//
// A Whoop-style "Stress Monitor": one 0–3 number, a band (LOW/MEDIUM/HIGH), and a
// single plain-English line on *why*. The score is a transparent proxy for autonomic
// load, DERIVED from how today's resting HR / HRV sit against a personal 30-day
// baseline (a stored "stress" series, if present, takes priority):
//
//   zRHR = (todayRHR − meanRHR) / sdRHR        // positive when RHR is UP
//   zHRV = (meanHRV − todayHRV) / sdHRV        // positive when HRV is DOWN
//   raw  = zRHR + zHRV                          // combined autonomic load
//   stress = 3 / (1 + e^(−raw))                // 0 calm · 1.5 baseline · 3 high
//
// Bands: 0–1 LOW · 1–2 MEDIUM · 2–3 HIGH. Everything is computed live from
// `recentDays` (+ the stored series) so the math is fully inspectable — see the
// "How this is computed" card at the bottom.
//
// Source priority for today's value:
//   1. A persisted daily `stress` value from the metricSeries store ("my-whoop").
//   2. Otherwise the z-score derivation above.
// Both the hero number and the full trend line share ONE baseline so the line is
// internally comparable.

@Composable
fun StressScreen(vm: AppViewModel, onBreathe: () -> Unit = {}) {
    val days by vm.recentDays.collectAsStateWithLifecycle()

    // Stored daily "stress" values (0–3), keyed by day. Loaded once per device; the
    // metricSeries store is the Android analogue of the macOS `repo.series(key:source:)`.
    // We pull a wide range so the whole history is covered.
    var stored by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var storedLoaded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val rows = runCatching {
            vm.repo.metricSeries("my-whoop", "stress", "0000-01-01", "9999-12-31")
        }.getOrDefault(emptyList())
        stored = rows.associate { it.day to it.value.coerceIn(0.0, 3.0) }
        storedLoaded = true
    }

    // Opt-in (Settings → Experimental "stress engine"): when on, the WHOLE Stress monitor is driven by
    // the per-user calibrated StressEngine — the live hero score is a recent-window (~15 min) read, and
    // the intraday timeline + the daily trend are re-scored from raw HR against the wearer's own baseline.
    // Off → the shipped DaytimeStress intraday + the stored daily score (unchanged).
    val context = androidx.compose.ui.platform.LocalContext.current
    val useEngine = remember { PuffinExperiment.from(context).experimentalStressEngine }

    var daytime by remember { mutableStateOf<DaytimeStress.Result?>(null) }
    var engine by remember { mutableStateOf<EngineBundle?>(null) }
    androidx.compose.runtime.LaunchedEffect(useEngine) {
        if (useEngine) {
            val e = runCatching { computeEngine(vm) }.getOrNull()
            engine = e
            daytime = e?.today ?: DaytimeStress.Result.EMPTY
        } else {
            daytime = runCatching { loadDaytimeStress(vm) }.getOrDefault(DaytimeStress.Result.EMPTY)
        }
    }

    // When the engine drives it: trend = its per-day means, hero = its live (~15 min) score. Else stored.
    val effectiveStored = if (useEngine) engine?.trend else stored
    val liveOverride = if (useEngine) engine?.liveScore?.takeIf { !it.isNaN() } else null
    val model = remember(days, effectiveStored, liveOverride) {
        effectiveStored?.let { StressModel.build(days, it, liveOverride) }
    }
    val ready = if (useEngine) engine != null else storedLoaded

    LazyScreenScaffold(
        title = "Stress",
        subtitle = "Autonomic load from HRV and resting heart rate",
    ) {
        when {
            model != null -> StressContent(model, daytime, onBreathe, useEngine)
            !ready -> item { StressLoading() }
            else -> item { StressEmpty() }
        }
    }
}

/**
 * Read TODAY's banked HR + R-R and build the intraday stress timeline. Local-day window
 * [midnight, now]; [DaytimeStress] buckets it into waking hours and reuses the daily
 * score's math, so this is the same proxy at a finer grain — never a new score.
 */
private suspend fun loadDaytimeStress(vm: AppViewModel): DaytimeStress.Result {
    val nowSeconds = System.currentTimeMillis() / 1000L
    val tzOffsetSeconds = java.util.TimeZone.getDefault().getOffset(nowSeconds * 1_000L) / 1_000L
    // Local midnight (wall-clock seconds): floor the LOCAL time to the day, then undo the
    // offset so the bound is back on the wall clock the samples are stored in.
    val localNow = nowSeconds + tzOffsetSeconds
    val from = (localNow - Math.floorMod(localNow, 86_400L)) - tzOffsetSeconds
    val hr = vm.repo.hrSamples("my-whoop", from, nowSeconds, limit = 200_000)
    if (hr.size < DaytimeStress.minHourHrSamples) return DaytimeStress.Result.EMPTY
    val rr = vm.repo.rrIntervals("my-whoop", from, nowSeconds, limit = 200_000)
    return DaytimeStress.analyze(hr, rr, tzOffsetSeconds)
}

/** Everything the calibrated Stress monitor renders from (opt-in engine path). */
private class EngineBundle(
    val today: DaytimeStress.Result,   // intraday timeline (hourly), today
    val liveScore: Double,             // live recent-window (~15 min) hero score; NaN when no recent HR
    val trend: Map<String, Double>,    // per-day calibrated daily mean, keyed "yyyy-MM-dd"
)

/**
 * Opt-in calibrated path (Settings → Experimental "stress engine"): drive the WHOLE Stress monitor from
 * [StressEngine] instead of DaytimeStress + the stored daily series. One ~30-day 5-min HR-bucket load
 * yields the per-user between-night HR baseline AND the per-day trend; today's intraday line is built
 * from per-minute HR. The hero/live score is the mean of the last ~15 min of the (EWMA-smoothed) series
 * — a recent read, NOT a whole-day mean. Baseline is TRAINED once enough nights exist,
 * else the per-hardware cold-start (modelKey only affects that fallback; whoop5 is the safe default).
 */
private suspend fun computeEngine(vm: AppViewModel): EngineBundle {
    val now = System.currentTimeMillis() / 1000L
    val tz = java.util.TimeZone.getDefault()
    val tzOff = tz.getOffset(now * 1_000L) / 1_000L

    val buckets = vm.repo.hrBuckets("my-whoop", now - 31L * 86_400L, now, 300L)
    val byNight = HashMap<Long, MutableList<Double>>()
    for (b in buckets) {
        byNight.getOrPut(Math.floorDiv(b.bucket * 300L - 43_200L, 86_400L)) { ArrayList() }.add(b.avgBpm)
    }
    val hrByNight = byNight.entries.filter { it.value.isNotEmpty() }.map { it.key to it.value.average() }
    val hrBase = StressEngine.resolveBaselines(hrByNight, modelKey = "whoop5").baseline

    // Today's intraday from per-minute HR; live score = mean of the last ~15 min of the series.
    val localNow = now + tzOff
    val from = (localNow - Math.floorMod(localNow, 86_400L)) - tzOff
    val hr = vm.repo.hrSamples("my-whoop", from, now, limit = 200_000)
    var today = DaytimeStress.Result.EMPTY
    var live = Double.NaN
    if (hr.size >= DaytimeStress.minHourHrSamples) {
        val series = StressEngine.scoreSeries(StressEngine.minuteFeatures(hr), hrBase)
        today = adaptToDaytime(series, tzOff)
        val lastTs = series.lastOrNull()?.ts
        if (lastTs != null) {
            val recent = series.filter { it.ts >= lastTs - 15L * 60L }
            if (recent.isNotEmpty()) live = recent.map { it.level }.average()
        }
    }

    // Per-day trend: waking-window (06–22 local) mean of the calibrated series, from the 5-min buckets.
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = tz }
    val byDay = sortedMapOf<String, MutableList<Pair<Long, Double>>>()
    for (b in buckets) {
        val ts = b.bucket * 300L
        byDay.getOrPut(fmt.format(java.util.Date(ts * 1_000L))) { ArrayList() }.add(ts to b.avgBpm)
    }
    val trend = HashMap<String, Double>()
    for ((day, pts) in byDay) {
        val s = pts.sortedBy { it.first }
        val mins = LongArray(s.size) { s[it].first }
        val hrArr = Array<Double?>(s.size) { s[it].second }
        val series = StressEngine.scoreSeries(StressEngine.Features(mins, hrArr), hrBase)
        val waking = series.filter {
            val h = Math.floorMod(Math.floorDiv(it.ts + tzOff, 3_600L), 24L).toInt()
            h in DaytimeStress.wakingStartHour until DaytimeStress.wakingEndHour
        }
        if (waking.isNotEmpty()) trend[day] = waking.map { it.level }.average()
    }
    return EngineBundle(today, live, trend)
}

/** Aggregate the calibrated per-minute series into the hourly [DaytimeStress.Result] shape the Stress
 *  screen renders: same waking window (06–22 local) and sustained-high run logic, calibrated levels. */
private fun adaptToDaytime(series: List<StressEngine.MinutePoint>, tzOffsetSeconds: Long): DaytimeStress.Result {
    if (series.isEmpty()) return DaytimeStress.Result.EMPTY
    val byHour = sortedMapOf<Long, MutableList<Double>>()   // wall-clock bucket start -> levels
    for (p in series) {
        val localTs = p.ts + tzOffsetSeconds
        val hod = Math.floorMod(Math.floorDiv(localTs, 3_600L), 24L).toInt()
        if (hod < DaytimeStress.wakingStartHour || hod >= DaytimeStress.wakingEndHour) continue
        val bucketWall = Math.floorDiv(localTs, 3_600L) * 3_600L - tzOffsetSeconds
        byHour.getOrPut(bucketWall) { ArrayList() }.add(p.level)
    }
    if (byHour.isEmpty()) return DaytimeStress.Result.EMPTY
    val hours = byHour.map { (wall, lv) ->
        val hod = Math.floorMod(Math.floorDiv(wall + tzOffsetSeconds, 3_600L), 24L).toInt()
        DaytimeStress.HourPoint(hour = hod, startTs = wall, level = lv.average(), meanHr = null, rmssd = null)
    }
    val scored = hours.mapNotNull { p -> p.level?.let { p to it } }
    var run = 0
    for ((_, lvl) in scored.asReversed()) { if (lvl >= DaytimeStress.highBandFloor) run++ else break }
    val dayMean = if (scored.isEmpty()) null else scored.map { it.second }.average()
    val peak = scored.maxByOrNull { it.second }?.first
    return DaytimeStress.Result(hours, run >= DaytimeStress.sustainedHours, run, dayMean, peak)
}

// MARK: - Loaded content

// PERF (#scroll-jank): a [LazyListScope] extension so the 5 sections build lazily under
// [LazyScreenScaffold] (only the rows on screen are composed). Same order, the SAME
// `staggeredAppear(0..4)` indices, and the inter-section spacing is the scaffold's 20dp
// `Arrangement.spacedBy` — identical to the eager ColumnScope it replaced.
private fun androidx.compose.foundation.lazy.LazyListScope.StressContent(
    model: StressModel,
    daytime: DaytimeStress.Result?,
    onBreathe: () -> Unit,
    useEngine: Boolean = false,
) {
    // 1 · HERO — the count-up PipBar + band + one plain-English line, all in one card
    //     (the needle/semicircle gauge is gone, matching the iOS redesign: a big WHITE
    //     CountUpText value with "of 3" + the band word beside it, over a band-tinted
    //     PipBar on the 0…3 scale. Flat, crisp, no needle, no gauge, no glow, no scenic).
    item { StressHeroCard(model, modifier = Modifier.staggeredAppear(0)) }

    // 2 · Today's markers — uniform fixed-height tiles, two-up.
    item {
        Column(
            modifier = Modifier.staggeredAppear(1),
            verticalArrangement = Arrangement.spacedBy(Metrics.gap),
        ) {
            SectionHeader("Today", overline = "Markers", trailing = "vs 30-day baseline")
            StressTiles(model)
        }
    }

    // 3 · Today's intraday timeline — when in the day stress ran high, + a passive Breathe
    //     suggestion when the recent hours stay elevated.
    if (daytime != null && daytime.scored.isNotEmpty()) {
        item { StressDaytimeSection(daytime, onBreathe, modifier = Modifier.staggeredAppear(2), useEngine = useEngine) }
    }

    // 4 · Trend over the chosen window.
    item { StressTrendSection(model, modifier = Modifier.staggeredAppear(3)) }

    // 5 · Transparency — how the number is built.
    item { StressMethodologyCard(model, modifier = Modifier.staggeredAppear(4)) }
}

// MARK: - 1 · Hero — the NOOP count-up PipBar (the needle/speedometer is gone)
//
// Aaron's call mirrored from iOS: "remove the needle, it's not needed" + "straight
// horizontal bars that almost count up separated by pips". So the hero reads as one clean
// WHOOP-style block — a big WHITE CountUpText value with "of 3" + the band word beside it,
// over a PipBar on the 0…3 scale tinted by the live stress band (calm blue → steady green →
// tense amber). The SYNTHESIS number stays textPrimary (white), never the band colour.

@Composable
private fun StressHeroCard(model: StressModel, modifier: Modifier = Modifier) {
    val bandColor = StressRamp.color(model.score)
    NoopCard(tint = Palette.stressColor, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Overline("Stress monitor", modifier = Modifier.weight(1f))
                StatePill(model.band.title, tone = model.band.tone, showsDot = true)
            }

            // Big count-up value + "of 3", with the band word beside it (no needle).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUpText(
                        value = model.score,
                        format = { String.format(Locale.US, "%.1f", it) },
                        style = NoopType.display(52f),
                        color = Palette.textPrimary,
                    )
                    Text(
                        "of 3",
                        style = NoopType.number(15f, FontWeight.Medium),
                        color = Palette.textTertiary,
                        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    model.band.title,
                    style = NoopType.overline,
                    color = bandColor,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // The NOOP signature: a count-up PipBar on the 0…3 scale, band-tinted.
            PipBar(
                value = model.score.toFloat(),
                range = 0f..3f,
                segments = 21,
                tint = bandColor,
                height = 12.dp,
                modifier = Modifier.semantics {
                    contentDescription =
                        "Stress ${String.format(Locale.US, "%.1f", model.score)} of 3, ${model.band.title}"
                },
            )

            // One plain-English line, full width under the bar.
            Text(
                model.explanation,
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
        }
    }
}

// MARK: - 3 · Daytime timeline (intraday, same 0–3 proxy)

@Composable
private fun StressDaytimeSection(
    day: DaytimeStress.Result,
    onBreathe: () -> Unit,
    modifier: Modifier = Modifier,
    useEngine: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader("Today's Timeline", overline = "Intraday", trailing = timelineTrailing(day))

        NoopCard(tint = Palette.stressColor) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Overline("Stress through the day", modifier = Modifier.weight(1f))
                    val peak = day.peak
                    val peakLevel = peak?.level
                    if (peak != null && peakLevel != null) {
                        Text(
                            "peak ${String.format(Locale.US, "%.1f", peakLevel)} · ${hourLabel(peak.hour)}",
                            style = NoopType.captionNumber,
                            color = StressRamp.color(peakLevel),
                        )
                    }
                }

                // Autonomic-load LINE for the day, drawn in the same blue→green→amber WHOOP
                // ramp as the hero PipBar (README screen 9 "day autonomic-load line").
                DaytimeStressLine(day.hours)

                // Hour ruler under the line (first / midday / last covered hour).
                // start padding matches stressYAxisWidth so labels align with the chart area.
                val lo = day.hours.firstOrNull()?.hour
                val hi = day.hours.lastOrNull()?.hour
                if (lo != null && hi != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = stressYAxisWidth)) {
                        Text(hourLabel(lo), style = NoopType.footnote, color = Palette.textTertiary)
                        Spacer(Modifier.weight(1f))
                        Text(hourLabel((lo + hi) / 2), style = NoopType.footnote, color = Palette.textTertiary)
                        Spacer(Modifier.weight(1f))
                        Text(hourLabel(hi), style = NoopType.footnote, color = Palette.textTertiary)
                    }
                }

                Text(
                    if (useEngine)
                        "The line traces your autonomic load across the waking day, scored against " +
                            "your own baseline (your typical resting heart rate), read hour by hour — " +
                            "the same 0–3 proxy as the score above. Hours without enough data are skipped."
                    else
                        "The line traces your autonomic load across the waking day, scored " +
                            "against your own calm hours today — the same 0–3 proxy as the score " +
                            "above, read hour by hour. Hours without enough data are skipped.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }

        // Totals bar — how the scored hours split across Calm (blue) / Moderate (green) /
        // High (amber), mirroring the README screen-9 split.
        StressTotalsBar(day)

        // Sustained-high suggestion — only when the recent run stays in the HIGH band.
        if (day.sustainedHigh) SustainedBreatheCard(day, onBreathe)
    }
}

// MARK: - Daytime autonomic-load line (gradient, same scale as the gauge)
//
// Interactive Canvas line over the scored waking hours. Tap or drag to scrub across hours
// and see the stress level at a specific time in a tooltip pill. The Y-axis shows the 0–3
// scale with hairline grid lines. Unscored hours break the line (honest gap, no interpolation).

// Width reserved for the Y-axis labels. Matches the start padding on the X-axis ruler row.
private val stressYAxisWidth = 32.dp

@Composable
private fun DaytimeStressLine(hours: List<DaytimeStress.HourPoint>) {
    val levels = remember(hours) { hours.map { it.level } }
    if (levels.size < 2) return

    var scrubFrac by remember { mutableStateOf<Float?>(null) }
    // Same blue→green→amber WHOOP ramp as the hero PipBar / totals bar (no gold).
    val gradient = remember { Brush.horizontalGradient(*StressRamp.stops.toTypedArray()) }

    // Capture Compose colors at composition time — DrawScope lambdas run on the render thread.
    val hairline = Palette.hairline
    val textTertiary = Palette.textTertiary
    val textPrimary = Palette.textPrimary
    val stressColor = Palette.stressColor
    val yAxisPx = with(LocalDensity.current) { stressYAxisWidth.toPx() }

    // PERF (#scroll-jank — drawing-bound): this chart is scrubbable, so a finger drag re-records the
    // whole Canvas at ~60fps. Previously every frame rebuilt the gradient line + fill Paths and the
    // axis Paint from scratch, even though only the crosshair moves. Split the chart into two layers:
    //   • a STATIC base — the axis grid/labels and the gradient line+fill — drawn via `drawWithCache`,
    //     which rebuilds the Paths + label Paint ONLY when `levels`/size change (NOT per scrub frame);
    //   • a thin DYNAMIC overlay Canvas — just the crosshair, dot and tooltip — that reads `scrubFrac`.
    // The geometry (yAxisPx, 8dp top/bot pad, yFor, stepX) is byte-identical to the old single Canvas,
    // and the two layers share the same Box bounds, so the rendered pixels are unchanged. Hoisted Paints.
    val labelPaint = remember(textTertiary) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 22f
            textAlign = android.graphics.Paint.Align.RIGHT
            color = textTertiary.toArgb()
        }
    }
    val tooltipPaint = remember(textPrimary) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 26f
            color = textPrimary.toArgb()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    fun DrawScope.yFor(level: Double, topPad: Float, usable: Float): Float =
        topPad + (1f - (level / 3.0).coerceIn(0.0, 1.0).toFloat()) * usable

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .semantics { contentDescription = daytimeLineDescription(hours) }
            .pointerInput(hours) {
                // Single gesture handler: first touch shows the crosshair; dragging scrubs
                // across hours; lifting the finger clears it.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val chartW = (size.width - yAxisPx).coerceAtLeast(1f)
                    scrubFrac = ((down.position.x - yAxisPx) / chartW).coerceIn(0f, 1f)
                    var ptr = down
                    while (ptr.pressed) {
                        val event = awaitPointerEvent()
                        ptr = event.changes.firstOrNull() ?: break
                        if (ptr.pressed) {
                            ptr.consume()
                            scrubFrac = ((ptr.position.x - yAxisPx) / chartW).coerceIn(0f, 1f)
                        }
                    }
                    scrubFrac = null
                }
            },
    ) {
        // STATIC base layer — axis + gradient line/fill. `drawWithCache` rebuilds the cached Paths only
        // when the chart's size or `levels` change (the cache block reads neither `scrubFrac`), so a
        // scrub drag never re-walks the run-builder or re-allocates Paths. Same draw order as before.
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val topPad = 8.dp.toPx()
                    val botPad = 8.dp.toPx()
                    val usable = (h - topPad - botPad).coerceAtLeast(1f)
                    val chartLeft = yAxisPx
                    val chartW = (w - chartLeft).coerceAtLeast(1f)
                    val stepX = if (levels.size > 1) chartW / (levels.size - 1) else chartW
                    fun yForC(level: Double): Float =
                        topPad + (1f - (level / 3.0).coerceIn(0.0, 1.0).toFloat()) * usable

                    // Pre-build the gradient line + fill Paths for each contiguous run (null breaks).
                    data class Run(val fill: Path?, val line: Path?, val dot: Offset?, val dotColor: Color?)
                    val runs = ArrayList<Run>()
                    var i = 0
                    while (i < levels.size) {
                        if (levels[i] == null) { i++; continue }
                        var j = i
                        val pts = ArrayList<Offset>()
                        while (j < levels.size && levels[j] != null) {
                            pts.add(Offset(chartLeft + j * stepX, yForC(levels[j]!!)))
                            j++
                        }
                        if (pts.size >= 2) {
                            val fill = Path().apply {
                                moveTo(pts.first().x, h - botPad)
                                pts.forEach { lineTo(it.x, it.y) }
                                lineTo(pts.last().x, h - botPad)
                                close()
                            }
                            val line = Path().apply {
                                moveTo(pts.first().x, pts.first().y)
                                for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y)
                            }
                            runs.add(Run(fill, line, null, null))
                        } else if (pts.size == 1) {
                            runs.add(Run(null, null, pts.first(), StressRamp.color(levels[i]!!)))
                        }
                        i = j
                    }
                    val strokeW = 3.dp.toPx()
                    val dotR = 2.5.dp.toPx()

                    onDrawBehind {
                        if (w <= 0f || h <= 0f) return@onDrawBehind
                        // Y-axis: hairline grid lines + scale labels 0 / 1 / 2 / 3.
                        listOf(0.0, 1.0, 2.0, 3.0).forEach { lvl ->
                            val y = yForC(lvl)
                            drawLine(color = hairline, start = Offset(chartLeft, y), end = Offset(w, y), strokeWidth = 1f)
                            drawContext.canvas.nativeCanvas.drawText(lvl.toInt().toString(), chartLeft - 6f, y + 8f, labelPaint)
                        }
                        // Gradient line + fill — contiguous runs (null levels break the line).
                        runs.forEach { r ->
                            if (r.fill != null) drawPath(r.fill, brush = gradient, alpha = StrandAlpha.chartFillSoft + 0.10f)
                            if (r.line != null) drawPath(r.line, brush = gradient, style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            if (r.dot != null && r.dotColor != null) drawCircle(color = r.dotColor, radius = dotR, center = r.dot)
                        }
                    }
                },
        )

        // DYNAMIC overlay — crosshair + dot + tooltip pill, drawn only while the finger is down. A thin
        // Canvas that re-records on each scrub frame; the heavy static Paths above are untouched.
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val frac = scrubFrac ?: return@Canvas

            val topPad = 8.dp.toPx()
            val botPad = 8.dp.toPx()
            val usable = (h - topPad - botPad).coerceAtLeast(1f)
            val chartLeft = yAxisPx
            val chartW = (w - chartLeft).coerceAtLeast(1f)
            val stepX = if (levels.size > 1) chartW / (levels.size - 1) else chartW

            val scrubIdx = (frac * (levels.size - 1)).roundToInt().coerceIn(0, levels.size - 1)
            val scrubX = chartLeft + scrubIdx * stepX
            val pt = hours[scrubIdx]

            // Dashed vertical crosshair at the selected hour.
            drawLine(
                color = textTertiary,
                start = Offset(scrubX, topPad),
                end = Offset(scrubX, h - botPad),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
            )

            val lvl = pt.level
            if (lvl != null) {
                val dotY = yFor(lvl, topPad, usable)
                // Ring dot at the scrubbed point.
                drawCircle(color = stressColor, radius = 5.dp.toPx(), center = Offset(scrubX, dotY))
                drawCircle(color = Palette.tipCore, radius = 2.5.dp.toPx(), center = Offset(scrubX, dotY))

                // Tooltip pill: "9 am · 1.4" — avoid String.format; use integer tenths.
                val tenths = (lvl * 10).roundToInt().coerceIn(0, 30)
                val label = "${hourLabel(pt.hour)} · ${tenths / 10}.${tenths % 10}"
                val textW = tooltipPaint.measureText(label)
                val pillPad = 10f
                val pillW = textW + pillPad * 2
                val pillH = 34f
                val pillX = (scrubX - pillW / 2f).coerceIn(chartLeft, w - pillW)
                val pillY = (dotY - pillH - 10.dp.toPx()).coerceAtLeast(topPad)
                drawRoundRect(
                    color = stressColor.copy(alpha = 0.18f),
                    topLeft = Offset(pillX, pillY),
                    size = Size(pillW, pillH),
                    cornerRadius = CornerRadius(pillH / 2),
                )
                drawContext.canvas.nativeCanvas.drawText(label, pillX + pillW / 2f, pillY + pillH * 0.68f, tooltipPaint)
            }
        }
    }
}

private fun daytimeLineDescription(hours: List<DaytimeStress.HourPoint>): String {
    val scored = hours.mapNotNull { p -> p.level?.let { p.hour to it } }
    if (scored.isEmpty()) return "No intraday stress data yet today."
    val parts = scored.map { "${it.first}:00 ${String.format(Locale.US, "%.1f", it.second)}" }
    return "Hourly stress today: " + parts.joinToString(", ")
}

// MARK: - Totals bar — Calm / Moderate / High split of the scored waking hours
//
// A single proportional bar that splits the day's SCORED hours into the three bands
// (Calm 0–1 = blue, Moderate 1–2 = green, High 2–3 = amber), each segment widthed
// by its share of the scored hours, with a small legend reading "Calm 6h · Moderate 4h ·
// High 3h". Flat segments, hairline-separated — README screen-9 totals bar.

@Composable
private fun StressTotalsBar(day: DaytimeStress.Result) {
    val scored = day.scored
    if (scored.isEmpty()) return

    val calm = scored.count { (it.level ?: 0.0) < 1.0 }
    val high = scored.count { (it.level ?: 0.0) >= 2.0 }
    val moderate = scored.size - calm - high
    val total = scored.size.toFloat()

    NoopCard(tint = Palette.stressColor) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Overline("Time in band")

            // The proportional bar — three flat segments on the inset track, round outer ends.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Metrics.progressHeight)
                    .clip(RoundedCornerShape(Metrics.cornerBadge))
                    .background(Palette.surfaceInset),
            ) {
                if (calm > 0) {
                    Box(
                        modifier = Modifier
                            .weight(calm / total)
                            .fillMaxHeight()
                            .background(StressTotalsBand.Calm.color),
                    )
                }
                if (moderate > 0) {
                    Box(
                        modifier = Modifier
                            .weight(moderate / total)
                            .fillMaxHeight()
                            .background(StressTotalsBand.Moderate.color),
                    )
                }
                if (high > 0) {
                    Box(
                        modifier = Modifier
                            .weight(high / total)
                            .fillMaxHeight()
                            .background(StressTotalsBand.High.color),
                    )
                }
            }

            // Legend — one entry per non-empty band, "<swatch> Calm · 6h".
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TotalsLegendItem(StressTotalsBand.Calm, calm)
                TotalsLegendItem(StressTotalsBand.Moderate, moderate)
                TotalsLegendItem(StressTotalsBand.High, high)
            }
        }
    }
}

private enum class StressTotalsBand(val title: String, val color: Color) {
    Calm("Calm", StressRamp.CALM),         // blue — low stress
    Moderate("Moderate", StressRamp.STEADY), // green — balanced
    High("High", StressRamp.TENSE),        // amber — high
}

@Composable
private fun TotalsLegendItem(band: StressTotalsBand, hours: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(Metrics.legendSwatch)
                .clip(CircleShape)
                .background(if (hours > 0) band.color else Palette.surfaceInset),
        )
        Text(
            "${band.title} · ${hours}h",
            style = NoopType.captionNumber,
            color = if (hours > 0) Palette.textSecondary else Palette.textTertiary,
        )
    }
}

/** "avg 1.4 · 9h" summary for the timeline header, from the scored hours. */
private fun timelineTrailing(day: DaytimeStress.Result): String {
    val n = day.scored.size
    val mean = day.dayMean ?: return "${n}h"
    return "avg " + String.format(Locale.US, "%.1f", mean) + " · ${n}h"
}

/**
 * A passive, in-app nudge to run a Breathe session after a sustained high-stress run. No
 * notification — just a card with a CTA that opens the existing trainer.
 */
@Composable
private fun SustainedBreatheCard(day: DaytimeStress.Result, onBreathe: () -> Unit) {
    NoopCard(tint = Palette.stressColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Overline("Sustained high stress", modifier = Modifier.weight(1f))
                StatePill("${day.sustainedRun}h elevated", tone = StrandTone.Warning, showsDot = true)
            }
            Text(
                "Your last ${day.sustainedRun} hours have stayed in the high band. A few minutes " +
                    "of paced breathing can help downshift your nervous system.",
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            NoopButton(
                text = "Start a Breathe session",
                leadingIcon = Icons.Filled.Air,
                kind = NoopButtonKind.Primary,
                fullWidth = true,
                onClick = onBreathe,
            )
        }
    }
}

/** "6 am" / "2 pm" style hour-of-day label. */
private fun hourLabel(hour: Int): String {
    val h = ((hour % 24) + 24) % 24
    val ampm = if (h < 12) "am" else "pm"
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12 $ampm"
}


// MARK: - 2 · Today's tiles (uniform grid)

@Composable
private fun StressTiles(model: StressModel) {
    val tiles = listOf<@Composable (Modifier) -> Unit>(
        { m ->
            // Today's stress value, with its band as the caption.
            StatTile(
                modifier = m,
                label = "Stress",
                value = String.format(Locale.US, "%.1f", model.score),
                caption = "of 3 · ${model.band.title}",
                accent = StressRamp.color(model.score),
            )
        },
        { m ->
            // Resting HR — an INCREASE is the stressful direction.
            MarkerTile(
                modifier = m,
                label = "Resting HR",
                value = model.rhrToday?.let { "$it bpm" } ?: "—",
                delta = model.rhrDelta,
                accent = Palette.metricRose,
                higherIsStress = true,
            )
        },
        { m ->
            // HRV — a DECREASE is the stressful direction.
            MarkerTile(
                modifier = m,
                label = "HRV",
                value = model.hrvToday?.let { "${it.roundToInt()} ms" } ?: "—",
                delta = model.hrvDelta,
                accent = Palette.metricPurple,
                higherIsStress = false,
            )
        },
        { m ->
            // Estimated calm time — share of recent days spent in the LOW band.
            StatTile(
                modifier = m,
                label = "Calm time",
                value = model.calmTimeValue,
                caption = model.calmTimeCaption,
                accent = StressRamp.CALM,
            )
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                rowTiles.forEach { tile -> tile(Modifier.weight(1f)) }
                if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * A vs-baseline marker as a fixed-height [StatTile]. The delta is tinted by whether
 * the move is toward stress (warning) or recovery (positive). Mirrors macOS markerTile.
 */
@Composable
private fun MarkerTile(
    label: String,
    value: String,
    delta: Double?,
    accent: Color,
    higherIsStress: Boolean,
    modifier: Modifier = Modifier,
) {
    val deltaText: String
    val deltaColor: Color
    if (delta != null && kotlin.math.abs(delta) >= 0.5) {
        val up = delta > 0
        val isStressful = (up == higherIsStress)
        deltaText = "${if (up) "+" else "−"}${kotlin.math.abs(delta).roundToInt()} vs base"
        deltaColor = if (isStressful) Palette.statusWarning else Palette.statusPositive
    } else {
        deltaText = "at baseline"
        deltaColor = Palette.textTertiary
    }
    StatTile(
        modifier = modifier,
        label = label,
        value = value,
        accent = accent,
        delta = deltaText,
        deltaColor = deltaColor,
    )
}

// MARK: - 3 · Trend (range-controlled)

@Composable
private fun StressTrendSection(model: StressModel, modifier: Modifier = Modifier) {
    var range by remember { mutableStateOf(StressRange.Month) }
    val points = remember(model, range) { model.windowedTrend(range) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader("Stress Trend", overline = "History", trailing = range.label)
        if (points.size >= 2) {
            val avg = points.average()
            NoopCard(tint = Palette.stressColor) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Overline("Stress · ${range.label}")
                            Text(
                                "Daily 0–3 proxy",
                                style = NoopType.footnote,
                                color = Palette.textTertiary,
                            )
                        }
                        Text(
                            "avg " + String.format(Locale.US, "%.1f", avg),
                            style = NoopType.captionNumber,
                            color = Palette.textSecondary,
                        )
                    }
                    LineChart(
                        values = points,
                        modifier = Modifier.height(Metrics.chartHeight),
                        color = StressRamp.STEADY,
                        fill = true,
                        selectionEnabled = true,
                    )
                    HorizontalDivider(color = Palette.hairline)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TrendFooterItem("Today", String.format(Locale.US, "%.1f", model.score))
                        TrendFooterItem("Average", String.format(Locale.US, "%.1f", avg))
                        TrendFooterItem("Days", points.size.toString())
                    }
                }
            }
            // The one segmented control — full width, right-aligned.
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                SegmentedPillControl(
                    items = StressRange.entries,
                    selection = range,
                    label = { it.label },
                    onSelect = { range = it },
                )
            }
        } else {
            NoopCard(tint = Palette.stressColor) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Not enough recent days to chart a trend yet. Keep wearing your strap to populate it.",
                        style = NoopType.subhead,
                        color = Palette.textTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TrendFooterItem(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Overline(label)
        Text(value, style = NoopType.number(18f), color = Palette.textPrimary)
    }
}

// MARK: - 4 · Methodology (transparency)

@Composable
private fun StressMethodologyCard(model: StressModel, modifier: Modifier = Modifier) {
    NoopCard(tint = Palette.stressColor, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Overline("How this is computed")
            Text(
                if (model.usingStored) {
                    "Today's value is your recorded daily stress score (0–3)."
                } else {
                    "Stress is derived from two autonomic signals."
                },
                style = NoopType.body,
                color = Palette.textPrimary,
            )
            Text(
                "We compare today's resting heart rate and HRV to your own 30-day " +
                    "baseline. A higher-than-usual resting HR and a lower-than-usual HRV " +
                    "both push the score up — classic signs the body is activated. The " +
                    "combined shift is mapped onto a 0–3 scale: 0 is calm, 1.5 sits at " +
                    "your baseline, 3 is highly activated.",
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            HorizontalDivider(color = Palette.hairline)
            Row(modifier = Modifier.fillMaxWidth()) {
                BandLegend("0–1", "LOW", StressRamp.CALM)
                BandLegend("1–2", "MEDIUM", StressRamp.STEADY)
                BandLegend("2–3", "HIGH", StressRamp.TENSE)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BandLegend(range: String, label: String, color: Color) {
    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = NoopType.captionNumber, color = Palette.textPrimary)
            Text(range, style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
}

// MARK: - Empty / loading states

@Composable
private fun StressLoading() {
    NoopCard {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Reading your heart-rate variability and resting heart rate…",
                style = NoopType.subhead,
                color = Palette.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StressEmpty() {
    DataPendingNote(
        title = "No stress history yet",
        body = "No stress history yet. Import your WHOOP export in Data Sources to see it.",
    )
}

// MARK: - Stress band

private enum class StressBand(val title: String, val tone: StrandTone) {
    Low("LOW", StrandTone.Positive),
    Medium("MEDIUM", StrandTone.Warning),
    High("HIGH", StrandTone.Critical);

    companion object {
        fun forScore(score: Double): StressBand = when {
            score < 1.0 -> Low
            score < 2.0 -> Medium
            else -> High
        }
    }
}

// MARK: - Stress ramp (the WHOOP Stress sweep: blue → green → amber)
//
// The Stress screen's one ramp, matching the iOS StressRamp exactly. WHOOP has NO gold:
// calm reads as the link blue, a balanced day as positive green, and a high-stress day as
// warning amber. The PipBar tint, the day autonomic-load line, the Calm/Moderate/High totals
// bar and the trend all sample this SAME ramp, so the colour language is identical across the
// screen. Never the gold or red→green recovery ramp.

private object StressRamp {
    val CALM = Palette.accent           // calm WHOOP blue — low
    val STEADY = Palette.statusPositive // balanced WHOOP green — baseline
    val TENSE = Palette.statusWarning   // high WHOOP amber — high

    /** The 3-stop ramp, evenly spaced (blue → green → amber). */
    val stops: List<Pair<Float, Color>> = listOf(
        0.00f to CALM,
        0.50f to STEADY,
        1.00f to TENSE,
    )

    /** Sample the ramp at a 0–3 stress score. */
    fun color(score: Double): Color = Palette.sample(stops, (score / 3.0).toFloat())
}

// MARK: - Trend range (the W/M/3M/6M/1Y/ALL window, mirroring ExploreRange)

private enum class StressRange(val label: String, val days: Int?) {
    Week("W", 7),
    Month("M", 30),
    Quarter("3M", 90),
    Half("6M", 180),
    Year("1Y", 365),
    All("ALL", null),
}

// MARK: - Stress model (transparent: stored value OR z-score derivation)

private class StressModel private constructor(
    val score: Double,            // 0–3 (today)
    val band: StressBand,
    val explanation: String,
    val rhrToday: Int?,
    val hrvToday: Double?,
    val rhrDelta: Double?,        // today − baseline mean (bpm)
    val hrvDelta: Double?,        // today − baseline mean (ms)
    val fullTrend: List<TrendPoint>, // entire daily proxy history, oldest → newest
    val calmTimeValue: String,
    val calmTimeCaption: String,
    val usingStored: Boolean,     // true when today's value came from the stored series
) {
    data class TrendPoint(val day: String, val value: Double)

    /** The full daily proxy trend, sliced to the selected trailing window (count-based,
     *  matching the day budget). Falls back to ALL when the trailing slice has < 2 points. */
    fun windowedTrend(range: StressRange): List<Double> {
        val all = fullTrend.map { it.value }
        val days = range.days ?: return all
        val slice = fullTrend.takeLast(days).map { it.value }
        return if (slice.size >= 2) slice else all
    }

    companion object {
        /** Build from oldest→newest daily metrics plus any stored "stress" series.
         *  Returns null only when there is no usable signal at all.
         *  [liveOverride] (opt-in calibrated engine): when non-null it replaces the HERO/current score
         *  with a live recent-window value (≈last 15 min), while the trend stays per-day. */
        fun build(days: List<DailyMetric>, stored: Map<String, Double>, liveOverride: Double? = null): StressModel? {
            val today = days.lastOrNull() ?: return null

            // Baseline window: up to 30 days ending the day BEFORE today, so "today" is
            // measured against its own recent past rather than itself.
            val history = if (days.size > 1) days.dropLast(1) else emptyList()
            val baseline = history.takeLast(30)

            val rhrBase = baseline.mapNotNull { it.restingHr?.toDouble() }
            val hrvBase = baseline.mapNotNull { it.avgHrv }

            val meanRHR = mean(rhrBase)
            val sdRHR = std(rhrBase, meanRHR)
            val meanHRV = mean(hrvBase)
            val sdHRV = std(hrvBase, meanHRV)

            val rhrT = today.restingHr?.toDouble()
            val hrvT = today.avgHrv

            val derivedAvailable = (rhrT != null && meanRHR != null) || (hrvT != null && meanHRV != null)
            val storedToday = stored[today.day]
            if (storedToday == null && !derivedAvailable && liveOverride == null) return null

            val derivedToday: Double? = if (derivedAvailable) {
                squash(rawScore(rhrT, meanRHR, sdRHR, hrvT, meanHRV, sdHRV))
            } else {
                null
            }

            // Hero/current score: the live recent-window value when the calibrated engine drives it,
            // else today's stored/derived daily value.
            val s = liveOverride?.coerceIn(0.0, 3.0) ?: storedToday ?: derivedToday ?: 1.5
            val usingStored = liveOverride == null && storedToday != null
            val band = StressBand.forScore(s)
            val rhrDelta = if (rhrT != null && meanRHR != null) rhrT - meanRHR else null
            val hrvDelta = if (hrvT != null && meanHRV != null) hrvT - meanHRV else null
            val explanation = explanation(band, rhrDelta, hrvDelta)

            // Full daily proxy history: stored value if present for the day, else the
            // z-score derivation against the SAME baseline so the line is comparable.
            val pts = ArrayList<TrendPoint>()
            for (d in days) {
                val v = stored[d.day]
                if (v != null) {
                    pts.add(TrendPoint(d.day, v.coerceIn(0.0, 3.0)))
                    continue
                }
                val dRHR = d.restingHr?.toDouble()
                val dHRV = d.avgHrv
                if ((dRHR == null || meanRHR == null) && (dHRV == null || meanHRV == null)) continue
                pts.add(TrendPoint(d.day, squash(rawScore(dRHR, meanRHR, sdRHR, dHRV, meanHRV, sdHRV))))
            }

            // "Calm time": share of the last 30 charted days that sat in the LOW band.
            val recent = pts.takeLast(30)
            val calmValue: String
            val calmCaption: String
            if (recent.isEmpty()) {
                calmValue = "—"
                calmCaption = "needs history"
            } else {
                val calm = recent.count { it.value < 1.0 }
                val pct = (calm.toDouble() / recent.size * 100).roundToInt()
                calmValue = "$pct%"
                calmCaption = "low-stress days · ${recent.size}d"
            }

            return StressModel(
                score = s,
                band = band,
                explanation = explanation,
                rhrToday = today.restingHr,
                hrvToday = hrvT,
                rhrDelta = rhrDelta,
                hrvDelta = hrvDelta,
                fullTrend = pts,
                calmTimeValue = calmValue,
                calmTimeCaption = calmCaption,
                usingStored = usingStored,
            )
        }

        // MARK: Stress math (pure helpers, ported from StressMath)

        private fun mean(xs: List<Double>): Double? =
            if (xs.isEmpty()) null else xs.sum() / xs.size

        /** Population standard deviation; 0 when there's no spread. */
        private fun std(xs: List<Double>, m: Double?): Double {
            if (m == null || xs.size <= 1) return 0.0
            val v = xs.sumOf { (it - m) * (it - m) } / xs.size
            return sqrt(v)
        }

        /** Combined autonomic z-score. RHR-up and HRV-down both push it positive. */
        private fun rawScore(
            rhrToday: Double?, meanRHR: Double?, sdRHR: Double,
            hrvToday: Double?, meanHRV: Double?, sdHRV: Double,
        ): Double {
            var sum = 0.0
            if (rhrToday != null && meanRHR != null && sdRHR > 0.0001) {
                sum += (rhrToday - meanRHR) / sdRHR        // up = stress
            }
            if (hrvToday != null && meanHRV != null && sdHRV > 0.0001) {
                sum += (meanHRV - hrvToday) / sdHRV        // down = stress
            }
            return sum
        }

        /** Logistic squash of the raw z-sum onto 0–3 (baseline 0 → 1.5). */
        private fun squash(raw: Double): Double =
            (3.0 / (1.0 + exp(-raw))).coerceIn(0.0, 3.0)

        private fun explanation(band: StressBand, rhrDelta: Double?, hrvDelta: Double?): String {
            val rhrUp = (rhrDelta ?: 0.0) > 1.0
            val hrvDn = (hrvDelta ?: 0.0) < -1.0
            val hrvUp = (hrvDelta ?: 0.0) > 1.0
            val rhrDn = (rhrDelta ?: 0.0) < -1.0
            return when (band) {
                StressBand.High -> when {
                    rhrUp && hrvDn -> "Resting HR is elevated and HRV is below your baseline — both classic signs of high activation. Prioritise rest, hydration and an easy day."
                    hrvDn -> "HRV has dropped well below your baseline, pointing to elevated stress or fatigue. Ease off and give your body time to recover."
                    rhrUp -> "Resting heart rate is running high versus your norm — your body is under load today. Keep effort light."
                    else -> "Your autonomic markers are skewed toward stress today. Treat it as a recovery-focused day."
                }
                StressBand.Medium -> when {
                    rhrUp || hrvDn -> "Slightly off baseline — ${if (rhrUp) "resting HR is a touch high" else "HRV is a little low"} — so you're moderately activated. Nothing alarming; just don't overreach."
                    else -> "You're sitting around your typical autonomic baseline — moderate stress, a normal, balanced day."
                }
                StressBand.Low -> when {
                    rhrDn && hrvUp -> "Resting heart rate is low and HRV is up — your nervous system looks well-recovered and calm. A great day to push if you want to."
                    hrvUp -> "HRV is above baseline, a sign of a relaxed, well-recovered nervous system. Stress is low."
                    else -> "Resting heart rate and HRV are sitting at or below baseline — low physiological stress. You're in a calm, recovered state."
                }
            }
        }
    }
}
