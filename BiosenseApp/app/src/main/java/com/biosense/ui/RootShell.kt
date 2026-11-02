package com.biosense.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Watch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.biosense.data.ProfileStore
import com.biosense.ui.screens.DeviceScreen
import com.biosense.ui.screens.HomeContent
import com.biosense.ui.screens.MeScreen
import com.biosense.ui.screens.MeSecondary
import com.biosense.ui.screens.MedicalIdScreen
import com.biosense.ui.screens.MedicationsScreen
import com.biosense.ui.screens.OnboardingScreen
import com.biosense.ui.screens.ProfileScreen
import com.biosense.ui.screens.Metric
import com.biosense.ui.screens.MetricDetailScreen
import com.biosense.ui.screens.SessionsScreen
import com.biosense.vm.MonitorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootShell(
    vm: MonitorViewModel,
    onRequestConnect: () -> Unit,
) {
    val ctx = LocalContext.current
    // First-launch gate: if no profile has been saved, show the onboarding
    // flow before anything else. Once completed (or if the user already has
    // a profile from a prior launch), fall through to the normal shell.
    var profileComplete by remember {
        mutableStateOf(ProfileStore(ctx).load().fullName.isNotBlank())
    }
    if (!profileComplete) {
        OnboardingScreen(onComplete = {
            vm.reloadProfile()
            profileComplete = true
        })
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Metric?>(null) }
    var meSub by remember { mutableStateOf<MeSecondary?>(null) }
    val state by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    BackHandler(enabled = detail != null || meSub != null) {
        if (detail != null) detail = null else meSub = null
    }

    LaunchedEffect(state.errorSnack) {
        state.errorSnack?.let {
            snackbar.showSnackbar(it)
            vm.clearSnack()
        }
    }
    LaunchedEffect(state.lastExport) {
        state.lastExport?.let { snackbar.showSnackbar("Saved: $it") }
    }

    val detailTitle = detail?.let { metricTitle(it) }
    val meSubTitle = meSub?.let { meSecondaryTitle(it) }
    val tabTitle = detailTitle ?: meSubTitle ?: when (tab) {
        0 -> "Biosense"
        1 -> "Sessions"
        2 -> "Device"
        else -> "Me"
    }
    val tabSubtitle = when {
        detail != null -> "Session detail"
        meSub != null -> "Profile & care"
        else -> when (tab) {
            0 -> "V2 · Native"
            1 -> "Exports & recordings"
            2 -> "Wearable"
            else -> "Profile & settings"
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tabTitle, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            tabSubtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    if (detail != null || meSub != null) {
                        IconButton(onClick = { if (detail != null) detail = null else meSub = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    state.batteryPct?.let { pct ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            Icon(
                                imageVector = batteryIcon(pct),
                                contentDescription = "Wearable battery",
                                tint = batteryTint(pct),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "$pct%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = { BottomNav(tab) { tab = it; detail = null; meSub = null } },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val activeDetail = detail
            val activeMeSub = meSub
            when {
                activeDetail != null -> MetricDetailScreen(metric = activeDetail, state = state, vm = vm)
                activeMeSub == MeSecondary.PROFILE -> ProfileScreen(onSaved = {
                    vm.reloadProfile()
                    meSub = null
                })
                activeMeSub == MeSecondary.MEDICATIONS -> MedicationsScreen(vm = vm)
                activeMeSub == MeSecondary.MEDICAL_ID -> MedicalIdScreen()
                else -> when (tab) {
                    0 -> HomeContent(
                        state = state,
                        vm = vm,
                        onRequestConnect = onRequestConnect,
                        onOpenMetric = { detail = it },
                    )
                    1 -> SessionsScreen(vm = vm)
                    2 -> DeviceScreen(state = state, vm = vm, onRequestConnect = onRequestConnect)
                    else -> MeScreen(state = state, vm = vm, onOpen = { meSub = it })
                }
            }
        }
    }
}

private fun batteryTint(pct: Int): Color = when {
    pct <= 15 -> Color(0xFFEF4444)
    pct <= 35 -> Color(0xFFF5B942)
    else -> Color(0xFF4ADE80)
}

// Pick a battery glyph whose fill roughly matches the reported percentage so
// the icon itself communicates level, not just its tint.
private fun batteryIcon(pct: Int): ImageVector = when {
    pct >= 95 -> Icons.Filled.BatteryFull
    pct >= 80 -> Icons.Filled.Battery6Bar
    pct >= 65 -> Icons.Filled.Battery5Bar
    pct >= 50 -> Icons.Filled.Battery4Bar
    pct >= 35 -> Icons.Filled.Battery3Bar
    pct >= 20 -> Icons.Filled.Battery2Bar
    pct >= 10 -> Icons.Filled.Battery1Bar
    else -> Icons.Filled.Battery0Bar
}

private fun meSecondaryTitle(s: MeSecondary): String = when (s) {
    MeSecondary.MEDICATIONS -> "Medications"
    MeSecondary.MEDICAL_ID -> "Medical ID"
    MeSecondary.PROFILE -> "Profile & alerts"
}

private fun metricTitle(m: Metric): String = when (m) {
    Metric.WELLNESS -> "Wellness"
    Metric.HR -> "Heart Rate"
    Metric.SPO2 -> "SpO₂"
    Metric.HRV -> "HRV"
    Metric.GYRO -> "Gyro RMS"
    Metric.STEPS -> "Steps"
    Metric.STRESS -> "Stress"
}

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        val items = listOf(
            Triple("Health", Icons.Filled.Favorite, 0),
            Triple("Sessions", Icons.Filled.History, 1),
            Triple("Device", Icons.Filled.Watch, 2),
            Triple("Me", Icons.Filled.Person, 3),
        )
        for ((label, icon, idx) in items) {
            NavigationBarItem(
                selected = selected == idx,
                onClick = { onSelect(idx) },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFFF5B942),
                    selectedTextColor = Color(0xFFF5B942),
                    indicatorColor = Color(0x22F5B942),
                ),
            )
        }
    }
}
