package com.ayuvo.health.ui.partner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.EmptyState
import com.ayuvo.health.ui.design.SectionHeader
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ReportLoad(val name: String, val report: PartnerReport?)

/**
 * A partner's report overview (`partner/{ownerId}/report/{recordId}`, docs §7.6): summary, highlights, abnormal values
 * first with reference ranges and flags, all results, diagnoses, medications and recommendations. The original
 * document never left the partner's phone, and the screen says so.
 */
@Composable
fun PartnerReportScreen(container: AppContainer, ownerId: String, recordId: String, onBack: () -> Unit) {
    var load by remember { mutableStateOf<ReportLoad?>(null) }
    LaunchedEffect(ownerId, recordId) {
        load = withContext(Dispatchers.IO) {
            if (!container.partnerDatabaseExists()) return@withContext ReportLoad("", null)
            val store = container.partnerStore
            val name = store.partner(ownerId)?.displayName.orEmpty()
            val row = store.records(ownerId, listOf("report_overview"), null, limit = 300).firstOrNull { it.recordId == recordId }
            ReportLoad(name, row?.let(PartnerDashboardBuilder::report))
        }
    }
    val l = load
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AyuvoTopBar(title = l?.report?.title ?: stringResource(R.string.partner_cat_reports), onBack = onBack) }
    ) { padding ->
        val r = l?.report
        if (r == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (l == null) CircularProgressIndicator()
                else EmptyState(Icons.Outlined.Description, stringResource(R.string.partner_report_missing), stringResource(R.string.partner_report_missing_body))
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("partner.report.detail"),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 4.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.ItemGap)
        ) {
            item(key = "head") {
                SurfaceCard(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        PartnerBadge(l.name.ifBlank { stringResource(R.string.partner_title) })
                    }
                    r.reportDate?.let { Text(partnerDayLabel(it), fontSize = 14.sp, color = AyuvoColors.secondaryLabel()) }
                    val doctor = listOfNotNull(r.doctor, r.specialty).joinToString(" · ")
                    if (doctor.isNotBlank()) Text(doctor, fontSize = 14.sp)
                    r.facility?.let { Text(it, fontSize = 14.sp, color = AyuvoColors.secondaryLabel()) }
                }
            }
            item(key = "note") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = AyuvoPalette.Partner, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.partner_report_original_note, l.name.ifBlank { stringResource(R.string.partner_title) }), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
                }
            }
            r.summary?.let { s ->
                item(key = "summary-h") { SectionHeader(stringResource(R.string.partner_report_summary)) }
                item(key = "summary") { SurfaceCard { Text(s, fontSize = 15.sp) } }
            }
            if (r.highlights.isNotEmpty()) {
                item(key = "hl-h") { SectionHeader(stringResource(R.string.partner_report_highlights)) }
                item(key = "hl") { BulletCard(r.highlights) }
            }
            if (r.abnormal.isNotEmpty()) {
                item(key = "ab-h") { SectionHeader(stringResource(R.string.partner_report_abnormal)) }
                item(key = "ab") { ResultsCard(r.abnormal, "partner.report.abnormal") }
            }
            val others = r.results.filterNot { res -> r.abnormal.any { it.name == res.name && it.value == res.value } }
            if (others.isNotEmpty()) {
                item(key = "res-h") { SectionHeader(stringResource(if (r.abnormal.isEmpty()) R.string.partner_report_results else R.string.partner_report_other_results)) }
                item(key = "res") { ResultsCard(others, "partner.report.results") }
            }
            if (r.diagnoses.isNotEmpty()) {
                item(key = "dx-h") { SectionHeader(stringResource(R.string.partner_report_diagnoses)) }
                item(key = "dx") { BulletCard(r.diagnoses) }
            }
            if (r.medications.isNotEmpty()) {
                item(key = "med-h") { SectionHeader(stringResource(R.string.partner_report_medications)) }
                item(key = "med") {
                    SurfaceCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.medications.forEach { m ->
                            Column {
                                Text(listOfNotNull(m.name, m.strength).joinToString(" "), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                val sub = listOfNotNull(m.dose, m.frequency, m.duration).joinToString(" · ")
                                if (sub.isNotBlank()) Text(sub, fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
                            }
                        }
                    }
                }
            }
            if (r.recommendations.isNotEmpty()) {
                item(key = "rec-h") { SectionHeader(stringResource(R.string.partner_report_recommendations)) }
                item(key = "rec") { BulletCard(r.recommendations) }
            }
            item(key = "disclaimer") {
                Text(stringResource(R.string.partner_report_disclaimer), fontSize = 12.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun BulletCard(lines: List<String>) {
    SurfaceCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { line ->
            Row {
                Text(stringResource(R.string.partner_bullet), fontSize = 15.sp, color = AyuvoPalette.Partner)
                Spacer(Modifier.width(8.dp))
                Text(line, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun flagLabel(flag: String?): String? = when (flag) {
    "low" -> stringResource(R.string.partner_flag_low)
    "high" -> stringResource(R.string.partner_flag_high)
    "critical_low", "critical_high", "critical" -> stringResource(R.string.partner_flag_critical)
    "abnormal" -> stringResource(R.string.partner_flag_abnormal)
    "normal" -> stringResource(R.string.partner_flag_normal)
    else -> null
}

@Composable
private fun ResultsCard(results: List<PartnerResult>, tag: String) {
    SurfaceCard(modifier = Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        results.forEach { res ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(res.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    val range = res.refText ?: when {
                        res.refLow != null && res.refHigh != null -> stringResource(R.string.partner_range, fmt(res.refLow, 2), fmt(res.refHigh, 2))
                        res.refLow != null -> stringResource(R.string.partner_range_min, fmt(res.refLow, 2))
                        res.refHigh != null -> stringResource(R.string.partner_range_max, fmt(res.refHigh, 2))
                        else -> null
                    }
                    if (range != null) Text(stringResource(R.string.partner_reference, listOfNotNull(range, res.unit).joinToString(" ")), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(listOfNotNull(res.value, res.unit).joinToString(" "), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = if (res.abnormal) AyuvoPalette.Destructive else MaterialTheme.colorScheme.onSurface)
                    flagLabel(res.flag)?.let {
                        Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (res.abnormal) AyuvoPalette.Destructive else AyuvoColors.secondaryLabel())
                    }
                }
            }
        }
    }
}
