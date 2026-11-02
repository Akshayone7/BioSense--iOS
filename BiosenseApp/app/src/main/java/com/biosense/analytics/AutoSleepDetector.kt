package com.biosense.analytics

import com.biosense.data.HistoryStore
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Always-on sleep detector. Aggregates wrist-accel samples into 60-s epochs,
 * classifies each epoch as sleep or wake from activity + (optional) HR, and
 * auto-transitions into/out of a "recording" state based on sustained runs.
 *
 * State machine:
 *   WATCHING → RECORDING: 20 consecutive minutes of sleep-classified epochs
 *   RECORDING → WATCHING: 5 consecutive minutes of wake-classified epochs
 *
 * On auto-onset the session's startMs is backdated to when the quiet run
 * actually began (20 min ago), so persisted summaries capture the full sleep
 * period rather than only the time since the trigger fired. Sessions shorter
 * than [minSessionMin] on close are discarded (naps, false positives).
 *
 * Manual Start/Stop is exposed so the user can override the detector.
 */
class AutoSleepDetector(
    private val onSessionStart: (startMs: Long, auto: Boolean) -> Unit = { _, _ -> },
    private val onSessionEnd: (record: HistoryStore.SleepRecord, epochs: List<MinuteEpoch>) -> Unit = { _, _ -> },
) {

    enum class State { WATCHING, RECORDING }

    enum class Stage { WAKE, LIGHT, DEEP, REM }

    data class MinuteEpoch(
        val startMs: Long,
        val activityG: Float,
        val sleep: Boolean,
        val hrBpm: Float?,
        val stage: Stage = Stage.WAKE,
    )

    private val activityThreshold = 0.02f   // g mean-abs-deviation; below → "quiet"
    private val hrRestOffsetBpm = 5f        // allow HR up to rest+5 for "sleep" epoch
    private val onsetEpochs = 20            // 20 min sustained quiet triggers auto-start
    private val offsetEpochs = 5            // 5 min sustained motion triggers auto-stop
    private val minSessionMin = 30          // discard anything shorter than half an hour

    private val emaAlpha = 0.02f
    private var slowEma: Float = 1.0f
    private var sumAbs: Double = 0.0
    private var sampleCount: Int = 0
    private var epochStartMs: Long = 0

    private var restingHrBpm: Float? = null
    private var latestHrBpm: Float? = null
    private var prevHrForStage: Float? = null

    // Stage thresholds tuned for wrist-PPG data (see classifyStage docstring).
    private val deepActivityG = 0.008f
    private val deepHrOffsetBpm = 2f
    private val remHrSpikeBpm = 6f

    private var _state: State = State.WATCHING
    private var _recordingSinceMs: Long = 0
    private var _recordingIsAuto: Boolean = false
    private val recentEpochs = ArrayDeque<MinuteEpoch>()  // just enough for the trigger windows
    private val activeSessionEpochs = ArrayList<MinuteEpoch>()

    val state: State get() = _state
    val recordingSinceMs: Long get() = _recordingSinceMs
    val recordingIsAuto: Boolean get() = _recordingIsAuto
    val activeEpochCount: Int get() = activeSessionEpochs.size
    val activeSleepMin: Int get() = activeSessionEpochs.count { it.sleep }
    val activeWakeMin: Int get() = activeSessionEpochs.size - activeSleepMin
    /** Number of low-activity minutes in a row right now — surfaces "watching for sleep" progress. */
    val quietRunMin: Int
        get() {
            var n = 0
            for (i in recentEpochs.indices.reversed()) {
                if (recentEpochs.elementAt(i).sleep) n++ else break
            }
            return n
        }

    fun setRestingHr(bpm: Float?) { restingHrBpm = bpm }
    fun noteHr(bpm: Float?) { latestHrBpm = bpm }

    fun ingest(ax: Float, ay: Float, az: Float, tMs: Long) {
        val mag = sqrt(ax * ax + ay * ay + az * az)
        slowEma += emaAlpha * (mag - slowEma)
        sumAbs += abs(mag - slowEma).toDouble()
        sampleCount++

        if (epochStartMs == 0L) { epochStartMs = tMs; return }
        if (tMs - epochStartMs < 60_000L) return

        val activity = if (sampleCount > 0) (sumAbs / sampleCount).toFloat() else 0f
        // HR gate: if we have both a resting baseline and a recent HR, require
        // HR to be at rest-band; otherwise fall back to accel-only.
        val hrOk = restingHrBpm == null || latestHrBpm == null ||
            latestHrBpm!! <= (restingHrBpm!! + hrRestOffsetBpm)
        val isSleep = activity < activityThreshold && hrOk

        val stage = classifyStage(isSleep, activity, latestHrBpm)
        val ep = MinuteEpoch(
            startMs = epochStartMs,
            activityG = activity,
            sleep = isSleep,
            hrBpm = latestHrBpm,
            stage = stage,
        )
        prevHrForStage = latestHrBpm
        recentEpochs.addLast(ep)
        val cap = maxOf(onsetEpochs, offsetEpochs)
        while (recentEpochs.size > cap) recentEpochs.removeFirst()

        when (_state) {
            State.WATCHING -> {
                if (recentEpochs.size >= onsetEpochs &&
                    recentEpochs.takeLast(onsetEpochs).all { it.sleep }
                ) {
                    // Backdate onset to when the quiet run began.
                    val onset = recentEpochs.elementAt(recentEpochs.size - onsetEpochs)
                    _state = State.RECORDING
                    _recordingSinceMs = onset.startMs
                    _recordingIsAuto = true
                    activeSessionEpochs.clear()
                    activeSessionEpochs.addAll(recentEpochs.takeLast(onsetEpochs))
                    onSessionStart(_recordingSinceMs, true)
                }
            }
            State.RECORDING -> {
                activeSessionEpochs.add(ep)
                if (recentEpochs.size >= offsetEpochs &&
                    recentEpochs.takeLast(offsetEpochs).all { !it.sleep }
                ) {
                    finalizeSession(tMs)
                }
            }
        }

        epochStartMs = tMs
        sumAbs = 0.0
        sampleCount = 0
    }

    fun manualStart(nowMs: Long) {
        if (_state == State.RECORDING) return
        _state = State.RECORDING
        _recordingSinceMs = nowMs
        _recordingIsAuto = false
        activeSessionEpochs.clear()
        onSessionStart(nowMs, false)
    }

    fun manualStop(nowMs: Long) {
        if (_state != State.RECORDING) return
        finalizeSession(nowMs)
    }

    private fun finalizeSession(nowMs: Long) {
        // For auto-detected sessions, trim the trailing wake epochs that
        // triggered offset — they're likely the person waking up, not sleep.
        val trim = if (_recordingIsAuto) offsetEpochs else 0
        val effective = if (activeSessionEpochs.size > trim)
            activeSessionEpochs.dropLast(trim) else activeSessionEpochs

        val sleepMin = effective.count { it.sleep }
        val wakeMin = effective.size - sleepMin
        val eff = if (effective.isNotEmpty()) sleepMin * 100 / effective.size else 0

        var episodes = 0
        var inWake = false
        for (ep in effective) {
            if (!ep.sleep && !inWake) { episodes++; inWake = true }
            else if (ep.sleep) inWake = false
        }

        val allHr = effective.mapNotNull { it.hrBpm }
        val avgHr = if (allHr.isNotEmpty()) allHr.average().toFloat() else null

        val actualEndMs = if (effective.isNotEmpty())
            effective.last().startMs + 60_000L else nowMs
        val totalMin = ((actualEndMs - _recordingSinceMs) / 60_000L).toInt()

        val lightMin = effective.count { it.stage == Stage.LIGHT }
        val deepMin = effective.count { it.stage == Stage.DEEP }
        val remMin = effective.count { it.stage == Stage.REM }

        if (totalMin >= minSessionMin) {
            val rec = HistoryStore.SleepRecord(
                startMs = _recordingSinceMs,
                endMs = actualEndMs,
                sleepMin = sleepMin,
                wakeMin = wakeMin,
                efficiencyPct = eff,
                wakeEpisodes = episodes,
                avgHrBpm = avgHr,
                lightMin = lightMin,
                deepMin = deepMin,
                remMin = remMin,
            )
            onSessionEnd(rec, effective)
        }

        _state = State.WATCHING
        _recordingSinceMs = 0
        _recordingIsAuto = false
        prevHrForStage = null
        activeSessionEpochs.clear()
    }

    // Wrist-only sleep staging is inherently coarse (no EEG/EOG), but we can
    // approximate the four stages using the two signals we do have:
    //   - WAKE  → epoch already classified as non-sleep.
    //   - DEEP  → very low motion AND HR ≤ resting+2 bpm (slow-wave: bradycardia + immobility).
    //   - REM   → sleep + HR jumps ≥6 bpm from prior minute (autonomic surges are the
    //             classic PPG-only tell for REM; also motion is often minimal).
    //   - LIGHT → any other sleep-classified epoch (default bucket).
    // This mirrors the heuristic Huawei/Fitbit/Garmin use with motion+PPG-only rigs.
    private fun classifyStage(isSleep: Boolean, activity: Float, hr: Float?): Stage {
        if (!isSleep) return Stage.WAKE
        val rest = restingHrBpm
        val prev = prevHrForStage
        val hrDelta = if (hr != null && prev != null) hr - prev else 0f
        if (hr != null && prev != null && hrDelta >= remHrSpikeBpm) return Stage.REM
        if (hr != null && rest != null && hr <= rest + deepHrOffsetBpm && activity < deepActivityG) return Stage.DEEP
        return Stage.LIGHT
    }

    companion object {
        /** Compute a rest-HR baseline from historical HR readings: 10th percentile. */
        fun computeRestingHr(hrSamples: FloatArray): Float? {
            if (hrSamples.isEmpty()) return null
            val sorted = hrSamples.copyOf().also { it.sort() }
            val idx = (sorted.size * 0.10f).toInt().coerceAtMost(sorted.size - 1)
            return sorted[idx]
        }
    }
}
