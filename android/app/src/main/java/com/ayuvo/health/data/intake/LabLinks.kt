package com.ayuvo.health.data.intake

import com.ayuvo.health.data.intake.IntakeMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject

/** Latest lab value for one analyte with the report's printed reference range. */
data class LabValue(val analyte: String, val value: Double, val refLow: Double?, val refHigh: Double?)

data class LabLinksInput(
    val labs: List<LabValue>,
    /** Average daily intake per nutrient key, supplements included. */
    val intakeAvg: Map<String, Double?>,
    val goals: Map<String, Double?>,
    val supplementNutrients: List<String>
)

data class LabLink(val id: String, val analytesLow: List<String>, val nutrient: String, val intakePct: Double?, val supplementProvides: Boolean) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "id" to id, "analytes_low" to analytesLow, "nutrient" to nutrient, "intake_pct" to intakePct, "supplement_provides" to supplementProvides
    )
}

data class LabLinksResult(val links: List<LabLink>) {
    fun toJson(): JsonObject = MedicationJson.obj("links" to links.map { it.toJson() })
}

/** Port of `lab_nutrient_links`: associational only, never names a condition. */
object LabLinks {

    fun labNutrientLinks(inp: LabLinksInput, cfg: IntakeConfig): LabLinksResult {
        val labs = LinkedHashMap<String, LabValue>()
        for (l in inp.labs) labs[l.analyte] = l
        val out = ArrayList<LabLink>()
        for (r in cfg.labLinks) {
            val low = r.analytes.filter { a -> labs[a]?.let { it.refLow != null && it.value < it.refLow } == true }
            if (low.isEmpty()) continue
            val n = r.nutrient
            val g = inp.goals[n]
            val intake = inp.intakeAvg[n]
            out += LabLink(
                id = r.id, analytesLow = low, nutrient = n,
                intakePct = if (IntakeMath.truthy(g) && intake != null) roundTo(intake * 100.0 / g!!, 0) else null,
                supplementProvides = n in inp.supplementNutrients
            )
        }
        return LabLinksResult(out)
    }
}
