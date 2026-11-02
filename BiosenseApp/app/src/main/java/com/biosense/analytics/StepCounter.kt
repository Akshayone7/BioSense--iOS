package com.biosense.analytics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Streaming step detector on |a| (g). Standard peak-count algorithm:
 *   1) High-pass by subtracting a slow EMA of the raw magnitude (removes gravity + drift)
 *   2) Threshold-cross rising edges, with a refractory window to reject double-triggers
 *
 * Tuned for wrist-mounted accel @ 104 Hz. Returns cumulative step count since
 * construction; the caller diffs against last-seen for per-tick increments.
 */
class StepCounter(private val sampleRateHz: Float = 104f) {
    private val emaAlphaSlow = 0.01f      // ~1 s time constant for gravity removal
    private val threshold = 0.10f          // g above baseline required to count as a step
    private val refractoryMs = 250L        // fastest cadence we accept (~4 Hz cap = ~240 spm)

    private var slowEma: Float = 1.0f
    private var lastStepTimeMs: Long = 0
    private var wasAbove = false
    private var _total: Long = 0
    val total: Long get() = _total

    fun ingest(ax: Float, ay: Float, az: Float, tMs: Long) {
        val mag = sqrt(ax * ax + ay * ay + az * az)
        slowEma = slowEma + emaAlphaSlow * (mag - slowEma)
        val hp = mag - slowEma
        val isAbove = hp > threshold
        if (isAbove && !wasAbove) {
            if (tMs - lastStepTimeMs >= refractoryMs) {
                _total++
                lastStepTimeMs = tMs
            }
        }
        wasAbove = isAbove
    }

    fun ingestBatch(ax: FloatArray, ay: FloatArray, az: FloatArray, baseTMs: Long, dtMs: Float) {
        val n = minOf(ax.size, ay.size, az.size)
        for (i in 0 until n) {
            ingest(ax[i], ay[i], az[i], baseTMs + (i * dtMs).toLong())
        }
    }

    fun reset() {
        _total = 0
        lastStepTimeMs = 0
        wasAbove = false
        slowEma = 1.0f
    }
}
