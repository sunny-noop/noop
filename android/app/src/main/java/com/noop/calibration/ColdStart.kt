package com.noop.calibration

/**
 * ColdStart — per-hardware population baselines for users without enough history yet, plus the resolution
 * rule: use the user's own trained baseline once it has enough nights, otherwise fall back to the
 * population default for their hardware family. Values are shipped constants.
 *
 * CONVENTION (critical): these defaults are on the SAME ruler [CalBaseline] uses for a trained baseline —
 * the BETWEEN-NIGHT spread of per-night summaries (centre = typical nightly mean, scale = night-to-night
 * spread of those means, single-digit bpm for HR), NOT the within-day spread of raw samples. The recipe
 * coefficients are locked to this convention, so a population-typical wearer sees no score jump when they
 * graduate from cold-start to their own baseline. (See StressEngineContinuityTest.) Population estimates
 * population estimates; replaced per-feature once the wearer reaches the seed-night gate.
 */
object ColdStart {

    /** Population defaults: model family -> feature -> Baseline(centre, scale). Between-night convention.
     *  HR only — the stress engine is HR-only (HRV dropped as sparse/unstable). */
    val DEFAULTS: Map<String, Map<String, Baseline>> = mapOf(
        "whoop5" to mapOf("hr" to Baseline(78.0, 5.0)),
        "whoop4" to mapOf("hr" to Baseline(76.0, 8.0)),
    )

    /** Map a device registry model string to a cold-start family key. */
    fun deviceModelKey(model: String): String = if (model.trim().startsWith("4")) "whoop4" else "whoop5"

    fun defaultFor(modelKey: String, feature: String): Baseline =
        DEFAULTS.getValue(modelKey).getValue(feature)

    /**
     * Resolve a feature baseline: the user's [trained] baseline if it exists and has >= [seed] nights,
     * else — before maturity — a per-account override prior [userSeed] when supplied, otherwise the
     * per-hardware population default.
     *
     * [userSeed] is the opt-in calibration seam (mirrors `detectSleep(userFloor:)` in the sleep stager):
     * pass the wearer's own learned/stored prior so a brand-new or short-history account starts from its
     * own signal instead of the population mean. `null` keeps current behaviour exactly — nothing in the
     * shipped path supplies it yet; it's the seam for an unsupervised, per-account calibration later.
     */
    fun resolve(
        trained: Baseline?,
        nNights: Int,
        seed: Int,
        model: String,
        feature: String,
        userSeed: Baseline? = null,
    ): Calibration.Resolved =
        if (trained != null && nNights >= seed) {
            Calibration.Resolved(trained, Calibration.BaselineSource.TRAINED, nNights)
        } else if (userSeed != null) {
            Calibration.Resolved(userSeed, Calibration.BaselineSource.USER_SEED, nNights)
        } else {
            Calibration.Resolved(defaultFor(model, feature), Calibration.BaselineSource.COLD_START, nNights)
        }

    /**
     * Build a per-account override prior from whatever per-night history the wearer has — even below the
     * TRAINED seed gate — for use as [resolve]'s `userSeed`. This is the public seam (analogous to the
     * sleep stager's `sessionJerkFloor(...)`): a thin pass-through to [CalBaseline.build] that names the
     * intent — "use the wearer's own signal as the cold-start prior." Returns null when there is no
     * history (caller then falls back to the population default). Off by default; nothing calls it yet.
     */
    fun userSeedFrom(samples: List<Pair<Long, Double>>, window: Int = 28): Baseline? =
        CalBaseline.build(samples, window)
}
