package com.ayuvo.health.coach

import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.records.coach.RecordsCoach
import com.ayuvo.health.records.processing.RecordText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarkdownBlocksVectorsTest { @Test fun allCases() = CoachVectors.assertAll("markdown_blocks.json") }
class ChartSpecVectorsTest { @Test fun allCases() = CoachVectors.assertAll("chart_spec.json") }
class ChartRepairVectorsTest { @Test fun allCases() = CoachVectors.assertAll("chart_repair.json") }
class AttachmentExcerptVectorsTest { @Test fun allCases() = CoachVectors.assertAll("attachment_excerpt.json") }
class ConversationTitleVectorsTest { @Test fun allCases() = CoachVectors.assertAll("conversation_title.json") }
class DataSourcesVectorsTest { @Test fun allCases() = CoachVectors.assertAll("data_sources.json") }
class PromptGalleryVectorsTest { @Test fun allCases() = CoachVectors.assertAll("prompt_gallery.json") }
class PromptChipsVectorsTest { @Test fun allCases() = CoachVectors.assertAll("prompt_chips.json") }
class ChatArchiveVectorsTest { @Test fun allCases() = CoachVectors.assertAll("chat_archive.json") }
class ExportVectorsTest { @Test fun allCases() = CoachVectors.assertAll("export.json") }

val COACH_VECTOR_FILES_WITH_RUNNERS = listOf(
    "markdown_blocks.json", "chart_spec.json", "chart_repair.json", "attachment_excerpt.json",
    "conversation_title.json",
    "data_sources.json", "prompt_gallery.json", "prompt_chips.json", "chat_archive.json",
    "export.json"
)

class CoachVectorCoverageTest {
    @Test
    fun everyVectorFileIsRun() {
        val dir = CoachTestFiles.shared("coach/test-vectors")
        assertNotNull("shared/coach/test-vectors missing", dir)
        val files = dir!!.listFiles { f: File -> f.name.endsWith(".json") }.orEmpty().map { it.name }.sorted()
        assertEquals(COACH_VECTOR_FILES_WITH_RUNNERS.sorted(), files)
    }
}

/** The bundled catalogs must be byte-identical to the shared ones. */
class CoachCatalogParityTest {
    private fun asset(relative: String): File =
        listOf("src/main/assets/$relative", "app/src/main/assets/$relative")
            .map(::File).first { it.exists() }

    @Test
    fun chartSpecIsBundledVerbatim() {
        assertEquals(CoachTestFiles.shared("coach/chart_spec.json")!!.readText(),
                     asset("coach/chart_spec.json").readText())
    }

    @Test
    fun promptGalleryIsBundledVerbatim() {
        assertEquals(CoachTestFiles.shared("coach/prompt_gallery.json")!!.readText(),
                     asset("coach/prompt_gallery.json").readText())
    }

    @Test
    fun medicationCoachToolsAreBundledVerbatim() {
        assertEquals(CoachTestFiles.shared("medications/coach_tools.json")!!.readText(),
                     asset("medications/coach_tools.json").readText())
    }

    /** The chart prompt the model is given must still carry the rule that keeps charts honest. */
    @Test
    fun chartPromptKeepsTheNoInventedValuesRule() {
        val prompt = CoachTestFiles.chartSpec["prompt"] as JsonObject
        val section = prompt.str("charts_section") ?: ""
        assertTrue(section.contains(CoachReference.CHART_FENCE))
        assertTrue(section.contains("Never estimate"))
        for (kind in CoachReference.CHART_TYPES) {
            assertTrue("the chart prompt no longer lists $kind", section.contains(kind))
        }
    }
}

class CoachReferenceBehaviourTest {
    private fun ok(result: JsonObject) = (result["ok"] as? JsonPrimitive)?.booleanOrNull == true

    /** A spec that does not parse must never reach the renderer as a chart. */
    @Test
    fun malformedChartsFallBackToACodeBlock() {
        for (raw in listOf(
            // NaN is the only reading here, so nothing is left to draw (§5).
            """{"type":"bar","series":[{"points":[["a",NaN]]}]}""",
            """{'type':'bar','series':[{'points':[['a',1]]}]}""",
            """{"type":"sankey","series":[{"points":[["a",1]]}]}""",
            "not json at all"
        )) {
            val block = CoachReference.chartBlock(raw)
            assertFalse(raw, ok(block))
            assertEquals(raw, block.str("text"))
            assertNull(block["spec"])
        }
    }

    /** The repairs of §5 rescue a badly punctuated spec without moving a single number. */
    @Test
    fun punctuationIsRepairedButValuesAreNot() {
        val raw = "Here you go:\n" +
            """{"type":"Column Chart", // sleep""" + "\n" +
            """"labels":["Mon","Tue","Wed"],"series":[{"label":"Asleep","values":["6.2",NaN,5.4],}],}""" +
            "\nHope that helps."
        val block = CoachReference.chartBlock(raw)
        assertTrue(raw, ok(block))
        val spec = block["spec"] as JsonObject
        assertEquals("bar", spec.str("type"))
        val points = ((spec["series"] as JsonArray)[0] as JsonObject)["points"] as JsonArray
        assertEquals(2, points.size)                     // Tuesday had no reading and is not drawn
        assertEquals("Mon", (points[0] as JsonObject).str("x"))
        assertEquals("6.2", (points[0] as JsonObject)["y"].toString())
        assertEquals("Wed", (points[1] as JsonObject).str("x"))
    }

    /** The strict scanner, not org.json: these are all accepted by org.json and must be refused. */
    @Test
    fun strictJsonRefusesPlatformExtensions() {
        for (raw in listOf("""{"a":1,}""", "[1,2,]", """{'a':1}""", "{a:1}", """{"a":NaN}""",
                           """{"a":01}""", """{"a":1} trailing""")) {
            assertNull(raw, CoachReference.strictJson(raw))
        }
        assertNotNull(CoachReference.strictJson("""{"a":[1,2],"b":"x","c":true,"d":null,"e":1.5e2}"""))
    }

    /** An attachment excerpt never carries a line the records redaction rule would have dropped. */
    @Test
    fun attachmentExcerptRedactsIdentityLines() {
        val got = CoachReference.attachmentExcerpt(
            listOf("Patient Name: Ravi Kumar\nUHID 998877\nHemoglobin 11.2 g/dL")
        )
        val text = got.str("text")!!
        assertFalse(text.contains("Ravi"))
        assertFalse(text.contains("998877"))
        assertTrue(text.contains("Hemoglobin"))
        for (line in text.split("\n")) {
            assertFalse("a redacted line survived: $line",
                        RecordsCoach.piiLine(RecordText.fold(line), emptyList()))
        }
    }

    /** Rule 3: a switch narrows, it never grants consent. */
    @Test
    fun aSwitchCannotGrantConsent() {
        val got = CoachVectors.runCase(
            "resolve_data_sources",
            MedicationJson.obj(
                "available" to MedicationJson.obj("health" to true),
                "consents" to MedicationJson.obj("health" to false),
                "switches" to MedicationJson.obj("health" to true)
            )
        ) as JsonObject
        val sources = got["sources"] as JsonObject
        assertEquals(false, (sources["health"] as JsonPrimitive).booleanOrNull)
        assertTrue((got["tools"] as kotlinx.serialization.json.JsonArray).isEmpty())
    }
}

private typealias MedicationJson = com.ayuvo.health.medications.logic.MedicationJson
