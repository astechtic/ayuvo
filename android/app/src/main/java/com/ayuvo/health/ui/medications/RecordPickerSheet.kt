package com.ayuvo.health.ui.medications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.RecordFilter
import com.ayuvo.health.records.model.RecordQuery
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.records.RecordFormat
import com.ayuvo.health.ui.records.RecordThumbnail
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Single-select picker over prescriptions and medication lists (docs/medications.md §13), the
 * `CoachRecordsPickerSheet` shape with a radio instead of a checkbox. Never creates the records
 * database: without one it just shows the empty text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordPickerSheet(
    container: AppContainer,
    selectedId: String?,
    onPick: (HealthRecord) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<HealthRecord>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) delay(150)
        candidates = if (container.recordsDatabaseExists()) {
            runCatching {
                container.recordsStore.page(RecordQuery(filter = RecordFilter.PRESCRIPTIONS, search = query), after = null, limit = 40).items
            }.getOrDefault(emptyList())
        } else emptyList()
        loaded = true
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = medicationSheetColor()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.medications_record_picker_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.medications_record_picker_subtitle),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            GlassTextField(value = query, onValueChange = { query = it }, placeholder = stringResource(R.string.medications_record_picker_search))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (loaded && candidates.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.medications_record_picker_empty),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                items(candidates, key = { it.id }) { record ->
                    val selected = record.id == selectedId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .clickable { onPick(record) }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = if (selected) stringResource(R.string.sheet_selected_a11y) else null,
                            tint = AppColors.Calorie
                        )
                        Spacer(Modifier.width(10.dp))
                        RecordThumbnail(record = record, files = container.recordFiles, size = 36.dp, cornerRadius = 10.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(record.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(RecordFormat.displayDate(record), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}
