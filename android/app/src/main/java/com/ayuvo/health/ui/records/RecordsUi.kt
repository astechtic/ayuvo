package com.ayuvo.health.ui.records

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ayuvo.health.R
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.RecordCategory
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordFilter
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.ui.theme.AppColors
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@StringRes
internal fun RecordType.labelRes(): Int = when (this) {
    RecordType.LAB_REPORT -> R.string.records_type_lab_report
    RecordType.PRESCRIPTION -> R.string.records_type_prescription
    RecordType.CONSULTATION_NOTE -> R.string.records_type_consultation_note
    RecordType.DISCHARGE_SUMMARY -> R.string.records_type_discharge_summary
    RecordType.IMAGING_REPORT -> R.string.records_type_imaging_report
    RecordType.DIAGNOSTIC_REPORT -> R.string.records_type_diagnostic_report
    RecordType.MEDICATION_LIST -> R.string.records_type_medication_list
    RecordType.VACCINATION_RECORD -> R.string.records_type_vaccination_record
    RecordType.BILL -> R.string.records_type_bill
    RecordType.INSURANCE -> R.string.records_type_insurance
    RecordType.PERSONAL_NOTE -> R.string.records_type_personal_note
    RecordType.OTHER -> R.string.records_type_other
}

@StringRes
internal fun RecordCategory.labelRes(): Int = when (this) {
    RecordCategory.LAB_REPORTS -> R.string.records_category_lab_reports
    RecordCategory.PRESCRIPTIONS -> R.string.records_category_prescriptions
    RecordCategory.DOCTOR_VISITS -> R.string.records_category_doctor_visits
    RecordCategory.IMAGING -> R.string.records_category_imaging
    RecordCategory.HOSPITALIZATION -> R.string.records_category_hospitalization
    RecordCategory.PROCEDURES -> R.string.records_category_procedures
    RecordCategory.VACCINATION -> R.string.records_category_vaccination
    RecordCategory.MEDICATION -> R.string.records_category_medication
    RecordCategory.INSURANCE_BILLS -> R.string.records_category_insurance_bills
    RecordCategory.PERSONAL_NOTES -> R.string.records_category_personal_notes
    RecordCategory.OTHER -> R.string.records_category_other
}

@StringRes
internal fun RecordFilter.labelRes(): Int = when (this) {
    RecordFilter.ALL -> R.string.records_filter_all
    RecordFilter.REPORTS -> R.string.records_filter_reports
    RecordFilter.PRESCRIPTIONS -> R.string.records_filter_prescriptions
    RecordFilter.LAB -> R.string.records_filter_lab
    RecordFilter.IMAGING -> R.string.records_filter_imaging
    RecordFilter.DOCTOR_NOTES -> R.string.records_filter_doctor_notes
    RecordFilter.DISCHARGE -> R.string.records_filter_discharge
    RecordFilter.BILLS -> R.string.records_filter_bills
    RecordFilter.IMAGES -> R.string.records_filter_images
    RecordFilter.PDFS -> R.string.records_filter_pdfs
    RecordFilter.NOTES -> R.string.records_filter_notes
    RecordFilter.RECEIVED -> R.string.records_filter_received
    RecordFilter.FAVORITES -> R.string.records_filter_favorites
    RecordFilter.ARCHIVED -> R.string.records_filter_archived
}

@StringRes
internal fun RecordsViewMode.labelRes(): Int = when (this) {
    RecordsViewMode.TIMELINE -> R.string.records_view_timeline
    RecordsViewMode.LIST -> R.string.records_view_list
    RecordsViewMode.GRID -> R.string.records_view_grid
}

internal fun RecordFileType.icon(): ImageVector = when (this) {
    RecordFileType.PDF -> Icons.Filled.PictureAsPdf
    RecordFileType.IMAGE -> Icons.Filled.Image
    RecordFileType.TEXT -> Icons.AutoMirrored.Filled.Notes
    RecordFileType.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

internal object RecordFormat {
    private val medium: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    /** The date shown on rows: `sort_date` (document date, else the local import day). */
    fun displayDate(record: HealthRecord): String {
        val date = runCatching { LocalDate.parse(record.sortDate) }.getOrNull()
            ?: Instant.ofEpochMilli(record.createdMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return date.format(medium)
    }

    fun date(date: LocalDate): String = date.format(medium)

    fun addedAt(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

    /** "September 2026" for the timeline month header of a `sort_date` (`yyyy-MM-dd`). */
    fun monthHeader(sortDate: String): String {
        val month = runCatching { YearMonth.parse(sortDate.take(7)) }.getOrNull() ?: return sortDate
        return month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    fun bytes(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format(Locale.getDefault(), "%.0f KB", size / 1024.0)
        size < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024))
        else -> String.format(Locale.getDefault(), "%.2f GB", size / (1024.0 * 1024 * 1024))
    }
}

/** 320 px thumbnail from `thumb.jpg`, or a tinted file-type tile when there is none. */
@Composable
internal fun RecordThumbnail(
    record: HealthRecord,
    files: RecordFileStore,
    modifier: Modifier = Modifier,
    size: Dp? = 48.dp,
    cornerRadius: Dp = 12.dp
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(cornerRadius)
    val base = (if (size != null) modifier.size(size) else modifier)
        .clip(shape)
        .background(if (isDark) Color.White.copy(alpha = 0.06f) else AppColors.Calorie.copy(alpha = 0.08f))
        .border(0.6.dp, Color.White.copy(alpha = if (isDark) 0.08f else 0.5f), shape)
    val thumb = remember(record.thumbnailPath) { files.resolve(record.thumbnailPath) }
    Box(base, contentAlignment = Alignment.Center) {
        if (thumb != null) {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                record.fileType.icon(),
                contentDescription = null,
                tint = AppColors.Calorie,
                modifier = Modifier.size(if (size != null) size * 0.5f else 34.dp)
            )
        }
    }
}

@Composable
internal fun RecordChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(50)
    val selectedModifier = if (selected) {
        Modifier.background(AppColors.CalorieGradient)
    } else {
        Modifier
            .background(if (isDark) Color.White.copy(alpha = 0.07f) else Color(0xFFEDE3DD).copy(alpha = 0.72f))
            .border(0.6.dp, Color.White.copy(alpha = if (isDark) 0.08f else 0.5f), shape)
    }
    Box(
        modifier
            .clip(shape)
            .then(selectedModifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
            maxLines = 1
        )
    }
}
