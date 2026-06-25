package com.noop.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeEwmaTest {
    private fun mins(vararg minuteIndex: Long) = LongArray(minuteIndex.size) { minuteIndex[it] * 60 }

    @Test fun firstValueSeedsState() {
        val out = Calibration.timeEwma(mins(0, 1, 2), listOf(10.0, 10.0, 10.0), 20.0)
        assertEquals(10.0, out[0], 1e-9)
        assertEquals(10.0, out[2], 1e-9)
    }

    @Test fun nanUntilFirstRealValue() {
        val out = Calibration.timeEwma(mins(0, 1, 2), listOf(null, null, 5.0), 20.0)
        assertTrue(out[0].isNaN())
        assertTrue(out[1].isNaN())
        assertEquals(5.0, out[2], 1e-9)
    }

    @Test fun missingValueCarriesState() {
        val out = Calibration.timeEwma(mins(0, 1, 2), listOf(10.0, null, 20.0), 20.0)
        assertEquals(10.0, out[1], 1e-9)
        val a = 1.0 - Math.pow(2.0, -2.0 / 20.0)
        assertEquals((1 - a) * 10.0 + a * 20.0, out[2], 1e-9)
    }

    @Test fun largeGapResetsTowardNewValue() {
        val out = Calibration.timeEwma(mins(0, 1000), listOf(10.0, 50.0), 20.0)
        assertEquals(50.0, out[1], 1e-3)
    }
}
