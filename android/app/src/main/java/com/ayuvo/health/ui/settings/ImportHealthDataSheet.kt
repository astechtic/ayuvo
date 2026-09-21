package com.ayuvo.health.ui.settings

import com.ayuvo.health.ui.design.AyuvoColors
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.export.HealthDataImporter
import com.ayuvo.health.export.HealthImportMode
import com.ayuvo.health.export.HealthImportPreview
import com.ayuvo.health.services.health.HealthSyncTrigger
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Preview a `ayuvo-health-data` zip, then Merge or Replace all (with confirmation). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportHealthDataSheet(container: AppContainer, uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    var preview by remember { mutableStateOf<HealthImportPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    val readFailed = stringResource(R.string.import_read_failed)
    val doneFormat = stringResource(R.string.health_import_result)

    fun sizeOf(): Long? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull()

    LaunchedEffect(uri) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    HealthDataImporter(container.healthStore).preview(input, sizeOf())
                } ?: throw IllegalArgumentException(readFailed)
            }
        }
        preview = result.getOrNull()
        error = result.exceptionOrNull()?.localizedMessage ?: if (result.isFailure) readFailed else null
    }

    fun runImport(mode: HealthImportMode) {
        scope.launch {
            importing = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    container.healthSync.withPaused {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            HealthDataImporter(container.healthStore).apply(input, mode, sizeOf())
                        } ?: throw IllegalArgumentException(readFailed)
                    }
                }
            }
            importing = false
            result.onSuccess {
                done = String.format(doneFormat, it.inserted + it.updated, it.skippedUnitMismatch)
                container.requestHealthSync(HealthSyncTrigger.IMPORT_COMPLETED)
            }.onFailure { error = it.localizedMessage ?: readFailed }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!importing) onDismiss() },
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = AyuvoColors.sheetBackground(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.health_settings_import), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val p = preview
            when {
                error != null -> {
                    Text(error!!, color = Color(0xFFFF3B30), fontSize = 14.sp, lineHeight = 20.sp)
                    ActionButton(stringResource(R.string.action_done), enabled = true, loading = false, primary = false, onClick = onDismiss)
                }
                done != null -> {
                    Text(done!!, fontSize = 14.sp, lineHeight = 20.sp)
                    ActionButton(stringResource(R.string.action_done), enabled = true, loading = false, primary = true, onClick = onDismiss)
                }
                p == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.padding(6.dp))
                    Text(stringResource(R.string.health_import_reading))
                }
                else -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SummaryLine(stringResource(R.string.health_import_platform), "${p.manifest.platform} · ${p.manifest.app_version}")
                        SummaryLine(stringResource(R.string.health_import_records), p.sampleCount.toString())
                        SummaryLine(stringResource(R.string.health_import_types), p.countsByType.size.toString())
                        p.manifest.date_range?.let { SummaryLine(stringResource(R.string.import_date_range), "${it.start} – ${it.end}") }
                        if (p.unknownTypes.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f))
                            SummaryLine(stringResource(R.string.health_import_unknown_types), p.unknownTypes.joinToString(", "))
                        }
                        if (p.unitMismatches > 0) SummaryLine(stringResource(R.string.health_import_unit_mismatch), p.unitMismatches.toString())
                        if (p.checksumWarning) {
                            Text(stringResource(R.string.health_import_checksum_warning), color = Color(0xFFFF9500), fontSize = 13.sp)
                        }
                    }
                    Text(
                        stringResource(R.string.health_import_modes_body),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    ActionButton(stringResource(R.string.health_import_merge), enabled = !importing, loading = importing, primary = true) { runImport(HealthImportMode.MERGE) }
                    ActionButton(stringResource(R.string.health_import_replace), enabled = !importing, loading = false, primary = false) { confirmReplace = true }
                }
            }
        }
    }

    if (confirmReplace) {
        GlassDialog(onDismissRequest = { confirmReplace = false }) {
            Text(stringResource(R.string.health_import_replace_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.health_import_replace_message), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = stringResource(R.string.health_import_replace),
                onPrimary = { confirmReplace = false; runImport(HealthImportMode.REPLACE_ALL) },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { confirmReplace = false },
                destructive = true
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f), fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}
