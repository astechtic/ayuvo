package com.ayuvo.health.ui.cycle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.cycle.data.CycleSyncState
import com.ayuvo.health.cycle.engine.CycleNormalizedPeriod
import com.ayuvo.health.cycle.engine.CyclePeriodValidation
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoShapes
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.EmptyState
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.home.SheetDatePickerDialog
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val shortDate: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val longDate: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

internal fun rangeText(p: CycleNormalizedPeriod): String =
    shortDate.format(LocalDate.parse(p.start)) + " – " + (p.end?.let { shortDate.format(LocalDate.parse(it)) } ?: "…")

/** History (docs/cycle-tracking.md §5): cycles newest first with cycle length, period length and a flow strip. */
@Composable
fun CycleHistoryScreen(container: AppContainer, onBack: () -> Unit, onOpenCycle: (String) -> Unit) {
    val vm: CycleViewModel = viewModel(factory = CycleViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    val ranks = remember { vm.config.flowLevels.filter { it.period }.associate { it.key to it.rank } }
    val periods = ui.snapshot?.periods.orEmpty().reversed()
    val trendByStart = ui.trends?.cycles.orEmpty().associateBy { it.start }
    val syncByStart = ui.periods.associateBy { it.startDay }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AyuvoTopBar(title = stringResource(R.string.cycle_history), onBack = onBack, actions = {
                IconButton(onClick = { adding = true }, modifier = Modifier.testTag("cycle.history.add")) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cycle_period_add_title))
                }
            })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("cycle.history"),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            if (!ui.loading && periods.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.History,
                        title = stringResource(R.string.cycle_history_empty_title),
                        message = stringResource(R.string.cycle_history_empty_body),
                        actionLabel = stringResource(R.string.cycle_period_add_title),
                        onAction = { adding = true }
                    )
                }
            } else {
                item {
                    InsetGroup {
                        periods.forEach { p ->
                            val t = trendByStart[p.start]
                            row {
                                val sub = buildList {
                                    add(t?.cycleLength?.let { pluralStringResource(R.plurals.cycle_cycle_length_value, it, it) }
                                        ?: stringResource(R.string.cycle_current_cycle))
                                    add(p.length?.let { pluralStringResource(R.plurals.cycle_period_length_value, it, it) }
                                        ?: stringResource(R.string.cycle_ongoing))
                                    if (p.source != "app") add(stringResource(R.string.cycle_from_health))
                                    if (syncByStart[p.start]?.syncState == CycleSyncState.FAILED) add(stringResource(R.string.cycle_not_synced))
                                }.joinToString(" · ")
                                GroupRow(
                                    rangeText(p),
                                    subtitle = sub,
                                    modifier = Modifier.testTag("cycle.history.row.${p.start}"),
                                    leading = { FlowStrip(t?.flow.orEmpty().ifEmpty { listOf(null) }, ranks, Modifier.width(56.dp)) },
                                    onClick = { onOpenCycle(p.start) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) PeriodEditSheet(vm, null, null, null, onDismiss = { adding = false })
}

/** One cycle (docs/cycle-tracking.md §5 "History"): its numbers, day logs, notes, edit and delete. */
@Composable
fun CycleDetailScreen(container: AppContainer, start: String, onBack: () -> Unit) {
    val vm: CycleViewModel = viewModel(factory = CycleViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val cfg = vm.config
    val all = ui.snapshot?.periods.orEmpty()
    val index = all.indexOfFirst { it.start == start }
    val period = all.getOrNull(index)
    val trend = ui.trends?.cycles?.firstOrNull { it.start == start }
    val appPeriod = period?.let { p -> ui.periods.firstOrNull { it.id in p.members } }
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var daySheet by rememberSaveable { mutableStateOf<String?>(null) }
    val ranks = remember { cfg.flowLevels.filter { it.period }.associate { it.key to it.rank } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = period?.let { rangeText(it) } ?: stringResource(R.string.cycle_history), onBack = onBack) }
    ) { padding ->
        if (period == null) {
            if (!ui.loading) EmptyState(Icons.Filled.History, stringResource(R.string.cycle_history_empty_title), "", Modifier.padding(padding))
            return@Scaffold
        }
        val end = all.getOrNull(index + 1)?.let { LocalDate.parse(it.start).minusDays(1) } ?: ui.today
        val days = generateSequence(LocalDate.parse(start)) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("cycle.detail"),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            item {
                SurfaceCard {
                    Text(longDate.format(LocalDate.parse(period.start)) + " – " + (period.end?.let { longDate.format(LocalDate.parse(it)) } ?: stringResource(R.string.cycle_ongoing)),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(
                            trend?.cycleLength?.let { pluralStringResource(R.plurals.cycle_cycle_length_value, it, it) } ?: stringResource(R.string.cycle_current_cycle),
                            period.length?.let { pluralStringResource(R.plurals.cycle_period_length_value, it, it) },
                            trend?.painMax?.let { stringResource(R.string.cycle_max_pain, it) }
                        ).joinToString(" · "),
                        fontSize = 14.sp, color = AyuvoColors.secondaryLabel()
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowStrip(trend?.flow.orEmpty(), ranks)
                    if (period.source != "app" && appPeriod == null) {
                        Spacer(Modifier.height(10.dp))
                        CycleBadge(stringResource(R.string.cycle_from_health))
                    }
                    if (appPeriod != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("cycle.detail.edit")) {
                                Text(stringResource(R.string.cycle_edit_period))
                            }
                            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("cycle.detail.delete")) {
                                Text(stringResource(R.string.cycle_delete_period), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            item {
                InsetGroup(header = stringResource(R.string.cycle_day_logs)) {
                    days.forEachIndexed { i, d ->
                        val log = ui.logs[d.toString()]
                        val inPeriod = !d.isAfter(period.end?.let(LocalDate::parse) ?: ui.today)
                        if (log == null && !inPeriod) return@forEachIndexed
                        row {
                            GroupRow(
                                stringResource(R.string.cycle_day_n, i + 1) + " · " + shortDate.format(d),
                                subtitle = log?.let { summary(it, context, vm) } ?: stringResource(R.string.cycle_not_logged),
                                onClick = { daySheet = d.toString() }
                            )
                        }
                    }
                }
            }
        }
    }
    if (editing && appPeriod != null) {
        PeriodEditSheet(vm, appPeriod.id, LocalDate.parse(appPeriod.startDay), appPeriod.endDay?.let(LocalDate::parse), onDismiss = { editing = false })
    }
    daySheet?.let { CycleDaySheet(vm, LocalDate.parse(it), onDismiss = { daySheet = null }) }
    if (confirmDelete && appPeriod != null) {
        GlassDialog(onDismissRequest = { confirmDelete = false }) {
            Text(stringResource(R.string.cycle_delete_period), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.cycle_delete_period_body), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = { confirmDelete = false; vm.deletePeriod(appPeriod.id); onBack() },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { confirmDelete = false },
                destructive = true
            )
        }
    }
}

private fun summary(log: com.ayuvo.health.cycle.data.CycleDayLog, context: android.content.Context, vm: CycleViewModel): String {
    val cfg = vm.config
    val parts = mutableListOf<String>()
    log.flow?.let { parts += CycleText.flow(context, cfg, it) }
    log.pain?.let { parts += context.getString(R.string.cycle_pain_value, it) }
    if (log.symptoms.isNotEmpty()) parts += log.symptoms.joinToString(", ") { CycleText.symptom(context, cfg, it) }
    if (log.moods.isNotEmpty()) parts += log.moods.joinToString(", ") { CycleText.mood(context, cfg, it) }
    log.note?.takeIf { it.isNotBlank() }?.let { parts += "“" + it.take(80) + "”" }
    return parts.joinToString(" · ")
}

/**
 * Add / edit a period (docs/cycle-tracking.md §3.11): start and end pickers (never in the future), "Still going",
 * the live duration, and the engine's validation — an overlap offers "Merge them" or "Adjust dates".
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PeriodEditSheet(
    vm: CycleViewModel, id: String?, initialStart: LocalDate?, initialEnd: LocalDate?, onDismiss: () -> Unit,
    /** A new period starts as "Still going" (the Summary "+" Period entry). */
    defaultOngoing: Boolean = false
) {
    val today = LocalDate.now()
    var start by rememberSaveable { mutableStateOf((initialStart ?: today).toString()) }
    var end by rememberSaveable { mutableStateOf((if (id != null) initialEnd else if (defaultOngoing) null else today)?.toString()) }
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf<CyclePeriodValidation?>(null) }
    val s = LocalDate.parse(start)
    val e = end?.let(LocalDate::parse)
    val duration = e?.let { ChronoUnit.DAYS.between(s, it).toInt() + 1 }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AyuvoShapes.Sheet,
        containerColor = AyuvoColors.sheetBackground(),
        // The sheet is its own window, so it re-enables resource ids for uiautomator.
        modifier = Modifier.testTag("cycle.periodSheet").semantics { testTagsAsResourceId = true }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(if (id == null) R.string.cycle_period_add_title else R.string.cycle_period_edit_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            InsetGroup {
                row { GroupRow(stringResource(R.string.cycle_start), value = longDate.format(s), modifier = Modifier.testTag("cycle.period.start"), onClick = { pick = "start" }) }
                row {
                    GroupRow(
                        stringResource(R.string.cycle_end),
                        value = e?.let { longDate.format(it) } ?: stringResource(R.string.cycle_end_ongoing),
                        modifier = Modifier.testTag("cycle.period.end"),
                        onClick = { pick = "end" }
                    )
                }
                row {
                    GroupRow(
                        stringResource(R.string.cycle_end_ongoing),
                        trailing = RowTrailing.Toggle(end == null, { on -> end = if (on) null else today.toString() }),
                        modifier = Modifier.testTag("cycle.period.ongoing")
                    )
                }
            }
            duration?.let {
                Text(stringResource(R.string.cycle_duration_label) + ": " + pluralStringResource(R.plurals.cycle_days, it.coerceAtLeast(0), it.coerceAtLeast(0)),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("cycle.period.duration"))
            }
            validation?.let { v ->
                val text = if ("overlap" in v.errors && v.merged != null) {
                    stringResource(R.string.cycle_err_overlap, shortDate.format(LocalDate.parse(v.merged!![0]!!)),
                        v.merged!![1]?.let { shortDate.format(LocalDate.parse(it)) } ?: stringResource(R.string.cycle_ongoing))
                } else errorText(v.errors.firstOrNull() ?: "", vm.config.limits.periodMax)
                Text(text, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.testTag("cycle.period.error"))
                if (v.errors == listOf("overlap")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { vm.savePeriod(id, s, e, merge = true) { r -> if (r == null) onDismiss() else validation = r } },
                            colors = ButtonDefaults.buttonColors(containerColor = CycleColors.Period)) { Text(stringResource(R.string.cycle_merge)) }
                        OutlinedButton(onClick = { validation = null; pick = "start" }) { Text(stringResource(R.string.cycle_adjust)) }
                    }
                }
            }
            Button(
                onClick = { vm.savePeriod(id, s, e, merge = false) { r -> if (r == null) onDismiss() else validation = r } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("cycle.period.save"),
                colors = ButtonDefaults.buttonColors(containerColor = CycleColors.Period)
            ) { Text(stringResource(R.string.action_save), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
    pick?.let { which ->
        SheetDatePickerDialog(
            initialDate = if (which == "start") s else (e ?: today),
            onConfirm = { d ->
                pick = null
                validation = null
                if (which == "start") start = d.toString() else end = d.toString()
            },
            onDismiss = { pick = null }
        )
    }
}
