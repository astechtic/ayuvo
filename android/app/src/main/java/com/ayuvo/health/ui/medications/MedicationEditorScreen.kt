package com.ayuvo.health.ui.medications

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.medications.logic.MedicationLocalTime
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.FoodRelation
import com.ayuvo.health.medications.model.MedicationDraft
import com.ayuvo.health.medications.model.MedicationForm
import com.ayuvo.health.medications.model.ScheduleDraft
import com.ayuvo.health.medications.model.ScheduleFrequency
import com.ayuvo.health.medications.model.ValidationError
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.ui.components.GlassColumn
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.components.InAppCameraCaptureDialog
import com.ayuvo.health.ui.components.OptionPickerSheet
import com.ayuvo.health.ui.home.SheetDatePickerDialog
import com.ayuvo.health.ui.home.SheetTimePickerDialog
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.records.RecordChip
import com.ayuvo.health.ui.records.RecordFormat
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** Add / Edit medication (docs/medications.md §15), pushed over the Health tab. */
@Composable
fun MedicationEditorScreen(
    container: AppContainer,
    medicationId: String?,
    recordId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit
) {
    val vm: MedicationEditorViewModel = viewModel(
        key = "med-editor-${medicationId ?: "new"}-${recordId.orEmpty()}",
        factory = MedicationEditorViewModel.Factory(container, medicationId, recordId)
    )
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var relatedRecord by remember { mutableStateOf<HealthRecord?>(null) }
    LaunchedEffect(ui.draft.relatedRecordId) {
        val id = ui.draft.relatedRecordId
        relatedRecord = if (id != null && container.recordsDatabaseExists()) runCatching { container.recordsStore.record(id) }.getOrNull() else null
    }
    val photoBitmap = remember(ui.draft.photoPath) {
        ui.draft.photoPath?.let { container.medicationPhotos.loadThumbnail(it) }
    }
    fun cancel() {
        vm.discard()
        onBack()
    }
    BackHandler { cancel() }

    Column(Modifier.fillMaxSize()) {
        MedicationTopBar(
            title = stringResource(if (ui.isEditing) R.string.medications_edit_title else R.string.medications_add_title),
            onBack = { cancel() }
        )
        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MedicationEditorForm(
                draft = ui.draft,
                errors = ui.errors,
                onChange = vm::update,
                isEditing = ui.isEditing,
                photo = photoBitmap,
                photoBusy = ui.photoBusy,
                onPhotoPicked = vm::setPhoto,
                onPhotoCaptured = vm::setPhoto,
                onRemovePhoto = vm::removePhoto,
                relatedRecord = relatedRecord,
                container = container
            )
            if (ui.isEditing) {
                Text(
                    stringResource(R.string.medications_edit_schedule_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
            ui.saveError?.let { code ->
                Text(saveErrorText(code), fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 6.dp))
            }
            GlassPrimaryButton(
                text = stringResource(R.string.action_save),
                enabled = !ui.saving,
                onClick = { scope.launch { vm.save()?.let(onSaved) } }
            )
            GlassTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = { cancel() },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun saveErrorText(code: String): String = when (code) {
    "invalid_transition" -> stringResource(R.string.medications_error_invalid_transition)
    "prn_has_schedule", "invalid_schedule", "schedule_required" -> stringResource(R.string.medications_error_schedule)
    else -> stringResource(R.string.medications_error_generic)
}

/** Frequency presets offered by the form; `SPECIFIC` keeps whatever times the user lists. */
internal enum class FrequencyPreset { ONCE, TWICE, THRICE, FOUR, SPECIFIC, INTERVAL, WEEKLY }

private val PRESET_TIMES = mapOf(
    FrequencyPreset.ONCE to listOf("08:00"),
    FrequencyPreset.TWICE to listOf("08:00", "20:00"),
    FrequencyPreset.THRICE to listOf("08:00", "14:00", "20:00"),
    FrequencyPreset.FOUR to listOf("08:00", "13:00", "18:00", "22:00")
)

internal fun presetOf(schedule: ScheduleDraft): FrequencyPreset = when (schedule.frequency) {
    ScheduleFrequency.INTERVAL -> FrequencyPreset.INTERVAL
    ScheduleFrequency.WEEKLY -> FrequencyPreset.WEEKLY
    else -> PRESET_TIMES.entries.firstOrNull { it.value == schedule.times }?.key ?: FrequencyPreset.SPECIFIC
}

internal fun applyPreset(schedule: ScheduleDraft, preset: FrequencyPreset): ScheduleDraft = when (preset) {
    FrequencyPreset.ONCE, FrequencyPreset.TWICE, FrequencyPreset.THRICE, FrequencyPreset.FOUR ->
        schedule.copy(frequency = ScheduleFrequency.DAILY, times = PRESET_TIMES.getValue(preset), days = emptyList(), intervalHours = null, anchorTime = null)
    FrequencyPreset.SPECIFIC ->
        schedule.copy(frequency = ScheduleFrequency.DAILY, times = schedule.times.ifEmpty { listOf("08:00") }, days = emptyList(), intervalHours = null, anchorTime = null)
    FrequencyPreset.INTERVAL ->
        schedule.copy(frequency = ScheduleFrequency.INTERVAL, times = emptyList(), days = emptyList(), intervalHours = schedule.intervalHours ?: 8, anchorTime = schedule.anchorTime ?: "08:00")
    FrequencyPreset.WEEKLY ->
        schedule.copy(frequency = ScheduleFrequency.WEEKLY, times = schedule.times.ifEmpty { listOf("08:00") }, days = schedule.days.ifEmpty { listOf(1) }, intervalHours = null, anchorTime = null)
}

@Composable
private fun presetLabel(preset: FrequencyPreset): String = stringResource(
    when (preset) {
        FrequencyPreset.ONCE -> R.string.medications_preset_once
        FrequencyPreset.TWICE -> R.string.medications_preset_twice
        FrequencyPreset.THRICE -> R.string.medications_preset_thrice
        FrequencyPreset.FOUR -> R.string.medications_preset_four
        FrequencyPreset.SPECIFIC -> R.string.medications_preset_specific
        FrequencyPreset.INTERVAL -> R.string.medications_preset_interval
        FrequencyPreset.WEEKLY -> R.string.medications_preset_weekly
    }
)

@Composable
internal fun validationText(code: String?): String? = when (code) {
    null -> null
    "name_required" -> stringResource(R.string.medications_error_name)
    "strength_too_long" -> stringResource(R.string.medications_error_strength)
    "dose_quantity_invalid" -> stringResource(R.string.medications_error_dose)
    "start_date_invalid", "end_date_invalid" -> stringResource(R.string.medications_error_date)
    "end_date_before_start" -> stringResource(R.string.medications_error_end_before_start)
    "times_required" -> stringResource(R.string.medications_error_times_required)
    "times_invalid" -> stringResource(R.string.medications_error_times_invalid)
    "days_required", "days_invalid" -> stringResource(R.string.medications_error_days)
    "interval_invalid", "anchor_required", "anchor_invalid" -> stringResource(R.string.medications_error_interval)
    "instructions_too_long" -> stringResource(R.string.medications_error_instructions)
    "frequency_required" -> stringResource(R.string.medications_error_frequency)
    else -> stringResource(R.string.medications_error_generic)
}

/**
 * The form itself, shared by the Add/Edit screen and the prescription-import candidate editor.
 * Photo and related-record pickers are optional (the import editor passes none).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MedicationEditorForm(
    draft: MedicationDraft,
    errors: List<ValidationError>,
    onChange: ((MedicationDraft) -> MedicationDraft) -> Unit,
    isEditing: Boolean,
    container: AppContainer,
    photo: Bitmap? = null,
    photoBusy: Boolean = false,
    onPhotoPicked: ((android.net.Uri) -> Unit)? = null,
    onPhotoCaptured: ((ByteArray) -> Unit)? = null,
    onRemovePhoto: (() -> Unit)? = null,
    relatedRecord: HealthRecord? = null,
    allowRecordLink: Boolean = true
) {
    val context = LocalContext.current
    fun error(field: String): String? = errors.firstOrNull { it.field == field }?.code
    var picker by rememberSaveable { mutableStateOf<String?>(null) }   // form | unit | food | interval
    var addingTime by rememberSaveable { mutableStateOf(false) }
    var editingAnchor by rememberSaveable { mutableStateOf(false) }
    var pickingStart by rememberSaveable { mutableStateOf(false) }
    var pickingEnd by rememberSaveable { mutableStateOf(false) }
    var showRecordPicker by rememberSaveable { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var quantityText by rememberSaveable(draft.doseQuantity?.let { MedicationFormat.quantity(it) }) {
        mutableStateOf(draft.doseQuantity?.let { q -> if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString() } ?: "")
    }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPhotoPicked?.invoke(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera = true
    }

    // -- Medicine ---------------------------------------------------------------------------------
    FormSection(stringResource(R.string.medications_section_medicine)) {
        FieldLabel(stringResource(R.string.medications_field_name))
        GlassTextField(value = draft.name, onValueChange = { v -> onChange { it.copy(name = v.take(80)) } }, placeholder = stringResource(R.string.medications_field_name_hint))
        FieldError(validationText(error("name")))
        FieldLabel(stringResource(R.string.medications_field_strength))
        GlassTextField(value = draft.strength.orEmpty(), onValueChange = { v -> onChange { it.copy(strength = v.take(40)) } }, placeholder = stringResource(R.string.medications_field_strength_hint))
        FieldError(validationText(error("strength")))
        FieldLabel(stringResource(R.string.medications_field_form))
        PickerRow(value = stringResource(draft.form.labelRes()), onClick = { picker = "form" })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel(stringResource(R.string.medications_field_generic))
                GlassTextField(value = draft.genericName.orEmpty(), onValueChange = { v -> onChange { it.copy(genericName = v.take(80)) } })
            }
            Column(Modifier.weight(1f)) {
                FieldLabel(stringResource(R.string.medications_field_brand))
                GlassTextField(value = draft.brandName.orEmpty(), onValueChange = { v -> onChange { it.copy(brandName = v.take(80)) } })
            }
        }
    }

    // -- Dose -------------------------------------------------------------------------------------
    FormSection(stringResource(R.string.medications_section_dose)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                FieldLabel(stringResource(R.string.medications_field_dose_quantity))
                GlassTextField(
                    value = quantityText,
                    onValueChange = { v ->
                        quantityText = v.take(8)
                        onChange { it.copy(doseQuantity = v.replace(',', '.').toDoubleOrNull()) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
            Column(Modifier.weight(1f)) {
                FieldLabel(stringResource(R.string.medications_field_dose_unit))
                PickerRow(value = stringResource(draft.doseUnit.labelRes()), onClick = { picker = "unit" })
            }
        }
        FieldError(validationText(error("dose_quantity")))
        FieldLabel(stringResource(R.string.medications_field_food))
        PickerRow(value = stringResource(draft.foodRelation.labelRes()), onClick = { picker = "food" })
    }

    // -- Schedule ---------------------------------------------------------------------------------
    FormSection(stringResource(R.string.medications_section_schedule)) {
        SwitchRow(
            label = stringResource(R.string.medications_field_prn),
            subtitle = stringResource(R.string.medications_field_prn_hint),
            checked = draft.isPrn,
            onChange = { on -> onChange { it.copy(isPrn = on) } }
        )
        if (!draft.isPrn) {
            val schedule = draft.schedule
            val preset = presetOf(schedule)
            FieldLabel(stringResource(R.string.medications_field_frequency))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FrequencyPreset.entries.forEach { p ->
                    RecordChip(text = presetLabel(p), selected = p == preset, onClick = { onChange { it.copy(schedule = applyPreset(it.schedule, p)) } })
                }
            }
            FieldError(validationText(error("frequency_kind")))
            when (schedule.frequency) {
                ScheduleFrequency.DAILY, ScheduleFrequency.WEEKLY -> {
                    FieldLabel(stringResource(R.string.medications_field_times))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        schedule.times.forEach { hhmm ->
                            TimeChip(label = MedicationFormat.slot(context, hhmm), onRemove = {
                                onChange { it.copy(schedule = it.schedule.copy(times = it.schedule.times - hhmm)) }
                            })
                        }
                        if (schedule.times.size < 12) {
                            GlassTextButton(text = stringResource(R.string.medications_action_add_time), onClick = { addingTime = true })
                        }
                    }
                    FieldError(validationText(error("times")))
                    if (schedule.frequency == ScheduleFrequency.WEEKLY) {
                        FieldLabel(stringResource(R.string.medications_field_days))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..7).forEach { day ->
                                val on = day in schedule.days
                                RecordChip(text = MedicationFormat.weekday(day), selected = on, onClick = {
                                    onChange {
                                        val days = if (on) it.schedule.days - day else (it.schedule.days + day).sorted()
                                        it.copy(schedule = it.schedule.copy(days = days))
                                    }
                                })
                            }
                        }
                        FieldError(validationText(error("days")))
                    }
                }
                ScheduleFrequency.INTERVAL -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            FieldLabel(stringResource(R.string.medications_field_interval))
                            PickerRow(
                                value = stringResource(R.string.medications_interval_value, schedule.intervalHours ?: 8),
                                onClick = { picker = "interval" }
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            FieldLabel(stringResource(R.string.medications_field_anchor))
                            PickerRow(value = MedicationFormat.slot(context, schedule.anchorTime ?: "08:00"), onClick = { editingAnchor = true })
                        }
                    }
                    FieldError(validationText(error("interval_hours") ?: error("anchor_time")))
                }
                null -> {}
            }
            SwitchRow(
                label = stringResource(R.string.medications_field_reminders),
                subtitle = null,
                checked = schedule.reminderEnabled,
                onChange = { on -> onChange { it.copy(schedule = it.schedule.copy(reminderEnabled = on)) } }
            )
        }
    }

    // -- Duration ---------------------------------------------------------------------------------
    FormSection(stringResource(R.string.medications_section_duration)) {
        FieldLabel(stringResource(R.string.medications_field_start))
        PickerRow(value = MedicationFormat.date(draft.startDate), onClick = { pickingStart = true })
        FieldError(validationText(error("start_date")))
        SwitchRow(
            label = stringResource(R.string.medications_field_no_end),
            subtitle = null,
            checked = draft.endDate == null,
            onChange = { noEnd ->
                onChange {
                    it.copy(endDate = if (noEnd) null else MedicationLocalTime.formatDate((MedicationLocalTime.parseDate(it.startDate) ?: LocalDate.now()).plusDays(6)))
                }
            }
        )
        if (draft.endDate != null) {
            FieldLabel(stringResource(R.string.medications_field_end))
            PickerRow(value = MedicationFormat.date(draft.endDate), onClick = { pickingEnd = true })
            FieldError(validationText(error("end_date")))
        }
    }

    // -- Instructions, photo, record --------------------------------------------------------------
    FormSection(stringResource(R.string.medications_section_more)) {
        FieldLabel(stringResource(R.string.medications_field_instructions))
        GlassTextField(
            value = draft.instructions.orEmpty(),
            onValueChange = { v -> onChange { it.copy(instructions = v.take(200)) } },
            placeholder = stringResource(R.string.medications_field_instructions_hint),
            singleLine = false,
            minLines = 2
        )
        FieldError(validationText(error("instructions")))
        if (onPhotoPicked != null && onPhotoCaptured != null) {
            FieldLabel(stringResource(R.string.medications_field_photo))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (photo != null) {
                    Image(
                        bitmap = photo.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_medication_photo),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                    )
                    GlassTextButton(text = stringResource(R.string.action_remove), onClick = { onRemovePhoto?.invoke() })
                } else if (photoBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Box {
                        GlassTextButton(text = stringResource(R.string.medications_action_add_photo), onClick = { photoMenu = true })
                        DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.medications_photo_camera)) }, onClick = {
                                photoMenu = false
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCamera = true
                                else cameraPermission.launch(Manifest.permission.CAMERA)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.medications_photo_gallery)) }, onClick = {
                                photoMenu = false
                                photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                        }
                    }
                }
            }
        }
        if (allowRecordLink) {
            FieldLabel(stringResource(R.string.medications_field_record))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        relatedRecord != null -> "${relatedRecord.title} · ${RecordFormat.displayDate(relatedRecord)}"
                        draft.relatedRecordId != null -> stringResource(R.string.medications_record_missing)
                        else -> stringResource(R.string.medications_record_none)
                    },
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (draft.relatedRecordId != null) {
                    GlassTextButton(text = stringResource(R.string.action_remove), onClick = { onChange { it.copy(relatedRecordId = null) } })
                }
                GlassTextButton(text = stringResource(R.string.medications_action_choose_record), onClick = { showRecordPicker = true })
            }
        }
    }

    // -- pickers ----------------------------------------------------------------------------------
    when (picker) {
        "form" -> OptionPickerSheet(
            title = stringResource(R.string.medications_field_form),
            items = MedicationForm.entries,
            label = { stringResource(it.labelRes()) },
            selected = { it == draft.form },
            onSelect = { form ->
                picker = null
                onChange { it.copy(form = form, doseUnit = if (it.doseUnit == it.form.defaultUnit) form.defaultUnit else it.doseUnit) }
            },
            onDismiss = { picker = null }
        )
        "unit" -> OptionPickerSheet(
            title = stringResource(R.string.medications_field_dose_unit),
            items = DoseUnit.entries,
            label = { stringResource(it.labelRes()) },
            selected = { it == draft.doseUnit },
            onSelect = { unit -> picker = null; onChange { it.copy(doseUnit = unit) } },
            onDismiss = { picker = null }
        )
        "food" -> OptionPickerSheet(
            title = stringResource(R.string.medications_field_food),
            items = FoodRelation.entries,
            label = { stringResource(it.labelRes()) },
            selected = { it == draft.foodRelation },
            onSelect = { food -> picker = null; onChange { it.copy(foodRelation = food) } },
            onDismiss = { picker = null }
        )
        "interval" -> OptionPickerSheet(
            title = stringResource(R.string.medications_field_interval),
            items = listOf(1, 2, 3, 4, 6, 8, 12, 24),
            label = { stringResource(R.string.medications_interval_value, it) },
            selected = { it == draft.schedule.intervalHours },
            onSelect = { hours -> picker = null; onChange { it.copy(schedule = it.schedule.copy(intervalHours = hours)) } },
            onDismiss = { picker = null }
        )
    }
    if (addingTime) {
        SheetTimePickerDialog(
            initialTime = LocalTime.of(8, 0),
            onConfirm = { time ->
                addingTime = false
                val hhmm = MedicationLocalTime.formatHhmm(time.hour, time.minute)
                onChange { it.copy(schedule = it.schedule.copy(times = (it.schedule.times + hhmm).distinct().sorted())) }
            },
            onDismiss = { addingTime = false }
        )
    }
    if (editingAnchor) {
        val (h, m) = MedicationLocalTime.parseHhmm(draft.schedule.anchorTime) ?: (8 to 0)
        SheetTimePickerDialog(
            initialTime = LocalTime.of(h, m),
            onConfirm = { time ->
                editingAnchor = false
                onChange { it.copy(schedule = it.schedule.copy(anchorTime = MedicationLocalTime.formatHhmm(time.hour, time.minute))) }
            },
            onDismiss = { editingAnchor = false }
        )
    }
    if (pickingStart) {
        SheetDatePickerDialog(
            initialDate = MedicationLocalTime.parseDate(draft.startDate) ?: LocalDate.now(),
            onConfirm = { date -> pickingStart = false; onChange { it.copy(startDate = MedicationLocalTime.formatDate(date)) } },
            onDismiss = { pickingStart = false }
        )
    }
    if (pickingEnd) {
        SheetDatePickerDialog(
            initialDate = MedicationLocalTime.parseDate(draft.endDate) ?: LocalDate.now(),
            onConfirm = { date -> pickingEnd = false; onChange { it.copy(endDate = MedicationLocalTime.formatDate(date)) } },
            onDismiss = { pickingEnd = false }
        )
    }
    if (showRecordPicker) {
        RecordPickerSheet(
            container = container,
            selectedId = draft.relatedRecordId,
            onPick = { record -> showRecordPicker = false; onChange { it.copy(relatedRecordId = record.id) } },
            onDismiss = { showRecordPicker = false }
        )
    }
    if (showCamera && onPhotoCaptured != null) {
        InAppCameraCaptureDialog(
            onCapture = { bytes -> showCamera = false; onPhotoCaptured(bytes) },
            onDismiss = { showCamera = false }
        )
    }
}

@Composable
private fun FormSection(title: String, content: @Composable () -> Unit) {
    GlassColumn(Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 16.dp) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun FieldError(text: String?) {
    if (text != null) Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun PickerRow(value: String, onClick: () -> Unit) {
    GlassTextButton(text = value, onClick = onClick, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SwitchRow(label: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Calorie)
        )
    }
}

@Composable
private fun TimeChip(label: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RecordChip(text = label, selected = true, onClick = {})
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_medication_remove_time, label), modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(2.dp))
    }
}
