package com.ayuvo.health.ui.medications

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.medications.export.MedicationsArchive
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.TimelineItem
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.records.RecordChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browse › Medications (docs/medications.md §8): today's timeline with Take / Skip / Snooze,
 * as-needed medicines, the searchable list with status chips, and the archive menu, under a
 * title bar whose actions keep the `medications.add` / `medications.menu` test tags.
 */
@Composable
fun MedicationsScreen(
    container: AppContainer,
    onOpenMedication: (String) -> Unit,
    onAddMedication: () -> Unit,
    onOpenHistory: () -> Unit,
    onImportFromRecord: (String) -> Unit,
    onBack: (() -> Unit)? = null
) {
    val vm: MedicationsViewModel = viewModel(factory = MedicationsViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var doseSheet by remember { mutableStateOf<TimelineItem?>(null) }
    var prnSheet by remember { mutableStateOf<Medication?>(null) }
    var showRecordPicker by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val messages = mapOf(
        MedicationsMessage.DOSE_TAKEN to stringResource(R.string.medications_msg_taken),
        MedicationsMessage.DOSE_SKIPPED to stringResource(R.string.medications_msg_skipped),
        MedicationsMessage.DOSE_SNOOZED to stringResource(R.string.medications_msg_snoozed),
        MedicationsMessage.DOSE_UNDONE to stringResource(R.string.medications_msg_undone),
        MedicationsMessage.ACTION_FAILED to stringResource(R.string.medications_error_generic),
        MedicationsMessage.PRN_LOGGED to stringResource(R.string.medications_msg_prn_logged),
        MedicationsMessage.EXPORTED to stringResource(R.string.medications_msg_exported),
        MedicationsMessage.EXPORT_FAILED to stringResource(R.string.medications_msg_export_failed),
        MedicationsMessage.IMPORT_FAILED to stringResource(R.string.medications_msg_import_failed)
    )
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is MedicationsEvent.Message -> messages[event.text]?.let { snackbar.showSnackbar(it) }
            }
        }
    }
    val importResult = ui.lastImport
    if (importResult != null) {
        val text = pluralStringResource(R.plurals.medications_msg_imported, importResult.inserted + importResult.updated, importResult.inserted + importResult.updated)
        LaunchedEffect(importResult) {
            vm.consumeImportResult()
            snackbar.showSnackbar(text)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MedicationsArchive.MIME_TYPE)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = vm.exportBytes()
            val ok = bytes != null && withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
            }
            vm.reportExport(ok)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) vm.reportExport(false) else vm.importBytes(bytes)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AyuvoTopBar(
                title = stringResource(R.string.medications_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = onAddMedication, modifier = Modifier.testTag("medications.add")) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_medication_add))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("medications.menu")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_medication_menu))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.medications_menu_from_prescription)) },
                                onClick = { menuOpen = false; showRecordPicker = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.medications_menu_history)) },
                                onClick = { menuOpen = false; onOpenHistory() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.medications_menu_export)) },
                                enabled = !ui.exportBusy,
                                onClick = { menuOpen = false; exportLauncher.launch(MedicationsArchive.FILE_NAME) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.medications_menu_import)) },
                                onClick = { menuOpen = false; importLauncher.launch(arrayOf(MedicationsArchive.MIME_TYPE, "*/*")) }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("medications.home"),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (ui.loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                return@LazyColumn
            }

            if (ui.totalMedications == 0) {
                item(key = "empty") {
                    EmptyMedicationsCard(onAdd = onAddMedication, onFromPrescription = { showRecordPicker = true })
                }
                item(key = "disclaimer-empty") { MedicationsDisclaimer() }
                return@LazyColumn
            }

            item(key = "notices") { ReminderNoticeCards(visible = ui.hasScheduledMedication) }

            val timeline = ui.timeline
            if (timeline != null) {
                if (timeline.summary.total > 0) {
                    item(key = "summary") { TodaySummaryStrip(timeline.summary) }
                }
                item(key = "today-title") {
                    SectionLabel(stringResource(R.string.medications_section_today))
                }
                if (timeline.groups.isEmpty()) {
                    item(key = "today-empty") {
                        Text(
                            stringResource(R.string.medications_today_empty),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    }
                }
                timeline.groups.forEach { group ->
                    item(key = "slot-${group.slot}") { TimeSlotHeader(MedicationFormat.slot(context, group.slot)) }
                    items(group.items, key = { "dose-${it.medicationId}-${it.scheduleId}-${it.scheduledAtMs}-${it.logId}" }) { item ->
                        DoseRow(
                            item = item,
                            medication = timeline.medications[item.medicationId],
                            onTake = { vm.act(item, DoseAction.TAKEN) },
                            onSkip = { vm.act(item, DoseAction.SKIPPED) },
                            onSnooze = { minutes -> vm.act(item, DoseAction.SNOOZED, snoozeMinutes = minutes) },
                            onOpen = { doseSheet = item }
                        )
                    }
                }
                if (timeline.prn.isNotEmpty()) {
                    item(key = "prn-title") { SectionLabel(stringResource(R.string.medications_section_prn)) }
                    items(timeline.prn, key = { "prn-${it.medicationId}" }) { row ->
                        val medication = timeline.medications[row.medicationId] ?: return@items
                        PrnRowCard(
                            medication = medication,
                            row = row,
                            onLogDose = { prnSheet = medication },
                            onOpen = { onOpenMedication(medication.id) }
                        )
                    }
                }
            }

            item(key = "all-title") { SectionLabel(stringResource(R.string.medications_section_all)) }
            item(key = "search") {
                Box {
                    GlassTextField(
                        value = ui.filter.query,
                        onValueChange = vm::setQuery,
                        placeholder = stringResource(R.string.medications_search_placeholder)
                    )
                    if (ui.filter.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }, modifier = Modifier.align(Alignment.CenterEnd)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                }
            }
            item(key = "chips") {
                val chips = listOf<MedicationStatus?>(MedicationStatus.ACTIVE, MedicationStatus.PAUSED, MedicationStatus.COMPLETED, MedicationStatus.STOPPED, null)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chips, key = { it?.raw ?: "all" }) { status ->
                        val label = if (status == null) stringResource(R.string.medications_filter_all) else stringResource(status.labelRes())
                        val count = if (status == null) ui.totalMedications else ui.counts[status] ?: 0
                        RecordChip(
                            text = if (count > 0) "$label · $count" else label,
                            selected = ui.filter.status == status,
                            onClick = { vm.setStatusFilter(status) },
                            modifier = Modifier.testTag("medications.filter.${status?.raw ?: "all"}")
                        )
                    }
                }
            }
            item(key = "list") {
                if (ui.medications.isEmpty()) {
                    Text(
                        stringResource(R.string.medications_list_empty),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                    )
                } else {
                    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 0.dp) {
                        Column {
                            ui.medications.forEachIndexed { index, medication ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                MedicationRow(medication = medication, onOpen = { onOpenMedication(medication.id) })
                            }
                        }
                    }
                }
            }
            item(key = "disclaimer") {
                Spacer(Modifier.height(4.dp))
                MedicationsDisclaimer()
            }
        }
    }

    doseSheet?.let { item ->
        DoseActionSheet(
            item = item,
            medication = ui.timeline?.medications?.get(item.medicationId),
            defaultSnoozeMinutes = vm.defaultSnoozeMinutes.collectAsState().value,
            onAction = { action, minutes, takenAt, note ->
                doseSheet = null
                vm.act(item, action, snoozeMinutes = minutes, takenAtMs = takenAt, note = note)
            },
            onDismiss = { doseSheet = null }
        )
    }
    prnSheet?.let { medication ->
        PrnLogSheet(
            medication = medication,
            onLog = { takenAt, quantity, note ->
                prnSheet = null
                vm.logPrn(medication.id, takenAt, quantity, note)
            },
            onDismiss = { prnSheet = null }
        )
    }
    if (showRecordPicker) {
        RecordPickerSheet(
            container = container,
            selectedId = null,
            onPick = { record ->
                showRecordPicker = false
                onImportFromRecord(record.id)
            },
            onDismiss = { showRecordPicker = false }
        )
    }
}

/** First-run card: what the feature does plus the two ways in (docs plan §3.3). */
@Composable
private fun EmptyMedicationsCard(onAdd: () -> Unit, onFromPrescription: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.medications_empty_title), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.medications_empty_body),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(2.dp))
            GlassPrimaryButton(text = stringResource(R.string.medications_empty_add), onClick = onAdd)
            GlassTextButton(
                text = stringResource(R.string.medications_menu_from_prescription),
                onClick = onFromPrescription,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
