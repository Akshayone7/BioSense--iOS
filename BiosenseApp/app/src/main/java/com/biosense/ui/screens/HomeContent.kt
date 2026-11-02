package com.biosense.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biosense.analytics.ExerciseModeClassifier
import com.biosense.ble.BleManager
import com.biosense.ui.components.MetricCard
import com.biosense.ui.theme.AccentHr
import com.biosense.ui.theme.AccentHrv
import com.biosense.ui.theme.AccentSpo2
import com.biosense.vm.MonitorViewModel

@Composable
fun HomeContent(
    state: MonitorViewModel.UiState,
    vm: MonitorViewModel,
    onRequestConnect: () -> Unit,
    onOpenMetric: (Metric) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        WellnessHero(state = state, onClick = { onOpenMetric(Metric.WELLNESS) })
        Spacer(Modifier.height(14.dp))
        StatusRow(state = state)
        Spacer(Modifier.height(10.dp))
        ControlRow(state, onRequestConnect, vm)
        Spacer(Modifier.height(14.dp))

        MetricsGrid(state, vm.strideMeters(), onOpenMetric)

        Spacer(Modifier.height(16.dp))
        SleepCard(state, vm)
        Spacer(Modifier.height(12.dp))
        RecordRow(state, vm)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WellnessHero(state: MonitorViewModel.UiState, onClick: () -> Unit) {
    val score = state.wellness
    val bandColor = when {
        score == null -> Color(0xFF6B7280)
        score.value >= 85 -> Color(0xFF4ADE80)   // Primed — green
        score.value >= 70 -> Color(0xFF6EE7A8)   // Ready — teal
        score.value >= 50 -> Color(0xFFF5B942)   // Steady — amber
        score.value >= 30 -> Color(0xFFF7A072)   // Taxed — orange
        else -> Color(0xFFEF4444)                // Depleted — red
    }
    val modeLabel = when (state.mode) {
        ExerciseModeClassifier.Mode.EXERCISE -> "Exercise"
        ExerciseModeClassifier.Mode.REST -> "Rest"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "WELLNESS",
                fontSize = 11.sp,
                letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .background(bandColor.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    score?.label ?: "Collecting",
                    color = bandColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                score?.value?.toString() ?: "—",
                color = bandColor,
                fontSize = 44.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "/100",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (score == null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Keep wearing the device — a score appears after enough HRV and HR data accrue.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Mode: $modeLabel · Exercise ${state.exerciseMinutesToday} min today",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun SleepCard(state: MonitorViewModel.UiState, vm: MonitorViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bedtime, contentDescription = null, tint = Color(0xFF9EC5FF))
            Spacer(Modifier.width(8.dp))
            Text("Sleep tracking", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (state.sleepActive) {
                OutlinedButton(onClick = { vm.stopSleepSession() }) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp)); Text("Stop")
                }
            } else {
                Button(
                    onClick = { vm.startSleepSession() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B4A8A)),
                ) {
                    Icon(Icons.Filled.Bedtime, contentDescription = null)
                    Spacer(Modifier.width(4.dp)); Text("Start")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (state.sleepActive) {
            val mins = ((System.currentTimeMillis() - state.sleepStartedMs) / 60000L).toInt().coerceAtLeast(0)
            val mode = if (state.sleepIsAuto) "Auto-recording" else "Manual recording"
            Text(
                "$mode · ${formatDuration(mins)} · sleep ${state.sleepSleepMin}m / wake ${state.sleepWakeMin}m",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Watching for sleep · quiet ${state.sleepQuietRunMin}/20 min" +
                    (state.restingHrBpm?.let { " · rest HR ${it.toInt()} bpm" } ?: ""),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val last = state.lastSleepSummary
            if (last != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Last: sleep ${last.sleepMin}m · wake ${last.wakeMin}m · " +
                        "${last.efficiencyPct}% efficiency · ${last.wakeEpisodes} wake episodes",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (last.lightMin + last.deepMin + last.remMin > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Stages: light ${last.lightMin}m · deep ${last.deepMin}m · REM ${last.remMin}m",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Auto-starts after 20 min of quiet wrist motion. Tap Start to force it earlier.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatDuration(mins: Int): String {
    val h = mins / 60; val m = mins % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

@Composable
private fun StatusRow(state: MonitorViewModel.UiState) {
    val (dotColor, label) = when (state.connState) {
        BleManager.ConnectionState.CONNECTED -> Color(0xFF4ADE80) to "Connected"
        BleManager.ConnectionState.SCANNING, BleManager.ConnectionState.CONNECTING -> Color(0xFFF5B942) to "Connecting"
        BleManager.ConnectionState.RECONNECTING -> Color(0xFFF5B942) to "Reconnecting"
        BleManager.ConnectionState.DISCONNECTED -> Color(0xFF6B7280) to "Disconnected"
        BleManager.ConnectionState.ERROR -> Color(0xFFEF4444) to "Error"
        BleManager.ConnectionState.PERMISSION_REQUIRED -> Color(0xFFEF4444) to "Permissions"
        BleManager.ConnectionState.BLUETOOTH_OFF -> Color(0xFFEF4444) to "Bluetooth off"
        BleManager.ConnectionState.IDLE -> Color(0xFF6B7280) to "Idle"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Text(
            state.statusMessage,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ControlRow(
    state: MonitorViewModel.UiState,
    onRequestConnect: () -> Unit,
    vm: MonitorViewModel,
) {
    val connected = state.connState == BleManager.ConnectionState.CONNECTED
    val busy = state.connState in setOf(
        BleManager.ConnectionState.SCANNING,
        BleManager.ConnectionState.CONNECTING,
        BleManager.ConnectionState.RECONNECTING
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { if (connected) vm.disconnect() else onRequestConnect() },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (connected) Color(0xFF3A1A1A) else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = if (connected) Icons.Filled.BluetoothDisabled else Icons.Filled.Bluetooth,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(if (connected) "Disconnect" else if (busy) "Working…" else "Connect")
        }
    }
}

/**
 * User-facing reason a heart-rate reading is missing, mapped from the
 * estimator's internal reject code. Empty once a reading exists. Shared by the
 * home card and the detail screen so raw debug codes never reach the UI.
 */
internal fun hrStatusText(s: MonitorViewModel.UiState): String = when {
    s.hrBpm != null -> ""
    !s.ppgContact -> "no contact — wear snug on wrist"
    s.ppgReason.startsWith("motion") -> "too much motion — hold still"
    s.ppgReason.startsWith("no_pulse") -> "no pulse — reposition sensor"
    s.ppgReason.startsWith("irregular_ibi") -> "unstable — hold still"
    s.ppgReason.startsWith("saturation") -> "pressed too hard"
    else -> "acquiring…"
}

@Composable
private fun MetricsGrid(s: MonitorViewModel.UiState, strideM: Float, onOpenMetric: (Metric) -> Unit) {
    val hr = s.hrBpm?.let { "%.0f".format(it) } ?: "—"
    val hrv = s.hrvRmssdMs?.let { "%.0f".format(it) } ?: "—"
    val spo2 = s.spo2Pct?.let { "%.0f".format(it) } ?: "—"

    val hrSubtitle = hrStatusText(s)
    val spo2Subtitle = when {
        s.spo2Pct != null -> if (s.wristStill) "uncalibrated · held still" else "uncalibrated · last still reading"
        !s.wristStill -> "hold wrist still for spot-check"
        s.hrBpm != null -> "wrist too weak — try fingertip"
        else -> hrSubtitle
    }
    val hrvSubtitle = if (s.hrvRmssdMs != null) "successive IBI diffs" else hrSubtitle

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                "Heart Rate", hr, "bpm",
                hrSubtitle,
                AccentHr, Modifier.weight(1f),
                onClick = { onOpenMetric(Metric.HR) },
            )
            MetricCard(
                "SpO₂", spo2, "%",
                spo2Subtitle,
                AccentSpo2, Modifier.weight(1f),
                onClick = { onOpenMetric(Metric.SPO2) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                "HRV (RMSSD)", hrv, "ms",
                hrvSubtitle,
                AccentHrv, Modifier.weight(1f),
                onClick = { onOpenMetric(Metric.HRV) },
            )
            val stepsKm = s.stepsToday * strideM / 1000f
            MetricCard(
                "Steps", s.stepsToday.toString(), "",
                "≈ %.2f km today".format(stepsKm),
                Color(0xFF6EE7A8), Modifier.weight(1f),
                onClick = { onOpenMetric(Metric.STEPS) },
            )
        }
        // Stress card intentionally hidden from the UI. The score is still
        // computed and stored; restore the card here to show it again.
    }
}

@Composable
private fun RecordRow(state: MonitorViewModel.UiState, vm: MonitorViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { vm.toggleRecording() }) {
            Icon(
                imageVector = if (state.recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = if (state.recording) MaterialTheme.colorScheme.onSurface else Color(0xFFEF4444),
            )
            Spacer(Modifier.width(6.dp))
            Text(if (state.recording) "Stop" else "Record")
        }
        OutlinedButton(onClick = { vm.exportRecording() }, enabled = state.recordedCount > 0) {
            Icon(Icons.Filled.FileDownload, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Save session")
        }
        Spacer(Modifier.weight(1f))
        if (state.recording || state.recordedCount > 0) {
            Text(
                "${state.recordedCount} samples",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}
