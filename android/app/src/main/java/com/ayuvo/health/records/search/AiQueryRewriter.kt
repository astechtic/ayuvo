package com.ayuvo.health.records.search

import com.ayuvo.health.records.ai.AiResolution
import com.ayuvo.health.records.ai.RecordsAiModeResolver
import com.ayuvo.health.records.ai.RecordsAiExtractor
import com.ayuvo.health.records.model.RecordAdvancedFilters
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.RecordsAiMode
import com.ayuvo.health.records.processing.RecordJson
import com.ayuvo.health.records.processing.RecordJson.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/** A validated AI rewrite of a search query (§17). */
data class RewrittenQuery(val filters: RecordAdvancedFilters, val terms: List<String>?)

/**
 * `AiQueryRewriter` (§17): sends ONLY the query text to the resolved AI and turns the reply into
 * a `RecordQuery` validated against the contract enumerations. Never sends record content.
 */
class AiQueryRewriter(
    private val resolver: () -> RecordsAiModeResolver,
    private val ai: () -> RecordsAiExtractor
) {
    suspend fun rewrite(query: String, mode: RecordsAiMode?, today: LocalDate = LocalDate.now()): RewrittenQuery? {
        if (mode == null || mode == RecordsAiMode.OFF) return null
        // Ask mode: the user tapped "Search smarter with AI", which is the consent for this query.
        val requested = when (mode) {
            RecordsAiMode.ASK -> if (resolver().options().localAvailable) "local" else "cloud"
            else -> null
        }
        val run = resolver().resolve(mode, requested) as? AiResolution.Run ?: return null
        val reply = ai().rewriteQuery(query, run.target)
        return parse(reply.text)
    }

    companion object {
        private val flags = setOf("abnormal", "low", "high", "critical")
        private val isoDate = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /** Lenient: the first JSON object in [text]; unknown values are dropped, never guessed. */
        fun parse(text: String): RewrittenQuery? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = RecordJson.parseObject(text.substring(start, end + 1)) ?: return null
            fun strings(key: String): List<String> = (obj[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            val types = strings("record_types").mapNotNull { raw -> RecordType.entries.firstOrNull { it.raw == raw } }.toSet()
            val filters = RecordAdvancedFilters(
                dateFrom = obj.string("date_from")?.takeIf { isoDate.matches(it) },
                dateTo = obj.string("date_to")?.takeIf { isoDate.matches(it) },
                doctor = obj.string("doctor")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                facility = obj.string("facility")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                types = types,
                flags = strings("flags").filter { it in flags }.toSet()
            )
            val terms = (obj["terms"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
            if (filters.isEmpty && terms.isNullOrEmpty()) return null
            return RewrittenQuery(filters, terms)
        }
    }
}
