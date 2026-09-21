package com.ayuvo.health.ui.records

import com.ayuvo.health.ui.design.AyuvoColors
import android.app.DatePickerDialog
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.knowledge.TrendSeries
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.LinkKind
import com.ayuvo.health.records.model.Observation
import com.ayuvo.health.records.model.ObservationEdit
import com.ayuvo.health.records.model.RecordLink
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.RelatedRecord
import com.ayuvo.health.records.model.ResultFlag
import com.ayuvo.health.ui.charts.smoothTrendPath
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.theme.AppColors
import java.time.LocalDate

internal val FlagLowColor = Color(0xFF3D8BFD)
internal val FlagHighColor = Color(0xFFFF453A)
internal val FlagNormalColor = Color(0xFF34C759)
internal val FlagAbnormalColor = Color(0xFFE8A33D)

internal fun ResultFlag.color(neutral: Color): Color = when (this) {
    ResultFlag.LOW, ResultFlag.CRITICAL_LOW -> FlagLowColor
    ResultFlag.HIGH, ResultFlag.CRITICAL_HIGH -> FlagHighColor
    ResultFlag.ABNORMAL -> FlagAbnormalColor
    ResultFlag.NORMAL -> FlagNormalColor
    ResultFlag.UNKNOWN -> neutral
}

@StringRes
internal fun ResultFlag.labelRes(): Int? = when (this) {
    ResultFlag.LOW -> R.string.records_flag_low
    ResultFlag.HIGH -> R.string.records_flag_high
    ResultFlag.CRITICAL_LOW -> R.string.records_flag_critical_low
    ResultFlag.CRITICAL_HIGH -> R.string.records_flag_critical_high
    ResultFlag.ABNORMAL -> R.string.records_flag_abnormal
    ResultFlag.NORMAL -> R.string.records_flag_normal
    ResultFlag.UNKNOWN -> null
}

@StringRes
internal fun LinkKind.labelRes(): Int = when (this) {
    LinkKind.FOLLOW_UP -> R.string.records_link_kind_follow_up
    LinkKind.PRESCRIPTION_FOR -> R.string.records_link_kind_prescription_for
    LinkKind.SAME_EPISODE -> R.string.records_link_kind_same_episode
    LinkKind.PREVIOUS_REPORT -> R.string.records_link_kind_previous_report
    LinkKind.RELATED -> R.string.records_link_kind_related
    LinkKind.SPLIT_FROM -> R.string.records_link_kind_split_from
}

@Composable
internal fun FlagChip(flag: ResultFlag, modifier: Modifier = Modifier) {
    val label = flag.labelRes() ?: return
    val color = flag.color(MaterialTheme.colorScheme.onSurface)
    Text(
        stringResource(label),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** `value unit` as the report prints it. */
internal fun observationValue(o: Observation): String = listOfNotNull(o.valueText, o.unit?.takeIf { it.isNotBlank() }).joinToString(" ")

/** A small smoothed sparkline; points coloured by flag, last point emphasised. */
@Composable
internal fun MiniSparkline(points: List<TrendSeries.Point>, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val line = AppColors.Calorie
    Canvas(modifier) {
        val values = points.map { it.value }
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
        val padX = 5.dp.toPx()
        val padY = 5.dp.toPx()
        val w = size.width - padX * 2
        val h = size.height - padY * 2
        val offsets = points.mapIndexed { i, p ->
            val x = padX + if (points.size == 1) w / 2 else w * i / (points.size - 1)
            val y = padY + h - ((p.value - min) / span * h).toFloat()
            Offset(x, y)
        }
        drawPath(smoothTrendPath(offsets), line.copy(alpha = 0.85f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        offsets.forEachIndexed { i, o ->
            val c = points[i].resultFlag.color(neutral)
            val r = if (i == offsets.lastIndex) 3.6.dp.toPx() else 2.6.dp.toPx()
            drawCircle(Color.White, r + 1.dp.toPx(), o)
            drawCircle(c, r, o)
        }
    }
}

/**
 * Health data points (§19, §21, §24): name / mapped analyte, value + unit, flag chip, reference
 * range, confirmed/edited badge; tap → edit sheet. Analytes with ≥ 2 trend points show an inline
 * sparkline "7.2 → 8.4 → 9.7" and View full trend.
 */
@Composable
internal fun HealthDataPointsCard(
    observations: List<Observation>,
    trends: Map<String, TrendSeries.Trend>,
    catalog: AnalyteCatalog,
    onRow: (Observation) -> Unit,
    onViewTrend: (String) -> Unit,
    onAdd: () -> Unit
) {
    val visible = observations.filter { it.state != FieldState.REJECTED }
    GlassSurface(Modifier.fillMaxWidth().animateContentSize(), cornerRadius = 20.dp, padding = 0.dp) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.records_data_points_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAdd).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, null, tint = AppColors.Calorie, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(stringResource(R.string.records_data_points_add), color = AppColors.Calorie, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (visible.isEmpty()) {
                Text(
                    stringResource(R.string.records_data_points_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                return@Column
            }
            val trendShown = HashSet<String>()
            val cat = catalog
            visible.forEachIndexed { index, obs ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                ObservationRow(obs, catalog, onRow)
                val analyteId = obs.analyteId
                val trend = analyteId?.let { trends[it] }
                if (analyteId != null && trend != null && analyteId !in trendShown) {
                    // §21: the trend as of this report, shown with ≥ 2 points.
                    val mini = TrendSeries.mini(trend, obs.id, cat)
                    if (mini.show) {
                        trendShown += analyteId
                        val upTo = trend.series.firstOrNull { s -> s.points.any { obs.id in it.observationIds } }?.points.orEmpty()
                            .let { pts -> pts.subList(0, pts.indexOfFirst { obs.id in it.observationIds } + 1) }
                        MiniTrendRow(upTo, mini.text!!, mini.changeText) { onViewTrend(analyteId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ObservationRow(obs: Observation, catalog: AnalyteCatalog, onRow: (Observation) -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
    val mapped = catalog.displayName(obs.analyteId)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onRow(obs) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(0.52f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(mapped ?: obs.rawName, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = when {
                mapped == null && obs.analyteId == null -> stringResource(R.string.records_data_points_unmapped)
                mapped != null && AnalyteCatalog.normalizeName(mapped) != AnalyteCatalog.normalizeName(obs.rawName) -> obs.rawName
                else -> null
            }
            sub?.let { Text(it, fontSize = 11.sp, color = if (obs.analyteId == null) FlagAbnormalColor else dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                when (obs.state) {
                    FieldState.CONFIRMED -> SmallBadge(stringResource(R.string.records_data_points_confirmed), FlagNormalColor)
                    FieldState.USER -> SmallBadge(stringResource(R.string.records_data_points_edited), FlagNormalColor)
                    else -> Unit
                }
                if (obs.excludedFromTrends) Icon(Icons.Filled.VisibilityOff, stringResource(R.string.records_data_points_excluded), tint = dim, modifier = Modifier.size(12.dp))
                obs.sourcePage?.let { Text(stringResource(R.string.records_source_page, it + 1), fontSize = 10.sp, color = AppColors.Calorie) }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(0.48f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    observationValue(obs),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (obs.flag.isAbnormal) obs.flag.color(MaterialTheme.colorScheme.onSurface) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                obs.refText?.let { Text(stringResource(R.string.records_data_points_ref, it), fontSize = 11.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (obs.flag != ResultFlag.UNKNOWN) FlagChip(obs.flag)
            }
        }
    }
}

@Composable
private fun SmallBadge(text: String, color: Color) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
private fun MiniTrendRow(seriesPoints: List<TrendSeries.Point>, text: String, change: String?, onViewTrend: () -> Unit) {
    val points = seriesPoints.takeLast(8)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Calorie.copy(alpha = 0.06f))
            .clickable(onClick = onViewTrend)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiniSparkline(points, Modifier.width(76.dp).height(32.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            change?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1) }
        }
        Text(stringResource(R.string.records_view_full_trend), fontSize = 12.sp, color = AppColors.Calorie, fontWeight = FontWeight.SemiBold)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = AppColors.Calorie, modifier = Modifier.size(14.dp))
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
internal fun sheetColor(): Color = AyuvoColors.sheetBackground()

/** §24 edit sheet: test mapping, value, unit, date, range, trends toggle, remove, source jump. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ObservationEditSheet(
    observation: Observation,
    catalog: AnalyteCatalog,
    onSave: (ObservationEdit) -> Unit,
    onRemove: () -> Unit,
    onViewSource: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var analyteId by rememberSaveable(observation.id) { mutableStateOf(observation.analyteId) }
    var value by rememberSaveable(observation.id) { mutableStateOf(observation.valueText) }
    var unit by rememberSaveable(observation.id) { mutableStateOf(observation.unit.orEmpty()) }
    var date by rememberSaveable(observation.id) { mutableStateOf(observation.observedDate) }
    var ref by rememberSaveable(observation.id) { mutableStateOf(observation.refText.orEmpty()) }
    var inTrends by rememberSaveable(observation.id) { mutableStateOf(!observation.excludedFromTrends) }
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetColor()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.records_obs_edit_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.records_obs_as_printed, listOfNotNull(observation.rawName, observationValue(observation)).joinToString(" · ")), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

            SheetLabel(stringResource(R.string.records_obs_field_test))
            PickerRow(catalog.displayName(analyteId) ?: stringResource(R.string.records_data_points_unmapped)) { picking = true }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(0.55f)) {
                    SheetLabel(stringResource(R.string.records_obs_field_value))
                    GlassTextField(value = value, onValueChange = { value = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Column(Modifier.weight(0.45f)) {
                    SheetLabel(stringResource(R.string.records_obs_field_unit))
                    GlassTextField(value = unit, onValueChange = { unit = it })
                }
            }
            val unitOptions = catalog.analyte(analyteId)?.units?.map { it.unit }.orEmpty().distinct()
            if (unitOptions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    unitOptions.forEach { u -> RecordChip(text = u, selected = u == unit, onClick = { unit = u }) }
                }
            }
            SheetLabel(stringResource(R.string.records_obs_field_date))
            PickerRow(date?.let { runCatching { RecordFormat.date(LocalDate.parse(it)) }.getOrNull() } ?: stringResource(R.string.records_date_unknown)) {
                val initial = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
                DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).toString() }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
            }
            SheetLabel(stringResource(R.string.records_obs_field_ref))
            GlassTextField(value = ref, onValueChange = { ref = it }, placeholder = stringResource(R.string.records_obs_field_ref_hint))
            Row(Modifier.fillMaxWidth().clickable { inTrends = !inTrends }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.records_obs_exclude), modifier = Modifier.weight(1f))
                Switch(checked = inTrends, onCheckedChange = { inTrends = it }, colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Calorie))
            }
            Text(stringResource(R.string.records_obs_edit_note), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            GlassPrimaryButton(
                text = stringResource(R.string.action_save),
                enabled = value.isNotBlank(),
                onClick = {
                    val newUnit = unit.trim().ifEmpty { null }
                    val newRef = ref.trim().ifEmpty { null }
                    val edit = ObservationEdit(
                        setValue = value.trim() != observation.valueText, value = value.trim(),
                        setUnit = newUnit != observation.unit, unit = newUnit,
                        setObservedDate = date != null && date != observation.observedDate, observedDate = date,
                        setAnalyte = analyteId != observation.analyteId, analyteId = analyteId,
                        setRefText = newRef != observation.refText, refText = newRef,
                        excludedFromTrends = (!inTrends).takeIf { it != observation.excludedFromTrends }
                    )
                    onSave(edit)
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onViewSource != null) {
                    GlassTextButton(text = stringResource(R.string.records_obs_view_source), onClick = onViewSource, modifier = Modifier.weight(1f))
                }
                GlassTextButton(text = stringResource(R.string.records_obs_remove), onClick = onRemove, color = FlagHighColor, modifier = Modifier.weight(1f))
            }
        }
    }
    if (picking) {
        AnalytePickerSheet(
            catalog = catalog,
            selected = analyteId,
            allowUnmapped = true,
            // A printed unit the analyte doesn't list is kept; it charts as its own series.
            onPick = { id ->
                analyteId = id
                picking = false
            },
            onDismiss = { picking = false }
        )
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
}

@Composable
private fun PickerRow(text: String, onClick: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick), cornerRadius = 16.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = AppColors.Calorie, modifier = Modifier.size(16.dp))
        }
    }
}

/** Searchable analyte catalogue picker (display names and aliases). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalytePickerSheet(
    catalog: AnalyteCatalog,
    selected: String?,
    allowUnmapped: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, catalog) { catalog.search(query, limit = 80) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetColor()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.records_obs_choose_test), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            GlassTextField(value = query, onValueChange = { query = it }, placeholder = stringResource(R.string.records_obs_search_tests))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                if (allowUnmapped && query.isBlank()) {
                    item(key = "unmapped") {
                        PickerOption(stringResource(R.string.records_obs_unmap), null, selected == null) { onPick(null) }
                    }
                }
                if (results.isEmpty()) {
                    item(key = "none") { Text(stringResource(R.string.records_obs_no_tests), modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                }
                items(results, key = { it.id }) { a ->
                    PickerOption(a.displayName, listOfNotNull(a.aliases.take(3).joinToString(", ").ifBlank { null }, a.canonicalUnit).joinToString(" · ").ifBlank { null }, a.id == selected) { onPick(a.id) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PickerOption(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
            subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = AppColors.Calorie, modifier = Modifier.size(18.dp))
    }
}

/** "Add value" (§24): analyte + value + unit + date (default record date). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddObservationSheet(
    catalog: AnalyteCatalog,
    defaultDate: String?,
    onSave: (analyteId: String?, name: String, value: String, unit: String?, date: String?, ref: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var analyteId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(defaultDate) }
    var ref by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetColor()
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.records_obs_add_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            SheetLabel(stringResource(R.string.records_obs_field_test))
            PickerRow(catalog.displayName(analyteId) ?: stringResource(R.string.records_obs_choose_test)) { picking = true }
            if (analyteId == null) {
                GlassTextField(value = name, onValueChange = { name = it }, placeholder = stringResource(R.string.records_obs_field_test))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(0.55f)) {
                    SheetLabel(stringResource(R.string.records_obs_field_value))
                    GlassTextField(value = value, onValueChange = { value = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Column(Modifier.weight(0.45f)) {
                    SheetLabel(stringResource(R.string.records_obs_field_unit))
                    GlassTextField(value = unit, onValueChange = { unit = it })
                }
            }
            val unitOptions = catalog.analyte(analyteId)?.units?.map { it.unit }.orEmpty().distinct()
            if (unitOptions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    unitOptions.forEach { u -> RecordChip(text = u, selected = u == unit, onClick = { unit = u }) }
                }
            }
            SheetLabel(stringResource(R.string.records_obs_field_date))
            PickerRow(date?.let { runCatching { RecordFormat.date(LocalDate.parse(it)) }.getOrNull() } ?: stringResource(R.string.records_date_unknown)) {
                val initial = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
                DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).toString() }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
            }
            SheetLabel(stringResource(R.string.records_obs_field_ref))
            GlassTextField(value = ref, onValueChange = { ref = it }, placeholder = stringResource(R.string.records_obs_field_ref_hint))
            GlassPrimaryButton(
                text = stringResource(R.string.action_save),
                enabled = value.isNotBlank() && (analyteId != null || name.isNotBlank()),
                onClick = { onSave(analyteId, name.trim(), value.trim(), unit.trim().ifEmpty { null }, date, ref.trim().ifEmpty { null }) }
            )
        }
    }
    if (picking) {
        AnalytePickerSheet(catalog, analyteId, allowUnmapped = false, onPick = { id ->
            analyteId = id
            picking = false
            if (unit.isBlank()) catalog.analyte(id)?.canonicalUnit?.let { unit = it }
        }, onDismiss = { picking = false })
    }
}

/** Related records (§19, §22): Linked first, then Suggested with ✓ / ✕, and Link record. */
@Composable
internal fun RelatedRecordsCard(
    related: List<RelatedRecord>,
    files: RecordFileStore,
    onOpen: (HealthRecord) -> Unit,
    onAccept: (RecordLink) -> Unit,
    onReject: (RecordLink) -> Unit,
    onUnlink: (RecordLink) -> Unit,
    onLinkRecord: () -> Unit
) {
    val linked = related.filter { it.link.isLinked }
    val suggested = related.filter { !it.link.isLinked }
    GlassSurface(Modifier.fillMaxWidth().animateContentSize(), cornerRadius = 20.dp, padding = 0.dp) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.records_related_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onLinkRecord).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Link, null, tint = AppColors.Calorie, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.records_related_link), color = AppColors.Calorie, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (related.isEmpty()) {
                Text(
                    stringResource(R.string.records_related_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (linked.isNotEmpty()) {
                RelatedHeader(stringResource(R.string.records_related_linked))
                linked.forEach { item ->
                    RelatedRow(item, files, onOpen) {
                        GlassTextButton(text = stringResource(R.string.records_related_unlink), onClick = { onUnlink(item.link) }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
            if (suggested.isNotEmpty()) {
                RelatedHeader(stringResource(R.string.records_related_suggested))
                suggested.forEach { item ->
                    RelatedRow(item, files, onOpen) {
                        IconButton(onClick = { onAccept(item.link) }, modifier = Modifier.size(36.dp)) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(FlagNormalColor.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Check, stringResource(R.string.records_related_accept), tint = FlagNormalColor, modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = { onReject(item.link) }, modifier = Modifier.size(36.dp)) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, stringResource(R.string.records_related_reject), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelatedHeader(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun RelatedRow(item: RelatedRecord, files: RecordFileStore, onOpen: (HealthRecord) -> Unit, actions: @Composable () -> Unit) {
    val record = item.record
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(record) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RecordThumbnail(record = record, files = files, size = 40.dp, cornerRadius = 10.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(record.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(stringResource(item.link.kind.labelRes()), RecordFormat.displayDate(record)).joinToString(" · ") +
                    if (record.recordType != RecordType.OTHER) " · " + stringResource(record.recordType.labelRes()) else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        actions()
    }
}

/** "Link record": search + recent records, relation kind picker. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LinkRecordSheet(
    candidates: List<HealthRecord>,
    files: RecordFileStore,
    onSearch: (String) -> Unit,
    onLink: (String, LinkKind) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(LinkKind.RELATED) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        kotlinx.coroutines.delay(150)
        onSearch(query)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetColor()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.records_link_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            GlassTextField(value = query, onValueChange = { query = it }, placeholder = stringResource(R.string.records_link_search))
            SheetLabel(stringResource(R.string.records_link_kind))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(LinkKind.FOLLOW_UP, LinkKind.PRESCRIPTION_FOR, LinkKind.SAME_EPISODE, LinkKind.PREVIOUS_REPORT, LinkKind.RELATED).forEach { k ->
                    RecordChip(text = stringResource(k.labelRes()), selected = k == kind, onClick = { kind = k })
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                items(candidates, key = { it.id }) { record ->
                    val selected = record.id == chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) AppColors.Calorie.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable { chosen = if (selected) null else record.id }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RecordThumbnail(record = record, files = files, size = 38.dp, cornerRadius = 10.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(record.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(RecordFormat.displayDate(record), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        if (selected) Icon(Icons.Filled.Check, null, tint = AppColors.Calorie)
                    }
                }
            }
            GlassPrimaryButton(
                text = stringResource(R.string.records_link_action),
                enabled = chosen != null,
                onClick = { chosen?.let { onLink(it, kind) } }
            )
        }
    }
}
