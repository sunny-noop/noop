package com.noop.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Shared transport constants + framing live in :wearlink so the phone and watch can't drift. */
class SharedWireTest {

    @Test fun rfcommServiceIdsAreValidAndStable() {
        // Both the phone RFCOMM server and the watch RFCOMM client derive from these — one source.
        assertEquals("7f3e2d1c-8b4a-4c9e-a1d6-0f5b2e9c3a47", UUID.fromString(RfcommService.UUID).toString())
        assertEquals("noop-wear", RfcommService.SDP_NAME)
    }

    @Test fun encodeLineIsNewlineTerminatedAndRoundTrips() {
        val s = WatchSnapshot(hr = 72, batteryPct = 87.5)
        val line = WatchSnapshotCodec.encodeLine(s)
        assertTrue("must be newline-terminated", line.endsWith("\n"))
        assertEquals("exactly one line", 1, line.count { it == '\n' })
        assertEquals("body matches encode()", WatchSnapshotCodec.encode(s) + "\n", line)
        assertEquals("round-trips", s, WatchSnapshotCodec.decodeOrNull(line.trim()))
    }
}
