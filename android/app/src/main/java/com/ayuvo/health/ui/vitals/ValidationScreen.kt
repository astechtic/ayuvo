package com.ayuvo.health.ui.vitals

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.BuildConfig
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.EmptyState
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.vitals.camera.VitalsMode
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.session.VitalsValidation
import com.ayuvo.health.vitals.storage.VitalScanRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.time.Instant
import java.util.Locale

data class ValidationUi(
    val loading: Boolean = true,
    val rows: List<VitalsValidation.Row> = emptyList(),
    val scans: List<VitalScanRecord> = emptyList(),
    val referenced: Int = 0
)

class ValidationViewModel(private val container: AppContainer) : ViewModel() {
    val cfg: VitalsConfig = container.vitalsConfig
    private val _ui = MutableStateFlow(ValidationUi())
    val ui: StateFlow<ValidationUi> = _ui

    init {
        viewModelScope.launch { container.vitalScans.revision.collect { reload() } }
    }

    private suspend fun reload() {
        val scans = runCatching { container.vitalScans.scans(null, 0L, Long.MAX_VALUE) }.getOrDefault(emptyList())
        _ui.value = withContext(Dispatchers.Default) {
            ValidationUi(false, VitalsValidation.rows(scans, cfg), scans, VitalsValidation.withReference(scans).size)
        }
    }

    /** Writes `ayuvo-vitals-validation.json` (only scans with a reference) into the shareable cache folder. */
    suspend fun writeDataset(dir: File): File = withContext(Dispatchers.IO) {
        val doc = VitalsValidation.dataset(_ui.value.scans, Instant.now().toString(), BuildConfig.VERSION_NAME)
        dir.mkdirs()
        File(dir, VitalsValidation.FILE_NAME).apply {
            writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), doc) + "\n")
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ValidationViewModel(container) as T
    }
}

/**
 * Validation (docs/camera-vitals.md §6, §8): `validation_stats` per mode × metric over the user's own reference pairs,
 * and *Export validation dataset* for `scripts/vitals_eval.py` through the share sheet.
 */
@Composable
fun ValidationScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: ValidationViewModel = viewModel(factory = ValidationViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareTitle = stringResource(R.string.camvitals_validation_export)
    val failed = stringResource(R.string.camvitals_validation_export_failed)

    fun export() {
        scope.launch {
            runCatching {
                val file = vm.writeDataset(File(context.cacheDir, "capture"))
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, shareTitle))
            }.onFailure { Toast.makeText(context, failed, Toast.LENGTH_SHORT).show() }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(R.string.camvitals_validation_title), onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("vitals.validation"),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.camvitals_validation_intro, ui.referenced),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    fontSize = 14.sp,
                    color = AyuvoColors.secondaryLabel()
                )
            }
            if (!ui.loading && ui.rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Filled.Insights,
                        title = stringResource(R.string.camvitals_validation_empty_title),
                        message = stringResource(R.string.camvitals_validation_empty_body)
                    )
                }
            }
            for (mode in VitalsValidation.MODES) {
                val rows = ui.rows.filter { it.mode == mode }
                if (rows.isEmpty()) continue
                val m = VitalsMode.fromId(mode) ?: VitalsMode.FINGER
                item(key = "mode-$mode") {
                    Text(
                        VitalsText.modeName(context, m),
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                rows.forEach { row ->
                    item(key = "row-$mode-${row.metric}") { ValidationCard(row, vm.cfg) }
                }
            }
            item(key = "export") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::export,
                        enabled = ui.referenced > 0,
                        modifier = Modifier.fillMaxWidth().height(50.dp).testTag("vitals.validation.export"),
                        colors = ButtonDefaults.buttonColors(containerColor = vitalsTint())
                    ) { Text(stringResource(R.string.camvitals_validation_export), fontWeight = FontWeight.SemiBold) }
                    Text(
                        stringResource(R.string.camvitals_validation_export_footer),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = AyuvoColors.secondaryLabel()
                    )
                }
            }
        }
    }
}

@Composable
private fun ValidationCard(row: VitalsValidation.Row, cfg: VitalsConfig) {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val dash = stringResource(R.string.camvitals_dash)
    fun f(v: Double?, d: Int = 1) = v?.let { String.format(locale, "%.${d}f", it) } ?: dash
    val title = if (row.metric == "blood_pressure") stringResource(R.string.camvitals_validation_bp_systolic)
    else VitalsText.metricTitle(context, cfg, row.metric)
    Column {
        Text(
            title.uppercase(),
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            fontSize = 12.sp,
            color = AyuvoColors.secondaryLabel()
        )
        SurfaceCard(modifier = Modifier.testTag("vitals.validation.${row.mode}.${row.metric}")) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatLine(stringResource(R.string.camvitals_validation_n), row.n.toString())
                StatLine(stringResource(R.string.camvitals_validation_mae), f(row.mae))
                StatLine(stringResource(R.string.camvitals_validation_rmse), f(row.rmse))
                StatLine(stringResource(R.string.camvitals_validation_bias), f(row.bias))
                StatLine(
                    stringResource(R.string.camvitals_validation_loa),
                    if (row.loaLow != null && row.loaHigh != null) "${f(row.loaLow)} … ${f(row.loaHigh)}" else dash
                )
                StatLine(stringResource(R.string.camvitals_validation_r), f(row.r, 3))
                StatLine(
                    stringResource(R.string.camvitals_validation_failure),
                    row.failureRate?.let { String.format(locale, "%.0f%%", it * 100.0) } ?: dash
                )
                if (row.byConfidence.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.camvitals_validation_by_confidence), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    row.byConfidence.forEach { (label, n, mae) ->
                        StatLine(
                            VitalsText.confidence(context, label) ?: stringResource(R.string.camvitals_validation_confidence_unknown),
                            stringResource(R.string.camvitals_validation_conf_value, f(mae), n)
                        )
                    }
                }
            }
        }
        Text(
            stringResource(R.string.camvitals_validation_units, VitalsText.unit(context, runCatching { cfg.metric(row.metric).unit }.getOrDefault(""))),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            fontSize = 12.sp,
            color = AyuvoColors.secondaryLabel()
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}
