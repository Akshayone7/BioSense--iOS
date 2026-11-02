package com.biosense.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biosense.analytics.AcwrEstimator
import com.biosense.data.HistoryStore
import com.biosense.data.MedicationStore
import com.biosense.ui.components.ChartMarker
import com.biosense.ui.components.LineChart
import com.biosense.ui.theme.AccentHr
import com.biosense.ui.theme.AccentHrv
import com.biosense.ui.theme.AccentSpo2
import com.biosense.vm.MonitorViewModel

enum class Metric { WELLNESS, HR, SPO2, HRV, GYRO, STEPS, STRESS }
enum class Range { SESSION, DAY, WEEK, MONTH }

@Composable
fun MetricDetailScreen(
    metric: Metric,
    state: MonitorViewModel.UiState,
    vm: MonitorViewModel,
) {
    val cfg = metricConfig(metric, state, vm.strideMeters())
    var range by remember { mutableStateOf(Range.SESSION) }
    var persisted by remember { mutableStateOf<HistoryStore.DaySeries?>(null) }
    var loading by remember { mutableStateOf(false) }
    var medEvents by remember { mutableStateOf<List<MedicationStore.Event>>(emptyList()) }

    LaunchedEffect(range) {
        if (range == Range.SESSION) {
            persisted = null
            return@LaunchedEffect
        }
        loading = true
        persisted = when (range) {
            Range.DAY -> vm.loadHistoryToday()
            Range.WEEK -> vm.loadHistoryRecentDays(7)
            Range.MONTH -> vm.loadHistoryRecentDays(30)
            Range.SESSION -> null
        }
        loading = false
    }

    LaunchedEffect(metric, range) { medEvents = emptyList() }

    val displayed: FloatArray = when {
        // Wellness: Session view shows the in-memory intraday series (one
        // point per 5-min recompute tick); Day / Week / Month fall through
        // to the persisted daily series. DaySeries doesn't carry wellness.
        metric == Metric.WELLNESS && range == Range.SESSION -> state.wellnessIntraday
        metric == Metric.WELLNESS -> cfg.history
        range == Range.SESSION -> cfg.history
        else -> persisted?.let { pickMetric(it, metric) } ?: FloatArray(0)
    }
    val rangeLabel = when (range) {
        Range.SESSION -> "session"
        Range.DAY -> "today"
        Range.WEEK -> "last 7 days"
        Range.MONTH -> "last 30 days"
    }
    val xLabels = buildXLabels(range, persisted?.startMs ?: 0L, persisted?.endMs ?: 0L)
    val markers = buildMedMarkers(medEvents, persisted?.startMs ?: 0L, persisted?.endMs ?: 0L)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        HeaderCard(cfg)
        Spacer(Modifier.height(10.dp))
        RangeSelector(range) { range = it }
        Spacer(Modifier.height(10.dp))
        LineChart(
            title = "${cfg.title} — $rangeLabel",
            values = displayed,
            color = cfg.color,
            heightDp = 200,
            xLabels = xLabels,
            markers = markers,
        )
        if (markers.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MedMarkerLegend(medEvents.size)
        }
        Spacer(Modifier.height(14.dp))
        InsightsCard(cfg, displayed, range, loading)
        if (metric == Metric.WELLNESS) {
            Spacer(Modifier.height(14.dp))
            TrainingLoadCard(state.acwr)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TrainingLoadCard(acwr: AcwrEstimator.Result?) {
    val color = acwrBandColor(acwr?.band)
    Column {
        Text(
            "Training load",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(14.dp),
        ) {
            if (acwr == null) {
                Text(
                    "Collecting load data…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val valueText = acwr.ratio?.let { "%.2f".format(it) } ?: "—"
                Text(valueText, color = color, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .background(color.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        AcwrEstimator.bandLabel(acwr.band),
                        color = color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (acwr.ratio != null) {
                Text(
                    "7-day load %.0f min · 28-day baseline %.0f min".format(acwr.acute, acwr.chronic),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    acwrBandBlurb(acwr.band),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Building baseline · ${acwr.daysOfData} / ${AcwrEstimator.MIN_DAYS_FOR_RATIO} days",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "This week: %.0f min (7-day EWMA). The full ratio appears once 28 days of load data accrue.".format(acwr.acute),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "ACWR (Hulin 2014) · load = exercise minutes/day",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun acwrBandColor(band: AcwrEstimator.Band?): Color = when (band) {
    AcwrEstimator.Band.SWEET_SPOT -> Color(0xFF4ADE80)
    AcwrEstimator.Band.UNDERTRAINED, AcwrEstimator.Band.ELEVATED -> Color(0xFFF5B942)
    AcwrEstimator.Band.DANGER -> Color(0xFFEF4444)
    AcwrEstimator.Band.INSUFFICIENT_DATA, null -> Color(0xFF9AA6B8)
}

private fun acwrBandBlurb(band: AcwrEstimator.Band): String = when (band) {
    AcwrEstimator.Band.SWEET_SPOT -> "This week's load is in line with your month — adapted range."
    AcwrEstimator.Band.UNDERTRAINED -> "This week trails your 28-day baseline. Caution ramping back up."
    AcwrEstimator.Band.ELEVATED -> "Load spiking vs your baseline. Watch for sleep/HRV knock-on."
    AcwrEstimator.Band.DANGER -> "Load has spiked sharply — literature associates this with injury risk."
    AcwrEstimator.Band.INSUFFICIENT_DATA -> "Not enough history to compute the ratio."
}

private fun pickMetric(d: HistoryStore.DaySeries, m: Metric): FloatArray = when (m) {
    Metric.WELLNESS -> FloatArray(0)   // wellness is daily, not from DaySeries — use state.wellnessHistory
    Metric.HR -> d.hr
    Metric.SPO2 -> d.spo2
    Metric.HRV -> d.hrv
    Metric.GYRO -> d.gyroRms
    Metric.STEPS -> d.steps
    Metric.STRESS -> d.stress
}

/**
 * Build x-axis tick labels based on the range and the persisted data's time span.
 * Day view → 4 hh:mm marks across the day; Week/Month → 4 date marks. Empty for
 * session (chart is a live scroll, not aligned to wall-clock).
 */
private fun buildXLabels(range: Range, startMs: Long, endMs: Long): List<String> {
    if (range == Range.SESSION || startMs == 0L || endMs <= startMs) return emptyList()
    val ticks = 4
    val fmt = when (range) {
        Range.DAY -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
    }
    return (0 until ticks).map { i ->
        val t = startMs + ((endMs - startMs) * i.toLong() / (ticks - 1))
        fmt.format(java.util.Date(t))
    }
}

/**
 * Map dose "taken" events to normalized 0..1 fractions across [startMs, endMs].
 * Skipped/snoozed doses are dropped — the overlay is for "was the drug on
 * board?" reasoning, and only taken doses answer that.
 */
private fun buildMedMarkers(
    events: List<MedicationStore.Event>,
    startMs: Long,
    endMs: Long,
): List<ChartMarker> {
    if (events.isEmpty() || startMs == 0L || endMs <= startMs) return emptyList()
    val span = (endMs - startMs).toFloat()
    val amber = Color(0xFFF5B942)
    return events.mapNotNull { e ->
        if (e.action != MedicationStore.Action.TAKEN) return@mapNotNull null
        if (e.tMs < startMs || e.tMs > endMs) return@mapNotNull null
        ChartMarker(xFraction = (e.tMs - startMs) / span, color = amber)
    }
}

@Composable
private fun MedMarkerLegend(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Box(
            Modifier
                .width(10.dp)
                .height(2.dp)
                .background(Color(0xFFF5B942)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$count dose${if (count == 1) "" else "s"} taken in range",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class MetricUi(
    val title: String,
    val unit: String,
    val value: String,
    val subtitle: String,
    val color: Color,
    val history: FloatArray,
)

private fun metricConfig(m: Metric, s: MonitorViewModel.UiState, strideM: Float): MetricUi = when (m) {
    Metric.WELLNESS -> MetricUi(
        title = "Wellness",
        unit = "/100",
        value = s.wellness?.value?.toString() ?: "—",
        subtitle = if (s.wellness != null) "HRV + resting HR + sleep composite" else "collecting signals…",
        color = Color(0xFFF5B942),
        history = s.wellnessHistory,
    )
    Metric.HR -> MetricUi(
        title = "Heart Rate",
        unit = "bpm",
        value = s.hrBpm?.let { "%.0f".format(it) } ?: "—",
        subtitle = hrStatusText(s),
        color = AccentHr,
        history = s.hrHistory,
    )
    Metric.SPO2 -> MetricUi(
        title = "SpO₂",
        unit = "%",
        value = s.spo2Pct?.let { "%.0f".format(it) } ?: "—",
        subtitle = if (s.spo2Pct != null) "uncalibrated ratio" else "no contact",
        color = AccentSpo2,
        history = s.spo2History,
    )
    Metric.HRV -> MetricUi(
        title = "HRV (RMSSD)",
        unit = "ms",
        value = s.hrvRmssdMs?.let { "%.0f".format(it) } ?: "—",
        subtitle = if (s.hrvRmssdMs != null) "successive IBI diffs" else "no pulse",
        color = AccentHrv,
        history = s.hrvHistory,
    )
    Metric.GYRO -> MetricUi(
        title = "Gyro |ω| RMS",
        unit = "dps",
        value = "%.1f".format(s.gyroRmsDps),
        subtitle = "instantaneous magnitude",
        color = Color(0xFFC9A9FF),
        history = s.gyroRmsHistory,
    )
    Metric.STEPS -> MetricUi(
        title = "Steps",
        unit = "",
        value = s.stepsToday.toString(),
        subtitle = "≈ %.2f km · wrist accel peak-count".format(s.stepsToday * strideM / 1000f),
        color = Color(0xFF6EE7A8),
        history = s.stepsHistory,
    )
    Metric.STRESS -> MetricUi(
        title = "Stress",
        unit = "/100",
        value = s.stressScore?.toString() ?: "—",
        subtitle = if (s.stressScore != null) "${s.stressLabel} · HRV + HR vs baseline"
                   else "waiting for still + clean pulse",
        color = Color(0xFFF7A072),
        history = s.stressHistory,
    )
}

@Composable
private fun HeaderCard(cfg: MetricUi) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(18.dp),
    ) {
        Text(
            cfg.title.uppercase(),
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(cfg.value, color = cfg.color, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text(cfg.unit, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(cfg.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun RangeSelector(current: Range, onSelect: (Range) -> Unit) {
    val ranges = listOf(
        "Session" to Range.SESSION,
        "Day" to Range.DAY,
        "Week" to Range.WEEK,
        "Month" to Range.MONTH,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for ((label, r) in ranges) {
            val isSelected = r == current
            Box(
                Modifier
                    .padding(2.dp)
                    .background(
                        if (isSelected) Color(0x33F5B942) else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(r) }
                    .padding(vertical = 8.dp, horizontal = 16.dp),
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) Color(0xFFF5B942) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InsightsCard(cfg: MetricUi, series: FloatArray, range: Range, loading: Boolean) {
    Column {
        Text(
            "Insights",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(14.dp),
        ) {
            val body = when {
                loading -> "Loading…"
                series.isEmpty() && range != Range.SESSION ->
                    "No history stored yet for this range. Keep the app running to build history."
                series.size < 3 -> "Gathering samples…"
                else -> summary(series, cfg.unit)
            }
            Text(body, fontSize = 13.sp)
            if (range == Range.SESSION) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap Day for today's persisted history. Week/Month aggregate multiple days.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun summary(h: FloatArray, unit: String): String {
    var sum = 0.0
    var lo = h[0]; var hi = h[0]
    for (v in h) { sum += v; if (v < lo) lo = v; if (v > hi) hi = v }
    val avg = sum / h.size
    return "avg %.1f %s · range %.1f–%.1f · %d samples".format(avg, unit, lo, hi, h.size)
}
