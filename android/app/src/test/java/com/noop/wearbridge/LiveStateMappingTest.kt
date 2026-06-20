package com.noop.wearbridge

import com.noop.ble.LiveState
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStateMappingTest {
    @Test fun mapsGlanceableFields() {
        val live = LiveState(
            connected = true, bonded = true, heartRate = 71,
            batteryPct = 90.0, worn = true, backfilling = true, lastSyncAt = 1_700_000_000L,
        )
        val snap = live.toWatchSnapshot(nowMs = 123L)
        assertEquals(71, snap.hr)
        assertEquals(true, snap.connected)
        assertEquals(true, snap.bonded)
        assertEquals(90.0, snap.batteryPct!!, 0.0)
        assertEquals(true, snap.worn)
        assertEquals(true, snap.backfilling)
        assertEquals(1_700_000_000L, snap.lastSyncAt)
        assertEquals(123L, snap.emittedAt)
    }

    @Test fun nullHrAndBatteryPassThrough() {
        val snap = LiveState(connected = false, heartRate = null, batteryPct = null).toWatchSnapshot(nowMs = 5L)
        assertEquals(null, snap.hr)
        assertEquals(null, snap.batteryPct)
        assertEquals(false, snap.connected)
        assertEquals(5L, snap.emittedAt)
    }
}
