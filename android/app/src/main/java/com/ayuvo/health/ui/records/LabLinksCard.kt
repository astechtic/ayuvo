package com.ayuvo.health.ui.records

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.intake.IntakeDefaults
import com.ayuvo.health.data.intake.LabLink
import com.ayuvo.health.data.intake.LabLinkInputs
import com.ayuvo.health.data.intake.LabLinks
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.ui.design.SurfaceCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Health Records overview card linking low lab values to nutrition (docs/intake-metrics.md §3, `lab_nutrient_links`).
 * Fires only when a linked analyte is below the range printed on its report; shows the 30-day average intake as % of
 * the reference and whether any active supplement contains the nutrient. It never names a condition and only suggests
 * talking to a doctor. Hidden when nothing is linked.
 */
@Composable
internal fun LabLinksCard(container: AppContainer) {
    val context = LocalContext.current
    val revisionFlow = remember(container) { if (container.recordsDatabaseExists()) container.recordsStore.revision else MutableStateFlow(0L) }
    val revision by revisionFlow.collectAsState()
    val links by produceState(initialValue = emptyList<LabLink>(), revision) {
        value = runCatching { loadLinks(container) }.getOrDefault(emptyList())
    }
    if (links.isEmpty()) return
    SurfaceCard(Modifier.fillMaxWidth().testTag("records.lab_links"), padding = PaddingValues(0.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                stringResource(R.string.intake_lab_links_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 2.dp)
            )
            links.forEachIndexed { i, link ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp).testTag("records.lab_links.${link.id}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(labLinkText(context, container, link), fontSize = 14.sp)
                }
            }
            Text(
                stringResource(R.string.intake_lab_links_footer),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

private suspend fun loadLinks(container: AppContainer): List<LabLink> = withContext(Dispatchers.Default) {
    if (!container.recordsDatabaseExists()) return@withContext emptyList()
    val cfg = container.intakeConfig
    val observations = container.recordsStore.observationsOfAnalytes(LabLinkInputs.analytes(cfg))
    if (observations.isEmpty()) return@withContext emptyList()
    val zone = ZoneId.systemDefault()
    val input = LabLinkInputs.build(
        observations = observations,
        food = container.foodRepository.entries.first(),
        supplements = container.supplementIntake.current(),
        profile = NutrientFields.profile(container.prefs.userProfile.first()),
        today = LocalDate.now(zone),
        zone = zone,
        cfg = cfg
    )
    LabLinks.labNutrientLinks(input, cfg).links
}

/**
 * "Your {analyte} was below the report's range. Your average {nutrient} intake is {pct}% of the reference{, and none of
 * your supplements contain it}. Consider discussing this with your doctor."
 */
internal fun labLinkText(context: Context, container: AppContainer, link: LabLink): String {
    val analytes = link.analytesLow.map { container.analyteCatalog.displayName(it) ?: it }
    val low = if (analytes.size == 1) context.getString(R.string.intake_lab_link_low_one, analytes.single())
    else context.getString(R.string.intake_lab_link_low_many, joinNames(context, analytes))
    val nutrient = NutrientFields.displayName(context, IntakeDefaults.referenceKey(link.nutrient))
    val pct = link.intakePct?.roundToInt()
    val intake = when {
        pct == null -> null
        link.supplementProvides -> context.getString(R.string.intake_lab_link_intake, nutrient, pct)
        else -> context.getString(R.string.intake_lab_link_intake_no_supplement, nutrient, pct)
    }
    return listOfNotNull(low, intake, context.getString(R.string.intake_lab_link_doctor)).joinToString(" ")
}

private fun joinNames(context: Context, names: List<String>): String =
    if (names.size <= 1) names.joinToString()
    else context.getString(R.string.intake_lab_link_and, names.dropLast(1).joinToString(", "), names.last())
