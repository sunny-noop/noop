# Android Health Connect — export-gap closure (design)

**Date:** 2026-06-18
**Status:** Approved design, pre-implementation
**Scope:** Android app (`android/`, package `com.noop`). Related issue: NoopApp/noop#20
("Expand Apple Health Two-Way Synchronization") — this spec covers the **Android Health
Connect** analogue, not the iOS HealthKit side.

## Background

Android already ships **two-way** Health Connect sync:

- `app/.../ingest/HealthConnectImporter.kt` — reads steps, total/active calories, HR, RHR,
  HRV, sleep, SpO₂, respiratory, VO₂max, weight, exercise, distance.
- `app/.../ingest/HealthConnectWriter.kt` — writes `RestingHeartRateRecord`,
  `HeartRateVariabilityRmssdRecord`, `OxygenSaturationRecord`, `RespiratoryRateRecord`
  (daily metrics, 60-day re-write window) and `ExerciseSessionRecord` + `DistanceRecord`
  (live-GPS workouts only).
- `app/.../ui/DataSourcesScreen.kt` — the "Health Connect" card: import controls, auto-sync
  interval, and a "Share back" (writeback) opt-in.
- `app/.../ui/AppViewModel.kt` — `syncHealthConnectIfStale()` (app-open import) and
  `writebackHealthConnectNow()` (called on the 15-min `runAnalyzePass()` recompute and on the
  Share-back toggle).
- Permissions declared in `app/src/main/AndroidManifest.xml`; runtime grants via
  `androidx.health.connect.client.permission` + the `DataSourcesScreen` `PermissionController`
  launcher. Dependency: `androidx.health.connect:connect-client:1.1.0-alpha07`.

This feature is therefore **gap closure, not greenfield**.

### Gap analysis vs issue #20 export list

Issue #20 asks NOOP to *write* Active Energy, Heart Rate, Resting HR, Respiratory Rate, Blood
Oxygen, Steps, Sleep, Workouts. Current writer coverage:

| Metric | HC record | Status |
|---|---|---|
| Resting HR | `RestingHeartRateRecord` | ✅ done |
| Respiratory Rate | `RespiratoryRateRecord` | ✅ done |
| Blood Oxygen | `OxygenSaturationRecord` | ✅ done |
| **Active Energy** | `ActiveCaloriesBurnedRecord` | ❌ this spec |
| **Heart Rate** | `HeartRateRecord` (series) | ❌ this spec |
| **Steps** | `StepsRecord` | ❌ this spec |
| **Sleep** | `SleepSessionRecord` | ❌ this spec |
| Workouts | `ExerciseSessionRecord` | ⚠️ live-GPS only — **out of scope here** (tracked separately) |

The import-side gaps (Body Fat, Lean Body Mass, BMI via Health Connect) are **out of scope** for
this spec; this delivery is export-only.

## Goal

Add four export record types to `HealthConnectWriter` so a WHOOP synced on Linux/Android surfaces
its NOOP-computed Active Energy, Heart Rate, Steps, and Sleep into Health Connect, reusing the
existing opt-in, idempotency, UI, and foreground-writeback machinery.

### Non-goals

- iOS / HealthKit (separate work).
- Import-side body-composition (Body Fat / Lean Mass / BMI).
- Broadening workout export beyond the existing live-GPS path.
- Exporting fine sleep stages (deep/REM/light) — deliberately deferred (see Decisions).
- Any background `WorkManager` export job — export stays foreground-driven, matching the
  project's existing deliberate "no background-health worker" stance.

## Decisions (settled during brainstorming)

1. **Heart Rate resolution — hybrid.** Full 1 Hz inside detected workout and sleep windows;
   decimate to ~1 sample / 30 s elsewhere. Rationale: fidelity where it matters, ~30× lighter
   for the long continuous stretches that dominate the day.
2. **Sleep — session + asleep/awake only.** Write the sleep window and AWAKE-vs-ASLEEP segments;
   **omit** DEEP/REM/LIGHT until `SleepStager` is label-validated. Rationale: deep-stage detection
   is currently broken (`deep=0%`); exporting it would publish a known-wrong value into the user's
   health record.
3. **Daily aggregates ride the existing loop.** Active Energy and Steps are one record per local
   calendar day and slot into the writer's current 60-day re-write window (60 cheap records).
4. **Heart Rate is incremental.** HR is the only high-volume series; it must NOT re-walk history
   each 15-min cycle. A persisted write-frontier cursor bounds each run to new samples.

## Architecture

All new logic lives inside `HealthConnectWriter` plus small **pure builder functions** (no
Android/HC runtime types in their signatures where avoidable) so they unit-test offline. No
changes to the data flow, the UI wiring, or the analytics loop beyond what is listed.

```
AppViewModel.runAnalyzePass()  (every 15 min, + Share-back toggle)
        │
        └─► writebackHealthConnectNow()
                  │
                  └─► HealthConnectWriter.write(context, repo, deviceId)
                        ├─ daily aggregates (60-day window)   ← Active Energy, Steps  [NEW]
                        ├─ daily metrics (existing)            ← RHR, HRV, SpO₂, Resp
                        ├─ heart-rate series (frontier cursor) ← Heart Rate           [NEW]
                        └─ finalized sleep sessions (14-day)   ← Sleep                 [NEW]
```

### Record-by-record design

| Metric | HC record | Room source | Cadence / window | `clientRecordId` |
|---|---|---|---|---|
| Active Energy | `ActiveCaloriesBurnedRecord` (interval = local day) | `dailyMetric.activeKcalEst` | existing 60-day re-write | `noop-energy-<day>` |
| Steps | `StepsRecord` (interval = local day) | `dailyMetric.steps` | existing 60-day re-write | `noop-steps-<day>` |
| Heart Rate | `HeartRateRecord` (sample series, chunked) | `hrSample`, classified against `workout` + `sleepSession` windows | **incremental** from `hcHrFrontierTs`; chunked into ≤1 h blocks | `noop-hr-<chunkStartTs>` |
| Sleep | `SleepSessionRecord` (asleep/awake stages) | `sleepSession` (finalized only) | trailing ~14 days, per session | `noop-sleep-<startTs>` |

`clientRecordVersion` follows the existing convention (a monotonic timestamp — `endTs` for
session-shaped records, the recompute time for daily aggregates) so re-writes upsert rather than
duplicate.

### Components

1. **`DailyAggregateBuilder` (pure).** `dailyMetric` rows → list of
   `(day, startInstant, endInstant, activeKcal, steps)` aggregate descriptors. Skips days with
   no data. Tested offline.
2. **`HeartRateSeriesBuilder` (pure).** Inputs: `hrSample` list (ts seconds, bpm) above the
   frontier, plus workout/sleep window intervals. Output: list of chunk descriptors, each a list
   of `(instant, bpm)` samples — full-res inside any window, decimated to 1/30 s outside, split
   into ≤1 h chunks (and within the HC per-record sample cap). Returns the new frontier
   (max exported ts). Tested offline: decimation rate, window inclusion, chunk boundaries,
   frontier advance, empty input.
3. **`SleepSessionBuilder` (pure).** `sleepSession` (with `stagesJSON`) → `SleepSessionRecord`
   stage list mapping wake epochs → `STAGE_TYPE_AWAKE` / `STAGE_TYPE_AWAKE_IN_BED`, all other
   epochs → `STAGE_TYPE_SLEEPING`. Excludes the currently-open session (only `endTs` in the
   past). Tested offline: mapping, open-session exclusion, contiguous-segment coalescing.
4. **`HealthConnectWriter.write()` extension.** Calls the builders, constructs HC records,
   `insertRecords` in batches, advances + persists `hcHrFrontierTs`. Honours per-type
   permission grants (write only granted types — existing `WRITE_RECORDS`/`getWritePermission`
   pattern extends automatically).

### New state

- `NoopPrefs.hcHrFrontierTs: Long` — last exported HR sample ts (unix seconds). Default 0 →
  first export starts from the writer's existing window floor (bounded, not all-history). Reset
  to 0 if the user revokes/re-grants HR write, so a re-grant re-seeds cleanly. Idempotent
  `clientRecordId` is the dedupe backstop behind the cursor.

### Permissions

Add to the manifest and to `WRITE_RECORDS`:

- `android.permission.health.WRITE_ACTIVE_CALORIES_BURNED` → `ActiveCaloriesBurnedRecord`
- `android.permission.health.WRITE_STEPS` → `StepsRecord`
- `android.permission.health.WRITE_HEART_RATE` → `HeartRateRecord`
- `android.permission.health.WRITE_SLEEP` → `SleepSessionRecord`

The `DataSourcesScreen` permission launcher and the `getWritePermission(it)` mapping over
`WRITE_RECORDS` then cover the new types with no further UI change. The "Share back" copy on the
card should be updated to mention the added metrics.

## Error handling & edge cases

- **Open sleep session:** never exported — only sessions whose `endTs` is in the past.
- **HC per-record sample cap:** HR series chunked (≤1 h blocks) to stay under the limit.
- **Off-wrist gaps:** simply absent samples in the series — no synthetic fill.
- **Estimated energy:** `activeKcalEst` is an HR-only estimate; it is NOOP-attributed via
  `Metadata`, so consumers can distinguish it. Acceptable to export as our estimate.
- **Partial permission grant:** write only the granted record types (existing behaviour).
- **Duplicate prevention:** persisted frontier + idempotent `clientRecordId` together prevent
  duplicates across re-runs and across the 15-min loop.
- **Re-write cost:** only HR uses the frontier; daily aggregates (60) and finalized sleep
  sessions (14 days) are cheap to re-emit each cycle.

## Testing

**Offline JUnit (no device, matches existing test setup):**
- `DailyAggregateBuilder` — day bucketing, empty-day skip, kcal/steps mapping.
- `HeartRateSeriesBuilder` — decimation to 1/30 s outside windows, full-res inside
  workout/sleep windows, ≤1 h chunking, frontier advance, empty/duplicate input.
- `SleepSessionBuilder` — wake→AWAKE / sleep→SLEEPING mapping, open-session exclusion,
  segment coalescing.

**On-device (rooted Pixel — the locally-testable path):**
1. Install Health Connect; launch NOOP; grant the new write permissions via the
   DataSources screen.
2. Sync the WHOOP (existing Linux/BLE path), enable "Share back".
3. Trigger a writeback (15-min loop or the toggle), then inspect in the Health Connect app
   (or a read-back) that Active Energy, HR (hybrid resolution), Steps, and a sleep session
   (asleep/awake only) appear, attributed to NOOP, with no duplicates after a second cycle.

## Open questions / follow-ups

- HR chunk granularity (≤1 h assumed) — confirm against the HC `1.1.0-alpha07` per-record
  sample limit during implementation.
- Sleep trailing window (14 days assumed) vs the daily-metric 60-day window — align if desired.
- Whether to also emit `TotalCaloriesBurnedRecord` (BMR + active) alongside Active Energy —
  deferred; not in issue #20's explicit list.
- Re-enabling fine sleep stages once `SleepStager` deep detection is label-validated (separate
  workstream; the `SleepSessionBuilder` mapping is the single place to flip).
