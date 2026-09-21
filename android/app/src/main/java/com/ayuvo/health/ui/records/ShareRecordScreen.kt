package com.ayuvo.health.ui.records

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.share.RedactionClass
import com.ayuvo.health.records.share.ShareBuild
import com.ayuvo.health.records.share.SharePlan
import com.ayuvo.health.records.share.ShareWarningItem
import com.ayuvo.health.records.share.SummaryField
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareRecordUiState(
    val loading: Boolean = true,
    val records: List<HealthRecord> = emptyList(),
    val plan: SharePlan = SharePlan(emptyList()),
    val building: Boolean = false,
    val build: ShareBuild? = null,
    val failed: Boolean = false
)

/**
 * "What will be shared" (docs/health-records.md §34): the checklist, then a page-1 preview of each
 * produced file, then the final confirm that opens the system share sheet.
 */
class ShareRecordViewModel(private val container: AppContainer, private val recordIds: List<String>) : ViewModel() {
    private val store get() = container.recordsStore
    private val _ui = MutableStateFlow(ShareRecordUiState())
    val ui: StateFlow<ShareRecordUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { store.records(recordIds) }
                .onSuccess { found ->
                    val ordered = recordIds.mapNotNull { id -> found.firstOrNull { it.id == id } }
                    _ui.update { it.copy(loading = false, records = ordered, plan = SharePlan(ordered.map { r -> r.id })) }
                }
                .onFailure { _ui.update { it.copy(loading = false) } }
        }
    }

    /** Any plan change invalidates the built files, so the preview always matches the choices. */
    fun update(transform: (SharePlan) -> SharePlan) = _ui.update {
        it.copy(plan = transform(it.plan), build = null, failed = false)
    }

    fun togglePage(recordId: String, page: Int, pageCount: Int) = update { plan ->
        val current = plan.pagesOf(recordId, pageCount) ?: (0 until pageCount).toList()
        val next = if (page in current) current - page else (current + page).sorted()
        plan.copy(pages = plan.pages + (recordId to next.ifEmpty { listOf(page) }))
    }

    fun selectAllPages(recordId: String) = update { it.copy(pages = it.pages - recordId) }

    fun build() {
        if (_ui.value.plan.isEmpty || _ui.value.building) return
        _ui.update { it.copy(building = true, failed = false) }
        viewModelScope.launch {
            runCatching { container.recordShareBuilder.build(_ui.value.plan) }
                .onSuccess { result -> _ui.update { it.copy(building = false, build = result, failed = result.items.isEmpty()) } }
                .onFailure { _ui.update { it.copy(building = false, failed = true) } }
        }
    }

    /** `ACTION_SEND_MULTIPLE` over the FileProvider `records_share` path (§34). */
    fun shareIntent(context: Context): Intent? {
        val build = _ui.value.build ?: return null
        if (build.items.isEmpty()) return null
        val authority = "${context.packageName}.fileprovider"
        val uris = build.items.mapNotNull { item ->
            runCatching { FileProvider.getUriForFile(context, authority, item.file) }.getOrNull()
        }
        if (uris.isEmpty()) return null
        val mime = build.items.map { it.mimeType }.distinct().singleOrNull() ?: "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            }
        }
        build.summaryText?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    /** §34: `shared_count` + 1 and `last_shared_ms` once the share sheet was opened. */
    fun onShared() {
        val ids = _ui.value.build?.sharedRecordIds.orEmpty()
        if (ids.isEmpty()) return
        viewModelScope.launch { runCatching { store.markShared(ids) } }
    }

    class Factory(private val container: AppContainer, private val recordIds: List<String>) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ShareRecordViewModel(container, recordIds) as T
    }
}

@Composable
fun ShareRecordScreen(container: AppContainer, recordIds: List<String>, onBack: () -> Unit) {
    val vm: ShareRecordViewModel = viewModel(
        key = "share-${recordIds.joinToString(",")}",
        factory = ShareRecordViewModel.Factory(container, recordIds)
    )
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val plan = ui.plan

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.records_title), tint = AppColors.Calorie) }
            Text(stringResource(R.string.records_share_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppColors.Calorie) }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "originals") {
                ShareCard {
                    CheckRow(stringResource(R.string.records_share_original), plan.includeOriginal) { on ->
                        vm.update { it.copy(includeOriginal = on) }
                    }
                    if (plan.includeOriginal) {
                        for (record in ui.records) {
                            val pageCount = if (record.isSplitChild) record.pageEnd!! - record.pageStart!! + 1 else record.pageCount.coerceAtLeast(1)
                            Text(
                                record.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 12.dp, top = 6.dp)
                            )
                            if (record.fileType == RecordFileType.PDF && pageCount > 1) {
                                val selected = plan.pagesOf(record.id, pageCount)
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    contentPadding = PaddingValues(start = 12.dp, top = 4.dp, bottom = 2.dp)
                                ) {
                                    item(key = "all") {
                                        PageChip(stringResource(R.string.records_share_all_pages), selected == null) { vm.selectAllPages(record.id) }
                                    }
                                    items((0 until pageCount).toList(), key = { it }) { page ->
                                        PageChip("${page + 1}", selected != null && page in selected) {
                                            vm.togglePage(record.id, page, pageCount)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "summary") {
                ShareCard {
                    CheckRow(stringResource(R.string.records_share_summary), plan.includeSummary) { on ->
                        vm.update { it.copy(includeSummary = on) }
                    }
                    if (plan.includeSummary) {
                        for (field in SummaryField.entries) {
                            CheckRow(stringResource(field.labelRes()), field in plan.summaryFields, indent = true) { on ->
                                vm.update { it.with(field, on) }
                            }
                        }
                        CheckRow(stringResource(R.string.records_share_highlights), plan.includeHighlights, indent = true) { on ->
                            vm.update { it.copy(includeHighlights = on) }
                        }
                        CheckRow(stringResource(R.string.records_share_notes), plan.includeNotes, indent = true) { on ->
                            vm.update { it.copy(includeNotes = on) }
                        }
                    }
                }
            }

            item(key = "redactions") {
                ShareCard {
                    Text(
                        stringResource(R.string.records_share_redact_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                    )
                    for (redaction in RedactionClass.entries) {
                        CheckRow(stringResource(redaction.labelRes()), redaction in plan.redactions, indent = true) { on ->
                            vm.update { it.with(redaction, on) }
                        }
                    }
                    if (plan.redactions.isNotEmpty()) {
                        Text(
                            stringResource(R.string.records_share_redact_note),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                        )
                    }
                }
            }

            val build = ui.build
            if (build != null) {
                if (build.warnings.isNotEmpty()) {
                    item(key = "warnings") {
                        ShareCard {
                            for (warning: ShareWarningItem in build.warnings) {
                                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Filled.Warning, null, tint = AppColors.Calorie, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(warning.text, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                items(build.items, key = { it.file.path }) { item ->
                    ShareCard {
                        Text(item.file.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
                        val preview = item.preview
                        if (preview != null) {
                            Spacer(Modifier.height(8.dp))
                            AsyncImage(
                                model = preview,
                                contentDescription = stringResource(R.string.records_share_preview),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)
                            )
                        } else if (item.mimeType == "text/plain") {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                build.summaryText.orEmpty().take(900),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
            item(key = "spacer") { Spacer(Modifier.height(24.dp)) }
        }

        // The bottom bar clears the floating tab bar, so the confirm button is always reachable.
        GlassSurface(Modifier.fillMaxWidth().padding(bottom = BottomNavScrollPadding), padding = 0.dp) {
            Column(Modifier.padding(16.dp)) {
                if (ui.failed) {
                    Text(stringResource(R.string.records_share_failed), fontSize = 12.sp, color = AppColors.Calorie)
                    Spacer(Modifier.height(8.dp))
                } else if (plan.isEmpty) {
                    Text(stringResource(R.string.records_share_nothing), fontSize = 12.sp, color = AppColors.Calorie)
                    Spacer(Modifier.height(8.dp))
                }
                val build = ui.build
                GlassPrimaryButton(
                    text = when {
                        ui.building -> stringResource(R.string.records_share_building)
                        build == null -> stringResource(R.string.records_share_preview)
                        build.items.size == 1 -> stringResource(R.string.records_share_confirm_one)
                        else -> stringResource(R.string.records_share_confirm, build.items.size)
                    },
                    enabled = !ui.building && !plan.isEmpty,
                    onClick = {
                        if (build == null) {
                            vm.build()
                        } else {
                            val intent = vm.shareIntent(context)
                            if (intent != null) {
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    )
                                }.onSuccess { vm.onShared() }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ShareCard(content: @Composable () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), padding = 0.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) { content() }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, indent: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = if (indent) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, fontSize = if (indent) 14.sp else 15.sp, fontWeight = if (indent) FontWeight.Normal else FontWeight.SemiBold)
    }
}

@Composable
private fun PageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) AppColors.Calorie.copy(alpha = 0.18f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

internal fun SummaryField.labelRes(): Int = when (this) {
    SummaryField.DOCTOR -> R.string.records_share_field_doctor
    SummaryField.FACILITY -> R.string.records_share_field_facility
    SummaryField.PATIENT_NAME -> R.string.records_share_field_patient_name
    SummaryField.DATES -> R.string.records_share_field_dates
    SummaryField.TEST_RESULTS -> R.string.records_share_field_test_results
    SummaryField.MEDICATIONS -> R.string.records_share_field_medications
    SummaryField.DIAGNOSES -> R.string.records_share_field_diagnoses
    SummaryField.RECOMMENDATIONS -> R.string.records_share_field_recommendations
}

internal fun RedactionClass.labelRes(): Int = when (this) {
    RedactionClass.NAME -> R.string.records_share_redact_name
    RedactionClass.ADDRESS -> R.string.records_share_redact_address
    RedactionClass.PHONE -> R.string.records_share_redact_phone
    RedactionClass.PATIENT_ID -> R.string.records_share_redact_patient_id
    RedactionClass.INSURANCE_ID -> R.string.records_share_redact_insurance_id
    RedactionClass.OTHER_IDS -> R.string.records_share_redact_other_ids
}
