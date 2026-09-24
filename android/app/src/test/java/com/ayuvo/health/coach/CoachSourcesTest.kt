package com.ayuvo.health.coach

import com.ayuvo.health.coach.model.AttachmentKind
import com.ayuvo.health.coach.model.ChatAttachment
import com.ayuvo.health.coach.model.CoachDataSwitches
import com.ayuvo.health.coach.model.CoachSource
import com.ayuvo.health.coach.processing.CoachAttachmentComposer
import com.ayuvo.health.medications.coach.CoachMedicationsContext
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.logic.MedicationsCoachContract
import com.ayuvo.health.medications.logic.MedicationsCoachTools
import com.ayuvo.health.services.ai.CoachTools
import com.ayuvo.health.services.ai.medicationsDataLines
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The data switches and the medication tools (docs/coach.md §3, §6, §8; docs/medications.md §20).
 * The rule these all protect: a switch narrows what consent already permits, and never grants it.
 */
class CoachSourcesTest {

    @Before
    fun loadContract() {
        MedicationsCoachTools.contract = MedicationsCoachContract.parse(
            CoachTestFiles.shared("medications/coach_tools.json")!!.readText()
        )!!
    }

    private fun medicationsContext(count: Int = 1): CoachMedicationsContext {
        val rows = (0 until count).map { index ->
            MedicationJson.obj(
                "id" to "m$index", "name" to "Metformin", "strength" to "500 mg", "form" to "tablet",
                "dose_quantity" to 1, "dose_unit" to "tablet", "food_relation" to "with",
                "status" to "active", "is_prn" to 0, "start_date" to "2026-09-01"
            )
        }
        return CoachMedicationsContext(
            snapshot = MedicationJson.obj(
                "medications" to rows, "schedules" to emptyList<JsonObject>(),
                "dose_logs" to emptyList<JsonObject>()
            ),
            timeZone = "Asia/Kolkata",
            nowMs = 1_789_900_000_000L,
            count = count,
            activeCount = count
        )
    }

    private fun tools(
        medications: CoachMedicationsContext? = null,
        switches: CoachDataSwitches = CoachDataSwitches.ALL_ON
    ) = CoachTools(
        weights = emptyList(), bodyFats = emptyList(), foods = emptyList(),
        medications = medications, sources = switches
    )

    // -- §8 gating -------------------------------------------------------------------------------

    @Test
    fun nutritionAndWorkoutToolsAreAlwaysThere() {
        val names = tools().advertisedToolNames
        assertTrue(CoachTools.NUTRITION_TOOL_NAMES.all { it in names })
        assertTrue(CoachTools.WORKOUT_TOOL_NAMES.all { it in names })
        assertFalse(CoachTools.MEDICATION_TOOL_NAMES.any { it in names })
    }

    @Test
    fun medicationToolsNeedBothTheDataAndTheConsent() {
        assertFalse(tools().advertisedToolNames.any { it in CoachTools.MEDICATION_TOOL_NAMES })
        val names = tools(medicationsContext()).advertisedToolNames
        assertTrue(CoachTools.MEDICATION_TOOL_NAMES.all { it in names })
    }

    /** Rule 3: the switch only narrows. */
    @Test
    fun aSwitchRemovesToolsButNeverAddsThem() {
        val off = CoachDataSwitches.ALL_ON.with(CoachSource.MEDICATIONS, false)
        assertFalse(tools(medicationsContext(), off).advertisedToolNames
            .any { it in CoachTools.MEDICATION_TOOL_NAMES })

        // Health has no snapshot and no consent here; switching it "on" changes nothing.
        val on = CoachDataSwitches.ALL_ON.with(CoachSource.HEALTH, true)
        assertFalse(tools(switches = on).advertisedToolNames.any { it in CoachTools.HEALTH_TOOL_NAMES })
    }

    @Test
    fun turningFoodOffDropsTheWorkoutToolsToo() {
        val off = CoachDataSwitches.ALL_ON.with(CoachSource.FOOD, false)
        val names = tools(switches = off).advertisedToolNames
        assertFalse(CoachTools.NUTRITION_TOOL_NAMES.any { it in names })
        assertFalse(CoachTools.WORKOUT_TOOL_NAMES.any { it in names })
    }

    @Test
    fun effectiveSourcesMatchTheAdvertisedTools() {
        assertTrue(CoachSource.MEDICATIONS in tools(medicationsContext()).effectiveSources)
        val off = CoachDataSwitches.ALL_ON.with(CoachSource.MEDICATIONS, false)
        assertFalse(CoachSource.MEDICATIONS in tools(medicationsContext(), off).effectiveSources)
    }

    // -- §20 medication tools --------------------------------------------------------------------

    @Test
    fun medicationToolNamesComeFromTheSharedContract() {
        assertEquals(
            listOf("get_medications", "get_dose_history", "get_medication_adherence"),
            CoachTools.MEDICATION_TOOL_NAMES
        )
        val tools = tools(medicationsContext())
        for (name in CoachTools.MEDICATION_TOOL_NAMES) {
            assertTrue("$name has no description", tools.descriptionFor(name).isNotEmpty())
            assertNotNull("$name has no schema", tools.rawSchemaFor(name))
        }
    }

    @Test
    fun aMedicationToolReturnsItsPayload() {
        val raw = tools(medicationsContext()).executeMedications("get_medications", emptyMap())
        assertNotNull(raw)
        val payload = MedicationJson.json.parseToJsonElement(raw!!) as JsonObject
        assertEquals("1", payload["count"].toString())
        assertTrue(raw.contains("Metformin"))
    }

    /** The user turned the source off while a call was in flight. */
    @Test
    fun aMedicationToolFailsClosedWithoutContext() {
        val raw = tools().executeMedications("get_medications", emptyMap())!!
        val payload = MedicationJson.json.parseToJsonElement(raw) as JsonObject
        assertEquals("medication data is not available", payload.str("error"))
    }

    @Test
    fun badArgumentsComeBackAsContractErrors() {
        val tools = tools(medicationsContext())
        val badDate = tools.executeMedications(
            "get_dose_history", mapOf("from" to "14-09-2026", "to" to "2026-09-20"))!!
        assertTrue(badDate.contains("invalid date"))
        val reversed = tools.executeMedications(
            "get_medication_adherence", mapOf("from" to "2026-09-20", "to" to "2026-09-14"))!!
        assertTrue(reversed.contains("from must be on or before to"))
    }

    /** The whole reason Coach may read this data at all. */
    @Test
    fun theGuardrailsForbidDosingAdvice() {
        val guardrails = MedicationsCoachTools.contract.prompt["guardrails"].orEmpty()
        assertTrue(guardrails.contains("Never tell the user to start, stop, change"))
        val lines = medicationsDataLines(medicationsContext(), "hi")
        assertTrue(lines.any { it.contains("Never tell the user to start, stop, change") })
    }

    /** The "no medication data" line is only worth the tokens when the user actually asked. */
    @Test
    fun theNotAvailableLineOnlyAppearsWhenAsked() {
        assertTrue(medicationsDataLines(null, "how did I sleep?").isEmpty())
        val asked = medicationsDataLines(null, "am I taking my pills on time?")
        assertEquals(1, asked.size)
        assertTrue(asked[0].contains("No medication data is available"))
    }

    @Test
    fun mentionWordsComeFromTheSharedContract() {
        assertTrue(MedicationsCoachTools.contract.mentionsWords.isNotEmpty())
        assertTrue(CoachMedicationsContext.mentionsMedicines("Which MEDICINES am I on?"))
        assertTrue(CoachMedicationsContext.mentionsMedicines("my dosage changed"))
        assertFalse(CoachMedicationsContext.mentionsMedicines("how many steps yesterday"))
    }

    /** Providers without tool calling get names and schedules — never a dose recommendation. */
    @Test
    fun theOnDeviceBlockListsMedicinesWithoutAdvice() {
        val block = medicationsContext().onDeviceBlock()!!
        assertTrue(block.startsWith("## Medications"))
        assertTrue(block.contains("Metformin 500 mg"))
        assertFalse(block.lowercase().contains("should take"))
    }

    // -- §6 attachments in the outgoing message ----------------------------------------------------

    @Test
    fun documentExcerptsAreAppendedToTheMessage() {
        val attachment = ChatAttachment(
            kind = AttachmentKind.PDF, filename = "report.pdf",
            excerpt = "Hemoglobin 11.2 g/dL", createdMs = 1
        )
        val text = CoachAttachmentComposer.messageWithAttachments("What does this say?", listOf(attachment))
        assertTrue(text.startsWith("What does this say?"))
        assertTrue(text.contains("--- Attached file: report.pdf ---"))
        assertTrue(text.contains("Hemoglobin 11.2 g/dL"))
    }

    @Test
    fun imagesAreNotInlinedAsText() {
        val image = ChatAttachment(kind = AttachmentKind.IMAGE, filename = "photo.jpg", createdMs = 1)
        assertEquals("look", CoachAttachmentComposer.messageWithAttachments("look", listOf(image)))
    }

    @Test
    fun aDocumentWithNoReadableTextSaysSoRatherThanVanishing() {
        val empty = ChatAttachment(kind = AttachmentKind.PDF, filename = "scan.pdf", excerpt = null, createdMs = 1)
        val text = CoachAttachmentComposer.messageWithAttachments("read this", listOf(empty))
        assertTrue(text.contains("scan.pdf"))
        assertTrue(text.contains("(no readable text)"))
    }

    /** Three long documents must not blow the context window. */
    @Test
    fun theTurnBudgetTruncatesRatherThanOverflowing() {
        val long = "a".repeat(30_000)
        val attachments = (0 until 3).map {
            ChatAttachment(kind = AttachmentKind.PDF, filename = "doc$it.pdf", excerpt = long, createdMs = 1)
        }
        val text = CoachAttachmentComposer.messageWithAttachments("summarise", attachments)
        assertTrue(text.length <= com.ayuvo.health.coach.logic.CoachReference.MAX_TURN_CHARS + 500)
        assertTrue(text.contains("(truncated)"))
    }
}
