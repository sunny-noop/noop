package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Test

class StressEngineFeaturesTest {
    @Test fun bucketsHrByMinuteMean() {
        val d = "dev"
        val hr = listOf(HrSample(d, ts = 0, bpm = 60), HrSample(d, ts = 30, bpm = 80),  // minute 0 -> mean 70
                        HrSample(d, ts = 60, bpm = 100))                                 // minute 1 -> 100
        val f = StressEngine.minuteFeatures(hr)
        assertEquals(listOf(0L, 60L), f.minutes.toList())
        assertEquals(70.0, f.hr[0]!!.toDouble(), 1e-9)
        assertEquals(100.0, f.hr[1]!!.toDouble(), 1e-9)
    }
}
