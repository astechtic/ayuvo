package com.ayuvo.health.ui.cycle

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.cycle.engine.CycleConfig
import com.ayuvo.health.cycle.engine.CycleDayStatus
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.SurfaceCard
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Calendar screen (docs/cycle-tracking.md §5 "Calendar"): swipeable months, legend, tap a day for its sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CycleCalendarScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: CycleViewModel = viewModel(factory = CycleViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val months by vm.months.collectAsState()
    val context = LocalContext.current
    val firstDay = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val thisMonth = remember { YearMonth.now() }
    val pager = rememberPagerState(initialPage = PAST_MONTHS) { PAST_MONTHS + FUTURE_MONTHS + 1 }
    val scope = rememberCoroutineScope()
    var daySheet by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AyuvoTopBar(title = stringResource(R.string.cycle_calendar), onBack = onBack, actions = {
                TextButton(onClick = { scope.launch { pager.animateScrollToPage(PAST_MONTHS) } }, modifier = Modifier.testTag("cycle.calendar.today")) {
                    Text(stringResource(R.string.cycle_today))
                }
            })
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().testTag("cycle.calendar"), verticalAlignment = Alignment.Top) { page ->
                val month = thisMonth.plusMonths((page - PAST_MONTHS).toLong())
                LaunchedEffect(month, ui.snapshot) { vm.ensureMonth(month, firstDay) }
                SurfaceCard {
                    MonthHeader(
                        month,
                        onPrev = { scope.launch { pager.animateScrollToPage((page - 1).coerceAtLeast(0)) } },
                        onNext = { scope.launch { pager.animateScrollToPage((page + 1).coerceAtMost(pager.pageCount - 1)) } }
                    )
                    Spacer(Modifier.height(8.dp))
                    MonthCalendar(
                        month = month,
                        statuses = months[month].orEmpty(),
                        loggedDays = ui.logs.keys,
                        today = ui.today,
                        showFertility = ui.showFertility,
                        firstDayOfWeek = firstDay,
                        config = vm.config,
                        onDayClick = { d -> daySheet = d.toString() }
                    )
                }
            }
            CycleLegend(vm.config, ui.showFertility)
            if (ui.showFertility) {
                Text(CycleText.fertilityNote(context, vm.config), fontSize = 12.sp, lineHeight = 16.sp, color = AyuvoColors.secondaryLabel())
            }
            Text(CycleText.disclaimer(context, vm.config), fontSize = 12.sp, lineHeight = 16.sp, color = AyuvoColors.secondaryLabel())
            Spacer(Modifier.height(24.dp))
        }
    }
    daySheet?.let { CycleDaySheet(vm = vm, day = LocalDate.parse(it), onDismiss = { daySheet = null }) }
}

private const val PAST_MONTHS = 120
private const val FUTURE_MONTHS = 12

@Composable
private fun MonthHeader(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    val title = remember(month) { DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()).format(month) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.cycle_prev_month))
        }
        Text(
            title.replaceFirstChar { it.titlecase(Locale.getDefault()) },
            modifier = Modifier.weight(1f).semantics { heading() },
            textAlign = TextAlign.Center, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
        )
        IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.cycle_next_month))
        }
    }
}

/**
 * A reusable month grid (6 weeks). Each day draws its mark shape (filled circle = period, dashed circle = estimated
 * period, tinted square = likely fertile, ring with a dot = estimated ovulation, dotted circle = period expected),
 * a bold outline for today and a small dot when something is logged. Every day has a full spoken description.
 */
@Composable
fun MonthCalendar(
    month: YearMonth,
    statuses: List<CycleDayStatus>,
    loggedDays: Set<String>,
    today: LocalDate,
    showFertility: Boolean,
    firstDayOfWeek: DayOfWeek,
    config: CycleConfig,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val (start, _) = CycleViewModel.gridRange(month, firstDayOfWeek)
    val byDay = remember(statuses) { statuses.associateBy { it.day } }
    val spoken = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()) }
    val periodDayLabel = stringResource(R.string.cycle_a11y_period_day)
    val loggedLabel = stringResource(R.string.cycle_a11y_logged)
    val todayLabel = stringResource(R.string.cycle_today)
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val dow = firstDayOfWeek.plus(i.toLong())
                Text(
                    dow.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()),
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    fontSize = 12.sp, color = AyuvoColors.secondaryLabel()
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        for (week in 0 until 6) {
            Row(Modifier.fillMaxWidth()) {
                for (d in 0 until 7) {
                    val date = start.plusDays((week * 7 + d).toLong())
                    val inMonth = YearMonth.from(date) == month
                    val status = byDay[date.toString()]
                    val mark = status?.let { markOf(it.phase, showFertility) } ?: CycleMark.NONE
                    val isToday = date == today
                    val logged = date.toString() in loggedDays
                    val description = buildString {
                        append(spoken.format(date))
                        if (isToday) append(", ").append(todayLabel)
                        if (status != null && mark != CycleMark.NONE) {
                            append(", ").append(CycleText.phase(context, config, status.phase))
                            if (status.phase == "period" && status.periodDay != null) append(", ").append(periodDayLabel.format(status.periodDay))
                        }
                        if (logged) append(", ").append(loggedLabel)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .heightIn(min = 44.dp)
                            .clip(CircleShape)
                            .clickable(enabled = inMonth) { onDayClick(date) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = description
                                role = Role.Button
                            }
                            .testTag("cycle.day.$date"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (inMonth) DayMarkBackground(mark, isToday, 40.dp)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                date.dayOfMonth.toString(),
                                fontSize = 15.sp,
                                fontWeight = if (isToday || mark == CycleMark.PERIOD) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    !inMonth -> AyuvoColors.tertiaryLabel()
                                    mark == CycleMark.PERIOD -> Color.White
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (logged && inMonth) {
                                Box(
                                    Modifier.size(4.dp).clip(CircleShape)
                                        .background(if (mark == CycleMark.PERIOD) Color.White else AyuvoColors.secondaryLabel())
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CycleLegend(config: CycleConfig, showFertility: Boolean) {
    val context = LocalContext.current
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("cycle.legend"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CycleLegendItem(CycleMark.PERIOD, CycleText.phase(context, config, "period"))
        CycleLegendItem(CycleMark.PREDICTED_PERIOD, CycleText.phase(context, config, "predicted_period"))
        if (showFertility) {
            CycleLegendItem(CycleMark.FERTILE, CycleText.phase(context, config, "fertile"))
            CycleLegendItem(CycleMark.OVULATION, CycleText.phase(context, config, "ovulation"))
        }
        CycleLegendItem(CycleMark.LATE, CycleText.phase(context, config, "late"))
    }
}
