package com.ayuvo.health.cycle.coach

import com.ayuvo.health.cycle.engine.CycleConfig
import com.ayuvo.health.cycle.engine.CycleSnapshot
import com.ayuvo.health.cycle.engine.CycleTrends
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer
import java.util.Locale

/**
 * Cycle summary for the Coach prompt (docs/cycle-tracking.md §8, `shared/cycle/coach.json`): only when the user turned
 * on Coach access. Summary lines (cycle lengths, median, range, variability, typical period, today's cycle day and
 * estimated phase, next estimate, late days, top symptoms and moods, max pain per cycle) plus the guardrails. Notes
 * and individual day logs are never included. English prompt text, like the rest of `ChatService`.
 */
class CoachCycleContext(private val coach: Coach) {

    /** The parsed `coach.json`. */
    class Coach(
        val mentionsWords: List<String>,
        val header: String,
        val notAvailableLine: String,
        val lines: Map<String, String>,
        val prompt: List<String>
    ) {
        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            fun parse(text: String): Coach {
                val o = json.parseToJsonElement(text) as JsonObject
                fun s(k: String) = (o[k] as JsonPrimitive).content
                fun list(k: String) = (o[k] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
                val lines = (o["lines"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
                return Coach(list("mentions_words"), s("header"), s("not_available_line"), lines, list("prompt"))
            }
        }
    }

    /** Whether [message] names a cycle topic (whole words, case and accents folded). */
    fun mentionsCycle(message: String): Boolean {
        val words = fold(message).split(' ').filter { it.isNotEmpty() }.toSet()
        return coach.mentionsWords.any { fold(it) in words }
    }

    /** "Not available" line when Coach access is off and the user asked about their cycle; else empty. */
    fun notAvailableLines(message: String): List<String> =
        if (mentionsCycle(message)) listOf("", "- " + coach.notAvailableLine) else emptyList()

    /** The summary lines (no header, no guardrails). Nothing that is not an estimate is called one. */
    fun summaryLines(snapshot: CycleSnapshot, trends: CycleTrends, config: CycleConfig, showFertility: Boolean): List<String> {
        val out = ArrayList<String>()
        val loc = Locale.US
        val stats = snapshot.stats
        val p = snapshot.prediction
        if (p.basis == "none") return out
        if (stats.cycleCount > 0) {
            out += fill(
                "cycles",
                "lengths" to stats.cycleLengths.joinToString(", "),
                "median" to (stats.cycleMedian?.let { num(it, loc) } ?: "-"),
                "low" to (stats.cycleRange?.getOrNull(0)?.toString() ?: "-"),
                "high" to (stats.cycleRange?.getOrNull(1)?.toString() ?: "-"),
                "variability" to when (stats.variability) {
                    "high" -> "higher than usual"
                    "regular" -> "regular"
                    else -> "not enough cycles to tell"
                }
            )
        } else {
            out += coach.lines["no_cycles"].orEmpty()
        }
        p.periodLength?.let { out += fill("period", "days" to it.toString()) }
        p.currentCycleDay?.let { day ->
            val phaseKey = snapshot.today.phase.let { if (!showFertility && (it == "fertile" || it == "ovulation")) "unknown" else it }
            val phase = config.phases.firstOrNull { it.key == phaseKey }?.title ?: phaseKey
            out += fill("current", "day" to day.toString(), "phase" to phase.lowercase(loc))
        }
        if (p.nextStart != null && p.nextRange != null) {
            out += fill("next", "date" to p.nextStart, "low" to p.nextRange[0], "high" to p.nextRange[1])
        }
        if (p.lateDays > 0) out += fill("late", "days" to p.lateDays.toString())
        val cycles = trends.windowCycles
        fun freq(list: List<com.ayuvo.health.cycle.engine.CycleFrequency>, catalog: List<CycleConfig.Item>): String =
            list.take(TOP).joinToString(", ") { f ->
                val title = catalog.firstOrNull { it.key == f.key }?.title ?: f.key
                "${title.lowercase(loc)} (${f.cycles} of $cycles cycles)"
            }
        if (trends.symptomFrequency.isNotEmpty()) out += fill("symptoms", "cycles" to cycles.toString(), "list" to freq(trends.symptomFrequency, config.symptoms))
        if (trends.moodFrequency.isNotEmpty()) out += fill("moods", "cycles" to cycles.toString(), "list" to freq(trends.moodFrequency, config.moods))
        val pains = trends.cycles.takeLast(config.prediction.historyWindow).mapNotNull { it.painMax }
        if (pains.isNotEmpty()) out += fill("pain", "list" to pains.joinToString(", "))
        return out.filter { it.isNotBlank() }
    }

    /** The prompt section: header, summary lines and guardrails. */
    fun promptLines(snapshot: CycleSnapshot, trends: CycleTrends, config: CycleConfig, showFertility: Boolean): List<String> {
        val summary = summaryLines(snapshot, trends, config, showFertility)
        if (summary.isEmpty()) return emptyList()
        return buildList {
            add("")
            add("## Cycle tracking")
            add(coach.header)
            summary.forEach { add("- $it") }
            add("")
            addAll(coach.prompt)
        }
    }

    /** The ≤ 12-line block for providers without tool calling (docs/coach.md on-device rules). */
    fun onDeviceBlock(snapshot: CycleSnapshot, trends: CycleTrends, config: CycleConfig, showFertility: Boolean): String {
        val summary = summaryLines(snapshot, trends, config, showFertility)
        if (summary.isEmpty()) return ""
        val lines = ArrayList<String>()
        lines += coach.header
        summary.take(MAX_ON_DEVICE_LINES - 4).forEach { lines += "- $it" }
        lines += coach.prompt.drop(1).take(3)
        return lines.take(MAX_ON_DEVICE_LINES).joinToString("\n")
    }

    private fun fill(key: String, vararg values: Pair<String, String>): String {
        var t = coach.lines[key] ?: return ""
        for ((k, v) in values) t = t.replace("{$k}", v)
        return t
    }

    private fun num(v: Double, loc: Locale): String =
        if (v == Math.floor(v)) String.format(loc, "%.0f", v) else String.format(loc, "%.1f", v)

    companion object {
        const val TOP = 5
        const val MAX_ON_DEVICE_LINES = 12

        internal fun fold(text: String): String {
            val stripped = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            return stripped.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        }
    }
}
