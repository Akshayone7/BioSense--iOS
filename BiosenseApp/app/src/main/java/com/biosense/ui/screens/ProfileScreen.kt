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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biosense.data.ProfileStore

@Composable
fun ProfileScreen(onSaved: () -> Unit = {}) {
    val ctx = LocalContext.current
    val store = remember { ProfileStore(ctx) }
    var profile by remember { mutableStateOf(store.load()) }

    var fullName by remember { mutableStateOf(profile.fullName) }
    var email by remember { mutableStateOf(profile.email) }
    var phone by remember { mutableStateOf(profile.phone) }
    var age by remember { mutableStateOf(profile.ageYears?.toString() ?: "") }
    var weight by remember { mutableStateOf(profile.weightKg?.let { "%.1f".format(it) } ?: "") }
    var height by remember { mutableStateOf(profile.heightCm?.let { "%.0f".format(it) } ?: "") }
    var sex by remember { mutableStateOf(profile.sex) }
    var hrHigh by remember { mutableStateOf(profile.hrHighBpm?.toString() ?: "") }
    var hrLow by remember { mutableStateOf(profile.hrLowBpm?.toString() ?: "") }
    var alertsOn by remember { mutableStateOf(profile.alertsEnabled) }

    val derivedHigh = ProfileStore.ageDerivedHigh(age.toIntOrNull())
    val derivedLowLabel = "${ProfileStore.DEFAULT_LOW_BPM} bpm (default)"

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        SectionLabel("Personal details")
        FieldCard {
            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it.take(80) },
                label = { Text("Full name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.take(120) },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.take(20) },
                label = { Text("Phone") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = age,
                onValueChange = { age = it.filter(Char::isDigit).take(3) },
                label = { Text("Age (years)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            SexSelector(sex) { sex = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                    label = { Text("Weight (kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter(Char::isDigit).take(3) },
                    label = { Text("Height (cm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Heart-rate alerts")
        FieldCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enable abnormal-HR alerts", modifier = Modifier.weight(1f))
                Switch(checked = alertsOn, onCheckedChange = { alertsOn = it })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Sustained > 60 s above the high bound or below the low bound raises a notification.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = hrHigh,
                    onValueChange = { hrHigh = it.filter(Char::isDigit).take(3) },
                    label = { Text("High (bpm)") },
                    placeholder = { Text("$derivedHigh") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = hrLow,
                    onValueChange = { hrLow = it.filter(Char::isDigit).take(3) },
                    label = { Text("Low (bpm)") },
                    placeholder = { Text(derivedLowLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Blank = use derived defaults. High defaults to 85 % of age-derived max HR.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Wearer ID")
        FieldCard {
            val wearerId = profile.wearerIdShort
            if (wearerId == null) {
                Text(
                    "Save your profile to generate a wearer ID. It stamps every exported CSV and PDF so your data is attributable to you.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        wearerId,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Copy",
                        color = Color(0xFFF5B942),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Wearer ID", wearerId))
                            Toast.makeText(ctx, "Wearer ID copied", Toast.LENGTH_SHORT).show()
                        }.padding(start = 8.dp, end = 4.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "This ID identifies all data exported from this device. It cannot be changed.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val next = ProfileStore.Profile(
                    fullName = fullName.trim(),
                    email = email.trim(),
                    phone = phone.trim(),
                    ageYears = age.toIntOrNull()?.takeIf { it in 1..120 },
                    sex = sex,
                    weightKg = weight.toFloatOrNull()?.takeIf { it in 1f..400f },
                    heightCm = height.toFloatOrNull()?.takeIf { it in 30f..250f },
                    hrHighBpm = hrHigh.toIntOrNull()?.takeIf { it in 60..240 },
                    hrLowBpm = hrLow.toIntOrNull()?.takeIf { it in 20..120 },
                    alertsEnabled = alertsOn,
                )
                store.save(next)
                profile = store.load()
                onSaved()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save profile") }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun FieldCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun SexSelector(current: ProfileStore.Sex, onChange: (ProfileStore.Sex) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        val opts = listOf(
            ProfileStore.Sex.FEMALE to "Female",
            ProfileStore.Sex.MALE to "Male",
            ProfileStore.Sex.OTHER to "Other",
        )
        for ((s, label) in opts) {
            val isSel = s == current
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (isSel) Color(0x33F5B942) else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onChange(s) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSel) Color(0xFFF5B942) else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
