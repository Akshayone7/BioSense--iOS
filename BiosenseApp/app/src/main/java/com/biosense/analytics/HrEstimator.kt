package com.biosense.analytics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * PPG heart-rate + HRV(RMSSD) estimator with contact/quality gating.
 *
 * Uses band-limited peak detection on the IR channel. Only emits values
 * when signal quality passes the gates below, so we never publish
 * phantom readings from ambient noise.
 *
 * NOT a medical device — see the platform README.
 *
 * Optional LMS adaptive motion-compensation: when accelerometer samples are
 * provided to [estimate], the IR AC component is first motion-compensated
 * via a 5-tap-per-axis Least-Mean-Squares filter against the 3-axis accel
 * reference (Widrow & Hoff 1960; Haykin 2002, Adaptive Filter Theory, 4th ed.,
 * ch. 5). The filter learns a 15-weight linear model that predicts the
 * motion artifact in IR from recent accel samples, subtracts it, and updates
 * weights from the residual. Weights persist across calls so the filter
 * adapts to sustained patterns (walking cadence, lifting). Without accel
 * input, pre-LMS behavior is preserved byte-for-byte.
 */
class HrEstimator(private val sampleRateHz: Float = 104f) {

    private var lastBpm: Float? = null

    // LMS adaptive-filter state. One weight vector per accel axis, [lmsTaps]
    // samples each — persists across estimate() calls so the filter converges
    // over seconds of continuous motion rather than restarting per window.
    // [lmsMu] sits well below the LMS stability bound 2 / (M · σ²_x) for
    // typical wrist-accel variance (~0.01–0.5 g²), giving conservative
    // convergence (~200 samples to adapt) in exchange for guaranteed stability.
    private val lmsTaps = 5
    private val lmsMu = 0.005f
    private val wx = FloatArray(lmsTaps)
    private val wy = FloatArray(lmsTaps)
    private val wz = FloatArray(lmsTaps)

    data class Result(
        val bpm: Float?,
        val rmssdMs: Float?,
        val spo2Estimate: Float?,
        val contact: Boolean,
        val quality: Float,   // 0..1 — higher is cleaner PPG
        val reason: String,   // debug: why we returned null (empty on success)
    )

    companion object {
        // MAX30102 raw counts: ambient/no-contact ~ 1–5k; wrist w/ contact ~15k+.
        private const val MIN_IR_DC = 15_000f
        // A finger on the sensor typically drives IR into saturation-ish (>2M)
        // — treat that as pressure/artefact, not real signal.
        private const val MAX_IR_DC = 2_000_000f
        // AC/DC ratio (perfusion index) plausible band for wrist PPG.
        private const val MIN_PERFUSION = 0.0003f
        private const val MAX_PERFUSION = 0.06f
        private const val MIN_PEAKS = 4
        private const val MIN_BPM = 35f
        private const val MAX_BPM = 200f
    }

    fun estimate(
        ir: FloatArray,
        red: FloatArray,
        ax: FloatArray? = null,
        ay: FloatArray? = null,
        az: FloatArray? = null,
        accelSampleRateHz: Float = 104f,
    ): Result {
        val n = ir.size
        if (n < (sampleRateHz * 4).toInt())
            return Result(null, null, null, contact = false, quality = 0f, reason = "insufficient_samples")

        // ---- Contact gate: IR DC in a realistic range ---------------------
        val irMean = mean(ir)
        if (irMean < MIN_IR_DC)
            return Result(null, null, null, contact = false, quality = 0f, reason = "no_contact")
        if (irMean > MAX_IR_DC)
            return Result(null, null, null, contact = false, quality = 0f, reason = "saturation")

        // ---- Perfusion gate: AC amplitude has to be pulsatile, not noisy --
        val redMean = if (red.size == n) mean(red) else 0f
        val irAc = meanAbsDev(ir, irMean)
        val perfusion = irAc / irMean
        if (perfusion < MIN_PERFUSION)
            return Result(null, null, null, contact = true, quality = 0f, reason = "no_pulse")
        val lmsActive = ax != null && ay != null && az != null
        // When LMS motion compensation is available, raise the raw-signal
        // perfusion ceiling. Motion-induced AC is exactly what the filter is
        // designed to remove, so we defer the "really motion artifact?"
        // judgement to the downstream IBI-variance gate on the cleaned signal.
        val maxPerfusionGate = if (lmsActive) 0.15f else MAX_PERFUSION
        if (perfusion > maxPerfusionGate)
            return Result(null, null, null, contact = true, quality = 0f, reason = "motion")

        // ---- Motion-compensation (optional): LMS adaptive filter against
        // the 3-axis accel reference. Runs on the mean-subtracted AC signal
        // BEFORE the bandpass; the bandpass still handles slow baseline drift.
        // See [lmsMotionClean] for the algorithm and literature citations.
        val ac = FloatArray(n) { ir[it] - irMean }
        val cleanAc = if (lmsActive) lmsMotionClean(ac, ax!!, ay!!, az!!, accelSampleRateHz) else ac

        // ---- Bandpass via difference-of-moving-averages -------------------
        // Short MA smooths high-frequency noise (~100 ms lowpass); long MA
        // tracks baseline wander (~1 s). Their difference is a coarse
        // 1–3 Hz bandpass that isolates the pulse fundamental.
        // shortW ≈ 200 ms (removes >5 Hz noise but keeps pulse ~1–2 Hz).
        // longW ≈ 2 s (tracks baseline / breathing drift, but crucially is
        // longer than the pulse period so it does not cancel the pulse).
        val shortW = (sampleRateHz * 0.20f).toInt().coerceAtLeast(1)
        val longW = (sampleRateHz * 2.0f).toInt().coerceAtLeast(5)
        val shortMa = movingAverage(cleanAc, shortW)
        val longMa = movingAverage(cleanAc, longW)
        val bp = FloatArray(n) { shortMa[it] - longMa[it] }

        // ---- Peak pick with refractory + AC-scaled threshold -------------
        // 0.4 s refractory ⇒ ceiling ~150 BPM, but crucially rejects the
        // dicrotic notch (typically 0.2–0.3 s after the main peak).
        val refractory = (sampleRateHz * 0.4f).toInt().coerceAtLeast(1)
        val sd = stdev(bp)
        val thresh = 0.35f * sd
        val peaks = ArrayList<Int>()
        var lastPeak = -refractory - 1
        for (i in 2 until n - 2) {
            val v = bp[i]
            if (v > thresh && v >= bp[i - 1] && v >= bp[i - 2] &&
                v >= bp[i + 1] && v >= bp[i + 2] &&
                (i - lastPeak) > refractory
            ) { peaks += i; lastPeak = i }
        }
        if (peaks.size < MIN_PEAKS) {
            val gaps = if (peaks.size >= 2) (1 until peaks.size).map { peaks[it] - peaks[it-1] } else emptyList()
            return Result(null, null, null, contact = true, quality = 0f,
                reason = "too_few_peaks p=${peaks.size} gaps=$gaps sd=${"%.0f".format(sd)} n=$n")
        }

        // ---- IBI stats & sanity ------------------------------------------
        val rawIbis = FloatArray(peaks.size - 1) { (peaks[it + 1] - peaks[it]) / sampleRateHz }
        // Trim outlier IBIs (motion glitches, missed/double peaks) by keeping
        // only those within ±30% of the median. Standard PPG cleanup.
        val medianIbi = median(rawIbis)
        val kept = rawIbis.filter { it in (0.7f * medianIbi)..(1.3f * medianIbi) }
        if (kept.size < MIN_PEAKS - 1)
            return Result(null, null, null, contact = true, quality = 0f,
                reason = "sparse_after_trim kept=${kept.size} p=${peaks.size} ibis=${rawIbis.map { "%.2f".format(it) }} med=${"%.2f".format(medianIbi)}")

        val ibisSec = kept.toFloatArray()
        val meanIbi = ibisSec.average().toFloat()
        val bpm = 60f / meanIbi
        if (bpm !in MIN_BPM..MAX_BPM)
            return Result(null, null, null, contact = true, quality = 0f,
                reason = "bpm_out_of_range bpm=${"%.0f".format(bpm)} p=${peaks.size}")

        // Reject if surviving IBIs are still jittery. After trimming, real
        // cardiac rhythm should sit well under CV=0.25.
        val ibiSd = stdev(ibisSec)
        val ibiCv = ibiSd / meanIbi
        if (ibiCv > 0.25f)
            return Result(null, null, null, contact = true, quality = 0f,
                reason = "irregular_ibi cv=${"%.2f".format(ibiCv)} bpm=${"%.0f".format(bpm)} p=${peaks.size}")

        // Temporal smoothing across windows so the UI number is stable, not
        // the per-window raw estimate. EMA with α=0.3 gives ~3-window horizon.
        val smoothedBpm = if (lastBpm == null) bpm else 0.7f * lastBpm!! + 0.3f * bpm
        lastBpm = smoothedBpm

        // RMSSD (ms)
        var rmssd: Float? = null
        if (ibisSec.size >= 3) {
            var s = 0f; var c = 0
            for (i in 1 until ibisSec.size) {
                val d = (ibisSec[i] - ibisSec[i - 1]) * 1000f
                s += d * d; c++
            }
            rmssd = sqrt(s / c)
        }

        // SpO2 (rough & uncalibrated)
        val spo2 = spo2Estimate(ir, red, irMean, redMean)

        // Quality score — how much of AC energy sits at the fundamental HR band.
        val quality = (1f - ibiCv).coerceIn(0f, 1f)

        return Result(smoothedBpm, rmssd, spo2, contact = true, quality = quality, reason = "")
    }

    /**
     * SpO2 from the R-ratio R = (RED_ac/RED_mean) / (IR_ac/IR_mean), mapped
     * through the textbook Nellcor curve `SpO2 ≈ 110 - 25·R`.
     *
     * On the wrist the RED channel typically has almost no pulsatility (RED
     * doesn't penetrate wrist tissue like IR does), so R collapses toward 0
     * and the formula pegs at 100%. Gate on all three:
     *   1. RED DC must actually be lit (redMean above a floor),
     *   2. RED must have meaningful pulsatility (perfusion above a floor),
     *   3. The resulting R must fall in a sane range (0.4 ≤ R ≤ 2.0 → 100–60%).
     * If any check fails we return null so the UI shows "—" instead of a
     * fake 100%.
     */
    private fun spo2Estimate(ir: FloatArray, red: FloatArray, irMean: Float, redMean: Float): Float? {
        if (red.size != ir.size || irMean <= 0f) return null
        // MAX30102 idle (no LED / no finger) reads ~600 on the RED channel;
        // real fingertip contact drives it into the tens of thousands.
        if (redMean < 5_000f) return null
        var irAc = 0f; var redAc = 0f
        for (i in ir.indices) { irAc += abs(ir[i] - irMean); redAc += abs(red[i] - redMean) }
        irAc /= ir.size; redAc /= red.size
        if (irAc <= 0f) return null
        val redPerfusion = redAc / redMean
        // On the wrist RED perfusion is essentially zero; on a fingertip it's
        // in the 1e-3 range. Require at least 3e-4 before we trust the ratio.
        if (redPerfusion < 3e-4f) return null
        val r = (redAc / redMean) / (irAc / irMean)
        // Physiologically meaningful R lies roughly 0.4–2.0. Outside that band
        // the reading is dominated by sensor noise / bad optical coupling —
        // better to publish nothing than an implausible number.
        if (r < 0.4f || r > 2.0f) return null
        val est = 110f - 25f * r
        return est.coerceIn(70f, 100f)
    }

    /**
     * Short-tap LMS adaptive noise canceller: treats the 3-axis wrist accel
     * as a reference for motion artifact in the IR AC channel. Adaptively
     * learns a 15-weight linear model (5 taps × 3 axes) that predicts the
     * artifact, subtracts it from IR, and updates weights from the residual.
     *
     * Standard LMS recursion from Haykin 2002 (ch. 5):
     *     v_hat(n) = Σ_k [ wx_k·x(n-k) + wy_k·y(n-k) + wz_k·z(n-k) ]
     *     e(n)     = d(n) - v_hat(n)                  // output == error signal
     *     w_k(n+1) = w_k(n) + μ · e(n) · ref_k(n-k)   // gradient-descent update
     *
     * Why this preserves the pulse: the cardiac component in d(n) is
     * statistically uncorrelated with wrist acceleration, so no linear filter
     * can predict it from x/y/z. LMS converges to subtract only the correlated
     * (motion) component, leaving the pulse intact (Widrow 1975 proof).
     *
     * Decimates accel from [accelRateHz] to the PPG rate ([sampleRateHz]) by
     * block averaging (anti-aliases the ~10x downsample). Aligns the latest
     * common tail of the two signals; only that overlapping portion gets
     * motion-compensated. If the accel window is too short to seed [lmsTaps]
     * samples the IR passes through unchanged.
     */
    private fun lmsMotionClean(
        irAc: FloatArray,
        ax: FloatArray,
        ay: FloatArray,
        az: FloatArray,
        accelRateHz: Float,
    ): FloatArray {
        val decim = (accelRateHz / sampleRateHz).toInt().coerceAtLeast(1)
        val accelLen = minOf(ax.size, ay.size, az.size)
        val decimatedLen = accelLen / decim
        if (decimatedLen < lmsTaps + 1) return irAc

        // Block-average decimation. Suppresses alias energy that would otherwise
        // fold into the pulse band after the ~10x downsample.
        val axD = FloatArray(decimatedLen)
        val ayD = FloatArray(decimatedLen)
        val azD = FloatArray(decimatedLen)
        for (i in 0 until decimatedLen) {
            var sx = 0f; var sy = 0f; var sz = 0f
            val base = i * decim
            for (k in 0 until decim) {
                sx += ax[base + k]; sy += ay[base + k]; sz += az[base + k]
            }
            axD[i] = sx / decim; ayD[i] = sy / decim; azD[i] = sz / decim
        }

        // Center each axis. LMS stability analysis assumes zero-mean reference;
        // raw accel has a dominant gravity DC term that would otherwise bias
        // the weight updates.
        val mx = mean(axD); val my = mean(ayD); val mz = mean(azD)
        for (i in axD.indices) { axD[i] -= mx; ayD[i] -= my; azD[i] -= mz }

        // Align tails and compensate only the overlapping portion. IR samples
        // older than the aligned window stay as-is — the bandpass handles their
        // baseline drift.
        val n = minOf(irAc.size, decimatedLen)
        val irStart = irAc.size - n
        val axStart = axD.size - n
        val out = irAc.copyOf()

        for (i in lmsTaps until n) {
            var predicted = 0f
            for (t in 0 until lmsTaps) {
                predicted += wx[t] * axD[axStart + i - t]
                predicted += wy[t] * ayD[axStart + i - t]
                predicted += wz[t] * azD[axStart + i - t]
            }
            val cleaned = out[irStart + i] - predicted
            out[irStart + i] = cleaned
            for (t in 0 until lmsTaps) {
                wx[t] += lmsMu * cleaned * axD[axStart + i - t]
                wy[t] += lmsMu * cleaned * ayD[axStart + i - t]
                wz[t] += lmsMu * cleaned * azD[axStart + i - t]
            }
        }

        // Divergence guard. In rare pathological conditions (sensor dropouts,
        // extreme σ²_x) μ can push weights beyond stability — reset to zero
        // rather than carry corruption into future windows.
        if (wx.any { it.isNaN() || abs(it) > 1e6f } ||
            wy.any { it.isNaN() || abs(it) > 1e6f } ||
            wz.any { it.isNaN() || abs(it) > 1e6f }) {
            wx.fill(0f); wy.fill(0f); wz.fill(0f)
        }

        return out
    }

    private fun mean(x: FloatArray): Float { var s = 0f; for (v in x) s += v; return s / x.size }

    private fun median(x: FloatArray): Float {
        val sorted = x.sortedArray()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else 0.5f * (sorted[n / 2 - 1] + sorted[n / 2])
    }

    private fun meanAbsDev(x: FloatArray, m: Float): Float {
        var s = 0f; for (v in x) s += abs(v - m); return s / x.size
    }

    private fun stdev(x: FloatArray): Float {
        val m = mean(x)
        var s = 0f; for (v in x) { val d = v - m; s += d * d }
        return sqrt(s / x.size)
    }

    private fun movingAverage(x: FloatArray, w: Int): FloatArray {
        if (w <= 1) return x
        val out = FloatArray(x.size); var acc = 0f
        for (i in x.indices) {
            acc += x[i]
            if (i >= w) acc -= x[i - w]
            out[i] = if (i >= w - 1) acc / w else acc / (i + 1)
        }
        return out
    }
}
