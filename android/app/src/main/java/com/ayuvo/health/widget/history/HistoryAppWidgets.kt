package com.ayuvo.health.widget.history

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.ayuvo.health.MainActivity
import com.ayuvo.health.R
import com.ayuvo.health.actions.ActionIntents
import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.models.LogEntryIntents
import com.ayuvo.health.widget.AyuvoGlanceAppWidget
import com.ayuvo.health.widget.AyuvoGlanceAppWidgetReceiver
import com.ayuvo.health.widget.DashboardColors
import com.ayuvo.health.widget.DashboardHeader
import com.ayuvo.health.widget.dpToPx
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.TextStyle as MonthStyle
import java.util.Locale

/** Which history a widget shows (docs/widgets.md "History widgets"). */
enum class HistoryKind { WORKOUT, FOOD }

/**
 * Workout / Food history widgets: a GitHub-style year heatmap, one square per day. The grid is drawn
 * into a Bitmap (Glance has no canvas); as many weeks as fit, ending with this week. Reads only
 * [HistoryHeatmapSnapshot]; days it does not cover (e.g. after midnight before a refresh) are empty.
 */
abstract class HistoryAppWidget(private val kind: HistoryKind) : AyuvoGlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = PreferencesStore(context)
        val initial = runCatching { store.historyHeatmapSnapshot.first() }.getOrNull()
        provideContent {
            val snapshot by store.historyHeatmapSnapshot.collectAsState(initial)
            GlanceTheme { HistoryContent(context, kind, snapshot, LocalDate.now()) }
        }
    }
}

class WorkoutHistoryAppWidget : HistoryAppWidget(HistoryKind.WORKOUT)
class FoodHistoryAppWidget : HistoryAppWidget(HistoryKind.FOOD)

class WorkoutHistoryWidgetReceiver : AyuvoGlanceAppWidgetReceiver() {
    override val glanceAppWidget = WorkoutHistoryAppWidget()
}

class FoodHistoryWidgetReceiver : AyuvoGlanceAppWidgetReceiver() {
    override val glanceAppWidget = FoodHistoryAppWidget()
}

private object HistoryStyle {
    const val WORKOUT = DashboardColors.MOVE
    const val FOOD = DashboardColors.EAT
    /** Level 0: neutral grey that reads on both the light and the dark widget background. */
    const val EMPTY = 0x408E8E93
    const val LABEL = 0xFF8E8E93.toInt()
    val ALPHA = floatArrayOf(0f, 0.35f, 0.55f, 0.78f, 1f)

    fun tint(kind: HistoryKind): Long = if (kind == HistoryKind.WORKOUT) WORKOUT else FOOD

    fun levelColor(level: Int, tint: Long): Int {
        if (level <= 0) return EMPTY
        val alpha = (ALPHA[level.coerceIn(1, HistoryHeatmap.LEVELS)] * 255).toInt()
        return (tint.toInt() and 0x00FFFFFF) or (alpha shl 24)
    }
}

/** Workout history opens the workout minutes detail; food history opens Nutrition (`open.section`). */
private fun tapIntent(context: Context, kind: HistoryKind): Intent = when (kind) {
    HistoryKind.WORKOUT -> LogEntryIntents.metricIntent(context, AppMetricId.WORKOUT_MINUTES.key)
    HistoryKind.FOOD -> Intent(context, MainActivity::class.java).apply {
        action = ActionIntents.ACTION
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(ActionIntents.EXTRA_ACTION_ID, "open.section")
        putExtra("section", "nutrition")
    }
}

@Composable
private fun HistoryContent(context: Context, kind: HistoryKind, snapshot: HistoryHeatmapSnapshot?, today: LocalDate) {
    val size = LocalSize.current
    val tint = HistoryStyle.tint(kind)
    val wide = size.width.value >= 230f
    val showLegend = size.height.value >= 135f
    val title = context.getString(if (kind == HistoryKind.WORKOUT) R.string.widget_history_workout_title else R.string.widget_history_food_title)
    val icon = if (kind == HistoryKind.WORKOUT) R.drawable.ic_widget_fitness else R.drawable.ic_widget_restaurant
    val lastDay = snapshot?.lastDay

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(DashboardColors.background)
            .cornerRadius(22.dp)
            .padding(12.dp)
            .clickable(actionStartActivity(tapIntent(context, kind)))
    ) {
        if (snapshot == null || lastDay == null) {
            DashboardHeader(icon, title, tint)
            Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                Text(
                    context.getString(R.string.widget_history_open_app),
                    style = TextStyle(color = DashboardColors.secondary, fontSize = 12.sp, textAlign = TextAlign.Center)
                )
            }
            return@Column
        }

        val widthDp = size.width.value - 24f
        val gridDp = (size.height.value - 24f - 18f - (if (showLegend) 20f else 0f)).coerceAtLeast(40f)
        val heatmap = heatmapBitmap(
            context = context,
            widthPx = context.dpToPx(widthDp),
            heightPx = context.dpToPx(gridDp),
            series = if (kind == HistoryKind.WORKOUT) snapshot.workout else snapshot.food,
            lastDay = lastDay,
            today = today,
            weekStart = snapshot.weekStart,
            tint = tint
        )
        val months = HistoryHeatmap.monthsShown(heatmap.columns)
        val range = context.resources.getQuantityString(R.plurals.widget_history_last_months, months, months)
        val by = context.getString(if (kind == HistoryKind.WORKOUT) R.string.widget_history_by_time else R.string.widget_history_by_meals)
        val header = if (wide) context.getString(R.string.widget_history_title_full, title, range, by)
        else context.getString(R.string.widget_history_title_short, title, range)
        val series = if (kind == HistoryKind.WORKOUT) snapshot.workout else snapshot.food
        val a11y = context.resources.getQuantityString(
            if (kind == HistoryKind.WORKOUT) R.plurals.widget_history_workout_a11y else R.plurals.widget_history_food_a11y,
            series.activeDays, series.activeDays
        )

        DashboardHeader(icon, header, tint)
        Spacer(GlanceModifier.height(6.dp))
        Image(
            provider = ImageProvider(heatmap.bitmap),
            contentDescription = a11y,
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight()
        )
        if (showLegend) {
            Spacer(GlanceModifier.height(4.dp))
            Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalAlignment = Alignment.CenterVertically) {
                val less = if (kind == HistoryKind.WORKOUT) R.string.widget_history_less_time else R.string.widget_history_fewer_meals
                val more = if (kind == HistoryKind.WORKOUT) R.string.widget_history_more_time else R.string.widget_history_more_meals
                Text(context.getString(less), maxLines = 1, style = TextStyle(color = DashboardColors.secondary, fontSize = 10.sp))
                Spacer(GlanceModifier.width(4.dp))
                Image(
                    provider = ImageProvider(legendBitmap(context, tint)),
                    contentDescription = null,
                    modifier = GlanceModifier.size(width = LEGEND_WIDTH_DP.dp, height = LEGEND_CELL_DP.dp)
                )
                Spacer(GlanceModifier.width(4.dp))
                Text(context.getString(more), maxLines = 1, style = TextStyle(color = DashboardColors.secondary, fontSize = 10.sp))
            }
        }
    }
}

private const val LEGEND_CELL_DP = 10f
private const val LEGEND_GAP_DP = 3f
private const val LEGEND_WIDTH_DP = LEGEND_CELL_DP * 5 + LEGEND_GAP_DP * 4
private const val MAX_PITCH_DP = 18f
private const val LABEL_DP = 13f

internal class HeatmapBitmap(val bitmap: Bitmap, val columns: Int)

/** Five squares, level 0 → 4, for the "Less … More" legend. */
private fun legendBitmap(context: Context, tint: Long): Bitmap {
    val cell = context.dpToPx(LEGEND_CELL_DP)
    val gap = context.dpToPx(LEGEND_GAP_DP)
    val bitmap = Bitmap.createBitmap(cell * 5 + gap * 4, cell, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val radius = cell * 0.25f
    for (level in 0..HistoryHeatmap.LEVELS) {
        paint.color = HistoryStyle.levelColor(level, tint)
        val left = (level * (cell + gap)).toFloat()
        canvas.drawRoundRect(RectF(left, 0f, left + cell, cell.toFloat()), radius, radius, paint)
    }
    return bitmap
}

/**
 * The grid: 7 rows (week start first), columns = weeks ending with the week of [today], days after
 * today not drawn, today outlined, month labels above the column that contains a month's 1st.
 */
internal fun heatmapBitmap(
    context: Context,
    widthPx: Int,
    heightPx: Int,
    series: HistorySeries,
    lastDay: LocalDate,
    today: LocalDate,
    weekStart: WeekStart,
    tint: Long
): HeatmapBitmap {
    val density = context.resources.displayMetrics.density
    val labelPx = LABEL_DP * density
    val pitch = minOf((heightPx - labelPx) / 7f, MAX_PITCH_DP * density).coerceAtLeast(2f)
    val columns = ((widthPx + pitch * 0.18f) / pitch).toInt().coerceIn(1, HistoryHeatmap.DAYS / 7)
    val cell = pitch * 0.82f
    val gridWidth = columns * pitch - (pitch - cell)
    val left = ((widthPx - gridWidth) / 2f).coerceAtLeast(0f)
    val gridHeight = 7 * pitch - (pitch - cell)
    val top = (labelPx + ((heightPx - labelPx) - gridHeight) / 2f).coerceAtLeast(labelPx)

    val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (1.5f * density).coerceAtMost(cell * 0.25f)
        color = tint.toInt()
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HistoryStyle.LABEL
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, context.resources.displayMetrics)
    }
    val radius = cell * 0.24f
    val grid = HistoryHeatmap.grid(today, weekStart, columns)
    val locale = Locale.getDefault()
    var labelEnd = -Float.MAX_VALUE

    for (c in 0 until columns) {
        val x = left + c * pitch
        for (r in 0 until 7) {
            val day = grid.day(c, r)
            if (day > today) continue
            if (day.dayOfMonth == 1 && x >= labelEnd) {
                val label = day.month.getDisplayName(MonthStyle.SHORT_STANDALONE, locale)
                canvas.drawText(label, x, labelPx - 3f * density, text)
                labelEnd = x + text.measureText(label) + 4f * density
            }
            val y = top + r * pitch
            val rect = RectF(x, y, x + cell, y + cell)
            fill.color = HistoryStyle.levelColor(HistoryHeatmap.levelAt(series, lastDay, day), tint)
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (day == today) {
                val inset = outline.strokeWidth / 2f
                canvas.drawRoundRect(RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset), radius, radius, outline)
            }
        }
    }
    return HeatmapBitmap(bitmap, columns)
}
