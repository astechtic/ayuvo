package com.ayuvo.health.ui.records

import android.app.DatePickerDialog
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.records.ingest.ThumbnailMaker
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.ProcessingStatus
import com.ayuvo.health.records.model.RecordCategory
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordSource
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.components.OptionPickerSheet
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private enum class DetailEdit { TITLE, NOTES, TAG, TYPE, CATEGORY, DELETE }

/** Record detail (Phase 1): viewer, editable details, tags, notes, favorite/archive/delete, export. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordDetailScreen(
    container: AppContainer,
    recordId: String,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit = {},
    onOpenSplit: (String) -> Unit = {},
    onOpenTrend: (String) -> Unit = {},
    focusObservationId: String? = null
) {
    val vm: RecordDetailViewModel = viewModel(
        key = "record-$recordId-${focusObservationId.orEmpty()}",
        factory = RecordDetailViewModel.Factory(container, recordId, focusObservationId)
    )
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var edit by rememberSaveable { mutableStateOf<DetailEdit?>(null) }
    var showReview by rememberSaveable { mutableStateOf(false) }
    var showDuplicates by rememberSaveable { mutableStateOf(false) }
    var editingObservationId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingObservation by rememberSaveable { mutableStateOf(false) }
    var linkingRecord by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val exportFailed = stringResource(R.string.records_export_failed)
    val noApp = stringResource(R.string.records_no_app)

    fun export(view: Boolean) {
        scope.launch {
            val shared = vm.shareableUri(context)
            if (shared == null) {
                Toast.makeText(context, exportFailed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (uri, mime) = shared
            val intent = if (view) vm.viewIntent(uri, mime) else vm.shareIntent(uri, mime)
            runCatching {
                context.startActivity(
                    if (view) intent else Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }.onFailure { Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show() }
        }
    }

    val record = ui.record
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.records_title), tint = AppColors.Calorie)
            }
            Text(
                record?.title.orEmpty(),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (record != null) {
                IconButton(onClick = { vm.toggleFavorite() }) {
                    Icon(
                        if (record.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        stringResource(R.string.records_action_favorite),
                        tint = AppColors.Calorie
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.records_more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.records_action_rename)) },
                            onClick = { menuOpen = false; edit = DetailEdit.TITLE }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.records_action_export)) },
                            onClick = { menuOpen = false; export(view = false) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.records_action_reprocess)) },
                            onClick = { menuOpen = false; vm.reprocess() }
                        )
                        val options = ui.aiOptions
                        if (record.aiModeUsed == com.ayuvo.health.records.model.AiModeUsed.NONE && options != null) {
                            if (options.localAvailable) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.records_action_find_more_ai) + " · " + stringResource(R.string.records_method_ai_local)) },
                                    onClick = { menuOpen = false; vm.decideAi("local") }
                                )
                            }
                            if (options.cloudConfigured) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.records_action_find_more_ai) + " · " + stringResource(R.string.records_method_ai_cloud)) },
                                    onClick = { menuOpen = false; vm.decideAi("cloud") }
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(if (record.archived) R.string.records_action_unarchive else R.string.records_action_archive)) },
                            onClick = { menuOpen = false; vm.toggleArchived() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; edit = DetailEdit.DELETE }
                        )
                    }
                }
            }
        }

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.Calorie)
            }
            record == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.records_detail_missing), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                RecordViewer(
                    record = record,
                    file = vm.originalFile(),
                    pages = ui.pages,
                    height = if (record.fileType == RecordFileType.TEXT) 260.dp else 440.dp,
                    renderCache = container.recordFiles.renderPages,
                    onOpenElsewhere = { export(view = true) },
                    pageRange = if (record.isSplitChild) record.pageStart!!..record.pageEnd!! else null,
                    focus = ui.focus
                )
                LaunchedEffect(ui.focus?.token) { if (ui.focus != null) scrollState.animateScrollTo(0) }
                val intelligence = ui.intelligence
                DetailStatusLine(record, intelligence)
                val options = ui.aiOptions
                if (intelligence?.job?.awaitingConsent == true && options != null) {
                    AiConsentBanner(
                        options = options,
                        waitingCount = ui.waitingForAi,
                        onLocal = { vm.decideAi("local") },
                        onCloud = { vm.decideAi("cloud") },
                        onNotNow = { vm.decideAi("off") },
                        onApplyAll = if (ui.waitingForAi > 1) { mode -> vm.decideAiForAll(mode) } else null
                    )
                }
                if (intelligence != null) {
                    DetailBanners(
                        record = record,
                        intelligence = intelligence,
                        reviewCount = if (record.reviewStatus == com.ayuvo.health.records.model.ReviewStatus.REVIEWED) 0 else ui.reviewItems.size,
                        onReview = { showReview = true },
                        onDuplicates = { showDuplicates = true },
                        onSplit = { onOpenSplit(record.id) },
                        onOpenRecord = onOpenRecord
                    )
                    HighlightsCard(intelligence.highlights, onFocusPage = vm::focusPage, onDismiss = vm::dismissHighlight)
                    ExtractedInfoCard(intelligence.fields.filter { it.key != com.ayuvo.health.records.model.FieldKey.TEST_RESULT || ui.observations.isEmpty() }, onFocus = vm::focusField, onEdit = { showReview = true })
                    if (ui.observations.isNotEmpty() || record.recordType == RecordType.LAB_REPORT || record.recordType == RecordType.DIAGNOSTIC_REPORT) {
                        HealthDataPointsCard(
                            observations = ui.observations,
                            trends = ui.trends,
                            catalog = ui.catalog,
                            onRow = { editingObservationId = it.id },
                            onViewTrend = onOpenTrend,
                            onAdd = { addingObservation = true }
                        )
                    }
                    val usable = intelligence.fields.filter { it.state != com.ayuvo.health.records.model.FieldState.REJECTED }
                    ListCard(
                        R.string.records_medications_title,
                        intelligence.highlights.filter { it.section == com.ayuvo.health.records.model.HighlightSection.MEDICATIONS && !it.dismissed }
                            .map { it.text to it.sourcePage }
                            .ifEmpty { usable.filter { it.key == com.ayuvo.health.records.model.FieldKey.MEDICATION }.map { fieldDisplayValue(it) to it.sourcePage } },
                        onFocusPage = vm::focusPage
                    )
                    ListCard(
                        R.string.records_recommendations_title,
                        intelligence.highlights.filter { it.section == com.ayuvo.health.records.model.HighlightSection.RECOMMENDATIONS && !it.dismissed }
                            .map { it.text to it.sourcePage }
                            .ifEmpty { usable.filter { it.key == com.ayuvo.health.records.model.FieldKey.RECOMMENDATION }.map { it.valueText to it.sourcePage } },
                        onFocusPage = vm::focusPage
                    )
                }
                if (record.processingStatus == ProcessingStatus.FAILED_PARTIAL && record.processingError == ThumbnailMaker.ERROR_PROTECTED_PDF) {
                    Text(
                        stringResource(R.string.records_protected_note),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }

                DetailCard {
                    DetailRow(stringResource(R.string.records_field_title), record.title) { edit = DetailEdit.TITLE }
                    HorizontalDivider()
                    DetailRow(
                        stringResource(R.string.records_field_date),
                        record.documentDate?.let { runCatching { RecordFormat.date(LocalDate.parse(it)) }.getOrNull() }
                            ?: stringResource(R.string.records_date_unknown)
                    ) {
                        val initial = record.documentDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                            ?: Instant.ofEpochMilli(record.createdMs).atZone(ZoneId.systemDefault()).toLocalDate()
                        DatePickerDialog(
                            context,
                            { _, year, month, day -> vm.setDate(LocalDate.of(year, month + 1, day)) },
                            initial.year,
                            initial.monthValue - 1,
                            initial.dayOfMonth
                        ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                    }
                    HorizontalDivider()
                    DetailRow(stringResource(R.string.records_field_type), stringResource(record.recordType.labelRes())) { edit = DetailEdit.TYPE }
                    HorizontalDivider()
                    DetailRow(stringResource(R.string.records_field_category), stringResource(record.category.labelRes())) { edit = DetailEdit.CATEGORY }
                }

                DetailCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.records_field_tags), fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ui.tags.forEach { tag ->
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable { vm.removeTag(tag) }
                                        .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(tag.name, fontSize = 14.sp, color = AppColors.Calorie, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Filled.Close, stringResource(R.string.action_remove), tint = AppColors.Calorie, modifier = Modifier.size(14.dp))
                                }
                            }
                            RecordChip(text = stringResource(R.string.records_add_tag), selected = false, onClick = { edit = DetailEdit.TAG })
                        }
                    }
                }

                DetailCard {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { edit = DetailEdit.NOTES }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(stringResource(R.string.records_field_notes), fontWeight = FontWeight.SemiBold)
                        Text(
                            record.notes?.takeIf { it.isNotBlank() } ?: stringResource(R.string.records_add_notes),
                            color = if (record.notes.isNullOrBlank()) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }

                DetailCard {
                    InfoRow(stringResource(R.string.records_field_file), fileSummary(record))
                    HorizontalDivider()
                    InfoRow(stringResource(R.string.records_field_added), RecordFormat.addedAt(record.createdMs))
                    HorizontalDivider()
                    InfoRow(stringResource(R.string.records_field_source), stringResource(record.source.labelRes()))
                    record.originalFilename?.let {
                        HorizontalDivider()
                        InfoRow(stringResource(R.string.records_field_filename), it)
                    }
                }

                RelatedRecordsCard(
                    related = ui.related,
                    files = container.recordFiles,
                    onOpen = { onOpenRecord(it.id) },
                    onAccept = vm::acceptLink,
                    onReject = vm::rejectLink,
                    onUnlink = vm::unlink,
                    onLinkRecord = { linkingRecord = true }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextButton(
                        text = stringResource(R.string.records_action_export),
                        onClick = { export(view = false) },
                        modifier = Modifier.weight(1f)
                    )
                    GlassTextButton(
                        text = stringResource(if (record.archived) R.string.records_action_unarchive else R.string.records_action_archive),
                        onClick = { vm.toggleArchived() },
                        modifier = Modifier.weight(1f)
                    )
                    GlassTextButton(
                        text = stringResource(R.string.action_delete),
                        onClick = { edit = DetailEdit.DELETE },
                        color = Color(0xFFFF453A),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.heightIn(min = BottomNavScrollPadding))
            }
        }
    }

    if (record != null && showReview) {
        ReviewDetailsSheet(
            items = ui.reviewItems,
            onConfirm = { vm.confirmField(it.id) },
            onEdit = { field, value -> vm.editField(field.id, value) },
            onIgnore = { vm.rejectField(it.id) },
            onConfirmAll = {
                vm.confirmAll()
                showReview = false
            },
            onAdd = { key, value -> vm.addField(key, value) },
            onFocus = {
                showReview = false
                vm.focusField(it)
            },
            onDismiss = { showReview = false }
        )
    }

    val editingObservation = editingObservationId?.let { id -> ui.observations.firstOrNull { it.id == id } }
    if (record != null && editingObservation != null) {
        ObservationEditSheet(
            observation = editingObservation,
            catalog = ui.catalog,
            onSave = { edit ->
                vm.editObservation(editingObservation, edit)
                editingObservationId = null
            },
            onRemove = {
                vm.removeObservation(editingObservation)
                editingObservationId = null
            },
            onViewSource = if (editingObservation.sourcePage != null) {
                {
                    editingObservationId = null
                    vm.focusObservation(editingObservation)
                }
            } else null,
            onDismiss = { editingObservationId = null }
        )
    }
    if (record != null && addingObservation) {
        AddObservationSheet(
            catalog = ui.catalog,
            defaultDate = record.documentDate ?: record.sortDate,
            onSave = { analyteId, name, value, unit, date, ref ->
                vm.addObservation(analyteId, name, value, unit, date, ref)
                addingObservation = false
            },
            onDismiss = { addingObservation = false }
        )
    }
    if (record != null && linkingRecord) {
        LinkRecordSheet(
            candidates = ui.linkCandidates,
            files = container.recordFiles,
            onSearch = vm::searchLinkCandidates,
            onLink = { id, kind ->
                vm.linkTo(id, kind)
                linkingRecord = false
            },
            onDismiss = { linkingRecord = false }
        )
    }
    ui.aliasPrompt?.let { (rawName, _, count) ->
        GlassDialog(onDismissRequest = vm::dismissAliasPrompt) {
            Text(stringResource(R.string.records_obs_apply_others, count, rawName), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            GlassDialogActions(
                primaryText = stringResource(R.string.records_obs_apply_others_action),
                onPrimary = vm::applyAliasToOthers,
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = vm::dismissAliasPrompt
            )
        }
    }

    if (record != null && showDuplicates) {
        val pair = ui.duplicateMatches.firstOrNull()
        if (pair == null) {
            showDuplicates = false
        } else {
            val (candidate, match) = pair
            DuplicateRecordSheet(
                match = match,
                files = container.recordFiles,
                reason = candidate.reason,
                onKeepBoth = { vm.keepBoth(candidate); showDuplicates = false },
                onOpenExisting = {
                    showDuplicates = false
                    onOpenRecord(if (candidate.existingId == recordId) candidate.recordId else candidate.existingId)
                },
                onCancelImport = {
                    showDuplicates = false
                    vm.cancelNew(candidate) { if (candidate.recordId == recordId) onBack() }
                },
                onReplace = {
                    showDuplicates = false
                    vm.replaceExisting(candidate) {}
                    if (candidate.recordId == recordId) onBack()
                },
                onMerge = {
                    showDuplicates = false
                    vm.mergeIntoExisting(candidate) {}
                    if (candidate.recordId == recordId) onBack()
                }
            )
        }
    }

    if (record != null) {
        when (edit) {
            DetailEdit.TITLE -> TextEditDialog(
                title = stringResource(R.string.records_action_rename),
                initial = record.title,
                singleLine = true,
                allowEmpty = false,
                onDismiss = { edit = null },
                onSave = { vm.rename(it); edit = null }
            )
            DetailEdit.NOTES -> TextEditDialog(
                title = stringResource(R.string.records_field_notes),
                initial = record.notes.orEmpty(),
                singleLine = false,
                allowEmpty = true,
                onDismiss = { edit = null },
                onSave = { vm.setNotes(it); edit = null }
            )
            DetailEdit.TAG -> TextEditDialog(
                title = stringResource(R.string.records_add_tag),
                initial = "",
                singleLine = true,
                allowEmpty = false,
                suggestions = ui.allTags.map { it.name }.filter { name -> ui.tags.none { it.name.equals(name, ignoreCase = true) } },
                onDismiss = { edit = null },
                onSave = { vm.addTag(it); edit = null }
            )
            DetailEdit.TYPE -> OptionPickerSheet(
                title = stringResource(R.string.records_field_type),
                items = RecordType.entries,
                label = { stringResource(it.labelRes()) },
                selected = { it == record.recordType },
                onSelect = { vm.setType(it); edit = null },
                onDismiss = { edit = null }
            )
            DetailEdit.CATEGORY -> OptionPickerSheet(
                title = stringResource(R.string.records_field_category),
                items = RecordCategory.entries,
                label = { stringResource(it.labelRes()) },
                selected = { it == record.category },
                onSelect = { vm.setCategory(it); edit = null },
                onDismiss = { edit = null }
            )
            DetailEdit.DELETE -> GlassDialog(onDismissRequest = { edit = null }) {
                Text(stringResource(R.string.records_delete_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.records_delete_message), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
                GlassDialogActions(
                    primaryText = stringResource(R.string.action_delete),
                    onPrimary = {
                        edit = null
                        vm.delete(onDone = onBack)
                    },
                    destructive = true,
                    dismissText = stringResource(R.string.action_cancel),
                    onDismiss = { edit = null }
                )
            }
            null -> Unit
        }
    }
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 0.dp) {
        Column(Modifier.padding(vertical = 2.dp)) { content() }
    }
}

@Composable
private fun DetailRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.4f))
        Text(
            value,
            color = AppColors.Calorie,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(0.6f)
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.4f))
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(0.6f)
        )
    }
}

@Composable
private fun fileSummary(record: HealthRecord): String {
    val kind = when (record.fileType) {
        RecordFileType.PDF -> stringResource(R.string.records_file_pdf)
        RecordFileType.IMAGE -> stringResource(R.string.records_file_image)
        RecordFileType.TEXT -> stringResource(R.string.records_file_text)
        RecordFileType.OTHER -> stringResource(R.string.records_file_other)
    }
    val parts = mutableListOf(kind, RecordFormat.bytes(record.fileSize))
    if (record.fileType == RecordFileType.PDF && record.pageCount > 0) {
        parts += pluralStringResource(R.plurals.records_pages, record.pageCount, record.pageCount)
    }
    return parts.joinToString(" · ")
}

private fun RecordSource.labelRes(): Int = when (this) {
    RecordSource.IMPORT -> R.string.records_source_import
    RecordSource.PHOTOS -> R.string.records_source_photos
    RecordSource.CAMERA -> R.string.records_source_camera
    RecordSource.SCAN -> R.string.records_source_scan
    RecordSource.PASTE -> R.string.records_source_paste
    RecordSource.NOTE -> R.string.records_source_note
    RecordSource.SHARE_IN -> R.string.records_source_share_in
    RecordSource.OPEN_IN -> R.string.records_source_open_in
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    singleLine: Boolean,
    allowEmpty: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    suggestions: List<String> = emptyList()
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    GlassDialog(onDismissRequest = onDismiss) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        GlassTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 4,
            maxLines = if (singleLine) 1 else 10
        )
        val matching = suggestions.filter { value.isBlank() || it.contains(value.trim(), ignoreCase = true) }.take(8)
        if (matching.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                matching.forEach { name -> RecordChip(text = name, selected = false, onClick = { onSave(name) }) }
            }
        }
        GlassDialogActions(
            primaryText = stringResource(R.string.action_save),
            onPrimary = { onSave(value) },
            primaryEnabled = allowEmpty || value.isNotBlank(),
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss
        )
    }
}
