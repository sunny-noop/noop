package com.noop.analytics

import com.noop.calibration.Baseline
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StressEngineGoldenTest {
    @Test fun reproducesReferenceRecipeWithinRounding() {
        val text = javaClass.classLoader!!.getResourceAsStream("stress_golden.json")!!
            .bufferedReader().readText()
        val js = JSONObject(text)
        val base = js.getJSONObject("baseline")
        val hrBase = Baseline(base.getDouble("hrCentre"), base.getDouble("hrScale"))

        val mj = js.getJSONArray("minutes")
        val minutes = LongArray(mj.length())
        val hr = arrayOfNulls<Double>(mj.length())
        for (i in 0 until mj.length()) {
            val o = mj.getJSONObject(i)
            minutes[i] = o.getLong("tMin") * 60
            hr[i] = o.getDouble("hr")
        }
        val feats = StressEngine.Features(minutes, hr)
        val series = StressEngine.scoreSeries(feats, hrBase)

        val expected = js.getJSONArray("expected")
        assertEquals(expected.length(), series.size)
        for (i in 0 until expected.length()) {
            assertEquals("minute $i", expected.getDouble(i), series[i].level, 1e-3)
        }
    }
}
