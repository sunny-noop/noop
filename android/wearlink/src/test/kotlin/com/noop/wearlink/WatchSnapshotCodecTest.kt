package com.noop.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WatchSnapshotCodecTest {
    @Test fun roundTrip_fullSnapshot() {
        val s = WatchSnapshot(
            hr = 72, connected = true, bonded = true, batteryPct = 87.5,
            worn = true, backfilling = false, lastSyncAt = 1_700_000_000L, emittedAt = 1_700_000_123L,
        )
        assertEquals(s, WatchSnapshotCodec.decodeOrNull(WatchSnapshotCodec.encode(s)))
    }

    @Test fun roundTrip_nullsAndDefaults() {
        val s = WatchSnapshot()
        assertEquals(s, WatchSnapshotCodec.decodeOrNull(WatchSnapshotCodec.encode(s)))
    }

    @Test fun decode_malformed_returnsNull() {
        assertNull(WatchSnapshotCodec.decodeOrNull("not json"))
        assertNull(WatchSnapshotCodec.decodeOrNull(""))
    }

    @Test fun encode_isSingleLine() {
        assertFalse(WatchSnapshotCodec.encode(WatchSnapshot(hr = 60)).contains("\n"))
    }

    @Test fun roundTrip_batteryPctScientificNotation() {
        // A small Double whose toString() uses exponent form must still round-trip.
        val s = WatchSnapshot(batteryPct = 1.0E-4)
        val decoded = WatchSnapshotCodec.decodeOrNull(WatchSnapshotCodec.encode(s))
        assertEquals(s, decoded)
    }

    @Test fun decode_sparseObject_keepsDataClassDefaults() {
        // A structurally valid object missing most fields decodes to the data-class defaults,
        // including worn = true (NOT false).
        val decoded = WatchSnapshotCodec.decodeOrNull("{\"hr\":72}")
        assertEquals(WatchSnapshot(hr = 72), decoded)
    }
}
