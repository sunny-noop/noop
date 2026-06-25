package com.noop.calibration

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationColdStartTest {
    @Test fun deviceModelKeyMapping() {
        assertEquals("whoop4", ColdStart.deviceModelKey("4.0"))
        assertEquals("whoop5", ColdStart.deviceModelKey("5"))
        assertEquals("whoop5", ColdStart.deviceModelKey("5.2"))
        assertEquals("whoop5", ColdStart.deviceModelKey("MG"))
        assertEquals("whoop5", ColdStart.deviceModelKey("unknown"))
    }

    @Test fun coldStartDefaultsMatchProfile() {
        // between-night convention, HR-only (see ColdStart doc + StressEngineContinuityTest)
        assertEquals(Baseline(78.0, 5.0), ColdStart.defaultFor("whoop5", "hr"))
        assertEquals(Baseline(76.0, 8.0), ColdStart.defaultFor("whoop4", "hr"))
    }

    @Test fun resolveUsesTrainedWhenMature() {
        val trained = Baseline(60.0, 9.0)
        val r = ColdStart.resolve(trained, nNights = 6, seed = 4, model = "whoop5", feature = "hr")
        assertEquals(Calibration.BaselineSource.TRAINED, r.source)
        assertEquals(trained, r.baseline)
        assertEquals(6, r.nNights)
    }

    @Test fun resolveFallsBackToColdStartWhenImmature() {
        val r = ColdStart.resolve(Baseline(60.0, 9.0), nNights = 2, seed = 4, model = "whoop5", feature = "hr")
        assertEquals(Calibration.BaselineSource.COLD_START, r.source)
        assertEquals(Baseline(78.0, 5.0), r.baseline)
    }

    @Test fun resolveFallsBackWhenTrainedNull() {
        val r = ColdStart.resolve(null, nNights = 10, seed = 4, model = "whoop4", feature = "hr")
        assertEquals(Calibration.BaselineSource.COLD_START, r.source)
        assertEquals(Baseline(76.0, 8.0), r.baseline)
    }

    // ── opt-in per-account override seam (userSeed); null keeps current behaviour ──

    @Test fun userSeedReplacesColdStartBeforeMaturity() {
        val seed = Baseline(62.0, 8.0)
        val r = ColdStart.resolve(null, nNights = 1, seed = 4, model = "whoop5", feature = "hr", userSeed = seed)
        assertEquals(Calibration.BaselineSource.USER_SEED, r.source)
        assertEquals(seed, r.baseline)
    }

    @Test fun userSeedIgnoredOnceTrainedIsMature() {
        val trained = Baseline(60.0, 9.0)
        val r = ColdStart.resolve(trained, nNights = 6, seed = 4, model = "whoop5", feature = "hr",
            userSeed = Baseline(62.0, 8.0))
        assertEquals(Calibration.BaselineSource.TRAINED, r.source)
        assertEquals(trained, r.baseline)
    }

    @Test fun nullUserSeedKeepsPopulationDefault() {
        // identical to resolveFallsBackToColdStartWhenImmature — proves the new param is opt-in.
        val r = ColdStart.resolve(Baseline(60.0, 9.0), nNights = 2, seed = 4, model = "whoop5", feature = "hr",
            userSeed = null)
        assertEquals(Calibration.BaselineSource.COLD_START, r.source)
        assertEquals(Baseline(78.0, 5.0), r.baseline)
    }

    @Test fun userSeedFromBuildsFromOwnHistoryBelowSeed() {
        // three nights (below a seed of 4) still yield a usable per-account prior.
        val seed = ColdStart.userSeedFrom(listOf(1L to 58.0, 2L to 60.0, 3L to 62.0))
        assertEquals(Baseline(60.0, CalBaseline.pstdev(listOf(58.0, 60.0, 62.0))), seed)
        assertEquals(null, ColdStart.userSeedFrom(emptyList()))
    }
}
