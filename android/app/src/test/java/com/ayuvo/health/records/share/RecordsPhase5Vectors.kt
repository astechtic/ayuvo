package com.ayuvo.health.records.share

import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.backup.RecordsArchiveFormat
import com.ayuvo.health.records.coach.SnapshotCoachData
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.TextSource
import com.ayuvo.health.records.processing.RecordsVectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Phase 5 vector dispatch (docs §34, §35): `share_summary.json`, `redaction.json` and `archive.json`,
 * mirroring the reference `run_case`.
 */
object RecordsPhase5Vectors {

    fun runCase(function: String, input: JsonObject, catalog: AnalyteCatalog): JsonElement = when (function) {
        "share_summary" -> shareSummary(input, catalog)
        "redaction" -> redaction(input, catalog)
        "archive" -> archive(input)
        else -> error("unknown function $function")
    }

    // -- §34 share summary -------------------------------------------------------------------

    private fun shareSummary(input: JsonObject, catalog: AnalyteCatalog): JsonElement {
        val snapshot = input.obj("snapshot")
        val data = SnapshotCoachData.parse(snapshot, catalog)
        val plan = sharePlan(input.obj("plan"))
        val byId = data.records.associateBy { it.id }
        val inputs = plan.recordIds.distinct().mapNotNull { byId[it] }.map { record ->
            ShareSummaryText.Input(
                record = record,
                fields = data.fields.filter { it.recordId == record.id },
                observations = data.observations.filter { it.recordId == record.id },
                highlights = data.highlights.filter { it.recordId == record.id }
            )
        }
        val labels = (input["type_labels"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }
        val result = ShareSummaryText.build(inputs, plan, catalog, labels)
        return RecordsVectors.obj("text" to result.text, "record_ids" to result.recordIds)
    }

    fun sharePlan(o: JsonObject): SharePlan {
        val pages = LinkedHashMap<String, List<Int>?>()
        (o["pages"] as? JsonObject)?.forEach { (id, value) ->
            when {
                value is JsonPrimitive && value.content == "all" -> Unit
                value is JsonArray -> pages[id] = value.mapNotNull { (it as? JsonPrimitive)?.longOrNull?.toInt() }
                else -> Unit
            }
        }
        return SharePlan(
            recordIds = (o["record_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            pages = pages,
            includeOriginal = o.bool("include_original"),
            includeSummary = o.bool("include_summary"),
            summaryFields = (o["summary_fields"] as? JsonArray).orEmpty()
                .mapNotNull { SummaryField.fromRaw((it as? JsonPrimitive)?.content) }.toSet(),
            includeHighlights = o.bool("include_highlights"),
            includeNotes = o.bool("include_notes"),
            redactions = (o["redactions"] as? JsonArray).orEmpty()
                .mapNotNull { RedactionClass.fromRaw((it as? JsonPrimitive)?.content) }.toSet()
        )
    }

    // -- §34 redaction -----------------------------------------------------------------------

    private fun redaction(input: JsonObject, catalog: AnalyteCatalog): JsonElement {
        val snapshot = input.obj("snapshot")
        val data = SnapshotCoachData.parse(snapshot, catalog)
        val pages = snapshotPages(snapshot)
        return when (input.str("op")) {
            "targets" -> {
                val recordId = input.str("record_id")!!
                val classes = (input["classes"] as? JsonArray).orEmpty().mapNotNull { RedactionClass.fromRaw((it as? JsonPrimitive)?.content) }
                val result = RedactionTargets.forRecord(
                    recordId,
                    pages.filter { it.recordId == recordId },
                    data.fields.filter { it.recordId == recordId },
                    classes
                )
                RecordsVectors.obj(
                    "record_id" to result.recordId,
                    "classes" to result.classes.map { it.raw },
                    "pages" to result.pages.map { page ->
                        RecordsVectors.obj(
                            "page_index" to page.pageIndex,
                            "lines" to page.lines.map { line ->
                                RecordsVectors.obj("index" to line.index, "classes" to line.classes.map { it.raw }, "box" to line.box)
                            }
                        )
                    },
                    "excluded_pages" to result.excludedPages,
                    "warnings" to result.warnings.map(::warningJson)
                )
            }

            "plan_warnings" -> {
                val plan = sharePlan(input.obj("plan"))
                val byId = data.records.associateBy { it.id }
                val inputs = plan.recordIds.distinct().mapNotNull { byId[it] }.map { record ->
                    ShareSummaryText.Input(
                        record = record,
                        fields = data.fields.filter { it.recordId == record.id },
                        observations = data.observations.filter { it.recordId == record.id },
                        highlights = data.highlights.filter { it.recordId == record.id }
                    )
                }
                val result = SharePlanWarnings.build(
                    plan = plan,
                    records = byId,
                    pagesByRecord = pages.groupBy { it.recordId },
                    fieldsByRecord = data.fields.groupBy { it.recordId },
                    summaryInputs = inputs,
                    catalog = catalog
                )
                RecordsVectors.obj(
                    "warnings" to result.warnings.map(::warningJson),
                    "pages" to result.pages.map { RecordsVectors.obj("record_id" to it.recordId, "pages" to it.pages) },
                    "records" to result.records
                )
            }

            else -> error("redaction op")
        }
    }

    private fun warningJson(w: ShareWarningItem) =
        RecordsVectors.obj("code" to w.code, "record_id" to w.recordId, "page_index" to w.pageIndex, "text" to w.text)

    /** Snapshot pages including `blocks_json` (which [SnapshotCoachData] does not carry). */
    fun snapshotPages(snapshot: JsonObject): List<RecordPage> =
        (snapshot["pages"] as? JsonArray).orEmpty().map { element ->
            val o = element as JsonObject
            val blocks = o["blocks_json"]
            RecordPage(
                recordId = o.str("record_id")!!,
                pageIndex = (o["page_index"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
                text = o.str("text"),
                textSource = TextSource.fromRaw(o.str("text_source")),
                blocksJson = when {
                    blocks == null || blocks is JsonNull -> null
                    blocks is JsonPrimitive && blocks.isString -> blocks.content
                    else -> blocks.toString()
                }
            )
        }

    // -- §35 archive -------------------------------------------------------------------------

    private fun archive(input: JsonObject): JsonElement = when (input.str("op")) {
        "manifest" -> RecordsArchiveFormat.manifest(
            snapshot(input.obj("snapshot")),
            input.str("platform")!!,
            input.str("app_version")!!,
            (input["created_ms"] as JsonPrimitive).long(),
            input.str("time_zone")!!,
            input.boolOrTrue("include_files")
        )

        "entries" -> RecordsVectors.obj(
            "entries" to RecordsArchiveFormat.entryNames(snapshot(input.obj("snapshot")), input.boolOrTrue("include_files"))
        )

        "rows" -> {
            val rows = RecordsArchiveFormat.archiveRows(snapshot(input.obj("snapshot")))
            RecordsVectors.obj(
                "entries" to RecordsArchiveFormat.DATA_ENTRIES.map { name ->
                    RecordsVectors.obj("name" to name, "rows" to rows.getValue(name))
                }
            )
        }

        "truncate" -> {
            val unit = input.str("text_unit").orEmpty()
            val times = (input["text_times"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
            val text = unit.repeat(times)
            val page = LinkedHashMap<String, JsonElement>(input.obj("page"))
            if (text.isNotEmpty()) page["text"] = JsonPrimitive(text)
            val (row, changed) = RecordsArchiveFormat.truncatePageRow(
                RecordsArchiveFormat.row(JsonObject(page), RecordsArchiveFormat.PAGE_COLUMNS)
            )
            val kept = (row["text"] as? JsonPrimitive)?.content.orEmpty()
            RecordsVectors.obj(
                "input_length" to text.codePointCount(0, text.length),
                "text_length" to kept.codePointCount(0, kept.length),
                "text_bytes" to kept.toByteArray(Charsets.UTF_8).size,
                "text_truncated" to ((row["text_truncated"] as? JsonPrimitive)?.booleanOrNull == true),
                "blocks_truncated" to ((row["blocks_truncated"] as? JsonPrimitive)?.booleanOrNull == true),
                "changed" to changed,
                "line_bytes" to RecordsArchiveFormat.compact(row).toByteArray(Charsets.UTF_8).size,
                "head" to takeCp(kept, 40),
                "tail" to lastCp(kept, 40)
            )
        }

        "read" -> readJson(
            RecordsArchiveFormat.readArchiveRows(
                (input["entries"] as? JsonArray).orEmpty().map { e ->
                    val o = e as JsonObject
                    RecordsArchiveFormat.Entry(
                        name = o.str("name")!!,
                        json = o["json"] as? JsonObject,
                        rows = (o["rows"] as? JsonArray)?.toList(),
                        sha256 = o.str("sha256")
                    )
                }
            )
        )

        "merge" -> {
            val incoming = input.obj("incoming")
            val rows = (incoming["rows"] as? JsonObject)?.mapValues { (_, v) -> (v as JsonArray).map { it as JsonObject } }
                ?: incoming.mapValues { (_, v) -> (v as JsonArray).map { it as JsonObject } }
            val files = (incoming["files"] as? JsonObject)?.mapValues { (_, v) ->
                (v as JsonObject).mapValues { (_, x) -> (x as JsonPrimitive).content }
            }.orEmpty()
            val mode = RecordsArchiveFormat.ImportMode.entries.firstOrNull { it.raw == input.str("mode") }
            if (mode == null) {
                RecordsVectors.obj("error" to "bad_mode", "mode" to input.str("mode"))
            } else {
                mergeJson(RecordsArchiveFormat.mergePlan(snapshot(input.obj("snapshot")), rows, files, mode))
            }
        }

        else -> error("archive op")
    }

    private fun readJson(r: RecordsArchiveFormat.ReadResult) = RecordsVectors.obj(
        "ok" to r.ok,
        "error" to r.error,
        "error_text" to r.errorText,
        "manifest" to r.manifest,
        "rows" to JsonObject(r.rows.mapValues { (_, v) -> JsonArray(v) }),
        "files" to JsonObject(r.files.mapValues { (_, v) -> JsonObject(v.mapValues { e -> JsonPrimitive(e.value) }) }),
        "warnings" to r.warnings.map { RecordsVectors.obj("code" to it.code, "entry" to it.entry, "text" to it.text) },
        "counts" to JsonObject(r.counts.mapValues { JsonPrimitive(it.value) })
    )

    private fun mergeJson(p: RecordsArchiveFormat.MergePlan) = RecordsVectors.obj(
        "mode" to p.mode,
        "error" to p.error,
        "deleted" to p.deleted,
        "imported" to p.imported,
        "skipped" to p.skipped.map { RecordsVectors.obj("id" to it.id, "reason" to it.reason, "existing_id" to it.existingId) },
        "records" to p.records.map {
            RecordsVectors.obj(
                "id" to it.id, "processing_status" to it.processingStatus, "file_path" to it.filePath,
                "thumbnail_path" to it.thumbnailPath, "processing_error" to it.processingError
            )
        },
        "tables" to JsonObject(p.tables.mapValues { (_, v) -> RecordsVectors.obj("imported" to v.imported, "skipped" to v.skipped) }),
        "file_missing" to p.fileMissing,
        "rebuild_fts" to p.rebuildFts
    )

    fun snapshot(o: JsonObject): RecordsArchiveFormat.Snapshot = RecordsArchiveFormat.Snapshot(
        tables = o.mapNotNull { (k, v) -> (v as? JsonArray)?.let { k to it.mapNotNull { e -> e as? JsonObject } } }.toMap(),
        userAliases = (o["user_aliases"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty()
    )

    private fun takeCp(s: String, n: Int): String =
        if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

    private fun lastCp(s: String, n: Int): String {
        val count = s.codePointCount(0, s.length)
        return if (count <= n) s else s.substring(s.offsetByCodePoints(0, count - n))
    }

    private fun JsonObject.obj(k: String): JsonObject = this[k] as JsonObject
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.boolOrTrue(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: true
    private fun JsonPrimitive.long(): Long = longOrNull ?: doubleOrNull?.toLong() ?: 0L
}
