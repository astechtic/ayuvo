package com.ayuvo.health.ui.vitals

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.vitals.camera.VitalsMode
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.session.VitalsCompareFlow
import com.ayuvo.health.vitals.session.validValue
import com.ayuvo.health.vitals.storage.VitalScanRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class CompareUi(
    val loading: Boolean = true,
    val finger: VitalScanRecord? = null,
    val face: VitalScanRecord? = null,
    /** Engine `compare`: {hr_diff, rmssd_diff, ibi_mean_diff, status}. */
    val result: JsonObject? = null
) {
    val status: String get() = VitalResultUi.str(result?.get("status")) ?: STATUS_INCOMPLETE

    companion object {
        const val STATUS_CONSISTENT = "consistent"
        const val STATUS_INCONSISTENT = "inconsistent"
        const val STATUS_INCOMPLETE = "incomplete"
    }
}

class CompareViewModel(private val container: AppContainer, private val sessionId: String) : ViewModel() {
    val cfg: VitalsConfig = container.vitalsConfig
    private val _ui = MutableStateFlow(CompareUi())
    val ui: StateFlow<CompareUi> = _ui

    init {
        viewModelScope.launch {
            val scans = runCatching { container.vitalScans.scansInSession(sessionId) }.getOrDefault(emptyList())
            val finger = scans.lastOrNull { it.mode == VitalScanRecord.MODE_FINGER }
            val face = scans.lastOrNull { it.mode == VitalScanRecord.MODE_FACE }
            _ui.value = CompareUi(false, finger, face, VitalsCompareFlow.compare(finger, face, cfg))
        }
    }

    class Factory(private val container: AppContainer, private val sessionId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CompareViewModel(container, sessionId) as T
    }
}

/** Status text of the compare result; never names a preferred measurement. */
internal fun compareStatusRes(status: String): Int = when (status) {
    CompareUi.STATUS_CONSISTENT -> R.string.camvitals_compare_consistent
    CompareUi.STATUS_INCONSISTENT -> R.string.camvitals_compare_inconsistent
    else -> R.string.camvitals_compare_incomplete
}

/**
 * Compare finger & face (docs/camera-vitals.md §6, §7.1 "Compare"): both values, their differences and the engine
 * `compare` status. Neither result is preferred; an inconsistent pair asks for a repeat.
 */
@Composable
fun CompareScreen(
    container: AppContainer,
    sessionId: String,
    onBack: () -> Unit,
    onOpenScan: (String) -> Unit,
    onRepeat: () -> Unit
) {
    val vm: CompareViewModel = viewModel(key = "vitals-compare-$sessionId", factory = CompareViewModel.Factory(container, sessionId))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(R.string.camvitals_compare_title), onBack = onBack) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AyuvoSpacing.ScreenH, vertical = 8.dp)
                .testTag("vitals.compare"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (ui.loading) return@Column
            val status = ui.status
            val (bg, fg) = when (status) {
                CompareUi.STATUS_CONSISTENT -> Color(0xFF34C759).copy(alpha = 0.14f) to MaterialTheme.colorScheme.onSurface
                CompareUi.STATUS_INCONSISTENT -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                stringResource(compareStatusRes(status)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(bg)
                    .padding(16.dp)
                    .testTag("vitals.compare.status.$status"),
                color = fg,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            SurfaceCard(modifier = Modifier.testTag("vitals.compare.table")) {
                CompareTable(ui, vm.cfg)
            }
            Text(
                stringResource(R.string.camvitals_compare_footer, vm.cfg.compare.maxHrDiffBpm.toInt(), vm.cfg.compare.maxRmssdDiffMs.toInt()),
                modifier = Modifier.padding(horizontal = 16.dp),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = AyuvoColors.secondaryLabel()
            )
            InsetGroup(header = stringResource(R.string.camvitals_compare_scans)) {
                listOf(ui.finger to VitalsMode.FINGER, ui.face to VitalsMode.FACE).forEach { (r, mode) ->
                    if (r != null) {
                        row {
                            GroupRow(
                                title = VitalsText.modeName(context, mode),
                                subtitle = fmt.format(Instant.ofEpochMilli(r.startMs).atZone(zone)),
                                icon = modeIcon(mode), iconTint = vitalsTint(),
                                modifier = Modifier.testTag("vitals.compare.open.${mode.id}"),
                                onClick = { onOpenScan(r.id) }
                            )
                        }
                    }
                }
            }
            if (status != CompareUi.STATUS_CONSISTENT) {
                Button(
                    onClick = onRepeat,
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("vitals.compare.repeat"),
                    colors = ButtonDefaults.buttonColors(containerColor = vitalsTint())
                ) { Text(stringResource(R.string.camvitals_compare_repeat), fontWeight = FontWeight.SemiBold) }
            }
            Text(
                VitalsText.disclaimer(context, vm.cfg),
                modifier = Modifier.padding(horizontal = 16.dp),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = AyuvoColors.secondaryLabel()
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CompareTable(ui: CompareUi, cfg: VitalsConfig) {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val dash = stringResource(R.string.camvitals_dash)
    fun cell(v: Double?) = v?.let { String.format(locale, "%.0f", it) } ?: dash
    fun diff(key: String) = VitalResultUi.num(ui.result?.get(key))?.let { String.format(locale, "%.1f", it) } ?: dash
    val rows = listOf(
        Triple("heart_rate", R.string.camvitals_unit_bpm, "hr_diff"),
        Triple("hrv_rmssd", R.string.camvitals_unit_ms, "rmssd_diff"),
        Triple("ibi_mean", R.string.camvitals_unit_ms, "ibi_mean_diff")
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            Spacer(Modifier.weight(1.5f))
            listOf(R.string.camvitals_mode_finger, R.string.camvitals_mode_face, R.string.camvitals_compare_difference).forEach {
                Text(stringResource(it), fontSize = 12.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
        }
        rows.forEach { (metric, unit, key) ->
            Row(Modifier.testTag("vitals.compare.row.$metric")) {
                Column(Modifier.weight(1.5f)) {
                    Text(VitalsText.metricTitle(context, cfg, metric), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(unit), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
                }
                Text(cell(ui.finger?.validValue(metric)), fontSize = 15.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text(cell(ui.face?.validValue(metric)), fontSize = 15.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text(diff(key), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
        }
    }
}
