package com.ayuvo.health.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
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
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ayuvo.health.R
import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.models.LogEntryIntents
import com.ayuvo.health.models.formatFastingDuration
import com.ayuvo.health.ui.metrics.MetricCatalog
import com.ayuvo.health.widget.dashboard.DashboardRender
import com.ayuvo.health.widget.dashboard.WidgetDashboardSnapshot
import kotlinx.coroutines.flow.first

/** Per-widget slot keys stored in Glance state (docs/widgets.md: device-local, never backed up). */
object WidgetSlotKeys {
    fun metric(index: Int): Preferences.Key<String> = stringPreferencesKey("metric_$index")
    fun action(index: Int): Preferences.Key<String> = stringPreferencesKey("action_$index")
    const val SLOT_COUNT = 4
}

/** My Metrics widget: four user-chosen metric tiles (docs/widgets.md). */
class MyMetricsAppWidget : AyuvoGlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = PreferencesStore(context)
        val initial = runCatching { store.widgetDashboardSnapshot.first() }.getOrNull()
        provideContent {
            // Collected, not captured: updateAll() on a live session only recomposes.
            val snapshot by store.widgetDashboardSnapshot.collectAsState(initial)
            val nowMs = System.currentTimeMillis()
            val prefs = currentState<Preferences>()
            val slots = WidgetMetric.slots((0 until WidgetSlotKeys.SLOT_COUNT).map { prefs[WidgetSlotKeys.metric(it)] })
            GlanceTheme { MyMetricsContent(context, slots, snapshot, nowMs) }
        }
    }
}

class MyMetricsWidgetReceiver : AyuvoGlanceAppWidgetReceiver() {
    override val glanceAppWidget = MyMetricsAppWidget()
}

/** A tile as drawn. [caption] may be empty; [progress] draws the goal bar when present. */
internal data class MetricCell(val title: String, val number: String, val unit: String, val caption: String, val progress: Float?)

internal fun metricCell(context: Context, metric: WidgetMetric, s: WidgetDashboardSnapshot?, nowMs: Long): MetricCell {
    val title = when (metric) {
        WidgetMetric.NEXT_DOSE -> context.getString(R.string.widget_metric_next_dose)
        else -> MetricKey.parse(metric.key)?.let { MetricCatalog.title(context, it) } ?: metric.key
    }
    val dash = context.getString(R.string.widget_no_value)
    if (s == null) return MetricCell(title, dash, "", "", null)
    return when (metric) {
        WidgetMetric.NEXT_DOSE -> {
            val next = DashboardRender.nextDose(s, nowMs)
            val meds = s.medications?.takeIf { DashboardRender.isToday(s, nowMs) }
            when {
                next != null -> MetricCell(
                    title,
                    if (next.due) context.getString(R.string.widget_dose_due) else WidgetFormat.time(context, next.scheduledAtMs),
                    "", next.name, null
                )
                meds != null && meds.total > 0 -> MetricCell(title, context.getString(R.string.widget_doses_taken, meds.taken, meds.total), "", "", null)
                else -> MetricCell(title, dash, "", "", null)
            }
        }
        WidgetMetric.FASTING -> {
            val elapsed = DashboardRender.fastingElapsedMinutes(s, nowMs)
            when {
                !s.fasting.enabled && elapsed == null -> MetricCell(title, dash, "", context.getString(R.string.widget_tracking_off), null)
                elapsed != null -> MetricCell(
                    title, formatFastingDuration(elapsed * 60), "", context.getString(R.string.widget_fasting_now),
                    s.fasting.goalMinutes?.takeIf { it > 0 }?.let { (elapsed.toFloat() / it).coerceIn(0f, 1f) }
                )
                else -> tileCell(context, title, s, metric, nowMs)
            }
        }
        WidgetMetric.WATER -> if (!s.drink.enabled) MetricCell(title, dash, "", context.getString(R.string.widget_tracking_off), null) else tileCell(context, title, s, metric, nowMs)
        else -> tileCell(context, title, s, metric, nowMs)
    }
}

private fun tileCell(context: Context, title: String, s: WidgetDashboardSnapshot, metric: WidgetMetric, nowMs: Long): MetricCell {
    val tile = DashboardRender.tile(s, metric.key, nowMs)
    return MetricCell(title, tile.number, if (tile.hasData) tile.unit else "", WidgetFormat.caption(context, tile), tile.progress?.toFloat())
}

@Composable
private fun MyMetricsContent(context: Context, slots: List<WidgetMetric>, snapshot: WidgetDashboardSnapshot?, nowMs: Long) {
    val size = LocalSize.current
    val compact = size.width.value < 230f || size.height.value < 140f
    Column(
        GlanceModifier.fillMaxSize().background(DashboardColors.background).cornerRadius(22.dp).padding(8.dp)
    ) {
        slots.chunked(2).forEachIndexed { rowIndex, pair ->
            if (rowIndex > 0) Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                pair.forEachIndexed { index, metric ->
                    if (index > 0) Spacer(GlanceModifier.width(6.dp))
                    Box(GlanceModifier.defaultWeight().fillMaxHeight()) {
                        MetricCellView(context, metric, metricCell(context, metric, snapshot, nowMs), compact)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCellView(context: Context, metric: WidgetMetric, cell: MetricCell, compact: Boolean) {
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(DashboardColors.tile)
            .cornerRadius(14.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clickable(actionStartActivity(LogEntryIntents.metricIntent(context, metric.key))),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(metric.iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(DashboardColors.fixed(metric.tint)),
                modifier = GlanceModifier.size(12.dp)
            )
            Spacer(GlanceModifier.width(4.dp))
            Text(cell.title, maxLines = 1, style = TextStyle(color = DashboardColors.secondary, fontSize = 11.sp, fontWeight = FontWeight.Medium))
        }
        Spacer(GlanceModifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(cell.number, maxLines = 1, style = TextStyle(color = DashboardColors.primary, fontSize = if (compact) 15.sp else 19.sp, fontWeight = FontWeight.Bold))
            if (cell.unit.isNotEmpty()) {
                Spacer(GlanceModifier.width(3.dp))
                Text(cell.unit, maxLines = 1, style = TextStyle(color = DashboardColors.secondary, fontSize = 11.sp))
            }
        }
        if (!compact) {
            val progress = cell.progress
            if (progress != null) {
                Spacer(GlanceModifier.height(4.dp))
                Image(
                    provider = ImageProvider(barBitmap(context.dpToPx(120f), context.dpToPx(4f), progress, metric.tint)),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp)
                )
            } else if (cell.caption.isNotEmpty()) {
                Text(cell.caption, maxLines = 1, style = TextStyle(color = DashboardColors.secondary, fontSize = 10.sp))
            }
        }
    }
}
