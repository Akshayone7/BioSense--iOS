package com.biosense.analytics

/**
 * Daily readiness score (0..100, higher = better) composed from three
 * autonomic/recovery signals: HRV balance, resting-HR balance, and sleep.
 *
 * Weights (50/25/25 by default) are a *design judgment* within the
 * literature-supported ordering (HRV dominant; RHR + sleep secondary and
 * roughly comparable). HRV gets the largest weight but is capped at half,
 * because wrist-PPG HRV is the input most sensitive to measurement noise.
 * The ordering is backed by:
 *   - Shaffer & Ginsberg 2017, Front Public Health 5:258 (HRV as primary ANS marker)
 *   - Buchheit 2014, Front Physiol 5:73 (HRV + RHR as complementary autonomic tools)
 *   - Bellenger et al. 2016, Sports Med 46(10):1461-86 (systematic review of HRV)
 *   - Fullagar et al. 2015, Sports Med 45(2):161-86 (sleep in recovery)
 *   - Watson et al. 2015, J Clin Sleep Med 11(6):591-92 (sleep duration consensus)
 *
 * The *specific percentages* are not derived from a published formula. Later
 * upgrade path: replace [DEFAULT_WEIGHTS] at runtime with user-fit weights from
 * a morning self-report + non-negative least squares.
 *
 * v1 scope note: this module intentionally does NOT compute per-user baselines,
 * z-score attribution, or acute-vs-baseline flags. Per-user personalized
 * baseline modelling (Mahalanobis / multivariate Gaussian) is reserved as v2
 * novelty and must not be anticipated here. The score published is a
 * literature-weighted composite with transparent inputs — nothing more.
 */
object WellnessEstimator {

    /** Each component is in [0, 1] or null when the underlying signal is missing. */
    data class Components(val hrv: Float?, val rhr: Float?, val sleep: Float?)

    data class Weights(
        val hrv: Float = 0.50f,
        val rhr: Float = 0.25f,
        val sleep: Float = 0.25f,
    )

    data class Score(
        val value: Int,
        val label: String,
        val components: Components,
        val weightsUsed: Weights,
    )

    val DEFAULT_WEIGHTS = Weights()

    /**
     * Pure composition. Returns null when fewer than 2 components survive —
     * a 1-of-3 reading is not meaningful enough to publish.
     */
    fun compute(
        components: Components,
        weights: Weights = DEFAULT_WEIGHTS,
    ): Score? {
        val value = weighted(components, weights) ?: return null
        return Score(
            value = value,
            label = label(value),
            components = components,
            weightsUsed = renormalize(components, weights),
        )
    }

    fun label(score: Int): String = when {
        score >= 85 -> "Primed"
        score >= 70 -> "Ready"
        score >= 50 -> "Steady"
        score >= 30 -> "Taxed"
        else -> "Depleted"
    }

    // ---- Internals ----------------------------------------------------------

    /** Weighted sum with null-aware renormalization. Returns null if < 2 non-null components. */
    private fun weighted(c: Components, w: Weights): Int? {
        val parts = buildList {
            c.hrv?.let { add(it to w.hrv) }
            c.rhr?.let { add(it to w.rhr) }
            c.sleep?.let { add(it to w.sleep) }
        }
        if (parts.size < 2) return null
        val wSum = parts.sumOf { it.second.toDouble() }
        val vSum = parts.sumOf { it.first.toDouble() * it.second.toDouble() }
        return ((vSum / wSum) * 100.0).toInt().coerceIn(0, 100)
    }

    /** Renormalized weights after dropping null components. */
    private fun renormalize(c: Components, w: Weights): Weights {
        val hrvW = if (c.hrv != null) w.hrv else 0f
        val rhrW = if (c.rhr != null) w.rhr else 0f
        val sleepW = if (c.sleep != null) w.sleep else 0f
        val total = hrvW + rhrW + sleepW
        if (total <= 0f) return w
        return Weights(hrvW / total, rhrW / total, sleepW / total)
    }
}
