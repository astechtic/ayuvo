package com.ayuvo.health.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.records.coach.CoachRecordRef
import com.ayuvo.health.records.coach.RecordsCoach
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.theme.AppColors
import java.time.LocalDate

/** "Sep 12, 2026"-style date of a `yyyy-MM-dd` string, or the string itself. */
internal fun coachRecordDate(isoDate: String): String =
    runCatching { RecordFormat.date(LocalDate.parse(isoDate)) }.getOrDefault(isoDate)

/**
 * §26 consent sheet. [providerName] null = on-device Coach (nothing is sent online). Shared by
 * the Coach entry points and Settings › Health Records.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachRecordsConsentSheet(providerName: String?, onAllow: () -> Unit, onNotNow: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onNotNow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetColor()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("records_coach_consent"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(Icons.Filled.Description, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(30.dp))
            Text(stringResource(R.string.records_coach_consent_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (providerName == null) stringResource(R.string.records_coach_consent_body_on_device)
                else stringResource(R.string.records_coach_consent_body, providerName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
            )
            GlassPrimaryButton(text = stringResource(R.string.records_coach_consent_allow), onClick = onAllow)
            GlassTextButton(
                text = stringResource(R.string.records_coach_consent_not_now),
                onClick = onNotNow,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** §27 chip bar above the Coach input: "Analyzing: ✓ <title> — <date> … · Change records". */
@Composable
fun CoachRecordsChipBar(records: List<HealthRecord>, onChange: () -> Unit, modifier: Modifier = Modifier) {
    if (records.isEmpty()) return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("records_coach_chip_bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (records.size > 3) {
            Text(
                pluralStringResource(R.plurals.records_coach_analyzing_count, records.size, records.size),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Calorie,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
        } else {
            Text(stringResource(R.string.records_coach_analyzing), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.width(6.dp))
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(records, key = { it.id }) { record ->
                    Row(
                        Modifier
                            .clip50()
                            .background(AppColors.Calorie.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.records_coach_record_chip, record.title, coachRecordDate(record.sortDate)),
                            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = AppColors.Calorie, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)
                        )
                    }
                }
            }
        }
        Text("·", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 6.dp))
        Text(
            stringResource(R.string.records_coach_change_records),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Calorie,
            modifier = Modifier.clip50().clickable(onClick = onChange).padding(horizontal = 6.dp, vertical = 6.dp)
        )
    }
}

private fun Modifier.clip50(): Modifier = clip(RoundedCornerShape(50))

/** §27 "Change records" picker: search + recent records, checkboxes, at most 10. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachRecordsPickerSheet(
    selected: List<HealthRecord>,
    candidates: List<HealthRecord>,
    files: RecordFileStore,
    onSearch: (String) -> Unit,
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(selected.map { it.id }) } }
    val known = remember { mutableStateListOf<HealthRecord>().apply { addAll(selected) } }
    LaunchedEffect(candidates) { candidates.filter { c -> known.none { it.id == c.id } }.forEach { known += it } }
    LaunchedEffect(query) {
        kotlinx.coroutines.delay(150)
        onSearch(query)
    }
    val max = RecordsCoach.MAX_SELECTED
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetColor()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.records_coach_picker_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.records_coach_picker_limit, max), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            GlassTextField(value = query, onValueChange = { query = it }, placeholder = stringResource(R.string.records_coach_picker_search))
            val selectedRows = chosen.mapNotNull { id -> known.firstOrNull { it.id == id } }
            val others = candidates.filter { it.id !in chosen }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                if (selectedRows.isNotEmpty()) {
                    item(key = "h-selected") { PickerHeader(stringResource(R.string.records_coach_picker_selected)) }
                    items(selectedRows, key = { "s-" + it.id }) { record ->
                        PickerRow(record, files, checked = true, enabled = true) { chosen.remove(record.id) }
                    }
                }
                item(key = "h-others") {
                    PickerHeader(stringResource(if (query.isBlank()) R.string.records_coach_picker_recent else R.string.records_coach_picker_results))
                }
                if (others.isEmpty()) {
                    item(key = "empty") {
                        Text(stringResource(R.string.records_coach_picker_empty), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(8.dp))
                    }
                }
                items(others, key = { "c-" + it.id }) { record ->
                    PickerRow(record, files, checked = false, enabled = chosen.size < max) { if (chosen.size < max) chosen.add(record.id) }
                }
            }
            GlassPrimaryButton(text = stringResource(R.string.records_coach_picker_done), onClick = { onDone(chosen.toList()) })
        }
    }
}

@Composable
private fun PickerHeader(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp))
}

@Composable
private fun PickerRow(record: HealthRecord, files: RecordFileStore, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip50()
            .clickable(enabled = enabled || checked, onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled || checked,
            colors = CheckboxDefaults.colors(checkedColor = AppColors.Calorie)
        )
        RecordThumbnail(record = record, files = files, size = 36.dp, cornerRadius = 10.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(record.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(RecordFormat.displayDate(record), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

/** §30 prompt: "Coach uses <provider>. Send the selected records' details online?" */
@Composable
fun CoachRecordsModeDialog(providerName: String, onDeviceAvailable: Boolean, onSend: () -> Unit, onCancel: () -> Unit, onUseOnDevice: () -> Unit) {
    GlassDialog(onDismissRequest = onCancel) {
        Text(stringResource(R.string.records_coach_mode_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.records_coach_mode_body, providerName), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
        if (onDeviceAvailable) {
            GlassTextButton(text = stringResource(R.string.records_coach_mode_on_device), onClick = onUseOnDevice, modifier = Modifier.fillMaxWidth())
        }
        GlassDialogActions(
            primaryText = stringResource(R.string.records_coach_mode_send),
            onPrimary = onSend,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onCancel
        )
    }
}

/** §26 "Used records" chips under an assistant reply; each opens its record. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachUsedRecords(refs: List<CoachRecordRef>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    if (refs.isEmpty()) return
    Column(modifier.testTag("records_coach_used_records"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.records_coach_used_records), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            refs.forEach { ref ->
                Row(
                    Modifier
                        .clip50()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .border(0.6.dp, AppColors.Calorie.copy(alpha = 0.3f), RoundedCornerShape(50))
                        .clickable { onOpen(ref.recordId) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.records_coach_record_chip, ref.title, coachRecordDate(ref.date)),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = AppColors.Calorie, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
