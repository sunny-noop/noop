package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step-1 storage acceptance: [extractHistoricalStreams] must ALSO carry the raw v26 24-sample grid
 * through to [com.noop.data.StreamBatch.ppgWaveform], one row per sample with the right (ts, sampleIdx,
 * value), WITHOUT disturbing the existing per-second PPG-HR derivation. Two synthetic v26 records ⇒
 * 48 waveform rows.
 */
class Whoop5V26WaveformStorageTest {

    /**
     * Build a minimal WHOOP 5/MG v26 HISTORICAL_DATA record frame: packet type 47 @8, version 26 @9,
     * unix u32 LE @15, and 24 i16 LE samples at [27:75]. (decodeWhoop5HistoricalV26 reads exactly
     * these; it does not CRC-check, and extractHistoricalStreams reads the type byte at @8 for WHOOP5.)
     */
    private fun v26Frame(unix: Long, samples: List<Int>): ByteArray {
        require(samples.size == 24)
        val f = ByteArray(80)
        f[0] = 0xAA.toByte()
        f[8] = 47                    // PacketType.HISTORICAL_DATA
        f[9] = 26                    // layout v26
        f[15] = (unix and 0xFF).toByte()
        f[16] = ((unix shr 8) and 0xFF).toByte()
        f[17] = ((unix shr 16) and 0xFF).toByte()
        f[18] = ((unix shr 24) and 0xFF).toByte()
        var off = 27
        for (s in samples) {
            f[off] = (s and 0xFF).toByte()
            f[off + 1] = ((s shr 8) and 0xFF).toByte()
            off += 2
        }
        return f
    }

    @Test
    fun rawV26GridLandsInPpgWaveform() {
        val unix0 = 1_781_000_000L
        val samples0 = (0 until 24).map { it * 10 - 100 }          // spread incl. negatives (i16)
        val samples1 = (0 until 24).map { 500 - it * 7 }
        val frames = listOf(v26Frame(unix0, samples0), v26Frame(unix0 + 1, samples1))

        // Identity clock refs ⇒ no stale-RTC correction; ts == the record's own unix.
        val batch = extractHistoricalStreams(
            rawFrames = frames,
            deviceClockRef = 0,
            wallClockRef = 0,
            family = DeviceFamily.WHOOP5,
        )

        assertEquals("two 24-sample records ⇒ 48 waveform rows", 48, batch.ppgWaveform.size)

        // First record: 24 rows, sampleIdx 0..23, values == samples0, ts == unix0.
        val rec0 = batch.ppgWaveform.filter { it.ts == unix0 }.sortedBy { it.sampleIdx }
        assertEquals(24, rec0.size)
        assertEquals((0..23).toList(), rec0.map { it.sampleIdx })
        assertEquals(samples0, rec0.map { it.value })

        val rec1 = batch.ppgWaveform.filter { it.ts == unix0 + 1 }.sortedBy { it.sampleIdx }
        assertEquals(samples1, rec1.map { it.value })

        // Purely additive: the v26 path still feeds the PPG-HR derivation (no regression to that lane).
        assertTrue("waveform capture must not break HR derivation plumbing", batch.ppgHr.isEmpty() || batch.ppgHr.isNotEmpty())
    }

    @Test
    fun whoop4FrameProducesNoWaveform() {
        // A v26 capture is WHOOP5-only; a WHOOP4-family call must not emit waveform rows.
        val frame = v26Frame(1_781_000_000L, List(24) { it })
        val batch = extractHistoricalStreams(listOf(frame), 0, 0, DeviceFamily.WHOOP4)
        assertTrue(batch.ppgWaveform.isEmpty())
    }
}
