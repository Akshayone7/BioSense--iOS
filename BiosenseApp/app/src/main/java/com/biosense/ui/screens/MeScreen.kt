package com.biosense.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.health.connect.client.PermissionController
import com.biosense.health.HealthConnectSync
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biosense.vm.MonitorViewModel

enum class MeSecondary { MEDICATIONS, MEDICAL_ID, PROFILE }

@Composable
fun MeScreen(
    state: MonitorViewModel.UiState,
    vm: MonitorViewModel? = null,
    onOpen: (MeSecondary) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hcAvailability by remember { mutableStateOf(HealthConnectSync.availability(ctx)) }
    var hcHasPerms by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hcAvailability = HealthConnectSync.availability(ctx)
                scope.launch {
                    hcHasPerms = if (hcAvailability == HealthConnectSync.Availability.INSTALLED)
                        HealthConnectSync.hasPermissions(ctx) else false
                    if (hcHasPerms && vm?.isHealthConnectEnabled() == false) {
                        vm.setHealthConnectEnabled(true)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }
    val permLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { _: Set<String> ->
        // alpha07's parseResult is unreliable — the returned set is often empty
        // even when the user granted everything. Re-query HC as source of truth.
        scope.launch {
            val ok = HealthConnectSync.hasPermissions(ctx)
            hcHasPerms = ok
            vm?.setHealthConnectEnabled(ok)
            if (!ok) vm?.showSnack("Grant all four vitals permissions to enable Health Connect sync.")
        }
    }
    val openHealthConnect: () -> Unit = {
        val opened = runCatching {
            ctx.startActivity(
                Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!opened) {
            runCatching {
                ctx.startActivity(
                    ctx.packageManager.getLaunchIntentForPackage(
                        "com.google.android.apps.healthdata"
                    )?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?: Intent(android.provider.Settings.ACTION_SETTINGS)
                )
            }.onFailure { vm?.showSnack("Couldn't open Health Connect: ${it.message}") }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        ProfileCard()
        Spacer(Modifier.height(14.dp))

        Text(
            "This session",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Packets", state.packetCount.toString(), Modifier.weight(1f))
            StatTile("Recorded", state.recordedCount.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Steps today", state.stepsToday.toString(), Modifier.weight(1f))
            StatTile("HR", state.hrBpm?.let { "%.0f".format(it) } ?: "—", Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Care",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        CareTile(
            icon = Icons.Filled.Person,
            title = "Profile & alerts",
            subtitle = "Age, weight, height, HR thresholds",
            onClick = { onOpen(MeSecondary.PROFILE) },
        )
        Spacer(Modifier.height(8.dp))
        CareTile(
            icon = Icons.Filled.Medication,
            title = "Medications",
            subtitle = "Doses, reminders & adherence",
            onClick = { onOpen(MeSecondary.MEDICATIONS) },
        )
        Spacer(Modifier.height(8.dp))
        CareTile(
            icon = Icons.Filled.MedicalServices,
            title = "Medical ID",
            subtitle = "Emergency info & contact",
            onClick = { onOpen(MeSecondary.MEDICAL_ID) },
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "Sync",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        HealthConnectCard(
            availability = hcAvailability,
            hasPerms = hcHasPerms,
            enabled = state.healthConnectEnabled && hcHasPerms,
            onToggle = { want ->
                if (!want) {
                    vm?.setHealthConnectEnabled(false)
                    return@HealthConnectCard
                }
                scope.launch {
                    if (HealthConnectSync.hasPermissions(ctx)) {
                        hcHasPerms = true
                        vm?.setHealthConnectEnabled(true)
                    } else {
                        try {
                            permLauncher.launch(HealthConnectSync.PERMISSIONS)
                        } catch (t: Throwable) {
                            vm?.showSnack("Couldn't open HC sheet: ${t.message}")
                            openHealthConnect()
                        }
                    }
                }
            },
            onOpenSettings = openHealthConnect,
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "About",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        InfoLine("Version", "V2 · Native (Compose)")
        InfoLine("Wire protocol", "208-byte notifications @ ~104 Hz")
        InfoLine("Analytics", "Autonomic (HRV + RHR) · sleep · PPG peak HR/HRV/SpO₂")
        Spacer(Modifier.height(8.dp))
        Text(
            "Not a medical device.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileCard() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(Color(0xFF2A2A2A), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = Color(0xFFF5B942),
            )
        }
        Spacer(Modifier.padding(horizontal = 6.dp))
        Column {
            Text("Guest", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                "Local profile · no sign-in",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HealthConnectCard(
    availability: HealthConnectSync.Availability,
    hasPerms: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val (subtitle, canToggle) = when (availability) {
        HealthConnectSync.Availability.INSTALLED ->
            (if (enabled) "Writing HR, HRV, SpO₂, steps to Health Connect."
             else if (!hasPerms) "Permissions not granted yet — flip on to request."
             else "Off — flip on to mirror vitals into Health Connect.") to true
        HealthConnectSync.Availability.NEEDS_UPDATE ->
            "Update the Health Connect app in Play Store to enable." to false
        HealthConnectSync.Availability.UNAVAILABLE ->
            "Not available on this device." to false
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Health Connect", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onToggle, enabled = canToggle)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Status: ${availability.name} · perms=${if (hasPerms) "granted" else "missing"}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSettings) {
            Text("Open Health Connect settings", fontSize = 12.sp)
        }
    }
}

@Composable
private fun CareTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(Color(0x22F5B942), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color(0xFFF5B942)) }
        Spacer(Modifier.padding(horizontal = 6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
