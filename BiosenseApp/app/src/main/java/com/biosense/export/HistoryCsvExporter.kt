package com.biosense.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.biosense.analytics.AcwrEstimator
import com.biosense.data.HistoryStore
import com.biosense.data.MedicalIdStore
import com.biosense.data.ProfileStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Downloadable "database" view of the full history as a single CSV. The on-disk
 * JSONL under `<filesDir>/history/` stays at full ~5 s resolution; this
 * exporter is the only place dedup lives, so the user can always re-export
 * with different tolerances later.
 *
 * Dedup heartbeat: walk the time-ordered snapshots and overwrite the previous
 * kept row whenever the new sample is within tolerance of it. A step count
 * change — any step delta — always starts a new row. A 10-minute gap also
 * forces a new row so long quiet periods still show time progression.
 */
object HistoryCsvExporter {

    private const val SUBDIR = "Biosense"
    private const val MAX_GAP_MS = 10L * 60_000L

    // Tolerances the user signed off on. Steps intentionally has none —
    // tracked separately below.
    private const val TOL_HR_BPM = 2f
    private const val TOL_HRV_MS = 5f
    private const val TOL_SPO2_PCT = 1f
    private const val TOL_GYRO_DPS = 1f
    private const val TOL_STRESS = 5

    /**
     * Returns the display filename saved to Downloads.
     *
     * [wellnessByDay] maps a calendar-day key (`yyyy-MM-dd` in the exporter's
     * local time zone) to that day's wellness score; rows whose day has no
     * entry get an empty `wellness_score` cell.
     */
    fun export(
        ctx: Context,
        rows: List<HistoryStore.Snapshot>,
        profile: ProfileStore.Profile,
        medId: MedicalIdStore.Info,
        wellnessByDay: Map<String, Int>,
        acwr: AcwrEstimator.Result?,
        fromLabel: String,
        toLabel: String,
        filename: String,
    ): String {
        val kept = dedupe(rows.sortedBy { it.tMs })
        val bytes = buildCsv(kept, profile, medId, wellnessByDay, acwr, fromLabel, toLabel).toByteArray()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused Downloads insert")
            resolver.openOutputStream(uri).use { os: OutputStream? ->
                requireNotNull(os).write(bytes)
            }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
            if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, filename)).use { it.write(bytes) }
        }
        return filename
    }

    // ---- Dedup --------------------------------------------------------------

    /**
     * Keep the newest sample of each quiet run. The invariant: for any two
     * adjacent kept rows A→B, either B's steps differ, B is >10 min after A,
     * or at least one metric is outside tolerance.
     */
    internal fun dedupe(rows: List<HistoryStore.Snapshot>): List<HistoryStore.Snapshot> {
        if (rows.isEmpty()) return rows
        val out = ArrayList<HistoryStore.Snapshot>(rows.size)
        for (row in rows) {
            val last = out.lastOrNull()
            if (last == null || !withinRange(last, row)) {
                out += row
            } else {
                out[out.lastIndex] = row
            }
        }
        return out
    }

    private fun withinRange(prev: HistoryStore.Snapshot, cur: HistoryStore.Snapshot): Boolean {
        // Steps: any change at all gets its own row — never collapse step deltas.
        if ((prev.steps ?: 0L) != (cur.steps ?: 0L)) return false
        if (cur.tMs - prev.tMs > MAX_GAP_MS) return false
        if (!floatClose(prev.hr, cur.hr, TOL_HR_BPM)) return false
        if (!floatClose(prev.hrv, cur.hrv, TOL_HRV_MS)) return false
        if (!floatClose(prev.spo2, cur.spo2, TOL_SPO2_PCT)) return false
        if (!floatClose(prev.gyroRms, cur.gyroRms, TOL_GYRO_DPS)) return false
        if (!intClose(prev.stress, cur.stress, TOL_STRESS)) return false
        return true
    }

    // Null handling: both-null is a match (quiet gap), one-null-one-not never
    // matches so a metric appearing or disappearing always gets its own row.
    private fun floatClose(a: Float?, b: Float?, tol: Float): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return abs(a - b) <= tol
    }

    private fun intClose(a: Int?, b: Int?, tol: Int): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return abs(a - b) <= tol
    }

    // ---- CSV formatting -----------------------------------------------------

    private fun buildCsv(
        rows: List<HistoryStore.Snapshot>,
        profile: ProfileStore.Profile,
        medId: MedicalIdStore.Info,
        wellnessByDay: Map<String, Int>,
        acwr: AcwrEstimator.Result?,
        fromLabel: String,
        toLabel: String,
    ): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val sb = StringBuilder(rows.size * 90 + 512)

        // ---- Metadata header ----------------------------------------------
        // Identity + export metadata as "key: value" lines, terminated by a
        // blank line separator before the CSV body. Pandas/R/Excel can skip
        // past this with skiprows=N (where N is the metadata line count +1
        // for the blank). Standard CSV tools break on this header, which is
        // the trade-off for not denormalizing identity onto every row.
        sb.append("# Biosense export\n")
        sb.append("wearer_id: ").append(profile.wearerIdShort ?: "").append('\n')
        sb.append("wearer_name: ").append(sanitizeMeta(profile.fullName.ifBlank { medId.fullName })).append('\n')
        if (profile.email.isNotBlank()) {
            sb.append("wearer_email: ").append(sanitizeMeta(profile.email)).append('\n')
        }
        if (profile.phone.isNotBlank()) {
            sb.append("wearer_phone: ").append(sanitizeMeta(profile.phone)).append('\n')
        }
        profile.ageYears?.let { sb.append("age: ").append(it).append('\n') }
        if (profile.sex != ProfileStore.Sex.UNSET) {
            sb.append("sex: ").append(profile.sex.name.lowercase()).append('\n')
        }
        profile.heightCm?.let { sb.append("height_cm: ").append("%.1f".format(it)).append('\n') }
        profile.weightKg?.let { sb.append("weight_kg: ").append("%.1f".format(it)).append('\n') }
        sb.append("from_date: ").append(fromLabel).append('\n')
        sb.append("to_date: ").append(toLabel).append('\n')
        // ACWR snapshot at export time (Hulin 2014 / Williams 2017 EWMA). It is
        // NOT computed for the from/to range, so it is dated explicitly.
        // Null ratio means fewer than 28 calendar days of load data — surface
        // acute + days so the researcher knows how close we are to publishing.
        if (acwr != null) {
            sb.append("acwr_as_of: ")
                .append(SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())).append('\n')
            if (acwr.ratio != null) {
                sb.append("acwr_ratio: ").append("%.2f".format(acwr.ratio)).append('\n')
                sb.append("acwr_acute_min: ").append("%.0f".format(acwr.acute)).append('\n')
                sb.append("acwr_chronic_min: ").append("%.0f".format(acwr.chronic)).append('\n')
                sb.append("acwr_band: ").append(acwr.band.name.lowercase()).append('\n')
            } else {
                sb.append("acwr_ratio: (building baseline — needs ")
                    .append(AcwrEstimator.MIN_DAYS_FOR_RATIO).append(" days)\n")
                sb.append("acwr_acute_min: ").append("%.0f".format(acwr.acute)).append('\n')
            }
            sb.append("acwr_days_of_data: ").append(acwr.daysOfData).append('\n')
        }
        sb.append("exported_at: ").append(iso.format(Date())).append('\n')
        sb.append("row_count: ").append(rows.size).append('\n')
        sb.append('\n')  // blank line separator between metadata and data

        // ---- Data body (per-sample vitals, no identity columns) -----------
        sb.append("timestamp_iso,epoch_ms,hr_bpm,hrv_rmssd_ms,spo2_pct,gyro_rms_dps,steps_today,stress_score,wellness_score\n")

        for (r in rows) {
            val dayKey = dayFmt.format(Date(r.tMs))
            val wellness = wellnessByDay[dayKey]?.toString() ?: ""
            sb.append(iso.format(Date(r.tMs))).append(',')
            sb.append(r.tMs).append(',')
            sb.append(r.hr?.let { "%.1f".format(it) } ?: "").append(',')
            sb.append(r.hrv?.let { "%.1f".format(it) } ?: "").append(',')
            sb.append(r.spo2?.let { "%.1f".format(it) } ?: "").append(',')
            sb.append(r.gyroRms?.let { "%.2f".format(it) } ?: "").append(',')
            sb.append(r.steps?.toString() ?: "").append(',')
            sb.append(r.stress?.toString() ?: "").append(',')
            sb.append(wellness).append('\n')
        }
        return sb.toString()
    }

    /** Strip newlines from a metadata value so it can't break the key:value shape. */
    private fun sanitizeMeta(s: String): String =
        s.replace('\n', ' ').replace('\r', ' ').trim()
}
