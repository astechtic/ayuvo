package com.ayuvo.health.ui.insights

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.data.analytics.ForecastView
import com.ayuvo.health.data.analytics.engine.AnalyticsConfig
import com.ayuvo.health.insights.AnalyticsDay
import com.ayuvo.health.l10n.ContractStrings
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.SurfaceCard
import kotlin.math.roundToInt

/**
 * Display text for `analytics_config.json` (docs/localization.md): looked up through [ContractStrings] by the keys
 * scripts/l10n/l10n_contracts.py generates (`analytics.…`), with the config English as fallback.
 */
object AnalyticsText {
    fun t(context: Context?, key: String, english: String): String = ContractStrings.text(context, key, english)

    private fun cfg(): Map<String, Any?>? = AnalyticsConfig.active?.root

    @Suppress("UNCHECKED_CAST")
    private fun path(vararg keys: String): String? {
        var node: Any? = cfg()
        for (k in keys) node = (node as? Map<String, Any?>)?.get(k)
        return node as? String
    }

    fun metricLabel(context: Context?, id: String): String =
        path("metrics", id, "label")?.let { t(context, "analytics.metrics.$id.label", it) } ?: id

    fun loadState(context: Context?, state: String?): String =
        state?.let { s -> path("load", "states", s)?.let { t(context, "analytics.load.states.$s", it) } ?: s } ?: InsightsFormat.MISSING

    fun anomalyState(context: Context?, state: String?): String? =
        state?.let { s -> path("anomaly", "states", s)?.let { t(context, "analytics.anomaly.states.$s", it) } }

    fun persistentNote(context: Context?): String? = path("anomaly", "persistent_note")?.let { t(context, "analytics.anomaly.persistent_note", it) }

    fun warning(context: Context?, code: String): String? = path("recovery", "warnings", code)?.let { t(context, "analytics.recovery.warnings.$code", it) }

    fun disclaimer(context: Context?): String? = path("disclaimer")?.let { t(context, "analytics.disclaimer", it) }

    fun classification(context: Context?, id: String?): String? =
        id?.let { c -> path("classifications", c, "label")?.let { t(context, "analytics.classifications.$c.label", it) } }

    fun summaryNone(context: Context?): String? = path("recovery", "summary", "none")?.let { t(context, "analytics.recovery.summary.none", it) }

    /** "Main signals pulling the score down: …" rebuilt from translated driver texts. */
    fun summary(context: Context?, drivers: List<Map<String, Any?>>): String? {
        val neg = drivers.filter { it["direction"] == "negative" }
        val pos = drivers.filter { it["direction"] == "positive" }
        fun text(d: Map<String, Any?>) = t(context, "analytics.recovery.drivers.${d["text_key"] ?: "${d["id"]}.${d["direction"]}"}", d["text"] as? String ?: "")
        val parts = ArrayList<String>()
        if (neg.isNotEmpty()) path("recovery", "summary", "lead_negative")?.let {
            parts += t(context, "analytics.recovery.summary.lead_negative", it).replace("{items}", neg.joinToString("; ") { d -> text(d) })
        }
        if (pos.isNotEmpty()) path("recovery", "summary", "lead_positive")?.let {
            parts += t(context, "analytics.recovery.summary.lead_positive", it).replace("{items}", pos.joinToString("; ") { d -> text(d) })
        }
        return if (parts.isEmpty()) summaryNone(context) else parts.joinToString(" ")
    }

    fun percent(conf: Any?): String? = (conf as? Number)?.let { "${(it.toDouble() * 100).roundToInt()}%" }
}

internal fun Map<String, Any?>?.n(key: String): Double? = (this?.get(key) as? Number)?.toDouble()
internal fun Map<String, Any?>?.s(key: String): String? = this?.get(key) as? String
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>?.m(key: String): Map<String, Any?>? = this?.get(key) as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>?.ms(key: String): List<Map<String, Any?>> = (this?.get(key) as? List<Map<String, Any?>>).orEmpty()

@Composable
internal fun trendLabel(label: String?): String = stringResource(
    when (label) {
        "IMPROVING" -> R.string.analytics_trend_IMPROVING
        "STABLE" -> R.string.analytics_trend_STABLE
        "DECLINING" -> R.string.analytics_trend_DECLINING
        "UNUSUAL" -> R.string.analytics_trend_UNUSUAL
        "CHANGING" -> R.string.analytics_trend_CHANGING
        else -> R.string.analytics_trend_INSUFFICIENT_DATA
    }
)

/** "Confidence 87%" plus the status note when it is not a plain VALID result. */
@Composable
internal fun StatusLine(status: String?, confidence: Any?) {
    val pct = AnalyticsText.percent(confidence)
    val note = when (status) {
        "LOW_CONFIDENCE" -> stringResource(R.string.analytics_status_low)
        "INSUFFICIENT_HISTORY" -> stringResource(R.string.analytics_status_history)
        "INSUFFICIENT_DATA" -> stringResource(R.string.analytics_not_enough)
        "NO_DATA" -> stringResource(R.string.analytics_status_no_data)
        else -> null
    }
    Row(Modifier.fillMaxWidth().padding16(), verticalAlignment = Alignment.CenterVertically) {
        if (pct != null && status in setOf("VALID", "LOW_CONFIDENCE")) Chip(stringResource(R.string.analytics_confidence, pct), AyuvoColors.secondaryLabel())
        note?.let { Text("  $it", fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.weight(1f)) }
    }
}

private fun Modifier.padding16(): Modifier = this.padding(horizontal = 16.dp, vertical = 8.dp)

/** Browse › Insights › Health signals: every analytics card (docs/health-analytics.md §5). */
@Composable
fun HealthSignalsScreen(vm: InsightsViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var info by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    RefreshOnResume(vm::refresh)
    InsightsScaffold(
        title = stringResource(R.string.analytics_signals_title),
        tag = "insights.signals",
        onBack = onBack,
        onInfo = { info = true },
        disclaimers = listOfNotNull(AnalyticsText.disclaimer(context))
    ) {
        if (!insightsGate(ui, "insights.signals")) return@InsightsScaffold
        val a = ui.snapshot?.analytics
        if (a == null) {
            item(key = "none") { StateCard(Icons.Outlined.Insights, stringResource(R.string.analytics_not_enough), "", "insights.signals.none") }
            return@InsightsScaffold
        }
        item(key = "signals") { SignalsSection(a) }
        if (ui.forecastEnabled) ui.forecasts.values.filter { it.deployed && it.prediction != null }.forEach { f ->
            item(key = "forecast-${f.target}") { ForecastCard(f) }
        }
        a.hrv?.let { item(key = "hrv") { HrvSection(it) } }
        a.sleep?.let { item(key = "sleep") { SleepSection(it) } }
        a.load?.let { item(key = "load") { LoadSection(it) } }
        a.hrr?.let { item(key = "hrr") { HrrSection(it) } }
        a.met?.let { item(key = "met") { MetSection(it) } }
        a.energy?.let { item(key = "energy") { EnergySection(it) } }
        a.vo2max?.let { item(key = "vo2") { Vo2Section(it) } }
    }
    if (info) InsightMethodologySheet(vm.config, InsightMethodology.analytics(context), onDismiss = { info = false })
}

@Composable
private fun SignalsSection(a: AnalyticsDay) {
    val context = LocalContext.current
    val an = a.anomaly
    SurfaceCard(modifier = Modifier.testTag("insights.signals.anomaly"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.analytics_signals_card), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AyuvoColors.secondaryLabel())
        val state = an.s("state")
        if (state == null) {
            Text(stringResource(R.string.analytics_not_enough), fontSize = 15.sp)
            return@SurfaceCard
        }
        val color = when (state) {
            "NORMAL" -> AyuvoPalette.Success
            "MILD_DEVIATION" -> AyuvoPalette.Warning
            else -> AyuvoPalette.Destructive
        }
        Text(AnalyticsText.anomalyState(context, state) ?: an.s("message").orEmpty(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = color)
        an.ms("signals").filter { it["flagged"] == true }.forEach { sig ->
            Text(
                stringResource(R.string.analytics_signal_row, AnalyticsText.metricLabel(context, sig.s("id").orEmpty().let { if (it == "training_load") "training_load" else it }), InsightsFormat.signed(sig.n("z"), 1)),
                fontSize = 15.sp
            )
        }
        if (an?.get("persistent") == true) {
            AnalyticsText.persistentNote(context)?.let { Text(it, fontSize = 13.sp, color = AyuvoColors.secondaryLabel()) }
        }
        StatusLine(an.s("status"), an?.get("confidence"))
    }
}

@Composable
private fun HrvSection(h: Map<String, Any?>) {
    val b = h.m("baseline")
    val t = h.m("trend")
    InsetGroup(header = stringResource(R.string.analytics_hrv_title), dividerInset = 16.dp, modifier = Modifier.testTag("insights.signals.hrv")) {
        row { KeyValueRow(stringResource(R.string.analytics_today), b.n("value")?.let { "${InsightsFormat.number(it)} ms" } ?: InsightsFormat.MISSING) }
        row { KeyValueRow(stringResource(R.string.analytics_baseline), b.n("median")?.let { "${InsightsFormat.number(it)} ms" } ?: stringResource(R.string.analytics_status_history)) }
        row { KeyValueRow(stringResource(R.string.analytics_vs_baseline), b.n("z")?.let { stringResource(R.string.analytics_sd, InsightsFormat.signed(it, 1)) } ?: InsightsFormat.MISSING) }
        row { KeyValueRow(stringResource(R.string.analytics_trend), trendLabel(t.s("label"))) }
        row { KeyValueRow(stringResource(R.string.analytics_stability), h.n("cv_ln_7d")?.let { "${InsightsFormat.number(it, 1)}%" } ?: InsightsFormat.MISSING) }
        row { StatusLine(b.s("status"), b?.get("confidence")) }
    }
}

@Composable
private fun SleepSection(s: Map<String, Any?>) {
    val need = InsightsFormat.duration(s.n("need_min"))
    InsetGroup(header = stringResource(R.string.analytics_sleep_title), dividerInset = 16.dp, modifier = Modifier.testTag("insights.signals.sleep")) {
        row { KeyValueRow(stringResource(R.string.analytics_today), InsightsFormat.duration(s.n("asleep_min"))) }
        row {
            KeyValueRow(
                stringResource(R.string.analytics_sleep_need),
                if (s.s("need_source") == "personal") need else stringResource(R.string.analytics_sleep_need_default, need)
            )
        }
        row { KeyValueRow(stringResource(R.string.analytics_sleep_debt), InsightsFormat.duration(s.n("debt_min"))) }
        row { KeyValueRow(stringResource(R.string.analytics_efficiency), s.n("efficiency")?.let { "${InsightsFormat.number(it)}%" } ?: InsightsFormat.MISSING) }
        row { KeyValueRow(stringResource(R.string.analytics_bedtime_var), s.n("bedtime_sd")?.let { stringResource(R.string.analytics_plus_minus_min, InsightsFormat.number(it)) } ?: InsightsFormat.MISSING) }
        row { KeyValueRow(stringResource(R.string.analytics_wake_var), s.n("wake_sd")?.let { stringResource(R.string.analytics_plus_minus_min, InsightsFormat.number(it)) } ?: InsightsFormat.MISSING) }
        s.n("nap_min")?.takeIf { it > 0 }?.let { row { KeyValueRow(stringResource(R.string.analytics_naps), InsightsFormat.duration(it)) } }
        row { StatusLine(s.s("status"), s?.get("confidence")) }
    }
}

@Composable
private fun LoadSection(l: Map<String, Any?>) {
    val context = LocalContext.current
    val method = l.s("primary_method")
    val pm = method?.let { l.m("methods").m(it) }
    InsetGroup(header = stringResource(R.string.analytics_load_title), dividerInset = 16.dp, modifier = Modifier.testTag("insights.signals.load")) {
        row { KeyValueRow(stringResource(R.string.analytics_load_state), if (l.s("state") != null) AnalyticsText.loadState(context, l.s("state")) else stringResource(R.string.analytics_status_history)) }
        method?.let {
            row {
                KeyValueRow(stringResource(R.string.analytics_load_method), stringResource(
                    when (it) {
                        "trimp" -> R.string.analytics_method_trimp
                        "rpe_load" -> R.string.analytics_method_rpe_load
                        else -> R.string.analytics_method_minutes
                    }
                ))
            }
        }
        row { KeyValueRow(stringResource(R.string.analytics_load_acute), InsightsFormat.number(pm.n("acute"))) }
        row { KeyValueRow(stringResource(R.string.analytics_load_chronic), InsightsFormat.number(pm.n("chronic"))) }
        row { KeyValueRow(stringResource(R.string.analytics_load_ratio), InsightsFormat.number(pm.n("ratio"), 2)) }
        row { Text(stringResource(R.string.analytics_load_note), fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.fillMaxWidth().padding16()) }
        row { StatusLine(l.s("status"), l?.get("confidence")) }
    }
}

@Composable
private fun HrrSection(h: Map<String, Any?>) {
    InsetGroup(header = stringResource(R.string.analytics_hrr_title), dividerInset = 16.dp, modifier = Modifier.testTag("insights.signals.hrr")) {
        row { KeyValueRow(stringResource(R.string.analytics_hrr_latest, h.s("latest_day").orEmpty()), h.n("latest")?.let { "${InsightsFormat.number(it)} bpm" } ?: InsightsFormat.MISSING) }
        row { KeyValueRow(stringResource(R.string.analytics_baseline), h.n("median")?.let { "${InsightsFormat.number(it)} bpm" } ?: stringResource(R.string.analytics_not_enough)) }
        row { KeyValueRow(stringResource(R.string.analytics_trend), trendLabel(h.s("label"))) }
    }
}

@Composable
private fun MetSection(m: Map<String, Any?>) {
    InsetGroup(header = stringResource(R.string.analytics_met_title), dividerInset = 16.dp, modifier = Modifier.testTag("insights.signals.met")) {
        row { KeyValueRow(stringResource(R.string.analytics_met_moderate), InsightsFormat.number(m.n("moderate_min"))) }
        row { KeyValueRow(stringResource(R.string.analytics_met_vigorous), InsightsFormat.number(m.n("vigorous_min"))) }
        row { KeyValueRow(stringResource(R.string.analytics_met_light), InsightsFormat.number(m.n("light_min"))) }
        row { KeyValueRow(stringResource(R.string.analytics_met_equivalent), InsightsFormat.number(m.n("moderate_equivalent_min"))) }
        m.n("unknown_min")?.takeIf { it > 0 }?.let { row { KeyValueRow(stringResource(R.string.analytics_met_unknown), InsightsFormat.number(it)) } }
    }
}

@Composable
private fun EnergySection(e: Map<String, Any?>) {
    InsetGroup(header = stringResource(R.string.analytics_energy_title), dividerInset = 16.dp, modifier = Modifier.testTag("insights.signals.energy")) {
        row {
            KeyValueRow(
                stringResource(
                    when (e.s("resting_source")) {
                        "provider" -> R.string.analytics_energy_resting_provider
                        "predicted" -> R.string.analytics_energy_resting_predicted
                        else -> R.string.analytics_energy_resting
                    }
                ),
                e.n("resting_kcal")?.let { "${InsightsFormat.number(it)} kcal" } ?: InsightsFormat.MISSING
            )
        }
        row { KeyValueRow(stringResource(R.string.analytics_energy_active), e.n("active_kcal")?.let { "${InsightsFormat.number(it)} kcal" } ?: InsightsFormat.MISSING) }
        row { KeyValueRow(stringResource(R.string.analytics_energy_total), e.n("estimated_daily_expenditure")?.let { "${InsightsFormat.number(it)} kcal" } ?: InsightsFormat.MISSING) }
        row { Text(stringResource(R.string.analytics_energy_note), fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.fillMaxWidth().padding16()) }
        row { StatusLine(e.s("status"), e?.get("confidence")) }
    }
}

@Composable
private fun Vo2Section(v: Map<String, Any?>) {
    val kinds = v.m("kinds")
    val shown = listOf("provider" to R.string.analytics_vo2_provider, "gps" to R.string.analytics_vo2_gps, "uth" to R.string.analytics_vo2_uth)
        .filter { (k, _) -> kinds.m(k).n("latest") != null }
    if (shown.isEmpty()) return
    InsetGroup(header = stringResource(R.string.analytics_vo2_title), dividerInset = 16.dp, modifier = Modifier.testTag("insights.signals.vo2")) {
        shown.forEach { (k, label) ->
            val r = kinds.m(k)
            val latest = InsightsFormat.number(r.n("latest"), 1)
            row {
                KeyValueRow(
                    stringResource(label),
                    r.n("change")?.let { stringResource(R.string.analytics_vo2_change, latest, InsightsFormat.signed(it, 1)) } ?: latest
                )
            }
        }
    }
}

@Composable
private fun ForecastCard(f: ForecastView) {
    val context = LocalContext.current
    SurfaceCard(modifier = Modifier.testTag("insights.signals.forecast.${f.target}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.analytics_forecast_title) + " · " + AnalyticsText.metricLabel(context, f.target), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AyuvoColors.secondaryLabel())
        Text(
            stringResource(R.string.analytics_forecast_range, InsightsFormat.number(f.prediction, 0), InsightsFormat.number(f.intervalLow, 0), InsightsFormat.number(f.intervalHigh, 0)),
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(
                R.string.analytics_forecast_metrics, InsightsFormat.number(f.metrics.n("mae"), 1),
                InsightsFormat.number(f.baselines.n("persistence_mae"), 1), InsightsFormat.number(f.baselines.n("median28_mae"), 1), f.nTest
            ),
            fontSize = 13.sp, color = AyuvoColors.secondaryLabel()
        )
        Text(stringResource(R.string.analytics_forecast_note), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
        AnalyticsText.classification(context, "ML_PREDICTED")?.let { Chip(it, AyuvoColors.secondaryLabel()) }
    }
}
