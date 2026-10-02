package com.ayuvo.health.ui.vitals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.MonitorHeart
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
import com.ayuvo.health.vitals.camera.CameraController
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.storage.VitalCalibration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class CalibrationUi(
    val loading: Boolean = true,
    val experimentalEnabled: Boolean = false,
    val researchEnabled: Boolean = false,
    val deviceModel: String = "",
    val spo2: List<VitalCalibration> = emptyList(),
    val bp: List<VitalCalibration> = emptyList()
)

class CalibrationViewModel(private val container: AppContainer) : ViewModel() {
    val cfg: VitalsConfig = container.vitalsConfig
    private val _ui = MutableStateFlow(CalibrationUi())
    val ui: StateFlow<CalibrationUi> = _ui

    init {
        viewModelScope.launch { container.vitalScans.revision.collect { reload() } }
    }

    private suspend fun reload() {
        val all = runCatching { container.vitalScans.allCalibrations() }.getOrDefault(emptyList())
        _ui.value = CalibrationUi(
            loading = false,
            experimentalEnabled = container.prefs.vitalsExperimentalEnabled.first(),
            researchEnabled = container.prefs.vitalsResearchEnabled.first(),
            deviceModel = CameraController.deviceModel,
            spo2 = all.filter { it.kind == VitalCalibration.KIND_SPO2 }.sortedByDescending { it.tMs },
            bp = all.filter { it.kind == VitalCalibration.KIND_BP }.sortedByDescending { it.tMs }
        )
    }

    fun delete(id: String) {
        viewModelScope.launch { runCatching { container.vitalScans.deleteCalibration(id) } }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CalibrationViewModel(container) as T
    }
}

private val calJson = Json { ignoreUnknownKeys = true }

private fun calNum(text: String, key: String): Double? =
    runCatching { VitalResultUi.num((calJson.parseToJsonElement(text) as JsonObject)[key]) }.getOrNull()

/**
 * Personal calibrations (docs/camera-vitals.md §4, §7.1): what SpO₂ and BP calibrations need, the stored pairs and
 * delete. Calibrations are added from the reference reading sheet of a finger scan.
 */
@Composable
fun CalibrationScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: CalibrationViewModel = viewModel(factory = CalibrationViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val cfg = vm.cfg
    val zone = remember { ZoneId.systemDefault() }
    val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()) }
    var confirm by remember { mutableStateOf<VitalCalibration?>(null) }
    val locale = Locale.getDefault()
    val maxAgeMs = (cfg.research.bpCalibrationMaxAgeDays * 86_400_000.0).toLong()
    val now = System.currentTimeMillis()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(R.string.camvitals_calibration_title), onBack = onBack) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AyuvoSpacing.ScreenH, vertical = 8.dp)
                .testTag("vitals.calibration"),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            if (ui.loading) return@Column
            Text(stringResource(R.string.camvitals_calibration_intro), fontSize = 14.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(horizontal = 16.dp))
            // SpO₂ (experimental): per phone model.
            val spo2Here = ui.spo2.count { it.deviceModel == ui.deviceModel }
            InsetGroup(
                header = stringResource(R.string.camvitals_calibration_spo2_header),
                footer = stringResource(
                    R.string.camvitals_calibration_spo2_footer,
                    cfg.research.spo2MinCalibrations.toInt(), ui.deviceModel, spo2Here
                ) + if (ui.experimentalEnabled) "" else "\n" + stringResource(R.string.camvitals_calibration_spo2_off),
                modifier = Modifier.testTag("vitals.calibration.spo2")
            ) {
                if (ui.spo2.isEmpty()) {
                    row { GroupRow(title = stringResource(R.string.camvitals_calibration_none), trailing = RowTrailing.None) }
                }
                ui.spo2.forEach { c ->
                    row {
                        GroupRow(
                            title = calNum(c.referenceJson, "spo2")?.let { stringResource(R.string.camvitals_calibration_spo2_row, String.format(locale, "%.0f", it)) }
                                ?: stringResource(R.string.camvitals_dash),
                            subtitle = fmt.format(Instant.ofEpochMilli(c.tMs).atZone(zone)) + " · " + c.deviceModel +
                                (calNum(c.featuresJson, "ratio")?.let { " · R " + String.format(locale, "%.3f", it) } ?: ""),
                            icon = Icons.Filled.Bloodtype, iconTint = vitalsTint(),
                            trailing = RowTrailing.None,
                            modifier = Modifier.testTag("vitals.calibration.row"),
                            onClick = { confirm = c }
                        )
                    }
                }
            }
            // BP (research): per user, expire after bp_calibration_max_age_days.
            val bpValid = ui.bp.count { now - it.tMs <= maxAgeMs && (calNum(it.referenceJson, "scan_gap_min") ?: Double.MAX_VALUE) <= cfg.research.bpCalibrationMaxGapMin }
            InsetGroup(
                header = stringResource(R.string.camvitals_calibration_bp_header),
                footer = stringResource(
                    R.string.camvitals_calibration_bp_footer,
                    cfg.research.bpMinCalibrations.toInt(), cfg.research.bpCalibrationMaxGapMin.toInt(),
                    cfg.research.bpCalibrationMaxAgeDays.toInt(), bpValid
                ) + if (ui.researchEnabled) "" else "\n" + stringResource(R.string.camvitals_calibration_bp_off),
                modifier = Modifier.testTag("vitals.calibration.bp")
            ) {
                if (ui.bp.isEmpty()) {
                    row { GroupRow(title = stringResource(R.string.camvitals_calibration_none), trailing = RowTrailing.None) }
                }
                ui.bp.forEach { c ->
                    val sbp = calNum(c.referenceJson, "sbp")
                    val dbp = calNum(c.referenceJson, "dbp")
                    val gap = calNum(c.referenceJson, "scan_gap_min")
                    val expired = now - c.tMs > maxAgeMs
                    row {
                        GroupRow(
                            title = if (sbp != null && dbp != null) {
                                stringResource(R.string.camvitals_calibration_bp_row, String.format(locale, "%.0f/%.0f", sbp, dbp))
                            } else stringResource(R.string.camvitals_dash),
                            subtitle = fmt.format(Instant.ofEpochMilli(c.tMs).atZone(zone)) +
                                (gap?.let { " · " + stringResource(R.string.camvitals_calibration_gap, String.format(locale, "%.0f", it)) } ?: "") +
                                (if (expired) " · " + stringResource(R.string.camvitals_calibration_expired) else ""),
                            icon = Icons.Filled.MonitorHeart, iconTint = vitalsTint(),
                            trailing = RowTrailing.None,
                            modifier = Modifier.testTag("vitals.calibration.row"),
                            onClick = { confirm = c }
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.camvitals_calibration_how),
                modifier = Modifier.padding(horizontal = 16.dp),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = AyuvoColors.secondaryLabel()
            )
            Spacer(Modifier.height(16.dp))
        }
    }
    confirm?.let { c ->
        GlassDialog(onDismissRequest = { confirm = null }) {
            Text(stringResource(R.string.camvitals_calibration_delete_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.camvitals_calibration_delete_body), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = { vm.delete(c.id); confirm = null },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { confirm = null },
                destructive = true
            )
        }
    }
}
