package com.ayuvo.health.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.health.HealthChartPoint
import com.ayuvo.health.data.health.HealthDayKeys
import com.ayuvo.health.data.health.HealthHourlyRollup
import com.ayuvo.health.data.health.HealthRollupMath
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSeriesAggregator
import com.ayuvo.health.data.health.HealthSleepCodes
import com.ayuvo.health.data.health.HealthTypeDescriptor
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.SleepNightSpan
import com.ayuvo.health.data.metrics.SleepRangeSeries
import com.ayuvo.health.data.metrics.SleepRow
import com.ayuvo.health.data.metrics.SleepWindow
import com.ayuvo.health.ui.charts.ChartClock
import com.ayuvo.health.ui.metrics.MetricNavigation
import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.data.health.SleepNight
import com.ayuvo.health.models.HealthAggregation
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthKind
import com.ayuvo.health.ui.util.AppLabels
import com.ayuvo.health.data.metrics.ResolvedMetric
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.nutrients.NutrientReference
import com.ayuvo.health.nutrients.Nutrients
import com.ayuvo.health.nutrients.ReferenceLines
import com.ayuvo.health.ui.metrics.MetricCatalog
import com.ayuvo.health.ui.metrics.MetricGoalInputs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class HealthChartKind { BAR, LINE, RANGE, BLOOD_PRESSURE, SLEEP, PERIOD_BAND, NONE }

/** One "Show All Data" row, formatted off the main thread so the list composes cheaply. */
data class HealthRecordUi(
    val row: HealthSampleRow,
    val valueText: String,
    val timeText: String,
    /** "start – end" when the record spans time, otherwise the same as [timeText]. */
    val timeRangeText: String,
    val sourceLabel: String
)

/**
 * Goal facts of a health type from its catalog override (docs/ui-structure.md §4 "Health nutrition
 * types"): the reference lines of a nutrition type (`goal_source: nutrient.reference`), or the
 * profile goal of the macro types (`dietary_energy` → calorie goal).
 */
data class HealthGoalUi(
    /** Reference key whose lines, About and Learn more the chart shows; null for non-nutrient types. */
    val nutrientKey: String? = null,
    val learnSlug: String? = null,
    /** The app's own `nutrient:<key>` chart (every reference nutrient has one). */
    val nutrientMetric: MetricKey.Nutrient? = null,
    /** `food_tracked` of [nutrientMetric]: false = that chart counts Medications supplements only. */
    val foodTracked: Boolean? = null,
    /** `reference_lines` for the user's age, sex, calorie goal and (app-tracked only) custom goal. */
    val lines: ReferenceLines? = null,
    val customGoal: Int? = null,
    /** Single goal rule of a profile goal source (the macro types), canonical unit. */
    val goal: Double? = null
) {
    val isNutrient: Boolean get() = nutrientKey != null

    companion object {
        /**
         * Pure resolution from the catalog facts and the profile inputs. The custom goal applies
         * only to `app_tracked` nutrients (the apps store no goals for the others, docs/nutrients.md §5a).
         */
        fun resolve(resolved: ResolvedMetric, profile: UserProfile?, goals: OptionalNutrientGoals): HealthGoalUi {
            val nk = resolved.nutrientKey
            if (nk == null || NutrientReference.active?.byKey?.get(nk) == null) {
                return HealthGoalUi(goal = MetricCatalog.goal(resolved.goalSource, MetricGoalInputs(profile)))
            }
            val tracked = resolved.foodTracked == true
            val custom = if (tracked) NutrientFields.optionalNutrient(nk)?.let { goals.customGoal(it) } else null
            return HealthGoalUi(
                nutrientKey = nk,
                learnSlug = resolved.learnSlug,
                nutrientMetric = resolved.nutrientMetric?.let { MetricKey.parse(it) as? MetricKey.Nutrient },
                foodTracked = resolved.foodTracked,
                lines = Nutrients.referenceLines(nk, NutrientFields.profile(profile), custom?.toDouble()),
                customGoal = custom
            )
        }
    }
}

/** One "Data Sources & Access" row. */
data class HealthSourceUi(val packageName: String, val label: String, val count: Int)

data class HealthDetailUiState(
    val typeId: String,
    val type: HealthDataType? = null,
    val descriptor: HealthTypeDescriptor,
    val displayNameHint: String? = null,
    val range: HealthChartRange = HealthChartRange.WEEK,
    val anchor: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val weekStart: WeekStart = WeekStart.MONDAY,
    /** Calendar interval shown (docs/ui-structure.md §5). */
    val window: ClosedRange<LocalDate> = range.window(anchor),
    val canGoForward: Boolean = false,
    /** Big number above the chart: label res → formatted value (null = no data). */
    val headline: Pair<Int, String?>? = null,
    val points: List<HealthChartPoint> = emptyList(),
    val chartKind: HealthChartKind = HealthChartKind.NONE,
    /** Label res → formatted value, in display order. */
    val highlights: List<Pair<Int, String>> = emptyList(),
    val sleepNights: List<SleepNight> = emptyList(),
    /** D view: the shown night's window (`sleep_night_window`) and its rows (one source, in bed included). */
    val sleepWindow: SleepWindow? = null,
    val sleepRows: List<SleepRow> = emptyList(),
    /** W/M/6M/Y: `sleep_range_series` buckets; W/M also carry each night's stage rows by wake day. */
    val sleepRange: SleepRangeSeries? = null,
    val sleepNightRows: Map<LocalDate, List<SleepRow>> = emptyMap(),
    /** Text under the headline when it is not the window label (sleep: bedtime – wake). */
    val headlineRange: String? = null,
    val periodDays: Set<LocalDate> = emptySet(),
    val allData: List<HealthRecordUi> = emptyList(),
    val allDataEnd: Boolean = false,
    val loadingMore: Boolean = false,
    val sources: List<HealthSourceUi> = emptyList(),
    val unitPrefs: HealthUnitPrefs = HealthUnitPrefs(),
    val showOnHome: Boolean = false,
    val count: Long = 0,
    val historyLimitedBeforeMs: Long? = null,
    val loading: Boolean = true
) {
    val hasChartData: Boolean get() = points.any { !it.isEmpty } || sleepWindow != null || sleepRange?.domain != null || periodDays.isNotEmpty()
}

@OptIn(FlowPreview::class)
class HealthTypeDetailViewModel(private val container: AppContainer, private val typeId: String) : ViewModel() {
    private val type = HealthDataType.byId(typeId)
    private val resolved = MetricCatalog.resolve(container.metricCatalog, MetricKey.Health(typeId))

    /**
     * Android keeps `dietary_*` as virtual rollups of NutritionRecord rows, so the record list,
     * the CSV and the sources read the nutrition rows and show this nutrient's field.
     */
    private val dietaryKey: String? = type?.let { HealthDataType.dietaryExtraKeys[it] }
    private val listTypeId: String = if (dietaryKey != null) HealthDataType.NUTRITION_RECORD.id else typeId
    private var rawCursor: Pair<Long, String>? = null

    private fun asListRows(rows: List<HealthSampleRow>): List<HealthSampleRow> {
        val key = dietaryKey ?: return rows
        return rows.mapNotNull { r ->
            val v = (HealthRollupMath.parseExtra(r.extraJson)?.get(key) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            r.copy(typeId = typeId, value = v, value2 = null, value3 = null, count = 1, unit = type?.unit ?: r.unit)
        }
    }
    private val _goal = MutableStateFlow(HealthGoalUi())

    /** Reference lines / profile goal of this type; empty for types without a goal source. */
    val goal: StateFlow<HealthGoalUi> = _goal.asStateFlow()
    private val _ui = MutableStateFlow(
        HealthDetailUiState(typeId = typeId, type = type, descriptor = type?.let { HealthTypeDescriptor.of(it) } ?: HealthTypeDescriptor.resolve(typeId))
    )
    val ui: StateFlow<HealthDetailUiState> = _ui.asStateFlow()
    private val selection = MutableStateFlow(HealthChartRange.WEEK to LocalDate.now())
    private var weekStart = WeekStart.MONDAY
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val hourlyFetched = HashSet<LocalDate>()
    private val labelCache = HashMap<String, String>()
    private var sourceNames: Map<String, String> = emptyMap()
    private val dateTimeFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

    init {
        combine(
            selection,
            container.healthRepository.revision.debounce(REVISION_DEBOUNCE_MS),
            healthUnitPrefsFlow(container.prefs),
            container.favoritePins.isPinned(MetricKey.Health(typeId)),
            container.prefs.weekStartsOnMonday
        ) { sel, _, units, pinned, monday -> DetailInputs(sel.first, sel.second, units, pinned, WeekStart.of(monday)) }
            .onEach { i ->
                weekStart = i.weekStart
                recompute(i.range, i.anchor, i.units, i.pinned)
            }
            .launchIn(viewModelScope)
        if (resolved.nutrientKey != null || resolved.goalSource != "none") {
            combine(container.profileRepository.profile, container.prefs.optionalNutrientGoals) { profile, goals ->
                HealthGoalUi.resolve(resolved, profile, goals)
            }
                .onEach { _goal.value = it }
                .launchIn(viewModelScope)
        }
    }

    fun setRange(range: HealthChartRange) {
        selection.value = MetricNavigation.changeRange(selection.value, range, LocalDate.now(zone))
    }

    /** Tap on a W/M day bucket: open the Day chart of that day (docs/charts.md). */
    fun drillTo(day: LocalDate) {
        selection.value = MetricNavigation.changeRange(selection.value.first to day, HealthChartRange.DAY, LocalDate.now(zone))
    }

    fun shiftAnchor(direction: Int) {
        val (range, anchor) = selection.value
        selection.value = range to range.step(anchor, direction, zone, System.currentTimeMillis())
    }

    /** Reveals the next [PAGE] records (keyset paged from the store). */
    fun loadMore() {
        val state = _ui.value
        if (state.loadingMore || state.allDataEnd) return
        _ui.value = state.copy(loadingMore = true)
        viewModelScope.launch {
            val last = rawCursor ?: state.allData.lastOrNull()?.row?.let { it.endMs to it.id }
            val page = container.healthRepository.samplesPage(listTypeId, last?.first, last?.second, PAGE)
            page.lastOrNull()?.let { rawCursor = it.endMs to it.id }
            val current = _ui.value
            val rows = withContext(Dispatchers.Default) { asListRows(page).map { toRecordUi(it, current.descriptor, current.unitPrefs) } }
            _ui.value = current.copy(allData = current.allData + rows, allDataEnd = page.size < PAGE, loadingMore = false)
        }
    }

    fun setShowOnHome(show: Boolean) {
        viewModelScope.launch { container.favoritePins.toggle(MetricKey.Health(typeId), show) }
    }

    fun setMassUnit(metric: Boolean) = viewModelScope.launch { container.prefs.setWeightUnit(if (metric) "kg" else "lbs") }
    fun setLengthUnit(metric: Boolean) = viewModelScope.launch { container.prefs.setHeightUnit(if (metric) "cm" else "ftin") }
    fun setGlucoseUnit(mgDl: Boolean) = viewModelScope.launch { container.prefs.setHealthGlucoseUnit(if (mgDl) "mg/dL" else "mmol/L") }

    /** Opens Health Connect's access screen; false when no screen could be started. */
    fun openManageAccess(from: android.content.Context): Boolean = container.health.openManageAccess(from)

    /** CSV of every non-deleted record (newest first, bounded) for the share sheet. */
    suspend fun buildCsv(): String = withContext(Dispatchers.Default) {
        val sb = StringBuilder("id,type,start,end,value,value2,value3,value_text,unit,category_value,title,source,device,origin\n")
        var beforeEnd: Long? = null
        var beforeId: String? = null
        var total = 0
        while (total < CSV_MAX_ROWS) {
            val page = container.healthRepository.samplesPage(listTypeId, beforeEnd, beforeId, 500)
            if (page.isEmpty()) break
            for (r in asListRows(page)) {
                sb.append(listOf(r.id, r.typeId, iso(r.startMs, r.startOffsetS), iso(r.endMs, r.endOffsetS), r.value, r.value2, r.value3, r.valueText, r.unit, r.categoryValue, r.title, r.sourceId, r.device, r.origin)
                    .joinToString(",") { csv(it?.toString() ?: "") }).append('\n')
            }
            total += page.size
            beforeEnd = page.last().endMs
            beforeId = page.last().id
            if (page.size < 500) break
        }
        sb.toString()
    }

    private fun csv(field: String): String =
        if (field.contains(',') || field.contains('"') || field.contains('\n')) "\"" + field.replace("\"", "\"\"") + "\"" else field

    private fun iso(ms: Long, offsetS: Int?): String = Instant.ofEpochMilli(ms)
        .atOffset(java.time.ZoneOffset.ofTotalSeconds(offsetS ?: zone.rules.getOffset(Instant.ofEpochMilli(ms)).totalSeconds))
        .toString()

    private data class DetailInputs(val range: HealthChartRange, val anchor: LocalDate, val units: HealthUnitPrefs, val pinned: Boolean, val weekStart: WeekStart)

    private suspend fun recompute(range: HealthChartRange, anchor: LocalDate, units: HealthUnitPrefs, showOnHome: Boolean) {
        val repo = container.healthRepository
        val meta = repo.typeMeta()
        val descriptor = HealthTypeDescriptor.resolve(typeId, meta)
        sourceNames = repo.sourceNames()
        val computed = withContext(Dispatchers.Default) { compute(range, anchor, units, descriptor) }
        val previous = _ui.value
        // The record list survives range changes and unit changes re-format it in place.
        val firstPage = if (previous.allData.isEmpty() || previous.loading) repo.samplesPage(listTypeId, null, null, PAGE) else null
        firstPage?.lastOrNull()?.let { rawCursor = it.endMs to it.id }
        val counts = repo.sourceCounts(listTypeId)
        val listRows = withContext(Dispatchers.Default) {
            (firstPage?.let(::asListRows) ?: previous.allData.map { it.row }).map { toRecordUi(it, descriptor, units) }
        }
        val sources = counts.entries.map { (pkg, count) -> HealthSourceUi(pkg, sourceLabel(pkg), count) }
        val typeMeta = meta[typeId]
        _ui.value = computed.copy(
            unitPrefs = units,
            showOnHome = showOnHome,
            allData = listRows,
            allDataEnd = if (firstPage != null) firstPage.size < PAGE else previous.allDataEnd,
            loadingMore = false,
            sources = sources,
            count = repo.count(typeId),
            displayNameHint = typeMeta?.displayName ?: typeMeta?.nativeId,
            descriptor = descriptor,
            historyLimitedBeforeMs = repo.syncStates()[typeId]?.let { s -> if (!s.backfillWithHistory) s.backfillFloorMs else null },
            loading = false,
            today = LocalDate.now()
        )
    }

    private suspend fun compute(range: HealthChartRange, anchor: LocalDate, units: HealthUnitPrefs, descriptor: HealthTypeDescriptor): HealthDetailUiState {
        val repo = container.healthRepository
        val metricBounds = range.bounds(anchor, weekStart, zone, System.currentTimeMillis())
        val window = range.window(anchor, weekStart, zone)
        val bounds = metricBounds.buckets.map { it.startMs to it.endMs }
        val hours = metricBounds.buckets.map { it.label.toIntOrNull() ?: 0 }
        val base = _ui.value.copy(
            range = range, anchor = anchor, descriptor = descriptor, weekStart = weekStart, window = window,
            canGoForward = metricBounds.canGoForward, headline = null,
            points = emptyList(), sleepNights = emptyList(), sleepWindow = null, sleepRows = emptyList(), sleepRange = null,
            sleepNightRows = emptyMap(), headlineRange = null, periodDays = emptySet(), highlights = emptyList()
        )
        val kind = chartKind(descriptor)

        if (typeId == HealthDataType.SLEEP.id) return sleepState(base, range, anchor, window)
        if (typeId == HealthDataType.MENSTRUATION_PERIOD.id) {
            val rows = repo.samples(typeId, window.start.minusDays(45), window.endInclusive)
            val days = rows.flatMap { HealthDayKeys.daysCovered(it.startMs, it.endMs, it.startOffsetS, it.endOffsetS, zone) }.filter { it in window }.toSet()
            return base.copy(chartKind = HealthChartKind.PERIOD_BAND, periodDays = days, headline = R.string.health_detail_total to "${days.size}", highlights = listOf(R.string.health_detail_total to "${days.size}"))
        }

        val dailyRows = if (range == HealthChartRange.DAY) emptyList() else repo.daily(typeId, window.start, window.endInclusive)
        val points: List<HealthChartPoint> = when {
            range == HealthChartRange.DAY -> dayPoints(descriptor, anchor, bounds, hours)
            else -> HealthSeriesAggregator.bucketDaily(descriptor, dailyRows, bounds, zone)
        }
        val nonEmpty = points.filter { !it.isEmpty }
        val latest = repo.latest(typeId)
        val fmt = { v: Double? -> HealthValueFormatter.format(typeId, v, units, Locale.getDefault(), unitOverride = descriptor.unit.takeIf { type == null }).text }
        val highlights = when {
            kind == HealthChartKind.BLOOD_PRESSURE -> listOfNotNull(
                nonEmpty.takeIf { it.isNotEmpty() }?.let { pts -> R.string.health_detail_average to HealthValueFormatter.formatBloodPressure(pts.mapNotNull { it.avg }.average(), pts.mapNotNull { it.v2Avg }.takeIf { v -> v.isNotEmpty() }?.average()).text },
                nonEmpty.takeIf { it.isNotEmpty() }?.let { pts -> R.string.health_detail_range to "${pts.mapNotNull { it.min }.minOrNull()?.toInt() ?: 0}–${pts.mapNotNull { it.max }.maxOrNull()?.toInt() ?: 0} mmHg" },
                latest?.let { R.string.health_detail_latest to HealthValueFormatter.formatBloodPressure(it.value, it.value2).text }
            )
            // Health nutrition types (docs/nutrients.md §5a): the average over days with a value leads,
            // labelled like the nutrient charts; no Food vs Supplements split (Health Connect has no supplements).
            // Like the nutrient charts: no badges on D, where "Today X of Y" sits under the chart.
            resolved.nutrientKey != null && range == HealthChartRange.DAY -> emptyList()
            resolved.nutrientKey != null -> listOfNotNull(
                dailyAverage(range, nonEmpty, dailyRows, descriptor)?.let { R.string.nutrients_average_per_logged_day to fmt(it) },
                nonEmpty.takeIf { it.isNotEmpty() }?.let { R.string.nutrients_total to fmt(it.sumOf { p -> p.sum ?: 0.0 }) },
                dailyRows.count { it.count > 0 }.takeIf { it > 0 }?.let { R.string.nutrients_logged_days to "$it" }
            )
            descriptor.aggregation == HealthAggregation.SUM || descriptor.isDurationLike -> listOfNotNull(
                // Never show a fabricated 0 for an empty interval (docs/ui-structure.md §1).
                nonEmpty.takeIf { it.isNotEmpty() }?.let { R.string.health_detail_total to fmt(it.sumOf { p -> p.sum ?: 0.0 }) },
                dailyAverage(range, nonEmpty, dailyRows, descriptor)?.let { R.string.health_detail_average to fmt(it) },
                latest?.let { R.string.health_detail_latest to fmt(if (descriptor.isDurationLike) it.durationS else it.value) }
            )
            descriptor.aggregation == HealthAggregation.COUNT || descriptor.kind == HealthKind.CATEGORY -> listOfNotNull(
                nonEmpty.takeIf { it.isNotEmpty() }?.let { R.string.health_detail_records to "${it.sumOf { p -> p.count }}" },
                latest?.let { R.string.health_detail_latest to HealthValueFormatter.categoryLabel(typeId, it.categoryValue) }
            )
            else -> listOfNotNull(
                nonEmpty.takeIf { it.isNotEmpty() }?.let { pts -> R.string.health_detail_average to fmt(pts.sumOf { (it.avg ?: 0.0) * it.count } / pts.sumOf { it.count }.coerceAtLeast(1)) },
                nonEmpty.takeIf { it.isNotEmpty() }?.let { pts -> R.string.health_detail_range to "${HealthValueFormatter.format(typeId, pts.mapNotNull { it.min }.minOrNull(), units).number}–${fmt(pts.mapNotNull { it.max }.maxOrNull())}" },
                latest?.let { R.string.health_detail_latest to fmt(it.value) }
            )
        }
        val headline: Pair<Int, String?> = when {
            kind == HealthChartKind.BLOOD_PRESSURE -> R.string.health_detail_average to highlights.firstOrNull { it.first == R.string.health_detail_average }?.second
            descriptor.aggregation == HealthAggregation.SUM || descriptor.isDurationLike ->
                if (range == HealthChartRange.DAY) R.string.health_detail_total to nonEmpty.takeIf { it.isNotEmpty() }?.let { fmt(it.sumOf { p -> p.sum ?: 0.0 }) }
                else R.string.health_detail_average to dailyAverage(range, nonEmpty, dailyRows, descriptor)?.let(fmt)
            descriptor.aggregation == HealthAggregation.COUNT || descriptor.kind == HealthKind.CATEGORY ->
                R.string.health_detail_records to nonEmpty.takeIf { it.isNotEmpty() }?.let { "${it.sumOf { p -> p.count }}" }
            descriptor.aggregation == HealthAggregation.LATEST ->
                R.string.health_detail_latest to nonEmpty.lastOrNull()?.let { fmt(it.avg) }
            else -> R.string.health_detail_average to highlights.firstOrNull { it.first == R.string.health_detail_average }?.second
        }
        return base.copy(chartKind = kind, points = points, highlights = highlights, headline = headline)
    }

    /**
     * Sleep (docs/charts.md): D plots the night that woke up on the anchor day over its own window;
     * W/M/6M/Y plot bedtime → wake bars on the clock axis. No asleep time is shown for in-bed-only nights.
     */
    private suspend fun sleepState(base: HealthDetailUiState, range: HealthChartRange, anchor: LocalDate, window: ClosedRange<LocalDate>): HealthDetailUiState {
        val repo = container.healthRepository
        val is24 = android.text.format.DateFormat.is24HourFormat(container.appContext)
        val nights = repo.sleepNights(window.start, window.endInclusive)
        val asleep = nights.map { it.asleepS }.filter { it > 0 }
        val highlights = listOfNotNull(
            asleep.takeIf { it.isNotEmpty() }?.let { R.string.health_detail_average to HealthValueFormatter.duration(it.average()) },
            asleep.takeIf { it.size > 1 }?.let { R.string.health_detail_range to "${HealthValueFormatter.duration(it.min())}–${HealthValueFormatter.duration(it.max())}" },
            nights.lastOrNull()?.takeIf { it.asleepS > 0 }?.let { R.string.health_detail_latest to HealthValueFormatter.duration(it.asleepS) }
        )
        val rowsByNight: Map<String, List<SleepRow>> = if (range == HealthChartRange.DAY || range == HealthChartRange.WEEK || range == HealthChartRange.MONTH) {
            val chosen = nights.associate { it.nightOf to it.sourceId }
            repo.samples(typeId, window.start, window.endInclusive)
                .filter { !it.deleted && it.categoryValue != null && chosen[it.localDay] == it.sourceId }
                .groupBy({ it.localDay }, { SleepRow(it.startMs, it.endMs, it.categoryValue!!) })
        } else emptyMap()
        if (range == HealthChartRange.DAY) {
            val night = nights.firstOrNull { it.nightOf == anchor.toString() } ?: nights.firstOrNull()
            val rows = night?.let { rowsByNight[it.nightOf] }.orEmpty()
            val w = MetricsReference.sleepNightWindow(rows, zone)
            val headline = when {
                w == null -> R.string.sleep_time_asleep to null
                w.asleepS > 0 -> R.string.sleep_time_asleep to HealthValueFormatter.duration(w.asleepS.toDouble())
                else -> R.string.sleep_in_bed to HealthValueFormatter.duration(w.inBedS.toDouble())
            }
            return base.copy(
                chartKind = HealthChartKind.SLEEP, sleepNights = listOfNotNull(night), sleepWindow = w, sleepRows = rows,
                headline = headline,
                headlineRange = w?.let {
                    container.appContext.getString(R.string.sleep_time_span, ChartClock.time(it.bedtimeMs, zone, is24), ChartClock.time(it.wakeMs, zone, is24))
                },
                highlights = emptyList()
            )
        }
        val spans = nights.map { SleepNightSpan(it.nightOf, it.startMs, it.endMs, it.asleepS, it.inBedS) }
        val series = MetricsReference.sleepRangeSeries(spans, range.metricRange, MetricsReference.localMidnight(anchor, zone), zone, weekStart)
        val h = series.headline
        return base.copy(
            chartKind = HealthChartKind.SLEEP,
            sleepNights = nights,
            sleepRange = series,
            sleepNightRows = rowsByNight.mapKeys { LocalDate.parse(it.key) },
            headline = R.string.sleep_avg_time_asleep to h.asleepS?.takeIf { it > 0 }?.let { HealthValueFormatter.duration(it) },
            headlineRange = if (h.bedOffsetMin != null && h.wakeOffsetMin != null) {
                container.appContext.getString(R.string.sleep_time_span, ChartClock.offset(h.bedOffsetMin, is24), ChartClock.offset(h.wakeOffsetMin, is24))
            } else null,
            highlights = highlights
        )
    }

    /** Summed metrics: interval total ÷ days with data (D uses the hourly buckets' day). */
    private fun dailyAverage(range: HealthChartRange, nonEmpty: List<HealthChartPoint>, dailyRows: List<com.ayuvo.health.data.health.HealthDailyRollup>, descriptor: HealthTypeDescriptor): Double? {
        if (range == HealthChartRange.DAY) return nonEmpty.takeIf { it.isNotEmpty() }?.sumOf { it.sum ?: 0.0 }
        val days = dailyRows.filter { it.count > 0 }
        if (days.isEmpty()) return null
        return days.sumOf { (if (descriptor.isDurationLike) it.durationS ?: it.sum else it.sum) ?: 0.0 } / days.size
    }

    /** D view: hourly cache for SUM types (fetched once per visited day), series points for series types, rows otherwise. */
    private suspend fun dayPoints(descriptor: HealthTypeDescriptor, day: LocalDate, bounds: List<Pair<Long, Long>>, hours: List<Int>): List<HealthChartPoint> {
        val repo = container.healthRepository
        val t = type
        if (t != null && t.usesPlatformAggregate) {
            var hourly = repo.hourly(typeId, day)
            if (hourly.isEmpty() && day !in hourlyFetched) {
                hourlyFetched += day
                val fetched = runCatching { container.healthReadSource.aggregateHourly(t, day) }.getOrNull()
                if (!fetched.isNullOrEmpty()) {
                    val rows = fetched.map { (h, v) -> HealthHourlyRollup(typeId, day.toString(), h, sum = v, avg = v, min = v, max = v, count = 1) }
                    container.healthStore.replaceHourlyRollups(typeId, day.toString(), rows)
                    hourly = rows
                }
            }
            if (hourly.isNotEmpty()) {
                val byHour = hourly.associateBy { it.hour }
                return bounds.mapIndexed { i, (s, e) ->
                    val r = byHour[hours[i]]
                    if (r == null || r.count == 0) HealthChartPoint(s, e) else HealthChartPoint(s, e, sum = r.sum, avg = r.avg, min = r.min, max = r.max, count = r.count)
                }
            }
        }
        // Android keeps `dietary_*` as virtual rollups of NutritionRecord rows (HealthRollupMath.virtualDietary),
        // so the Day chart buckets the day's nutrition records by that nutrient's field.
        val dietaryKey = t?.let { HealthDataType.dietaryExtraKeys[it] }
        if (dietaryKey != null) {
            val rows = repo.samples(HealthDataType.NUTRITION_RECORD.id, day, day).mapNotNull { r ->
                if (r.deleted) return@mapNotNull null
                val v = (HealthRollupMath.parseExtra(r.extraJson)?.get(dietaryKey) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                r.copy(typeId = typeId, value = v, value2 = null, value3 = null, count = 1, unit = t.unit)
            }
            return HealthSeriesAggregator.bucketRows(descriptor, rows, bounds)
        }
        if (t != null && t.isSeriesType) {
            val points = repo.seriesPoints(typeId, bounds.first().first, bounds.last().second)
            if (points.isNotEmpty()) return HealthSeriesAggregator.bucketPoints(points, bounds)
        }
        return HealthSeriesAggregator.bucketRows(descriptor, repo.samples(typeId, day, day), bounds)
    }

    private fun chartKind(descriptor: HealthTypeDescriptor): HealthChartKind = when {
        typeId == HealthDataType.SLEEP.id -> HealthChartKind.SLEEP
        typeId == HealthDataType.MENSTRUATION_PERIOD.id -> HealthChartKind.PERIOD_BAND
        typeId == HealthDataType.BLOOD_PRESSURE.id -> HealthChartKind.BLOOD_PRESSURE
        descriptor.aggregation == HealthAggregation.SUM || descriptor.isDurationLike || descriptor.kind == HealthKind.CATEGORY || descriptor.aggregation == HealthAggregation.COUNT -> HealthChartKind.BAR
        descriptor.aggregation == HealthAggregation.MIN_MAX -> HealthChartKind.RANGE
        else -> HealthChartKind.LINE
    }

    // -- Record rows ------------------------------------------------------------

    private fun toRecordUi(row: HealthSampleRow, descriptor: HealthTypeDescriptor, units: HealthUnitPrefs): HealthRecordUi {
        val start = Instant.ofEpochMilli(row.startMs).atZone(zone)
        val timeText = dateTimeFmt.format(start)
        val rangeText = if (row.endMs > row.startMs) {
            val end = Instant.ofEpochMilli(row.endMs).atZone(zone)
            if (end.toLocalDate() == start.toLocalDate()) "$timeText – ${timeFmt.format(end)}" else "$timeText – ${dateTimeFmt.format(end)}"
        } else timeText
        return HealthRecordUi(
            row = row,
            valueText = valueText(row, descriptor, units),
            timeText = timeText,
            timeRangeText = rangeText,
            sourceLabel = if (row.origin == HealthSampleRow.ORIGIN_LOCAL_APP) ownLabel() else sourceLabel(row.sourceId)
        )
    }

    private fun valueText(row: HealthSampleRow, descriptor: HealthTypeDescriptor, units: HealthUnitPrefs): String = when {
        typeId == HealthDataType.BLOOD_PRESSURE.id -> HealthValueFormatter.formatBloodPressure(row.value, row.value2).text
        typeId == HealthDataType.SLEEP.id -> HealthValueFormatter.categoryLabel(typeId, row.categoryValue) + " · " + HealthValueFormatter.duration(row.durationS)
        descriptor.kind == HealthKind.CATEGORY -> HealthValueFormatter.categoryLabel(typeId, row.categoryValue) + (row.valueText?.let { " · $it" } ?: "")
        descriptor.isDurationLike -> HealthValueFormatter.duration(row.durationS) + (row.title?.let { " · $it" } ?: "")
        else -> HealthValueFormatter.format(typeId, row.value, units, Locale.getDefault(), unitOverride = row.unit.takeIf { type == null }).text +
            (if (row.count > 1 && row.value2 != null && row.value3 != null) " (${HealthValueFormatter.format(typeId, row.value2, units, Locale.getDefault()).number}–${HealthValueFormatter.format(typeId, row.value3, units, Locale.getDefault()).number})" else "") +
            (row.title?.let { " · $it" } ?: "")
    }

    /** Installed app label → platform/export source name → package id; own package → "Ayuvo". Cached per package. */
    private fun sourceLabel(packageName: String): String {
        if (packageName.isBlank()) return ownLabel()
        if (packageName == container.appContext.packageName) return ownLabel()
        return labelCache.getOrPut(packageName) {
            AppLabels.labelOrNull(container.appContext, packageName) ?: sourceNames[packageName]?.takeIf { it.isNotBlank() && it != packageName } ?: packageName
        }
    }

    private fun ownLabel(): String = container.appContext.getString(R.string.health_source_own)

    class Factory(private val container: AppContainer, private val typeId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HealthTypeDetailViewModel(container, typeId) as T
    }

    private companion object {
        /** Records revealed up front and per "Load more". */
        const val PAGE = 5
        const val CSV_MAX_ROWS = 20_000
        const val REVISION_DEBOUNCE_MS = 500L
    }
}
