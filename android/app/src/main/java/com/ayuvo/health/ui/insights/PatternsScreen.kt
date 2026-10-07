package com.ayuvo.health.ui.insights

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.insights.PatternResult
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.InsetGroup

/** Ayuvo Patterns: associations in the person's own data, always with sample sizes, never causes. */
@Composable
fun PatternsScreen(vm: InsightsViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var info by rememberSaveable { mutableStateOf(false) }
    val cfg = vm.config
    val resources = androidx.compose.ui.platform.LocalResources.current
    RefreshOnResume(vm::refresh)
    InsightsScaffold(
        title = stringResource(R.string.insights_patterns),
        tag = "insights.patterns",
        onBack = onBack,
        onInfo = { info = true },
        disclaimers = listOfNotNull(InsightsText.disclaimer(LocalContext.current, cfg, "patterns"), InsightsText.disclaimer(LocalContext.current, cfg, "general"))
    ) {
        if (!insightsGate(ui, "insights.patterns", needsHealth = false)) return@InsightsScaffold
        val snap = ui.snapshot ?: return@InsightsScaffold
        snap.analytics?.correlation?.let { corr -> item(key = "associations") { AssociationsGroup(corr, snap.analytics.responses) } }
        val found = snap.patterns.filter { it.surfaced }
        val rest = snap.patterns.filter { !it.surfaced }
        if (found.isNotEmpty()) {
            item(key = "found") {
                InsetGroup(header = stringResource(R.string.insights_patterns_found), footer = InsightsText.disclaimer(LocalContext.current, cfg, "patterns"), dividerInset = 16.dp, modifier = Modifier.testTag("insights.patterns.found")) {
                    found.forEach { p ->
                        row {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
                                Text(patternLabel(p.id), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
                                Text(InsightsText.pattern(LocalContext.current, cfg, p).orEmpty(), fontSize = 15.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }
        }
        item(key = "checked") {
            InsetGroup(header = stringResource(R.string.insights_patterns_checked), dividerInset = 16.dp, modifier = Modifier.testTag("insights.patterns.checked")) {
                rest.forEach { p -> row { KeyValueRow(patternLabel(p.id), patternStatus(p), dimmed = p.status != "ok") } }
            }
        }
    }
    if (info) InsightMethodologySheet(cfg, InsightMethodology.patterns(LocalContext.current, cfg, ui.snapshot) { id -> resources.getString(patternLabelRes(id)) }, onDismiss = { info = false })
}

/** Patterns v2: Spearman associations with Benjamini–Hochberg control and the personal response effect. */
@Composable
private fun AssociationsGroup(corr: Map<String, Any?>, responses: List<Map<String, Any?>>) {
    val context = LocalContext.current
    val surfaced = corr.ms("results").filter { it["surfaced"] == true }
    InsetGroup(
        header = stringResource(R.string.analytics_patterns_v2),
        footer = stringResource(R.string.analytics_patterns_v2_sub),
        dividerInset = 16.dp,
        modifier = Modifier.testTag("insights.patterns.associations")
    ) {
        if (surfaced.isEmpty()) {
            row { Text(stringResource(R.string.analytics_patterns_none), fontSize = 15.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) }
        }
        surfaced.forEach { r ->
            val resp = responses.firstOrNull { it["id"] == r["id"] && it["lag"] == r["lag"] }
            row {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
                    Text(associationText(context, r), fontSize = 15.sp, lineHeight = 20.sp)
                    Text(
                        stringResource(
                            R.string.analytics_pattern_stats, InsightsFormat.signed(r.n("spearman_rho"), 2),
                            InsightsFormat.signed(r.n("ci_low"), 2), InsightsFormat.signed(r.n("ci_high"), 2), r.n("n")?.toInt() ?: 0
                        ),
                        fontSize = 13.sp, color = AyuvoColors.secondaryLabel()
                    )
                    resp?.takeIf { it["status"] == "VALID" || it["status"] == "LOW_CONFIDENCE" }?.let {
                        Text(
                            stringResource(R.string.analytics_pattern_effect, InsightsFormat.signed(it.n("effect"), 3), InsightsFormat.signed(it.n("ci_low"), 3), InsightsFormat.signed(it.n("ci_high"), 3)),
                            fontSize = 13.sp, color = AyuvoColors.secondaryLabel()
                        )
                    }
                }
            }
        }
    }
}

/** The association sentence rebuilt from its translated template parts (analytics.correlation.*). */
private fun associationText(context: android.content.Context, r: Map<String, Any?>): String {
    val root = com.ayuvo.health.data.analytics.engine.AnalyticsConfig.active?.root ?: return r.s("text").orEmpty()
    val cc = root.m("correlation") ?: return r.s("text").orEmpty()
    val pair = cc.ms("pairs").firstOrNull { it["id"] == r["id"] } ?: return r.s("text").orEmpty()
    val id = pair.s("id")
    val dir = if ((r.n("spearman_rho") ?: 0.0) > 0) "higher" else "lower"
    val lag = (r.n("lag")?.toInt() ?: 0).toString()
    val t = { key: String, en: String? -> AnalyticsText.t(context, key, en.orEmpty()) }
    return t("analytics.correlation.template", cc.s("template"))
        .replace("{exposure}", t("analytics.correlation.pairs.$id.exposure_label", pair.s("exposure_label")))
        .replace("{direction}", t("analytics.correlation.direction_words.$dir", cc.m("direction_words").s(dir)))
        .replace("{outcome}", t("analytics.correlation.pairs.$id.outcome_label", pair.s("outcome_label")))
        .replace("{lag}", t("analytics.correlation.lag_words.$lag", cc.m("lag_words").s(lag)))
        .replace("{n}", (r.n("n")?.toInt() ?: 0).toString())
}

internal fun patternLabelRes(id: String): Int = when (id) {
    "late_workout_sleep" -> R.string.insights_pattern_late_workout_sleep
    "high_load_recovery" -> R.string.insights_pattern_high_load_recovery
    "water_goal_recovery" -> R.string.insights_pattern_water_goal_recovery
    "protein_strength_volume" -> R.string.insights_pattern_protein_strength_volume
    else -> R.string.insights_pattern_short_sleep_steps
}

@Composable
private fun patternLabel(id: String): String = stringResource(patternLabelRes(id))

@Composable
private fun patternStatus(p: PatternResult): String =
    if (p.status == "ok") stringResource(R.string.insights_pattern_no_clear, p.nExposed, p.nUnexposed)
    else stringResource(R.string.insights_pattern_collecting, p.nExposed, p.nUnexposed, p.needed)
