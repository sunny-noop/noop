package com.noop.calibration

import org.junit.Assert.assertEquals
import org.junit.Test

class MetricRecipeTest {
    @Test fun stressRecipeConstants() {
        val r = MetricRecipe.STRESS
        assertEquals("stress", r.name)
        assertEquals(listOf("hr"), r.needs)
        assertEquals(1.11554, r.params.getValue("b0"), 1e-9)
        assertEquals(0.10643, r.params.getValue("b_hr"), 1e-9)
        assertEquals(null, r.params["b_hrv"])      // HR-only: no HRV coefficient
        assertEquals(20.0, r.params.getValue("halflife"), 1e-9)
        assertEquals(listOf(1.0, 2.0), r.bands)
        assertEquals(listOf("LOW", "MEDIUM", "HIGH"), r.bandLabels)
    }
}
