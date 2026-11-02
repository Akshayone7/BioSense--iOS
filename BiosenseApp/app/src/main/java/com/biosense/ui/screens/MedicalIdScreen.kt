package com.biosense.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biosense.data.MedicalIdStore

@Composable
fun MedicalIdScreen() {
    val ctx = LocalContext.current
    val store = remember { MedicalIdStore(ctx) }

    var info by remember { mutableStateOf(MedicalIdStore.Info()) }
    LaunchedEffect(Unit) { info = store.load() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            "Emergency responders can see this on the lock screen if you enable it in " +
                "system Settings → Safety & emergency → Medical info.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Personal", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            F("Full name", info.fullName) { info = info.copy(fullName = it) }
            F("Date of birth", info.dob) { info = info.copy(dob = it) }
            F("Blood type", info.bloodType) { info = info.copy(bloodType = it) }
            Spacer(Modifier.height(4.dp))
            Text("Medical", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            F("Conditions", info.conditions, singleLine = false) { info = info.copy(conditions = it) }
            F("Allergies", info.allergies, singleLine = false) { info = info.copy(allergies = it) }
            F("Current medications", info.medications, singleLine = false) { info = info.copy(medications = it) }
            F("Notes", info.notes, singleLine = false) { info = info.copy(notes = it) }
            Spacer(Modifier.height(4.dp))
            Text("Emergency contact", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            F("Contact name", info.emergencyName) { info = info.copy(emergencyName = it) }
            F("Contact phone", info.emergencyPhone) { info = info.copy(emergencyPhone = it) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { store.save(info) }) { Text("Save") }
            if (info.emergencyPhone.isNotBlank()) {
                OutlinedButton(onClick = {
                    val dial = android.content.Intent(
                        android.content.Intent.ACTION_DIAL,
                        android.net.Uri.parse("tel:${info.emergencyPhone}"),
                    )
                    ctx.startActivity(dial)
                }) { Text("Test call") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun F(label: String, value: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}
