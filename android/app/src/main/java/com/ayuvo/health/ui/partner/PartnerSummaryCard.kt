package com.ayuvo.health.ui.partner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.data.PartnerStore
import com.ayuvo.health.partner.data.PartnerSyncState
import com.ayuvo.health.partner.logic.PartnerSummary
import com.ayuvo.health.partner.logic.SummaryMetric
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.CategoryIcon
import com.ayuvo.health.ui.design.SurfaceCard
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

/** One partner row of the Summary card (docs/partner-sync.md §15). */
data class PartnerSummaryRow(val partner: Partner, val sync: PartnerSyncState?, val metrics: List<SummaryMetric>)

object PartnerSummaryLoader {
    /**
     * Up to [max] partners by most recent sync, each with summary_metrics for today / yesterday. Reads the store only
     * (call off the main thread); a partner is listed even while offline (docs §14).
     */
    fun load(store: PartnerStore, today: LocalDate, max: Int = 3): List<PartnerSummaryRow> {
        val t = today.toString()
        val y = today.minusDays(1).toString()
        return store.partners(includeUnpaired = true)
            .map { it to store.syncState(it.ownerId) }
            .sortedWith(compareByDescending<Pair<Partner, PartnerSyncState?>> { it.second?.lastSyncMs ?: Long.MIN_VALUE }.thenBy { it.first.displayName })
            .take(max)
            .map { (p, s) ->
                val rows = store.records(p.ownerId, listOf("analytics_day", "sleep_night", "metric_day", "dose_log"), listOf(t, y), limit = 2000)
                val granted = store.grantsReceived(p.ownerId).filter { it.granted }.map { it.category }
                PartnerSummaryRow(p, s, PartnerSummary.metrics(rows, t, y, granted))
            }
    }
}

private fun whole(v: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(v)

private fun metricColor(key: String): Color = when (key) {
    "recovery" -> AyuvoPalette.Insights
    "sleep" -> AyuvoPalette.Sleep
    "resting_hr" -> AyuvoPalette.Heart
    "activity", "steps" -> AyuvoPalette.Activity
    "medicines" -> AyuvoPalette.Medications
    else -> AyuvoPalette.Other
}

/** "82", "7 h 24 min", "58 bpm", "35 min", "8,421 steps", "1 of 2". */
@Composable
internal fun summaryMetricValue(m: SummaryMetric): String = when (m.key) {
    "recovery" -> whole(m.value)
    "sleep" -> stringResource(R.string.partner_hours_minutes, whole(m.value / 60), whole(m.value % 60))
    "resting_hr" -> stringResource(R.string.partner_value_bpm, whole(m.value))
    "activity" -> stringResource(R.string.partner_value_minutes, whole(m.value))
    "steps" -> stringResource(R.string.partner_value_steps, whole(m.value))
    "medicines" -> stringResource(R.string.partner_value_of, whole(m.value), whole(m.unit?.removePrefix("of:")?.toLongOrNull() ?: 0L))
    else -> whole(m.value)
}

@Composable
internal fun summaryMetricLabel(m: SummaryMetric): String = stringResource(
    when (m.key) {
        "recovery" -> R.string.partner_metric_recovery
        "sleep" -> R.string.partner_metric_sleep
        "resting_hr" -> R.string.partner_metric_resting_hr
        "activity" -> R.string.partner_metric_activity
        "steps" -> R.string.partner_metric_steps
        else -> R.string.partner_metric_medicines
    }
)

/**
 * Summary › Today "Partners" card: up to three partners with freshness and up to three shared metrics. Metrics from
 * a category the partner no longer shares are dimmed. Hidden (not composed) when there are no partners.
 */
@Composable
fun PartnerSummaryCard(rows: List<PartnerSummaryRow>, onOpen: (String) -> Unit) {
    if (rows.isEmpty()) return
    SurfaceCard(padding = PaddingValues(0.dp), modifier = Modifier.testTag("summary.card.partner")) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(Icons.Filled.People, AyuvoPalette.Partner, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.partner_summary_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            PartnerBadge(stringResource(R.string.partner_read_only))
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 14.dp), thickness = 0.5.dp, color = AyuvoColors.separator())
            PartnerSummaryRowView(row) { onOpen(row.partner.ownerId) }
        }
    }
}

@Composable
private fun PartnerSummaryRowView(row: PartnerSummaryRow, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("summary.card.partner.row")
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PartnerAvatar(row.partner.displayName, 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.partner.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(partnerFreshnessLine(row.partner, row.sync), fontSize = 12.sp, color = AyuvoColors.secondaryLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.metrics.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(AyuvoSpacing.ItemGap), modifier = Modifier.padding(top = 4.dp)) {
                    row.metrics.forEach { m -> SummaryMetricChip(m) }
                }
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AyuvoColors.tertiaryLabel(), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SummaryMetricChip(m: SummaryMetric) {
    val alpha = if (m.shared) 1f else 0.4f
    Column {
        Text(
            summaryMetricLabel(m), fontSize = 11.sp, color = metricColor(m.key).copy(alpha = alpha),
            fontWeight = FontWeight.SemiBold, maxLines = 1
        )
        Text(
            summaryMetricValue(m), fontSize = 15.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), maxLines = 1
        )
    }
}

/** Initial in a pink circle: partner identity, never the user's own avatar colour. */
@Composable
fun PartnerAvatar(name: String, sizeDp: Int) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(AyuvoPalette.Partner),
        contentAlignment = Alignment.Center
    ) {
        Text(initial, color = Color.White, fontSize = (sizeDp * 0.45f).sp, fontWeight = FontWeight.SemiBold)
    }
}
