package com.ayuvo.health.ui.partner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.ui.components.UnitToggle
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.AyuvoSpacing
import com.ayuvo.health.ui.design.AyuvoTopBar
import com.ayuvo.health.ui.design.CategoryIcon
import com.ayuvo.health.ui.design.EmptyState
import com.ayuvo.health.ui.design.SectionHeader
import com.ayuvo.health.ui.design.Sparkline
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.medications.MedicationFormat
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun fmt(v: Double, digits: Int = 0): String =
    NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = digits; minimumFractionDigits = 0 }.format(v)

/** "Today", "Yesterday" or a medium date for a `yyyy-MM-dd` day. */
@Composable
internal fun partnerDayLabel(day: String?): String {
    if (day == null) return ""
    val d = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    val today = LocalDate.now()
    return when (d) {
        today -> stringResource(R.string.partner_today)
        today.minusDays(1) -> stringResource(R.string.partner_yesterday)
        else -> d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

@Composable
internal fun hoursMinutes(minutes: Double): String {
    val total = Math.round(minutes)
    return stringResource(R.string.partner_hours_minutes, fmt((total / 60).toDouble()), fmt((total % 60).toDouble()))
}

/**
 * Partner dashboard (`partner/{ownerId}`, docs/partner-sync.md §15): read-only data the partner shares with me,
 * clearly labelled as theirs. A section is hidden when its category was never shared, labelled "No longer shared"
 * when revoked, and shows an empty state when shared without data. Nothing is estimated.
 */
@Composable
fun PartnerDashboardScreen(
    container: AppContainer,
    ownerId: String,
    onBack: () -> Unit,
    onOpenReport: (String) -> Unit,
    onManage: () -> Unit
) {
    val vm: PartnerDashboardViewModel = viewModel(key = "partner-$ownerId", factory = PartnerDashboardViewModel.Factory(container, ownerId))
    val ui by vm.ui.collectAsState()
    LaunchedEffect(ownerId) { if (container.partnerDatabaseExists()) container.partnerManager.reloadAsync() }
    val d = ui.dashboard
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AyuvoTopBar(title = d?.partner?.displayName ?: stringResource(R.string.partner_title), onBack = onBack, actions = {
                TextButton(onClick = onManage, modifier = Modifier.testTag("partner.dashboard.manage")) {
                    Text(stringResource(R.string.partner_manage), color = com.ayuvo.health.ui.theme.AppColors.Calorie)
                }
            })
        }
    ) { padding ->
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (ui.loaded) EmptyState(Icons.Outlined.PersonOff, stringResource(R.string.partner_not_found), stringResource(R.string.partner_not_found_body))
                else CircularProgressIndicator()
            }
            return@Scaffold
        }
        var days by rememberSaveable { mutableStateOf(7) }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("partner.dashboard"),
            contentPadding = PaddingValues(start = AyuvoSpacing.ScreenH, end = AyuvoSpacing.ScreenH, top = 4.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(AyuvoSpacing.ItemGap)
        ) {
            item(key = "header") { DashboardHeader(d, ui.windowRunning, vm::syncNow) }

            val todayCats = listOf(PartnerCategory.VITALS, PartnerCategory.SLEEP, PartnerCategory.NUTRITION, PartnerCategory.WORKOUTS, PartnerCategory.MEDICINES)
            if (todayCats.any { d.share(it) != ReceivedShare.NEVER }) {
                item(key = "today-h") { SectionHeader(stringResource(R.string.partner_section_today)) }
                if (d.share(PartnerCategory.VITALS) != ReceivedShare.NEVER) item(key = "recovery") { RecoverySection(d) }
                if (d.share(PartnerCategory.SLEEP) != ReceivedShare.NEVER) item(key = "sleep") { SleepSection(d) }
                if (d.share(PartnerCategory.VITALS) != ReceivedShare.NEVER) item(key = "vitals") { VitalsSection(d, ui.weightMetric) }
                if (d.share(PartnerCategory.NUTRITION) != ReceivedShare.NEVER) item(key = "nutrition") { NutritionSection(d) }
                if (d.share(PartnerCategory.WORKOUTS) != ReceivedShare.NEVER) item(key = "workouts") { WorkoutSection(d) }
                if (d.share(PartnerCategory.MEDICINES) != ReceivedShare.NEVER) item(key = "medicines") { MedicinesSection(d) }
            }

            val trends = d.trends.filter { d.share(trendCategory(it.trend)) != ReceivedShare.NEVER }
            if (trends.isNotEmpty()) {
                item(key = "trends-h") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader(stringResource(R.string.partner_section_trends), modifier = Modifier.weight(1f))
                        UnitToggle(
                            leftLabel = stringResource(R.string.partner_days_7), rightLabel = stringResource(R.string.partner_days_30),
                            isLeft = days == 7, onSelect = { days = if (it) 7 else 30 }, modifier = Modifier.width(210.dp).testTag("partner.trends.range")
                        )
                    }
                }
                item(key = "trends") { TrendsSection(d, trends, days, ui.weightMetric) }
            }

            if (d.share(PartnerCategory.REPORTS) != ReceivedShare.NEVER) {
                item(key = "reports-h") { SectionHeader(stringResource(R.string.partner_section_reports)) }
                item(key = "reports") { ReportsSection(d, onOpenReport) }
            }

            item(key = "status-h") { SectionHeader(stringResource(R.string.partner_section_sync)) }
            item(key = "status") { SyncStatusSection(d) }
        }
    }
}

private fun trendCategory(t: PartnerTrend): PartnerCategory = if (t == PartnerTrend.SLEEP) PartnerCategory.SLEEP else PartnerCategory.VITALS

@Composable
private fun DashboardHeader(d: PartnerDashboard, running: Boolean, onSync: () -> Unit) {
    SurfaceCard(modifier = Modifier.testTag("partner.dashboard.header")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PartnerAvatar(d.partner.displayName, 48)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.partner.displayName, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    PartnerBadge(stringResource(R.string.partner_read_only))
                }
                Text(PartnerFormat.shortFingerprint(d.partner.fingerprint), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
                Text(partnerFreshnessLine(d.partner, d.sync), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.partner_dashboard_note, d.partner.displayName), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
        if (d.partner.trusted) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSync, enabled = !running, modifier = Modifier.testTag("partner.dashboard.syncNow")) {
                if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Sync, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (running) R.string.partner_syncing else R.string.partner_sync_now))
            }
        }
    }
}

/** A dashboard card for one category: title row (with "No longer shared" when revoked), then content or empty text. */
@Composable
private fun CategoryCard(
    d: PartnerDashboard,
    category: PartnerCategory,
    title: String,
    empty: Boolean,
    tag: String,
    emptyText: String = stringResource(R.string.partner_section_empty),
    content: @Composable ColumnScope.() -> Unit
) {
    SurfaceCard(modifier = Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(category.icon, category.color, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (d.share(category) == ReceivedShare.REVOKED) NoLongerSharedBadge()
        }
        if (empty) Text(emptyText, fontSize = 14.sp, color = AyuvoColors.secondaryLabel()) else content()
    }
}

@Composable
private fun ValueLine(value: String, unit: String?, caption: String?, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
        if (!unit.isNullOrBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(unit, fontSize = 14.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.weight(1f))
        if (!caption.isNullOrBlank()) Text(caption, fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun RecoverySection(d: PartnerDashboard) {
    val r = d.recovery
    CategoryCard(d, PartnerCategory.VITALS, stringResource(R.string.partner_metric_recovery), r == null, "partner.dashboard.recovery") {
        ValueLine(fmt(r!!.value), stringResource(R.string.partner_out_of_100), partnerDayLabel(r.day), AyuvoPalette.Insights)
    }
}

@Composable
private fun SleepSection(d: PartnerDashboard) {
    val s = d.sleep
    CategoryCard(d, PartnerCategory.SLEEP, stringResource(R.string.partner_cat_sleep), s == null, "partner.dashboard.sleep") {
        ValueLine(hoursMinutes(s!!.asleepMin), stringResource(R.string.partner_asleep), partnerDayLabel(s.day), AyuvoPalette.Sleep)
        s.inBedMin?.let { Text(stringResource(R.string.partner_in_bed, hoursMinutes(it)), fontSize = 13.sp, color = AyuvoColors.secondaryLabel()) }
        if (s.hasStages) {
            val stages = listOf(
                Triple(R.string.partner_stage_deep, s.deepMin, Color(0xFF3634A3)),
                Triple(R.string.partner_stage_rem, s.remMin, Color(0xFF5AC8FA)),
                Triple(R.string.partner_stage_light, s.lightMin, AyuvoPalette.Sleep),
                Triple(R.string.partner_stage_awake, s.awakeMin, AyuvoPalette.Warning)
            ).filter { (it.second ?: 0.0) > 0 }
            val total = stages.sumOf { it.second ?: 0.0 }.coerceAtLeast(1.0)
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                stages.forEach { (_, min, color) -> Box(Modifier.weight(((min ?: 0.0) / total).toFloat().coerceAtLeast(0.01f)).height(10.dp).background(color)) }
            }
            stages.forEach { (label, min, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(label), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(hoursMinutes(min ?: 0.0), fontSize = 14.sp, color = AyuvoColors.secondaryLabel())
                }
            }
        }
    }
}

@Composable
private fun vitalTitle(v: PartnerVital): String = stringResource(
    when (v) {
        PartnerVital.RESTING_HR -> R.string.partner_metric_resting_hr
        PartnerVital.HEART_RATE -> R.string.partner_vital_heart_rate
        PartnerVital.HRV -> R.string.partner_vital_hrv
        PartnerVital.SPO2 -> R.string.partner_vital_spo2
        PartnerVital.BLOOD_PRESSURE -> R.string.partner_vital_bp
        PartnerVital.WEIGHT -> R.string.partner_vital_weight
    }
)

@Composable
private fun vitalValue(r: PartnerVitalReading, weightMetric: Boolean): Pair<String, String> = when (r.vital) {
    PartnerVital.RESTING_HR, PartnerVital.HEART_RATE -> fmt(r.value) to stringResource(R.string.partner_unit_bpm)
    PartnerVital.HRV -> fmt(r.value) to stringResource(R.string.partner_unit_ms)
    PartnerVital.SPO2 -> fmt(r.value) to stringResource(R.string.partner_unit_percent)
    PartnerVital.BLOOD_PRESSURE -> (if (r.value2 != null) stringResource(R.string.partner_bp_value, fmt(r.value), fmt(r.value2)) else fmt(r.value)) to stringResource(R.string.partner_unit_mmhg)
    PartnerVital.WEIGHT -> if (weightMetric) fmt(r.value, 1) to stringResource(R.string.unit_kg) else fmt(r.value * 2.20462, 1) to stringResource(R.string.unit_lbs)
}

@Composable
private fun readingWhen(r: PartnerVitalReading): String {
    val context = LocalContext.current
    val day = partnerDayLabel(r.day)
    return if (r.tsMs != null) stringResource(R.string.partner_day_time, day, MedicationFormat.time(context, r.tsMs)) else day
}

@Composable
private fun VitalsSection(d: PartnerDashboard, weightMetric: Boolean) {
    CategoryCard(d, PartnerCategory.VITALS, stringResource(R.string.partner_section_vitals), d.vitals.isEmpty(), "partner.dashboard.vitals") {
        d.vitals.forEach { r ->
            val (value, unit) = vitalValue(r, weightMetric)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(vitalTitle(r.vital), fontSize = 15.sp)
                    Text(readingWhen(r), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
                }
                Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(3.dp))
                Text(unit, fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
            }
        }
    }
}

@Composable
private fun NutritionSection(d: PartnerDashboard) {
    val empty = d.foods.isEmpty() && d.waterMl == null
    CategoryCard(d, PartnerCategory.NUTRITION, stringResource(R.string.partner_section_nutrition), empty, "partner.dashboard.nutrition",
        emptyText = stringResource(R.string.partner_nothing_today)) {
        d.macros?.let { m ->
            ValueLine(fmt(m.calories), stringResource(R.string.summary_unit_kcal), null, AyuvoPalette.Nutrition)
            Text(
                stringResource(R.string.partner_macros, fmt(m.proteinG), fmt(m.carbsG), fmt(m.fatG)),
                fontSize = 13.sp, color = AyuvoColors.secondaryLabel()
            )
        }
        d.waterMl?.let { Text(stringResource(R.string.partner_water_ml, fmt(it)), fontSize = 14.sp, color = AyuvoPalette.Hydration) }
        d.foods.forEach { f ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.kcal_value_format, Math.round(f.calories).toInt()), fontSize = 14.sp, color = AyuvoColors.secondaryLabel())
            }
        }
    }
}

@Composable
private fun WorkoutSection(d: PartnerDashboard) {
    val shared = d.share(PartnerCategory.WORKOUTS) == ReceivedShare.SHARED
    val context = LocalContext.current
    CategoryCard(
        d, PartnerCategory.WORKOUTS, stringResource(R.string.partner_cat_workouts), d.workouts.isEmpty(), "partner.dashboard.workouts",
        // "Rest day" only while workouts are shared: a revoked category says nothing about today.
        emptyText = stringResource(if (shared) R.string.partner_rest_day else R.string.partner_nothing_today)
    ) {
        d.workouts.forEach { w ->
            val parts = buildList {
                if (w.durationS > 0) add(stringResource(R.string.partner_value_minutes, fmt(w.durationS / 60.0)))
                w.kcal?.let { add(stringResource(R.string.kcal_value_format, Math.round(it).toInt())) }
                w.distanceM?.takeIf { it > 0 }?.let { add(stringResource(R.string.partner_km, fmt(it / 1000.0, 2))) }
                w.startMs?.let { add(MedicationFormat.time(context, it)) }
            }
            Column {
                Text(w.title.ifBlank { stringResource(R.string.partner_workout) }, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
            }
        }
    }
}

@Composable
private fun doseStatus(status: String): Pair<String, Color> = when (status) {
    "taken" -> stringResource(R.string.partner_dose_taken) to AyuvoPalette.Success
    "skipped" -> stringResource(R.string.partner_dose_skipped) to AyuvoPalette.Warning
    "missed" -> stringResource(R.string.partner_dose_missed) to AyuvoPalette.Destructive
    "snoozed" -> stringResource(R.string.partner_dose_snoozed) to AyuvoColors.secondaryLabel()
    else -> stringResource(R.string.partner_dose_scheduled) to AyuvoColors.secondaryLabel()
}

@Composable
private fun MedicinesSection(d: PartnerDashboard) {
    val context = LocalContext.current
    CategoryCard(d, PartnerCategory.MEDICINES, stringResource(R.string.partner_cat_medicines), d.doses.isEmpty(), "partner.dashboard.medicines",
        emptyText = stringResource(R.string.partner_no_doses_today)) {
        val taken = d.doses.count { it.status == "taken" }
        Text(stringResource(R.string.partner_doses_taken, taken, d.doses.size), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AyuvoPalette.Medications)
        d.doses.forEach { dose ->
            val (label, color) = doseStatus(dose.status)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(dose.medicationName ?: stringResource(R.string.partner_medicine), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val time = (dose.takenMs ?: dose.scheduledMs)?.let { MedicationFormat.time(context, it) }
                    val sub = listOfNotNull(time, dose.note).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = AyuvoColors.secondaryLabel(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
            }
        }
    }
}

@Composable
private fun trendTitle(t: PartnerTrend): String = stringResource(
    when (t) {
        PartnerTrend.HEART_RATE -> R.string.partner_vital_heart_rate
        PartnerTrend.RESTING_HR -> R.string.partner_metric_resting_hr
        PartnerTrend.WEIGHT -> R.string.partner_vital_weight
        PartnerTrend.SLEEP -> R.string.partner_cat_sleep
        PartnerTrend.STEPS -> R.string.partner_metric_steps
        PartnerTrend.ACTIVITY -> R.string.partner_metric_activity
    }
)

private fun trendColor(t: PartnerTrend): Color = when (t) {
    PartnerTrend.HEART_RATE, PartnerTrend.RESTING_HR -> AyuvoPalette.Heart
    PartnerTrend.WEIGHT -> AyuvoPalette.Body
    PartnerTrend.SLEEP -> AyuvoPalette.Sleep
    PartnerTrend.STEPS, PartnerTrend.ACTIVITY -> AyuvoPalette.Activity
}

@Composable
private fun trendValue(t: PartnerTrend, v: Double, weightMetric: Boolean): String = when (t) {
    PartnerTrend.HEART_RATE, PartnerTrend.RESTING_HR -> stringResource(R.string.partner_value_bpm, fmt(v))
    PartnerTrend.WEIGHT -> if (weightMetric) "${fmt(v, 1)} ${stringResource(R.string.unit_kg)}" else "${fmt(v * 2.20462, 1)} ${stringResource(R.string.unit_lbs)}"
    PartnerTrend.SLEEP -> hoursMinutes(v * 60.0)
    PartnerTrend.STEPS -> stringResource(R.string.partner_value_steps, fmt(v))
    PartnerTrend.ACTIVITY -> stringResource(R.string.partner_value_minutes, fmt(v))
}

@Composable
private fun TrendsSection(d: PartnerDashboard, trends: List<PartnerTrendSeries>, days: Int, weightMetric: Boolean) {
    val withData = trends.filter { it.hasData(days) }
    val revoked = trends.map { trendCategory(it.trend) }.distinct().filter { d.share(it) == ReceivedShare.REVOKED }
    SurfaceCard(modifier = Modifier.testTag("partner.dashboard.trends"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (revoked.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(revoked.map { stringResource(it.titleRes) }.joinToString(", "), fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.weight(1f))
                NoLongerSharedBadge()
            }
        }
        if (withData.isEmpty()) {
            Text(stringResource(R.string.partner_trends_empty), fontSize = 14.sp, color = AyuvoColors.secondaryLabel())
        }
        withData.forEach { s ->
            val color = trendColor(s.trend)
            val dim = d.share(trendCategory(s.trend)) == ReceivedShare.REVOKED
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(trendTitle(s.trend), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color.copy(alpha = if (dim) 0.5f else 1f))
                    s.latest(days)?.let { Text(trendValue(s.trend, it, weightMetric), fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                    s.average(days)?.takeIf { s.window(days).count { v -> v != null } >= 2 }?.let { Text(stringResource(R.string.partner_average, trendValue(s.trend, it, weightMetric)), fontSize = 12.sp, color = AyuvoColors.secondaryLabel()) }
                }
                Sparkline(
                    values = s.window(days).map { it?.toFloat() ?: Float.NaN },
                    color = color.copy(alpha = if (dim) 0.5f else 1f),
                    modifier = Modifier.size(120.dp, 36.dp)
                )
            }
        }
    }
}

@Composable
private fun ReportsSection(d: PartnerDashboard, onOpen: (String) -> Unit) {
    CategoryCard(d, PartnerCategory.REPORTS, stringResource(R.string.partner_cat_reports), d.reports.isEmpty(), "partner.dashboard.reports") {
        Text(stringResource(R.string.partner_report_overview_note, d.partner.displayName), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
        d.reports.forEach { r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { onOpen(r.id) }
                    .testTag("partner.report.${r.id}")
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(r.reportDate?.let { partnerDayLabel(it) }, r.doctor, r.facility).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = AyuvoColors.secondaryLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (r.abnormal.isNotEmpty()) {
                    PartnerBadge(pluralStringResource(R.plurals.partner_abnormal_count, r.abnormal.size, r.abnormal.size), color = AyuvoPalette.Destructive)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AyuvoColors.tertiaryLabel())
            }
        }
    }
}

@Composable
private fun SyncStatusSection(d: PartnerDashboard) {
    SurfaceCard(modifier = Modifier.testTag("partner.dashboard.status"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(Icons.Filled.Insights, AyuvoPalette.Partner, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(partnerFreshness(d.sync), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(stringResource(PartnerFormat.statusLong(d.partner, d.sync)), fontSize = 14.sp)
        d.sync?.lastSyncMs?.let { ms ->
            val via = when (d.sync.lastTransport) {
                "package" -> stringResource(R.string.partner_via_file)
                "network", "lan" -> stringResource(R.string.partner_via_lan)
                else -> null
            }
            val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))
            Text(listOfNotNull(stringResource(R.string.partner_last_synced, date), via).joinToString(" · "), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
        }
        Text(stringResource(R.string.partner_limits), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
        if (!d.partner.trusted) Text(stringResource(R.string.partner_unpaired_kept), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
    }
}
