package com.ayuvo.health.ui.records

import com.ayuvo.health.ui.design.AyuvoColors
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.records.data.RecordIntelligence
import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.HighlightSection
import com.ayuvo.health.records.model.ProcessingError
import com.ayuvo.health.records.model.ProcessingStatus
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordHighlight
import com.ayuvo.health.records.model.ResultFlag
import com.ayuvo.health.records.model.SplitStatus
import com.ayuvo.health.records.processing.RecordJson
import com.ayuvo.health.records.processing.RecordJson.string
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.theme.AppColors
import java.time.LocalDate

private val Amber = Color(0xFFE8A33D)
private val FlagRed = Color(0xFFFF453A)

@StringRes
internal fun fieldLabelRes(key: String): Int = when (key) {
    FieldKey.DOCTOR_NAME -> R.string.records_key_doctor_name
    FieldKey.DOCTOR_SPECIALTY -> R.string.records_key_doctor_specialty
    FieldKey.FACILITY -> R.string.records_key_facility
    FieldKey.DEPARTMENT -> R.string.records_key_department
    FieldKey.PATIENT_NAME -> R.string.records_key_patient_name
    FieldKey.PATIENT_AGE -> R.string.records_key_patient_age
    FieldKey.PATIENT_SEX -> R.string.records_key_patient_sex
    FieldKey.REPORT_NAME -> R.string.records_key_report_name
    FieldKey.TEST_RESULT -> R.string.records_key_test_result
    FieldKey.DIAGNOSIS -> R.string.records_key_diagnosis
    FieldKey.SYMPTOM -> R.string.records_key_symptom
    FieldKey.MEDICATION -> R.string.records_key_medication
    FieldKey.PROCEDURE -> R.string.records_key_procedure
    FieldKey.RECOMMENDATION -> R.string.records_key_recommendation
    FieldKey.FOLLOW_UP_DATE -> R.string.records_key_follow_up_date
    FieldKey.VISIT_DATE -> R.string.records_key_visit_date
    FieldKey.COLLECTION_DATE -> R.string.records_key_collection_date
    FieldKey.REPORT_DATE -> R.string.records_key_report_date
    FieldKey.PRESCRIPTION_DATE -> R.string.records_key_prescription_date
    FieldKey.ADMISSION_DATE -> R.string.records_key_admission_date
    FieldKey.DISCHARGE_DATE -> R.string.records_key_discharge_date
    FieldKey.DOCUMENT_TIME -> R.string.records_key_document_time
    else -> R.string.records_key_other
}

@StringRes
private fun ExtractionMethod.badgeRes(): Int = when (this) {
    ExtractionMethod.AI_LOCAL -> R.string.records_method_ai_local
    ExtractionMethod.AI_CLOUD -> R.string.records_method_ai_cloud
    ExtractionMethod.USER -> R.string.records_method_user
    ExtractionMethod.FILE_METADATA -> R.string.records_method_file
    else -> R.string.records_method_rules
}

@Composable
internal fun MethodBadge(field: RecordField) {
    val confirmed = field.state == FieldState.CONFIRMED
    val text = if (confirmed) stringResource(R.string.records_method_confirmed) else stringResource(field.method.badgeRes())
    val color = when {
        confirmed || field.state == FieldState.USER -> Color(0xFF34C759)
        field.method.isAi -> Color(0xFF7D6CF2)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    }
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** A readable value: dates localized, test results "value unit (ref)". */
@Composable
internal fun fieldDisplayValue(field: RecordField): String = when {
    field.key in FieldKey.DATE_KEYS -> runCatching { RecordFormat.date(LocalDate.parse(field.valueText)) }.getOrDefault(field.valueText)
    field.key == FieldKey.TEST_RESULT -> {
        val json = RecordJson.parseObject(field.valueJson)
        listOfNotNull(json?.string("value"), json?.string("unit")).joinToString(" ").ifBlank { field.valueText } +
            (json?.string("ref_text")?.takeIf { it.isNotBlank() }?.let { "  ($it)" } ?: "")
    }
    field.key == FieldKey.MEDICATION -> {
        val json = RecordJson.parseObject(field.valueJson)
        listOfNotNull(field.valueText, json?.string("strength"), json?.string("frequency"), json?.string("duration")).filter { it.isNotBlank() }.joinToString(" · ")
    }
    else -> field.valueText
}

private fun RecordField.flag(): ResultFlag = ResultFlag.fromRaw(RecordJson.parseObject(valueJson)?.string("flag"))

/** Detail header status: AI label plus the current stage while processing (§16, plan §2). */
@Composable
internal fun DetailStatusLine(record: HealthRecord, intelligence: RecordIntelligence?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val stage = when (record.processingStatus) {
                ProcessingStatus.QUEUED -> R.string.records_stage_queued
                ProcessingStatus.EXTRACTING_TEXT -> R.string.records_stage_text
                ProcessingStatus.ANALYZING -> R.string.records_stage_analyzing
                else -> null
            }
            if (stage != null) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = AppColors.Calorie)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(stage), fontSize = 13.sp, color = AppColors.Calorie, fontWeight = FontWeight.Medium)
            } else {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    aiStatusLabel(record.aiModeUsed, intelligence?.columns?.aiProvider),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
        val note = when (record.processingError) {
            ProcessingError.AI_UNAVAILABLE -> R.string.records_note_ai_unavailable
            ProcessingError.AI_FAILED -> R.string.records_note_ai_failed
            ProcessingError.OCR_FAILED, ProcessingError.TEXT_UNAVAILABLE -> R.string.records_note_text_unavailable
            ProcessingError.UNSUPPORTED -> R.string.records_note_unsupported
            else -> null
        }
        note?.let {
            Text(stringResource(it), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
internal fun ActionBanner(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String?, action: String, tint: Color = AppColors.Calorie, onAction: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                body?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)) }
            }
            Spacer(Modifier.width(8.dp))
            GlassTextButton(text = action, onClick = onAction)
        }
    }
}

/** Needs review / duplicate / split / combined-document banners. */
@Composable
internal fun DetailBanners(
    record: HealthRecord,
    intelligence: RecordIntelligence,
    reviewCount: Int,
    onReview: () -> Unit,
    onDuplicates: () -> Unit,
    onSplit: () -> Unit,
    onOpenRecord: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reviewCount > 0) {
            ActionBanner(
                Icons.Filled.PriorityHigh,
                pluralStringResource(R.plurals.records_review_banner, reviewCount, reviewCount),
                stringResource(R.string.records_review_banner_body),
                stringResource(R.string.records_review_action),
                Amber,
                onReview
            )
        }
        if (intelligence.duplicates.isNotEmpty()) {
            ActionBanner(
                Icons.Filled.ContentCopy,
                stringResource(R.string.records_duplicate_title),
                stringResource(R.string.records_duplicate_banner_body),
                stringResource(R.string.records_review_action),
                Amber,
                onDuplicates
            )
        }
        val split = intelligence.split
        if (split != null && split.status == SplitStatus.PENDING && split.segments.size >= 2) {
            ActionBanner(
                Icons.AutoMirrored.Filled.CallSplit,
                stringResource(R.string.records_split_banner, split.segments.size),
                stringResource(R.string.records_split_banner_body),
                stringResource(R.string.records_split_review),
                AppColors.Calorie,
                onSplit
            )
        }
        if (intelligence.children.isNotEmpty()) {
            GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 14.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.records_combined_badge), fontWeight = FontWeight.SemiBold, color = AppColors.Calorie)
                    intelligence.children.forEach { child ->
                        Text(
                            "${child.title} · " + stringResource(R.string.records_split_pages, (child.pageStart ?: 0) + 1, (child.pageEnd ?: 0) + 1),
                            modifier = Modifier.fillMaxWidth().clickable { onOpenRecord(child.id) }.padding(vertical = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        intelligence.parent?.let { parent ->
            ActionBanner(
                Icons.Filled.Info,
                stringResource(R.string.records_part_of_combined),
                stringResource(R.string.records_split_pages, (record.pageStart ?: 0) + 1, (record.pageEnd ?: 0) + 1),
                stringResource(R.string.records_open),
                AppColors.Calorie
            ) { onOpenRecord(parent.id) }
        }
    }
}

/** AI highlights card (§15): important items, then the labelled AI summary. */
@Composable
internal fun HighlightsCard(highlights: List<RecordHighlight>, onFocusPage: (Int?) -> Unit, onDismiss: (String) -> Unit) {
    val important = highlights.filter { it.section == HighlightSection.IMPORTANT && !it.dismissed }
    val summary = highlights.firstOrNull { it.section == HighlightSection.SUMMARY && !it.dismissed }
    if (important.isEmpty() && summary == null) return
    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.records_highlights_card), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            important.forEach { h ->
                Row(Modifier.fillMaxWidth().clickable { onFocusPage(h.sourcePage) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(FlagRed))
                    Spacer(Modifier.width(10.dp))
                    Text(h.text, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    IconButton(onClick = { onDismiss(h.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, stringResource(R.string.action_remove), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
            }
            summary?.let { s ->
                if (important.isNotEmpty()) HorizontalDivider()
                Text(stringResource(R.string.records_ai_summary_label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF7D6CF2))
                Text(s.text, fontSize = 14.sp)
            }
        }
    }
}

private val groupOrder: List<Pair<Int, List<String>>> = listOf(
    R.string.records_group_dates to listOf(
        FieldKey.REPORT_DATE, FieldKey.COLLECTION_DATE, FieldKey.VISIT_DATE, FieldKey.PRESCRIPTION_DATE,
        FieldKey.ADMISSION_DATE, FieldKey.DISCHARGE_DATE, FieldKey.FOLLOW_UP_DATE, FieldKey.DOCUMENT_TIME
    ),
    R.string.records_group_people to listOf(
        FieldKey.DOCTOR_NAME, FieldKey.DOCTOR_SPECIALTY, FieldKey.FACILITY, FieldKey.DEPARTMENT,
        FieldKey.PATIENT_NAME, FieldKey.PATIENT_AGE, FieldKey.PATIENT_SEX
    ),
    R.string.records_group_report to listOf(FieldKey.REPORT_NAME, FieldKey.DIAGNOSIS, FieldKey.SYMPTOM, FieldKey.PROCEDURE),
    R.string.records_group_results to listOf(FieldKey.TEST_RESULT)
)

/** Extracted information, grouped, each row with its method badge and tap-to-source. */
@Composable
internal fun ExtractedInfoCard(fields: List<RecordField>, onFocus: (RecordField) -> Unit, onEdit: (RecordField) -> Unit) {
    val visible = fields.filter { it.state != FieldState.REJECTED }
    if (visible.none { f -> groupOrder.any { f.key in it.second } }) return
    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 0.dp) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.records_extracted_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            groupOrder.forEach { (title, keys) ->
                val rows = keys.flatMap { key -> visible.filter { it.key == key } }
                if (rows.isEmpty()) return@forEach
                Text(
                    stringResource(title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
                )
                rows.forEach { field -> FieldRow(field, onFocus, onEdit) }
            }
        }
    }
}

@Composable
private fun FieldRow(field: RecordField, onFocus: (RecordField) -> Unit, onEdit: (RecordField) -> Unit) {
    val isResult = field.key == FieldKey.TEST_RESULT
    val flag = if (isResult) field.flag() else ResultFlag.UNKNOWN
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (field.sourcePage != null) onFocus(field) else onEdit(field) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(0.45f)) {
            Text(
                if (isResult) field.valueText else stringResource(fieldLabelRes(field.key)),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodBadge(field)
                field.sourcePage?.let {
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.records_source_page, it + 1), fontSize = 10.sp, color = AppColors.Calorie)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            fieldDisplayValue(field),
            modifier = Modifier.weight(0.55f),
            fontSize = 14.sp,
            color = when {
                flag.isAbnormal -> FlagRed
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            },
            fontWeight = if (flag.isAbnormal) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (flag.isAbnormal) {
            Spacer(Modifier.width(4.dp))
            Text(if (flag == ResultFlag.LOW || flag == ResultFlag.CRITICAL_LOW) "↓" else if (flag == ResultFlag.ABNORMAL) "*" else "↑", color = FlagRed, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Medications / Recommendations cards built from their highlight sections (fallback: fields).
 * [action] renders under the list (the Medications card's "Add to Medications").
 */
@Composable
internal fun ListCard(
    @StringRes title: Int,
    items: List<Pair<String, Int?>>,
    onFocusPage: (Int?) -> Unit,
    action: (@Composable () -> Unit)? = null
) {
    if (items.isEmpty()) return
    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            items.forEach { (text, page) ->
                Row(Modifier.fillMaxWidth().clickable { onFocusPage(page) }, verticalAlignment = Alignment.Top) {
                    Text("•", color = AppColors.Calorie, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }
            if (action != null) {
                Spacer(Modifier.height(4.dp))
                action()
            }
        }
    }
}

/** "We found these details" (plan §2): only uncertain, key or conflicting values. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewDetailsSheet(
    items: List<ReviewItem>,
    onConfirm: (RecordField) -> Unit,
    onEdit: (RecordField, String) -> Unit,
    onIgnore: (RecordField) -> Unit,
    onConfirmAll: () -> Unit,
    onAdd: (String, String) -> Unit,
    onFocus: (RecordField) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var editing by remember { mutableStateOf<RecordField?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = AyuvoColors.sheetBackground()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.records_review_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.records_review_subtitle), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            if (items.isEmpty()) {
                Text(stringResource(R.string.records_review_empty), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            var lastConflictKey: String? = null
            items.forEach { item ->
                val field = item.field
                if (item.conflict && field.key != lastConflictKey) {
                    Text(
                        stringResource(R.string.records_review_conflict, stringResource(fieldLabelRes(field.key))),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Amber
                    )
                }
                lastConflictKey = if (item.conflict) field.key else null
                GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 16.dp, padding = 12.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (field.key == FieldKey.TEST_RESULT) field.valueText else stringResource(fieldLabelRes(field.key)),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.weight(1f)
                            )
                            MethodBadge(field)
                            field.sourcePage?.let {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.records_source_page, it + 1),
                                    fontSize = 11.sp,
                                    color = AppColors.Calorie,
                                    modifier = Modifier.clickable { onFocus(field) }
                                )
                            }
                        }
                        Text(fieldDisplayValue(field), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassTextButton(text = stringResource(R.string.records_review_confirm), onClick = { onConfirm(field) }, modifier = Modifier.weight(1f))
                            GlassTextButton(text = stringResource(R.string.records_review_edit), onClick = { editing = field }, modifier = Modifier.weight(1f))
                            GlassTextButton(
                                text = stringResource(R.string.records_review_ignore),
                                onClick = { onIgnore(field) },
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            if (items.isNotEmpty()) GlassPrimaryButton(text = stringResource(R.string.records_review_confirm_all), onClick = onConfirmAll)
            GlassTextButton(text = stringResource(R.string.records_review_add), onClick = { adding = true }, modifier = Modifier.fillMaxWidth())
            GlassTextButton(
                text = stringResource(R.string.records_review_later),
                onClick = onDismiss,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    editing?.let { field ->
        var value by remember(field.id) { mutableStateOf(field.valueText) }
        GlassDialog(onDismissRequest = { editing = null }) {
            Text(stringResource(fieldLabelRes(field.key)), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            if (field.key in FieldKey.DATE_KEYS) {
                Text(stringResource(R.string.records_review_date_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            GlassTextField(value = value, onValueChange = { value = it })
            GlassDialogActions(
                primaryText = stringResource(R.string.action_save),
                onPrimary = {
                    onEdit(field, value)
                    editing = null
                },
                primaryEnabled = value.isNotBlank() && (field.key !in FieldKey.DATE_KEYS || runCatching { LocalDate.parse(value.trim()) }.isSuccess),
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { editing = null }
            )
        }
    }
    if (adding) AddInformationDialog(onAdd = { k, v -> onAdd(k, v); adding = false }, onDismiss = { adding = false })
}

private val addableKeys = listOf(
    FieldKey.REPORT_NAME, FieldKey.DOCTOR_NAME, FieldKey.FACILITY, FieldKey.REPORT_DATE, FieldKey.VISIT_DATE,
    FieldKey.DIAGNOSIS, FieldKey.MEDICATION, FieldKey.RECOMMENDATION, FieldKey.FOLLOW_UP_DATE, FieldKey.PATIENT_NAME
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddInformationDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var key by rememberSaveable { mutableStateOf(FieldKey.DIAGNOSIS) }
    var value by rememberSaveable { mutableStateOf("") }
    GlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.records_review_add), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            addableKeys.forEach { k -> RecordChip(text = stringResource(fieldLabelRes(k)), selected = k == key, onClick = { key = k }) }
        }
        if (key in FieldKey.DATE_KEYS) {
            Text(stringResource(R.string.records_review_date_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        GlassTextField(value = value, onValueChange = { value = it }, modifier = Modifier.heightIn(min = 48.dp))
        GlassDialogActions(
            primaryText = stringResource(R.string.action_save),
            onPrimary = { onAdd(key, value.trim()) },
            primaryEnabled = value.isNotBlank() && (key !in FieldKey.DATE_KEYS || runCatching { LocalDate.parse(value.trim()) }.isSuccess),
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss
        )
    }
}
