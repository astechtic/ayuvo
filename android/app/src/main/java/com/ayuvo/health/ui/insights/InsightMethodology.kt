package com.ayuvo.health.ui.insights

import android.content.Context
import com.ayuvo.health.R
import com.ayuvo.health.insights.InsightsConfig
import com.ayuvo.health.insights.InsightsSnapshot
import java.time.LocalDate

/** One "Your inputs" line: what was used, or that it was missing and why. */
data class InsightInput(val label: String, val value: String, val missing: Boolean = false)

/**
 * The content of one "How we calculate this" sheet: the config methodology blocks (identical on
 * both platforms), the weights table, the person's own inputs, the sources and the disclaimers.
 */
data class MethodologyContent(
    val methodologyIds: List<String>,
    val weights: List<Pair<String, String>>,
    val inputs: List<InsightInput>,
    val sources: List<String>,
    val disclaimers: List<String>
)

object InsightMethodology {
    private fun pct(w: Double): String = InsightsFormat.number(w, if (w % 1.0 == 0.0) 0 else 1) + "%"

    private fun disclaimers(context: Context, cfg: InsightsConfig, vararg ids: String): List<String> = ids.mapNotNull { InsightsText.disclaimer(context, cfg, it) }

    @Suppress("UNCHECKED_CAST")
    private fun analyticsRoot(): Map<String, Any?>? = com.ayuvo.health.data.analytics.engine.AnalyticsConfig.active?.root

    /**
     * Recovery Indicator v2 (shared/analytics, docs/health-analytics.md §5.7): weights with their reasons, the
     * person's own inputs vs baseline (robust z), classification and the analytics disclaimer.
     */
    @Suppress("UNCHECKED_CAST")
    private fun recoveryV2(context: Context, cfg: InsightsConfig, snap: InsightsSnapshot): MethodologyContent? {
        val root = analyticsRoot() ?: return null
        val rc = root["recovery"] as? Map<String, Any?> ?: return null
        val res = context.resources
        val comps = (rc["components"] as List<Map<String, Any?>>)
        val weights = comps.map { c ->
            val metric = c["metric"] as String
            AnalyticsText.metricLabel(context, metric) to pct((c["weight"] as Number).toDouble())
        }
        val v2 = snap.recovery.v2
        val inputs = (v2?.get("components") as? List<Map<String, Any?>>).orEmpty().map { c ->
            val id = c["id"] as String
            val metric = comps.firstOrNull { it["id"] == id }?.get("metric") as? String ?: id
            val label = AnalyticsText.metricLabel(context, metric)
            if (c["available"] == true) {
                val unit = c["unit"] as? String ?: ""
                val value = (c["value"] as? Number)?.toDouble()
                val base = (c["baseline"] as? Number)?.toDouble()
                val z = (c["z"] as? Number)?.toDouble()
                val fmt = { v: Double? -> if (unit == "min") InsightsFormat.duration(v) else "${InsightsFormat.number(v, if (unit == "br/min" || unit == "°C") 1 else 0)} $unit" }
                InsightInput(label, res.getString(R.string.ui_insights_value_baseline, fmt(value), fmt(base)) + (z?.let { " · " + res.getString(R.string.analytics_sd, InsightsFormat.signed(it, 1)) } ?: ""))
            } else InsightInput(label, res.getString(R.string.ui_insights_no_reading), missing = true)
        }
        val why = comps.map { c -> AnalyticsText.metricLabel(context, c["metric"] as String) + ": " + (c["why"] as? String).orEmpty() }
        val classification = AnalyticsText.classification(context, "PERSONALIZED_STATISTICAL")
        return MethodologyContent(
            listOf("background"), weights, inputs,
            listOfNotNull(classification) + why,
            listOfNotNull(AnalyticsText.disclaimer(context)) + disclaimers(context, cfg, "background")
        )
    }

    /** Health signals screen: the algorithms behind each card, with their assumptions (shared/analytics registry). */
    @Suppress("UNCHECKED_CAST")
    fun analytics(context: Context): MethodologyContent {
        val root = analyticsRoot()
        val algos = (root?.get("algorithms") as? List<Map<String, Any?>>).orEmpty()
            .filter { it["id"] in setOf("ayuvo.anomaly", "ayuvo.hrv.status", "ayuvo.sleep.need", "ayuvo.sleep.status", "ayuvo.load.ewma", "ayuvo.hrr", "ayuvo.met.intensity", "ayuvo.energy", "ayuvo.fitness.vo2max_trend", "ayuvo.forecast") }
        val weights = algos.map { a -> (a["id"] as String) + " v" + (a["version"] as Number).toInt() to (AnalyticsText.classification(context, a["classification"] as? String) ?: "") }
        val sources = algos.map { a -> (a["id"] as String) + ": " + (a["assumptions"] as? String).orEmpty() }
        return MethodologyContent(emptyList(), weights, emptyList(), sources, listOfNotNull(AnalyticsText.disclaimer(context)))
    }

    fun recovery(context: Context, cfg: InsightsConfig, snap: InsightsSnapshot?): MethodologyContent {
        if (snap?.recovery?.v2 != null) recoveryV2(context, cfg, snap)?.let { return it }
        val res = context.resources
        val rc = cfg.recovery
        val weights = rc.components.map { InsightsText.metricLabel(context, cfg.metric(it.metric)) to pct(it.weight) } +
            (res.getString(R.string.ui_insights_training_load) to res.getString(R.string.ui_insights_load_points, InsightsFormat.number(rc.loadHigh.toDouble()), InsightsFormat.number(rc.loadVeryHigh.toDouble())))
        val inputs = snap?.recovery?.components?.map { c ->
            val m = cfg.metric(c.id)
            val mLabel = InsightsText.metricLabel(context, m)
            when {
                c.available -> InsightInput(
                    mLabel,
                    res.getString(
                        if (c.fallback) R.string.ui_insights_value_baseline_daily else R.string.ui_insights_value_baseline,
                        InsightsFormat.metricValue(m.id, m.unit, c.value), InsightsFormat.metricValue(m.id, m.unit, c.baseline)
                    )
                )
                c.value == null -> InsightInput(mLabel, res.getString(R.string.ui_insights_no_reading), missing = true)
                else -> InsightInput(mLabel, res.getString(R.string.ui_insights_learning_nights, c.baselineN, m.minPoints), missing = true)
            }
        }.orEmpty() + listOfNotNull(snap?.recovery?.load?.let { InsightInput(res.getString(R.string.ui_insights_yesterday_training), InsightsText.trainingLoadLabel(context, it.category, it.label)) })
        return MethodologyContent(
            listOf("recovery", "baselines", "background"), weights, inputs,
            listOf(rc.source, cfg.trainingLoad.source), disclaimers(context, cfg, "general", "background")
        )
    }

    fun healthAge(context: Context, cfg: InsightsConfig, snap: InsightsSnapshot?): MethodologyContent {
        val res = context.resources
        val ha = cfg.healthAge
        val weights = ha.markers.map { InsightsText.markerLabel(context, it) to res.getString(R.string.ui_insights_weight_cap, pct(it.weight), InsightsFormat.number(it.capYears)) }
        val inputs = ArrayList<InsightInput>()
        snap?.healthAge?.let { h ->
            inputs += InsightInput(res.getString(R.string.ui_insights_actual_age), h.actualAge?.let { InsightsFormat.number(it, 1) } ?: res.getString(R.string.ui_insights_birthday_missing), missing = h.actualAge == null)
            for (m in h.markers) {
                val label = ha.markers.firstOrNull { it.id == m.id }?.let { InsightsText.markerLabel(context, it) } ?: m.id
                inputs += if (m.available) {
                    InsightInput(label, res.getString(R.string.ui_insights_value_years, InsightsFormat.markerValue(m.id, m.basis, m.value, m.secondaryValue, res = res), InsightsFormat.signed(m.contributionYears, 2)))
                } else {
                    InsightInput(label, res.getString(R.string.ui_insights_not_enough_days, m.days, m.neededDays), missing = true)
                }
            }
        }
        return MethodologyContent(
            listOf("health_age"), weights, inputs,
            listOf(ha.source) + ha.markers.map { "${InsightsText.markerLabel(context, it)}: ${it.source}" },
            disclaimers(context, cfg, "health_age", "general")
        )
    }

    fun dailyReview(context: Context, cfg: InsightsConfig, snap: InsightsSnapshot?, day: LocalDate?): MethodologyContent {
        val res = context.resources
        val rv = cfg.dailyReview
        val weights = rv.areas.map { InsightsText.areaLabel(context, it) to pct(it.weight) }
        val review = day?.let { snap?.review(it) }
        val inputs = review?.areas?.mapNotNull { a ->
            val label = rv.areas.firstOrNull { it.id == a.id }?.let { InsightsText.areaLabel(context, it) } ?: a.id
            when {
                a.included -> InsightInput(label, "${a.score} / 100")
                review.notLogged.any { it.params["area"] == a.id } -> InsightInput(label, res.getString(R.string.ui_insights_not_logged), missing = true)
                else -> null
            }
        }.orEmpty()
        return MethodologyContent(listOf("daily_review"), weights, inputs, emptyList(), disclaimers(context, cfg, "general"))
    }

    fun patterns(context: Context, cfg: InsightsConfig, snap: InsightsSnapshot?, label: (String) -> String): MethodologyContent {
        val res = context.resources
        val pc = cfg.patterns
        val weights = listOf(
            res.getString(R.string.ui_insights_window) to res.getQuantityString(R.plurals.ui_insights_days, pc.windowDays, pc.windowDays),
            res.getString(R.string.ui_insights_each_group) to res.getString(R.string.ui_insights_at_least_days, pc.minGroup),
            res.getString(R.string.ui_insights_welch_t) to "|t| ≥ ${InsightsFormat.number(pc.minAbsT, 0)}",
            res.getString(R.string.ui_insights_cohens_d) to "|d| ≥ ${InsightsFormat.number(pc.minAbsD, 1)}"
        )
        val inputs = snap?.patterns?.map { p -> InsightInput(label(p.id), res.getString(R.string.ui_insights_days_vs, p.nExposed, p.nUnexposed), missing = p.status != "ok") }.orEmpty()
        return MethodologyContent(listOf("patterns"), weights, inputs, listOf(pc.source), disclaimers(context, cfg, "patterns", "general"))
    }

    fun baselines(context: Context, cfg: InsightsConfig, snap: InsightsSnapshot?): MethodologyContent {
        val res = context.resources
        val weights = cfg.metrics.values.filter { it.androidType != null }.map { InsightsText.metricLabel(context, it) to res.getString(R.string.ui_insights_window_readings, it.windowDays, it.minPoints) }
        val inputs = snap?.baselines?.map { b ->
            InsightInput(InsightsText.metricLabel(context, b.metric), if (b.baseline.ok) res.getQuantityString(R.plurals.ui_insights_readings, b.baseline.n, b.baseline.n) else res.getString(R.string.ui_insights_readings_of, b.baseline.n, b.baseline.needed), missing = !b.baseline.ok)
        }.orEmpty()
        return MethodologyContent(listOf("baselines"), weights, inputs, emptyList(), disclaimers(context, cfg, "general"))
    }

    fun hub(context: Context, cfg: InsightsConfig): MethodologyContent =
        MethodologyContent(listOf("background", "baselines"), emptyList(), emptyList(), emptyList(), disclaimers(context, cfg, "general", "background"))
}
