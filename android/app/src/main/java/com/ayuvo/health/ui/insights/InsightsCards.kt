package com.ayuvo.health.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.insights.HealthAgePace
import com.ayuvo.health.insights.HealthAgeResult
import com.ayuvo.health.insights.InsightsSnapshot
import com.ayuvo.health.insights.RecoveryResult
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.CategoryIcon
import com.ayuvo.health.ui.design.SurfaceCard
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
            is SummaryInsights.Learning -> InsightCard(
                icon = Icons.Outlined.Insights,
                title = stringResource(R.string.insights_card_learning),
                subtitle = stringResource(R.string.insights_card_learning_sub, insights.have, insights.need),
                trailing = null,
                tag = "summary.card.insightsLearning",
                onClick = onRecovery
            )
            is SummaryInsights.Cards -> {
                RecoveryCard(insights, onRecovery)
                HealthAgeCard(insights.healthAge, insights.pace, onHealthAge)
                InsightCard(
                    icon = Icons.Outlined.Checklist,
                    title = stringResource(R.string.insights_daily_review),
                    subtitle = if (insights.dayScore == null) stringResource(R.string.insights_day_score_none)
                    else stringResource(R.string.insights_card_review_sub, insights.wentWell, insights.toWatch),
                    trailing = insights.dayScore?.toString() ?: InsightsFormat.MISSING,
                    trailingColor = InsightsFormat.scoreColor(insights.dayScore),
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
        InsightCard(
            icon = Icons.Outlined.Insights,
            title = stringResource(R.string.insights_recovery),
            subtitle = stringResource(if (r.status == "no_sleep") R.string.insights_no_sleep else R.string.insights_no_heart),
            trailing = InsightsFormat.MISSING,
            tag = "summary.card.recovery",
            onClick = onClick
        )
        return
    }
    val color = InsightsFormat.recoveryColor(r.label)
    SurfaceCard(padding = PaddingValues(14.dp), onClick = onClick, modifier = Modifier.testTag("summary.card.recovery")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(r.score, color, "", size = 56.dp, stroke = 6.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.insights_recovery), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
                Text(r.labelText.orEmpty(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = color)
                c.topSignals.forEach { Text(it, fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AyuvoColors.tertiaryLabel())
        }
    }
}

@Composable
private fun HealthAgeCard(h: HealthAgeResult, pace: HealthAgePace, onClick: () -> Unit) {
    when (h.status) {
        "ok" -> InsightCard(
            icon = Icons.Outlined.Update,
            title = stringResource(R.string.insights_health_age),
            subtitle = (paceText(pace)?.let { "$it · " } ?: "") + differenceText(h.difference),
            trailing = InsightsFormat.number(h.healthAge, 1),
            tag = "summary.card.healthAge",
            onClick = onClick
        )
        "collecting" -> InsightCard(
            icon = Icons.Outlined.Update,
            title = stringResource(R.string.insights_health_age),
            subtitle = stringResource(R.string.insights_collecting_days, h.collecting?.have ?: 0, h.collecting?.need ?: 30),
            trailing = null,
            tag = "summary.card.healthAge",
            onClick = onClick
        )
        "no_birthday" -> InsightCard(
            icon = Icons.Outlined.Update,
            title = stringResource(R.string.insights_health_age),
            subtitle = stringResource(R.string.insights_no_birthday_body),
            trailing = null,
            tag = "summary.card.healthAge",
            onClick = onClick
        )
        else -> Unit
    }
}

@Composable
private fun InsightCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    trailing: String?,
    tag: String,
    trailingColor: androidx.compose.ui.graphics.Color = InsightsFormat.Insights,
    onClick: () -> Unit
) {
    SurfaceCard(padding = PaddingValues(14.dp), onClick = onClick, modifier = Modifier.testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(icon, InsightsFormat.Insights, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Text(trailing, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (trailing == InsightsFormat.MISSING) AyuvoPalette.Other else trailingColor)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AyuvoColors.tertiaryLabel())
        }
    }
}
