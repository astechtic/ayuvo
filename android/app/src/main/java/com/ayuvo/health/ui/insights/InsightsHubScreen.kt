package com.ayuvo.health.ui.insights

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Update
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup

/** Where the Insights screens open from (docs/insights.md §7). */
data class InsightsDestinations(
    val openRecovery: () -> Unit,
    val openHealthAge: () -> Unit,
    val openReview: () -> Unit,
    val openTrends: () -> Unit,
    val openPatterns: () -> Unit
)

/** Browse › Insights: Recovery, Health Age, Daily Review, Trends and Patterns. */
@Composable
fun InsightsHubScreen(vm: InsightsViewModel, onBack: () -> Unit, destinations: InsightsDestinations) {
    val ui by vm.ui.collectAsState()
    var info by rememberSaveable { mutableStateOf(false) }
    val cfg = vm.config
    RefreshOnResume(vm::refresh)
    InsightsScaffold(
        title = stringResource(R.string.insights_title),
        tag = "insights.hub",
        onBack = onBack,
        onInfo = { info = true },
        disclaimers = listOfNotNull(cfg.disclaimers["general"], cfg.disclaimers["health_age"])
    ) {
        val snap = ui.snapshot
        if (!ui.enabled) {
            insightsGate(ui, "insights.hub")
            return@InsightsScaffold
        }
        item(key = "rows") {
            InsetGroup(modifier = Modifier.testTag("insights.hub.list")) {
                row {
                    GroupRow(
                        title = stringResource(R.string.insights_recovery),
                        subtitle = stringResource(R.string.insights_hub_recovery_sub),
                        icon = Icons.Outlined.Bedtime,
                        iconTint = InsightsFormat.Insights,
                        value = snap?.recovery?.score?.toString(),
                        modifier = Modifier.testTag("insights.hub.recovery"),
                        onClick = destinations.openRecovery
                    )
                }
                row {
                    GroupRow(
                        title = stringResource(R.string.insights_health_age),
                        subtitle = stringResource(R.string.insights_hub_health_age_sub),
                        icon = Icons.Outlined.Update,
                        iconTint = InsightsFormat.Insights,
                        value = snap?.healthAge?.healthAge?.let { InsightsFormat.number(it, 1) },
                        modifier = Modifier.testTag("insights.hub.healthAge"),
                        onClick = destinations.openHealthAge
                    )
                }
                row {
                    GroupRow(
                        title = stringResource(R.string.insights_daily_review),
                        subtitle = stringResource(R.string.insights_hub_review_sub),
                        icon = Icons.Outlined.Checklist,
                        iconTint = InsightsFormat.Insights,
                        value = snap?.reviews?.lastOrNull()?.dayScore?.toString(),
                        modifier = Modifier.testTag("insights.hub.review"),
                        onClick = destinations.openReview
                    )
                }
                row {
                    GroupRow(
                        title = stringResource(R.string.insights_trends),
                        subtitle = stringResource(R.string.insights_hub_trends_sub),
                        icon = Icons.AutoMirrored.Outlined.ShowChart,
                        iconTint = InsightsFormat.Insights,
                        modifier = Modifier.testTag("insights.hub.trends"),
                        onClick = destinations.openTrends
                    )
                }
                row {
                    GroupRow(
                        title = stringResource(R.string.insights_patterns),
                        subtitle = stringResource(R.string.insights_hub_patterns_sub),
                        icon = Icons.Outlined.Hub,
                        iconTint = InsightsFormat.Insights,
                        value = snap?.patterns?.count { it.surfaced }?.takeIf { it > 0 }?.toString(),
                        modifier = Modifier.testTag("insights.hub.patterns"),
                        onClick = destinations.openPatterns
                    )
                }
            }
        }
        if (snap != null && !snap.healthEnabled) {
            item(key = "health-off") {
                StateCard(Icons.Outlined.Insights, stringResource(R.string.insights_health_off), stringResource(R.string.insights_health_off_body), "insights.hub.healthOff")
            }
        }
    }
    if (info) InsightMethodologySheet(cfg, InsightMethodology.hub(cfg), onDismiss = { info = false })
}
