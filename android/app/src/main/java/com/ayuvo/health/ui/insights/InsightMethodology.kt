package com.ayuvo.health.ui.insights

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

    private fun disclaimers(cfg: InsightsConfig, vararg ids: String): List<String> = ids.mapNotNull { cfg.disclaimers[it] }

    fun recovery(cfg: InsightsConfig, snap: InsightsSnapshot?): MethodologyContent {
        val rc = cfg.recovery
        val weights = rc.components.map { cfg.metric(it.metric).label to pct(it.weight) } +
            ("Training load" to "${InsightsFormat.number(rc.loadHigh.toDouble())} / ${InsightsFormat.number(rc.loadVeryHigh.toDouble())} points")
        val inputs = snap?.recovery?.components?.map { c ->
            val m = cfg.metric(c.id)
            when {
                c.available -> InsightInput(
                    m.label,
                    InsightsFormat.metricValue(m.id, m.unit, c.value) + " · baseline " + InsightsFormat.metricValue(m.id, m.unit, c.baseline) +
                        if (c.fallback) " · daily value" else ""
                )
                c.value == null -> InsightInput(m.label, "No reading last night", missing = true)
                else -> InsightInput(m.label, "Learning (${c.baselineN}/${m.minPoints} nights)", missing = true)
            }
        }.orEmpty() + listOfNotNull(snap?.recovery?.load?.let { InsightInput("Yesterday's training", it.label) })
        return MethodologyContent(
            listOf("recovery", "baselines", "background"), weights, inputs,
            listOf(rc.source, cfg.trainingLoad.source), disclaimers(cfg, "general", "background")
        )
    }

    fun healthAge(cfg: InsightsConfig, snap: InsightsSnapshot?): MethodologyContent {
        val ha = cfg.healthAge
        val weights = ha.markers.map { it.label to "${pct(it.weight)} · ±${InsightsFormat.number(it.capYears)} y" }
        val inputs = ArrayList<InsightInput>()
        snap?.healthAge?.let { h ->
            inputs += InsightInput("Actual age", h.actualAge?.let { InsightsFormat.number(it, 1) } ?: "Birthday missing", missing = h.actualAge == null)
            for (m in h.markers) {
                val label = ha.markers.firstOrNull { it.id == m.id }?.label ?: m.id
                inputs += if (m.available) {
                    InsightInput(label, InsightsFormat.markerValue(m.id, m.basis, m.value, m.secondaryValue) + " · " + InsightsFormat.signed(m.contributionYears, 2) + " y")
                } else {
                    InsightInput(label, "Not enough data (${m.days}/${m.neededDays} days)", missing = true)
                }
            }
        }
        return MethodologyContent(
            listOf("health_age"), weights, inputs,
            listOf(ha.source) + ha.markers.map { "${it.label}: ${it.source}" },
            disclaimers(cfg, "health_age", "general")
        )
    }

    fun dailyReview(cfg: InsightsConfig, snap: InsightsSnapshot?, day: LocalDate?): MethodologyContent {
        val rv = cfg.dailyReview
        val weights = rv.areas.map { it.label to pct(it.weight) }
        val review = day?.let { snap?.review(it) }
        val inputs = review?.areas?.mapNotNull { a ->
            val label = rv.areas.firstOrNull { it.id == a.id }?.label ?: a.id
            when {
                a.included -> InsightInput(label, "${a.score} / 100")
                review.notLogged.any { it.params["area"] == a.id } -> InsightInput(label, "Not logged", missing = true)
                else -> null
            }
        }.orEmpty()
        return MethodologyContent(listOf("daily_review"), weights, inputs, emptyList(), disclaimers(cfg, "general"))
    }

    fun patterns(cfg: InsightsConfig, snap: InsightsSnapshot?, label: (String) -> String): MethodologyContent {
        val pc = cfg.patterns
        val weights = listOf(
            "Window" to "${pc.windowDays} days",
            "Each group" to "≥ ${pc.minGroup} days",
            "Welch t" to "|t| ≥ ${InsightsFormat.number(pc.minAbsT, 0)}",
            "Cohen's d" to "|d| ≥ ${InsightsFormat.number(pc.minAbsD, 1)}"
        )
        val inputs = snap?.patterns?.map { p -> InsightInput(label(p.id), "${p.nExposed} vs ${p.nUnexposed} days", missing = p.status != "ok") }.orEmpty()
        return MethodologyContent(listOf("patterns"), weights, inputs, listOf(pc.source), disclaimers(cfg, "patterns", "general"))
    }

    fun baselines(cfg: InsightsConfig, snap: InsightsSnapshot?): MethodologyContent {
        val weights = cfg.metrics.values.filter { it.androidType != null }.map { it.label to "${it.windowDays} d · ≥ ${it.minPoints} readings" }
        val inputs = snap?.baselines?.map { b ->
            InsightInput(b.metric.label, if (b.baseline.ok) "${b.baseline.n} readings" else "${b.baseline.n}/${b.baseline.needed} readings", missing = !b.baseline.ok)
        }.orEmpty()
        return MethodologyContent(listOf("baselines"), weights, inputs, emptyList(), disclaimers(cfg, "general"))
    }

    fun hub(cfg: InsightsConfig): MethodologyContent =
        MethodologyContent(listOf("background", "baselines"), emptyList(), emptyList(), emptyList(), disclaimers(cfg, "general", "background"))
}
