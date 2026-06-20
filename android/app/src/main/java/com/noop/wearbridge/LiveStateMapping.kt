package com.noop.wearbridge

import com.noop.ble.LiveState
import com.noop.wearlink.WatchSnapshot

/**
 * Project the phone's rich LiveState down to the watch's glanceable WatchSnapshot.
 * Pure: nowMs is injected so the mapping is unit-testable without a system clock.
 */
fun LiveState.toWatchSnapshot(nowMs: Long): WatchSnapshot = WatchSnapshot(
    hr = heartRate,
    connected = connected,
    bonded = bonded,
    batteryPct = batteryPct,
    worn = worn,
    backfilling = backfilling,
    lastSyncAt = lastSyncAt,
    emittedAt = nowMs,
)
