package com.biosense.vm

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biosense.analytics.AcwrEstimator
import com.biosense.analytics.AutoSleepDetector
import com.biosense.analytics.ExerciseModeClassifier
import com.biosense.analytics.HrEstimator
import com.biosense.analytics.StepCounter
import com.biosense.analytics.StressEstimator
import com.biosense.analytics.WellnessEstimator
import com.biosense.ble.BleManager
import com.biosense.ble.PacketParser
import com.biosense.data.HistoryStore
import com.biosense.data.MedicalIdStore
import com.biosense.data.MedicationStore
import com.biosense.data.ProfileStore
import com.biosense.notification.MedicationScheduler
import com.biosense.export.CsvExporter
import com.biosense.export.HistoryBackup
import com.biosense.export.HistoryCsvExporter
import com.biosense.export.WeeklyReportPdf
import com.biosense.health.HealthConnectSync
import android.net.Uri
import com.biosense.service.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class MonitorViewModel(app: Application) : AndroidViewModel(app), BleManager.Callbacks {

    // ---- Public UI state ----------------------------------------------------

    data class UiState(
        val connState: BleManager.ConnectionState = BleManager.ConnectionState.IDLE,
        val statusMessage: String = "Not connected",
        val deviceName: String? = null,
        val packetCount: Long = 0,
        val hrBpm: Float? = null,
        val hrvRmssdMs: Float? = null,
        val spo2Pct: Float? = null,
        val ppgContact: Boolean = false,
        val ppgReason: String = "",
        val gyroRmsDps: Float = 0f,
        val stepsToday: Long = 0,
        val accelTrace: FloatArray = FloatArray(0),
        val gyroTrace: FloatArray = FloatArray(0),
        val hrHistory: FloatArray = FloatArray(0),
        val hrvHistory: FloatArray = FloatArray(0),
        val spo2History: FloatArray = FloatArray(0),
        val gyroRmsHistory: FloatArray = FloatArray(0),
        val recording: Boolean = false,
        val recordedCount: Int = 0,
        val lastExport: String? = null,
        val errorSnack: String? = null,
        val batteryPct: Int? = null,
        val batteryCycles: Int? = null,
        val batteryChargeAccumPct: Int? = null,
        val sleepActive: Boolean = false,
        val sleepStartedMs: Long = 0,
        val sleepEpochs: Int = 0,
        val sleepSleepMin: Int = 0,
        val sleepWakeMin: Int = 0,
        val sleepIsAuto: Boolean = false,
        val sleepDetectorState: AutoSleepDetector.State = AutoSleepDetector.State.WATCHING,
        val sleepQuietRunMin: Int = 0,
        val restingHrBpm: Float? = null,
        val lastSleepSummary: HistoryStore.SleepRecord? = null,
        val wristStill: Boolean = false,
        val lastFallAtMs: Long = 0,
        val stressScore: Int? = null,
        val stressLabel: String = "",
        val stressHistory: FloatArray = FloatArray(0),
        val stepsHistory: FloatArray = FloatArray(0),
        val healthConnectEnabled: Boolean = false,
        val wellness: WellnessEstimator.Score? = null,
        val wellnessHistory: FloatArray = FloatArray(0),
        /** In-memory intraday wellness series (one point per 5-min recompute
         *  tick during the live session). Powers the Session range on the
         *  Wellness detail chart; Day / Week / Month still read from the
         *  persisted daily series. Capped at ~24 h of 5-min samples. */
        val wellnessIntraday: FloatArray = FloatArray(0),
        val mode: ExerciseModeClassifier.Mode = ExerciseModeClassifier.Mode.REST,
        val exerciseMinutesToday: Int = 0,
        val acwr: AcwrEstimator.Result? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // ---- Internals ----------------------------------------------------------

    private val ble = BleManager(app, this)
    private val history = HistoryStore(app.filesDir)
    private var lastHistoryWriteMs: Long = 0
    private val historyWriteEveryMs: Long = 5_000
    private val steps = StepCounter(sampleRateHz = 104f)
    private val stepSampleDtMs: Float = 1000f / 104f
    // StepCounter counts since construction; these turn that into a per-
    // calendar-day count that resets at midnight and survives app restarts.
    private var stepsDayKey: String = ""
    private var stepsAtDayStart: Long = 0
    @Volatile private var stepsRestoredToday: Long = 0
    private val sleep = AutoSleepDetector(
        onSessionStart = { startMs, auto ->
            _ui.value = _ui.value.copy(
                sleepActive = true,
                sleepStartedMs = startMs,
                sleepIsAuto = auto,
                sleepDetectorState = AutoSleepDetector.State.RECORDING,
            )
        },
        onSessionEnd = { rec, _ ->
            viewModelScope.launch(Dispatchers.IO) { runCatching { history.appendSleep(rec) } }
            refreshRestingHr()
            _ui.value = _ui.value.copy(
                sleepActive = false,
                sleepIsAuto = false,
                sleepDetectorState = AutoSleepDetector.State.WATCHING,
                lastSleepSummary = rec,
                sleepEpochs = 0,
                sleepSleepMin = 0,
                sleepWakeMin = 0,
                sleepQuietRunMin = 0,
            )
            // New sleep record materially changes the wellness score — recompute.
            recomputeWellness()
        },
    )
    // Packet version 1 firmware forwards PPG at ~10 Hz and replicates the
    // reading across every IMU sample in a packet; version 2 forwards every
    // MAX30102 sample (100 Hz). We dedupe to recover the true PPG rate and
    // analyze at the rate the packet version announces (see onPacket).
    private var ppgRateHz = 10f
    private var hr = HrEstimator(sampleRateHz = ppgRateHz)
    private val stress = StressEstimator()

    private val meds = MedicationStore.of(app)
    private val profileStore = ProfileStore(app)
    private var profile: ProfileStore.Profile = profileStore.load()

    // Sustained-out-of-bounds HR tracking. We only fire an alert if the wearer
    // holds outside their profile bounds for ≥ HR_BREACH_MIN_MS to avoid
    // pinging on brief spikes. Debounced by HR_ALERT_DEBOUNCE_MS between fires.
    private var hrBreachStartMs: Long = 0
    private var hrBreachKind: MonitorService.HrAlertKind? = null
    private var lastHrAlertMs: Long = 0
    private val hrBreachMinMs: Long = 60_000
    private val hrAlertDebounceMs: Long = 10 * 60_000
    private var lastFreshHrAtMs: Long = 0
    private val hrAlertGapToleranceMs: Long = 15_000

    private val hcPrefs = app.getSharedPreferences("biosense_prefs", android.content.Context.MODE_PRIVATE)
    private var lastHcWriteAtMs: Long = 0
    private var lastHcStepsTotal: Long = 0
    // Coalesce HC writes to ~1/min so we don't spam the platform with hundreds
    // of near-duplicate samples. HC stores per-sample; users want a readable
    // series, not raw analytics ticks.
    private val hcWriteEveryMs: Long = 60_000

    init {
        _ui.value = _ui.value.copy(healthConnectEnabled = hcPrefs.getBoolean(PREF_HC_ENABLED, false))
        refreshRestingHr()
        refreshStressBaseline()
        // Rehydrate the "last night's sleep" card from persisted history so it
        // isn't blank after a process restart — the detector only fills it on
        // finalizeSession(), which won't fire until the next completed session.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val recent = history.loadSleepSessions().maxByOrNull { it.endMs }
                if (recent != null) _ui.value = _ui.value.copy(lastSleepSummary = recent)
            }
        }
        // Re-arm dose alarms on cold start / process death; AlarmManager loses
        // exact alarms across reboots and, on some OEMs, on force-stop.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { MedicationScheduler.rescheduleAll(app) }
        }
        // Rehydrate today's wellness score from the persisted daily store, then
        // kick off the periodic recompute. Daily/slow signals — a 5-min cadence
        // is plenty.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val days = history.loadRecentDailyWellness(30)
                val arr = FloatArray(days.size) { days[it].score.toFloat() }
                _ui.value = _ui.value.copy(wellnessHistory = arr)
            }
            // Recover today's exercise-minute count from the persisted load row
            // so a mid-day process restart doesn't zero the counter.
            runCatching {
                val todays = history.loadDailyLoadFor(System.currentTimeMillis())
                if (todays != null && todays.loadMinutes > 0) {
                    _ui.value = _ui.value.copy(exerciseMinutesToday = todays.loadMinutes)
                }
            }
            // Same for today's steps. Max (not last) so a snapshot written
            // just after restart, before this restore lands, can't win.
            runCatching {
                stepsRestoredToday = history.loadDaySnapshots(System.currentTimeMillis())
                    .mapNotNull { it.steps }.maxOrNull() ?: 0L
            }
            recomputeWellness()
            recomputeAcwr()
            while (true) {
                kotlinx.coroutines.delay(5L * 60_000L)
                recomputeWellness()
                recomputeAcwr()
            }
        }
    }

    // ---- Exercise/Rest mode state machine ----------------------------------
    // The ExerciseModeClassifier is pure. We own the "has motion been sustained
    // for N seconds" counter here; it ticks up while gyroRms is above threshold
    // and resets after a short debounce when motion drops.
    private var sustainedMotionSec: Int = 0
    private var lastMotionAboveMs: Long = 0
    private val motionDebounceMs: Long = 15_000
    private var lastModeTickMs: Long = 0
    private var exerciseMinuteAccumMs: Long = 0
    private var currentDayKey: String = ""

    private val fastWindow = 1200          // ~11.5 s @ 104 Hz for the live chart
    private val analysisWindow = 512       // ~5 s window for IMU FFT
    private val ppgWindow = 3000           // ~30 s @ 100 Hz (or 300 s @ 10 Hz) for PPG analysis

    private val axBuf = RingBuffer(fastWindow)
    private val ayBuf = RingBuffer(fastWindow)
    private val azBuf = RingBuffer(fastWindow)
    private val gxBuf = RingBuffer(fastWindow)
    private val gyBuf = RingBuffer(fastWindow)
    private val gzBuf = RingBuffer(fastWindow)
    private val irBuf = RingBuffer(ppgWindow)
    private val redBuf = RingBuffer(ppgWindow)
    private var lastIrPushed: Long = -1

    private val recorded = ArrayList<CsvExporter.Timestamped>(50_000)
    private val packetCounter = AtomicLong(0)
    private var lastAnalyzeAtSamples: Long = 0

    // Session-scope metric histories: append each analysis tick (~2 Hz), cap to
    // ~30 min (3600 points) so the detail chart can render a proper trend.
    private val historyCap = 3600
    private val hrHist = ArrayList<Float>(historyCap)
    private val hrvHist = ArrayList<Float>(historyCap)
    private val spo2Hist = ArrayList<Float>(historyCap)
    private val gyroRmsHist = ArrayList<Float>(historyCap)
    private val stressHist = ArrayList<Float>(historyCap)
    private val stepsHist = ArrayList<Float>(historyCap)
    // Intraday wellness series — appended on every successful recompute tick
    // (~1 per 5 min, so 288 covers ~24 h of continuous wear). Capped tighter
    // than the fast metrics because the wellness score only moves on 5-min
    // boundaries, not per analytics tick.
    private val wellnessIntradayCap = 288
    private val wellnessIntradayHist = ArrayList<Float>(wellnessIntradayCap)

    private fun appendHistory(list: ArrayList<Float>, v: Float) {
        list.add(v)
        if (list.size > historyCap) list.removeAt(0)
    }

    // SpO2 stillness gate — RED perfusion is fragile, so we only trust a fresh
    // estimate when the wrist has been genuinely still for a few analysis
    // ticks. Analysis fires every ~500 ms, so 6 ticks ≈ 3 s of stillness.
    private var stillTicks: Int = 0
    private val stillTicksRequired = 6
    private val stillGyroThresholdDps = 15f

    fun canOperateBle(): Boolean = ble.isBluetoothSupported()
    fun isBluetoothEnabled(): Boolean = ble.isBluetoothEnabled()

    fun connect() {
        // Bring up the foreground service first so the BLE stack survives
        // screen-off / app-backgrounded. Idempotent — safe to call repeatedly.
        MonitorService.start(getApplication())
        ble.connect()
    }
    fun disconnect() {
        ble.disconnect()
        MonitorService.stop(getApplication())
    }

    /**
     * Fire on app start / resume. If we've paired before and aren't already
     * hooked up, kick off a silent auto-connect via connectGatt(autoConnect=true)
     * so the wearable snaps back on as soon as it comes into range — no tap
     * needed.
     */
    fun autoConnectIfPaired() {
        if (!ble.hasSavedDevice()) return
        val s = _ui.value.connState
        val busy = s == BleManager.ConnectionState.CONNECTED ||
            s == BleManager.ConnectionState.CONNECTING ||
            s == BleManager.ConnectionState.SCANNING ||
            s == BleManager.ConnectionState.RECONNECTING
        if (busy) return
        connect()
    }
    fun setAutoReconnect(enabled: Boolean) = ble.setAutoReconnect(enabled)
    fun forgetDevice() = ble.forgetDevice()

    /** MAC of the last-paired wearable — needed to target the DFU update. */
    fun pairedDeviceMac(): String? = ble.savedMac()

    fun toggleRecording() {
        val newState = !_ui.value.recording
        if (newState) recorded.clear()
        _ui.value = _ui.value.copy(recording = newState, recordedCount = recorded.size)
    }

    fun exportRecording() {
        val snapshot = recorded.toList()
        if (snapshot.isEmpty()) {
            _ui.value = _ui.value.copy(errorSnack = "Nothing recorded yet.")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "biosense_$stamp.csv"
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    CsvExporter.saveSession(getApplication(), snapshot, name)
                }
                _ui.value = _ui.value.copy(lastExport = saved)
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(errorSnack = "Export failed: ${t.message}")
            }
        }
    }

    fun clearSnack() { _ui.value = _ui.value.copy(errorSnack = null) }

    fun showSnack(msg: String) { _ui.value = _ui.value.copy(errorSnack = msg) }

    /** Meters per step derived from the saved profile (height × sex factor). */
    fun strideMeters(): Float = profile.strideMeters()

    /** Re-read the persisted profile — called after ProfileScreen saves. */
    fun reloadProfile() { profile = profileStore.load() }

    /** Zip up the entire history/ folder to Downloads/Biosense/. */
    fun backupHistory() {
        if (profile.fullName.isBlank()) {
            _ui.value = _ui.value.copy(
                errorSnack = "Set up your profile first (Me → Profile) to enable exports.",
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "biosense_history_$stamp.zip"
            val msg = runCatching { HistoryBackup.exportZip(getApplication(), name) }
                .fold(
                    onSuccess = { "Backed up history to Downloads/Biosense/$it" },
                    onFailure = { "Backup failed: ${it.message}" },
                )
            _ui.value = _ui.value.copy(errorSnack = msg)
        }
    }

    /** Local midnight of the first day with recorded vitals, or null if none yet. */
    suspend fun earliestHistoryDayMs(): Long? = withContext(Dispatchers.IO) { history.earliestDayMs() }

    /**
     * Export the persisted history between two calendar days (inclusive, local
     * time) as one downloadable CSV with a heartbeat dedup — rows whose values
     * stay within tolerance of the previous kept row are collapsed into that
     * row (newest timestamp wins). Step changes always get their own row.
     */
    fun exportHistoryCsv(fromMs: Long, toMs: Long) {
        if (profile.fullName.isBlank()) {
            _ui.value = _ui.value.copy(
                errorSnack = "Set up your profile first (Me → Profile) to enable exports.",
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val fileFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
            val labelFmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            val fromLabel = labelFmt.format(Date(fromMs))
            val toLabel = labelFmt.format(Date(toMs))
            val name = "biosense_history_${fileFmt.format(Date(fromMs))}_to_${fileFmt.format(Date(toMs))}.csv"
            val msg = runCatching {
                val rows = history.loadSnapshotsInRange(fromMs, toMs)
                if (rows.isEmpty()) error("no data between $fromLabel and $toLabel")
                val info = MedicalIdStore(getApplication()).load()
                // Build day-key → score map for the wellness column. Pull plenty of
                // history so old rows still get their day's score if one existed.
                val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = java.util.TimeZone.getDefault()
                }
                val wellnessByDay = history.loadRecentDailyWellness(365)
                    .associate { dayFmt.format(Date(it.tDayMs)) to it.score }
                // Current ACWR snapshot for the CSV metadata header. Live from
                // the UI state — recomputed every 5 min on the same loop as
                // wellness, so it's fresh.
                val acwr = _ui.value.acwr
                HistoryCsvExporter.export(
                    getApplication(), rows, profile, info, wellnessByDay, acwr,
                    fromLabel, toLabel, name,
                )
            }.fold(
                onSuccess = { "Saved CSV to Downloads/Biosense/$it" },
                onFailure = { "CSV export failed: ${it.message}" },
            )
            _ui.value = _ui.value.copy(errorSnack = msg)
        }
    }

    /** Render a 7-day PDF (vitals + adherence + med list + medical ID) into Downloads. */
    fun exportWeeklyReport() {
        if (profile.fullName.isBlank()) {
            _ui.value = _ui.value.copy(
                errorSnack = "Set up your profile first (Me → Profile) to enable exports.",
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching {
                val series = history.loadRecentDays(7)
                val wellness = history.loadRecentDailyWellness(7)
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                val sleeps = history.loadSleepSessions().filter { it.endMs >= weekAgo }
                // Snapshot ACWR for the report. The in-memory value is fresh —
                // recomputed every 5 min on the same loop as wellness.
                val acwr = _ui.value.acwr
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val name = "biosense_report_$stamp.pdf"
                WeeklyReportPdf.export(getApplication(), profile, series, wellness, sleeps, acwr, name)
            }.fold(
                onSuccess = { "Saved report to Downloads/Biosense/$it" },
                onFailure = { "Report failed: ${it.message}" },
            )
            _ui.value = _ui.value.copy(errorSnack = msg)
        }
    }

    /** Merge a previously exported ZIP back into the history folder. */
    fun restoreHistory(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching { HistoryBackup.importZip(getApplication(), uri) }
                .fold(
                    onSuccess = { n -> "Restored $n files. Refresh the app to see them." },
                    onFailure = { "Restore failed: ${it.message}" },
                )
            _ui.value = _ui.value.copy(errorSnack = msg)
            refreshRestingHr()
            refreshStressBaseline()
        }
    }

    // ---- Sleep session ------------------------------------------------------

    fun startSleepSession() = sleep.manualStart(System.currentTimeMillis())
    fun stopSleepSession() = sleep.manualStop(System.currentTimeMillis())

    suspend fun loadSleepSessions(): List<HistoryStore.SleepRecord> =
        withContext(Dispatchers.IO) { history.loadSleepSessions() }

    /** Refresh resting-HR baseline from the last week of persisted HR samples. */
    private fun refreshRestingHr() {
        viewModelScope.launch(Dispatchers.IO) {
            val week = runCatching { history.loadRecentDays(7) }.getOrNull() ?: return@launch
            val rest = AutoSleepDetector.computeRestingHr(week.hr)
            sleep.setRestingHr(rest)
            stress.setRestingHr(rest)
            _ui.value = _ui.value.copy(restingHrBpm = rest)
        }
    }

    /** Rebuild the personal RMSSD baseline the stress estimator scores against. */
    private fun refreshStressBaseline() {
        viewModelScope.launch(Dispatchers.IO) {
            val week = runCatching { history.loadRecentDays(7) }.getOrNull() ?: return@launch
            stress.setRmssdBaseline(week.hrv)
        }
    }

    /**
     * Build today's wellness score from the three input components (HRV, RHR,
     * sleep), persist it to `wellness.jsonl`, and surface on the UI. Called from
     * `init` once at startup, every 5 minutes thereafter, and on sleep session end.
     */
    private fun recomputeWellness() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                val todayDay = history.loadDay(now)
                val recent7 = history.loadRecentDays(7)

                // --- Component 1: HRV — today's mean normalized via percentile
                // against the trailing 7-day reference distribution.
                val meanHrvToday = if (todayDay.hrv.isNotEmpty())
                    todayDay.hrv.average().toFloat() else null
                val hrvComponent = meanHrvToday?.let {
                    rmssdPercentile(recent7.hrv, it)
                }

                // --- Component 2: RHR — sigmoid of today's resting-HR delta
                // against the trailing 7-day resting-HR reference. Baseline is
                // recomputed per tick from the current 7-day window (rather
                // than read from the UiState slot that `refreshRestingHr`
                // populates), so a fresh install produces a valid baseline
                // within the first recompute cycle — no need to wait for a
                // sleep session or app restart to seed the slot.
                val rhrToday = AutoSleepDetector.computeRestingHr(todayDay.hr)
                val rhrBaseline = AutoSleepDetector.computeRestingHr(recent7.hr)
                // Propagate the fresh baseline back to UiState + stateful
                // consumers so the sleep card's "rest HR" line and the stress
                // estimator stay in sync, even if no wellness score publishes
                // this tick (e.g. still 1-of-3 components).
                if (rhrBaseline != null) {
                    sleep.setRestingHr(rhrBaseline)
                    stress.setRestingHr(rhrBaseline)
                    _ui.value = _ui.value.copy(restingHrBpm = rhrBaseline)
                }
                val rhrComponent = if (rhrToday != null && rhrBaseline != null)
                    sigmoid((rhrBaseline - rhrToday) / 5f) else null

                // --- Component 3: Sleep (efficiency × duration adequacy)
                val sleepRec = _ui.value.lastSleepSummary
                val sleepFresh = sleepRec != null && (now - sleepRec.endMs) < 24L * 60L * 60L * 1000L
                val sleepComponent = if (sleepFresh && sleepRec != null) {
                    val eff = sleepRec.efficiencyPct / 100f
                    val dur = (sleepRec.sleepMin / 480f).coerceAtMost(1f)
                    0.5f * eff + 0.5f * dur
                } else null

                val components = WellnessEstimator.Components(hrvComponent, rhrComponent, sleepComponent)
                val score = WellnessEstimator.compute(components)
                val exMin = _ui.value.exerciseMinutesToday
                if (score != null) {
                    history.upsertDailyWellness(
                        HistoryStore.DailyWellness(
                            tDayMs = now,
                            score = score.value,
                            label = score.label,
                            exerciseMin = exMin,
                        )
                    )
                    // Session intraday series: one point per successful recompute
                    // tick (~5-min cadence). Powers the Session range on the
                    // Wellness detail chart so users can see within-day variation
                    // (post-exercise dip, overnight recovery, etc.) rather than
                    // a single daily dot.
                    wellnessIntradayHist.add(score.value.toFloat())
                    if (wellnessIntradayHist.size > wellnessIntradayCap) {
                        wellnessIntradayHist.removeAt(0)
                    }
                    val days = history.loadRecentDailyWellness(30)
                    val arr = FloatArray(days.size) { days[it].score.toFloat() }
                    _ui.value = _ui.value.copy(
                        wellness = score,
                        wellnessHistory = arr,
                        wellnessIntraday = wellnessIntradayHist.toFloatArray(),
                    )
                } else {
                    _ui.value = _ui.value.copy(wellness = null)
                }
            }
        }
    }

    /**
     * Persist today's running exercise-minute count and recompute ACWR against
     * a dense 60-day window (long enough to let the 28-day chronic EWMA
     * stabilize). Called from `init` and every 5 min thereafter.
     */
    private fun recomputeAcwr() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                val todayLoad = _ui.value.exerciseMinutesToday
                // Upsert first so today's row is current. Guard against a stale
                // in-memory counter overwriting a larger persisted value (which
                // can happen briefly on cold start before the init-time seed).
                val existing = history.loadDailyLoadFor(now)?.loadMinutes ?: 0
                val persistLoad = maxOf(existing, todayLoad)
                history.upsertDailyLoad(HistoryStore.DailyLoad(now, persistLoad))

                val series = history.loadDenseDailyLoad(60, now)
                val realDays = history.countPersistedDailyLoadDays()
                val result = AcwrEstimator.compute(series.map { it.loadMinutes }, realDays)
                _ui.value = _ui.value.copy(acwr = result)
            }
        }
    }

    private fun rmssdPercentile(samples: FloatArray, v: Float): Float? {
        if (samples.size < 20) return null
        val sorted = samples.copyOf().also { it.sort() }
        var lo = 0; var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo.toFloat() / sorted.size.toFloat()
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + kotlin.math.exp(-x))

    suspend fun loadHistoryToday(): HistoryStore.DaySeries = withContext(Dispatchers.IO) {
        history.loadDay(System.currentTimeMillis())
    }

    suspend fun loadHistoryRecentDays(days: Int): HistoryStore.DaySeries = withContext(Dispatchers.IO) {
        history.loadRecentDays(days)
    }

    // ---- BLE callbacks (main thread) ---------------------------------------

    override fun onStatus(state: BleManager.ConnectionState, message: String?, deviceName: String?) {
        val prev = _ui.value
        // Clear vitals + battery on disconnect so stale values don't persist
        // across a device swap. During a live connection the hold-last-forever
        // policy in onPacket keeps the displayed values stable through motion;
        // the only time we want the cards to blank is when the link is gone.
        val clearVitals = state == BleManager.ConnectionState.DISCONNECTED ||
            state == BleManager.ConnectionState.ERROR ||
            state == BleManager.ConnectionState.IDLE
        _ui.value = prev.copy(
            connState = state,
            statusMessage = message ?: state.name,
            deviceName = deviceName ?: prev.deviceName,
            batteryPct = if (clearVitals) null else prev.batteryPct,
            batteryCycles = if (clearVitals) null else prev.batteryCycles,
            batteryChargeAccumPct = if (clearVitals) null else prev.batteryChargeAccumPct,
            hrBpm = if (clearVitals) null else prev.hrBpm,
            hrvRmssdMs = if (clearVitals) null else prev.hrvRmssdMs,
            spo2Pct = if (clearVitals) null else prev.spo2Pct,
            stressScore = if (clearVitals) null else prev.stressScore,
            stressLabel = if (clearVitals) "" else prev.stressLabel,
            ppgContact = if (clearVitals) false else prev.ppgContact,
            ppgReason = if (clearVitals) "" else prev.ppgReason,
        )
    }

    override fun onBattery(pct: Int) {
        _ui.value = _ui.value.copy(batteryPct = pct.coerceIn(0, 100))
    }

    override fun onBatteryStats(cycleCount: Int, chargeAccum: Int) {
        _ui.value = _ui.value.copy(
            batteryCycles = cycleCount.coerceAtLeast(0),
            batteryChargeAccumPct = chargeAccum.coerceIn(0, 99),
        )
    }

    override fun onHistoryRecord(captureMs: Long, irMean: Int, accelMg: Int, batteryPct: Int, flags: Int) {
        // Bit0 = IR valid (HR proxy), bit1 = accel valid. IR is stored /16 on
        // the wearable so it fits in u16 — undo that here. We don't attempt HR
        // extraction from a 1-min mean; just persist the raw proxy alongside
        // the activity level so the offline gaps aren't blank.
        val hasAccel = (flags and 0x02) != 0
        val gyroRms = if (hasAccel) (accelMg / 1000.0f) else null
        // Persist off-thread; we route it through HistoryStore so it lands
        // in the same JSONL file the live snapshots use.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                history.append(
                    HistoryStore.Snapshot(
                        tMs = captureMs,
                        hr = null,          // not extractable from a 60s IR mean
                        hrv = null,
                        spo2 = null,
                        gyroRms = gyroRms,  // reused as an activity-level slot
                    )
                )
            }
        }
    }

    override fun onHistoryFlushComplete(sent: Int) {
        if (sent > 0) {
            _ui.value = _ui.value.copy(
                errorSnack = "Restored $sent min of buffered data from wearable."
            )
        }
    }

    override fun onFallEvent(deviceUptimeSec: Long) {
        val nowMs = System.currentTimeMillis()
        _ui.value = _ui.value.copy(
            errorSnack = "FALL DETECTED — check on the wearer.",
            lastFallAtMs = nowMs,
        )
        // Also raise a high-priority Android notification via the foreground
        // service so it surfaces even when the app isn't in focus. This is
        // the safety-critical path — snackbar is nice-to-have, the OS
        // notification is what actually pages someone.
        MonitorService.raiseFallAlert(getApplication(), nowMs)
    }

    override fun onPacket(bytes: ByteArray) {
        val packet = PacketParser.parse(bytes) ?: return
        val nowMs = System.currentTimeMillis()

        // Follow the firmware's PPG forwarding rate. A change (reflash, or a
        // different wearable) restarts the estimator and the PPG buffers so
        // samples taken at two different rates are never analyzed together.
        val announcedPpgRateHz = if (packet.version >= 2) 100f else 10f
        if (announcedPpgRateHz != ppgRateHz) {
            ppgRateHz = announcedPpgRateHz
            hr = HrEstimator(sampleRateHz = ppgRateHz)
            irBuf.clear(); redBuf.clear()
            lastIrPushed = -1
        }

        for ((idx, s) in packet.samples.withIndex()) {
            axBuf.push(s.ax); ayBuf.push(s.ay); azBuf.push(s.az)
            gxBuf.push(s.gx); gyBuf.push(s.gy); gzBuf.push(s.gz)
            val sampleTMs = nowMs + (idx * stepSampleDtMs).toLong()
            steps.ingest(s.ax, s.ay, s.az, sampleTMs)
            sleep.ingest(s.ax, s.ay, s.az, sampleTMs)
            if (s.ir != lastIrPushed) {
                irBuf.push(s.ir.toFloat()); redBuf.push(s.red.toFloat())
                lastIrPushed = s.ir
            }
            if (_ui.value.recording) {
                recorded += CsvExporter.Timestamped(nowMs + (idx * 10L /* ≈ 104Hz */), s)
            }
        }
        packetCounter.incrementAndGet()

        // Only run analytics every ~half second to keep the UI light.
        val totalSamples = axBuf.total
        if (totalSamples - lastAnalyzeAtSamples >= 52) {
            lastAnalyzeAtSamples = totalSamples

            val axArr = axBuf.snapshotTail(analysisWindow)
            val ayArr = ayBuf.snapshotTail(analysisWindow)
            val azArr = azBuf.snapshotTail(analysisWindow)
            val gxArr = gxBuf.snapshotTail(analysisWindow)
            val gyArr = gyBuf.snapshotTail(analysisWindow)
            val gzArr = gzBuf.snapshotTail(analysisWindow)
            // 25 s of distinct PPG samples at the current forwarding rate
            // (250 at 10 Hz, 2500 at 100 Hz).
            val ppgTail = (ppgRateHz * 25f).toInt()
            val irArr = irBuf.snapshotTail(ppgTail)
            val redArr = redBuf.snapshotTail(ppgTail)

            val gyroRms = rmsMagnitude(gxArr, gyArr, gzArr)
            // Pass the 3-axis accel window into the HR estimator so it can run
            // its LMS motion-compensation filter against the wrist-accel
            // reference (see HrEstimator.lmsMotionClean). IMU runs at ~104 Hz;
            // the estimator decimates internally to the PPG rate.
            val hrRes = hr.estimate(irArr, redArr, axArr, ayArr, azArr, 104f)
            updateExerciseMode(gyroRms, hrRes.bpm, nowMs)

            val accelTrace = magnitudeTrail(axArr, ayArr, azArr, downsampleTo = 240)
            val gyroTrace = magnitudeTrail(gxArr, gyArr, gzArr, downsampleTo = 240)

            if (hrRes.reason.isNotEmpty()) {
                Log.d("PPG", "reject=${hrRes.reason} contact=${hrRes.contact} q=${hrRes.quality}")
            }

            val prev = _ui.value
            val gotValid = hrRes.bpm != null

            // Two values per vital:
            //  - fresh*: produced by THIS analysis window, or null. Only fresh
            //    values are stored, exported, sent to Health Connect, plotted
            //    on the session charts, or fed to stress / sleep / alerts.
            //  - next*:  what the UI card shows. Holds the last fresh value
            //    until a newer one arrives or the BLE link drops (cleared in
            //    onStatus), so the card never flickers to "acquiring…".
            // Never store or compute from next* — that would turn a held
            // display value into a fake measurement.
            val freshHr = hrRes.bpm
            val freshRmssd = hrRes.rmssdMs
            val nextHr = freshHr ?: prev.hrBpm
            val nextRmssd = freshRmssd ?: prev.hrvRmssdMs

            // Stillness counter — gates SpO2 freshness and drives wristStill.
            if (gyroRms < stillGyroThresholdDps) stillTicks++ else stillTicks = 0
            val isStill = stillTicks >= stillTicksRequired
            // SpO2 is fresh only when the wrist is still AND the red channel
            // produced a value.
            val freshSpo2 = if (isStill) hrRes.spo2Estimate else null
            val nextSpo2 = freshSpo2 ?: prev.spo2Pct

            // Stress from fresh inputs only, and only when PPG is clean and
            // the wearer isn't in motion.
            val suppressStress = StressEstimator.shouldSuppress(gyroRms, hrRes.contact)
            val freshStress = if (!suppressStress) stress.estimate(freshHr, freshRmssd) else null
            val nextStress = freshStress ?: prev.stressScore

            // Hold PPG metadata (contact / reason) with the displayed value so
            // the HR card subtitle doesn't flicker during transient failures.
            val nextContact = if (gotValid) hrRes.contact else prev.ppgContact
            val nextReason = if (gotValid) hrRes.reason else prev.ppgReason

            // Steps today = raw counter since the day started (or since app
            // start, if later) + any count restored from history on restart.
            rollStepsDayIfNeeded(nowMs)
            val stepsToday = stepsRestoredToday + (steps.total - stepsAtDayStart)

            freshHr?.let { appendHistory(hrHist, it) }
            freshRmssd?.let { appendHistory(hrvHist, it) }
            freshSpo2?.let { appendHistory(spo2Hist, it) }
            appendHistory(gyroRmsHist, gyroRms)
            appendHistory(stepsHist, stepsToday.toFloat())
            freshStress?.let { appendHistory(stressHist, it.toFloat()) }

            if (nowMs - lastHistoryWriteMs >= historyWriteEveryMs) {
                lastHistoryWriteMs = nowMs
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        history.append(
                            HistoryStore.Snapshot(
                                tMs = nowMs,
                                hr = freshHr,
                                hrv = freshRmssd,
                                spo2 = freshSpo2,
                                gyroRms = gyroRms,
                                steps = stepsToday,
                                stress = freshStress,
                            )
                        )
                    }
                }
            }

            // Mirror to Health Connect on a slower cadence (once per minute) so
            // Fit/Samsung/Fitbit reflect what we're recording without spamming
            // the platform with per-tick samples.
            if (_ui.value.healthConnectEnabled && nowMs - lastHcWriteAtMs >= hcWriteEveryMs) {
                val windowMs = if (lastHcWriteAtMs == 0L) hcWriteEveryMs else nowMs - lastHcWriteAtMs
                val stepsDelta = (steps.total - lastHcStepsTotal).coerceAtLeast(0)
                lastHcWriteAtMs = nowMs
                lastHcStepsTotal = steps.total
                val app = getApplication<Application>()
                viewModelScope.launch(Dispatchers.IO) {
                    HealthConnectSync.writeSnapshot(
                        ctx = app,
                        tMs = nowMs,
                        hrBpm = freshHr,
                        hrvRmssdMs = freshRmssd,
                        spo2Pct = freshSpo2,
                        stepsDelta = stepsDelta,
                        deltaWindowMs = windowMs,
                    )
                }
            }

            sleep.noteHr(freshHr)
            maybeFireHrAlert(freshHr, nowMs)

            _ui.value = prev.copy(
                packetCount = packetCounter.get(),
                hrBpm = nextHr,
                hrvRmssdMs = nextRmssd,
                spo2Pct = nextSpo2,
                ppgContact = nextContact,
                ppgReason = nextReason,
                gyroRmsDps = gyroRms,
                stepsToday = stepsToday,
                accelTrace = accelTrace,
                gyroTrace = gyroTrace,
                hrHistory = hrHist.toFloatArray(),
                hrvHistory = hrvHist.toFloatArray(),
                spo2History = spo2Hist.toFloatArray(),
                gyroRmsHistory = gyroRmsHist.toFloatArray(),
                stressHistory = stressHist.toFloatArray(),
                stepsHistory = stepsHist.toFloatArray(),
                recordedCount = recorded.size,
                sleepEpochs = sleep.activeEpochCount,
                sleepSleepMin = sleep.activeSleepMin,
                sleepWakeMin = sleep.activeWakeMin,
                sleepDetectorState = sleep.state,
                sleepQuietRunMin = sleep.quietRunMin,
                sleepIsAuto = sleep.recordingIsAuto,
                sleepActive = sleep.state == AutoSleepDetector.State.RECORDING,
                sleepStartedMs = if (sleep.state == AutoSleepDetector.State.RECORDING) sleep.recordingSinceMs else prev.sleepStartedMs,
                wristStill = isStill,
                stressScore = nextStress,
                stressLabel = nextStress?.let { StressEstimator.label(it) } ?: "",
            )
        }
    }

    /**
     * Per-tick mode update. Grows [sustainedMotionSec] while gyro is above
     * threshold; resets to 0 after [motionDebounceMs] of continuous quiet.
     * Accumulates [exerciseMinuteAccumMs] whenever the resulting mode is
     * EXERCISE, flushing to a minute counter at the 60 s boundary. Rolls over
     * [exerciseMinutesToday] at the day boundary.
     */
    private fun updateExerciseMode(gyroRmsDps: Float, currentHr: Float?, nowMs: Long) {
        // Day-boundary rollover for the exercise-minutes counter.
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
        if (currentDayKey.isEmpty()) currentDayKey = todayKey
        if (todayKey != currentDayKey) {
            // Finalize yesterday's load row before we lose the counter — ACWR
            // depends on a complete day-by-day series. Anchor the timestamp at
            // noon of yesterday's day-key, parsed back from `currentDayKey`, so
            // yesterday's load lands in yesterday's row even when the rollover
            // detection fires hours after actual midnight (e.g. device off-wrist
            // charging overnight; BLE reconnects at 07:00 next morning).
            val yesterdayLoad = _ui.value.exerciseMinutesToday
            val yesterdayAnchorMs = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = java.util.TimeZone.getDefault()
            }.parse(currentDayKey)!!.time + 12L * 60L * 60L * 1000L
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    history.upsertDailyLoad(HistoryStore.DailyLoad(yesterdayAnchorMs, yesterdayLoad))
                }
                recomputeAcwr()
            }
            currentDayKey = todayKey
            exerciseMinuteAccumMs = 0
            _ui.value = _ui.value.copy(exerciseMinutesToday = 0)
        }

        // Track sustained motion for the IMU half of the classifier.
        val aboveMotion = gyroRmsDps >= ExerciseModeClassifier.MOTION_SUSTAIN_THRESHOLD_DPS
        val lastTick = lastModeTickMs
        lastModeTickMs = nowMs
        val dtMs = if (lastTick == 0L) 0L else (nowMs - lastTick).coerceAtMost(2_000L)
        if (aboveMotion) {
            sustainedMotionSec = minOf(10_000, sustainedMotionSec + (dtMs / 1000L).toInt())
            lastMotionAboveMs = nowMs
        } else if (nowMs - lastMotionAboveMs > motionDebounceMs) {
            sustainedMotionSec = 0
        }

        // Age-based HRmax from the stored profile (Tanaka 2001, same formula as
        // ProfileStore.ageDerivedHigh — we reuse the stored high-threshold
        // directly to avoid duplicating the formula).
        val hrMax = profile.effectiveHighBpm()
        val mode = ExerciseModeClassifier.classify(
            currentHr = currentHr,
            rhrBaseline = _ui.value.restingHrBpm,
            hrMax = hrMax,
            gyroRmsDps = gyroRmsDps,
            sustainedMotionSec = sustainedMotionSec,
        )

        // Accrue exercise minutes while in EXERCISE mode.
        if (mode == ExerciseModeClassifier.Mode.EXERCISE && dtMs > 0) {
            exerciseMinuteAccumMs += dtMs
            if (exerciseMinuteAccumMs >= 60_000L) {
                val whole = (exerciseMinuteAccumMs / 60_000L).toInt()
                exerciseMinuteAccumMs -= whole * 60_000L
                _ui.value = _ui.value.copy(
                    exerciseMinutesToday = _ui.value.exerciseMinutesToday + whole,
                )
            }
        }

        if (mode != _ui.value.mode) _ui.value = _ui.value.copy(mode = mode)
    }

    private fun rmsMagnitude(gx: FloatArray, gy: FloatArray, gz: FloatArray): Float {
        val n = minOf(gx.size, gy.size, gz.size)
        if (n == 0) return 0f
        var s = 0f
        for (i in 0 until n) {
            s += gx[i] * gx[i] + gy[i] * gy[i] + gz[i] * gz[i]
        }
        return kotlin.math.sqrt(s / n)
    }

    private fun magnitudeTrail(x: FloatArray, y: FloatArray, z: FloatArray, downsampleTo: Int): FloatArray {
        val n = minOf(x.size, y.size, z.size)
        if (n == 0) return FloatArray(0)
        val step = maxOf(1, n / downsampleTo)
        val outLen = n / step
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val idx = i * step
            val vx = x[idx]; val vy = y[idx]; val vz = z[idx]
            out[i] = kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)
        }
        return out
    }

    // ---- Health Connect -----------------------------------------------------

    fun healthConnectAvailability() = HealthConnectSync.availability(getApplication())
    fun isHealthConnectEnabled(): Boolean = hcPrefs.getBoolean(PREF_HC_ENABLED, false)
    fun setHealthConnectEnabled(enabled: Boolean) {
        hcPrefs.edit().putBoolean(PREF_HC_ENABLED, enabled).apply()
        _ui.value = _ui.value.copy(healthConnectEnabled = enabled)
    }

    // ---- Medications --------------------------------------------------------

    suspend fun listMedications(): List<MedicationStore.Medication> =
        withContext(Dispatchers.IO) { meds.list() }

    suspend fun recentMedEvents(daysBack: Int): List<MedicationStore.Event> =
        withContext(Dispatchers.IO) {
            val since = System.currentTimeMillis() - daysBack * 24L * 60L * 60L * 1000L
            meds.recentEvents(since)
        }

    fun upsertMedication(m: MedicationStore.Medication) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                meds.upsert(m)
                MedicationScheduler.rescheduleAll(getApplication())
            }
        }
    }

    fun deleteMedication(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val m = meds.list().firstOrNull { it.id == id }
                meds.delete(id)
                if (m != null) MedicationScheduler.cancelForMedication(getApplication(), m)
            }
        }
    }

    fun setMedicationEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val m = meds.list().firstOrNull { it.id == id } ?: return@runCatching
                meds.upsert(m.copy(enabled = enabled))
                MedicationScheduler.rescheduleAll(getApplication())
            }
        }
    }

    fun logMedicationAction(
        id: String,
        name: String,
        dose: String,
        action: MedicationStore.Action,
        scheduledHhmm: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                meds.logEvent(
                    MedicationStore.Event(
                        tMs = System.currentTimeMillis(),
                        id = id,
                        name = name,
                        dose = dose,
                        action = action,
                        scheduledHhmm = scheduledHhmm,
                    )
                )
            }
        }
    }

    /** Reset the steps-today baseline when the calendar day changes. */
    private fun rollStepsDayIfNeeded(nowMs: Long) {
        val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
        if (stepsDayKey.isEmpty()) { stepsDayKey = key; return }
        if (key != stepsDayKey) {
            stepsDayKey = key
            stepsAtDayStart = steps.total
            stepsRestoredToday = 0
        }
    }

    /** [hrBpm] must be a FRESH reading from this window (null if none). */
    private fun maybeFireHrAlert(hrBpm: Float?, nowMs: Long) {
        if (!profile.alertsEnabled) {
            hrBreachStartMs = 0
            hrBreachKind = null
            return
        }
        if (hrBpm == null) {
            // No fresh reading this window. Carry a breach through brief
            // dropouts, but abandon it once readings have stopped for longer
            // than the gap tolerance, so an alert never fires on stale data.
            if (nowMs - lastFreshHrAtMs > hrAlertGapToleranceMs) {
                hrBreachStartMs = 0
                hrBreachKind = null
            }
            return
        }
        lastFreshHrAtMs = nowMs
        val bpm = hrBpm.toInt()
        val high = profile.effectiveHighBpm()
        val low = profile.effectiveLowBpm()
        val kind = when {
            bpm >= high -> MonitorService.HrAlertKind.HIGH
            bpm <= low -> MonitorService.HrAlertKind.LOW
            else -> null
        }
        if (kind == null) {
            hrBreachStartMs = 0
            hrBreachKind = null
            return
        }
        if (hrBreachKind != kind) {
            hrBreachKind = kind
            hrBreachStartMs = nowMs
            return
        }
        val sustained = nowMs - hrBreachStartMs >= hrBreachMinMs
        val debounced = nowMs - lastHrAlertMs >= hrAlertDebounceMs
        if (sustained && debounced) {
            lastHrAlertMs = nowMs
            MonitorService.raiseHrAlert(getApplication(), bpm, kind)
        }
    }

    companion object {
        private const val PREF_HC_ENABLED = "health_connect_enabled"
    }

    private class RingBuffer(val capacity: Int) {
        private val data = FloatArray(capacity)
        private var head = 0
        private var size = 0
        var total: Long = 0; private set

        fun push(v: Float) {
            data[head] = v
            head = (head + 1) % capacity
            if (size < capacity) size++
            total++
        }

        fun clear() {
            head = 0
            size = 0
        }

        fun snapshotTail(n: Int): FloatArray {
            val len = minOf(n, size)
            val out = FloatArray(len)
            var idx = (head - len + capacity) % capacity
            for (i in 0 until len) {
                out[i] = data[idx]
                idx = (idx + 1) % capacity
            }
            return out
        }
    }
}
