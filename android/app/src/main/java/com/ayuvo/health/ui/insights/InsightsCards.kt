package com.ayuvo.health.ui.insights

import com.ayuvo.health.insights.InsightsConfig
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Update
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ayuvo.health.R
import com.ayuvo.health.insights.HealthAgePace
import com.ayuvo.health.insights.HealthAgeResult
import com.ayuvo.health.insights.InsightsSnapshot
import com.ayuvo.health.insights.RecoveryResult
import com.ayuvo.health.ui.design.SummaryTile
import com.ayuvo.health.ui.design.SummaryTileChart
import kotlin.math.abs

/** What the Summary Insights section shows (docs/insights.md §7); null hides the section. */
sealed interface SummaryInsights {
    /** One card replaces the others while the Recovery baselines are being learned. */
    data class Learning(val have: Int, val need: Int) : SummaryInsights

    data class Cards(
        val recovery: RecoveryResult,
        /** The two signals with the largest weighted impact, positive or negative. */
        val topSignals: List<String>,
        val healthAge: HealthAgeResult,
        val pace: HealthAgePace,
        val dayScore: Int?,
        val wentWell: Int,
        val toWatch: Int
    ) : SummaryInsights

    companion object {
        /** Hidden when Insights is off or Health sync is disabled. */
        fun from(snapshot: InsightsSnapshot?, enabled: Boolean): SummaryInsights? {
            val s = snapshot ?: return null
            if (!enabled || !s.healthEnabled) return null
            val r = s.recovery
            if (r.status == "collecting") {
                val c = r.collecting ?: return null
                return Learning(c.have, c.need)
            }
            val review = s.reviews.lastOrNull()
            return Cards(
                recovery = r,
                topSignals = (r.positives + r.negatives).sortedByDescending { abs(it.impact) }.take(2).map { it.text },
                healthAge = s.healthAge,
                pace = s.pace,
                dayScore = review?.dayScore,
                wentWell = review?.wentWell?.size ?: 0,
                toWatch = review?.needsAttention?.size ?: 0
            )
        }
    }
}

@Composable
internal fun InsightsSummarySection(insights: SummaryInsights, onRecovery: () -> Unit, onHealthAge: () -> Unit, onReview: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (insights) {
            is SummaryInsights.Learning -> InsightTile(
                icon = Icons.Outlined.Insights,
                title = stringResource(R.string.insights_card_learning),
                detail = stringResource(R.string.insights_card_learning_sub, insights.have, insights.need),
                tag = "summary.card.insightsLearning",
                onClick = onRecovery
            )
            is SummaryInsights.Cards -> {
                RecoveryCard(insights, onRecovery)
                HealthAgeCard(insights.healthAge, insights.pace, onHealthAge)
                InsightTile(
                    icon = Icons.Outlined.Checklist,
                    title = stringResource(R.string.insights_daily_review),
                    trailing = stringResource(R.string.health_home_today),
                    value = insights.dayScore?.toString() ?: "",
                    detail = if (insights.dayScore == null) stringResource(R.string.insights_day_score_none)
                    else stringResource(R.string.insights_card_review_sub, insights.wentWell, insights.toWatch),
                    chart = insights.dayScore?.let { SummaryTileChart.Ring(it / 100f) } ?: SummaryTileChart.None,
                    tag = "summary.card.review",
                    onClick = onReview
                )
            }
        }
    }
}

@Composable
private fun RecoveryCard(c: SummaryInsights.Cards, onClick: () -> Unit) {
    val r = c.recovery
    if (!r.ok) {
        InsightTile(
            icon = Icons.Outlined.Insights,
            title = stringResource(R.string.insights_recovery),
            trailing = stringResource(R.string.health_home_today),
            detail = stringResource(if (r.status == "no_sleep") R.string.insights_no_sleep else R.string.insights_no_heart),
            tag = "summary.card.recovery",
            onClick = onClick
        )
        return
    }
    val context = LocalContext.current
    val top = (r.positives + r.negatives).maxByOrNull { abs(it.impact) }
    InsightTile(
        icon = Icons.Outlined.Insights,
        title = stringResource(R.string.insights_recovery),
        trailing = stringResource(R.string.health_home_today),
        value = InsightsText.bandLabel(context, r.label, r.labelText),
        detail = top?.let { InsightsText.signal(context, InsightsConfig.active, r, it) },
        chart = SummaryTileChart.Ring((r.score ?: 0) / 100f, r.score?.toString()),
        tag = "summary.card.recovery",
        onClick = onClick
    )
}

@Composable
private fun HealthAgeCard(h: HealthAgeResult, pace: HealthAgePace, onClick: () -> Unit) {
    val title = stringResource(R.string.insights_health_age)
    when (h.status) {
        "ok" -> InsightTile(
            icon = Icons.Outlined.Update,
            title = title,
            value = InsightsFormat.number(h.healthAge, 1),
            detail = (paceText(pace)?.let { "$it · " } ?: "") + differenceText(h.difference),
            tag = "summary.card.healthAge",
            onClick = onClick
        )
        "collecting" -> InsightTile(
            icon = Icons.Outlined.Update,
            title = title,
            detail = stringResource(R.string.insights_collecting_days, h.collecting?.have ?: 0, h.collecting?.need ?: 30),
            tag = "summary.card.healthAge",
            onClick = onClick
        )
        "no_birthday" -> InsightTile(
            icon = Icons.Outlined.Update,
            title = title,
            detail = stringResource(R.string.insights_no_birthday_body),
            tag = "summary.card.healthAge",
            onClick = onClick
        )
        else -> Unit
    }
}

@Composable
private fun InsightTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    tag: String,
    trailing: String? = null,
    value: String = "",
    detail: String? = null,
    chart: SummaryTileChart = SummaryTileChart.None,
    onClick: () -> Unit
) {
    SummaryTile(
        title = title,
        icon = icon,
        tint = InsightsFormat.Insights,
        trailing = trailing,
        value = value,
        detail = detail,
        chart = chart,
        modifier = Modifier.testTag(tag),
        onClick = onClick
    )
}
