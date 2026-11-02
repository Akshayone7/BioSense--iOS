package com.biosense.data

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Persists a rolling per-day rollup of metric snapshots as JSON Lines under
 * `<filesDir>/history/YYYY-MM-DD.jsonl`. One line per snapshot, format:
 *
 *   {"t":<epochMs>,"hr":<bpm>,"hrv":<ms>,"spo2":<pct>,"tr":<0-100>,"gy":<dps>}
 *
 * Nullable metrics are simply omitted from the line. Read APIs return
 * per-metric FloatArrays suitable for the existing LineChart.
 */
class HistoryStore(private val filesDir: File) {

    private val historyDir = File(filesDir, "history").also { if (!it.exists()) it.mkdirs() }
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    data class Snapshot(
        val tMs: Long,
        val hr: Float?,
        val hrv: Float?,
        val spo2: Float?,
        val gyroRms: Float?,
        val steps: Long? = null,
        val stress: Int? = null,
    )

    data class DaySeries(
        val hr: FloatArray,
        val hrv: FloatArray,
        val spo2: FloatArray,
        val gyroRms: FloatArray,
        val steps: FloatArray,
        val stress: FloatArray,
        val startMs: Long = 0,
        val endMs: Long = 0,
    )

    fun append(snap: Snapshot) {
        val file = File(historyDir, dayFmt.format(Date(snap.tMs)) + ".jsonl")
        FileWriter(file, true).use { w ->
            w.append('{')
            w.append("\"t\":").append(snap.tMs.toString())
            snap.hr?.let { w.append(",\"hr\":").append("%.1f".format(it)) }
            snap.hrv?.let { w.append(",\"hrv\":").append("%.1f".format(it)) }
            snap.spo2?.let { w.append(",\"spo2\":").append("%.1f".format(it)) }
            snap.gyroRms?.let { w.append(",\"gy\":").append("%.2f".format(it)) }
            snap.steps?.let { w.append(",\"st\":").append(it.toString()) }
            snap.stress?.let { w.append(",\"sx\":").append(it.toString()) }
            w.append("}\n")
        }
    }

    /** Load all snapshots for the calendar day containing [epochMs]. */
    fun loadDay(epochMs: Long): DaySeries {
        val file = File(historyDir, dayFmt.format(Date(epochMs)) + ".jsonl")
        if (!file.exists()) return DaySeries(F0, F0, F0, F0, F0, F0)
        val hr = ArrayList<Float>()
        val hrv = ArrayList<Float>()
        val spo2 = ArrayList<Float>()
        val gyro = ArrayList<Float>()
        val steps = ArrayList<Float>()
        val stress = ArrayList<Float>()
        var startMs = 0L; var endMs = 0L
        file.forEachLine { line ->
            parseLongField(line, "\"t\":")?.let {
                if (startMs == 0L) startMs = it
                endMs = it
            }
            parseFloatField(line, "\"hr\":")?.let(hr::add)
            parseFloatField(line, "\"hrv\":")?.let(hrv::add)
            parseFloatField(line, "\"spo2\":")?.let(spo2::add)
            parseFloatField(line, "\"gy\":")?.let(gyro::add)
            parseFloatField(line, "\"st\":")?.let(steps::add)
            parseFloatField(line, "\"sx\":")?.let(stress::add)
        }
        return DaySeries(
            hr.toFloatArray(),
            hrv.toFloatArray(),
            spo2.toFloatArray(),
            gyro.toFloatArray(),
            steps.toFloatArray(),
            stress.toFloatArray(),
            startMs = startMs,
            endMs = endMs,
        )
    }

    /**
     * Read one day's JSONL back as the raw timestamped Snapshot list (same shape
     * as what `append()` writes). Used by the CSV database exporter, which needs
     * per-row `tMs` plus the steps/stress fields that `DaySeries` flattens away.
     */
    fun loadDaySnapshots(epochMs: Long): List<Snapshot> {
        val file = File(historyDir, dayFmt.format(Date(epochMs)) + ".jsonl")
        if (!file.exists()) return emptyList()
        val out = ArrayList<Snapshot>()
        file.forEachLine { line ->
            val t = parseLongField(line, "\"t\":") ?: return@forEachLine
            out += Snapshot(
                tMs = t,
                hr = parseFloatField(line, "\"hr\":"),
                hrv = parseFloatField(line, "\"hrv\":"),
                spo2 = parseFloatField(line, "\"spo2\":"),
                gyroRms = parseFloatField(line, "\"gy\":"),
                steps = parseLongField(line, "\"st\":"),
                stress = parseIntField(line, "\"sx\":"),
            )
        }
        return out
    }

    private fun dayFiles(): List<File> =
        historyDir.listFiles { f ->
            f.isFile && f.name.matches(Regex("""\d{4}-\d{2}-\d{2}\.jsonl"""))
        }?.sortedBy { it.name } ?: emptyList()

    /** Local midnight of the earliest day that has vitals data, or null if none. */
    fun earliestDayMs(): Long? {
        val first = dayFiles().firstOrNull() ?: return null
        return dayFmt.parse(first.name.removeSuffix(".jsonl"))?.time
    }

    /**
     * Snapshots from every calendar day between [fromMs] and [toMs] inclusive
     * (local time), oldest→newest. Day keys are `yyyy-MM-dd`, which sort
     * correctly as text, so the range check is a plain string comparison.
     * Skips the `sleep.jsonl` / wellness / medication files.
     */
    fun loadSnapshotsInRange(fromMs: Long, toMs: Long): List<Snapshot> {
        val fromKey = dayFmt.format(Date(fromMs))
        val toKey = dayFmt.format(Date(toMs))
        val out = ArrayList<Snapshot>()
        for (f in dayFiles()) {
            val key = f.name.removeSuffix(".jsonl")
            if (key < fromKey || key > toKey) continue
            f.forEachLine { line ->
                val t = parseLongField(line, "\"t\":") ?: return@forEachLine
                out += Snapshot(
                    tMs = t,
                    hr = parseFloatField(line, "\"hr\":"),
                    hrv = parseFloatField(line, "\"hrv\":"),
                    spo2 = parseFloatField(line, "\"spo2\":"),
                    gyroRms = parseFloatField(line, "\"gy\":"),
                    steps = parseLongField(line, "\"st\":"),
                    stress = parseIntField(line, "\"sx\":"),
                )
            }
        }
        return out
    }

    /** Aggregate the last [days] daily files into one series per metric (oldest→newest). */
    fun loadRecentDays(days: Int, nowMs: Long = System.currentTimeMillis()): DaySeries {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val hr = ArrayList<Float>()
        val hrv = ArrayList<Float>()
        val spo2 = ArrayList<Float>()
        val gyro = ArrayList<Float>()
        val steps = ArrayList<Float>()
        val stress = ArrayList<Float>()
        var startMs = 0L; var endMs = 0L
        repeat(days) {
            val d = loadDay(cal.timeInMillis)
            hr += d.hr.toList()
            hrv += d.hrv.toList()
            spo2 += d.spo2.toList()
            gyro += d.gyroRms.toList()
            steps += d.steps.toList()
            stress += d.stress.toList()
            if (d.startMs != 0L) {
                if (startMs == 0L) startMs = d.startMs
                endMs = d.endMs
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return DaySeries(
            hr.toFloatArray(),
            hrv.toFloatArray(),
            spo2.toFloatArray(),
            gyro.toFloatArray(),
            steps.toFloatArray(),
            stress.toFloatArray(),
            startMs = startMs,
            endMs = endMs,
        )
    }

    // ---- Sleep sessions -----------------------------------------------------
    //
    // Written to <filesDir>/history/sleep.jsonl (append-only), one line per
    // finished session:
    //   {"s":<startMs>,"e":<endMs>,"sm":<sleepMin>,"wm":<wakeMin>,"eff":<0-100>,
    //    "we":<wakeEpisodes>,"hr":<avgBpm|null>,"lm":<lightMin>,"dm":<deepMin>,"rm":<remMin>}
    // Epoch-level detail lives with the session's summary in memory only —
    // we keep the persisted line small so scans stay fast.

    data class SleepRecord(
        val startMs: Long,
        val endMs: Long,
        val sleepMin: Int,
        val wakeMin: Int,
        val efficiencyPct: Int,
        val wakeEpisodes: Int,
        val avgHrBpm: Float?,
        val lightMin: Int = 0,
        val deepMin: Int = 0,
        val remMin: Int = 0,
    )

    private val sleepFile = File(historyDir, "sleep.jsonl")

    fun appendSleep(rec: SleepRecord) {
        FileWriter(sleepFile, true).use { w ->
            w.append('{')
                .append("\"s\":").append(rec.startMs.toString())
                .append(",\"e\":").append(rec.endMs.toString())
                .append(",\"sm\":").append(rec.sleepMin.toString())
                .append(",\"wm\":").append(rec.wakeMin.toString())
                .append(",\"eff\":").append(rec.efficiencyPct.toString())
                .append(",\"we\":").append(rec.wakeEpisodes.toString())
            rec.avgHrBpm?.let { w.append(",\"hr\":").append("%.1f".format(it)) }
            w.append(",\"lm\":").append(rec.lightMin.toString())
                .append(",\"dm\":").append(rec.deepMin.toString())
                .append(",\"rm\":").append(rec.remMin.toString())
            w.append("}\n")
        }
    }

    fun loadSleepSessions(): List<SleepRecord> {
        if (!sleepFile.exists()) return emptyList()
        val out = ArrayList<SleepRecord>()
        sleepFile.forEachLine { line ->
            val s = parseLongField(line, "\"s\":") ?: return@forEachLine
            val e = parseLongField(line, "\"e\":") ?: return@forEachLine
            val sm = parseIntField(line, "\"sm\":") ?: 0
            val wm = parseIntField(line, "\"wm\":") ?: 0
            val eff = parseIntField(line, "\"eff\":") ?: 0
            val we = parseIntField(line, "\"we\":") ?: 0
            val hr = parseFloatField(line, "\"hr\":")
            val lm = parseIntField(line, "\"lm\":") ?: 0
            val dm = parseIntField(line, "\"dm\":") ?: 0
            val rm = parseIntField(line, "\"rm\":") ?: 0
            out.add(SleepRecord(s, e, sm, wm, eff, we, hr, lm, dm, rm))
        }
        return out
    }

    // ---- Daily wellness score persistence ----------------------------------
    //
    // Written to <filesDir>/history/wellness.jsonl, one line per calendar day:
    //   {"t":<dayMs>,"score":<int>,"label":"<word>","exMin":<int>}
    // Idempotent per day via upsertDailyWellness — same-day re-saves overwrite
    // the matching line rather than appending.

    data class DailyWellness(
        val tDayMs: Long,
        val score: Int,
        val label: String,
        val exerciseMin: Int,
    )

    private val wellnessFile = File(historyDir, "wellness.jsonl")

    fun upsertDailyWellness(rec: DailyWellness) {
        val dayKey = dayFmt.format(Date(rec.tDayMs))
        val keep = ArrayList<String>()
        if (wellnessFile.exists()) {
            wellnessFile.forEachLine { line ->
                val t = parseLongField(line, "\"t\":") ?: return@forEachLine
                if (dayFmt.format(Date(t)) != dayKey) keep += line
            }
        }
        FileWriter(wellnessFile, false).use { w ->
            for (line in keep) { w.append(line); w.append('\n') }
            w.append('{')
                .append("\"t\":").append(rec.tDayMs.toString())
                .append(",\"score\":").append(rec.score.toString())
                .append(",\"label\":\"").append(rec.label).append("\"")
                .append(",\"exMin\":").append(rec.exerciseMin.toString())
                .append("}\n")
        }
    }

    fun loadRecentDailyWellness(days: Int, nowMs: Long = System.currentTimeMillis()): List<DailyWellness> {
        if (!wellnessFile.exists()) return emptyList()
        val cutoff = nowMs - days.toLong() * 24L * 60L * 60L * 1000L
        val out = ArrayList<DailyWellness>()
        wellnessFile.forEachLine { line ->
            val t = parseLongField(line, "\"t\":") ?: return@forEachLine
            if (t < cutoff) return@forEachLine
            val score = parseIntField(line, "\"score\":") ?: return@forEachLine
            val label = parseStringField(line, "\"label\":") ?: ""
            val exMin = parseIntField(line, "\"exMin\":") ?: 0
            out.add(DailyWellness(t, score, label, exMin))
        }
        return out.sortedBy { it.tDayMs }
    }

    // ---- Daily load (for ACWR) ----------------------------------------------
    //
    // Written to <filesDir>/history/load.jsonl, one line per calendar day:
    //   {"t":<dayMs>,"load":<minutes>}
    // Idempotent per day via upsertDailyLoad — same-day re-saves overwrite
    // the matching line (same mechanic as upsertDailyWellness).

    data class DailyLoad(
        val tDayMs: Long,
        val loadMinutes: Int,
    )

    private val loadFile = File(historyDir, "load.jsonl")

    fun upsertDailyLoad(rec: DailyLoad) {
        val dayKey = dayFmt.format(Date(rec.tDayMs))
        val keep = ArrayList<String>()
        if (loadFile.exists()) {
            loadFile.forEachLine { line ->
                val t = parseLongField(line, "\"t\":") ?: return@forEachLine
                if (dayFmt.format(Date(t)) != dayKey) keep += line
            }
        }
        FileWriter(loadFile, false).use { w ->
            for (line in keep) { w.append(line); w.append('\n') }
            w.append('{')
                .append("\"t\":").append(rec.tDayMs.toString())
                .append(",\"load\":").append(rec.loadMinutes.toString())
                .append("}\n")
        }
    }

    /**
     * Load today's row, if any. Used at ViewModel init to recover the
     * exercise-minute counter when the process restarts mid-day.
     */
    fun loadDailyLoadFor(epochMs: Long): DailyLoad? {
        if (!loadFile.exists()) return null
        val wantKey = dayFmt.format(Date(epochMs))
        var hit: DailyLoad? = null
        loadFile.forEachLine { line ->
            val t = parseLongField(line, "\"t\":") ?: return@forEachLine
            if (dayFmt.format(Date(t)) != wantKey) return@forEachLine
            val load = parseIntField(line, "\"load\":") ?: return@forEachLine
            hit = DailyLoad(t, load)
        }
        return hit
    }

    /** Distinct calendar days we've ever persisted a load row for. */
    fun countPersistedDailyLoadDays(): Int {
        if (!loadFile.exists()) return 0
        val seen = HashSet<String>()
        loadFile.forEachLine { line ->
            val t = parseLongField(line, "\"t\":") ?: return@forEachLine
            seen += dayFmt.format(Date(t))
        }
        return seen.size
    }

    /**
     * Dense oldest-to-newest list of the last [days] calendar days, filling
     * any missing day with load = 0. AcwrEstimator requires a dense series so
     * gaps are treated as rest days, not missing data.
     */
    fun loadDenseDailyLoad(days: Int, nowMs: Long = System.currentTimeMillis()): List<DailyLoad> {
        val byDay = HashMap<String, Int>()
        if (loadFile.exists()) {
            loadFile.forEachLine { line ->
                val t = parseLongField(line, "\"t\":") ?: return@forEachLine
                val load = parseIntField(line, "\"load\":") ?: return@forEachLine
                byDay[dayFmt.format(Date(t))] = load
            }
        }
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val out = ArrayList<DailyLoad>(days)
        repeat(days) {
            val key = dayFmt.format(cal.time)
            out += DailyLoad(cal.timeInMillis, byDay[key] ?: 0)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    private fun parseStringField(line: String, key: String): String? {
        val i = line.indexOf(key); if (i < 0) return null
        var j = i + key.length
        if (j >= line.length || line[j] != '"') return null
        j++
        val start = j
        while (j < line.length && line[j] != '"') j++
        return line.substring(start, j)
    }

    private fun parseLongField(line: String, key: String): Long? =
        substringField(line, key)?.toLongOrNull()
    private fun parseIntField(line: String, key: String): Int? =
        substringField(line, key)?.toIntOrNull()
    private fun substringField(line: String, key: String): String? {
        val i = line.indexOf(key); if (i < 0) return null
        var j = i + key.length; val start = j
        while (j < line.length) { val c = line[j]; if (c == ',' || c == '}') break; j++ }
        return line.substring(start, j)
    }

    private fun parseFloatField(line: String, key: String): Float? {
        val i = line.indexOf(key)
        if (i < 0) return null
        var j = i + key.length
        val start = j
        while (j < line.length) {
            val c = line[j]
            if (c == ',' || c == '}') break
            j++
        }
        return line.substring(start, j).toFloatOrNull()
    }

    companion object { private val F0 = FloatArray(0) }
}
