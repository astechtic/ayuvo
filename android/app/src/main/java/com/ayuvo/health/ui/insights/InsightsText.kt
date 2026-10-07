package com.ayuvo.health.ui.insights

import android.content.Context
import com.ayuvo.health.insights.InsightsConfig
import com.ayuvo.health.insights.InsightsMath
import com.ayuvo.health.insights.PatternResult
import com.ayuvo.health.insights.RecoveryResult
import com.ayuvo.health.insights.RecoverySignal
import com.ayuvo.health.insights.ReviewItem
import com.ayuvo.health.l10n.ContractStrings

/**
 * Display text for `insights_config.json` (docs/localization.md): labels, disclaimers, methodology
 * and the engine sentences, translated at display time through [ContractStrings]. The engines, the
 * reference vectors and the AI payload keep the English the engines wrote; a sentence is re-filled
 * from its params only when its template has a translation, otherwise the engine text is shown.
 */
object InsightsText {
    private fun t(context: Context?, key: String, english: String): String = ContractStrings.text(context, key, english)

    fun metricLabel(context: Context?, m: InsightsConfig.Metric): String = t(context, "insights.metrics.${m.id}.label", m.label)

    fun areaLabel(context: Context?, a: InsightsConfig.Area): String = t(context, "insights.daily_review.areas.${a.id}.label", a.label)

    fun markerLabel(context: Context?, m: InsightsConfig.Marker): String = t(context, "insights.health_age.markers.${m.id}.label", m.label)

    fun disclaimer(context: Context?, cfg: InsightsConfig, id: String): String? =
        cfg.disclaimers[id]?.let { t(context, "insights.disclaimers.$id", it) }

    fun methodologyTitle(context: Context?, id: String, m: InsightsConfig.Methodology): String =
        t(context, "insights.methodology.$id.title", m.title)

    fun sectionHeading(context: Context?, id: String, index: Int, s: InsightsConfig.Section): String =
        t(context, "insights.methodology.$id.sections.$index.heading", s.heading)

    fun sectionBody(context: Context?, id: String, index: Int, s: InsightsConfig.Section): String =
        t(context, "insights.methodology.$id.sections.$index.body", s.body)

    /** Recovery band name (`good`, `moderate`, `low`); [english] is the engine's `labelText`. */
    fun bandLabel(context: Context?, bandId: String?, english: String?): String =
        if (bandId == null || english == null) english.orEmpty() else t(context, "insights.recovery.bands.$bandId.label", english)

    fun bandRecommendation(context: Context?, bandId: String?, english: String?): String =
        if (bandId == null || english == null) english.orEmpty() else t(context, "insights.recovery.bands.$bandId.recommendation", english)

    /** Training-load category label (`none`, `light`, `moderate`, `high`). */
    fun trainingLoadLabel(context: Context?, category: String, english: String): String =
        t(context, "insights.training_load.labels.$category", english)

    /** "Explained on this device" / "Explained using online AI · {provider}" (key suffix `local` or `cloud`). */
    fun aiStatusLabel(context: Context?, kind: String, english: String): String = t(context, "insights.ai.status_labels.$kind", english)

    /** [english] template translated via [key] and filled with [params]; [fallback] when untranslated or it cannot be filled. */
    private fun refill(context: Context?, key: String, english: String, params: Map<String, Any?>, fallback: String, localize: (String, Any?) -> Any?): String {
        val template = t(context, key, english)
        if (template == english || params.isEmpty() && english.contains('{')) return fallback
        return runCatching { InsightsMath.fill(template, params.mapValues { (k, v) -> localize(k, v) }) }.getOrDefault(fallback)
    }

    /** A Recovery contributor line ("HRV +8% vs baseline"). */
    fun signal(context: Context?, cfg: InsightsConfig?, r: RecoveryResult, s: RecoverySignal): String {
        // Recovery v2 drivers carry their analytics contract key (shared/analytics recovery.drivers).
        (s.params["v2_key"] as? String)?.let { return t(context, it, s.text) }
        val english = cfg?.recovery?.contributors?.get(s.id) ?: return s.text
        return refill(context, "insights.recovery.contributors.${s.id}", english, s.params, s.text) { k, v ->
            if (s.id == "training_load" && k == "category") {
                r.load?.let { trainingLoadLabel(context, it.category, v as? String ?: it.label) } ?: v
            } else v
        }
    }

    /** A pattern sentence; the more/less word is translated with the template. */
    fun pattern(context: Context?, cfg: InsightsConfig?, p: PatternResult): String? {
        val text = p.text ?: return null
        val pair = cfg?.patterns?.pairs?.firstOrNull { it.id == p.id } ?: return text
        return refill(context, "insights.patterns.pairs.${p.id}.template", pair.template, p.params, text) { k, v ->
            when {
                k != "direction_word" -> v
                v == pair.moreWord -> t(context, "insights.patterns.pairs.${p.id}.more_word", pair.moreWord)
                v == pair.lessWord -> t(context, "insights.patterns.pairs.${p.id}.less_word", pair.lessWord)
                else -> v
            }
        }
    }

    /** A Daily Review line (rule or "not logged"); [patterns] resolve the `reduce_pattern` sentence. */
    fun reviewItem(context: Context?, cfg: InsightsConfig?, item: ReviewItem, patterns: List<PatternResult>? = null): String {
        val rv = cfg?.dailyReview ?: return item.text
        val (key, english) = if (item.ruleId == "not_logged") {
            "insights.daily_review.not_logged_template" to rv.notLoggedTemplate
        } else {
            val rule = rv.rules.firstOrNull { it.id == item.ruleId } ?: return item.text
            "insights.daily_review.rules.${rule.id}.template" to rule.template
        }
        if (item.ruleId == "reduce_pattern") {
            val source = patterns?.firstOrNull { it.text != null && it.text == item.params["pattern_text"] }
            return source?.let { pattern(context, cfg, it) } ?: item.text
        }
        return refill(context, key, english, item.params, item.text) { k, v ->
            when (k) {
                "area_label" -> rv.areas.firstOrNull { it.id == item.params["area"] }?.let { areaLabel(context, it) } ?: v
                "nutrient" -> rv.reduceNutrients.firstOrNull { it.label == v }?.let {
                    t(context, "insights.daily_review.reduce_nutrients.${it.id}.label", it.label)
                } ?: v
                else -> v
            }
        }
    }
}
