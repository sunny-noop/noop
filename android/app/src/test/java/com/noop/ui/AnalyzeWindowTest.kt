package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The window (in days back from today) that an import-triggered analyze pass must cover so a freshly
 * imported capture is scored — even an OLD historical one, past the default recent window.
 */
class AnalyzeWindowTest {

    private val today = LocalDate.parse("2026-06-18")

    @Test
    fun nullSinceDayFallsBackToTheMinimumRecentWindow() {
        assertEquals(21, analyzeWindowDays(null, today, minDays = 21, maxDays = 730))
    }

    @Test
    fun malformedSinceDayFallsBackToTheMinimum() {
        assertEquals(21, analyzeWindowDays("not-a-date", today, minDays = 21, maxDays = 730))
    }

    @Test
    fun recentImportStaysAtTheMinimumFloor() {
        // 10 days of span (06-08 → 06-18) is inside the default window, so the floor wins.
        assertEquals(21, analyzeWindowDays("2026-06-08", today, minDays = 21, maxDays = 730))
    }

    @Test
    fun oldImportWidensTheWindowToCoverItsFirstDay() {
        // 100 days back must score back that far (+2 buffer), not just the default 21.
        assertEquals(102, analyzeWindowDays("2026-03-10", today, minDays = 21, maxDays = 730))
    }

    @Test
    fun ancientImportIsClampedToTheMaximum() {
        assertEquals(730, analyzeWindowDays("2010-01-01", today, minDays = 21, maxDays = 730))
    }

    @Test
    fun futureSinceDayFallsBackToTheMinimum() {
        // Clock skew / a bad row dated in the future must never yield a negative or zero window.
        assertEquals(21, analyzeWindowDays("2026-07-01", today, minDays = 21, maxDays = 730))
    }
}
