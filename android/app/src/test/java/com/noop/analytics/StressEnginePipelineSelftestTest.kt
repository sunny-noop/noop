package com.noop.analytics

import com.noop.calibration.Baseline
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.sqrt

class StressEnginePipelineSelftestTest {
    private fun pearson(a: List<Double>, b: List<Double>): Double {
        val ma = a.average(); val mb = b.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; num += x * y; da += x * x; db += y * y }
        return num / sqrt(da * db)
    }

    @Test fun tracksSlowAutonomicDrift() {
        val n = 480
        val minutes = LongArray(n) { it * 60L }
        val hr = Array<Double?>(n) { 70.0 + 8.0 * sin(it / 400.0) }   // stays in [62,78]; very slow drift
        val series = StressEngine.scoreSeries(
            StressEngine.Features(minutes, hr),
            hrBase = Baseline(60.0, 14.0),
        )
        val truth = (0 until n).map { 70.0 + 8.0 * sin(it / 400.0) }.drop(n - series.size)
        assertTrue("corr too low", pearson(series.map { it.level }, truth) > 0.95)
    }
}
