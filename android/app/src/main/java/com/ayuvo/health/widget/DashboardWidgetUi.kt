package com.ayuvo.health.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.ui.metrics.MetricCatalog
import com.ayuvo.health.ui.metrics.MetricUnits
import com.ayuvo.health.widget.dashboard.WidgetTile
import java.text.NumberFormat
import java.util.Date

/** Neutral Apple-flat surfaces for the Today / My Metrics / Quick Log widgets (docs/ui-structure.md §3). */
internal object DashboardColors {
    val background = DayNightColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1C1C1E))
    val tile = DayNightColorProvider(day = Color(0xFFF2F2F7), night = Color(0xFF2C2C2E))
    val primary = DayNightColorProvider(day = Color(0xFF1C1C1E), night = Color(0xFFF2F2F7))
    val secondary = DayNightColorProvider(day = Color(0xFF8E8E93), night = Color(0xFF8E8E93))
    const val DIMMED = 0xFF8E8E93

    const val EAT = 0xFF34C759
    const val MOVE = 0xFFFF9500
    const val DRINK = 0xFF007AFF

    fun fixed(argb: Long): ColorProvider = ColorProvider(Color(argb))

    /** Tinted bubble fill: the domain colour at 18% alpha. */
    fun bubble(argb: Long): ColorProvider = ColorProvider(Color(argb).copy(alpha = 0.18f))
}

internal fun Context.dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)

private fun track(argb: Int): Int = (argb and 0x00FFFFFF) or (0x40 shl 24)

/** One ring with a round-capped arc from 12 o'clock; [progress] null draws the track only. */
internal fun ringBitmap(sizePx: Int, strokePx: Float, progress: Float?, argb: Long): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    drawRing(Canvas(bitmap), sizePx / 2f, sizePx / 2f - strokePx / 2f, strokePx, progress, argb.toInt())
    return bitmap
}

/** Apple-style concentric rings, outermost first. */
internal fun concentricRingsBitmap(sizePx: Int, strokePx: Float, gapPx: Float, rings: List<Pair<Float?, Long>>): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    rings.forEachIndexed { index, (progress, argb) ->
        val radius = sizePx / 2f - strokePx / 2f - index * (strokePx + gapPx)
        if (radius > strokePx / 2f) drawRing(canvas, sizePx / 2f, radius, strokePx, progress, argb.toInt())
    }
    return bitmap
}

private fun drawRing(canvas: Canvas, center: Float, radius: Float, strokePx: Float, progress: Float?, argb: Int) {
    val rect = RectF(center - radius, center - radius, center + radius, center + radius)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        color = track(argb)
    }
    canvas.drawArc(rect, 0f, 360f, false, paint)
    val sweep = (progress ?: 0f).coerceIn(0f, 1f) * 360f
    if (sweep > 0f) {
        paint.color = argb
        canvas.drawArc(rect, -90f, sweep, false, paint)
    }
}

/** Thin horizontal goal bar for My Metrics tiles. */
internal fun barBitmap(widthPx: Int, heightPx: Int, progress: Float, argb: Long): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = heightPx / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = track(argb.toInt()) }
    canvas.drawRoundRect(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), radius, radius, paint)
    val fill = progress.coerceIn(0f, 1f) * widthPx
    if (fill > 0f) {
        paint.color = argb.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, fill.coerceAtLeast(heightPx.toFloat()), heightPx.toFloat()), radius, radius, paint)
    }
    return bitmap
}

/** Formatting shared by the three widgets; "—" whenever a value is unknown. */
internal object WidgetFormat {
    fun integer(value: Double?): String? = value?.let { NumberFormat.getIntegerInstance().format(Math.round(it)) }

    fun time(context: Context, ms: Long): String = DateFormat.getTimeFormat(context).format(Date(ms))

    fun relative(ms: Long): String =
        DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

    fun weight(kg: Double, metric: Boolean): String {
        val units = MetricUnits(weightMetric = metric)
        val display = MetricCatalog.display(AppMetricId.WEIGHT, kg, units)
        return "${MetricCatalog.formatDisplay(AppMetricId.WEIGHT, display)} ${MetricCatalog.unitLabel(AppMetricId.WEIGHT, units)}"
    }

    fun caption(context: Context, tile: WidgetTile): String = when {
        !tile.hasData -> ""
        tile.caption == WidgetTile.CAPTION_TODAY -> context.getString(R.string.widget_caption_today)
        tile.caption == WidgetTile.CAPTION_LAST_NIGHT -> context.getString(R.string.widget_caption_last_night)
        tile.captionMs != null -> relative(tile.captionMs)
        else -> ""
    }
}

/** Small tinted glyph + label header used by the dashboard widgets. */
@Composable
internal fun DashboardHeader(iconRes: Int, label: String, tint: Long, fontSize: TextUnit = 12.sp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(DashboardColors.fixed(tint)),
            modifier = GlanceModifier.size(13.dp)
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(
            text = label,
            maxLines = 1,
            style = TextStyle(color = DashboardColors.secondary, fontWeight = FontWeight.Medium, fontSize = fontSize)
        )
    }
}
