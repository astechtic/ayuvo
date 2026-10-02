package com.ayuvo.health.ui.vitals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.vitals.camera.VitalsMode
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.storage.VitalScanRecord
import com.ayuvo.health.vitals.session.VitalReference
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class ScanDetailUi(
    val loading: Boolean = true,
    val record: VitalScanRecord? = null,
    val result: VitalResultUi? = null,
    /** The scan is half of a linked finger + face pair whose other half still exists (§6). */
    val compareSessionId: String? = null,
    val reference: VitalReference? = null,
    val experimentalEnabled: Boolean = false,
    val researchEnabled: Boolean = false
)

class ScanDetailViewModel(private val container: AppContainer, private val scanId: String) : ViewModel() {
    val cfg: VitalsConfig = container.vitalsConfig
    private val _ui = MutableStateFlow(ScanDetailUi())
    val ui: StateFlow<ScanDetailUi> = _ui

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val record = runCatching { container.vitalScans.scan(scanId) }.getOrNull()?.takeIf { !it.deleted }
            val signals = if (record != null) runCatching { container.vitalScans.signals(scanId) }.getOrDefault(emptyList()) else emptyList()
            val result = record?.let { r -> withContext(Dispatchers.Default) { VitalResultUi.from(r, signals, cfg) } }
            val session = record?.sessionId
            val paired = session != null && runCatching { container.vitalScans.scansInSession(session) }.getOrDefault(emptyList())
                .map { it.mode }.toSet().containsAll(listOf(VitalScanRecord.MODE_FINGER, VitalScanRecord.MODE_FACE))
            _ui.value = ScanDetailUi(
                loading = false, record = record, result = result,
                compareSessionId = if (paired) session else null,
                reference = VitalReference.parse(record?.referenceJson)?.takeIf { !it.isEmpty },
                experimentalEnabled = container.prefs.vitalsExperimentalEnabled.first(),
                researchEnabled = container.prefs.vitalsResearchEnabled.first()
            )
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { container.vitalScans.deleteScan(scanId) }
            onDone()
        }
    }

    class Factory(private val container: AppContainer, private val scanId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ScanDetailViewModel(container, scanId) as T
    }
}

/** A saved scan: details, the results layout with its stored waveform, and delete (§7.1 "History"). */
@Composable
fun ScanDetailScreen(container: AppContainer, scanId: String, onBack: () -> Unit, onOpenCompare: (String) -> Unit = {}) {
    val vm: ScanDetailViewModel = viewModel(key = "vitals-scan-$scanId", factory = ScanDetailViewModel.Factory(container, scanId))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var showReference by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(R.string.camvitals_scan), onBack = onBack) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AyuvoSpacing.ScreenH, vertical = 8.dp)
                .testTag("vitals.detail"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val record = ui.record
            val result = ui.result
            if (!ui.loading && (record == null || result == null)) {
                Text(stringResource(R.string.camvitals_scan_missing), color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(16.dp))
                return@Column
            }
            if (record == null || result == null) return@Column
            val mode = VitalsMode.fromId(record.mode) ?: VitalsMode.FINGER
            InsetGroup(header = stringResource(R.string.camvitals_details)) {
                row {
                    GroupRow(
                        title = fmt.format(Instant.ofEpochMilli(record.startMs).atZone(ZoneId.systemDefault())),
                        icon = modeIcon(mode), iconTint = vitalsTint(), trailing = RowTrailing.None
                    )
                }
                row { GroupRow(title = stringResource(R.string.camvitals_mode), value = VitalsText.modeName(context, mode), trailing = RowTrailing.None) }
                row { GroupRow(title = stringResource(R.string.camvitals_context), value = VitalsText.contextName(context, record.context), trailing = RowTrailing.None) }
                row {
                    GroupRow(
                        title = stringResource(R.string.camvitals_duration),
                        value = stringResource(R.string.camvitals_seconds, (record.durationMs / 1000L).toInt()),
                        trailing = RowTrailing.None
                    )
                }
                row { GroupRow(title = stringResource(R.string.camvitals_device), value = record.deviceModel, trailing = RowTrailing.None) }
            }
            ui.compareSessionId?.let { session ->
                InsetGroup {
                    row {
                        GroupRow(
                            title = stringResource(R.string.camvitals_open_compare),
                            subtitle = stringResource(R.string.camvitals_compare_detail_sub),
                            icon = Icons.Filled.CompareArrows, iconTint = vitalsTint(),
                            modifier = Modifier.testTag("vitals.detail.compare"),
                            onClick = { onOpenCompare(session) }
                        )
                    }
                }
            }
            VitalResultContent(result, vm.cfg)
            InsetGroup(header = stringResource(R.string.camvitals_reference_header), modifier = Modifier.testTag("vitals.detail.reference")) {
                ui.reference?.let { ref -> referenceRows(context, ref).forEach { (label, value) -> row { GroupRow(title = label, value = value, trailing = RowTrailing.None) } } }
                row {
                    GroupRow(
                        title = stringResource(if (ui.reference == null) R.string.camvitals_add_reference else R.string.camvitals_edit_reference),
                        modifier = Modifier.testTag("vitals.detail.addReference"),
                        onClick = { showReference = true }
                    )
                }
            }
            InsetGroup {
                row {
                    GroupRow(
                        title = stringResource(R.string.camvitals_delete_scan),
                        destructive = true,
                        trailing = RowTrailing.None,
                        modifier = Modifier.testTag("vitals.detail.delete"),
                        onClick = { confirmDelete = true }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    val record = ui.record
    if (showReference && record != null) {
        ReferenceReadingSheet(
            container = container,
            record = record,
            experimentalEnabled = ui.experimentalEnabled,
            researchEnabled = ui.researchEnabled,
            onDismiss = { showReference = false },
            onSaved = { showReference = false; vm.reload() }
        )
    }
    if (confirmDelete) {
        GlassDialog(onDismissRequest = { confirmDelete = false }) {
            Text(stringResource(R.string.camvitals_delete_scan_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.camvitals_delete_scan_body), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = { confirmDelete = false; vm.delete(onBack) },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { confirmDelete = false },
                destructive = true
            )
        }
    }
}

/** Display rows of a stored reference reading ("Heart rate" → "63 bpm", ...). */
internal fun referenceRows(context: android.content.Context, ref: VitalReference): List<Pair<String, String>> {
    val locale = Locale.getDefault()
    fun n(v: Double, decimals: Int = 0) = String.format(locale, "%.${decimals}f", v)
    val out = ArrayList<Pair<String, String>>()
    ref.heartRate?.let { out += context.getString(R.string.camvitals_reference_hr) to "${n(it)} ${context.getString(R.string.camvitals_unit_bpm)}" }
    ref.rmssd?.let { out += context.getString(R.string.camvitals_reference_rmssd) to "${n(it)} ${context.getString(R.string.camvitals_unit_ms)}" }
    ref.respiratoryRate?.let { out += context.getString(R.string.camvitals_reference_resp) to "${n(it, 1)} ${context.getString(R.string.camvitals_unit_per_min)}" }
    ref.spo2?.let { out += context.getString(R.string.camvitals_reference_spo2) to "${n(it)} ${context.getString(R.string.camvitals_unit_percent)}" }
    if (ref.systolic != null && ref.diastolic != null) {
        out += context.getString(R.string.camvitals_reference_bp) to "${n(ref.systolic)}/${n(ref.diastolic)} ${context.getString(R.string.camvitals_unit_mmhg)}"
    }
    ref.device?.takeIf { it.isNotBlank() }?.let { out += context.getString(R.string.camvitals_reference_device) to it }
    return out
}
