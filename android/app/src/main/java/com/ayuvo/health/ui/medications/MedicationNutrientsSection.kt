package com.ayuvo.health.ui.medications

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ayuvo.health.R
import com.ayuvo.health.medications.model.MedicationDraft
import com.ayuvo.health.medications.model.NutrientInputRow
import com.ayuvo.health.nutrients.LabelItem
import com.ayuvo.health.nutrients.LabelParseResult
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.nutrients.NutrientFormat
import com.ayuvo.health.nutrients.Nutrients
import com.ayuvo.health.ui.components.GlassColumn
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.components.InAppCameraCaptureDialog
import com.ayuvo.health.ui.components.OptionPickerSheet
import com.ayuvo.health.ui.design.AyuvoColors
import java.io.ByteArrayOutputStream

/** Display label of an input unit (`iu` → "IU"). */
@Composable
private fun unitLabel(unit: String): String = when (unit) {
    "iu" -> stringResource(R.string.nutrients_unit_iu)
    "mcg" -> stringResource(R.string.unit_mcg)
    "mg" -> stringResource(R.string.unit_mg)
    else -> stringResource(R.string.unit_g)
}

@Composable
private fun formLabel(form: String): String = stringResource(
    when (form) {
        "natural" -> R.string.nutrients_form_natural
        "synthetic" -> R.string.nutrients_form_synthetic
        "retinol" -> R.string.nutrients_form_retinol
        "supplement_beta_carotene" -> R.string.nutrients_form_supplement_beta_carotene
        "food_beta_carotene" -> R.string.nutrients_form_food_beta_carotene
        "food_alpha_carotene_beta_cryptoxanthin" -> R.string.nutrients_form_food_alpha_carotene
        else -> R.string.nutrients_form_unknown
    }
)

@Composable
private fun convertErrorText(code: String?): String? = when (code) {
    null -> null
    "unknown_nutrient" -> stringResource(R.string.nutrients_error_pick_nutrient)
    "invalid_amount" -> stringResource(R.string.nutrients_error_amount)
    "iu_not_supported", "unsupported_unit" -> stringResource(R.string.nutrients_error_unit)
    "form_required", "unknown_form" -> stringResource(R.string.nutrients_error_form)
    else -> stringResource(R.string.nutrients_error_amount)
}

/**
 * "Nutrients (for supplements)" (docs/medications.md §21, docs/nutrients.md §6-§8): rows of
 * nutrient, amount and unit per ONE dose unit, converted to the canonical unit on save, plus
 * "Get nutrients with AI" from a label photo or the name and strength (reviewed, never auto-saved).
 */
@Composable
internal fun MedicationNutrientsSection(
    draft: MedicationDraft,
    nutrientError: String?,
    onChange: ((MedicationDraft) -> MedicationDraft) -> Unit,
    aiBusy: Boolean,
    aiError: String?,
    onAiFromText: () -> Unit,
    onAiFromPhoto: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    var pickingKeyFor by remember { mutableStateOf<String?>(null) }
    var pickingUnitFor by remember { mutableStateOf<String?>(null) }
    var pickingFormFor by remember { mutableStateOf<String?>(null) }
    var aiMenu by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    val doseUnit = stringResource(draft.doseUnit.labelRes())
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
                }
            }.getOrNull()
            if (bytes != null) onAiFromPhoto(bytes)
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera = true
    }
    fun updateRow(id: String, transform: (NutrientInputRow) -> NutrientInputRow) =
        onChange { d -> d.copy(nutrients = d.nutrients.map { if (it.id == id) transform(it) else it }) }

    GlassColumn(Modifier.fillMaxWidth().testTag("medication.nutrients"), cornerRadius = 22.dp, padding = 16.dp) {
        Text(stringResource(R.string.nutrients_section_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.nutrients_section_hint, doseUnit),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            draft.nutrients.forEach { row ->
                val key = row.key
                val converted = row.convert()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassTextButton(
                            text = key?.let { stringResource(NutrientFields.nameRes(it)) } ?: stringResource(R.string.nutrients_choose_nutrient),
                            onClick = { pickingKeyFor = row.id },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onChange { d -> d.copy(nutrients = d.nutrients.filterNot { it.id == row.id }) } }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.nutrients_remove_row), modifier = Modifier.size(18.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassTextField(
                            value = row.amount,
                            onValueChange = { v -> updateRow(row.id) { it.copy(amount = v.take(12)) } },
                            modifier = Modifier.weight(1f),
                            placeholder = stringResource(R.string.nutrients_amount_hint),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        GlassTextButton(text = unitLabel(row.unit), onClick = { pickingUnitFor = row.id })
                        Text(stringResource(R.string.nutrients_per_unit, doseUnit), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
                    }
                    if (key != null && row.unit == "iu" && Nutrients.iuForms(key).isNotEmpty()) {
                        GlassTextButton(
                            text = row.form?.let { formLabel(it) } ?: stringResource(R.string.nutrients_choose_form),
                            onClick = { pickingFormFor = row.id },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    val error = if (row.amount.isBlank() && key != null) null else convertErrorText(converted.error)
                    if (error != null) {
                        Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp))
                    } else if (converted.ok && (row.unit != converted.unit || row.form != null)) {
                        Text(
                            stringResource(R.string.nutrients_saved_as, NutrientFormat.withUnit(converted.amount, converted.unit.orEmpty())),
                            fontSize = 12.sp,
                            color = AyuvoColors.secondaryLabel(),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
            nutrientError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassTextButton(
                    text = stringResource(R.string.nutrients_add_row),
                    onClick = { onChange { d -> d.copy(nutrients = d.nutrients + NutrientInputRow()) } }
                )
                Box {
                    if (aiBusy) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.nutrients_ai_reading), fontSize = 14.sp)
                        }
                    } else {
                        GlassTextButton(
                            text = stringResource(R.string.nutrients_ai_button),
                            onClick = { aiMenu = true },
                            modifier = Modifier.testTag("medication.nutrients.ai")
                        )
                    }
                    DropdownMenu(expanded = aiMenu, onDismissRequest = { aiMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.nutrients_ai_from_camera)) }, onClick = {
                            aiMenu = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCamera = true
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.nutrients_ai_from_gallery)) }, onClick = {
                            aiMenu = false
                            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.nutrients_ai_from_text)) }, onClick = {
                            aiMenu = false
                            onAiFromText()
                        })
                    }
                }
            }
            aiError?.let { code ->
                Text(
                    when (code) {
                        MedicationEditorViewModel.AI_SETUP -> stringResource(R.string.nutrients_ai_setup)
                        MedicationEditorViewModel.AI_EMPTY -> stringResource(R.string.nutrients_ai_empty)
                        MedicationEditorViewModel.AI_FAILED -> stringResource(R.string.nutrients_ai_failed)
                        else -> code
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            Text(
                stringResource(R.string.nutrients_section_retroactive),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }

    pickingKeyFor?.let { id ->
        val used = draft.nutrients.filter { it.id != id }.mapNotNull { it.key }.toSet()
        OptionPickerSheet(
            title = stringResource(R.string.nutrients_choose_nutrient),
            items = NutrientFields.REFERENCE_KEYS.filter { it !in used },
            label = { stringResource(NutrientFields.nameRes(it)) },
            selected = { k -> draft.nutrients.firstOrNull { it.id == id }?.key == k },
            onSelect = { k ->
                pickingKeyFor = null
                updateRow(id) { r ->
                    val unit = if (r.unit in Nutrients.inputUnits(k)) r.unit else NutrientFields.unit(k)
                    r.copy(key = k, unit = if (r.key == null) NutrientFields.unit(k) else unit, form = null)
                }
            },
            onDismiss = { pickingKeyFor = null }
        )
    }
    pickingUnitFor?.let { id ->
        val row = draft.nutrients.firstOrNull { it.id == id }
        OptionPickerSheet(
            title = stringResource(R.string.nutrients_choose_unit),
            items = row?.key?.let { Nutrients.inputUnits(it) } ?: listOf("g", "mg", "mcg"),
            label = { unitLabel(it) },
            selected = { it == row?.unit },
            onSelect = { u -> pickingUnitFor = null; updateRow(id) { it.copy(unit = u, form = if (u == "iu") it.form else null) } },
            onDismiss = { pickingUnitFor = null }
        )
    }
    pickingFormFor?.let { id ->
        val row = draft.nutrients.firstOrNull { it.id == id }
        OptionPickerSheet(
            title = stringResource(R.string.nutrients_choose_form),
            items = row?.key?.let { Nutrients.iuForms(it) }.orEmpty(),
            label = { formLabel(it) },
            selected = { it == row?.form },
            onSelect = { f -> pickingFormFor = null; updateRow(id) { it.copy(form = f) } },
            onDismiss = { pickingFormFor = null }
        )
    }
    if (showCamera) {
        InAppCameraCaptureDialog(
            onCapture = { bytes -> showCamera = false; onAiFromPhoto(bytes) },
            onDismiss = { showCamera = false }
        )
    }
}

/**
 * Review of the AI result: every recognised item can be edited or removed; nothing reaches the form
 * until "Add to medication", and nothing is saved until the form is saved. Rejected items are
 * listed as "Not recognised" so the user can add them by hand.
 */
@Composable
internal fun NutrientReviewDialog(result: LabelParseResult, doseUnit: String, onConfirm: (List<LabelItem>) -> Unit, onDismiss: () -> Unit) {
    val items = remember(result) { mutableStateListOf<LabelItem>().apply { addAll(result.items) } }
    val amounts = remember(result) { mutableStateListOf<String>().apply { addAll(result.items.map { NutrientInputRow.plain(it.amount) }) } }
    GlassDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("medication.nutrients.review")) {
        Text(stringResource(R.string.nutrients_review_title), fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.nutrients_review_hint, doseUnit), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
        Column(
            Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEachIndexed { i, item ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(NutrientFields.nameRes(item.key)), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    GlassTextField(
                        value = amounts[i],
                        onValueChange = { v -> amounts[i] = v.take(12) },
                        modifier = Modifier.width(96.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Text(unitLabel(item.unit), fontSize = 14.sp)
                    IconButton(onClick = { items.removeAt(i); amounts.removeAt(i) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.nutrients_remove_row), modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (result.rejected.isNotEmpty()) {
                Text(
                    pluralStringResource(R.plurals.nutrients_review_rejected, result.rejected.size, result.rejected.size),
                    fontSize = 12.sp,
                    color = AyuvoColors.secondaryLabel()
                )
            }
        }
        val parsed = items.mapIndexedNotNull { i, item ->
            val v = amounts.getOrNull(i)?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return@mapIndexedNotNull null
            val c = Nutrients.convertAmount(v, item.unit, item.key)
            if (c.ok) item.copy(amount = c.amount!!) else null
        }
        GlassDialogActions(
            primaryText = stringResource(R.string.nutrients_review_confirm),
            onPrimary = { onConfirm(parsed) },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            primaryEnabled = parsed.isNotEmpty() && parsed.size == items.size
        )
    }
}
