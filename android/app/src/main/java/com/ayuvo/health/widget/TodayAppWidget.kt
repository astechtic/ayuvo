package com.ayuvo.health.widget

import android.content.Context
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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.models.LogEntryIntents
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.models.formatFastingDuration
import com.ayuvo.health.widget.dashboard.DashboardRender
import com.ayuvo.health.widget.dashboard.WidgetDashboardSnapshot
import kotlinx.coroutines.flow.first

/**
 * Today widget (docs/widgets.md): Eat · Move · Drink rings; the large size adds Fasting, Next dose,
 * Weight and Workout rows, each hidden when there is nothing real to show. Taps open Summary.
 */
class TodayAppWidget : AyuvoGlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = PreferencesStore(context)
        val initial = runCatching { store.widgetDashboardSnapshot.first() }.getOrNull()
        provideContent {
            // Collected, not captured: updateAll() on a live session only recomposes.
            val snapshot by store.widgetDashboardSnapshot.collectAsState(initial)
            GlanceTheme { TodayContent(context, snapshot, System.currentTimeMillis()) }
        }
    }
}

class TodayWidgetReceiver : AyuvoGlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayAppWidget()
}

/** One ring as drawn: [value] / [goal] are display strings ("—" when unknown). */
private data class RingDisplay(val label: String, val color: Long, val progress: Float?, val value: String, val goal: String?)

private fun rings(context: Context, s: WidgetDashboardSnapshot?, nowMs: Long): List<RingDisplay> {
    val dash = context.getString(R.string.widget_no_value)
    if (s == null) {
        return listOf(
            RingDisplay(context.getString(R.string.widget_ring_eat), DashboardColors.EAT, null, dash, null),
            RingDisplay(context.getString(R.string.widget_ring_move), DashboardColors.MOVE, null, dash, null)
        )
    }
    val fresh = DashboardRender.isToday(s, nowMs)
    val eat = s.eat.value.takeIf { fresh }
    val result = mutableListOf(
        RingDisplay(
            context.getString(R.string.widget_ring_eat), DashboardColors.EAT,
            DashboardRender.progress(eat, s.eat.goal),
            WidgetFormat.integer(eat) ?: dash,
            WidgetFormat.integer(s.eat.goal)?.let { "$it kcal" }
        )
    )
    val steps = s.move.steps.takeIf { fresh }
    result += RingDisplay(
        context.getString(R.string.widget_ring_move), DashboardColors.MOVE,
        DashboardRender.progress(steps, s.move.goal),
        if (!s.move.connected) context.getString(R.string.widget_ring_connect) else WidgetFormat.integer(steps) ?: dash,
        WidgetFormat.integer(s.move.goal)?.let { "$it ${context.getString(R.string.widget_steps_unit)}" }
    )
    if (s.drink.enabled) {
        val unit = WaterUnit.fromStorage(s.waterUnitRaw)
        val ml = s.drink.ml.takeIf { fresh }
        result += RingDisplay(
            context.getString(R.string.widget_ring_drink), DashboardColors.DRINK,
            DashboardRender.progress(ml, s.drink.goal),
            ml?.let { unit.displayValue(it.toInt()) } ?: dash,
            s.drink.goal?.let { unit.format(it.toInt()) }
        )
    }
    return result
}

/** A detail row of the large size. */
private data class TodayRow(val iconRes: Int, val tint: Long, val title: String, val value: String)

private fun rows(context: Context, s: WidgetDashboardSnapshot, nowMs: Long): List<TodayRow> = buildList {
    DashboardRender.fastingElapsedMinutes(s, nowMs)?.let { minutes ->
        val elapsed = formatFastingDuration(minutes * 60)
        val value = s.fasting.goalMinutes?.let { context.getString(R.string.widget_fasting_progress, elapsed, formatFastingDuration(it * 60L)) } ?: elapsed
        add(TodayRow(R.drawable.ic_widget_timer, 0xFF00C7BE, context.getString(R.string.widget_row_fasting), value))
    }
    DashboardRender.nextDose(s, nowMs)?.let { dose ->
        val time = if (dose.due) context.getString(R.string.widget_dose_due) else WidgetFormat.time(context, dose.scheduledAtMs)
        add(TodayRow(R.drawable.ic_widget_pill, 0xFF32ADE6, context.getString(R.string.widget_row_next_dose), "${dose.name} · $time"))
    }
    s.weight?.let { w ->
        add(TodayRow(R.drawable.ic_widget_scale, 0xFFAF52DE, context.getString(R.string.widget_row_weight), WidgetFormat.weight(w.value, s.weightMetric)))
    }
    s.workouts?.takeIf { DashboardRender.isToday(s, nowMs) }?.let { w ->
        add(TodayRow(R.drawable.ic_widget_fitness, 0xFFFF9500, context.getString(R.string.widget_row_workout), context.getString(R.string.widget_workouts_summary, w.count, w.minutes)))
    }
}

@Composable
private fun TodayContent(context: Context, snapshot: WidgetDashboardSnapshot?, nowMs: Long) {
    val size = LocalSize.current
    val rings = rings(context, snapshot, nowMs)
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(DashboardColors.background)
            .cornerRadius(22.dp)
            .padding(12.dp)
            .clickable(actionStartActivity(LogEntryIntents.summaryIntent(context)))
    ) {
        when {
            size.width.value < 230f -> SmallToday(context, rings, size.width.value, size.height.value)
            size.height.value < 230f || snapshot == null -> MediumToday(context, rings)
            else -> Column(GlanceModifier.fillMaxSize()) {
                MediumToday(context, rings)
                Spacer(GlanceModifier.height(10.dp))
                rows(context, snapshot, nowMs).forEach { row ->
                    TodayRowView(row)
                    Spacer(GlanceModifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun SmallToday(context: Context, rings: List<RingDisplay>, width: Float, height: Float) {
    val ringDp = minOf(width - 24f, height - 64f).coerceIn(56f, 120f)
    val sizePx = context.dpToPx(ringDp)
    val stroke = context.dpToPx(ringDp * 0.11f).toFloat()
    val bitmap = concentricRingsBitmap(sizePx, stroke, stroke * 0.18f, rings.map { it.progress to it.color })
    Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        DashboardHeader(R.drawable.ic_widget_favorite, context.getString(R.string.widget_today_label), DashboardColors.EAT)
        Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
            Image(provider = ImageProvider(bitmap), contentDescription = null, modifier = GlanceModifier.size(ringDp.dp))
        }
        Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            rings.forEachIndexed { index, ring ->
                if (index > 0) Spacer(GlanceModifier.width(6.dp))
                Text(
                    ring.value,
                    maxLines = 1,
                    style = TextStyle(color = DashboardColors.fixed(ring.color), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                )
            }
        }
    }
}

@Composable
private fun MediumToday(context: Context, rings: List<RingDisplay>) {
    val ringDp = 58f
    val sizePx = context.dpToPx(ringDp)
    val stroke = context.dpToPx(8f).toFloat()
    Column(GlanceModifier.fillMaxWidth()) {
        DashboardHeader(R.drawable.ic_widget_favorite, context.getString(R.string.widget_today_label), DashboardColors.EAT)
        Spacer(GlanceModifier.height(8.dp))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            rings.forEach { ring ->
                Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        provider = ImageProvider(ringBitmap(sizePx, stroke, ring.progress, ring.color)),
                        contentDescription = ring.label,
                        modifier = GlanceModifier.size(ringDp.dp)
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    Text(ring.label, maxLines = 1, style = TextStyle(color = DashboardColors.secondary, fontSize = 11.sp, textAlign = TextAlign.Center))
                    Text(
                        ring.value,
                        maxLines = 1,
                        style = TextStyle(color = DashboardColors.fixed(ring.color), fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center)
                    )
                    ring.goal?.let {
                        Text(
                            context.getString(R.string.widget_of_goal, it),
                            maxLines = 1,
                            style = TextStyle(color = DashboardColors.secondary, fontSize = 10.sp, textAlign = TextAlign.Center)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayRowView(row: TodayRow) {
    Row(
        GlanceModifier.fillMaxWidth().background(DashboardColors.tile).cornerRadius(12.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(row.iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(DashboardColors.fixed(row.tint)),
            modifier = GlanceModifier.size(16.dp)
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(row.title, maxLines = 1, style = TextStyle(color = DashboardColors.secondary, fontSize = 12.sp))
        Spacer(GlanceModifier.defaultWeight())
        Text(row.value, maxLines = 1, style = TextStyle(color = DashboardColors.primary, fontWeight = FontWeight.Medium, fontSize = 13.sp))
    }
}
