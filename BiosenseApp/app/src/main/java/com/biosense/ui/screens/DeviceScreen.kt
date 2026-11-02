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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.biosense.ble.BleManager
import com.biosense.health.BatteryCalc
import com.biosense.ota.DfuInstaller
import com.biosense.ui.components.LineChart
import com.biosense.vm.MonitorViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

@Composable
fun DeviceScreen(
    state: MonitorViewModel.UiState,
    vm: MonitorViewModel,
    onRequestConnect: () -> Unit,
) {
    val connected = state.connState == BleManager.ConnectionState.CONNECTED

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        DeviceCard(state = state, connected = connected)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { if (connected) vm.disconnect() else onRequestConnect() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connected) Color(0xFF3A1A1A) else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = if (connected) Icons.Filled.BluetoothDisabled else Icons.Filled.BluetoothConnected,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (connected) "Disconnect" else "Connect")
            }
        }
        Spacer(Modifier.height(20.dp))

        SectionTitle("Connection")
        InfoRow("Status", state.statusMessage)
        InfoRow("Device name", state.deviceName ?: "—")
        InfoRow("Packets received", state.packetCount.toString())
        InfoRow("Auto-reconnect", "on")

        Spacer(Modifier.height(20.dp))
        SectionTitle("Battery")
        InfoRow(
            "Pack",
            "${BatteryCalc.CAPACITY_MAH} mAh · ${BatteryCalc.NOMINAL_V} V · ${BatteryCalc.ENERGY_WH} Wh",
        )
        val pct = state.batteryPct
        if (pct != null) {
            val runtimeMin = BatteryCalc.estimatedRuntimeMin(pct)
            InfoRow("Charge", "$pct%")
            InfoRow(
                "Est. runtime",
                "~${runtimeMin / 60} h ${runtimeMin % 60} m  @ ${BatteryCalc.AVG_STREAM_CURRENT_MA.toInt()} mA",
            )
        } else {
            InfoRow("Charge", "—")
        }
        val cycles = state.batteryCycles
        val accum = state.batteryChargeAccumPct
        if (cycles != null) {
            val soh = BatteryCalc.sohPct(cycles)
            val fullMah = BatteryCalc.fullChargeMah(cycles)
            val cycleLabel = if (accum != null && accum > 0) "$cycles + $accum%" else cycles.toString()
            InfoRow("Cycle count", cycleLabel)
            InfoRow("Health (SoH)", "$soh%  (~$fullMah mAh)")
        } else {
            InfoRow("Cycle count", "—")
            InfoRow("Health (SoH)", "—")
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Firmware")
        OtaSection(vm = vm, deviceMac = vm.pairedDeviceMac())

        Spacer(Modifier.height(20.dp))
        RawSignalsSection(state)

        Spacer(Modifier.height(20.dp))
        SectionTitle("Actions")
        ActionRow(
            label = "Forget this device",
            trailingIcon = Icons.Filled.Delete,
            danger = true,
            onClick = { vm.forgetDevice() },
        )
        ActionRow(
            label = "Toggle auto-reconnect",
            trailingIcon = Icons.Filled.ChevronRight,
            onClick = { vm.setAutoReconnect(false) },
        )
    }
}

@Composable
private fun DeviceCard(state: MonitorViewModel.UiState, connected: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(Color(0xFF1E1E1E), CircleShape)
                .border(2.dp, Color(0xFFF5B942), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Watch,
                contentDescription = null,
                tint = Color(0xFFF5B942),
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                state.deviceName ?: "Biosense wearable",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (connected) Color(0xFF4ADE80) else Color(0xFF6B7280),
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (connected) "Connected · streaming" else "Not connected",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun OtaSection(vm: MonitorViewModel, deviceMac: String?) {
    val ctx = LocalContext.current
    var picked by remember { mutableStateOf<android.net.Uri?>(null) }
    var status by remember { mutableStateOf("Pick a firmware .zip to update the wearable.") }
    var progress by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            picked = uri
            status = "Ready: ${uri.lastPathSegment ?: "selected package"}"
            progress = 0
        }
    }

    LaunchedEffect(running, picked) {
        val uri = picked
        val mac = deviceMac
        if (running && uri != null && mac != null) {
            DfuInstaller.install(ctx, mac, uri).onEach { ev ->
                when (ev) {
                    is DfuInstaller.Event.Connecting -> status = "Connecting to wearable…"
                    is DfuInstaller.Event.Starting -> status = "Sending firmware…"
                    is DfuInstaller.Event.Validating -> status = "Validating…"
                    is DfuInstaller.Event.Progress -> {
                        progress = ev.percent
                        status = "Uploading ${ev.percent}% · ${"%.1f".format(ev.speedBps / 1024f)} KB/s"
                    }
                    is DfuInstaller.Event.Completed -> {
                        status = "Firmware installed. Wearable is rebooting."
                        progress = 100
                        running = false
                    }
                    is DfuInstaller.Event.Aborted -> {
                        status = "Update aborted."
                        running = false
                    }
                    is DfuInstaller.Event.Error -> {
                        status = "Update failed: ${ev.message ?: "error ${ev.error}"}"
                        running = false
                    }
                }
            }.collect { }
        }
    }

    Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (running || progress in 1..99) {
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            modifier = Modifier.weight(1f),
            enabled = !running,
        ) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Choose .zip")
        }
        Button(
            onClick = { running = true },
            enabled = !running && picked != null && deviceMac != null,
            modifier = Modifier.weight(1f),
        ) { Text(if (running) "Updating…" else "Install") }
    }
    if (deviceMac == null) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Pair the wearable first — DFU needs the MAC.",
            fontSize = 11.sp,
            color = Color(0xFFF5B942),
        )
    }
}

/**
 * Developer view of the live IMU channels. Collapsed by default so the Device
 * tab stays scannable; a typical user doesn't need |a| or |ω| traces, but
 * they're invaluable for sensor-contact debugging and motion-pattern tuning.
 */
@Composable
private fun RawSignalsSection(state: MonitorViewModel.UiState) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Raw signals",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            "gyro ${"%.1f".format(state.gyroRmsDps)} dps",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(horizontal = 4.dp))
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Hide raw signals" else "Show raw signals",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        Spacer(Modifier.height(8.dp))
        LineChart(
            title = "Accelerometer · |a| (g)",
            values = state.accelTrace,
            color = Color(0xFFC9A9FF),
        )
        Spacer(Modifier.height(10.dp))
        LineChart(
            title = "Gyroscope · |ω| (dps)",
            values = state.gyroTrace,
            color = Color(0xFF6EE7A8),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = if (danger) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            trailingIcon,
            contentDescription = null,
            tint = if (danger) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
