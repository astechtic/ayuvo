package com.ayuvo.health.ui.records

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ayuvo.health.R
import com.ayuvo.health.records.ingest.ImportItem
import com.ayuvo.health.records.ingest.ImportSpec
import com.ayuvo.health.records.model.ImportMethod
import com.ayuvo.health.records.model.RecordSource
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.components.InAppCameraCaptureDialog
import com.ayuvo.health.ui.theme.AppColors
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

enum class AddRecordAction { SCAN, CAMERA, PHOTOS, FILES, PDF, PASTE, NOTE }

/**
 * Every way to add a record, owning its own launchers so the Records empty state and the
 * header "+" share one implementation. Each action returns immediately; imports run on the
 * app scope via [onImport].
 */
class AddRecordLaunchers internal constructor(val launch: (AddRecordAction) -> Unit)

@Composable
fun rememberAddRecordLaunchers(onImport: (List<ImportItem>, ImportSpec) -> Unit): AddRecordLaunchers {
    val context = LocalContext.current
    var showCamera by remember { mutableStateOf(false) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    var showNote by rememberSaveable { mutableStateOf(false) }
    val scannerUnavailable = stringResource(R.string.records_scan_unavailable)
    val pickerFailed = stringResource(R.string.records_picker_failed)

    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf ?: return@rememberLauncherForActivityResult
        onImport(listOf(ImportItem.FromUri(pdf.uri)), ImportSpec(RecordSource.SCAN, ImportMethod.DOCUMENT_SCANNER))
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNotEmpty()) {
            onImport(uris.map { ImportItem.FromUri(it) }, ImportSpec(RecordSource.PHOTOS, ImportMethod.PHOTO_PICKER))
        }
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            onImport(uris.map { ImportItem.FromUri(it) }, ImportSpec(RecordSource.IMPORT, ImportMethod.FILE_PICKER))
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera = true
    }

    fun startScan() {
        val activity = context.findActivity()
        if (activity == null) {
            Toast.makeText(context, scannerUnavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(SCAN_PAGE_LIMIT)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        // The scanner module is delivered by Google Play services; without it (or Play services
        // itself) the task fails and the user gets a hint to use the camera or file picker.
        runCatching {
            GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                .addOnSuccessListener { sender ->
                    runCatching { scanner.launch(IntentSenderRequest.Builder(sender).build()) }
                        .onFailure { Toast.makeText(context, scannerUnavailable, Toast.LENGTH_SHORT).show() }
                }
                .addOnFailureListener { Toast.makeText(context, scannerUnavailable, Toast.LENGTH_SHORT).show() }
        }.onFailure { Toast.makeText(context, scannerUnavailable, Toast.LENGTH_SHORT).show() }
    }

    if (showCamera) {
        InAppCameraCaptureDialog(
            onCapture = { bytes ->
                showCamera = false
                onImport(listOf(ImportItem.FromBytes(bytes, null)), ImportSpec(RecordSource.CAMERA, ImportMethod.CAMERA))
            },
            onDismiss = { showCamera = false }
        )
    }
    if (showPaste) {
        PasteTextDialog(
            onDismiss = { showPaste = false },
            onSave = { text ->
                showPaste = false
                onImport(listOf(ImportItem.FromText(text)), ImportSpec(RecordSource.PASTE, ImportMethod.PASTE_TEXT))
            }
        )
    }
    if (showNote) {
        NoteEditorDialog(
            onDismiss = { showNote = false },
            onSave = { title, body ->
                showNote = false
                onImport(
                    listOf(ImportItem.FromText(body)),
                    ImportSpec(RecordSource.NOTE, ImportMethod.NOTE_EDITOR, userTitle = title)
                )
            }
        )
    }

    return AddRecordLaunchers { action ->
        runCatching {
            when (action) {
                AddRecordAction.SCAN -> startScan()
                AddRecordAction.CAMERA -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        showCamera = true
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                }
                AddRecordAction.PHOTOS -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                AddRecordAction.FILES -> documents.launch(arrayOf("application/pdf", "image/*", "text/plain", "text/markdown"))
                AddRecordAction.PDF -> documents.launch(arrayOf("application/pdf"))
                AddRecordAction.PASTE -> showPaste = true
                AddRecordAction.NOTE -> showNote = true
            }
        }.onFailure { Toast.makeText(context, pickerFailed, Toast.LENGTH_SHORT).show() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRecordSheet(onDismiss: () -> Unit, onAction: (AddRecordAction) -> Unit) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = if (isDark) Color(0xF2141416) else Color(0xFFFAF3EE)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.records_add_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val actions = listOf(
                Triple(AddRecordAction.SCAN, Icons.Filled.DocumentScanner, R.string.records_add_scan),
                Triple(AddRecordAction.CAMERA, Icons.Filled.PhotoCamera, R.string.records_add_camera),
                Triple(AddRecordAction.PHOTOS, Icons.Filled.PhotoLibrary, R.string.records_add_photos),
                Triple(AddRecordAction.FILES, Icons.Filled.FolderOpen, R.string.records_add_files),
                Triple(AddRecordAction.PDF, Icons.Filled.PictureAsPdf, R.string.records_add_pdf),
                Triple(AddRecordAction.PASTE, Icons.Filled.ContentPaste, R.string.records_add_paste),
                Triple(AddRecordAction.NOTE, Icons.Filled.EditNote, R.string.records_add_note)
            )
            actions.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (action, icon, label) ->
                        AddActionTile(
                            icon = icon,
                            label = stringResource(label),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onDismiss()
                                onAction(action)
                            }
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.IosShare, null, tint = AppColors.Calorie, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.records_add_receive_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                )
            }
        }
    }
}

@Composable
private fun AddActionTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    GlassSurface(
        modifier = modifier
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        cornerRadius = 18.dp,
        padding = 12.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = AppColors.Calorie, modifier = Modifier.size(24.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        }
    }
}

@Composable
private fun PasteTextDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    GlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.records_paste_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        GlassTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = stringResource(R.string.records_paste_placeholder),
            singleLine = false,
            minLines = 5,
            maxLines = 12
        )
        GlassTextButton(
            text = stringResource(R.string.records_paste_from_clipboard),
            onClick = {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
                val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!pasted.isNullOrEmpty()) text = if (text.isEmpty()) pasted else text + "\n" + pasted
            }
        )
        GlassDialogActions(
            primaryText = stringResource(R.string.action_save),
            onPrimary = { onSave(text) },
            primaryEnabled = text.isNotBlank(),
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun NoteEditorDialog(onDismiss: () -> Unit, onSave: (String?, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    GlassDialog(onDismissRequest = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.Notes, null, tint = AppColors.Calorie)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.records_note_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        GlassTextField(value = title, onValueChange = { title = it }, placeholder = stringResource(R.string.records_note_title_placeholder))
        GlassTextField(
            value = body,
            onValueChange = { body = it },
            placeholder = stringResource(R.string.records_note_body_placeholder),
            singleLine = false,
            minLines = 6,
            maxLines = 14
        )
        GlassDialogActions(
            primaryText = stringResource(R.string.action_save),
            onPrimary = { onSave(title.ifBlank { null }, body) },
            primaryEnabled = body.isNotBlank(),
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss
        )
    }
}

/** Empty-state call to action: Scan, Choose file, Paste text and the privacy note. */
@Composable
internal fun RecordsEmptyState(onAction: (AddRecordAction) -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier = modifier.fillMaxWidth(), cornerRadius = 24.dp, padding = 20.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.FolderOpen, null, tint = AppColors.Calorie, modifier = Modifier.size(44.dp))
            Text(
                stringResource(R.string.records_empty_title),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.records_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            listOf(
                Triple(AddRecordAction.SCAN, Icons.Filled.DocumentScanner, R.string.records_add_scan),
                Triple(AddRecordAction.FILES, Icons.Filled.FolderOpen, R.string.records_empty_choose_file),
                Triple(AddRecordAction.PASTE, Icons.Filled.ContentPaste, R.string.records_add_paste)
            ).forEach { (action, icon, label) ->
                AddActionRow(icon = icon, label = stringResource(label), onClick = { onAction(action) })
            }
            Text(
                stringResource(R.string.records_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AddActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    com.ayuvo.health.ui.components.GlassPrimaryButton(text = label, onClick = onClick) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private const val MAX_PICK = 20
private const val SCAN_PAGE_LIMIT = 50
