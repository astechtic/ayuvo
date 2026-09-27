package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.export.MedicationsArchive
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.FoodRelation
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationDraft
import com.ayuvo.health.medications.model.MedicationForm
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.MedicationsSnapshot
import com.ayuvo.health.medications.model.NutrientInputRow
import com.ayuvo.health.nutrients.MedicationNutrientRow
import com.ayuvo.health.nutrients.NutrientsTestFiles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Supplement nutrients through the archive file and the form draft (docs/medications.md §14, §15, §21). */
class MedicationNutrientsArchiveTest {
    @Before
    fun install() = NutrientsTestFiles.install()

    private val med = Medication(
        id = "d3", name = "Vitamin D3 60,000 IU", form = MedicationForm.CAPSULE, doseQuantity = 1.0, doseUnit = DoseUnit.CAPSULE,
        foodRelation = FoodRelation.WITH, startDate = "2026-09-01", status = MedicationStatus.ACTIVE, createdMs = 1_000L, updatedMs = 2_000L
    )

    @Test
    fun archiveRoundTripKeepsNutrients() {
        val snapshot = MedicationsSnapshot(medications = listOf(med), nutrients = listOf(MedicationNutrientRow("d3", "vitamin_d", 1500.0)))
        val bytes = MedicationsArchive.write(snapshot, 3_000L, "UTC", "1.0")
        val archive = MedicationsArchive.read(bytes)
        val exported = ((archive["medications"] as JsonArray)[0] as JsonObject)["nutrients"] as JsonArray
        assertEquals(1, exported.size)
        val merge = ArchiveCodec.merge(MedicationsSnapshot(), archive, 4_000L)
        assertTrue(merge.ok)
        val row = merge.ops.single().row!!
        assertEquals(listOf("vitamin_d" to 1500.0), ArchiveCodec.nutrientRows(row))
    }

    @Test
    fun formDraftConvertsIuAndValidates() {
        val draft = MedicationDraft(
            name = "Vitamin D3", startDate = "2026-09-01",
            nutrients = listOf(NutrientInputRow(key = "vitamin_d", amount = "60,000".replace(",", ""), unit = "iu"))
        )
        assertEquals(listOf(MedicationNutrientRow("d3", "vitamin_d", 1500.0)), draft.nutrientRows("d3"))
        assertTrue(DraftValidation.validate(draft.toValidationJson()).none { it.field == "nutrients" })
        val bad = draft.copy(nutrients = draft.nutrients + NutrientInputRow(key = "vitamin_e", amount = "400", unit = "iu"))
        assertEquals(listOf("nutrient_amount_invalid"), DraftValidation.validate(bad.toValidationJson()).filter { it.field == "nutrients" }.map { it.code })
        val natural = draft.copy(nutrients = listOf(NutrientInputRow(key = "vitamin_e", amount = "400", unit = "iu", form = "natural")))
        assertEquals(268.0, natural.nutrientRows("x").single().amountPerUnit, 1e-9)
    }
}
