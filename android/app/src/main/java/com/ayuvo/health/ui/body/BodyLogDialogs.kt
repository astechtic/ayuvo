package com.ayuvo.health.ui.body

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.ayuvo.health.ui.components.GlassSurface
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

/* Weight / body-fat logging dialogs and history sheets (moved from ProgressScreen; shared by metric detail). */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllWeightHistorySheet(
    entries: List<WeightEntry>,
    useMetric: Boolean,
    onDelete: (java.util.UUID) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault())
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
                Text(stringResource(R.string.progress_weight_history), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = AppColors.Calorie) }
            }
            Spacer(Modifier.height(12.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                padding = 0.dp
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .padding(vertical = 4.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(formatWeight(entry.weightKg, useMetric), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text(fmt.format(entry.date), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllBodyFatHistorySheet(
    entries: List<BodyFatEntry>,
    onDelete: (java.util.UUID) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault())
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
                Text(stringResource(R.string.progress_body_fat_history), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = AppColors.Calorie) }
            }
            Spacer(Modifier.height(12.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                padding = 0.dp
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .padding(vertical = 4.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(String.format(Locale.US, "%.1f%%", entry.bodyFatPercent), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text(fmt.format(entry.date), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllWorkoutHistorySheet(
    entries: List<WorkoutSession>,
    onRequestDelete: (WorkoutSession) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val displayDate = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
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
                Text(
                    stringResource(R.string.progress_workout_history),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done), color = AppColors.Calorie)
                }
            }
            Spacer(Modifier.height(12.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                padding = 0.dp
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(vertical = 4.dp)
                ) {
                    items(entries.sortedWith(
                        compareByDescending<WorkoutSession> { it.diaryDateKey }
                            .thenByDescending { it.completedAt }
                    ), key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${entry.caloriesBurned ?: 0} kcal",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    runCatching { LocalDate.parse(entry.diaryDateKey).format(displayDate) }
                                        .getOrDefault(entry.diaryDateKey),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                            IconButton(onClick = { onRequestDelete(entry) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Box(
                            Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun AddWeightDialog(
    useMetric: Boolean,
    initialKg: Double,
    onUnitChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (Double) -> Unit
) {
    // Wheel picker matches Settings → Goal Weight + the onboarding height/weight
    // step — split-decimal so users land on e.g. 72.4 without typing.
    var pickerKg by remember { mutableStateOf(initialKg) }
    var metric by remember { mutableStateOf(useMetric) }
    GlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.progress_log_weight_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        UnitToggle(stringResource(R.string.unit_kg), stringResource(R.string.unit_lbs), metric, { metric = it; onUnitChange(it) }, Modifier.fillMaxWidth())
        if (metric) {
            SplitDecimalWheelPicker(
                value = pickerKg.coerceIn(30.0, 250.0),
                onValueChange = { pickerKg = it },
                min = 30,
                max = 250,
                unit = stringResource(R.string.unit_kg)
            )
        } else {
            val lbs = (pickerKg * 2.20462).coerceIn(60.0, 500.0)
            SplitDecimalWheelPicker(
                value = lbs,
                onValueChange = { newLbs -> pickerKg = newLbs / 2.20462 },
                min = 60,
                max = 500,
                unit = stringResource(R.string.unit_lbs)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            GlassTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            Spacer(Modifier.width(8.dp))
            GlassPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = { onSubmit(pickerKg) },
                modifier = Modifier.width(132.dp)
            )
        }
    }
}

internal fun formatWeight(kg: Double, useMetric: Boolean): String =
    if (useMetric) String.format(Locale.US, "%.1f kg", kg)
    else String.format(Locale.US, "%.1f lbs", kg * 2.20462)

internal fun formatWeightChange(deltaKg: Double, useMetric: Boolean): String {
    val displayValue = if (useMetric) deltaKg else deltaKg * 2.20462
    val roundedValue = if (Math.abs(displayValue) < 0.05) 0.0 else displayValue
    val sign = if (roundedValue > 0) "+" else ""
    val unit = if (useMetric) "kg" else "lbs"
    return String.format(Locale.US, "%s%.1f %s", sign, roundedValue, unit)
}


@Composable
internal fun AddBodyFatDialog(
    initialFraction: Double,
    onDismiss: () -> Unit,
    onSubmit: (Double) -> Unit
) {
    // Whole-percent wheel — body fat measurements rarely justify 0.1% resolution
    // given the noise of calipers / smart scales (matches iOS LogBodyFatSheet).
    var pct by remember { mutableStateOf(initialFraction * 100) }
    GlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.progress_log_body_fat_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        DecimalWheelPicker(
            value = pct.coerceIn(3.0, 60.0),
            onValueChange = { pct = it },
            min = 3.0,
            max = 60.0,
            step = 0.5,
            unit = stringResource(R.string.unit_percent)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            GlassTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            Spacer(Modifier.width(8.dp))
            GlassPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = { onSubmit(pct / 100.0) },
                modifier = Modifier.width(132.dp)
            )
        }
    }
}

internal fun formatPercent(fraction: Double): String =
    String.format(Locale.US, "%.1f%%", fraction * 100)

internal fun formatPercentValue(percent: Double): String =
    String.format(Locale.US, "%.1f%%", percent)

internal fun formatPercentChange(deltaPercent: Double): String {
    val roundedValue = if (Math.abs(deltaPercent) < 0.05) 0.0 else deltaPercent
    val sign = if (roundedValue > 0) "+" else ""
    return String.format(Locale.US, "%s%.1f%%", sign, roundedValue)
}

