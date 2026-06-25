package com.noop.calibration

/**
 * MetricRecipe — a metric's universal (shipped, per-user-invariant) formula constants. Adding a new
 * calibrated metric is "add a recipe + an engine"; nothing structural changes. The per-user part comes from
 * CalBaseline + ColdStart at apply time, and each consumer engine owns its own combiner form.
 */
data class MetricRecipe(
    val name: String,
    val needs: List<String>,
    val params: Map<String, Double>,   // may be empty (e.g. a 0-param multiplicative combiner)
    val bands: List<Double>,           // cut-points
    val bandLabels: List<String>,      // size == bands.size + 1
) {
    companion object {
        /** Stress 0–3: windowed linear clip(b0 + b_hr·z_HR, 0, 3), EWMA halflife 20 min. HR-only. */
        val STRESS = MetricRecipe(
            name = "stress",
            needs = listOf("hr"),
            // HR-ONLY. The coefficient acts on the per-user z-score standardized by CalBaseline's
            // BETWEEN-NIGHT baseline (one summary per night) — LOCKED to that convention, so
            // ColdStart.DEFAULTS must express the same between-night spread or the score rescales at the
            // cold-start->trained handoff. HRV (RMSSD) was dropped: it adds only ~0.02 r, is sparse
            // (~9% of daytime seconds) + PPG-floored, and does NOT generalize per-night (hurts on some days).
            params = mapOf("b0" to 1.11554, "b_hr" to 0.10643, "halflife" to 20.0),
            bands = listOf(1.0, 2.0),
            bandLabels = listOf("LOW", "MEDIUM", "HIGH"),
        )
    }
}
