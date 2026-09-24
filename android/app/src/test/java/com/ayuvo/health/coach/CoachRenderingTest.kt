package com.ayuvo.health.coach

import com.ayuvo.health.coach.logic.CoachCatalogs
import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.services.ai.chartPromptLines
import com.ayuvo.health.ui.coach.seriesOf
import com.ayuvo.health.ui.coach.thinnedTo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Markdown and chart rendering (docs/coach.md §4, §5). The parsing itself is covered by the shared
 * vectors; these guard the layer between the parser and the views — that every block kind reaches
 * it, that a bad chart can never reach the chart view, and that the model is told how to draw one.
 */
class CoachRenderingTest {

    @Before
    fun loadCatalog() {
        CoachCatalogs.chartSpec = CoachCatalogs.parse(
            CoachTestFiles.shared("coach/chart_spec.json")!!.readText()
        )!!
    }

    private fun blocks(markdown: String): List<JsonObject> =
        CoachReference.parseBlocks(markdown).arr("blocks").orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull == true

    // -- §4 blocks reaching the view ---------------------------------------------------------------

    @Test
    fun everyBlockKindReachesTheRenderer() {
        val markdown = """
            # Title
            Some text.

            ## Second
            - bullet
              - nested
            1. first
            - [x] done

            > quoted

            ---

            | Day | Hours |
            |:--|--:|
            | Mon | 6.2 |

            ```swift
            let a = 1
            ```
        """.trimIndent()
        val kinds = blocks(markdown).mapNotNull { it.str("kind") }
        for (expected in listOf("heading", "paragraph", "bullet", "numbered", "task", "quote",
                                "rule", "table", "code")) {
            assertTrue("no $expected block reached the renderer", expected in kinds)
        }
    }

    @Test
    fun nestedListsKeepTheirDepth() {
        val depths = blocks("- one\n  - two\n    - three")
            .filter { it.str("kind") == "bullet" }
            .map { (it["depth"] as JsonPrimitive).content.toInt() }
        assertEquals(listOf(0, 1, 2), depths)
    }

    @Test
    fun taskItemsCarryTheirCheckedState() {
        val tasks = blocks("- [x] done\n- [ ] todo").filter { it.str("kind") == "task" }
        assertEquals(listOf(true, false), tasks.map { it.flag("checked") })
        assertEquals(listOf("done", "todo"), tasks.map { it.str("text") })
    }

    @Test
    fun aTableKeepsItsAlignmentsAndPadsShortRows() {
        val table = blocks("| a | b |\n|:--|--:|\n| 1 |").first { it.str("kind") == "table" }
        assertEquals(listOf("a", "b"), com.ayuvo.health.medications.logic.MedicationJson.strings(table["headers"]))
        assertEquals(listOf("left", "right"), com.ayuvo.health.medications.logic.MedicationJson.strings(table["aligns"]))
        val rows = (table["rows"] as kotlinx.serialization.json.JsonArray)
            .map { com.ayuvo.health.medications.logic.MedicationJson.strings(it) }
        assertEquals(listOf(listOf("1", "")), rows)
    }

    @Test
    fun plainTextIsOneParagraph() {
        val rows = blocks("hello\nthere")
        assertEquals(1, rows.size)
        assertEquals("paragraph", rows[0].str("kind"))
        assertEquals("hello there", rows[0].str("text"))
    }

    // -- §5 charts ---------------------------------------------------------------------------------

    @Test
    fun aValidChartReachesTheViewWithItsSpec() {
        val markdown = "Here it is.\n\n```ayuvo-chart\n" +
            """{"type":"bar","title":"Sleep","unit":"h","series":[{"label":"Asleep","points":[["Mon",6.2],["Tue",7.1]]}]}""" +
            "\n```"
        val chart = blocks(markdown).first { it.str("kind") == "chart" }
        assertTrue(chart.flag("ok"))
        val spec = chart["spec"] as JsonObject
        assertEquals("bar", spec.str("type"))
        assertEquals("Sleep", spec.str("title"))
        assertEquals(2, seriesOf(spec).first().points.size)
    }

    /** Rule 1: a spec that does not parse is shown as text, never as a guessed chart. */
    @Test
    fun aBadChartNeverReachesTheChartView() {
        for (raw in listOf(
            """{"type":"bar","series":[]}""",
            """{"type":"sankey","series":[{"points":[["a",1]]}]}""",
            "not json"
        )) {
            val chart = blocks("```ayuvo-chart\n$raw\n```").first { it.str("kind") == "chart" }
            assertFalse(raw, chart.flag("ok"))
            assertNull(chart["spec"])
            assertEquals(raw, chart.str("text"))
        }
    }

    @Test
    fun seriesAndPointsAreReadInOrder() {
        val spec = CoachReference.parseChartSpec(
            """{"type":"range","series":[{"label":"BP","points":[["Mon",78,121],["Tue",80,126]]}]}"""
        )["spec"] as JsonObject
        val series = seriesOf(spec)
        assertEquals(1, series.size)
        assertEquals("BP", series[0].label)
        assertEquals(listOf("Mon", "Tue"), series[0].points.map { it.x })
        assertEquals(listOf(78.0, 80.0), series[0].points.map { it.y })
        assertEquals(listOf(121.0, 126.0), series[0].points.mapNotNull { it.y2 })
    }

    @Test
    fun theAccessibilityLabelNamesTheKindAndTheRange() {
        val spec = CoachReference.parseChartSpec(
            """{"type":"bar","title":"Sleep","unit":"h","series":[{"points":[["Mon",6.2],["Tue",7.8]]}]}"""
        )["spec"] as JsonObject
        val label = CoachReference.chartAccessibilityText(spec)
        assertTrue(label.contains("Bar chart"))
        assertTrue(label.contains("Sleep"))
        assertTrue(label.contains("6.2"))
        assertTrue(label.contains("7.8"))
        assertTrue(label.contains("h"))
    }

    /** A 60-point series must not become a smear of overlapping labels. */
    @Test
    fun axisLabelsAreThinnedButKeepTheEnds() {
        val labels = (1..60).map { "d$it" }
        val thinned = labels.thinnedTo(6)
        assertEquals(6, thinned.size)
        assertEquals("d1", thinned.first())
        assertEquals("d60", thinned.last())
        assertEquals(listOf("a", "b"), listOf("a", "b").thinnedTo(6))
    }

    // -- The model has to know the format ----------------------------------------------------------

    @Test
    fun theSystemPromptTeachesTheChartBlock() {
        val section = CoachCatalogs.chartsPromptSection()
        assertTrue(section.contains("ayuvo-chart"))
        for (kind in CoachReference.CHART_TYPES) {
            assertTrue("the prompt no longer lists $kind", section.contains(kind))
        }
        // The rule that keeps a chart honest.
        assertTrue(section.contains("Never estimate"))
        assertTrue(CoachCatalogs.chartGuardrails().contains("never introduces a number"))
    }

    @Test
    fun theChartSectionIsAppendedToTheSystemPrompt() {
        val lines = chartPromptLines()
        assertNotNull(lines)
        assertTrue(lines.any { it.contains("ayuvo-chart") })
        assertTrue(lines.any { it.contains("Chart guardrails") })
    }
}
