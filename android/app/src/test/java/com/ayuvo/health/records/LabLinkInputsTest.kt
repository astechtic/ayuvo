package com.ayuvo.health.records

import com.ayuvo.health.data.intake.LabLinkInputs
import com.ayuvo.health.data.intake.LabLinks
import com.ayuvo.health.data.intake.LabLinksInput
import com.ayuvo.health.intake.IntakeTestFiles
import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Test

/** Latest lab values feeding `lab_nutrient_links` (docs/intake-metrics.md §3). */
class LabLinkInputsTest {
    private fun obs(id: String, analyte: String, value: Double, low: Double?, date: String, state: FieldState = FieldState.CONFIRMED) =
        Observation(
            id = id, recordId = "r", analyteId = analyte, rawName = analyte, valueNum = value, valueText = value.toString(),
            refLow = low, refHigh = null, observedDate = date, method = ExtractionMethod.values().first(), state = state
        )

    @Test
    fun latestUsableValuePerAnalyteDrivesTheLink() {
        val labs = LabLinkInputs.latestLabs(
            listOf(
                obs("1", "hemoglobin", 11.0, 13.0, "2026-01-10"),
                obs("2", "hemoglobin", 14.0, 13.0, "2026-09-01"),
                obs("3", "ferritin", 8.0, 15.0, "2026-09-01"),
                obs("4", "ferritin", 50.0, 15.0, "2026-09-20", state = FieldState.REJECTED)
            )
        )
        assertEquals(listOf("ferritin" to 8.0, "hemoglobin" to 14.0), labs.map { it.analyte to it.value })
        val cfg = IntakeTestFiles.config
        val links = LabLinks.labNutrientLinks(LabLinksInput(labs, mapOf("iron_mg" to 6.0), mapOf("iron_mg" to 8.0), emptyList()), cfg).links
        assertEquals(listOf("ferritin"), links.single().analytesLow)
        assertEquals(75.0, links.single().intakePct!!, 0.0)
        assertEquals(false, links.single().supplementProvides)
        assertEquals(listOf("hemoglobin", "mcv", "ferritin", "iron", "vitamin_d_25oh", "vitamin_b12", "folate"), LabLinkInputs.analytes(cfg))
    }
}
