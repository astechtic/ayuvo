package com.ayuvo.health.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll

/** Runs all widget refresh requests and reports whether every request was accepted. */
object WidgetUpdateCoordinator {
    suspend fun updateAll(context: Context): Boolean {
        var allSucceeded = true
        widgets.forEach { widget ->
            runCatching { widget.updateAll(context) }
                .onFailure {
                    allSucceeded = false
                    Log.e(TAG, "${widget.javaClass.simpleName}.updateAll failed", it)
                }
        }
        return allSucceeded
    }

    private val widgets: List<AyuvoGlanceAppWidget> = listOf(
        CalorieAppWidget(),
        ProteinAppWidget(),
        AllMetricsAppWidget(),
        WaterAppWidget(),
        TodayAppWidget(),
        MyMetricsAppWidget(),
        QuickLogAppWidget()
    )

    private const val TAG = "AyuvoWidget"
}
