package com.noop.wearlink

/**
 * The minimal, stable phone->watch payload. A strict subset of the phone's LiveState,
 * limited to the glanceable fields the watch renders. Pure data — no Android types.
 */
data class WatchSnapshot(
    val hr: Int? = null,
    val connected: Boolean = false,
    val bonded: Boolean = false,
    val batteryPct: Double? = null,
    val worn: Boolean = true,
    val backfilling: Boolean = false,
    val lastSyncAt: Long? = null,
    /** Publisher wall-clock (unix ms) when this snapshot was emitted. */
    val emittedAt: Long = 0L,
)
