package com.ayuvo.health.ui.fasting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.formatFastingDuration
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.EmptyState
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.home.ActiveFastingRow
import com.ayuvo.health.ui.home.FastingGoalDialog
import com.ayuvo.health.ui.home.FastingSessionDialog
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import com.ayuvo.health.ui.util.clockTimePattern
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Browse › Fasting (docs/ui-structure.md §2): active fast, start/end controls, trend link, history. */
@Composable
fun FastingScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenMetric: (MetricKey) -> Unit
) {
    val vm: FastingViewModel = viewModel(factory = FastingViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    var showStart by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FastingSession?>(null) }
    val context = LocalContext.current
    val dayFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()) }
    val timeFormatter = remember(context) {
        DateTimeFormatter.ofPattern(clockTimePattern(context), Locale.getDefault()).withZone(ZoneId.systemDefault())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = stringResource(R.string.domain_fasting), onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 8.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.SectionGap)
        ) {
            if (ui.loading) return@LazyColumn
            val active = ui.active
            if (!ui.trackingEnabled && active == null) {
                item(key = "off") {
                    EmptyState(
                        icon = Icons.Filled.Timer,
                        title = stringResource(R.string.fasting_off_title),
                        message = stringResource(R.string.fasting_off_body),
                        actionLabel = stringResource(R.string.fasting_turn_on),
                        onAction = vm::enableTracking
                    )
                }
            } else if (active != null) {
                item(key = "active") {
                    InsetGroup(header = stringResource(R.string.fasting_section_current)) {
                        row {
                            ActiveFastingRow(session = active, rowShape = RoundedCornerShape(0.dp), onClick = { editing = active })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { vm.end() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie, contentColor = Color.White)
                        ) { Text(stringResource(R.string.fasting_end), fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(onClick = vm::cancel, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.fasting_cancel), color = AyuvoPalette.Destructive)
                        }
                    }
                }
            } else {
                item(key = "start") {
                    Button(
                        onClick = { showStart = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie, contentColor = Color.White)
                    ) { Text(stringResource(R.string.fasting_start), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                }
            }

            item(key = "trend") {
                InsetGroup(header = stringResource(R.string.fasting_section_trends)) {
                    row {
                        GroupRow(
                            title = stringResource(R.string.metric_fasting),
                            subtitle = stringResource(R.string.fasting_trend_subtitle),
                            icon = Icons.AutoMirrored.Filled.ShowChart,
                            iconTint = AyuvoPalette.Fasting,
                            onClick = { onOpenMetric(MetricKey.App(AppMetricId.FASTING)) }
                        )
                    }
                }
            }

            val history = ui.history
            item(key = "history") {
                if (history.isEmpty()) {
                    InsetGroup(header = stringResource(R.string.fasting_section_history), footer = stringResource(R.string.fasting_history_empty)) {}
                } else {
                    InsetGroup(header = stringResource(R.string.fasting_section_history), dividerInset = AyuvoSpacing.RowH) {
                        history.take(HISTORY_LIMIT).forEach { session ->
                            row {
                                val ended = session.endedAt
                                GroupRow(
                                    title = stringResource(R.string.fasting_completed_format, formatFastingDuration(session.durationSeconds())),
                                    subtitle = ended?.let {
                                        "${dayFormatter.format(it)} · ${timeFormatter.format(session.startedAt)} – ${timeFormatter.format(it)}"
                                    },
                                    value = stringResource(R.string.fasting_goal_format, formatFastingDuration(session.goalMinutes.toLong() * 60)),
                                    onClick = { editing = session }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showStart) {
        FastingGoalDialog(
            title = stringResource(R.string.fasting_start),
            initialMinutes = ui.defaultGoalMinutes,
            confirmLabel = stringResource(R.string.fasting_start),
            onConfirm = { showStart = false; vm.start(it) },
            onDismiss = { showStart = false }
        )
    }
    editing?.let { session ->
        FastingSessionDialog(
            session = session,
            onSave = { vm.update(it); editing = null },
            onEndNow = { vm.end(it); editing = null },
            onDelete = { vm.delete(session.id); editing = null },
            onDismiss = { editing = null }
        )
    }
    if (ui.overlap) {
        GlassDialog(onDismissRequest = vm::dismissOverlap) {
            Text(stringResource(R.string.fasting_overlap_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.fasting_overlap_message), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            GlassDialogActions(primaryText = stringResource(R.string.action_ok), onPrimary = vm::dismissOverlap, onDismiss = vm::dismissOverlap)
        }
    }
}

private const val HISTORY_LIMIT = 60
