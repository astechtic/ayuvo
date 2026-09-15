package com.ayuvo.health.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.core.content.FileProvider
import com.ayuvo.health.AppContainer
import com.ayuvo.health.BuildConfig
import com.ayuvo.health.R
import com.ayuvo.health.export.HealthDataExporter
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Export the Health Data mirror as a `ayuvo-health-data` zip: Share (FileProvider) or Save to file (SAF). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportHealthDataSheet(container: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.export_failed)
    val doneFormat = stringResource(R.string.health_export_done)
    val title = stringResource(R.string.health_settings_export)
    val fileName = remember { "Ayuvo-Health-Data-${LocalDate.now()}.zip" }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        HealthDataExporter(container.healthStore).write(out, BuildConfig.VERSION_NAME)
                    } ?: error(failed)
                }
            }
            busy = false
            status = result.fold({ String.format(doneFormat, it.sampleCount) }, { it.localizedMessage ?: failed })
        }
    }

    fun share() {
        scope.launch {
            busy = true
            status = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "capture").apply { mkdirs() }
                    val file = File(dir, fileName)
                    file.outputStream().use { out -> HealthDataExporter(container.healthStore).write(out, BuildConfig.VERSION_NAME) }
                    file
                }
            }
            busy = false
            result.onSuccess { file ->
                runCatching {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, title))
                    onDismiss()
                }.onFailure { status = it.localizedMessage ?: failed }
            }.onFailure { status = it.localizedMessage ?: failed }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = if (isDark) Color(0xF2141416) else Color(0xFFFAF3EE),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.health_export_body),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
                fontSize = 14.sp
            )
            status?.let { Text(it, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), fontSize = 13.sp) }
            ActionButton(stringResource(R.string.health_export_share), enabled = !busy, loading = busy, primary = true) { share() }
            ActionButton(stringResource(R.string.health_export_save), enabled = !busy, loading = false, primary = false) { saveLauncher.launch(fileName) }
        }
    }
}

@Composable
internal fun ActionButton(text: String, enabled: Boolean, loading: Boolean, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) AppColors.Calorie.copy(alpha = if (enabled) 1f else 0.28f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = if (primary) Color.White else AppColors.Calorie, strokeWidth = 2.dp, modifier = Modifier.padding(1.dp))
        } else {
            Text(text, color = if (primary) Color.White else AppColors.Calorie, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
