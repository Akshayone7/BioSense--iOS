package com.biosense.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biosense.data.ProfileStore

/**
 * First-launch profile capture. Shown by [com.biosense.ui.RootShell] when
 * `ProfileStore.load().fullName.isBlank()`. Dismisses itself on save via
 * [onComplete] and the shell falls through to the normal tab UI.
 *
 * Required fields: full name, email, phone, age, sex. Height and weight are
 * optional at this step and can be filled later under Me → Profile.
 * Medical ID (allergies / conditions / emergency contact) also lives under
 * Me → Medical ID and is intentionally kept out of this flow to keep the
 * first-launch friction low.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { ProfileStore(ctx) }

    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf(ProfileStore.Sex.UNSET) }
    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var attemptedSave by remember { mutableStateOf(false) }

    // Validation. Minimal — just non-blank + loose email/phone format.
    val nameValid = fullName.trim().isNotBlank()
    val emailValid = email.trim().contains('@') && email.trim().contains('.')
    val phoneValid = phone.filter { it.isDigit() }.length >= 7
    val ageValid = age.toIntOrNull()?.let { it in 1..120 } == true
    val sexValid = sex != ProfileStore.Sex.UNSET
    val allValid = nameValid && emailValid && phoneValid && ageValid && sexValid

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(Color(0x22F5B942), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = Color(0xFFF5B942),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text("Welcome to Biosense", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Text(
                    "Set up your profile to get started",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "This information identifies the wearer on all exports (CSV, PDF) and powers your personalized HR thresholds. Stored on-device only.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        // Identity card
        SectionLabel("Identity")
        FieldCard {
            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it.take(80) },
                label = { Text("Full name *") },
                isError = attemptedSave && !nameValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.take(120) },
                label = { Text("Email *") },
                isError = attemptedSave && !emailValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.take(20) },
                label = { Text("Phone *") },
                isError = attemptedSave && !phoneValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Demographics")
        FieldCard {
            OutlinedTextField(
                value = age,
                onValueChange = { age = it.filter(Char::isDigit).take(3) },
                label = { Text("Age in years *") },
                isError = attemptedSave && !ageValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text("Sex *", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            SexPicker(sex) { sex = it }
            if (attemptedSave && !sexValid) {
                Spacer(Modifier.height(4.dp))
                Text("Please select one", fontSize = 11.sp, color = Color(0xFFEF4444))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter(Char::isDigit).take(3) },
                    label = { Text("Height (cm)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                    label = { Text("Weight (kg)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Height / weight are optional. Fill them if you want stride-based distance and calorie estimates.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                attemptedSave = true
                if (!allValid) return@Button
                val next = ProfileStore.Profile(
                    fullName = fullName.trim(),
                    email = email.trim(),
                    phone = phone.trim(),
                    ageYears = age.toIntOrNull(),
                    sex = sex,
                    weightKg = weight.toFloatOrNull()?.takeIf { it in 1f..400f },
                    heightCm = height.toFloatOrNull()?.takeIf { it in 30f..250f },
                    alertsEnabled = true,
                )
                store.save(next)
                onComplete()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF5B942)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Create profile", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "You can edit any of these later under Me → Profile. Emergency contact and medical ID live under Me → Medical ID.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun SexPicker(current: ProfileStore.Sex, onChange: (ProfileStore.Sex) -> Unit) {
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
