package com.ayuvo.health.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.insights.DailyReviewResult
import com.ayuvo.health.insights.InsightsConfig
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.theme.AppColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Daily Health Review for one of the last 7 days: Day Score, area scores, the four item sections,
 * "not logged" notes and "Explain with AI". [initialDay] comes from the notification or the action.
 */
@Composable
fun DailyReviewScreen(vm: InsightsViewModel, onBack: () -> Unit, initialDay: LocalDate? = null) {
    val ui by vm.ui.collectAsState()
    var info by rememberSaveable { mutableStateOf(false) }
    var dayText by rememberSaveable { mutableStateOf(initialDay?.toString()) }
    val cfg = vm.config
    RefreshOnResume(vm::refresh)
    val snap = ui.snapshot
    val day = dayText?.let(LocalDate::parse)?.takeIf { d -> snap == null || snap.reviews.any { it.day == d } } ?: snap?.today
    InsightsScaffold(
        title = stringResource(R.string.insights_daily_review),
        tag = "insights.review",
        onBack = onBack,
        onInfo = { info = true },
        disclaimers = listOfNotNull(cfg.disclaimers["general"])
    ) {
        if (!insightsGate(ui, "insights.review", needsHealth = false)) return@InsightsScaffold
        val s = snap ?: return@InsightsScaffold
        val review = day?.let { s.review(it) } ?: return@InsightsScaffold
        item(key = "days") { DayPicker(s.reviews.map { it.day }, review.day) { dayText = it.toString() } }
        item(key = "score") { DayScoreCard(review) }
        item(key = "areas") { AreasGroup(review, cfg) }
        for ((id, title) in listOf(
            "went_well" to R.string.insights_went_well,
            "needs_attention" to R.string.insights_needs_attention,
            "improve" to R.string.insights_improve,
            "reduce" to R.string.insights_reduce
        )) {
            val items = review.category(id)
            if (items.isEmpty()) continue
            item(key = "cat-$id") {
                InsetGroup(header = stringResource(title), dividerInset = 16.dp, modifier = Modifier.testTag("insights.review.$id")) {
                    items.forEach { i -> row { Text(i.text, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) } }
                }
            }
        }
        if (review.notLogged.isNotEmpty()) {
            item(key = "not-logged") {
                InsetGroup(header = stringResource(R.string.insights_not_logged), dividerInset = 16.dp, modifier = Modifier.testTag("insights.review.notLogged")) {
                    review.notLogged.forEach { i ->
                        row { Text(i.text, fontSize = 15.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) }
                    }
                }
            }
        }
        if (review.dayScore != null) {
            item(key = "explain") {
                ExplainSection(
                    state = ui.explanations[vm.explanationKey("daily_review", review.day)] ?: ExplainUi.Idle,
                    availability = ui.ai,
                    disclaimer = cfg.disclaimers["ai"],
                    tag = "insights.review",
                    onExplain = { vm.explain("daily_review", review.day) }
                )
            }
        }
    }
    if (info) InsightMethodologySheet(cfg, InsightMethodology.dailyReview(cfg, snap, day), onDismiss = { info = false })
}

@Composable
private fun DayPicker(days: List<LocalDate>, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val weekday = remember { DateTimeFormatter.ofPattern("EEE", Locale.getDefault()) }
    Row(Modifier.fillMaxWidth().testTag("insights.review.days"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { d ->
            val on = d == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) AppColors.Calorie else MaterialTheme.colorScheme.surface)
                    .clickable(role = Role.Tab) { onSelect(d) }
                    .padding(vertical = 8.dp)
                    .testTag("insights.review.day.$d"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val label = d.format(weekday)
                Text(label, fontSize = 11.sp, color = if (on) MaterialTheme.colorScheme.onPrimary else AyuvoColors.secondaryLabel(), maxLines = 1)
                Text(d.dayOfMonth.toString(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun DayScoreCard(review: DailyReviewResult) {
    SurfaceCard(modifier = Modifier.testTag("insights.review.score")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(review.dayScore, InsightsFormat.scoreColor(review.dayScore), "/ 100", size = 96.dp, stroke = 10.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.insights_day_score), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val fmt = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()) }
                Text(review.day.format(fmt), fontSize = 14.sp, color = AyuvoColors.secondaryLabel())
                if (review.dayScore == null) {
                    Text(stringResource(R.string.insights_day_score_none), fontSize = 14.sp, color = AyuvoColors.secondaryLabel(), textAlign = TextAlign.Start)
                }
            }
        }
    }
}

@Composable
private fun AreasGroup(review: DailyReviewResult, cfg: InsightsConfig) {
    val included = review.areas.filter { it.included }
    if (included.isEmpty()) return
    InsetGroup(header = stringResource(R.string.insights_areas), dividerInset = 16.dp, modifier = Modifier.testTag("insights.review.areas")) {
        included.forEach { a ->
            row { KeyValueRow(cfg.dailyReview.areas.firstOrNull { it.id == a.id }?.label ?: a.id, "${a.score} / 100") }
        }
    }
}
