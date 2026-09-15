package com.ayuvo.health.ui.records

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.knowledge.TrendSeries
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.Observation
import com.ayuvo.health.records.model.ObservationEdit
import com.ayuvo.health.ui.charts.CardSection
import com.ayuvo.health.ui.charts.IosStyleSegmentedControl
import com.ayuvo.health.ui.charts.StatBadgeRow
import com.ayuvo.health.ui.charts.TrendPoint
import com.ayuvo.health.ui.charts.TrendXAxisLabels
import com.ayuvo.health.ui.charts.downsampleTrend
import com.ayuvo.health.ui.charts.formatTick
import com.ayuvo.health.ui.charts.niceAxisTicks
import com.ayuvo.health.ui.charts.smoothTrendAreaPath
import com.ayuvo.health.ui.charts.smoothTrendPath
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class TrendUiState(
    val loading: Boolean = true,
    val catalog: AnalyteCatalog = AnalyteCatalog.EMPTY,
    val trend: TrendSeries.Trend? = null,
    /** Every non-rejected row with a date, excluded ones too (table). */
    val rows: List<Observation> = emptyList(),
    val records: Map<String, HealthRecord> = emptyMap()
)

class TrendViewModel(private val container: AppContainer, private val analyteId: String) : ViewModel() {
    private val store get() = container.recordsStore
    private val _ui = MutableStateFlow(TrendUiState())
    val ui: StateFlow<TrendUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { store.revision.collectLatest { reload() } }
    }

    private suspend fun reload() {
        runCatching {
            val catalog = withContext(Dispatchers.IO) { container.analyteCatalog }
            val rows = store.analyteObservations(analyteId, includeExcluded = true)
            val trend = TrendSeries.build(analyteId, rows, catalog)
            val records = store.records(rows.map { it.recordId }).associateBy { it.id }
            TrendUiState(false, catalog, trend, rows.filter { it.recordId in records }, records)
        }.onSuccess { state -> _ui.value = state }.onFailure { _ui.update { it.copy(loading = false) } }
    }

    fun setExcluded(o: Observation, excluded: Boolean) {
        viewModelScope.launch { runCatching { store.editObservation(o.id, ObservationEdit(excludedFromTrends = excluded, rememberAlias = false)) } }
    }

    fun remove(o: Observation) {
        viewModelScope.launch { runCatching { store.removeObservation(o.id) } }
    }

    class Factory(private val container: AppContainer, private val analyteId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TrendViewModel(container, analyteId) as T
    }
}

/** One selectable way to show the trend: a series, optionally converted to another listed unit. */
private data class TrendView(val seriesIndex: Int, val unit: String?, val convertTo: String?)

/**
 * Trend screen (plan §2, docs §21): smoothed chart with the latest report's reference band,
 * points coloured by flag, unit/series toggle, point tap → the source record at its page, and the
 * table of values with hide/remove.
 */
@Composable
fun TrendScreen(
    container: AppContainer,
    analyteId: String,
    onBack: () -> Unit,
    onOpenPoint: (recordId: String, observationId: String) -> Unit
) {
    val vm: TrendViewModel = viewModel(key = "trend-$analyteId", factory = TrendViewModel.Factory(container, analyteId))
    val ui by vm.ui.collectAsState()
    val analyte = ui.catalog.analyte(analyteId)
    val name = analyte?.displayName ?: ui.rows.firstOrNull()?.rawName ?: analyteId
    val trend = ui.trend
    val views = remember(trend, analyte) {
        buildList {
            trend?.series?.forEachIndexed { i, s ->
                add(TrendView(i, s.unit, null))
                if (s.convertible && analyte != null) {
                    // Other listed units that the user's reports actually print.
                    val printed = s.points.mapNotNull { it.unit }.toSet()
                    analyte.units.map { it.unit }.filter { it != s.unit && it in printed }.distinct().forEach { add(TrendView(i, it, it)) }
                }
            }
        }
    }
    var viewIndex by rememberSaveable(analyteId) { mutableStateOf(0) }
    val view = views.getOrNull(viewIndex) ?: views.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.records_title), tint = AppColors.Calorie) }
            Text(stringResource(R.string.records_trend_title, name), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppColors.Calorie) }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val series = view?.let { trend?.series?.getOrNull(it.seriesIndex) }
            val points = series?.points.orEmpty().map { p ->
                val v = if (view?.convertTo != null) ui.catalog.fromCanonical(analyteId, p.value, view.convertTo) ?: p.value else p.value
                val lo = if (view?.convertTo != null) p.refLow?.let { ui.catalog.fromCanonical(analyteId, it, view.convertTo) } else p.refLow
                val hi = if (view?.convertTo != null) p.refHigh?.let { ui.catalog.fromCanonical(analyteId, it, view.convertTo) } else p.refHigh
                p.copy(value = v, refLow = lo, refHigh = hi)
            }
            val unit = view?.convertTo ?: series?.unit
            val decimals = ui.catalog.decimals(analyteId)
            if (views.size > 1) {
                item(key = "units") {
                    IosStyleSegmentedControl(
                        options = views.indices.toList(),
                        selected = views.indexOf(view).coerceAtLeast(0),
                        label = { i -> views[i].unit ?: "—" },
                        onSelect = { viewIndex = it }
                    )
                }
            }
            if (points.size >= 1) {
                item(key = "stats") {
                    val last = points.last()
                    val prev = points.getOrNull(points.size - 2)
                    StatBadgeRow(
                        listOf(
                            stringResource(R.string.records_trend_latest) to listOfNotNull(TrendSeries.format(last.value, decimals), unit).joinToString(" "),
                            stringResource(R.string.records_trend_change) to (prev?.let { p ->
                                val d = last.value - p.value
                                (if (d < 0) "−" else "+") + TrendSeries.format(abs(d), decimals)
                            } ?: "—"),
                            stringResource(R.string.records_trend_count) to points.flatMap { it.recordIds }.distinct().size.toString()
                        )
                    )
                }
            }
            item(key = "chart") {
                CardSection {
                    if (points.size < 2) {
                        Text(
                            stringResource(R.string.records_trend_empty, name),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                        )
                        if (points.size == 1) TrendChart(points, bandFor(points), unit, decimals) { p -> onOpenPoint(p.recordIds.first(), p.observationIds.first()) }
                    } else {
                        TrendChart(points, bandFor(points), unit, decimals) { p -> onOpenPoint(p.recordIds.first(), p.observationIds.first()) }
                    }
                }
            }
            val band = bandFor(points)
            if (band.first != null || band.second != null) {
                item(key = "band") {
                    Text(
                        stringResource(R.string.records_trend_band, listOfNotNull(band.first?.let { TrendSeries.format(it, decimals) }, band.second?.let { TrendSeries.format(it, decimals) }).joinToString(" – ") + (unit?.let { " $it" } ?: "")),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                    )
                }
            }
            trend?.series?.filter { !it.convertible }?.takeIf { it.isNotEmpty() && (trend.series.first().convertible) }?.let { separate ->
                item(key = "separate") {
                    Text(
                        stringResource(R.string.records_trend_separate_units, separate.mapNotNull { it.unit }.joinToString(", ")),
                        fontSize = 12.sp,
                        color = FlagAbnormalColor
                    )
                }
            }
            item(key = "table-title") { SectionTitle(stringResource(R.string.records_trend_points)) }
            val tableRows = ui.rows.sortedWith(compareByDescending<Observation> { it.observedDate }.thenByDescending { it.createdMs })
            item(key = "table") {
                CardSection {
                    Column {
                        tableRows.forEachIndexed { i, o ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            TrendTableRow(
                                o,
                                ui.records[o.recordId],
                                onOpen = { onOpenPoint(o.recordId, o.id) },
                                onToggleExcluded = { vm.setExcluded(o, !o.excludedFromTrends) },
                                onRemove = { vm.remove(o) }
                            )
                        }
                    }
                }
            }
            item(key = "note") {
                Text(stringResource(R.string.records_trend_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
        }
    }
}

/** §21 band: the most recent point's own range that has a bound (after any unit toggle). */
private fun bandFor(points: List<TrendSeries.Point>): Pair<Double?, Double?> =
    points.lastOrNull { it.refLow != null || it.refHigh != null }?.let { it.refLow to it.refHigh } ?: (null to null)

@Composable
private fun TrendChart(
    points: List<TrendSeries.Point>,
    band: Pair<Double?, Double?>,
    unit: String?,
    decimals: Int,
    onTapPoint: (TrendSeries.Point) -> Unit
) {
    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val label = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val line = AppColors.Calorie
    val bandColor = FlagNormalColor.copy(alpha = 0.10f)
    val zone = ZoneId.systemDefault()
    val times = points.map { LocalDate.parse(it.date).atStartOfDay(zone).toInstant().toEpochMilli() }
    val tStart = times.first()
    val tEnd = times.last().coerceAtLeast(tStart + 1)
    val values = points.map { it.value } + listOfNotNull(band.first, band.second)
    val rawMin = values.min()
    val rawMax = values.max()
    val pad = ((rawMax - rawMin).takeIf { it > 0 } ?: (abs(rawMax).takeIf { it > 0 } ?: 1.0)) * 0.12
    val ticks = niceAxisTicks(rawMin - pad, rawMax + pad, 5)
    val yMin = minOf(ticks.firstOrNull() ?: rawMin, rawMin - pad)
    val yMax = maxOf(ticks.lastOrNull() ?: rawMax, rawMax + pad)
    val reveal = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { reveal.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    val showsYear = LocalDate.parse(points.first().date).year != LocalDate.parse(points.last().date).year
    val fmt = DateTimeFormatter.ofPattern(if (showsYear) "MMM yyyy" else "d MMM", Locale.getDefault()).withZone(zone)
    val axisWidth = 36.dp
    Column {
        Row(Modifier.fillMaxWidth().height(220.dp)) {
            Canvas(
                Modifier
                    .weight(1f)
                    .height(220.dp)
                    .pointerInput(points) {
                        detectTapGestures { tap ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val hit = points.indices.minByOrNull { i ->
                                val x = if (points.size == 1) w / 2 else ((times[i] - tStart).toFloat() / (tEnd - tStart)) * (w - 24f) + 12f
                                val y = h - ((points[i].value - yMin) / (yMax - yMin) * h).toFloat()
                                abs(x - tap.x) + abs(y - tap.y) * 0.3f
                            }
                            if (hit != null) onTapPoint(points[hit])
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                fun yOf(v: Double) = h - ((v - yMin) / (yMax - yMin) * h).toFloat()
                fun xOf(i: Int) = if (points.size == 1) w / 2 else ((times[i] - tStart).toFloat() / (tEnd - tStart)) * (w - 24f) + 12f
                ticks.forEach { t -> drawLine(grid, Offset(0f, yOf(t)), Offset(w, yOf(t)), strokeWidth = 1f) }
                val lo = band.first
                val hi = band.second
                if (lo != null || hi != null) {
                    val top = yOf(hi ?: yMax).coerceIn(0f, h)
                    val bottom = yOf(lo ?: yMin).coerceIn(0f, h)
                    drawRoundRect(bandColor, Offset(0f, top), Size(w, (bottom - top).coerceAtLeast(1f)), CornerRadius(6f, 6f))
                    listOfNotNull(lo, hi).forEach { b ->
                        drawLine(FlagNormalColor.copy(alpha = 0.45f), Offset(0f, yOf(b)), Offset(w, yOf(b)), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    }
                }
                val offsets = points.indices.map { Offset(xOf(it), yOf(points[it].value)) }
                val lineOffsets = if (points.size > 60) {
                    downsampleTrend(points.indices.map { TrendPoint(times[it], points[it].value) }).map { p ->
                        Offset(((p.timeMs - tStart).toFloat() / (tEnd - tStart)) * (w - 24f) + 12f, yOf(p.value))
                    }
                } else offsets
                clipRect(right = w * reveal.value) {
                    if (lineOffsets.size >= 2) {
                        drawPath(smoothTrendAreaPath(lineOffsets, h), line.copy(alpha = 0.08f))
                        drawPath(smoothTrendPath(lineOffsets), line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                    }
                    if (points.size <= 120) {
                        offsets.forEachIndexed { i, o ->
                            drawCircle(Color.White, 6.dp.toPx(), o)
                            drawCircle(points[i].resultFlag.color(neutral), 4.5.dp.toPx(), o)
                        }
                    }
                }
            }
            Column(Modifier.width(axisWidth).height(220.dp)) {
                Box(Modifier.fillMaxSize()) {
                    ticks.forEach { t ->
                        val frac = ((t - yMin) / (yMax - yMin)).toFloat()
                        Text(
                            formatTick(t),
                            fontSize = 10.sp,
                            color = label,
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = (220 * (1 - frac) - 7).coerceIn(0f, 206f).dp)
                        )
                    }
                }
            }
        }
        TrendXAxisLabels(tStart, tEnd, showsYear, points.size == 1, fmt, label, axisWidth)
        unit?.let { Text(it, fontSize = 11.sp, color = label, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun TrendTableRow(
    o: Observation,
    record: HealthRecord?,
    onOpen: () -> Unit,
    onToggleExcluded: () -> Unit,
    onRemove: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val alpha = if (o.excludedFromTrends) 0.45f else 1f
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                o.observedDate?.let { runCatching { RecordFormat.date(LocalDate.parse(it)) }.getOrNull() } ?: "—",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
            Text(
                record?.title.orEmpty(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f * alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                observationValue(o),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = (if (o.flag.isAbnormal) o.flag.color(MaterialTheme.colorScheme.onSurface) else MaterialTheme.colorScheme.onSurface).copy(alpha = alpha)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (o.state == FieldState.CONFIRMED || o.state == FieldState.USER) {
                    Text(stringResource(if (o.state == FieldState.USER) R.string.records_data_points_edited else R.string.records_data_points_confirmed), fontSize = 10.sp, color = FlagNormalColor)
                }
                if (o.excludedFromTrends) Text(stringResource(R.string.records_data_points_excluded), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                FlagChip(o.flag)
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, stringResource(R.string.records_more), modifier = Modifier.size(18.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.records_trend_point_open)) }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(
                    text = { Text(stringResource(if (o.excludedFromTrends) R.string.records_trend_include else R.string.records_trend_exclude)) },
                    onClick = { menu = false; onToggleExcluded() }
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.records_obs_remove), color = FlagHighColor) }, onClick = { menu = false; onRemove() })
            }
        }
        Spacer(Modifier.width(2.dp))
    }
}
