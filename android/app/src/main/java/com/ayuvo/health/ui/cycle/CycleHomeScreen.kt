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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.cycle.engine.CycleSnapshot
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.EmptyState
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.home.SheetDatePickerDialog
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Period Tracker dashboard (docs/cycle-tracking.md §5): setup the first time, then the cycle ring with today's cycle
 * day and estimated phase, the status line and its basis badge, one-tap Period started / ended, quick actions,
 * notes from the rule-based insights and the disclaimer.
 */
@Composable
fun CycleHomeScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenSettings: () -> Unit,
    forceSetup: Boolean = false,
    /** Opened from the Summary "+" Period entry: the period sheet opens right away (after setup). */
    startWithPeriodSheet: Boolean = false
) {
    val vm: CycleViewModel = viewModel(factory = CycleViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    if (!ui.loading && (!ui.setupDone || forceSetup)) {
        CycleSetupScreen(vm = vm, onBack = onBack, onDone = { if (forceSetup) onBack() })
        return
    }
    var daySheet by rememberSaveable { mutableStateOf<String?>(null) }
    var pickFor by rememberSaveable { mutableStateOf<String?>(null) } // "start" | "end"
    var error by remember { mutableStateOf<String?>(null) }
    var periodSheet by rememberSaveable { mutableStateOf(startWithPeriodSheet) }
    val context = LocalContext.current
    val cfg = vm.config

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AyuvoTopBar(title = stringResource(R.string.cycle_title), onBack = onBack, actions = {
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("cycle.settings")) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cycle_settings))
                }
            })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("cycle.home"),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            val snap = ui.snapshot
            item(key = "ring") {
                SurfaceCard(modifier = Modifier.testTag("cycle.ring")) {
                    if (snap == null || snap.prediction.basis == "none") {
                        EmptyState(
                            icon = Icons.Filled.Loop,
                            title = stringResource(R.string.cycle_no_data_title),
                            message = CycleText.basisAbout(context, cfg, "none")
                        )
                    } else {
                        RingBlock(ui, snap, cfg)
                    }
                    Spacer(Modifier.height(12.dp))
                    val ongoing = ui.ongoing
                    Button(
                        onClick = {
                            if (ongoing != null) vm.endPeriod(LocalDate.now()) { v -> error = v?.errors?.firstOrNull() }
                            else vm.setPeriodDay(LocalDate.now(), true) { e -> error = e }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("cycle.primary"),
                        colors = ButtonDefaults.buttonColors(containerColor = CycleColors.Period)
                    ) {
                        Text(
                            stringResource(if (ongoing != null) R.string.cycle_period_ended else R.string.cycle_period_started),
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(
                        onClick = { pickFor = if (ongoing != null) "end" else "start" },
                        modifier = Modifier.align(Alignment.CenterHorizontally).testTag("cycle.primary.otherDay")
                    ) { Text(stringResource(R.string.cycle_change_date)) }
                    error?.let {
                        Text(errorText(it, cfg.limits.periodMax), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }
            item(key = "actions") {
                InsetGroup {
                    row {
                        GroupRow(stringResource(R.string.cycle_log_today), icon = Icons.Filled.EditNote, iconTint = CycleColors.Period,
                            modifier = Modifier.testTag("cycle.action.logToday"), onClick = { daySheet = LocalDate.now().toString() })
                    }
                    row {
                        GroupRow(stringResource(R.string.cycle_calendar), icon = Icons.Filled.CalendarMonth, iconTint = CycleColors.Period,
                            modifier = Modifier.testTag("cycle.action.calendar"), onClick = onOpenCalendar)
                    }
                    row {
                        GroupRow(stringResource(R.string.cycle_history), icon = Icons.Filled.History, iconTint = CycleColors.Period,
                            modifier = Modifier.testTag("cycle.action.history"), onClick = onOpenHistory)
                    }
                    row {
                        GroupRow(stringResource(R.string.cycle_insights), icon = Icons.Filled.Insights, iconTint = CycleColors.Period,
                            modifier = Modifier.testTag("cycle.action.insights"), onClick = onOpenInsights)
                    }
                }
            }
            if (snap != null && ui.showFertility) {
                val current = snap.prediction.windows.firstOrNull { w ->
                    w.fertile != null && !LocalDate.parse(w.fertile!![1]).isBefore(ui.today)
                }
                if (current != null) {
                    item(key = "fertile") {
                        val fmt = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
                        InsetGroup(footer = CycleText.fertilityNote(context, cfg)) {
                            row {
                                GroupRow(
                                    CycleText.phase(context, cfg, "fertile"),
                                    value = fmt.format(LocalDate.parse(current.fertile!![0])) + " – " + fmt.format(LocalDate.parse(current.fertile!![1])),
                                    trailing = com.ayuvo.health.ui.design.RowTrailing.None,
                                    leading = { DayMarkBackground(CycleMark.FERTILE, today = false, size = 22.dp) }
                                )
                            }
                            current.ovulation?.let { ov ->
                                row {
                                    GroupRow(
                                        CycleText.phase(context, cfg, "ovulation"),
                                        value = fmt.format(LocalDate.parse(ov)),
                                        trailing = com.ayuvo.health.ui.design.RowTrailing.None,
                                        leading = { DayMarkBackground(CycleMark.OVULATION, today = false, size = 22.dp) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val notes = snap?.insights.orEmpty()
            if (notes.isNotEmpty()) {
                item(key = "notes") {
                    InsetGroup(header = stringResource(R.string.cycle_notes_header), modifier = Modifier.testTag("cycle.insights")) {
                        notes.forEach { n ->
                            row {
                                Text(
                                    CycleText.insight(context, cfg, n),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                }
            }
            item(key = "disclaimer") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(CycleText.disclaimer(context, cfg), fontSize = 12.sp, lineHeight = 16.sp, color = AyuvoColors.secondaryLabel())
                    Text(stringResource(R.string.cycle_privacy_note), fontSize = 12.sp, lineHeight = 16.sp, color = AyuvoColors.secondaryLabel())
                }
            }
        }
    }
    daySheet?.let { day ->
        CycleDaySheet(vm = vm, day = LocalDate.parse(day), onDismiss = { daySheet = null })
    }
    if (periodSheet) {
        val ongoing = ui.ongoing
        PeriodEditSheet(
            vm, ongoing?.id, ongoing?.let { LocalDate.parse(it.startDay) }, null,
            onDismiss = { periodSheet = false }, defaultOngoing = true
        )
    }
    pickFor?.let { which ->
        SheetDatePickerDialog(
            initialDate = LocalDate.now(),
            onConfirm = { d ->
                pickFor = null
                if (which == "end") vm.endPeriod(d) { v -> error = v?.errors?.firstOrNull() }
                else vm.setPeriodDay(d, true) { e -> error = e }
            },
            onDismiss = { pickFor = null }
        )
    }
}

@Composable
private fun RingBlock(ui: CycleUi, snap: CycleSnapshot, cfg: com.ayuvo.health.cycle.engine.CycleConfig) {
    val context = LocalContext.current
    val p = snap.prediction
    // With fertility estimates hidden, a fertile / ovulation day shows no phase name at all.
    val hidden = !ui.showFertility && (snap.today.phase == "fertile" || snap.today.phase == "ovulation")
    val phaseText = if (hidden) "" else CycleText.phase(context, cfg, snap.today.phase)
    val marks = ui.ring.map { markOf(it.phase, ui.showFertility) }
    val todayIdx = ui.ring.indexOfFirst { it.day == ui.today.toString() }.takeIf { it >= 0 }
    val day = p.currentCycleDay ?: 0
    val total = ui.ring.size
    val desc = stringResource(R.string.cycle_ring_desc, day, total, phaseText.ifEmpty { "—" })
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        CycleRing(marks = marks, todayIndex = todayIdx, description = desc, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth()) {
            RingCenter(stringResource(R.string.cycle_day_label), day.toString(), phaseText)
        }
        Spacer(Modifier.height(12.dp))
        Text(statusLine(ui, snap), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("cycle.status"))
        p.nextRange?.let { r ->
            if (!p.ongoing && p.lateDays == 0) {
                val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                Text(
                    stringResource(R.string.cycle_status_range, fmt.format(LocalDate.parse(r[0])), fmt.format(LocalDate.parse(r[1]))),
                    fontSize = 13.sp, color = AyuvoColors.secondaryLabel()
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CycleBadge(CycleText.basis(context, cfg, p.basis))
            if (snap.stats.variability == "high") CycleBadge(stringResource(R.string.cycle_badge_variable), CycleColors.Period)
        }
    }
}

@Composable
internal fun statusLine(ui: CycleUi, snap: CycleSnapshot): String {
    val p = snap.prediction
    val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    return when {
        p.ongoing -> stringResource(
            R.string.cycle_status_ongoing, p.currentCycleDay ?: 1,
            p.expectedEnd?.let { fmt.format(LocalDate.parse(it)) } ?: "—"
        )
        p.rawNextStart != null && !LocalDate.parse(p.rawNextStart).isAfter(ui.today) ->
            if (p.lateDays == 0) stringResource(R.string.cycle_status_today)
            else pluralStringResource(R.plurals.cycle_status_late, p.lateDays, p.lateDays)
        p.nextStart != null -> {
            val days = ChronoUnit.DAYS.between(ui.today, LocalDate.parse(p.nextStart)).toInt()
            pluralStringResource(R.plurals.cycle_status_next, days, days)
        }
        else -> ""
    }
}

@Composable
internal fun errorText(code: String, periodMax: Int): String = when (code) {
    "future", "future_start", "future_end" -> stringResource(R.string.cycle_err_future)
    "end_before_start" -> stringResource(R.string.cycle_err_end_before)
    "too_long" -> stringResource(R.string.cycle_err_too_long, periodMax)
    "open_not_latest" -> stringResource(R.string.cycle_err_open_not_latest)
    "overlap" -> stringResource(R.string.cycle_err_overlap_short)
    else -> code
}
