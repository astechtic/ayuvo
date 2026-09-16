package com.ayuvo.health.ui.records

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.records.backup.ArchiveImportResult
import com.ayuvo.health.records.backup.ArchiveProgress
import com.ayuvo.health.records.backup.RecordsArchiveFormat
import com.ayuvo.health.records.backup.RecordsBackupStatus
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class RecordsBackupUiState(
    val status: RecordsBackupStatus = RecordsBackupStatus(),
    val includeOriginals: Boolean = true,
    val busy: Boolean = false,
    val progress: String? = null,
    val archive: File? = null,
    val archiveBytes: Long = 0,
    val imported: ArchiveImportResult? = null,
    val driveSignedIn: Boolean = false,
    val driveTokenExpired: Boolean = false,
    val error: String? = null
)

/** Settings › Health Records › Backup & restore (docs/health-records.md §35, §36). */
class RecordsBackupViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(RecordsBackupUiState())
    val ui: StateFlow<RecordsBackupUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val status = container.recordsBackup.status()
                val signedIn = container.keyStore.cloudBackupAccessToken() != null
                _ui.update { it.copy(status = status, driveSignedIn = signedIn) }
            }
        }
    }

    fun setIncludeOriginals(on: Boolean) = _ui.update { it.copy(includeOriginals = on) }

    fun export() {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null, archive = null, imported = null) }
        viewModelScope.launch {
            runCatching {
                container.recordsBackup.export(_ui.value.includeOriginals) { p -> _ui.update { it.copy(progress = step(p)) } }
            }.onSuccess { result ->
                _ui.update { it.copy(busy = false, progress = null, archive = result.file, archiveBytes = result.archiveBytes) }
                refresh()
            }.onFailure { e ->
                _ui.update { it.copy(busy = false, progress = null, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun import(uri: Uri, mode: RecordsArchiveFormat.ImportMode) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null, imported = null, archive = null) }
        viewModelScope.launch {
            runCatching {
                container.recordsBackup.importFrom(uri, mode) { p -> _ui.update { it.copy(progress = step(p)) } }
            }.onSuccess { result ->
                _ui.update { it.copy(busy = false, progress = null, imported = result) }
                refresh()
            }.onFailure { e ->
                _ui.update { it.copy(busy = false, progress = null, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun setDriveEnabled(on: Boolean) {
        viewModelScope.launch {
            runCatching { container.recordsDriveBackup.setEnabled(on) }
            refresh()
            if (on) driveBackupNow()
        }
    }

    fun driveBackupNow() {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null, driveTokenExpired = false) }
        viewModelScope.launch {
            container.recordsDriveBackup.backupNow { sent, total ->
                _ui.update { it.copy(progress = "${sent * 100 / total.coerceAtLeast(1)}%") }
            }.onFailure { e ->
                val expired = e is com.ayuvo.health.backup.DriveCloudBackupClient.DriveTokenExpiredException
                _ui.update { it.copy(error = e.message ?: e.javaClass.simpleName, driveTokenExpired = expired) }
            }
            _ui.update { it.copy(busy = false, progress = null) }
            refresh()
        }
    }

    fun driveRestore(mode: RecordsArchiveFormat.ImportMode) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null, imported = null) }
        viewModelScope.launch {
            container.recordsDriveBackup.restore(mode) { p -> _ui.update { it.copy(progress = step(p)) } }
                .onSuccess { result -> _ui.update { it.copy(imported = result) } }
                .onFailure { e -> _ui.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
            _ui.update { it.copy(busy = false, progress = null) }
            refresh()
        }
    }

    private fun step(progress: ArchiveProgress): String =
        if (progress.total > 0) "${progress.step} ${progress.done}/${progress.total}" else progress.step

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordsBackupViewModel(container) as T
    }
}

@Composable
fun RecordsBackupScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: RecordsBackupViewModel = viewModel(key = "records-backup", factory = RecordsBackupViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var driveRestoreMode by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.records_title), tint = AppColors.Calorie) }
            Text(stringResource(R.string.records_backup_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "status") {
                BackupCard {
                    val last = ui.status.lastArchiveMs
                    Text(
                        if (last == null) stringResource(R.string.records_backup_never)
                        else stringResource(
                            R.string.records_backup_last,
                            formatMoment(last),
                            formatBytes(ui.status.lastArchiveSize),
                            ui.status.lastArchiveRecords
                        ),
                        fontSize = 13.sp
                    )
                    ui.status.lastRestoreMs?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.records_backup_last_restore, formatMoment(it)),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    if (ui.busy) {
                        Spacer(Modifier.height(6.dp))
                        Text(ui.progress ?: stringResource(R.string.records_backup_working), fontSize = 12.sp, color = AppColors.Calorie)
                    }
                    ui.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.records_backup_failed, it), fontSize = 12.sp, color = AppColors.Calorie)
                    }
                }
            }

            item(key = "export") {
                BackupCard {
                    Text(stringResource(R.string.records_backup_create), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setIncludeOriginals(!ui.includeOriginals) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = ui.includeOriginals, onCheckedChange = { vm.setIncludeOriginals(it) })
                        Column {
                            Text(stringResource(R.string.records_backup_include_files), fontSize = 14.sp)
                            Text(
                                stringResource(R.string.records_backup_include_files_note),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    GlassTextButton(
                        text = stringResource(R.string.records_backup_create),
                        onClick = { vm.export() },
                        enabled = !ui.busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val archive = ui.archive
                    if (archive != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.records_backup_exported, formatBytes(ui.archiveBytes)), fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        GlassTextButton(
                            text = stringResource(R.string.records_backup_share_archive),
                            onClick = { shareArchive(context, archive) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item(key = "restore") {
                BackupCard {
                    Text(stringResource(R.string.records_backup_restore), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    GlassTextButton(
                        text = stringResource(R.string.records_backup_restore),
                        onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        enabled = !ui.busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ui.imported?.let { result ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.records_backup_imported, result.importedRecords, result.skippedRecords),
                            fontSize = 12.sp
                        )
                        for (warning in result.warnings) {
                            Text(warning.text, fontSize = 12.sp, color = AppColors.Calorie)
                        }
                        if (result.missingFiles > 0) {
                            Text(
                                stringResource(R.string.records_backup_missing_files, result.missingFiles),
                                fontSize = 12.sp,
                                color = AppColors.Calorie
                            )
                        }
                    }
                }
            }

            item(key = "drive") {
                BackupCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.records_backup_drive_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.records_backup_drive_subtitle),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                        Switch(checked = ui.status.driveEnabled, onCheckedChange = { vm.setDriveEnabled(it) }, enabled = !ui.busy)
                    }
                    if (ui.status.driveEnabled && !ui.driveSignedIn) {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.records_backup_drive_signin), fontSize = 12.sp, color = AppColors.Calorie)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        ui.status.driveLastMs?.let { stringResource(R.string.records_backup_drive_last, formatMoment(it)) }
                            ?: stringResource(R.string.records_backup_drive_never),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    if (ui.status.driveEnabled) {
                        Spacer(Modifier.height(6.dp))
                        GlassTextButton(
                            text = stringResource(R.string.records_backup_drive_backup_now),
                            onClick = { vm.driveBackupNow() },
                            enabled = !ui.busy && ui.driveSignedIn,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        GlassTextButton(
                            text = stringResource(R.string.records_backup_drive_restore),
                            onClick = { driveRestoreMode = true },
                            enabled = !ui.busy && ui.driveSignedIn,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (ui.driveTokenExpired) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.records_backup_drive_reauth),
                            fontSize = 12.sp,
                            color = AppColors.Calorie
                        )
                    }
                }
            }
        }
    }

    val uri = pendingUri
    if (uri != null) {
        ModePicker(
            onPick = { mode ->
                pendingUri = null
                vm.import(uri, mode)
            },
            onDismiss = { pendingUri = null }
        )
    }
    if (driveRestoreMode) {
        ModePicker(
            onPick = { mode ->
                driveRestoreMode = false
                vm.driveRestore(mode)
            },
            onDismiss = { driveRestoreMode = false }
        )
    }
}

/** Merge or Replace, with the §35 consequence spelled out before anything is deleted. */
@Composable
private fun ModePicker(onPick: (RecordsArchiveFormat.ImportMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.records_backup_restore)) },
        text = {
            Column {
                Text(stringResource(R.string.records_backup_mode_merge_note), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.records_backup_mode_replace_note), fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(RecordsArchiveFormat.ImportMode.MERGE) }) {
                Text(stringResource(R.string.records_backup_mode_merge))
            }
        },
        dismissButton = {
            TextButton(onClick = { onPick(RecordsArchiveFormat.ImportMode.REPLACE) }) {
                Text(stringResource(R.string.records_backup_mode_replace))
            }
        }
    )
}

@Composable
private fun BackupCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), padding = 0.dp) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

private fun shareArchive(context: android.content.Context, archive: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

private val momentFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

internal fun formatMoment(ms: Long): String =
    runCatching { momentFormatter.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())) }.getOrElse { "" }
