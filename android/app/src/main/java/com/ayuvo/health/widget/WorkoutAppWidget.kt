package com.ayuvo.health.widget

import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.ayuvo.health.R

/**
 * Workout widget (docs/widgets.md "Workout"): idle start buttons (Walk, Run, Cycle, Hike GPS workouts and a strength
 * session) or the running workout with a live chronometer and its controls. Small (2×2) and medium (4×2) layouts.
 */
class WorkoutAppWidget : AyuvoGlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Collected, not captured: WorkoutWidgetSync moves this only when a render is due.
            val inputs by WorkoutWidgetSync.inputs.collectAsState()
            val model = WorkoutWidgetMapper.map(inputs.live, inputs.strength, System.currentTimeMillis())
            GlanceTheme { WorkoutContent(context, model) }
        }
    }
}

class WorkoutWidgetReceiver : AyuvoGlanceAppWidgetReceiver() {
    override val glanceAppWidget = WorkoutAppWidget()
}

private const val MOVE = DashboardColors.MOVE
private const val END_RED = 0xFFFF3B30

internal fun workoutSportIcon(sport: String): Int = when (sport) {
    "run" -> R.drawable.ic_widget_run
    "cycle" -> R.drawable.ic_widget_cycle
    "hike" -> R.drawable.ic_widget_hike
    WorkoutWidgetModel.STRENGTH_SPORT -> R.drawable.ic_widget_fitness
    else -> R.drawable.ic_widget_walk
}

private fun startLabel(start: WorkoutWidgetStart): Int = when (start) {
    WorkoutWidgetStart.WALK -> R.string.widget_workout_walk
    WorkoutWidgetStart.RUN -> R.string.widget_workout_run
    WorkoutWidgetStart.CYCLE -> R.string.widget_workout_cycle
    WorkoutWidgetStart.HIKE -> R.string.widget_workout_hike
    WorkoutWidgetStart.STRENGTH -> R.string.widget_workout_strength
}

private fun startIcon(start: WorkoutWidgetStart): Int = workoutSportIcon(start.gpsSport ?: WorkoutWidgetModel.STRENGTH_SPORT)

@Composable
private fun WorkoutContent(context: Context, model: WorkoutWidgetModel) {
    val size = LocalSize.current
    val medium = size.width.value >= 220f
    when (model) {
        WorkoutWidgetModel.Idle -> IdleContent(context, medium)
        is WorkoutWidgetModel.Active -> ActiveContent(context, model, medium, compactHeight = size.height.value < 130f)
    }
}

// -- Idle -------------------------------------------------------------------------------------------

@Composable
private fun IdleContent(context: Context, medium: Boolean) {
    Column(GlanceModifier.fillMaxSize().background(DashboardColors.background).cornerRadius(22.dp).padding(8.dp)) {
        Box(GlanceModifier.padding(start = 4.dp, bottom = 6.dp)) {
            DashboardHeader(R.drawable.ic_widget_fitness, context.getString(R.string.widget_workout_title), MOVE)
        }
        if (medium) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                WorkoutWidgetStart.entries.forEachIndexed { index, start ->
                    if (index > 0) Spacer(GlanceModifier.width(6.dp))
                    Box(GlanceModifier.defaultWeight().fillMaxHeight()) { StartTile(context, start) }
                }
            }
        } else {
            val rows = listOf(
                listOf(WorkoutWidgetStart.WALK, WorkoutWidgetStart.RUN),
                listOf(WorkoutWidgetStart.CYCLE, WorkoutWidgetStart.HIKE),
                listOf(WorkoutWidgetStart.STRENGTH)
            )
            rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(GlanceModifier.height(5.dp))
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    row.forEachIndexed { index, start ->
                        if (index > 0) Spacer(GlanceModifier.width(5.dp))
                        Box(GlanceModifier.defaultWeight().fillMaxHeight()) { StartChip(context, start) }
                    }
                }
            }
        }
    }
}

/** Medium: bubble + label, like the Quick Log buttons. */
@Composable
private fun StartTile(context: Context, start: WorkoutWidgetStart) {
    val label = context.getString(startLabel(start))
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(DashboardColors.tile)
            .cornerRadius(16.dp)
            .padding(4.dp)
            .clickable(actionStartActivity(WorkoutWidgetIntents.startIntent(context, start))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(GlanceModifier.size(36.dp).background(DashboardColors.bubble(MOVE)).cornerRadius(18.dp), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(startIcon(start)),
                contentDescription = label,
                colorFilter = ColorFilter.tint(DashboardColors.fixed(MOVE)),
                modifier = GlanceModifier.size(20.dp)
            )
        }
        Spacer(GlanceModifier.height(4.dp))
        Text(label, maxLines = 1, style = TextStyle(color = DashboardColors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
    }
}

/** Small: icon and label in one row. */
@Composable
private fun StartChip(context: Context, start: WorkoutWidgetStart) {
    val label = context.getString(startLabel(start))
    Row(
        GlanceModifier
            .fillMaxSize()
            .background(DashboardColors.tile)
            .cornerRadius(12.dp)
            .padding(horizontal = 6.dp)
            .clickable(actionStartActivity(WorkoutWidgetIntents.startIntent(context, start))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(startIcon(start)),
            contentDescription = null,
            colorFilter = ColorFilter.tint(DashboardColors.fixed(MOVE)),
            modifier = GlanceModifier.size(16.dp)
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(label, maxLines = 1, style = TextStyle(color = DashboardColors.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium))
    }
}

// -- Active -----------------------------------------------------------------------------------------

private fun statusLabel(status: WorkoutWidgetStatus): Int = when (status) {
    WorkoutWidgetStatus.RECORDING -> R.string.widget_workout_status_recording
    WorkoutWidgetStatus.PAUSED -> R.string.widget_workout_status_paused
    WorkoutWidgetStatus.SAVING -> R.string.widget_workout_status_saving
    WorkoutWidgetStatus.RECOVERY -> R.string.widget_workout_status_recovery
    WorkoutWidgetStatus.STRENGTH -> R.string.widget_workout_status_strength
}

private fun statusTint(status: WorkoutWidgetStatus): Long = when (status) {
    WorkoutWidgetStatus.PAUSED, WorkoutWidgetStatus.SAVING -> DashboardColors.DIMMED
    WorkoutWidgetStatus.RECOVERY -> DashboardColors.DRINK
    else -> MOVE
}

@Composable
private fun ActiveContent(context: Context, model: WorkoutWidgetModel.Active, medium: Boolean, compactHeight: Boolean) {
    val title = model.title ?: context.getString(R.string.widget_workout_strength_title)
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(DashboardColors.background)
            .cornerRadius(22.dp)
            .padding(10.dp)
            .clickable(actionStartActivity(WorkoutWidgetIntents.openIntent(context)))
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(workoutSportIcon(model.sport)),
                contentDescription = null,
                colorFilter = ColorFilter.tint(DashboardColors.fixed(MOVE)),
                modifier = GlanceModifier.size(14.dp)
            )
            Spacer(GlanceModifier.width(4.dp))
            Text(
                title,
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = DashboardColors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            )
            Text(
                context.getString(statusLabel(model.status)),
                maxLines = 1,
                style = TextStyle(color = DashboardColors.fixed(statusTint(model.status)), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            )
        }
        if (medium) {
            Row(GlanceModifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.defaultWeight()) { Chronometer(context, model, if (compactHeight) 26f else 40f) }
                if (model.distanceText != null) {
                    Stat(context.getString(R.string.widget_workout_distance), if (model.waitingForGps) "—" else model.distanceText)
                    Spacer(GlanceModifier.width(10.dp))
                    Stat(
                        context.getString(if (model.paceIsSpeed) R.string.widget_workout_speed else R.string.widget_workout_pace),
                        if (model.waitingForGps) "—" else model.paceText.orEmpty()
                    )
                }
            }
            if (model.waitingForGps) SecondaryLine(context.getString(R.string.widget_workout_waiting_gps))
        } else {
            Box(GlanceModifier.fillMaxWidth().padding(top = 2.dp)) { Chronometer(context, model, if (compactHeight) 22f else 28f) }
            when {
                model.waitingForGps -> SecondaryLine(context.getString(R.string.widget_workout_waiting_gps))
                model.distanceText != null -> SecondaryLine(listOfNotNull(model.distanceText, model.paceText).joinToString(" · "))
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        if (model.controls.isNotEmpty()) {
            Row(GlanceModifier.fillMaxWidth().height(if (compactHeight) 32.dp else 38.dp)) {
                model.controls.forEachIndexed { index, control ->
                    if (index > 0) Spacer(GlanceModifier.width(6.dp))
                    Box(GlanceModifier.defaultWeight().fillMaxHeight()) {
                        ControlButton(context, control, showLabel = medium || model.controls.size == 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SecondaryLine(text: String) {
    Text(text, maxLines = 1, style = TextStyle(color = DashboardColors.secondary, fontSize = 11.sp, fontWeight = FontWeight.Medium))
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, maxLines = 1, style = TextStyle(color = DashboardColors.secondary, fontSize = 10.sp))
        Text(value, maxLines = 1, style = TextStyle(color = DashboardColors.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold))
    }
}

/** A platform Chronometer (Glance has none): it ticks on the launcher without widget updates. */
@Composable
private fun Chronometer(context: Context, model: WorkoutWidgetModel.Active, textSizeSp: Float) {
    val views = RemoteViews(context.packageName, R.layout.widget_workout_chronometer)
    val nowElapsed = SystemClock.elapsedRealtime()
    val base = if (model.countDown) nowElapsed + model.elapsedMs else nowElapsed - model.elapsedMs
    views.setChronometer(R.id.widget_workout_chronometer, base, null, model.ticking)
    views.setChronometerCountDown(R.id.widget_workout_chronometer, model.countDown)
    views.setTextViewTextSize(R.id.widget_workout_chronometer, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
    AndroidRemoteViews(views)
}

private fun controlIcon(control: WorkoutWidgetControl): Int = when (control) {
    WorkoutWidgetControl.PAUSE -> R.drawable.ic_widget_pause
    WorkoutWidgetControl.RESUME -> R.drawable.ic_widget_play
    WorkoutWidgetControl.LAP -> R.drawable.ic_widget_flag
    WorkoutWidgetControl.END -> R.drawable.ic_widget_stop
    WorkoutWidgetControl.SKIP_RECOVERY -> R.drawable.ic_widget_skip
    WorkoutWidgetControl.FINISH_STRENGTH -> R.drawable.ic_widget_check
}

private fun controlLabel(control: WorkoutWidgetControl): Int = when (control) {
    WorkoutWidgetControl.PAUSE -> R.string.widget_workout_pause
    WorkoutWidgetControl.RESUME -> R.string.widget_workout_resume
    WorkoutWidgetControl.LAP -> R.string.widget_workout_lap
    WorkoutWidgetControl.END -> R.string.widget_workout_end
    WorkoutWidgetControl.SKIP_RECOVERY -> R.string.widget_workout_skip
    WorkoutWidgetControl.FINISH_STRENGTH -> R.string.widget_workout_finish
}

private fun controlAction(context: Context, control: WorkoutWidgetControl): Action =
    WorkoutWidgetIntents.serviceIntent(context, control)?.let { actionStartService(it, isForegroundService = false) }
        ?: actionSendBroadcast(WorkoutWidgetIntents.finishStrengthIntent(context))

@Composable
private fun ControlButton(context: Context, control: WorkoutWidgetControl, showLabel: Boolean) {
    val tint = if (control == WorkoutWidgetControl.END) END_RED else MOVE
    val label = context.getString(controlLabel(control))
    Row(
        GlanceModifier
            .fillMaxSize()
            .background(DashboardColors.bubble(tint))
            .cornerRadius(12.dp)
            .clickable(controlAction(context, control)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(controlIcon(control)),
            contentDescription = label,
            colorFilter = ColorFilter.tint(DashboardColors.fixed(tint)),
            modifier = GlanceModifier.size(18.dp)
        )
        if (showLabel) {
            Spacer(GlanceModifier.width(4.dp))
            Text(label, maxLines = 1, style = TextStyle(color = DashboardColors.fixed(tint), fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
    }
}
