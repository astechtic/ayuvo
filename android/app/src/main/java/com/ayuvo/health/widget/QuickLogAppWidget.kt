package com.ayuvo.health.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.ayuvo.health.R
import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.models.LogEntryIntents
import com.ayuvo.health.widget.dashboard.WidgetDashboardSnapshot
import kotlinx.coroutines.flow.first

/**
 * Quick Log widget: four user-chosen log buttons (docs/widgets.md). Each opens the app where the
 * Summary "+" entry (or the + food menu) goes; the widget itself never logs anything.
 */
class QuickLogAppWidget : AyuvoGlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = PreferencesStore(context)
        val initial = runCatching { store.widgetDashboardSnapshot.first() }.getOrNull()
        provideContent {
            // Collected, not captured: updateAll() on a live session only recomposes.
            val snapshot by store.widgetDashboardSnapshot.collectAsState(initial)
            val prefs = currentState<Preferences>()
            val slots = QuickLogAction.slots((0 until WidgetSlotKeys.SLOT_COUNT).map { prefs[WidgetSlotKeys.action(it)] })
            GlanceTheme { QuickLogContent(context, slots, snapshot) }
        }
    }
}

class QuickLogWidgetReceiver : AyuvoGlanceAppWidgetReceiver() {
    override val glanceAppWidget = QuickLogAppWidget()
}

/** Label and dimmed state for one button, from the snapshot's water / fasting switches. */
internal data class QuickLogCell(val label: String, val dimmed: Boolean)

internal fun quickLogCell(context: Context, action: QuickLogAction, s: WidgetDashboardSnapshot?): QuickLogCell {
    val fastActive = s?.fasting?.activeStartedAtMs != null
    val label = if (action == QuickLogAction.FASTING && fastActive) {
        context.getString(R.string.widget_action_end_fast)
    } else context.getString(action.labelRes)
    val dimmed = s != null && when (action.requires) {
        WidgetRequirement.WATER_TRACKING -> !s.drink.enabled
        WidgetRequirement.FASTING_TRACKING -> !s.fasting.enabled && !fastActive
        null -> false
    }
    return QuickLogCell(label, dimmed)
}

@Composable
private fun QuickLogContent(context: Context, slots: List<QuickLogAction>, snapshot: WidgetDashboardSnapshot?) {
    val size = LocalSize.current
    val grid = size.height.value >= 120f
    Column(GlanceModifier.fillMaxSize().background(DashboardColors.background).cornerRadius(22.dp).padding(6.dp)) {
        val rows = if (grid) slots.chunked(2) else listOf(slots)
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                row.forEachIndexed { index, action ->
                    if (index > 0) Spacer(GlanceModifier.width(6.dp))
                    Box(GlanceModifier.defaultWeight().fillMaxHeight()) {
                        QuickLogButton(context, action, quickLogCell(context, action, snapshot), compact = !grid && size.height.value < 90f)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickLogButton(context: Context, action: QuickLogAction, cell: QuickLogCell, compact: Boolean) {
    val tint = if (cell.dimmed) DashboardColors.DIMMED else action.tint
    val bubble = if (compact) 28.dp else 36.dp
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(DashboardColors.tile)
            .cornerRadius(16.dp)
            .padding(4.dp)
            .clickable(actionStartActivity(LogEntryIntents.logIntent(context, action))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            GlanceModifier.size(bubble).background(DashboardColors.bubble(tint)).cornerRadius(bubble / 2),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(action.iconRes),
                contentDescription = cell.label,
                colorFilter = ColorFilter.tint(DashboardColors.fixed(tint)),
                modifier = GlanceModifier.size(if (compact) 16.dp else 20.dp)
            )
        }
        Spacer(GlanceModifier.height(if (compact) 2.dp else 4.dp))
        Text(
            cell.label,
            maxLines = 1,
            style = TextStyle(
                color = if (cell.dimmed) DashboardColors.secondary else DashboardColors.primary,
                fontSize = if (compact) 10.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        )
    }
}
