package com.noop.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class CalBaselineTest {
    @Test fun emptyReturnsNull() {
        assertNull(CalBaseline.build(emptyList()))
    }

    @Test fun medianCentreAndPopulationStdevScale() {
        val s = listOf(1L to 10.0, 2L to 20.0, 3L to 30.0)
        val b = CalBaseline.build(s)!!
        assertEquals(20.0, b.centre, 1e-9)
        assertEquals(sqrt(((10.0 - 20).let { it * it } + 0.0 + (30.0 - 20).let { it * it }) / 3.0), b.scale, 1e-9)
    }

    @Test fun evenCountMedianAverages() {
        val b = CalBaseline.build(listOf(1L to 10.0, 2L to 20.0, 3L to 30.0, 4L to 40.0))!!
        assertEquals(25.0, b.centre, 1e-9)
    }

    @Test fun dedupsByDayKeepFirst() {
        val b = CalBaseline.build(listOf(1L to 10.0, 1L to 999.0, 2L to 30.0))!!
        assertEquals(20.0, b.centre, 1e-9)
    }

    @Test fun windowKeepsLastNNights() {
        val s = (1L..40L).map { it to it.toDouble() }
        val b = CalBaseline.build(s, window = 28)!!
        assertEquals(26.5, b.centre, 1e-9)
    }

    @Test fun singleNightScaleIsOne() {
        val b = CalBaseline.build(listOf(5L to 42.0))!!
        assertEquals(42.0, b.centre, 1e-9)
        assertEquals(1.0, b.scale, 1e-9)
    }

    @Test fun quantileCentreWhenRequested() {
        val s = (1L..10L).map { it to it.toDouble() }
        val b = CalBaseline.build(s, q = 0.9)!!
        assertEquals(9.1, b.centre, 1e-9)
    }
}
