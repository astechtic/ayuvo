package com.ayuvo.health.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.health.HealthBucket
import com.ayuvo.health.data.health.HealthChartPoint
import com.ayuvo.health.data.health.HealthDayKeys
import com.ayuvo.health.data.health.HealthHourlyRollup
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSeriesAggregator
import com.ayuvo.health.data.health.HealthSleepCodes
import com.ayuvo.health.data.health.HealthTypeDescriptor
import com.ayuvo.health.data.health.SleepNight
import com.ayuvo.health.models.HealthAggregation
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthKind
import com.ayuvo.health.ui.util.AppLabels
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

/** Detail ranges — separate from Progress' TimeRange (D/W/M/6M/Y with an anchor). */
enum class HealthChartRange(val labelRes: Int, val shiftDays: Long) {
    DAY(R.string.health_range_day, 1),
    WEEK(R.string.health_range_week, 7),
    MONTH(R.string.health_range_month, 30),
    SIX_MONTHS(R.string.health_range_six_months, 182),
    YEAR(R.string.health_range_year, 365);

    fun window(anchor: LocalDate): ClosedRange<LocalDate> = when (this) {
        DAY -> anchor..anchor
        WEEK -> anchor.minusDays(6)..anchor
        MONTH -> anchor.minusDays(29)..anchor
        SIX_MONTHS -> anchor.minusDays(181)..anchor
        YEAR -> anchor.minusMonths(11).withDayOfMonth(1)..anchor
    }

    val bucket: HealthBucket
        get() = when (this) {
            DAY -> HealthBucket.HOUR
            WEEK, MONTH -> HealthBucket.DAY
            SIX_MONTHS -> HealthBucket.WEEK
            YEAR -> HealthBucket.MONTH
        }
}

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
    val points: List<HealthChartPoint> = emptyList(),
    val chartKind: HealthChartKind = HealthChartKind.NONE,
    /** Label res → formatted value, in display order. */
    val highlights: List<Pair<Int, String>> = emptyList(),
    val sleepNights: List<SleepNight> = emptyList(),
    /** D view only: (stage code, start, end) segments of the shown night. */
    val sleepStages: List<Triple<Int, Long, Long>> = emptyList(),
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
    val window: ClosedRange<LocalDate> get() = range.window(anchor)
    val canGoForward: Boolean get() = anchor.isBefore(today)
    val hasChartData: Boolean get() = points.any { !it.isEmpty } || sleepNights.isNotEmpty() || periodDays.isNotEmpty()
}

@OptIn(FlowPreview::class)
class HealthTypeDetailViewModel(private val container: AppContainer, private val typeId: String) : ViewModel() {
    private val type = HealthDataType.byId(typeId)
    private val _ui = MutableStateFlow(
        HealthDetailUiState(typeId = typeId, type = type, descriptor = type?.let { HealthTypeDescriptor.of(it) } ?: HealthTypeDescriptor.resolve(typeId))
    )
    val ui: StateFlow<HealthDetailUiState> = _ui.asStateFlow()
    private val selection = MutableStateFlow(HealthChartRange.WEEK to LocalDate.now())
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
            container.prefs.healthHomeTiles
        ) { sel, _, units, tiles -> Triple(sel, units, tiles) }
            .onEach { (sel, units, tiles) -> recompute(sel.first, sel.second, units, HealthHomeTiles.parse(tiles).any { it.id == typeId }) }
            .launchIn(viewModelScope)
    }

    fun setRange(range: HealthChartRange) {
        selection.value = range to selection.value.second
    }

    fun shiftAnchor(direction: Int) {
        val (range, anchor) = selection.value
        val next = anchor.plusDays(direction * range.shiftDays)
        selection.value = range to (if (next.isAfter(LocalDate.now())) LocalDate.now() else next)
    }

    /** Reveals the next [PAGE] records (keyset paged from the store). */
    fun loadMore() {
        val state = _ui.value
        if (state.loadingMore || state.allDataEnd) return
        _ui.value = state.copy(loadingMore = true)
        viewModelScope.launch {
            val last = state.allData.lastOrNull()?.row
            val page = container.healthRepository.samplesPage(typeId, last?.endMs, last?.id, PAGE)
            val current = _ui.value
            val rows = withContext(Dispatchers.Default) { page.map { toRecordUi(it, current.descriptor, current.unitPrefs) } }
            _ui.value = current.copy(allData = current.allData + rows, allDataEnd = page.size < PAGE, loadingMore = false)
        }
    }

    fun setShowOnHome(show: Boolean) {
        viewModelScope.launch {
            val current = HealthHomeTiles.parse(container.prefs.healthHomeTiles.first())
            val t = type ?: return@launch
            val next = if (show) (current + t).distinct().take(HealthHomeTiles.MAX_TILES) else current - t
            container.prefs.setHealthHomeTiles(HealthHomeTiles.serialize(next))
        }
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
            val page = container.healthRepository.samplesPage(typeId, beforeEnd, beforeId, 500)
            if (page.isEmpty()) break
            for (r in page) {
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

    private suspend fun recompute(range: HealthChartRange, anchor: LocalDate, units: HealthUnitPrefs, showOnHome: Boolean) {
        val repo = container.healthRepository
        val meta = repo.typeMeta()
        val descriptor = HealthTypeDescriptor.resolve(typeId, meta)
        sourceNames = repo.sourceNames()
        val computed = withContext(Dispatchers.Default) { compute(range, anchor, units, descriptor) }
        val previous = _ui.value
        // The record list survives range changes and unit changes re-format it in place.
        val firstPage = if (previous.allData.isEmpty() || previous.loading) repo.samplesPage(typeId, null, null, PAGE) else null
        val counts = repo.sourceCounts(typeId)
        val listRows = withContext(Dispatchers.Default) {
            (firstPage ?: previous.allData.map { it.row }).map { toRecordUi(it, descriptor, units) }
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
        val window = range.window(anchor)
        val bounds = HealthSeriesAggregator.bucketBounds(window, range.bucket, zone)
        val base = _ui.value.copy(range = range, anchor = anchor, descriptor = descriptor, points = emptyList(), sleepNights = emptyList(), sleepStages = emptyList(), periodDays = emptySet(), highlights = emptyList())
        val kind = chartKind(descriptor)

        if (typeId == HealthDataType.SLEEP.id) {
            val nights = repo.sleepNights(window.start, window.endInclusive)
            val asleep = nights.map { it.asleepS }
            val stages = if (range == HealthChartRange.DAY) {
                nights.firstOrNull()?.let { night ->
                    repo.samples(typeId, window.start, window.endInclusive)
                        .filter { it.sourceId == night.sourceId && it.localDay == night.nightOf && it.categoryValue != null && it.categoryValue != HealthSleepCodes.IN_BED }
                        .map { Triple(it.categoryValue!!, it.startMs, it.endMs) }
                } ?: emptyList()
            } else emptyList()
            return base.copy(
                chartKind = HealthChartKind.SLEEP,
                sleepNights = nights,
                sleepStages = stages,
                highlights = listOfNotNull(
                    asleep.takeIf { it.isNotEmpty() }?.let { R.string.health_detail_average to HealthValueFormatter.duration(it.average()) },
                    asleep.takeIf { it.isNotEmpty() }?.let { R.string.health_detail_range to "${HealthValueFormatter.duration(it.min())}–${HealthValueFormatter.duration(it.max())}" },
                    nights.lastOrNull()?.let { R.string.health_detail_latest to HealthValueFormatter.duration(it.asleepS) }
                )
            )
        }
        if (typeId == HealthDataType.MENSTRUATION_PERIOD.id) {
            val rows = repo.samples(typeId, window.start.minusDays(45), window.endInclusive)
            val days = rows.flatMap { HealthDayKeys.daysCovered(it.startMs, it.endMs, it.startOffsetS, it.endOffsetS, zone) }.filter { it in window }.toSet()
            return base.copy(chartKind = HealthChartKind.PERIOD_BAND, periodDays = days, highlights = listOf(R.string.health_detail_total to "${days.size}"))
        }

        val points: List<HealthChartPoint> = when {
            range == HealthChartRange.DAY -> dayPoints(descriptor, anchor, bounds)
            else -> HealthSeriesAggregator.bucketDaily(descriptor, repo.daily(typeId, window.start, window.endInclusive), bounds, zone)
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
            descriptor.aggregation == HealthAggregation.SUM || descriptor.isDurationLike -> listOfNotNull(
                R.string.health_detail_total to fmt(nonEmpty.sumOf { it.sum ?: 0.0 }),
                nonEmpty.takeIf { it.isNotEmpty() }?.let { R.string.health_detail_average to fmt(it.sumOf { p -> p.sum ?: 0.0 } / it.size) },
                latest?.let { R.string.health_detail_latest to fmt(if (descriptor.isDurationLike) it.durationS else it.value) }
            )
            descriptor.aggregation == HealthAggregation.COUNT || descriptor.kind == HealthKind.CATEGORY -> listOfNotNull(
                R.string.health_detail_records to "${nonEmpty.sumOf { it.count }}",
                latest?.let { R.string.health_detail_latest to HealthValueFormatter.categoryLabel(typeId, it.categoryValue) }
            )
            else -> listOfNotNull(
                nonEmpty.takeIf { it.isNotEmpty() }?.let { pts -> R.string.health_detail_average to fmt(pts.sumOf { (it.avg ?: 0.0) * it.count } / pts.sumOf { it.count }.coerceAtLeast(1)) },
                nonEmpty.takeIf { it.isNotEmpty() }?.let { pts -> R.string.health_detail_range to "${HealthValueFormatter.format(typeId, pts.mapNotNull { it.min }.minOrNull(), units).number}–${fmt(pts.mapNotNull { it.max }.maxOrNull())}" },
                latest?.let { R.string.health_detail_latest to fmt(it.value) }
            )
        }
        return base.copy(chartKind = kind, points = points, highlights = highlights)
    }

    /** D view: hourly cache for SUM types (fetched once per visited day), series points for series types, rows otherwise. */
    private suspend fun dayPoints(descriptor: HealthTypeDescriptor, day: LocalDate, bounds: List<Pair<Long, Long>>): List<HealthChartPoint> {
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
                    val r = byHour[i]
                    if (r == null || r.count == 0) HealthChartPoint(s, e) else HealthChartPoint(s, e, sum = r.sum, avg = r.avg, min = r.min, max = r.max, count = r.count)
                }
            }
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
