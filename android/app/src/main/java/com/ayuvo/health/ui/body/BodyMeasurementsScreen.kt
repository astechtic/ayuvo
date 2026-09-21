package com.ayuvo.health.ui.body

import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.AyuvoColors
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import com.ayuvo.health.models.BodyMeasurement
import com.ayuvo.health.models.Gender
import com.ayuvo.health.ui.components.DecimalWheelPicker
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.IconBubble
import com.ayuvo.health.ui.components.SplitDecimalWheelPicker
import com.ayuvo.health.ui.components.UnitToggle
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
import com.ayuvo.health.ui.settings.NutritionPickerSheet
import androidx.annotation.StringRes
import com.ayuvo.health.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.HomeTopNutrient
import com.ayuvo.health.models.MacroValueFormatter
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.QuickActionRequest
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/* Body measurements tracking screen (moved from ProgressScreen). */

// ── Body Measurements (optional tape-measure tracking) ──────────────────

internal val measurementHistoryFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault())

internal fun displayLengthCm(context: android.content.Context, cm: Double, useMetric: Boolean): String =
    if (useMetric) String.format(Locale.US, "%.1f %s", cm, context.getString(R.string.unit_cm))
    else String.format(Locale.US, "%.1f %s", cm / 2.54, context.getString(R.string.unit_in))

/** Logged sites in display order, skipping any that weren't entered. */
internal fun measurementSiteList(context: android.content.Context, m: BodyMeasurement): List<Pair<String, Double>> = buildList {
    m.neckCm?.let { add(context.getString(R.string.measure_neck) to it) }
    m.waistCm?.let { add(context.getString(R.string.measure_waist) to it) }
    m.hipsCm?.let { add(context.getString(R.string.measure_hips) to it) }
    m.chestCm?.let { add(context.getString(R.string.measure_chest) to it) }
    m.upperArmCm?.let { add(context.getString(R.string.measure_upper_arm) to it) }
    m.thighCm?.let { add(context.getString(R.string.measure_thigh) to it) }
    m.calfCm?.let { add(context.getString(R.string.measure_calf) to it) }
    m.wristCm?.let { add(context.getString(R.string.measure_wrist) to it) }
}

/** Derived metrics computable from this entry + profile, skipping any missing their inputs. */
internal fun derivedMetricList(context: android.content.Context, m: BodyMeasurement, gender: Gender, heightCm: Double): List<Pair<String, String>> = buildList {
    m.waistToHipRatio?.let { add(context.getString(R.string.derived_waist_to_hip) to String.format(Locale.US, "%.2f", it)) }
    m.waistToHeightRatio(heightCm)?.let { add(context.getString(R.string.derived_waist_to_height) to String.format(Locale.US, "%.2f", it)) }
    m.usNavyBodyFatPercent(gender, heightCm)?.let { add(context.getString(R.string.derived_body_fat) to String.format(Locale.US, "%.0f%%", it)) }
    m.wristFrame(gender, heightCm)?.let { add(context.getString(R.string.derived_frame) to context.getString(it.labelRes)) }
}

internal fun measurementHistorySummary(context: android.content.Context, m: BodyMeasurement, gender: Gender, heightCm: Double, useMetric: Boolean): String {
    val sites = measurementSiteList(context, m).map { "${it.first} ${displayLengthCm(context, it.second, useMetric)}" }
    val bf = m.usNavyBodyFatPercent(gender, heightCm)?.let { "BF ${String.format(Locale.US, "%.0f%%", it)}" }
    return (sites + listOfNotNull(bf)).joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BodyMeasurementsHistorySheet(
    entries: List<BodyMeasurement>,
    gender: Gender,
    heightCm: Double,
    useMetric: Boolean,
    onDelete: (java.util.UUID) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetSurface = AyuvoColors.sheetBackground()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetSurface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.measurement_history), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = AppColors.Calorie) }
            }
            Spacer(Modifier.height(12.dp))
            SurfaceCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(vertical = 4.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(measurementHistoryFmt.format(entry.date), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    measurementHistorySummary(LocalContext.current, entry, gender, heightCm, useMetric),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                            IconButton(onClick = { onDelete(entry.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Settings → Personal Info detail screen for body-circumference measurements. Mirrors the Other
 * Nutrients screen: a tappable row per body part that opens a wheel picker to set its value, plus
 * the AI-derived metrics and history. Talks to BodyMeasurementRepository directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyMeasurementsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by container.bodyMeasurementRepository.entries.collectAsState(initial = emptyList())
    val profile by container.profileRepository.profile.collectAsState(initial = null)
    val heightUnit by container.prefs.heightUnit.collectAsState(initial = "cm")
    val heightMetric = heightUnit == "cm"
    val gender = profile?.gender ?: Gender.MALE
    val heightCm = profile?.heightCm ?: 0.0
    val latest = entries.maxByOrNull { it.date }
    val unit = if (heightMetric) "cm" else "in"

    var editing by remember { mutableStateOf<BodyMeasurement.Site?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    val notSet = stringResource(R.string.settings_not_set)
    val cmUnit = stringResource(R.string.unit_cm)
    val inUnit = stringResource(R.string.unit_in)
    fun displayValue(site: BodyMeasurement.Site): String {
        val cm = latest?.value(site) ?: return notSet
        return if (heightMetric) String.format(Locale.US, "%.0f %s", cm, cmUnit) else String.format(Locale.US, "%.0f %s", cm / 2.54, inUnit)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AyuvoTopBar(
                title = stringResource(R.string.body_measurements_title),
                onBack = onBack,
                windowInsets = WindowInsets.statusBars
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 14.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Optional. Ayuvo turns these into waist-to-hip, waist-to-height, body-fat %, and frame size, and reads them when it recalculates your goals and in Coach.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            item {
                SurfaceCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                    Column {
                        BodyMeasurement.Site.values().forEachIndexed { index, site ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { editing = site }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(site.labelRes), modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(displayValue(site), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                            }
                            if (index != BodyMeasurement.Site.values().lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            }
                        }
                    }
                }
            }
            if (latest != null) {
                val derived = derivedMetricList(context, latest, gender, heightCm)
                if (derived.isNotEmpty()) {
                    item {
                        SurfaceCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.label_derived), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                                derived.forEach { (label, value) ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp)
                                        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Calorie)
                                    }
                                }
                            }
                        }
                    }
                }
                if (entries.size > 1) {
                    item {
                        SurfaceCard(
                            modifier = Modifier.fillMaxWidth(),
                            padding = PaddingValues(14.dp),
                            onClick = { showHistory = true }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.measurement_history), modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("${entries.size}", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { site ->
        val current = latest?.value(site)
        // Editor-owned display value: a unit flip CONVERTS what's on the wheel
        // (clamped into the destination rows), matching the height/weight editors,
        // instead of re-seeding from the saved value.
        var editorValue by remember(site) {
            mutableStateOf(
                current?.let { if (heightMetric) Math.round(it).toInt() else Math.round(it / 2.54).toInt() }
                    ?: if (heightMetric) 80 else 32
            )
        }
        GlassDialog(onDismissRequest = { editing = null }) {
            // Flipping here persists the shared length standard (same pref as the
            // Height editor); the collected pref recomposes range + labels.
            UnitToggle(
                stringResource(R.string.unit_cm),
                stringResource(R.string.unit_in),
                heightMetric,
                { metric ->
                    if (metric != heightMetric) {
                        editorValue = if (metric) {
                            Math.round(editorValue * 2.54).toInt().coerceIn(10, 250)
                        } else {
                            Math.round(editorValue / 2.54).toInt().coerceIn(4, 100)
                        }
                        scope.launch { container.prefs.setHeightUnit(if (metric) "cm" else "ftin") }
                    }
                },
                Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            // key() so a unit flip rebuilds the wheel seeded with the converted
            // value: its internal scroll state otherwise survives the range swap.
            key(heightMetric) {
                NutritionPickerSheet(
                    label = stringResource(site.labelRes),
                    unit = unit,
                    currentValue = editorValue,
                    range = if (heightMetric) 10..250 else 4..100,
                    step = 1,
                    onSave = { v ->
                        val cm = if (heightMetric) v.toDouble() else v * 2.54
                        scope.launch { container.bodyMeasurementRepository.setValue(site, cm) }
                        editing = null
                    },
                    onResetToAuto = if (current != null) {
                        { scope.launch { container.bodyMeasurementRepository.setValue(site, null) }; editing = null }
                    } else null,
                    resetLabel = stringResource(R.string.action_clear),
                    onValueChange = { editorValue = it }
                )
            }
        }
    }
    if (showHistory) {
        BodyMeasurementsHistorySheet(
            entries = entries.sortedByDescending { it.date },
            gender = gender,
            heightCm = heightCm,
            useMetric = heightMetric,
            onDelete = { id -> scope.launch { container.bodyMeasurementRepository.deleteEntry(id) } },
            onDismiss = { showHistory = false }
        )
    }
}
