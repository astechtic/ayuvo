package com.ayuvo.health.data.intake

import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.nutrients.NutrientProfile
import com.ayuvo.health.nutrients.NutrientTotals
import com.ayuvo.health.nutrients.SupplementSnapshot
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.Observation
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds the `lab_nutrient_links` input (docs/intake-metrics.md §3) from the app stores: the latest value of each
 * linked analyte with the report's printed range, the average daily intake over the last [WINDOW_DAYS] days (food plus
 * supplements, over logged days), the DRI goals for the profile and the nutrients any active supplement provides.
 */
object LabLinkInputs {
    const val WINDOW_DAYS = 30

    /** Every analyte a lab-link rule reads. */
    fun analytes(cfg: IntakeConfig): List<String> = cfg.labLinks.flatMap { it.analytes }.distinct()

    /** Latest usable value per analyte: rejected, trend-excluded and non-numeric rows are skipped. */
    fun latestLabs(observations: List<Observation>): List<LabValue> =
        observations.asSequence()
            .filter { it.analyteId != null && it.valueNum != null && it.state != FieldState.REJECTED && !it.excludedFromTrends }
            .groupBy { it.analyteId!! }
            .map { (id, list) ->
                val o = list.maxWithOrNull(compareBy<Observation>({ it.observedDate.orEmpty() }, { it.updatedMs }))!!
                LabValue(id, o.valueNum!!, o.refLow, o.refHigh)
            }
            .sortedBy { it.analyte }

    /**
     * Mean daily amount per DRI nutrient key over the logged days (any food or dose) of the window ending [today];
     * a nutrient with no value on a logged day counts 0 there. Empty when nothing was logged.
     */
    fun intakeAverage(
        food: List<FoodEntry>,
        supplements: SupplementSnapshot,
        today: LocalDate,
        zone: ZoneId,
        cfg: IntakeConfig
    ): Map<String, Double?> {
        val from = today.minusDays((WINDOW_DAYS - 1).toLong())
        val logged = NutrientTotals.loggedDays(food, supplements, zone)
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .filter { !it.isBefore(from) && !it.isAfter(today) }
            .sorted()
        if (logged.isEmpty()) return emptyMap()
        val totals = NutrientTotals(food, supplements, zone)
        val out = LinkedHashMap<String, Double?>()
        for (driKey in cfg.labLinks.map { it.nutrient }.distinct()) {
            val key = IntakeDefaults.referenceKey(driKey)
            var sum = 0.0
            for (d in logged) sum += totals.total(key, d).total ?: 0.0
            out[driKey] = sum / logged.size
        }
        return out
    }

    /** DRI keys of the nutrients an active supplement lists. */
    fun supplementNutrients(supplements: SupplementSnapshot, cfg: IntakeConfig): List<String> =
        supplements.activeNutrientKeys.mapNotNull { IntakeDefaults.driKey(it, cfg) }.distinct().sorted()

    fun build(
        observations: List<Observation>,
        food: List<FoodEntry>,
        supplements: SupplementSnapshot,
        profile: NutrientProfile,
        today: LocalDate,
        zone: ZoneId,
        cfg: IntakeConfig
    ): LabLinksInput = LabLinksInput(
        labs = latestLabs(observations),
        intakeAvg = intakeAverage(food, supplements, today, zone, cfg),
        goals = IntakeDefaults.goals(profile, cfg).goals,
        supplementNutrients = supplementNutrients(supplements, cfg)
    )
}
