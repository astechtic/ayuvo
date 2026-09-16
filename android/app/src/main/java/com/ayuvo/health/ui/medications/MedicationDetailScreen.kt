package com.ayuvo.health.ui.medications

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.medications.model.LifecycleAction
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.IconBubble
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.records.RecordFormat
import com.ayuvo.health.ui.records.RecordThumbnail
import com.ayuvo.health.ui.theme.AppColors
import androidx.compose.material.icons.filled.Medication

private enum class DetailConfirm { STOP, DELETE }

/** Medication detail (docs/medications.md): info, schedule, dates, adherence, history, record, lifecycle. */
@Composable
fun MedicationDetailScreen(
    container: AppContainer,
    medicationId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenHistory: (String) -> Unit,
    onOpenRecord: (String) -> Unit
) {
    val vm: MedicationDetailViewModel = viewModel(key = "med-detail-$medicationId", factory = MedicationDetailViewModel.Factory(container, medicationId))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<DetailConfirm?>(null) }
    var prnSheet by rememberSaveable { mutableStateOf(false) }
    val genericError = stringResource(R.string.medications_error_generic)
    val invalidTransition = stringResource(R.string.medications_error_invalid_transition)
    LaunchedEffect(ui.actionError) {
        val code = ui.actionError ?: return@LaunchedEffect
        Toast.makeText(context, if (code == "invalid_transition") invalidTransition else genericError, Toast.LENGTH_SHORT).show()
        vm.clearError()
    }
    val medication = ui.medication
    Column(Modifier.fillMaxSize()) {
        MedicationTopBar(title = medication?.name ?: "", onBack = onBack) {
            if (medication != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_medication_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.medications_action_edit)) }, onClick = { menuOpen = false; onEdit(medication.id) })
                        when (medication.status) {
                            MedicationStatus.ACTIVE -> DropdownMenuItem(
                                text = { Text(stringResource(R.string.medications_action_pause)) },
                                onClick = { menuOpen = false; vm.lifecycle(LifecycleAction.PAUSE) }
                            )
                            MedicationStatus.PAUSED -> DropdownMenuItem(
                                text = { Text(stringResource(R.string.medications_action_resume)) },
                                onClick = { menuOpen = false; vm.lifecycle(LifecycleAction.RESUME) }
                            )
                            else -> {}
                        }
                        if (!medication.status.isFinal) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.medications_action_stop)) }, onClick = { menuOpen = false; confirm = DetailConfirm.STOP })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menuOpen = false; confirm = DetailConfirm.DELETE })
                    }
                }
            }
        }
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            medication == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.medications_detail_missing), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = BottomNavScrollPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                GlassSurface(Modifier.fillMaxWidth().testTag("medications.detail.header"), cornerRadius = 22.dp, padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val photo = remember(medication.photoPath) { medication.photoPath?.let { container.medicationPhotos.loadThumbnail(it) } }
                        if (photo != null) {
                            Image(
                                bitmap = photo.asImageBitmap(),
                                contentDescription = stringResource(R.string.cd_medication_photo),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                            )
                        } else {
                            IconBubble(icon = Icons.Filled.Medication, size = 56.dp, iconSize = 30.dp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(medication.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val subtitle = listOfNotNull(medication.strength, stringResource(medication.form.labelRes())).joinToString(" · ")
                            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                            MedicationStatusBadge(medication.status)
                        }
                    }
                }
                if (medication.isPrn && medication.isActive) {
                    GlassPrimaryButton(text = stringResource(R.string.medications_action_log_dose), onClick = { prnSheet = true })
                }

                // Dose & schedule
                DetailCard(stringResource(R.string.medications_detail_schedule), tag = "schedule") {
                    InfoRow(stringResource(R.string.medications_section_dose), doseText(medication.doseQuantity, medication.doseUnit))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    InfoRow(stringResource(R.string.medications_field_frequency), scheduleDescription(medication, ui.schedule))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    InfoRow(stringResource(R.string.medications_field_food), stringResource(medication.foodRelation.labelRes()))
                    val schedule = ui.schedule
                    if (!medication.isPrn && schedule != null && medication.isActive) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.medications_field_reminders), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Switch(
                                checked = schedule.reminderEnabled,
                                onCheckedChange = vm::setReminderEnabled,
                                colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Calorie),
                                modifier = Modifier.testTag("medications.detail.reminders")
                            )
                        }
                    }
                }

                // Dates
                DetailCard(stringResource(R.string.medications_section_duration), tag = "dates") {
                    InfoRow(stringResource(R.string.medications_field_start), MedicationFormat.date(medication.startDate))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    InfoRow(
                        stringResource(R.string.medications_field_end),
                        medication.endDate?.let { MedicationFormat.date(it) } ?: stringResource(R.string.medications_field_no_end)
                    )
                    if (medication.endDate != null && !medication.status.isFinal) {
                        Text(
                            stringResource(R.string.medications_completes_on, MedicationFormat.date(medication.endDate)),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                // Adherence
                val adherence = ui.adherence
                if (!medication.isPrn) {
                    DetailCard(stringResource(R.string.medications_detail_adherence), tag = "adherence") {
                        Text(
                            if (adherence != null && adherence.hasData)
                                stringResource(R.string.medications_adherence_line, adherence.taken, adherence.expected, adherence.percent)
                            else stringResource(R.string.medications_adherence_none),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }

                // Recent history
                DetailCard(stringResource(R.string.medications_detail_history), tag = "history") {
                    if (ui.recentLogs.isEmpty()) {
                        Text(
                            stringResource(R.string.medications_history_empty),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    } else {
                        ui.recentLogs.forEachIndexed { index, log ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(MedicationFormat.dateTime(context, log.takenAtMs ?: log.scheduledAtMs), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(doseText(log.doseQuantity, log.doseUnit), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                                StatusBadge(status = log.status)
                            }
                        }
                        Text(
                            stringResource(R.string.medications_action_see_all),
                            color = AppColors.Calorie,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable { onOpenHistory(medication.id) }.padding(vertical = 12.dp)
                        )
                    }
                }

                // Related record
                if (medication.relatedRecordId != null) {
                    DetailCard(stringResource(R.string.medications_detail_record), tag = "record") {
                        val record = ui.relatedRecord
                        if (record != null) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenRecord(record.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RecordThumbnail(record = record, files = container.recordFiles, size = 44.dp, cornerRadius = 12.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(record.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(RecordFormat.displayDate(record), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                        } else {
                            Text(
                                stringResource(R.string.medications_record_missing),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }
                }

                if (!medication.instructions.isNullOrBlank()) {
                    DetailCard(stringResource(R.string.medications_field_instructions), tag = "instructions") {
                        Text(medication.instructions, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
                MedicationsDisclaimer()
            }
        }
    }

    when (confirm) {
        DetailConfirm.STOP -> GlassDialog(onDismissRequest = { confirm = null }) {
            Text(stringResource(R.string.medications_stop_title, medication?.name.orEmpty()), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.medications_stop_body), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
            GlassDialogActions(
                primaryText = stringResource(R.string.medications_action_stop),
                onPrimary = { confirm = null; vm.lifecycle(LifecycleAction.STOP) },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { confirm = null },
                destructive = true
            )
        }
        DetailConfirm.DELETE -> GlassDialog(onDismissRequest = { confirm = null }) {
            Text(stringResource(R.string.medications_delete_title, medication?.name.orEmpty()), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.medications_delete_body), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
            GlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = { confirm = null; vm.delete(onBack) },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { confirm = null },
                destructive = true
            )
        }
        null -> {}
    }
    if (prnSheet && medication != null) {
        PrnLogSheet(
            medication = medication,
            onLog = { takenAt, quantity, note -> prnSheet = false; vm.logPrn(takenAt, quantity, note) },
            onDismiss = { prnSheet = false }
        )
    }
}

@Composable
private fun DetailCard(title: String, tag: String, content: @Composable () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().testTag("medications.detail.$tag"), cornerRadius = 20.dp, padding = 0.dp) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 2.dp)
            )
            content()
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.4f))
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.6f)
        )
    }
}
