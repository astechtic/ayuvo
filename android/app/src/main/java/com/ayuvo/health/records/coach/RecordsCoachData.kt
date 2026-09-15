package com.ayuvo.health.records.coach

import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.data.RecordsQuerySql
import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.model.AiModeUsed
import com.ayuvo.health.records.model.AnalyteMethod
import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.HighlightSection
import com.ayuvo.health.records.model.ImportMethod
import com.ayuvo.health.records.model.LinkKind
import com.ayuvo.health.records.model.LinkOrigin
import com.ayuvo.health.records.model.LinkStatus
import com.ayuvo.health.records.model.Observation
import com.ayuvo.health.records.model.RecordCategory
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordFileType
import com.ayuvo.health.records.model.RecordHighlight
import com.ayuvo.health.records.model.RecordLink
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.RecordSource
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.ResultFlag
import com.ayuvo.health.records.model.ReviewStatus
import com.ayuvo.health.records.model.TextSource
import com.ayuvo.health.records.search.RecordSearchRanking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the Coach records functions read (docs §28 "store snapshot"). Lists are in row order.
 * [SnapshotCoachData] is the in-memory reference shape (vectors, tests); [StoreCoachData] reads SQLite.
 */
interface RecordsCoachData {
    val catalog: AnalyteCatalog
    /** Every record (archived included), row order. */
    suspend fun allRecords(): List<HealthRecord>
    suspend fun fieldsFor(recordIds: Collection<String>): Map<String, List<RecordField>>
    suspend fun observationsFor(recordIds: Collection<String>): Map<String, List<Observation>>
    suspend fun highlightsFor(recordIds: Collection<String>): Map<String, List<RecordHighlight>>
    suspend fun pages(recordId: String): List<RecordPage>
    suspend fun links(recordId: String): List<RecordLink>
    suspend fun userAliases(): Map<String, String>
    /** Observations (any state) whose analyte is one of [analyteIds]. */
    suspend fun observationsOfAnalytes(analyteIds: Collection<String>): List<Observation>
    /** Observations (any state) with no analyte. */
    suspend fun unmappedObservations(): List<Observation>
    /**
     * §28 step 5: the BM25 score (matchinfo `pcnalx` over every record) of each record whose FTS row
     * prefix-matches every term. Records that do not match are absent.
     */
    suspend fun bm25(terms: List<String>): Map<String, Double>

    suspend fun fields(recordId: String): List<RecordField> = fieldsFor(listOf(recordId))[recordId].orEmpty()
    suspend fun observations(recordId: String): List<Observation> = observationsFor(listOf(recordId))[recordId].orEmpty()
    suspend fun highlights(recordId: String): List<RecordHighlight> = highlightsFor(listOf(recordId))[recordId].orEmpty()
}

/** The reference snapshot in memory; BM25 is computed from `fts_row` tokens like FTS4 would. */
class SnapshotCoachData(
    val records: List<HealthRecord>,
    val tags: Map<String, List<String>> = emptyMap(),
    val fields: List<RecordField> = emptyList(),
    val observations: List<Observation> = emptyList(),
    val highlights: List<RecordHighlight> = emptyList(),
    val pages: List<RecordPage> = emptyList(),
    val links: List<RecordLink> = emptyList(),
    private val aliases: Map<String, String> = emptyMap(),
    override val catalog: AnalyteCatalog
) : RecordsCoachData {
    override suspend fun allRecords() = records
    override suspend fun fieldsFor(recordIds: Collection<String>) = recordIds.toSet().let { ids -> fields.filter { it.recordId in ids }.groupBy { it.recordId } }
    override suspend fun observationsFor(recordIds: Collection<String>) = recordIds.toSet().let { ids -> observations.filter { it.recordId in ids }.groupBy { it.recordId } }
    override suspend fun highlightsFor(recordIds: Collection<String>) = recordIds.toSet().let { ids -> highlights.filter { it.recordId in ids }.groupBy { it.recordId } }
    override suspend fun pages(recordId: String) = pages.filter { it.recordId == recordId }
    override suspend fun links(recordId: String) = links.filter { it.aId == recordId || it.bId == recordId }
    override suspend fun userAliases() = aliases
    override suspend fun observationsOfAnalytes(analyteIds: Collection<String>) = analyteIds.toSet().let { ids -> observations.filter { it.analyteId in ids } }
    override suspend fun unmappedObservations() = observations.filter { it.analyteId == null }

    override suspend fun bm25(terms: List<String>): Map<String, Double> {
        if (terms.isEmpty() || records.isEmpty()) return emptyMap()
        val all = records.map { r ->
            val row = RecordsCoach.ftsRow(
                r, tags[r.id].orEmpty(), fields.filter { it.recordId == r.id }, observations.filter { it.recordId == r.id },
                highlights.filter { it.recordId == r.id }, pages.filter { it.recordId == r.id }, catalog
            )
            row.map { RecordsCoach.ftsTokens(it) }
        }
        val out = LinkedHashMap<String, Double>()
        records.forEachIndexed { i, r ->
            val doc = all[i]
            if (terms.all { t -> doc.any { col -> col.any { it.startsWith(t) } } }) {
                out[r.id] = RecordSearchRanking.bm25(RecordsCoach.matchinfo(terms, doc, all), RecordsQuerySql.COLUMN_WEIGHTS)
            }
        }
        return out
    }

    companion object {
        /** Parses a vector snapshot (`records`, `fields`, `observations`, `highlights`, `pages`, `links`, `user_aliases`). */
        fun parse(o: JsonObject, catalog: AnalyteCatalog): SnapshotCoachData {
            fun list(key: String) = (o[key] as? JsonArray).orEmpty().map { it as JsonObject }
            val records = list("records").map { r ->
                val type = RecordType.fromRaw(r.s("record_type"))
                val created = r.l("created_ms") ?: 0L
                HealthRecord(
                    seq = r.l("seq") ?: 0L, id = r.s("id")!!, title = r.s("title") ?: "", recordType = type,
                    category = r.s("category")?.let { RecordCategory.fromRaw(it) } ?: type.defaultCategory,
                    source = RecordSource.fromRaw(r.s("source")), importMethod = ImportMethod.FILE_PICKER,
                    createdMs = created, updatedMs = created, sortDate = r.s("sort_date") ?: "",
                    mimeType = "", fileType = RecordFileType.OTHER, pageCount = (r.l("page_count") ?: 0L).toInt(),
                    reviewStatus = ReviewStatus.fromRaw(r.s("review_status")), favorite = r.b("favorite"),
                    archived = r.b("archived"), notes = r.s("notes"), aiModeUsed = AiModeUsed.fromRaw(r.s("ai_mode_used"))
                )
            }
            val tags = list("records").associate { r -> r.s("id")!! to (r["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
            val fields = list("fields").map { f ->
                RecordField(
                    id = f.s("id")!!, recordId = f.s("record_id")!!, key = f.s("field_key")!!, valueText = f.s("value_text") ?: "",
                    valueJson = (f["value_json"] as? JsonObject)?.toString(), method = ExtractionMethod.fromRaw(f.s("method")),
                    confidence = f.d("confidence") ?: 0.0, state = FieldState.fromRaw(f.s("state")), sourcePage = f.l("source_page")?.toInt()
                )
            }
            val observations = list("observations").map { parseObservation(it) }
            val highlights = list("highlights").map { h ->
                RecordHighlight(
                    id = h.s("id") ?: "", recordId = h.s("record_id")!!, section = HighlightSection.fromRaw(h.s("section")),
                    text = h.s("text") ?: "", method = ExtractionMethod.RULES, position = (h.l("position") ?: 0L).toInt(), dismissed = h.b("dismissed")
                )
            }
            val pages = list("pages").map { p -> RecordPage(p.s("record_id")!!, (p.l("page_index") ?: 0L).toInt(), p.s("text"), TextSource.PLAIN) }
            val links = list("links").map { l ->
                RecordLink(l.s("a_id")!!, l.s("b_id")!!, LinkKind.fromRaw(l.s("kind")), LinkOrigin.fromRaw(l.s("origin")), LinkStatus.fromRaw(l.s("status")))
            }
            val aliases = (o["user_aliases"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty()
            return SnapshotCoachData(records, tags, fields, observations, highlights, pages, links, aliases, catalog)
        }

        fun parseObservation(o: JsonObject) = Observation(
            id = o.s("id")!!, recordId = o.s("record_id")!!, fieldId = o.s("field_id"), analyteId = o.s("analyte_id"),
            analyteMethod = AnalyteMethod.fromRaw(o.s("analyte_method")), rawName = o.s("raw_name") ?: "", valueNum = o.d("value_num"),
            valueText = o.s("value_text") ?: "", unit = o.s("unit"), canonicalValue = o.d("canonical_value"), canonicalUnit = o.s("canonical_unit"),
            refLow = o.d("ref_low"), refHigh = o.d("ref_high"), refText = o.s("ref_text"), flag = ResultFlag.fromRaw(o.s("flag")),
            observedDate = o.s("observed_date"), observedDateMethod = o.s("observed_date_method"), method = ExtractionMethod.fromRaw(o.s("method")),
            confidence = o.d("confidence") ?: 0.0, state = FieldState.fromRaw(o.s("state")), sourcePage = o.l("source_page")?.toInt(),
            sourceBbox = o.s("source_bbox"), evidence = o.s("evidence"), excludedFromTrends = o.b("excluded_from_trends"),
            createdMs = o.l("created_ms") ?: 0L, updatedMs = o.l("updated_ms") ?: 0L
        )

        private fun JsonObject.p(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
        private fun JsonObject.s(k: String): String? = p(k)?.contentOrNull
        private fun JsonObject.d(k: String): Double? = p(k)?.takeIf { !it.isString }?.doubleOrNull
        private fun JsonObject.l(k: String): Long? = p(k)?.takeIf { !it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
        private fun JsonObject.b(k: String): Boolean = p(k)?.let { it.booleanOrNull ?: ((it.doubleOrNull ?: 0.0) != 0.0) } ?: false
    }
}

/** SQLite-backed data: BM25 comes from `matchinfo(records_fts, 'pcnalx')` over the whole FTS table. */
class StoreCoachData(private val store: RecordsStore, private val catalogProvider: () -> AnalyteCatalog) : RecordsCoachData {
    override val catalog: AnalyteCatalog get() = catalogProvider()
    private var cachedRecords: List<HealthRecord>? = null

    override suspend fun allRecords(): List<HealthRecord> = cachedRecords ?: store.allRecords().also { cachedRecords = it }
    override suspend fun fieldsFor(recordIds: Collection<String>) = store.fieldsFor(recordIds)
    override suspend fun observationsFor(recordIds: Collection<String>) = store.observationsFor(recordIds)
    override suspend fun highlightsFor(recordIds: Collection<String>) = store.highlightsFor(recordIds)
    override suspend fun pages(recordId: String) = store.pages(recordId)
    override suspend fun links(recordId: String) = store.links(recordId)
    override suspend fun userAliases() = store.userAliases()
    override suspend fun observationsOfAnalytes(analyteIds: Collection<String>) = store.observationsOfAnalytes(analyteIds)
    override suspend fun unmappedObservations() = store.unmappedObservations()
    override suspend fun bm25(terms: List<String>): Map<String, Double> {
        val bySeq = allRecords().associateBy { it.seq }
        return store.ftsMatchinfo(terms).mapNotNull { (seq, info) ->
            bySeq[seq]?.let { it.id to RecordSearchRanking.bm25(info, RecordsQuerySql.COLUMN_WEIGHTS) }
        }.toMap()
    }
}
