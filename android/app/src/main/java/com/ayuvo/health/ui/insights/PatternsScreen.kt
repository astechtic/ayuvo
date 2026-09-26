package com.ayuvo.health.ui.insights

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
        disclaimers = listOfNotNull(cfg.disclaimers["patterns"], cfg.disclaimers["general"])
    ) {
        if (!insightsGate(ui, "insights.patterns", needsHealth = false)) return@InsightsScaffold
        val snap = ui.snapshot ?: return@InsightsScaffold
        val found = snap.patterns.filter { it.surfaced }
        val rest = snap.patterns.filter { !it.surfaced }
        if (found.isNotEmpty()) {
            item(key = "found") {
                InsetGroup(header = stringResource(R.string.insights_patterns_found), footer = cfg.disclaimers["patterns"], dividerInset = 16.dp, modifier = Modifier.testTag("insights.patterns.found")) {
                    found.forEach { p ->
                        row {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
                                Text(patternLabel(p.id), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
                                Text(p.text.orEmpty(), fontSize = 15.sp, lineHeight = 20.sp)
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
    if (info) InsightMethodologySheet(cfg, InsightMethodology.patterns(cfg, ui.snapshot) { id -> resources.getString(patternLabelRes(id)) }, onDismiss = { info = false })
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
