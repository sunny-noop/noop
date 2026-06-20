# Android Health Connect Export-Gap Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four export record types — Active Energy, Heart Rate, Steps, Sleep — to the Android app's existing `HealthConnectWriter`, so a WHOOP synced on Linux/Android surfaces those NOOP-computed metrics into Health Connect.

**Architecture:** All decision logic (day bucketing, HR decimation/windowing/chunking, sleep stage mapping, write-frontier advance) lives in a new **pure** object `HealthExportPlan` that takes/returns plain Kotlin types and is unit-tested offline on the JVM — mirroring the existing `HealthConnectImporter.sumActiveKcalInWindow` test pattern. `HealthConnectWriter` gains thin, untested adapter glue that turns the plan's plain descriptors into Health Connect SDK records (like the existing `buildExerciseRecords`). Heart Rate uses a persisted frontier cursor so each 15-min writeback only emits new samples; daily aggregates ride the existing 60-day re-write loop; sleep is written per finalized session.

**Tech Stack:** Kotlin, `androidx.health.connect:connect-client:1.1.0-alpha07`, JUnit4, `org.json` (real impl on the unit-test classpath), Gradle (flavor `full`, so unit-test task is `testFullDebugUnitTest`).

**Spec:** `docs/superpowers/specs/2026-06-18-android-health-connect-export-design.md`

**Branch:** `feat/android-hc-export-gaps` (already created off `origin/main`; identity `sunny_noop`).

---

## Reference: confirmed existing APIs

These already exist and the tasks below depend on them — do not re-implement:

- `HealthConnectWriter` (`app/src/main/java/com/noop/ingest/HealthConnectWriter.kt`): `object`, with `WINDOW_DAYS = 60L`, `WRITE_RECORDS: List<KClass<out Record>>`, `PERMISSIONS`, `suspend fun write(context, repo, deviceId="my-whoop")`, `private fun meta(metric, day, version)`, and the pure `fun buildExerciseRecords(row, exerciseType): List<Record>`.
- `WhoopRepository` (`app/src/main/java/com/noop/data/WhoopRepository.kt`):
  - `suspend fun days(deviceId: String): List<DailyMetric>`
  - `fun computedDeviceId(deviceId: String): String` (= `"$deviceId-noop"`)
  - `suspend fun hrSamples(deviceId, from: Long, to: Long, limit: Int = DEFAULT_LIMIT): List<HrSample>`
  - `suspend fun sleepSessions(deviceId, from: Long, to: Long, limit: Int = DEFAULT_LIMIT): List<SleepSession>`
  - `suspend fun workouts(deviceId, from: Long, to: Long, limit: Int = DEFAULT_LIMIT): List<WorkoutRow>`
- Entities (`app/src/main/java/com/noop/data/Entities.kt`):
  - `DailyMetric(deviceId, day: String, …, steps: Int?, activeKcalEst: Double?, restingHr: Int?, …)`
  - `HrSample(deviceId, ts: Long /*epoch sec*/, bpm: Int, …)`
  - `SleepSession(deviceId, startTs: Long, endTs: Long, …, stagesJSON: String?)`
  - `WorkoutRow(deviceId, startTs: Long, endTs: Long, …)`
- `stagesJSON` shape: a JSON array of `{"start": <epochSec>, "end": <epochSec>, "stage": "wake"|"awake"|"light"|"deep"|"rem"}` (confirmed by `WhoopRepository.sleepEfficiency`, which classifies `stage != "wake" && stage != "awake"` as asleep).
- `NoopPrefs` (`object` inside `app/src/main/java/com/noop/ui/MainActivity.kt`, around line 142): pref accessors follow `of(context).getLong(KEY, default)` / `of(context).edit().putLong(KEY, v).apply()`; existing HC keys `KEY_HC_AUTO_SYNC`, `KEY_HC_LAST_SYNC`, `KEY_HC_WRITEBACK`.

> **Assumption to verify in Task 5:** `steps` and `activeKcalEst` are populated on the **computed** device's `DailyMetric` rows (`computedDeviceId(deviceId)`), the same rows the existing writeback reads. Confirm by grepping `AnalyticsEngine` for where it upserts `DailyMetric` with `steps`/`activeKcalEst`. If they live on the raw device id instead, read aggregates from `repo.days(deviceId)` rather than `repo.days(computedDeviceId(deviceId))` in Task 5.

---

## Task 1: Pure planner — daily aggregates (Active Energy + Steps)

**Files:**
- Create: `app/src/main/java/com/noop/ingest/HealthExportPlan.kt`
- Test: `app/src/test/java/com/noop/ingest/HealthExportPlanTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noop/ingest/HealthExportPlanTest.kt`:

```kotlin
package com.noop.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthExportPlanTest {

    // Fixed bounds: day "D" -> (1000, 2000) so tests are zone-independent.
    private val bounds: (String) -> Pair<Long, Long>? =
        { day -> if (day == "D") 1000L to 2000L else null }

    @Test fun dailyAggregate_emitsStepsAndKcalForADayWithBoth() {
        val out = HealthExportPlan.dailyAggregates(
            listOf(HealthExportPlan.DayInput(day = "D", steps = 5000, activeKcal = 420.0)),
            bounds,
        )
        assertEquals(1, out.size)
        assertEquals("D", out[0].day)
        assertEquals(1000L, out[0].startEpochSec)
        assertEquals(2000L, out[0].endEpochSec)
        assertEquals(5000L, out[0].steps)
        assertEquals(420.0, out[0].activeKcal!!, 0.001)
    }

    @Test fun dailyAggregate_skipsDayWithNoStepsAndNoKcal() {
        val out = HealthExportPlan.dailyAggregates(
            listOf(HealthExportPlan.DayInput("D", steps = null, activeKcal = null)),
            bounds,
        )
        assertTrue(out.isEmpty())
    }

    @Test fun dailyAggregate_treatsZeroAsAbsent() {
        val out = HealthExportPlan.dailyAggregates(
            listOf(HealthExportPlan.DayInput("D", steps = 0, activeKcal = 0.0)),
            bounds,
        )
        assertTrue(out.isEmpty())
    }

    @Test fun dailyAggregate_skipsDayWithUnresolvableBounds() {
        val out = HealthExportPlan.dailyAggregates(
            listOf(HealthExportPlan.DayInput("UNKNOWN", steps = 100, activeKcal = null)),
            bounds,
        )
        assertTrue(out.isEmpty())
    }

    @Test fun dailyAggregate_emitsStepsOnlyWhenKcalAbsent() {
        val out = HealthExportPlan.dailyAggregates(
            listOf(HealthExportPlan.DayInput("D", steps = 100, activeKcal = null)),
            bounds,
        )
        assertEquals(100L, out[0].steps)
        assertNull(out[0].activeKcal)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFullDebugUnitTest --tests "com.noop.ingest.HealthExportPlanTest"`
Expected: FAIL — `HealthExportPlan` / `DayInput` unresolved reference (compile error).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/noop/ingest/HealthExportPlan.kt`:

```kotlin
package com.noop.ingest

/**
 * Pure (Android-free, HC-SDK-free) planning logic for what NOOP exports INTO Health Connect.
 *
 * Everything here operates on plain Kotlin types so it is unit-testable on the JVM, mirroring
 * [HealthConnectImporter.sumActiveKcalInWindow]. [HealthConnectWriter] turns these descriptors into
 * actual Health Connect records (the untestable SDK glue is kept thin, as in `buildExerciseRecords`).
 */
object HealthExportPlan {

    // ---- Daily aggregates: Active Energy + Steps (one record per local calendar day) ----

    data class DayInput(val day: String, val steps: Int?, val activeKcal: Double?)

    data class DailyAgg(
        val day: String,
        val startEpochSec: Long,
        val endEpochSec: Long,
        val steps: Long?,
        val activeKcal: Double?,
    )

    /**
     * One descriptor per day that has positive steps and/or positive active kcal and resolvable
     * day bounds. [bounds] maps a "YYYY-MM-DD" day to (startOfDayEpochSec, startOfNextDayEpochSec);
     * inject it so zone math stays out of this pure function.
     */
    fun dailyAggregates(
        days: List<DayInput>,
        bounds: (String) -> Pair<Long, Long>?,
    ): List<DailyAgg> {
        val out = ArrayList<DailyAgg>()
        for (d in days) {
            val steps = d.steps?.toLong()?.takeIf { it > 0 }
            val kcal = d.activeKcal?.takeIf { it > 0.0 }
            if (steps == null && kcal == null) continue
            val b = bounds(d.day) ?: continue
            out.add(DailyAgg(d.day, b.first, b.second, steps, kcal))
        }
        return out
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testFullDebugUnitTest --tests "com.noop.ingest.HealthExportPlanTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noop/ingest/HealthExportPlan.kt \
        app/src/test/java/com/noop/ingest/HealthExportPlanTest.kt
git commit -m "feat(android): pure daily-aggregate planner for HC export (energy/steps)"
```

---

## Task 2: Pure planner — heart-rate series (hybrid resolution + chunking + frontier)

**Files:**
- Modify: `app/src/main/java/com/noop/ingest/HealthExportPlan.kt`
- Test: `app/src/test/java/com/noop/ingest/HealthExportPlanTest.kt`

- [ ] **Step 1: Write the failing test** (append these methods inside `HealthExportPlanTest`)

```kotlin
    private fun hr(ts: Long, bpm: Int) = HealthExportPlan.HrPoint(ts, bpm)

    @Test fun heartRate_emptyInputLeavesFrontierUnchanged() {
        val plan = HealthExportPlan.heartRate(emptyList(), emptyList(), frontierSec = 500L)
        assertTrue(plan.chunks.isEmpty())
        assertEquals(500L, plan.newFrontierSec)
    }

    @Test fun heartRate_ignoresSamplesAtOrBelowFrontier() {
        val samples = listOf(hr(100, 60), hr(200, 61), hr(300, 62))
        val plan = HealthExportPlan.heartRate(samples, emptyList(), frontierSec = 200L,
            decimateSec = 1, chunkSec = 3600, maxSamplesPerChunk = 1000)
        val tss = plan.chunks.flatMap { it.points }.map { it.tsSec }
        assertEquals(listOf(300L), tss)
        assertEquals(300L, plan.newFrontierSec)
    }

    @Test fun heartRate_decimatesOutsideWindowsToOnePerInterval() {
        // 1 Hz for 0..120s, no windows, decimate to 30s -> keep 0,30,60,90,120
        val samples = (0..120L).map { hr(it, 60) }
        val plan = HealthExportPlan.heartRate(samples, emptyList(), frontierSec = -1L,
            decimateSec = 30, chunkSec = 3600, maxSamplesPerChunk = 1000)
        val tss = plan.chunks.flatMap { it.points }.map { it.tsSec }
        assertEquals(listOf(0L, 30L, 60L, 90L, 120L), tss)
        assertEquals(120L, plan.newFrontierSec) // frontier advances past ALL seen samples
    }

    @Test fun heartRate_keepsFullResolutionInsideAWindow() {
        val samples = (0..120L).map { hr(it, 70) }
        val window = HealthExportPlan.Window(startSec = 40, endSec = 60) // 21 samples kept in full
        val plan = HealthExportPlan.heartRate(samples, listOf(window), frontierSec = -1L,
            decimateSec = 30, chunkSec = 3600, maxSamplesPerChunk = 1000)
        val tss = plan.chunks.flatMap { it.points }.map { it.tsSec }
        // Every second 40..60 present; outside is decimated.
        assertTrue((40L..60L).all { it in tss })
        assertTrue(tss.count { it in 41..59 } == 19)
    }

    @Test fun heartRate_chunksByTimeSpan() {
        val samples = listOf(hr(0, 60), hr(10, 60), hr(4000, 60)) // gap > 3600s
        val plan = HealthExportPlan.heartRate(samples, listOf(HealthExportPlan.Window(0, 5000)),
            frontierSec = -1L, decimateSec = 1, chunkSec = 3600, maxSamplesPerChunk = 1000)
        assertEquals(2, plan.chunks.size)
        assertEquals("noop-hr-0", plan.chunks[0].clientId)
        assertEquals("noop-hr-4000", plan.chunks[1].clientId)
    }

    @Test fun heartRate_chunksBySampleCount() {
        val samples = (0 until 5L).map { hr(it, 60) }
        val plan = HealthExportPlan.heartRate(samples, listOf(HealthExportPlan.Window(0, 10)),
            frontierSec = -1L, decimateSec = 1, chunkSec = 3600, maxSamplesPerChunk = 2)
        assertEquals(3, plan.chunks.size) // 2 + 2 + 1
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFullDebugUnitTest --tests "com.noop.ingest.HealthExportPlanTest"`
Expected: FAIL — `HealthExportPlan.heartRate` / `HrPoint` / `Window` unresolved.

- [ ] **Step 3: Write minimal implementation** (append inside the `HealthExportPlan` object, after `dailyAggregates`)

```kotlin
    // ---- Heart-rate series: full-res inside workout/sleep windows, decimated elsewhere ----

    data class HrPoint(val tsSec: Long, val bpm: Int)
    data class Window(val startSec: Long, val endSec: Long)
    data class HrChunk(
        val clientId: String,
        val startSec: Long,
        val endSec: Long,
        val points: List<HrPoint>,
    )
    data class HrPlan(val chunks: List<HrChunk>, val newFrontierSec: Long)

    /**
     * Build chunked HR series from samples newer than [frontierSec]. Inside any [windows] interval
     * every sample is kept; outside, at most one sample per [decimateSec]. Each chunk spans at most
     * [chunkSec] seconds and holds at most [maxSamplesPerChunk] points. [HrPlan.newFrontierSec]
     * advances past every fresh sample seen (so decimated-away tail samples are not revisited).
     */
    fun heartRate(
        samples: List<HrPoint>,
        windows: List<Window>,
        frontierSec: Long,
        decimateSec: Long = 30,
        chunkSec: Long = 3600,
        maxSamplesPerChunk: Int = 1000,
    ): HrPlan {
        val fresh = samples.filter { it.tsSec > frontierSec }.sortedBy { it.tsSec }
        if (fresh.isEmpty()) return HrPlan(emptyList(), frontierSec)

        fun inWindow(ts: Long) = windows.any { ts >= it.startSec && ts <= it.endSec }

        val kept = ArrayList<HrPoint>()
        var lastOut = Long.MIN_VALUE
        for (p in fresh) {
            if (inWindow(p.tsSec)) {
                kept.add(p); lastOut = p.tsSec
            } else if (p.tsSec - lastOut >= decimateSec) {
                kept.add(p); lastOut = p.tsSec
            }
        }

        val chunks = ArrayList<HrChunk>()
        var cur = ArrayList<HrPoint>()
        fun flush() {
            if (cur.isEmpty()) return
            chunks.add(HrChunk("noop-hr-${cur.first().tsSec}", cur.first().tsSec, cur.last().tsSec, ArrayList(cur)))
            cur = ArrayList()
        }
        for (p in kept) {
            if (cur.isNotEmpty() &&
                (p.tsSec - cur.first().tsSec >= chunkSec || cur.size >= maxSamplesPerChunk)) {
                flush()
            }
            cur.add(p)
        }
        flush()

        return HrPlan(chunks, fresh.last().tsSec)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testFullDebugUnitTest --tests "com.noop.ingest.HealthExportPlanTest"`
Expected: PASS (11 tests total).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noop/ingest/HealthExportPlan.kt \
        app/src/test/java/com/noop/ingest/HealthExportPlanTest.kt
git commit -m "feat(android): pure heart-rate series planner (hybrid res + chunk + frontier)"
```

---

## Task 3: Pure planner — sleep sessions (asleep/awake only, finalized only)

**Files:**
- Modify: `app/src/main/java/com/noop/ingest/HealthExportPlan.kt`
- Test: `app/src/test/java/com/noop/ingest/HealthExportPlanTest.kt`

- [ ] **Step 1: Write the failing test** (append inside `HealthExportPlanTest`)

```kotlin
    @Test fun sleep_excludesUnfinalizedSessions() {
        val sessions = listOf(HealthExportPlan.SleepInput(startTs = 100, endTs = 900, stagesJSON = null))
        val out = HealthExportPlan.sleepSessions(sessions, nowSec = 500L) // ends in the future
        assertTrue(out.isEmpty())
    }

    @Test fun sleep_emitsFinalizedSessionWithClientId() {
        val sessions = listOf(HealthExportPlan.SleepInput(100, 900, null))
        val out = HealthExportPlan.sleepSessions(sessions, nowSec = 1000L)
        assertEquals(1, out.size)
        assertEquals("noop-sleep-100", out[0].clientId)
        assertEquals(100L, out[0].startSec)
        assertEquals(900L, out[0].endSec)
        assertTrue(out[0].stages.isEmpty()) // null stagesJSON -> session bounds only
    }

    @Test fun sleep_mapsWakeVsAsleepAndCoalesces() {
        val json = """
            [{"start":100,"end":200,"stage":"light"},
             {"start":200,"end":300,"stage":"deep"},
             {"start":300,"end":400,"stage":"wake"},
             {"start":400,"end":500,"stage":"awake"},
             {"start":500,"end":600,"stage":"rem"}]
        """.trimIndent()
        val out = HealthExportPlan.sleepSessions(
            listOf(HealthExportPlan.SleepInput(100, 600, json)), nowSec = 1000L)
        val stages = out[0].stages
        // light+deep coalesce -> asleep[100,300); wake+awake coalesce -> awake[300,500); rem -> asleep[500,600)
        assertEquals(3, stages.size)
        assertEquals(true, stages[0].asleep); assertEquals(100L, stages[0].startSec); assertEquals(300L, stages[0].endSec)
        assertEquals(false, stages[1].asleep); assertEquals(300L, stages[1].startSec); assertEquals(500L, stages[1].endSec)
        assertEquals(true, stages[2].asleep); assertEquals(500L, stages[2].startSec); assertEquals(600L, stages[2].endSec)
    }

    @Test fun sleep_malformedJsonYieldsNoStagesButKeepsSession() {
        val out = HealthExportPlan.sleepSessions(
            listOf(HealthExportPlan.SleepInput(100, 900, "not json")), nowSec = 1000L)
        assertEquals(1, out.size)
        assertTrue(out[0].stages.isEmpty())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFullDebugUnitTest --tests "com.noop.ingest.HealthExportPlanTest"`
Expected: FAIL — `HealthExportPlan.sleepSessions` / `SleepInput` unresolved.

- [ ] **Step 3: Write minimal implementation** (append inside the `HealthExportPlan` object)

```kotlin
    // ---- Sleep sessions: AWAKE vs SLEEPING only (fine stages deferred until stager validated) ----

    data class SleepInput(val startTs: Long, val endTs: Long, val stagesJSON: String?)
    data class StagePlan(val startSec: Long, val endSec: Long, val asleep: Boolean)
    data class SleepPlan(
        val clientId: String,
        val startSec: Long,
        val endSec: Long,
        val stages: List<StagePlan>,
    )

    /** Finalized sessions (endTs <= [nowSec]) only; never the currently-open night. */
    fun sleepSessions(sessions: List<SleepInput>, nowSec: Long): List<SleepPlan> {
        val out = ArrayList<SleepPlan>()
        for (s in sessions) {
            if (s.endTs <= s.startTs) continue
            if (s.endTs > nowSec) continue
            out.add(SleepPlan("noop-sleep-${s.startTs}", s.startTs, s.endTs, parseStages(s.stagesJSON)))
        }
        return out
    }

    /** Parse the `{start,end,stage}` segment array; classify `wake`/`awake` as awake, else asleep;
     *  coalesce consecutive same-class segments. Returns empty on null/malformed JSON. */
    private fun parseStages(json: String?): List<StagePlan> {
        json ?: return emptyList()
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
        val raw = ArrayList<StagePlan>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val st = o.optLong("start", -1L)
            val en = o.optLong("end", -1L)
            if (st < 0 || en <= st) continue
            val name = o.optString("stage")
            raw.add(StagePlan(st, en, asleep = name != "wake" && name != "awake"))
        }
        val merged = ArrayList<StagePlan>()
        for (seg in raw.sortedBy { it.startSec }) {
            val last = merged.lastOrNull()
            if (last != null && last.asleep == seg.asleep && seg.startSec <= last.endSec) {
                merged[merged.size - 1] = last.copy(endSec = maxOf(last.endSec, seg.endSec))
            } else {
                merged.add(seg)
            }
        }
        return merged
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testFullDebugUnitTest --tests "com.noop.ingest.HealthExportPlanTest"`
Expected: PASS (15 tests total).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noop/ingest/HealthExportPlan.kt \
        app/src/test/java/com/noop/ingest/HealthExportPlanTest.kt
git commit -m "feat(android): pure sleep-session planner (asleep/awake, finalized only)"
```

---

## Task 4: Add the HR write-frontier preference

**Files:**
- Modify: `app/src/main/java/com/noop/ui/MainActivity.kt` (the `NoopPrefs` object, near the other `KEY_HC_*` entries ~line 228)

> No unit test: this is `SharedPreferences` glue with no logic, matching the untested existing `hcLastSync`/`hcWriteback` accessors. Verified by compile + on-device.

- [ ] **Step 1: Add the key and accessors**

In `NoopPrefs`, next to `KEY_HC_WRITEBACK`, add:

```kotlin
    const val KEY_HC_HR_FRONTIER = "noop.hcHrFrontierTs"

    /** Last HR sample epoch-second exported to Health Connect (0 = nothing exported yet). */
    fun hcHrFrontier(context: Context): Long =
        of(context).getLong(KEY_HC_HR_FRONTIER, 0L)

    fun setHcHrFrontier(context: Context, tsSec: Long) =
        of(context).edit().putLong(KEY_HC_HR_FRONTIER, tsSec).apply()
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :app:compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noop/ui/MainActivity.kt
git commit -m "feat(android): add HC heart-rate export frontier pref"
```

---

## Task 5: Declare the four new write permissions

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (in the Health Connect permission block, after the `READ_*` / existing `WRITE_*` entries)

- [ ] **Step 1: Add the permissions**

```xml
    <uses-permission android:name="android.permission.health.WRITE_ACTIVE_CALORIES_BURNED" />
    <uses-permission android:name="android.permission.health.WRITE_STEPS" />
    <uses-permission android:name="android.permission.health.WRITE_HEART_RATE" />
    <uses-permission android:name="android.permission.health.WRITE_SLEEP" />
```

- [ ] **Step 2: Verify the assumption about computed-day fields** (see header note)

Run: `grep -rnE 'activeKcalEst|\.steps *=' app/src/main/java/com/noop/analytics/AnalyticsEngine.kt | head`
Expected: lines that upsert a `DailyMetric` with `steps` / `activeKcalEst`. Confirm the `deviceId` used is `computedDeviceId(...)` (i.e. ends with `-noop`). If it is the raw device id, note that Task 6 must read aggregates from `repo.days(deviceId)` instead.

- [ ] **Step 3: Compile to verify the manifest still merges**

Run: `./gradlew :app:processFullDebugManifest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(android): declare HC write perms for energy/steps/HR/sleep"
```

---

## Task 6: Writer — export daily aggregates (Active Energy + Steps)

**Files:**
- Modify: `app/src/main/java/com/noop/ingest/HealthConnectWriter.kt`

> SDK glue (constructs HC records); not unit-tested, like `buildExerciseRecords`. The logic it relies on is already tested in Task 1.

- [ ] **Step 1: Add imports** (top of the file, with the other record imports)

```kotlin
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.units.Energy
```

- [ ] **Step 2: Add the two record classes to `WRITE_RECORDS`**

Change:

```kotlin
    private val WRITE_RECORDS: List<KClass<out Record>> = listOf(
        RestingHeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        OxygenSaturationRecord::class,
        RespiratoryRateRecord::class,
    )
```

to:

```kotlin
    private val WRITE_RECORDS: List<KClass<out Record>> = listOf(
        RestingHeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        OxygenSaturationRecord::class,
        RespiratoryRateRecord::class,
        ActiveCaloriesBurnedRecord::class,
        StepsRecord::class,
    )
```

(`PERMISSIONS` is derived from `WRITE_RECORDS`, so the two new write perms are now requested automatically.)

- [ ] **Step 3: Append daily-aggregate records inside `write()`**

Inside `write()`, immediately before `if (records.isEmpty()) return 0`, add:

```kotlin
        // Active Energy + Steps: one interval record per day, riding the same 60-day window.
        val zone2 = ZoneId.systemDefault()
        val aggs = HealthExportPlan.dailyAggregates(
            days.map { HealthExportPlan.DayInput(it.day, it.steps, it.activeKcalEst) },
        ) { day ->
            val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@dailyAggregates null
            val s = date.atStartOfDay(zone2).toEpochSecond()
            val e = date.plusDays(1).atStartOfDay(zone2).toEpochSecond()
            s to e
        }
        for (a in aggs) {
            val start = Instant.ofEpochSecond(a.startEpochSec)
            val end = Instant.ofEpochSecond(a.endEpochSec)
            val off = zone2.rules.getOffset(start)
            a.activeKcal?.let {
                records.add(ActiveCaloriesBurnedRecord(
                    startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
                    energy = Energy.kilocalories(it),
                    metadata = meta("energy", a.day, version),
                ))
            }
            a.steps?.let {
                records.add(StepsRecord(
                    startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
                    count = it,
                    metadata = meta("steps", a.day, version),
                ))
            }
        }
```

> Note: `records` is currently built with `buildList<Record> { … }`, which yields an immutable list. Change its declaration from `val records = buildList<Record> { … }` to a mutable accumulator so the aggregate loop can `records.add(...)`. Concretely: replace `val records = buildList<Record> {` with `val records = ArrayList<Record>()` and convert the `add(...)` calls inside the day loop to `records.add(...)` (or keep `buildList` and move the aggregate loop *inside* the `buildList` block — either is fine; pick the smaller diff).

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL. (Confirm the `ActiveCaloriesBurnedRecord` / `StepsRecord` constructor parameter names against `connect-client:1.1.0-alpha07` if the compile complains — `count` is `Long`, `energy` is `Energy`.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noop/ingest/HealthConnectWriter.kt
git commit -m "feat(android): export Active Energy + Steps to Health Connect"
```

---

## Task 7: Writer — export Heart Rate series (frontier-driven)

**Files:**
- Modify: `app/src/main/java/com/noop/ingest/HealthConnectWriter.kt`

- [ ] **Step 1: Add imports**

```kotlin
import androidx.health.connect.client.records.HeartRateRecord
import com.noop.ui.NoopPrefs
```

- [ ] **Step 2: Add a chunked-insert helper** (private, inside the object)

```kotlin
    /** Health Connect caps records per insert call; insert in batches to stay well under it. */
    private suspend fun insertChunked(client: HealthConnectClient, records: List<Record>, batch: Int = 1000): Int {
        var n = 0
        records.chunked(batch).forEach { client.insertRecords(it); n += it.size }
        return n
    }
```

- [ ] **Step 3: Add the HR export function** (private, inside the object)

```kotlin
    /** Export NOOP's heart-rate samples (raw [deviceId], not computed) above the persisted frontier. */
    private suspend fun writeHeartRate(context: Context, repo: WhoopRepository, deviceId: String): Int {
        val client = HealthConnectClient.getOrCreate(context)
        val now = System.currentTimeMillis() / 1000
        val floor = now - WINDOW_DAYS * 86_400
        val frontier = maxOf(NoopPrefs.hcHrFrontier(context), floor)

        val samples = repo.hrSamples(deviceId, from = frontier + 1, to = now, limit = 200_000)
            .map { HealthExportPlan.HrPoint(it.ts, it.bpm) }
        if (samples.isEmpty()) return 0

        val windows = buildList {
            repo.workouts(deviceId, frontier, now).forEach { add(HealthExportPlan.Window(it.startTs, it.endTs)) }
            repo.sleepSessions(deviceId, frontier, now).forEach { add(HealthExportPlan.Window(it.startTs, it.endTs)) }
        }

        val plan = HealthExportPlan.heartRate(samples, windows, frontier)
        if (plan.chunks.isEmpty()) {
            if (plan.newFrontierSec > frontier) NoopPrefs.setHcHrFrontier(context, plan.newFrontierSec)
            return 0
        }

        val zone = ZoneId.systemDefault()
        val records = plan.chunks.map { c ->
            val startTs = c.startSec
            val endTs = if (c.endSec > c.startSec) c.endSec else c.startSec + 1 // HC needs end > start
            val start = Instant.ofEpochSecond(startTs)
            val end = Instant.ofEpochSecond(endTs)
            val off = zone.rules.getOffset(start)
            HeartRateRecord(
                startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
                samples = c.points.map {
                    HeartRateRecord.Sample(time = Instant.ofEpochSecond(it.tsSec), beatsPerMinute = it.bpm.toLong())
                },
                metadata = Metadata(clientRecordId = c.clientId, clientRecordVersion = version()),
            )
        }
        val n = insertChunked(client, records)
        NoopPrefs.setHcHrFrontier(context, plan.newFrontierSec)
        return n
    }

    /** Shared write-version stamp (seconds), so re-runs upsert rather than duplicate. */
    private fun version(): Long = System.currentTimeMillis() / 1000
```

> The existing `write()` computes a local `version` val. Either reuse that by passing it in, or use this `version()` helper here. Keep one source — if you keep the local `version` in `write()`, drop the helper and have `writeHeartRate` take a `version: Long` parameter.

- [ ] **Step 4: Call it from `write()`**

In `write()`, after the existing `client.insertRecords(records)` and before `return records.size`, change the tail to accumulate the HR count:

```kotlin
        if (records.isEmpty()) return 0
        client.insertRecords(records)
        var total = records.size
        total += runCatching { writeHeartRate(context, repo, deviceId) }.getOrDefault(0)
        return total
```

> Note `write()`'s early `if (records.isEmpty()) return 0` would skip HR export on a day with no daily metrics. To avoid that, move the HR call above the `isEmpty()` guard, or change the guard to only skip the daily-insert (not the function). Simplest: replace the guard block with:
> ```kotlin
>         var total = 0
>         if (records.isNotEmpty()) { client.insertRecords(records); total += records.size }
>         total += runCatching { writeHeartRate(context, repo, deviceId) }.getOrDefault(0)
>         return total
> ```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL. (Confirm `HeartRateRecord.Sample(time, beatsPerMinute: Long)` against alpha07.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/noop/ingest/HealthConnectWriter.kt
git commit -m "feat(android): export heart-rate series to Health Connect (frontier-driven)"
```

---

## Task 8: Writer — export Sleep sessions (asleep/awake)

**Files:**
- Modify: `app/src/main/java/com/noop/ingest/HealthConnectWriter.kt`

- [ ] **Step 1: Add imports**

```kotlin
import androidx.health.connect.client.records.SleepSessionRecord
```

- [ ] **Step 2: Add the sleep export function** (private, inside the object)

```kotlin
    /** Export finalized sleep sessions as session + AWAKE/SLEEPING stages (no deep/REM/light yet). */
    private suspend fun writeSleep(context: Context, repo: WhoopRepository, deviceId: String): Int {
        val client = HealthConnectClient.getOrCreate(context)
        val now = System.currentTimeMillis() / 1000
        val floor = now - WINDOW_DAYS * 86_400
        val sessions = repo.sleepSessions(deviceId, from = floor, to = now)
            .map { HealthExportPlan.SleepInput(it.startTs, it.endTs, it.stagesJSON) }
        val plans = HealthExportPlan.sleepSessions(sessions, now)
        if (plans.isEmpty()) return 0

        val zone = ZoneId.systemDefault()
        val records = plans.map { p ->
            val start = Instant.ofEpochSecond(p.startSec)
            val end = Instant.ofEpochSecond(p.endSec)
            val off = zone.rules.getOffset(start)
            SleepSessionRecord(
                startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
                stages = p.stages.map { s ->
                    SleepSessionRecord.Stage(
                        startTime = Instant.ofEpochSecond(s.startSec),
                        endTime = Instant.ofEpochSecond(s.endSec),
                        stage = if (s.asleep) SleepSessionRecord.STAGE_TYPE_SLEEPING
                                else SleepSessionRecord.STAGE_TYPE_AWAKE,
                    )
                },
                metadata = Metadata(clientRecordId = p.clientId, clientRecordVersion = p.endSec),
            )
        }
        return insertChunked(client, records)
    }
```

- [ ] **Step 3: Call it from `write()`** (after the HR call)

```kotlin
        total += runCatching { writeSleep(context, repo, deviceId) }.getOrDefault(0)
        return total
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL. (Confirm `SleepSessionRecord.Stage(startTime, endTime, stage)` and the `STAGE_TYPE_*` constant names against alpha07.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noop/ingest/HealthConnectWriter.kt
git commit -m "feat(android): export sleep sessions to Health Connect (asleep/awake)"
```

---

## Task 9: Update the Data Sources "Share back" copy

**Files:**
- Modify: `app/src/main/java/com/noop/ui/DataSourcesScreen.kt`

- [ ] **Step 1: Find the writeback description string**

Run: `grep -nE 'Share back|writeback|hcWriteback|resting HR|RHR' app/src/main/java/com/noop/ui/DataSourcesScreen.kt`
Expected: a description string listing the metrics currently shared back (resting HR, HRV, SpO₂, respiratory).

- [ ] **Step 2: Extend the metric list in that string**

Edit the description to also mention the new metrics, e.g. append `", active energy, heart rate, steps and sleep"` to the existing sentence. (Keep wording consistent with the file's existing tone; do not invent a new UI control — the existing toggle already governs all writeback.)

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/noop/ui/DataSourcesScreen.kt
git commit -m "docs(android): mention new export metrics in Data Sources copy"
```

---

## Task 10: Full verification (unit tests + build) and on-device check

**Files:** none (verification only)

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew :app:testFullDebugUnitTest`
Expected: BUILD SUCCESSFUL — all existing tests plus the 15 new `HealthExportPlanTest` cases pass.

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleFullDebug`
Expected: BUILD SUCCESSFUL; APK under `app/build/outputs/apk/full/debug/`.

- [ ] **Step 3: On-device verification (rooted Pixel — the locally-testable path)**

**Primary: demo-seed + round-trip (no hardware, proves the concept end-to-end).**
1. Install the **demo** flavor APK (`assembleDemoDebug`) so `DemoSeeder` populates Room with HR/sleep/steps/calories; ensure the Health Connect app is present; launch NOOP.
2. Data Sources → grant the new write permissions (active energy, heart rate, steps, sleep) and enable "Share back".
3. Trigger a writeback (15-min `runAnalyzePass()` loop or toggle Share-back off/on).
4. **Round-trip:** run the existing Health Connect *import* (Data Sources → "Import from Health Connect") and confirm the metrics NOOP just wrote read back consistently (HR, steps, active calories, sleep) — i.e. write → HC stores → re-import matches. This validates the concept inside NOOP alone, no second app required.
5. In the Health Connect app, confirm under NOOP's contributions: **Active Energy** and **Steps** (daily), **Heart Rate** (dense inside workout/sleep windows, sparse elsewhere), and a **Sleep session** with only asleep/awake segments — no deep/REM/light.
6. Trigger a second writeback and confirm **no duplicate** records appear (idempotent `clientRecordId` + advanced frontier).

**Optional follow-up: real BLE data.** Repeat 2–6 on the `full` flavor after a real WHOOP BLE sync to confirm the same behaviour on live strap data.

- [ ] **Step 4: Final no-op commit / branch summary** (optional)

```bash
git log --oneline origin/main..HEAD
```
Expected: the Task 1–9 commits, authored `sunny_noop`.

---

## Self-review notes (author)

- **Spec coverage:** Active Energy (Task 6), Heart Rate hybrid (Tasks 2+7), Steps (Task 6), Sleep asleep/awake (Tasks 3+8), frontier cursor (Tasks 4+7), permissions (Task 5), idempotency via `clientRecordId` (Tasks 6–8), offline tests + on-device check (Tasks 1–3, 10). Workout broadening and import-side body composition are explicit non-goals — no tasks, as intended.
- **Type consistency:** planner names used identically across tasks — `HealthExportPlan.{DayInput, DailyAgg, dailyAggregates, HrPoint, Window, HrChunk, HrPlan, heartRate, SleepInput, StagePlan, SleepPlan, sleepSessions}`; prefs `hcHrFrontier`/`setHcHrFrontier`; clientRecordIds `noop-energy-<day>`, `noop-steps-<day>`, `noop-hr-<startSec>`, `noop-sleep-<startTs>`.
- **Open confirmations during impl (not blockers):** alpha07 constructor parameter names for the four records; whether `steps`/`activeKcalEst` sit on the computed-day rows (Task 5 Step 2); single `version` source between `write()` and `writeHeartRate` (Task 7 Step 3 note).
