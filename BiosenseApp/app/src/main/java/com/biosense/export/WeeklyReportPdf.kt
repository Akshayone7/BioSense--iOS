package com.biosense.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Color as GColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.biosense.analytics.AcwrEstimator
import com.biosense.data.HistoryStore
import com.biosense.data.ProfileStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Three-page vitals-focused weekly wellness report. Every page carries the
 * wearer identity in its top-right corner so the file is attributable at a
 * glance.
 *
 *   Page 1 — Wellness summary + 7-day score sparkline, best/worst day.
 *   Page 2 — Five small-multiple vitals charts (HR / HRV / SpO₂ / Sleep / Exercise).
 *   Page 3 — Per-night sleep table + ACWR training-load ratio.
 *
 * Charts are drawn with android.graphics.pdf.PdfDocument's Canvas — no new
 * dependency. Medication-adherence and medical-ID sections of the previous
 * PDF are deliberately out; those are app-only now.
 */
object WeeklyReportPdf {

    private const val SUBDIR = "Biosense"
    private const val PAGE_W = 595   // A4 at 72 dpi (points)
    private const val PAGE_H = 842
    private const val MARGIN = 40f

    fun export(
        ctx: Context,
        profile: ProfileStore.Profile,
        series: HistoryStore.DaySeries,
        wellness: List<HistoryStore.DailyWellness>,
        sleeps: List<HistoryStore.SleepRecord>,
        acwr: AcwrEstimator.Result?,
        filename: String,
    ): String {
        val doc = PdfDocument()
        val wearerId = profile.wearerIdShort ?: "—"
        val wearerName = profile.fullName.ifBlank { "Unnamed wearer" }
        val profileSummary = profileLine(profile)

        val today = System.currentTimeMillis()
        val start = today - 7L * 24 * 60 * 60 * 1000
        val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val dateRange = "${dateFmt.format(Date(start))} – ${dateFmt.format(Date(today))}"

        // --- Page 1: Wellness summary ----------------------------------------
        val p1 = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        drawHeader(p1, wearerId, wearerName, pageNum = 1)
        var y = MARGIN + 40f

        p1.canvas.drawText("Weekly Wellness Report", MARGIN, y, titlePaint); y += 20f
        p1.canvas.drawText(dateRange, MARGIN, y, smallPaint); y += 14f
        p1.canvas.drawText(profileSummary, MARGIN, y, smallPaint); y += 14f
        val contact = listOfNotNull(
            profile.email.takeIf { it.isNotBlank() },
            profile.phone.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (contact.isNotEmpty()) {
            p1.canvas.drawText(contact, MARGIN, y, smallPaint); y += 14f
        }
        y += 10f

        // Big score
        val latest = wellness.maxByOrNull { it.tDayMs }
        if (latest != null) {
            p1.canvas.drawText("Wellness score", MARGIN, y, h2Paint); y += 18f
            p1.canvas.drawText("${latest.score}", MARGIN, y + 36f, heroPaint)
            p1.canvas.drawText(" /100  (${latest.label})", MARGIN + 90f, y + 36f, subheroPaint)
            y += 60f
        } else {
            p1.canvas.drawText("Wellness score: collecting (not enough data yet)", MARGIN, y, bodyPaint); y += 20f
        }

        // 7-day sparkline
        if (wellness.isNotEmpty()) {
            p1.canvas.drawText("7-day trend", MARGIN, y, h2Paint); y += 10f
            val rect = RectF(MARGIN, y, PAGE_W - MARGIN, y + 110f)
            drawLineChart(p1, rect, wellness.map { it.score.toFloat() }, GColor.rgb(0xF5, 0xB9, 0x42))
            y += 120f
        }

        // Best / worst day
        if (wellness.isNotEmpty()) {
            val best = wellness.maxByOrNull { it.score }
            val worst = wellness.minByOrNull { it.score }
            val stamp = SimpleDateFormat("MMM d", Locale.US)
            best?.let {
                p1.canvas.drawText(
                    "Best day: ${stamp.format(Date(it.tDayMs))} — ${it.score} (${it.label})",
                    MARGIN, y, bodyPaint,
                ); y += 14f
            }
            worst?.let {
                p1.canvas.drawText(
                    "Worst day: ${stamp.format(Date(it.tDayMs))} — ${it.score} (${it.label})",
                    MARGIN, y, bodyPaint,
                ); y += 14f
            }
            y += 10f
        }

        // Exercise summary
        val totalEx = wellness.sumOf { it.exerciseMin }
        val days = wellness.size.coerceAtLeast(1)
        p1.canvas.drawText(
            "Exercise: $totalEx min total · ${totalEx / days} min/day avg across $days day(s)",
            MARGIN, y, bodyPaint,
        )
        drawFooter(p1)
        doc.finishPage(p1)

        // --- Page 2: Vitals charts -------------------------------------------
        val p2 = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 2).create())
        drawHeader(p2, wearerId, wearerName, pageNum = 2)
        var y2 = MARGIN + 40f
        p2.canvas.drawText("Vitals · last 7 days", MARGIN, y2, titlePaint); y2 += 24f

        val chartW = (PAGE_W - 2f * MARGIN - 20f) / 2f
        val chartH = 100f

        // Row 1: HR, HRV
        drawSmallChart(p2, RectF(MARGIN, y2, MARGIN + chartW, y2 + chartH + 24f),
            "HR (bpm)", series.hr.toList(), GColor.rgb(0xFF, 0x6B, 0x7A), isBar = false)
        drawSmallChart(p2, RectF(MARGIN + chartW + 20f, y2, PAGE_W - MARGIN, y2 + chartH + 24f),
            "HRV RMSSD (ms)", series.hrv.toList(), GColor.rgb(0x6E, 0xE7, 0xA8), isBar = false)
        y2 += chartH + 40f

        // Row 2: SpO2, Sleep (bar)
        drawSmallChart(p2, RectF(MARGIN, y2, MARGIN + chartW, y2 + chartH + 24f),
            "SpO₂ (%)", series.spo2.toList(), GColor.rgb(0x4F, 0xC3, 0xF7), isBar = false)
        val sleepHours = sleeps.sortedBy { it.startMs }.takeLast(7).map { it.sleepMin / 60f }
        drawSmallChart(p2, RectF(MARGIN + chartW + 20f, y2, PAGE_W - MARGIN, y2 + chartH + 24f),
            "Sleep (h/night)", sleepHours, GColor.rgb(0x9E, 0xC5, 0xFF), isBar = true)
        y2 += chartH + 40f

        // Row 3: Exercise minutes
        val exMinutes = wellness.sortedBy { it.tDayMs }.map { it.exerciseMin.toFloat() }
        drawSmallChart(p2, RectF(MARGIN, y2, MARGIN + chartW, y2 + chartH + 24f),
            "Exercise (min/day)", exMinutes, GColor.rgb(0xF5, 0xB9, 0x42), isBar = true)
        drawFooter(p2)
        doc.finishPage(p2)

        // --- Page 3: Sleep details + ACWR ------------------------------------
        val p3 = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 3).create())
        drawHeader(p3, wearerId, wearerName, pageNum = 3)
        var y3 = MARGIN + 40f
        p3.canvas.drawText("Sleep & recovery", MARGIN, y3, titlePaint); y3 += 24f

        if (sleeps.isEmpty()) {
            p3.canvas.drawText("No sleep sessions recorded this week.", MARGIN, y3, bodyPaint); y3 += 18f
        } else {
            // Header row
            p3.canvas.drawText("Date", MARGIN, y3, h2Paint)
            p3.canvas.drawText("Asleep", MARGIN + 110f, y3, h2Paint)
            p3.canvas.drawText("Efficiency", MARGIN + 180f, y3, h2Paint)
            p3.canvas.drawText("Wake eps", MARGIN + 280f, y3, h2Paint)
            p3.canvas.drawText("Avg HR", MARGIN + 370f, y3, h2Paint)
            y3 += 16f
            p3.canvas.drawLine(MARGIN, y3, PAGE_W - MARGIN, y3, dividerPaint); y3 += 8f

            val rowFmt = SimpleDateFormat("EEE MMM d", Locale.US)
            val recentNights = sleeps.sortedBy { it.startMs }.takeLast(7)
            for (rec in recentNights) {
                p3.canvas.drawText(rowFmt.format(Date(rec.startMs)), MARGIN, y3, bodyPaint)
                p3.canvas.drawText("${rec.sleepMin / 60}h ${rec.sleepMin % 60}m", MARGIN + 110f, y3, bodyPaint)
                p3.canvas.drawText("${rec.efficiencyPct}%", MARGIN + 180f, y3, bodyPaint)
                p3.canvas.drawText("${rec.wakeEpisodes}", MARGIN + 280f, y3, bodyPaint)
                p3.canvas.drawText(rec.avgHrBpm?.let { "%.0f bpm".format(it) } ?: "—", MARGIN + 370f, y3, bodyPaint)
                y3 += 14f
            }
        }
        y3 += 20f

        // Training load — real ACWR (Hulin 2014) when we have ≥ 28 days; before
        // that, surface the acute number alone with a "building baseline" line.
        p3.canvas.drawText("Training load (ACWR)", MARGIN, y3, h2Paint); y3 += 18f
        if (acwr == null) {
            p3.canvas.drawText("No load data recorded yet.", MARGIN, y3, bodyPaint); y3 += 14f
        } else if (acwr.ratio == null) {
            p3.canvas.drawText(
                "Building baseline: ${acwr.daysOfData} / ${AcwrEstimator.MIN_DAYS_FOR_RATIO} days of load data.",
                MARGIN, y3, bodyPaint,
            ); y3 += 14f
            p3.canvas.drawText(
                "Current 7-day load: %.0f min. Full ratio appears once 28 days accrue.".format(acwr.acute),
                MARGIN, y3, bodyPaint,
            ); y3 += 14f
        } else {
            val bandLabel = AcwrEstimator.bandLabel(acwr.band)
            p3.canvas.drawText("Ratio: %.2f  (%s)".format(acwr.ratio, bandLabel), MARGIN, y3, bodyPaint); y3 += 14f
            p3.canvas.drawText(
                "Acute (7-day EWMA): %.0f min · Chronic (28-day EWMA): %.0f min".format(acwr.acute, acwr.chronic),
                MARGIN, y3, bodyPaint,
            ); y3 += 14f
            val reading = when (acwr.band) {
                AcwrEstimator.Band.SWEET_SPOT -> "This week's load matches your adapted range."
                AcwrEstimator.Band.UNDERTRAINED -> "This week trails your 28-day baseline — ramp back up cautiously."
                AcwrEstimator.Band.ELEVATED -> "Load is spiking above baseline — watch for sleep/HRV knock-on."
                AcwrEstimator.Band.DANGER -> "Load has spiked sharply — literature associates ACWR > 1.5 with elevated injury risk."
                AcwrEstimator.Band.INSUFFICIENT_DATA -> ""
            }
            if (reading.isNotEmpty()) {
                p3.canvas.drawText(reading, MARGIN, y3, bodyPaint); y3 += 14f
            }
        }
        y3 += 6f
        p3.canvas.drawText(
            "Hulin et al. 2014 (Br J Sports Med); EWMA weighting per Williams 2017.",
            MARGIN, y3, smallPaint,
        )

        drawFooter(p3)
        doc.finishPage(p3)

        val bytes = java.io.ByteArrayOutputStream().use { baos ->
            doc.writeTo(baos); doc.close(); baos.toByteArray()
        }
        writeToDownloads(ctx, filename, bytes)
        return filename
    }

    // ---- Drawing helpers ----------------------------------------------------

    private fun drawHeader(page: PdfDocument.Page, wearerId: String, wearerName: String, pageNum: Int) {
        val label = "$wearerId · $wearerName"
        val width = headerPaint.measureText(label)
        page.canvas.drawText(label, PAGE_W - MARGIN - width, MARGIN - 10f, headerPaint)
        page.canvas.drawText("Page $pageNum of 3", MARGIN, MARGIN - 10f, headerPaint)
        page.canvas.drawLine(MARGIN, MARGIN, PAGE_W - MARGIN, MARGIN, dividerPaint)
    }

    private fun drawFooter(page: PdfDocument.Page) {
        val text = "Generated by this app — not a medical device."
        val width = smallPaint.measureText(text)
        page.canvas.drawText(text, (PAGE_W - width) / 2f, PAGE_H - MARGIN + 20f, smallPaint)
    }

    private fun drawSmallChart(
        page: PdfDocument.Page,
        rect: RectF,
        label: String,
        data: List<Float>,
        color: Int,
        isBar: Boolean,
    ) {
        // Label + min/max/avg above chart
        page.canvas.drawText(label, rect.left, rect.top, h2Paint)
        if (data.isEmpty()) {
            page.canvas.drawText("no data", rect.left, rect.top + 14f, smallPaint)
            return
        }
        val lo = data.min()
        val hi = data.max()
        val avg = data.average()
        page.canvas.drawText(
            "avg %.1f · range %.1f–%.1f".format(avg, lo, hi),
            rect.left, rect.top + 14f, smallPaint,
        )
        val chartTop = rect.top + 20f
        val chartBottom = rect.bottom - 4f
        if (isBar) drawBars(page, rect.left, chartTop, rect.right, chartBottom, data, color)
        else drawLineChart(page, RectF(rect.left, chartTop, rect.right, chartBottom), data, color)
    }

    private fun drawLineChart(page: PdfDocument.Page, rect: RectF, data: List<Float>, color: Int) {
        if (data.isEmpty()) return
        val w = rect.width()
        val h = rect.height()
        var lo = data.min(); var hi = data.max()
        if (hi - lo < 1e-3f) { hi = lo + 1f }
        val range = hi - lo

        // Axis line
        page.canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, dividerPaint)

        val stepX = w / (data.size - 1).coerceAtLeast(1)
        val path = Path()
        for (i in data.indices) {
            val x = rect.left + i * stepX
            val y = rect.bottom - ((data[i] - lo) / range) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val linePaint = Paint().apply {
            this.color = color
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        page.canvas.drawPath(path, linePaint)
    }

    private fun drawBars(
        page: PdfDocument.Page,
        left: Float, top: Float, right: Float, bottom: Float,
        data: List<Float>, color: Int,
    ) {
        if (data.isEmpty()) return
        val w = right - left
        val h = bottom - top
        var hi = data.max()
        if (hi < 1e-3f) hi = 1f

        page.canvas.drawLine(left, bottom, right, bottom, dividerPaint)

        val slot = w / data.size
        val gap = slot * 0.25f
        val barPaint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        for (i in data.indices) {
            val x1 = left + i * slot + gap / 2f
            val x2 = x1 + slot - gap
            val barH = (data[i] / hi) * h
            page.canvas.drawRect(x1, bottom - barH, x2, bottom, barPaint)
        }
    }

    private fun profileLine(p: ProfileStore.Profile): String {
        val bits = buildList {
            p.ageYears?.let { add("${it} yr") }
            if (p.sex != ProfileStore.Sex.UNSET) add(p.sex.name.lowercase().replaceFirstChar(Char::titlecase))
            p.heightCm?.let { add("${it.toInt()} cm") }
            p.weightKg?.let { add("%.1f kg".format(it)) }
        }
        return if (bits.isEmpty()) "" else bits.joinToString(" · ")
    }

    // ---- Paints -------------------------------------------------------------

    private val titlePaint = Paint().apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 20f; color = 0xFF000000.toInt(); isAntiAlias = true
    }
    private val heroPaint = Paint().apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 44f; color = 0xFFF5B942.toInt(); isAntiAlias = true
    }
    private val subheroPaint = Paint().apply {
        textSize = 14f; color = 0xFF666666.toInt(); isAntiAlias = true
    }
    private val h2Paint = Paint().apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 12f; color = 0xFF000000.toInt(); isAntiAlias = true
    }
    private val bodyPaint = Paint().apply {
        textSize = 11f; color = 0xFF222222.toInt(); isAntiAlias = true
    }
    private val smallPaint = Paint().apply {
        textSize = 9f; color = 0xFF666666.toInt(); isAntiAlias = true
    }
    private val headerPaint = Paint().apply {
        textSize = 9f; color = 0xFF888888.toInt(); isAntiAlias = true
    }
    private val dividerPaint = Paint().apply {
        color = 0xFFCCCCCC.toInt(); strokeWidth = 1f
    }

    // ---- File write (unchanged pattern) -------------------------------------

    private fun writeToDownloads(ctx: Context, filename: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused Downloads insert")
            resolver.openOutputStream(uri).use { requireNotNull(it).write(bytes) }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
            if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, filename)).use { it.write(bytes) }
        }
    }
}
