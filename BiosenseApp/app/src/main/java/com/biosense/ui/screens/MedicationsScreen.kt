package com.biosense.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.biosense.data.MedicationStore
import com.biosense.vm.MonitorViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MedicationsScreen(vm: MonitorViewModel) {
    var meds by remember { mutableStateOf<List<MedicationStore.Medication>>(emptyList()) }
    var events by remember { mutableStateOf<List<MedicationStore.Event>>(emptyList()) }
    var editing by remember { mutableStateOf<MedicationStore.Medication?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        meds = vm.listMedications()
        events = vm.recentMedEvents(daysBack = 7)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            "Your medications",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (meds.isEmpty()) {
            EmptyCard("No medications yet. Add one to start getting scheduled reminders.")
        } else {
            for (m in meds) {
                MedCard(
                    m = m,
                    onToggle = { vm.setMedicationEnabled(m.id, it); refreshTick++ },
                    onEdit = { editing = m },
                    onDelete = { vm.deleteMedication(m.id); refreshTick++ },
                    onLogNow = { action ->
                        vm.logMedicationAction(m.id, m.name, m.dose, action, m.timeList().firstOrNull().orEmpty())
                        refreshTick++
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = { editing = MedicationStore.Medication("", "", "", "", true) }) {
            Text("Add medication")
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Adherence · last 7 days",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        AdherenceCard(events)
        Spacer(Modifier.height(6.dp))
        RecentEventsList(events)
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { m ->
        MedEditor(
            initial = m,
            onCancel = { editing = null },
            onSave = {
                vm.upsertMedication(it)
                editing = null
                refreshTick++
            },
        )
    }
}

@Composable
private fun MedCard(
    m: MedicationStore.Medication,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLogNow: (MedicationStore.Action) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name.ifEmpty { "(unnamed)" }, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    listOf(m.dose, m.times).filter { it.isNotEmpty() }.joinToString(" · "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = m.enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onLogNow(MedicationStore.Action.TAKEN) }) { Text("Taken now") }
            OutlinedButton(onClick = { onLogNow(MedicationStore.Action.SKIPPED) }) { Text("Skipped") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) {
                Text("Delete", color = Color(0xFFEF4444))
            }
        }
    }
}

@Composable
private fun MedEditor(
    initial: MedicationStore.Medication,
    onCancel: () -> Unit,
    onSave: (MedicationStore.Medication) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var dose by remember { mutableStateOf(initial.dose) }
    var times by remember { mutableStateOf(initial.times) }
    var enabled by remember { mutableStateOf(initial.enabled) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(name = name, dose = dose, times = times, enabled = enabled))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        title = { Text(if (initial.id.isEmpty()) "Add medication" else "Edit medication") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(value = dose, onValueChange = { dose = it }, label = { Text("Dose (e.g. 100mg)") })
                OutlinedTextField(
                    value = times,
                    onValueChange = { times = it },
                    label = { Text("Times HH:MM,HH:MM") },
                    supportingText = { Text("24h clock, comma-separated") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text("Reminders enabled")
                }
            }
        },
    )
}

@Composable
private fun AdherenceCard(events: List<MedicationStore.Event>) {
    val taken = events.count { it.action == MedicationStore.Action.TAKEN }
    val skipped = events.count { it.action == MedicationStore.Action.SKIPPED }
    val total = taken + skipped
    val pct = if (total == 0) 0 else (taken * 100 / total)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$pct%", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF6EE7A8))
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text("adherence", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Text("$taken taken · $skipped skipped over the last 7 days", fontSize = 12.sp)
    }
}

@Composable
private fun RecentEventsList(events: List<MedicationStore.Event>) {
    if (events.isEmpty()) return
    val fmt = remember { SimpleDateFormat("MMM d · HH:mm", Locale.US) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp),
    ) {
        for (e in events.sortedByDescending { it.tMs }.take(12)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = when (e.action) {
                    MedicationStore.Action.TAKEN -> Color(0xFF6EE7A8)
                    MedicationStore.Action.SKIPPED -> Color(0xFFEF4444)
                    MedicationStore.Action.SNOOZED -> Color(0xFFF5B942)
                }
                Text(e.action.name.lowercase(), color = tint, fontSize = 12.sp, modifier = Modifier.width(70.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        listOf(e.name, e.dose).filter { it.isNotEmpty() }.joinToString(" · "),
                        fontSize = 13.sp,
                    )
                }
                Text(fmt.format(Date(e.tMs)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyCard(msg: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) { Text(msg, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

