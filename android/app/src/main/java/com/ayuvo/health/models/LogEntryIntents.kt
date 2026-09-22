package com.ayuvo.health.models

import android.content.Context
import android.content.Intent
import com.ayuvo.health.MainActivity
import com.ayuvo.health.widget.QuickLogAction

/** Where a widget tap asks the app to go (docs/widgets.md "Deep links"). */
sealed interface WidgetTarget {
    /** A Quick Log action: the same landing as the Summary "+" entry. */
    data class Log(val action: QuickLogAction) : WidgetTarget
    /** A My Metrics tile: the metric key as stored (`app:fasting`, `medications:next_dose`, health ids…). */
    data class Metric(val key: String) : WidgetTarget
    /** A Today widget tap: the Summary tab root. */
    data object Summary : WidgetTarget
}

data class WidgetRequest(val target: WidgetTarget, val id: Long = System.nanoTime())

/**
 * Intent contract between the Today / My Metrics / Quick Log widgets and [MainActivity]
 * (mirrors [com.ayuvo.health.medications.model.MedicationIntents]). Ids come from
 * `shared/widgets/widget_options.json`.
 */
object LogEntryIntents {
    const val ACTION_LOG = "com.ayuvo.health.LOG_ENTRY"
    const val EXTRA_LOG = "log_entry"
    const val ACTION_METRIC = "com.ayuvo.health.OPEN_METRIC"
    const val EXTRA_METRIC = "metric_key"
    const val ACTION_SUMMARY = "com.ayuvo.health.OPEN_SUMMARY"

    fun logIntent(context: Context, action: QuickLogAction): Intent =
        base(context, ACTION_LOG).putExtra(EXTRA_LOG, action.id)

    fun metricIntent(context: Context, key: String): Intent =
        base(context, ACTION_METRIC).putExtra(EXTRA_METRIC, key)

    fun summaryIntent(context: Context): Intent = base(context, ACTION_SUMMARY)

    private fun base(context: Context, action: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    /** Returns a request only for our actions; an unknown log id or empty metric opens Summary. */
    fun requestFrom(intent: Intent?): WidgetRequest? =
        targetFrom(intent?.action, intent?.getStringExtra(EXTRA_LOG), intent?.getStringExtra(EXTRA_METRIC))?.let(::WidgetRequest)

    /** Pure parse, kept separate so it can be unit-tested without an Android Intent. */
    fun targetFrom(action: String?, logId: String?, metricKey: String?): WidgetTarget? = when (action) {
        ACTION_LOG -> QuickLogAction.fromId(logId)?.let { WidgetTarget.Log(it) } ?: WidgetTarget.Summary
        ACTION_METRIC -> metricKey?.trim()?.takeIf { it.isNotEmpty() }?.let { WidgetTarget.Metric(it) } ?: WidgetTarget.Summary
        ACTION_SUMMARY -> WidgetTarget.Summary
        else -> null
    }
}
