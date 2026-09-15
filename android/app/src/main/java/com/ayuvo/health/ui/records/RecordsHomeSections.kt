package com.ayuvo.health.ui.records

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.records.data.HighlightWithRecord
import com.ayuvo.health.records.data.ProcessingSummary
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.data.RecordSearchHit
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.ProcessingStatus
import com.ayuvo.health.records.model.RecordAdvancedFilters
import com.ayuvo.health.records.model.RecordCategory
import com.ayuvo.health.records.model.RecordTag
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.ReviewStatus
import com.ayuvo.health.records.processing.PipelineProgress
import com.ayuvo.health.records.search.ParsedChip
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.theme.AppColors
import java.time.LocalDate

private val ReviewAmber = Color(0xFFE8A33D)

/** "Processing 3 records · 2 of 14 pages" (plan §2 Records home). */
@Composable
internal fun ProcessingStrip(summary: ProcessingSummary, progress: PipelineProgress?, modifier: Modifier = Modifier) {
    if (summary.processing <= 0) return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.Calorie)
        Spacer(Modifier.width(8.dp))
        val base = pluralStringResource(R.plurals.records_processing_count, summary.processing, summary.processing)
        val pages = progress?.takeIf { it.pagesTotal > 1 }?.let { stringResource(R.string.records_processing_pages, it.pagesDone, it.pagesTotal) }
        Text(
            listOfNotNull(base, pages).joinToString(" · "),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
    }
}

/** Small per-row status: processing, waiting for AI, needs review. */
@Composable
internal fun RecordStatusPill(record: HealthRecord) {
    val (label, color) = when {
        record.isProcessing -> stringResource(R.string.records_status_processing) to AppColors.Calorie
        record.processingStatus == ProcessingStatus.AI_PENDING_CONSENT -> stringResource(R.string.records_status_waiting_ai) to AppColors.Calorie
        record.reviewStatus == ReviewStatus.NEEDS_REVIEW -> stringResource(R.string.records_filter_needs_review) to ReviewAmber
        else -> return
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (record.isProcessing) {
            CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.5.dp, color = color)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
}

/** Needs review: records waiting for the user's confirmation. */
@Composable
internal fun NeedsReviewSection(records: List<HealthRecord>, files: RecordFileStore, onOpen: (HealthRecord) -> Unit) {
    if (records.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.records_filter_needs_review))
        GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 0.dp) {
            Column {
                records.forEach { record ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(record) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RecordThumbnail(record = record, files = files, size = 36.dp, cornerRadius = 10.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(record.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (record.processingStatus == ProcessingStatus.AI_PENDING_CONSENT) stringResource(R.string.records_review_row_ai)
                                else stringResource(R.string.records_review_row_hint),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1
                            )
                        }
                        Icon(Icons.Filled.PriorityHigh, null, tint = ReviewAmber, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** Important highlights from the report's own flags (§15), newest records first. */
@Composable
internal fun HighlightsSection(items: List<HighlightWithRecord>, onOpen: (HighlightWithRecord) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.records_highlights_title))
        GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 0.dp) {
            Column {
                items.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(item) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(AppColors.Calorie))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.highlight.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${item.record.title} · ${RecordFormat.displayDate(item.record)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Removable chips for what the parser understood ("Sep 2026", "abnormal"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ParsedChipsRow(chips: List<ParsedChip>, onRemove: (ParsedChip) -> Unit) {
    if (chips.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chips.forEach { chip ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(AppColors.Calorie.copy(alpha = 0.14f))
                    .clickable { onRemove(chip) }
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(chip.label, fontSize = 13.sp, color = AppColors.Calorie, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.Close, stringResource(R.string.action_remove), tint = AppColors.Calorie, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** A search result with its matched snippet (`[term]` highlighted). */
@Composable
internal fun SearchHitRow(hit: RecordSearchHit, files: RecordFileStore, onClick: () -> Unit) {
    val record = hit.record
    GlassSurface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        cornerRadius = 18.dp,
        padding = 10.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordThumbnail(record = record, files = files)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(record.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    RecordFormat.displayDate(record) + if (record.recordType != RecordType.OTHER) " · " + stringResource(record.recordType.labelRes()) else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1
                )
                hit.snippet?.let { snippet ->
                    Text(
                        snippetText(snippet),
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
            RecordStatusPill(record)
        }
    }
}

private fun snippetText(snippet: String) = buildAnnotatedString {
    var i = 0
    while (i < snippet.length) {
        val open = snippet.indexOf('[', i)
        if (open < 0) {
            append(snippet.substring(i).replace('\n', ' '))
            break
        }
        append(snippet.substring(i, open).replace('\n', ' '))
        val close = snippet.indexOf(']', open + 1)
        if (close < 0) {
            append(snippet.substring(open + 1))
            break
        }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = AppColors.Calorie)) { append(snippet.substring(open + 1, close)) }
        i = close + 1
    }
}

/** "Search smarter with AI" (§17): sends only the query text. */
@Composable
internal fun AiSearchRow(running: Boolean, onClick: () -> Unit) {
    GlassSurface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = !running, onClick = onClick),
        cornerRadius = 18.dp,
        padding = 14.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Calorie)
            else Icon(Icons.Filled.AutoAwesome, null, tint = AppColors.Calorie, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.records_search_ai), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.records_search_ai_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = AppColors.Calorie, modifier = Modifier.size(18.dp))
        }
    }
}

/** The "Filters" chip with its active count. */
@Composable
internal fun FiltersChip(count: Int, onClick: () -> Unit) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .then(
                if (count > 0) Modifier.background(AppColors.CalorieGradient)
                else Modifier
                    .background(if (isDark) Color.White.copy(alpha = 0.07f) else Color(0xFFEDE3DD).copy(alpha = 0.72f))
                    .border(0.6.dp, Color.White.copy(alpha = if (isDark) 0.08f else 0.5f), shape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (count > 0) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
        Icon(Icons.Filled.Tune, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            if (count > 0) stringResource(R.string.records_filters_count, count) else stringResource(R.string.records_filters),
            fontSize = 14.sp,
            fontWeight = if (count > 0) FontWeight.SemiBold else FontWeight.Medium,
            color = tint,
            maxLines = 1
        )
    }
}

/** Advanced Filters sheet (plan §2): date range, doctor, hospital, category, type, abnormal, tags, AI, confirmed. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RecordsFilterSheet(
    initial: RecordAdvancedFilters,
    tags: List<RecordTag>,
    onApply: (RecordAdvancedFilters) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val context = LocalContext.current
    var filters by remember { mutableStateOf(initial) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = if (isDark) Color(0xF2141416) else Color(0xFFFAF3EE)
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
            Text(stringResource(R.string.records_filters), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            FilterLabel(stringResource(R.string.records_filter_date_range))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                fun pick(current: String?, onPicked: (String?) -> Unit) {
                    val initialDate = current?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
                    DatePickerDialog(context, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d).toString()) }, initialDate.year, initialDate.monthValue - 1, initialDate.dayOfMonth).show()
                }
                GlassTextButton(
                    text = filters.dateFrom?.let { RecordFormat.date(LocalDate.parse(it)) } ?: stringResource(R.string.records_filter_from),
                    onClick = { pick(filters.dateFrom) { filters = filters.copy(dateFrom = it) } },
                    modifier = Modifier.weight(1f)
                )
                GlassTextButton(
                    text = filters.dateTo?.let { RecordFormat.date(LocalDate.parse(it)) } ?: stringResource(R.string.records_filter_to),
                    onClick = { pick(filters.dateTo) { filters = filters.copy(dateTo = it) } },
                    modifier = Modifier.weight(1f)
                )
            }

            FilterLabel(stringResource(R.string.records_filter_doctor))
            GlassTextField(
                value = filters.doctor.orEmpty(),
                onValueChange = { filters = filters.copy(doctor = it.trim().lowercase().ifEmpty { null }) },
                placeholder = stringResource(R.string.records_filter_doctor_hint)
            )
            FilterLabel(stringResource(R.string.records_filter_hospital))
            GlassTextField(
                value = filters.facility.orEmpty(),
                onValueChange = { filters = filters.copy(facility = it.trim().lowercase().ifEmpty { null }) },
                placeholder = stringResource(R.string.records_filter_hospital_hint)
            )

            FilterLabel(stringResource(R.string.records_field_category))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordCategory.entries.forEach { category ->
                    RecordChip(
                        text = stringResource(category.labelRes()),
                        selected = category in filters.categories,
                        onClick = { filters = filters.copy(categories = filters.categories.toggle(category)) }
                    )
                }
            }
            FilterLabel(stringResource(R.string.records_filter_report_type))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordType.entries.forEach { type ->
                    RecordChip(
                        text = stringResource(type.labelRes()),
                        selected = type in filters.types,
                        onClick = { filters = filters.copy(types = filters.types.toggle(type)) }
                    )
                }
            }
            if (tags.isNotEmpty()) {
                FilterLabel(stringResource(R.string.records_field_tags))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag ->
                        RecordChip(text = tag.name, selected = tag.id in filters.tagIds, onClick = { filters = filters.copy(tagIds = filters.tagIds.toggle(tag.id)) })
                    }
                }
            }
            FilterToggle(stringResource(R.string.records_filter_abnormal), "abnormal" in filters.flags) {
                filters = filters.copy(flags = if (it) filters.flags + "abnormal" else filters.flags - "abnormal")
            }
            FilterToggle(stringResource(R.string.records_filter_ai_processed), filters.aiProcessed) { filters = filters.copy(aiProcessed = it) }
            FilterToggle(stringResource(R.string.records_filter_user_confirmed), filters.userConfirmed) { filters = filters.copy(userConfirmed = it) }

            GlassPrimaryButton(text = stringResource(R.string.records_filters_apply), onClick = { onApply(filters) })
            GlassTextButton(
                text = stringResource(R.string.records_filters_reset),
                onClick = { onApply(RecordAdvancedFilters()) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

@Composable
private fun FilterLabel(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
}

@Composable
private fun FilterToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Calorie)
        )
    }
}
