package com.biosense.analytics

import kotlin.math.max
import kotlin.math.min

/**
 * Personalized stress score (0 = calm, 100 = highly stressed) derived from
 * HRV (RMSSD) and HR elevation over the user's own baseline. Two-signal blend:
 *
 *   score = 100 * (0.7 * (1 - rmssdPct) + 0.3 * hrElev)
 *
 * where:
 *   rmssdPct = current RMSSD's percentile against the user's 7-day RMSSD
 *              distribution (higher RMSSD → higher percentile → calmer);
 *   hrElev   = how far current HR sits above resting, clamped so 30 bpm
 *              above rest counts as fully elevated.
 *
 * We use short-term smoothing so the reading doesn't strobe with every
 * analysis tick, and cold-start defaults keep it usable before there's a
 * personal baseline.
 */
class StressEstimator {

    private var rmssdSorted: FloatArray = FloatArray(0)
    private var restingHrBpm: Float? = null
    private var emaScore: Float = Float.NaN
    private val emaAlpha = 0.2f

    /** Rebuild the RMSSD baseline from the last N days of persisted HRV samples. */
    fun setRmssdBaseline(samples: FloatArray) {
        rmssdSorted = if (samples.isEmpty()) FloatArray(0)
        else samples.copyOf().also { it.sort() }
    }

    fun setRestingHr(bpm: Float?) { restingHrBpm = bpm }

    /**
     * Estimate stress from the current HR + RMSSD. Returns null when inputs
     * are missing — caller should hold the last reading or blank the UI.
     */
    fun estimate(hrBpm: Float?, rmssdMs: Float?): Int? {
        if (hrBpm == null || rmssdMs == null) return null

        // Lower RMSSD → higher stress. Percentile is in [0, 1]; invert.
        val pct = percentile(rmssdSorted, rmssdMs) ?: fallbackRmssdPct(rmssdMs)
        val rmssdComponent = 1f - pct

        val hrElev = restingHrBpm?.let {
            ((hrBpm - it) / 30f).coerceIn(0f, 1f)
        } ?: fallbackHrElev(hrBpm)

        val raw = (0.7f * rmssdComponent + 0.3f * hrElev).coerceIn(0f, 1f) * 100f
        emaScore = if (emaScore.isNaN()) raw else emaAlpha * raw + (1f - emaAlpha) * emaScore
        return emaScore.toInt().coerceIn(0, 100)
    }

    /** Cold-start scaling when there's no personal RMSSD history yet. */
    private fun fallbackRmssdPct(rmssdMs: Float): Float {
        // Population-ish anchors: RMSSD ~15 ms is low, ~60 ms is high.
        return ((rmssdMs - 15f) / 45f).coerceIn(0f, 1f)
    }

    private fun fallbackHrElev(hrBpm: Float): Float {
        // If we don't know rest, assume 65 bpm as a rough default.
        return ((hrBpm - 65f) / 30f).coerceIn(0f, 1f)
    }

    private fun percentile(sorted: FloatArray, v: Float): Float? {
        if (sorted.size < 20) return null // not enough personal history yet
        var lo = 0; var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo.toFloat() / sorted.size.toFloat()
    }

    fun reset() { emaScore = Float.NaN }

    companion object {
        /** Should we suppress stress reading right now? True when moving. */
        fun shouldSuppress(gyroRmsDps: Float, ppgContact: Boolean): Boolean {
            return gyroRmsDps > 30f || !ppgContact
        }

        fun label(score: Int): String = when {
            score < 25 -> "calm"
            score < 50 -> "relaxed"
            score < 70 -> "elevated"
            else -> "high"
        }
    }
}
