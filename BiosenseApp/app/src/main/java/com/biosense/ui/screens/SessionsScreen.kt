package com.biosense.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biosense.data.ProfileStore
import com.biosense.vm.MonitorViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionEntry(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateAddedMs: Long,
)

@Composable
fun SessionsScreen(vm: MonitorViewModel) {
    val ctx = LocalContext.current
    var sessions by remember { mutableStateOf(emptyList<SessionEntry>()) }
    var refreshTick by remember { mutableStateOf(0) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var showCsvRange by remember { mutableStateOf(false) }
    val state by vm.ui.collectAsStateWithLifecycle()

    // Re-read profile on every refresh so wearer ID / name appear immediately
    // after the user saves their profile.
    val profile by remember(refreshTick) { mutableStateOf(ProfileStore(ctx).load()) }
    val canExport = profile.fullName.isNotBlank()

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let {
            vm.restoreHistory(it)
            refreshTick++
        }
    }

    LaunchedEffect(refreshTick) {
        sessions = loadSessions(ctx)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        LazyColumn {
            item {
                IdentityBanner(profile)
                Spacer(Modifier.height(14.dp))
            }

            item {
                Text(
                    "Export",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                        .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
                ) {
                    ExportRow(
                        icon = Icons.Filled.TableChart,
                        title = "Download history CSV",
                        subtitle = if (canExport) "Choose a date range · deduped by heartbeat"
                                   else "Set up your profile first to enable",
                        enabled = canExport,
                        onClick = { showCsvRange = true },
                    )
                    RowDivider()
                    ExportRow(
                        icon = Icons.Filled.PictureAsPdf,
                        title = "Weekly PDF report",
                        subtitle = if (canExport) "Last 7 days · wellness score + vitals charts"
                                   else "Set up your profile first to enable",
                        enabled = canExport,
                        onClick = { vm.exportWeeklyReport(); refreshTick++ },
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            item {
                SettingsToggle(expanded = settingsExpanded, onToggle = { settingsExpanded = !settingsExpanded })
                if (settingsExpanded) {
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
                    ) {
                        ExportRow(
                            icon = Icons.Filled.Backup,
                            title = "Backup everything (ZIP)",
                            subtitle = "Full history folder as a ZIP",
                            enabled = canExport,
                            onClick = { vm.backupHistory(); refreshTick++ },
                        )
                        RowDivider()
                        ExportRow(
                            icon = Icons.Filled.Restore,
                            title = "Restore from ZIP",
                            subtitle = "Merge a backup back into the app",
                            enabled = true,
                            onClick = { restoreLauncher.launch(arrayOf("application/zip")) },
                        )
                        RowDivider()
                        RawRecorderRow(state = state, vm = vm)
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Files in Downloads · ${sessions.size}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            if (sessions.isEmpty()) {
                item { EmptyState() }
            } else {
                items(sessions, key = { it.uri.toString() }) { s ->
                    SessionRow(s) {
                        val mime = mimeFor(s.displayName)
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, s.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(share, "Share ${s.displayName}"))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (showCsvRange) {
        CsvRangeDialog(
            vm = vm,
            onDismiss = { showCsvRange = false },
            onExport = { fromMs, toMs ->
                showCsvRange = false
                vm.exportHistoryCsv(fromMs, toMs)
                refreshTick++
            },
        )
    }
}

/**
 * Date-range picker for the history CSV. Dates are typed as dd/mm/yyyy with
 * the slashes inserted automatically, so the number pad works. Defaults to the
 * full span of recorded data (first day with data → today).
 */
@Composable
private fun CsvRangeDialog(
    vm: MonitorViewModel,
    onDismiss: () -> Unit,
    onExport: (fromMs: Long, toMs: Long) -> Unit,
) {
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { isLenient = false } }
    val today = remember { fmt.format(Date()) }
    var from by remember { mutableStateOf(TextFieldValue(today, TextRange(today.length))) }
    var to by remember { mutableStateOf(TextFieldValue(today, TextRange(today.length))) }
    var earliestLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val earliest = vm.earliestHistoryDayMs() ?: return@LaunchedEffect
        val label = fmt.format(Date(earliest))
        earliestLabel = label
        from = TextFieldValue(label, TextRange(label.length))
    }

    val fromMs = parseDdMmYyyy(fmt, from.text)
    val toMs = parseDdMmYyyy(fmt, to.text)
    val error = when {
        from.text.length == 10 && fromMs == null -> "From isn't a valid date"
        to.text.length == 10 && toMs == null -> "To isn't a valid date"
        fromMs != null && toMs != null && fromMs > toMs -> "From must be on or before To"
        else -> null
    }
    val valid = fromMs != null && toMs != null && error == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download history CSV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = formatDateInput(it) },
                    label = { Text("From (dd/mm/yyyy)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = formatDateInput(it) },
                    label = { Text("To (dd/mm/yyyy)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    error ?: earliestLabel?.let { "Data available from $it" } ?: "No recorded data yet",
                    fontSize = 12.sp,
                    color = if (error != null) Color(0xFFEF4444)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onExport(fromMs!!, toMs!!) }) {
                Text("Download")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Null unless [s] is a complete, real calendar date in dd/MM/yyyy. */
private fun parseDdMmYyyy(fmt: SimpleDateFormat, s: String): Long? =
    if (s.length != 10) null else runCatching { fmt.parse(s)?.time }.getOrNull()

/** Keep digits only (max 8) and re-insert the slashes: "15102026" → "15/10/2026". */
private fun formatDateInput(v: TextFieldValue): TextFieldValue {
    val digits = v.text.filter(Char::isDigit).take(8)
    val out = buildString {
        digits.forEachIndexed { i, c ->
            if (i == 2 || i == 4) append('/')
            append(c)
        }
    }
    return TextFieldValue(out, TextRange(out.length))
}

@Composable
private fun ExportRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Color(0xFFF5B942) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.padding(horizontal = 6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.FileDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IdentityBanner(profile: ProfileStore.Profile) {
    val name = profile.fullName.ifBlank { "Unnamed wearer" }
    val id = profile.wearerIdShort
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            "EXPORTING DATA FOR",
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            id ?: "No wearer ID yet — set up profile",
            fontSize = 12.sp,
            color = if (id != null) Color(0xFFF5B942) else Color(0xFFEF4444),
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SettingsToggle(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Settings,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Settings · Backup, Restore, raw-session recorder",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RawRecorderRow(state: MonitorViewModel.UiState, vm: MonitorViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = if (state.recording) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column(Modifier.weight(1f)) {
                Text("Record raw session (~104 Hz)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "Raw IMU + PPG samples. Developer / signal-analysis only.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
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
}

@Composable
private fun RowDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceVariant,
        thickness = 1.dp,
    )
}

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Nothing exported yet",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap any export above to generate a file.\nAll files land in Downloads/Biosense.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SessionRow(s: SessionEntry, onShare: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .clickable(onClick = onShare)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            iconFor(s.displayName),
            contentDescription = null,
            tint = tintFor(s.displayName),
        )
        Spacer(Modifier.padding(horizontal = 6.dp))
        Column(Modifier.weight(1f)) {
            Text(s.displayName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatBytes(s.sizeBytes)} · ${formatDate(s.dateAddedMs)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.Share,
            contentDescription = "Share",
            tint = Color(0xFFF5B942),
        )
    }
}

private fun iconFor(name: String): ImageVector = when {
    name.endsWith(".pdf", ignoreCase = true) -> Icons.Filled.PictureAsPdf
    name.endsWith(".zip", ignoreCase = true) -> Icons.Filled.Backup
    name.endsWith(".csv", ignoreCase = true) -> Icons.Filled.TableChart
    else -> Icons.Filled.Description
}

private fun tintFor(name: String): Color = when {
    name.endsWith(".pdf", ignoreCase = true) -> Color(0xFFFF6B7A)
    name.endsWith(".zip", ignoreCase = true) -> Color(0xFF9EC5FF)
    name.endsWith(".csv", ignoreCase = true) -> Color(0xFF6EE7A8)
    else -> Color(0xFF9AA6B8)
}

private fun mimeFor(name: String): String = when {
    name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
    name.endsWith(".zip", ignoreCase = true) -> "application/zip"
    name.endsWith(".csv", ignoreCase = true) -> "text/csv"
    else -> "*/*"
}

private fun formatBytes(n: Long): String {
    if (n < 1024) return "$n B"
    if (n < 1024 * 1024) return "%.1f KB".format(n / 1024f)
    return "%.1f MB".format(n / (1024f * 1024f))
}

private fun formatDate(ms: Long): String {
    if (ms <= 0) return ""
    return SimpleDateFormat("MMM d · HH:mm", Locale.US).format(Date(ms))
}

private fun loadSessions(ctx: android.content.Context): List<SessionEntry> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        loadFromMediaStore(ctx)
    } else {
        loadFromLegacyDir()
    }
}

private fun loadFromMediaStore(ctx: android.content.Context): List<SessionEntry> {
    val out = ArrayList<SessionEntry>()
    val projection = arrayOf(
        MediaStore.Downloads._ID,
        MediaStore.Downloads.DISPLAY_NAME,
        MediaStore.Downloads.SIZE,
        MediaStore.Downloads.DATE_ADDED,
        MediaStore.Downloads.RELATIVE_PATH,
    )
    // Match any Biosense artifact: session samples, history CSV, weekly PDF,
    // or backup ZIP. All of them land in Downloads/Biosense with the
    // "biosense_" prefix, so one glob covers everything.
    val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
    val args = arrayOf("%Biosense%", "biosense_%")
    val order = "${MediaStore.Downloads.DATE_ADDED} DESC"

    ctx.contentResolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        projection, selection, args, order
    )?.use { c ->
        val idIx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
        val nameIx = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
        val sizeIx = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
        val dateIx = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
        while (c.moveToNext()) {
            val id = c.getLong(idIx)
            out += SessionEntry(
                uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()),
                displayName = c.getString(nameIx),
                sizeBytes = c.getLong(sizeIx),
                dateAddedMs = c.getLong(dateIx) * 1000L,
            )
        }
    }
    return out
}

private fun loadFromLegacyDir(): List<SessionEntry> {
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Biosense")
    if (!dir.exists()) return emptyList()
    return dir.listFiles { f -> f.name.startsWith("biosense_") }
        ?.map {
            SessionEntry(
                uri = Uri.fromFile(it),
                displayName = it.name,
                sizeBytes = it.length(),
                dateAddedMs = it.lastModified(),
            )
        }
        ?.sortedByDescending { it.dateAddedMs }
        ?: emptyList()
}
