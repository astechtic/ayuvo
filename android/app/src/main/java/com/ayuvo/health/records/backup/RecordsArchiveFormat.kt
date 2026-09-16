package com.ayuvo.health.records.backup

import com.ayuvo.health.records.processing.RecordText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A row of an archive entry: the entry's columns in schema order, NULLs omitted. */
typealias ArchiveRow = JsonObject

/**
 * The portable `ayuvo-records` archive (docs/health-records.md §35): entry names, column order, row
 * encoding, truncation, reading and the merge plan. Pure Kotlin (ported from
 * `scripts/records_reference.py`) so the JVM tests run `test-vectors/archive.json` directly.
 */
object RecordsArchiveFormat {
    const val FORMAT = "ayuvo-records"
    const val FORMAT_VERSION = 1
    const val SCHEMA_VERSION = 4
    const val APP = "Ayuvo"
    const val PLATFORM = "android"

    /** Bytes of one encoded ndjson line. */
    const val LINE_CAP = 256 * 1024

    const val MANIFEST = "manifest.json"
    const val CHECKSUMS = "checksums.json"
    const val FILES_PREFIX = "files/"
    const val THUMB_NAME = "thumb.jpg"

    val MANIFEST_KEYS = listOf(
        "format", "format_version", "schema_version", "app", "app_version", "platform",
        "created_ms", "time_zone", "record_count", "file_count", "total_file_bytes"
    )

    // -- Columns in schema order (schema.sql + migrations 002/003/004). -------------------------
    // `records.seq` is the local FTS docid and is never exported.

    val RECORD_COLUMNS = listOf(
        "id", "parent_id", "page_start", "page_end", "title", "record_type", "category", "source",
        "import_method", "source_app", "original_filename", "created_ms", "updated_ms", "document_date",
        "document_date_precision", "document_date_method", "sort_date", "mime_type", "file_type",
        "file_size", "page_count", "file_path", "thumbnail_path", "checksum_sha256", "processing_status",
        "processing_error", "review_status", "favorite", "archived", "notes", "phash", "text_signature",
        "ai_mode_used", "ai_provider", "type_confidence", "type_method", "shared_count", "last_shared_ms"
    )
    val PAGE_COLUMNS = listOf("record_id", "page_index", "text", "text_source", "ocr_confidence", "width", "height", "blocks_json")
    val FIELD_COLUMNS = listOf(
        "id", "record_id", "field_key", "value_text", "value_json", "method", "confidence", "state",
        "source_page", "source_bbox", "evidence", "created_ms", "updated_ms"
    )
    val OBSERVATION_COLUMNS = listOf(
        "id", "record_id", "field_id", "analyte_id", "analyte_method", "raw_name", "value_num", "value_text",
        "unit", "canonical_value", "canonical_unit", "ref_low", "ref_high", "ref_text", "flag",
        "observed_date", "observed_date_method", "method", "confidence", "state", "source_page",
        "source_bbox", "evidence", "excluded_from_trends", "created_ms", "updated_ms"
    )
    val HIGHLIGHT_COLUMNS = listOf(
        "id", "record_id", "section", "text", "method", "provider", "field_id", "source_page",
        "confidence", "dismissed", "position", "created_ms"
    )
    val LINK_COLUMNS = listOf("a_id", "b_id", "kind", "origin", "status", "score", "reasons_json", "created_ms", "updated_ms")
    val ENTITY_COLUMNS = listOf("id", "kind", "display_name", "normalized_name", "specialty", "created_ms", "updated_ms")
    val RECORD_ENTITY_COLUMNS = listOf("record_id", "entity_id", "role")
    val TAG_COLUMNS = listOf("id", "name", "record_ids")
    val ALIAS_COLUMNS = listOf("normalized_name", "analyte_id", "created_ms")

    val DATA_ENTRIES = listOf(
        "records.ndjson", "pages.ndjson", "fields.ndjson", "observations.ndjson", "highlights.ndjson",
        "links.ndjson", "entities.ndjson", "record_entities.ndjson", "tags.json", "analyte_user_aliases.json"
    )

    val ENTRY_TABLE: Map<String, String> = linkedMapOf(
        "records.ndjson" to "records", "pages.ndjson" to "pages", "fields.ndjson" to "fields",
        "observations.ndjson" to "observations", "highlights.ndjson" to "highlights",
        "links.ndjson" to "links", "entities.ndjson" to "entities",
        "record_entities.ndjson" to "record_entities", "tags.json" to "tags",
        "analyte_user_aliases.json" to "analyte_user_aliases"
    )

    val ENTRY_COLUMNS: Map<String, List<String>> = linkedMapOf(
        "records.ndjson" to RECORD_COLUMNS, "pages.ndjson" to PAGE_COLUMNS, "fields.ndjson" to FIELD_COLUMNS,
        "observations.ndjson" to OBSERVATION_COLUMNS, "highlights.ndjson" to HIGHLIGHT_COLUMNS,
        "links.ndjson" to LINK_COLUMNS, "entities.ndjson" to ENTITY_COLUMNS,
        "record_entities.ndjson" to RECORD_ENTITY_COLUMNS, "tags.json" to TAG_COLUMNS,
        "analyte_user_aliases.json" to ALIAS_COLUMNS
    )

    /** Columns a row must carry to be imported (§35 reader validation). */
    val REQUIRED_COLUMNS: Map<String, List<String>> = linkedMapOf(
        "records.ndjson" to listOf("id"), "pages.ndjson" to listOf("record_id", "page_index"),
        "fields.ndjson" to listOf("id", "record_id", "field_key"),
        "observations.ndjson" to listOf("id", "record_id"),
        "highlights.ndjson" to listOf("id", "record_id", "section", "text"),
        "links.ndjson" to listOf("a_id", "b_id", "kind"), "entities.ndjson" to listOf("id", "kind"),
        "record_entities.ndjson" to listOf("record_id", "entity_id", "role"),
        "tags.json" to listOf("id", "name"),
        "analyte_user_aliases.json" to listOf("normalized_name", "analyte_id")
    )

    /** Child tables that follow their record on import. */
    val CHILD_TABLES = listOf("pages", "fields", "observations", "highlights", "record_entities")

    // -- Encoding ------------------------------------------------------------------------------

    /** Compact JSON, non-ASCII unescaped (kotlinx `JsonObject.toString()` already does both). */
    fun compact(element: JsonElement): String = element.toString()

    /**
     * Columns whose stored TEXT is exported as nested JSON (§38). `blocks_json` deliberately stays a
     * string (§35), so only `value_json` is listed.
     */
    val JSON_COLUMNS: Set<String> = setOf("value_json")

    /**
     * A JSON value with every object's keys sorted (§38: `value_json` is stored in §8 key order but
     * exported sorted, so archives are stable and portable).
     */
    fun sortedJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.keys.sorted().associateWith { sortedJson(value.getValue(it)) })
        is JsonArray -> JsonArray(value.map { sortedJson(it) })
        else -> value
    }

    /**
     * The columns present in [source], in schema order; nulls and absent columns are omitted. A column
     * holding a JSON object or array is written with sorted keys (§38).
     */
    fun row(source: JsonObject, columns: List<String>): ArchiveRow {
        val out = LinkedHashMap<String, JsonElement>()
        for (c in columns) {
            val v = source[c] ?: continue
            if (v is JsonNull) continue
            out[c] = if (v is JsonObject || v is JsonArray) sortedJson(v) else v
        }
        return JsonObject(out)
    }

    /**
     * The exact text of a data entry: one compact JSON object per line for `*.ndjson`, one compact JSON
     * value otherwise; always ending with a single LF.
     */
    fun entryText(name: String, payload: List<JsonElement>): String =
        if (name.endsWith(".ndjson")) payload.joinToString("") { compact(it) + "\n" }
        else compact(JsonArray(payload)) + "\n"

    fun manifestText(manifest: JsonObject): String = compact(manifest) + "\n"

    // -- Truncation ----------------------------------------------------------------------------

    data class Truncated(val row: ArchiveRow, val changed: Boolean)

    /**
     * A pages row whose encoded line exceeds [LINE_CAP] bytes keeps the longest prefix of its text (in
     * code points) that still fits and gains `"text_truncated": true`. When it does not fit even with an
     * empty text, `blocks_json` is dropped first (`"blocks_truncated": true`). No other table truncates.
     */
    fun truncatePageRow(input: ArchiveRow): Truncated {
        fun encoded(candidate: Map<String, JsonElement>): Int =
            compact(JsonObject(candidate)).toByteArray(Charsets.UTF_8).size
        val base = LinkedHashMap(input)
        if (encoded(base) <= LINE_CAP) return Truncated(input, false)
        val text = (base["text"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
        val probe = LinkedHashMap(base)
        probe["text"] = JsonPrimitive("")
        if (encoded(probe) > LINE_CAP && probe.containsKey("blocks_json")) {
            probe.remove("blocks_json")
            probe["blocks_truncated"] = JsonPrimitive(true)
            probe["text"] = JsonPrimitive(text)
            if (encoded(probe) <= LINE_CAP) return Truncated(JsonObject(probe), true)
            probe["text"] = JsonPrimitive("")
        }
        probe["text_truncated"] = JsonPrimitive(true)
        val points = text.codePointCount(0, text.length)
        var lo = 0
        var hi = points
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            probe["text"] = JsonPrimitive(takeCodePoints(text, mid))
            if (encoded(probe) <= LINE_CAP) lo = mid else hi = mid - 1
        }
        val kept = takeCodePoints(text, lo)
        if (kept.isEmpty()) probe.remove("text") else probe["text"] = JsonPrimitive(kept)
        return Truncated(JsonObject(probe), true)
    }

    private fun takeCodePoints(s: String, n: Int): String =
        if (n <= 0) "" else if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

    // -- Rows ----------------------------------------------------------------------------------

    /** An in-memory snapshot of the store's tables, as archive-shaped JSON rows. */
    class Snapshot(
        private val tables: Map<String, List<JsonObject>>,
        /** A snapshot may carry aliases as a `normalized_name -> analyte_id` map instead of a table. */
        val userAliases: Map<String, String> = emptyMap()
    ) {
        fun table(name: String): List<JsonObject> = tables[name].orEmpty()
        val records: List<JsonObject> get() = table("records")

        /** `analyte_user_aliases` rows, falling back to [userAliases]. */
        fun aliasRows(): List<JsonObject> = table("analyte_user_aliases").ifEmpty {
            userAliases.toSortedMap().map { (key, value) ->
                JsonObject(linkedMapOf<String, JsonElement>("normalized_name" to JsonPrimitive(key), "analyte_id" to JsonPrimitive(value)))
            }
        }
    }

    private fun str(o: JsonObject, k: String): String = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
    private fun num(o: JsonObject, k: String): Long = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L

    /** records ordered by (sort_date, created_ms, id) — the §35 export order. */
    fun recordOrder(snapshot: Snapshot): List<JsonObject> =
        snapshot.records.sortedWith(compareBy({ str(it, "sort_date") }, { num(it, "created_ms") }, { str(it, "id") }))

    /** §35 archive payload: the ten data entries, in entry order, each with its ordered rows. */
    fun archiveRows(snapshot: Snapshot): LinkedHashMap<String, List<ArchiveRow>> {
        val ordered = recordOrder(snapshot)
        val position = ordered.withIndex().associate { (i, r) -> str(r, "id") to i }
        val out = LinkedHashMap<String, List<ArchiveRow>>()
        out["records.ndjson"] = ordered.map { row(it, RECORD_COLUMNS) }
        out["pages.ndjson"] = snapshot.table("pages")
            .sortedWith(compareBy({ str(it, "record_id") }, { num(it, "page_index") }))
            .map { truncatePageRow(row(it, PAGE_COLUMNS)).row }
        out["fields.ndjson"] = snapshot.table("fields")
            .sortedWith(compareBy({ str(it, "record_id") }, { num(it, "created_ms") }, { str(it, "id") }))
            .map { row(it, FIELD_COLUMNS) }
        out["observations.ndjson"] = snapshot.table("observations")
            .sortedWith(compareBy({ str(it, "record_id") }, { num(it, "created_ms") }, { str(it, "id") }))
            .map { row(it, OBSERVATION_COLUMNS) }
        out["highlights.ndjson"] = snapshot.table("highlights")
            .sortedWith(compareBy({ str(it, "record_id") }, { str(it, "section") }, { num(it, "position") }, { str(it, "id") }))
            .map { row(it, HIGHLIGHT_COLUMNS) }
        out["links.ndjson"] = snapshot.table("links")
            .sortedWith(compareBy({ str(it, "a_id") }, { str(it, "b_id") }))
            .map { row(it, LINK_COLUMNS) }
        out["entities.ndjson"] = snapshot.table("entities")
            .sortedWith(compareBy({ str(it, "kind") }, { str(it, "normalized_name") }, { str(it, "id") }))
            .map { row(it, ENTITY_COLUMNS) }
        out["record_entities.ndjson"] = snapshot.table("record_entities")
            .sortedWith(compareBy({ str(it, "record_id") }, { str(it, "entity_id") }, { str(it, "role") }))
            .map { row(it, RECORD_ENTITY_COLUMNS) }

        val byTag = LinkedHashMap<String, MutableList<String>>()
        for (rt in snapshot.table("record_tags")) {
            byTag.getOrPut(str(rt, "tag_id")) { mutableListOf() }.add(str(rt, "record_id"))
        }
        out["tags.json"] = snapshot.table("tags")
            .sortedWith(compareBy({ RecordText.fold(str(it, "name")) }, { str(it, "id") }))
            .map { tag ->
                val ids = byTag[str(tag, "id")].orEmpty().distinct()
                    .sortedWith(compareBy({ position[it] ?: position.size }, { it }))
                JsonObject(
                    linkedMapOf(
                        "id" to (tag["id"] ?: JsonPrimitive("")),
                        "name" to (tag["name"] ?: JsonPrimitive("")),
                        "record_ids" to JsonArray(ids.map { JsonPrimitive(it) })
                    )
                )
            }
        out["analyte_user_aliases.json"] = snapshot.aliasRows()
            .sortedBy { str(it, "normalized_name") }
            .map { row(it, ALIAS_COLUMNS) }
        return out
    }

    data class FileEntry(val name: String, val recordId: String, val kind: String, val bytes: Long)

    /** The `files/` members in export order: per record, its original then its thumbnail. */
    fun fileEntries(snapshot: Snapshot, includeFiles: Boolean = true): List<FileEntry> {
        if (!includeFiles) return emptyList()
        val out = mutableListOf<FileEntry>()
        for (r in recordOrder(snapshot)) {
            for ((kind, pathKey, sizeKey) in listOf(
                Triple("original", "file_path", "file_size"),
                Triple("thumb", "thumbnail_path", "thumbnail_size")
            )) {
                val path = str(r, pathKey)
                if (path.isEmpty()) continue
                val name = path.replace('\\', '/').substringAfterLast('/')
                out += FileEntry("$FILES_PREFIX${str(r, "id")}/$name", str(r, "id"), kind, num(r, sizeKey))
            }
        }
        return out
    }

    fun entryNames(snapshot: Snapshot, includeFiles: Boolean = true): List<String> =
        listOf(MANIFEST) + DATA_ENTRIES + fileEntries(snapshot, includeFiles).map { it.name } + listOf(CHECKSUMS)

    /** §35 `manifest.json`; `record_count` counts archived records too. */
    fun manifest(
        snapshot: Snapshot,
        platform: String,
        appVersion: String,
        createdMs: Long,
        timeZone: String,
        includeFiles: Boolean = true
    ): JsonObject {
        val files = fileEntries(snapshot, includeFiles)
        return JsonObject(
            linkedMapOf(
                "format" to JsonPrimitive(FORMAT),
                "format_version" to JsonPrimitive(FORMAT_VERSION),
                "schema_version" to JsonPrimitive(SCHEMA_VERSION),
                "app" to JsonPrimitive(APP),
                "app_version" to JsonPrimitive(appVersion),
                "platform" to JsonPrimitive(platform),
                "created_ms" to JsonPrimitive(createdMs),
                "time_zone" to JsonPrimitive(timeZone),
                "record_count" to JsonPrimitive(snapshot.records.size),
                "file_count" to JsonPrimitive(files.size),
                "total_file_bytes" to JsonPrimitive(files.sumOf { it.bytes })
            )
        )
    }

    // -- Reading -------------------------------------------------------------------------------

    val READ_ERRORS: Map<String, String> = linkedMapOf(
        "manifest_missing" to "This file is not an Ayuvo records archive",
        "bad_format" to "This file is not an Ayuvo records archive",
        "unsupported_version" to "This backup was made by a newer version of Ayuvo"
    )

    val READ_WARNINGS: Map<String, String> = linkedMapOf(
        "checksums_missing" to "checksums.json is missing; nothing could be verified",
        "checksum_mismatch" to "{entry} does not match its checksum and may be damaged",
        "checksum_unlisted" to "{entry} has no checksum",
        "file_unlisted" to "{entry} has no checksum and was skipped",
        "entry_missing" to "{entry} is missing",
        "entry_order" to "The entries are not in the export order",
        "unknown_entry" to "{entry} is not part of this format and was ignored",
        "bad_row" to "{entry} has {count} unreadable row(s)",
        "unknown_columns" to "{entry} has unknown columns that were dropped: {columns}",
        "record_count_mismatch" to "The manifest counts {expected} record(s), the archive holds {actual}"
    )

    private val PLACEHOLDER = Regex("\\{([a-z_]+)\\}")

    fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]] ?: m.value }

    data class ArchiveWarning(val code: String, val entry: String?, val text: String)

    fun warning(code: String, values: Map<String, String> = emptyMap()): ArchiveWarning =
        ArchiveWarning(code, values["entry"], fill(READ_WARNINGS.getValue(code), values))

    /** One zip entry as the reader sees it. */
    data class Entry(
        val name: String,
        /** `manifest.json` / `checksums.json` payload. */
        val json: JsonObject? = null,
        /** Parsed rows of a data entry, or null for a `files/` member. */
        val rows: List<JsonElement>? = null,
        val sha256: String? = null
    )

    data class ReadResult(
        val ok: Boolean,
        val error: String?,
        val errorText: String?,
        val manifest: JsonObject?,
        val rows: Map<String, List<JsonObject>>,
        /** record id → {"original"/"thumb": entry name}. */
        val files: Map<String, Map<String, String>>,
        val warnings: List<ArchiveWarning>,
        val counts: Map<String, Int>
    )

    /** §35 reader (reference `read_archive_rows`). */
    fun readArchiveRows(entries: List<Entry>): ReadResult {
        val names = entries.map { it.name }
        val byName = entries.associateBy { it.name }
        val warnings = mutableListOf<ArchiveWarning>()

        val manifest = byName[MANIFEST]?.json
        if (manifest == null) {
            return ReadResult(false, "manifest_missing", READ_ERRORS.getValue("manifest_missing"), null, emptyMap(), emptyMap(), warnings, emptyMap())
        }
        if (str(manifest, "format") != FORMAT) {
            return ReadResult(false, "bad_format", READ_ERRORS.getValue("bad_format"), null, emptyMap(), emptyMap(), warnings, emptyMap())
        }
        val version = (manifest["format_version"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (version == null || version > FORMAT_VERSION) {
            return ReadResult(false, "unsupported_version", READ_ERRORS.getValue("unsupported_version"), manifest, emptyMap(), emptyMap(), warnings, emptyMap())
        }

        val checksums: Map<String, String>? = byName[CHECKSUMS]?.json?.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
        }
        if (checksums == null) warnings += warning("checksums_missing")

        val known = { n: String -> n in ENTRY_TABLE || n.startsWith(FILES_PREFIX) || n == MANIFEST || n == CHECKSUMS }
        val expectedOrder = (listOf(MANIFEST) + DATA_ENTRIES).filter { it in byName } +
            names.filter { it.startsWith(FILES_PREFIX) } +
            (if (CHECKSUMS in byName) listOf(CHECKSUMS) else emptyList())
        val presentKnown = names.filter(known)
        if (presentKnown != expectedOrder) warnings += warning("entry_order")
        names.filterNot(known).forEach { warnings += warning("unknown_entry", mapOf("entry" to it)) }

        if (checksums != null) {
            for (name in names) {
                if (name == CHECKSUMS) continue
                val want = checksums[name]
                val got = byName.getValue(name).sha256
                if (want == null) {
                    warnings += warning(if (name.startsWith(FILES_PREFIX)) "file_unlisted" else "checksum_unlisted", mapOf("entry" to name))
                } else if (got != null && got != want) {
                    warnings += warning("checksum_mismatch", mapOf("entry" to name))
                }
            }
            for (name in checksums.keys.sorted()) {
                if (name !in byName) warnings += warning("entry_missing", mapOf("entry" to name))
            }
        }

        val rows = LinkedHashMap<String, List<JsonObject>>()
        val counts = LinkedHashMap<String, Int>()
        for (name in DATA_ENTRIES) {
            val table = ENTRY_TABLE.getValue(name)
            val entryRows = byName[name]?.rows
            if (entryRows == null) {
                warnings += warning(
                    if (name !in byName) "entry_missing" else "bad_row",
                    mapOf("entry" to name, "count" to "0")
                )
                rows[table] = emptyList()
                counts[name] = 0
                continue
            }
            val columns = ENTRY_COLUMNS.getValue(name)
            val required = REQUIRED_COLUMNS.getValue(name)
            val kept = mutableListOf<JsonObject>()
            var bad = 0
            val unknown = sortedSetOf<String>()
            for (element in entryRows) {
                val r = element as? JsonObject
                if (r == null || required.any { r[it] == null || r[it] is JsonNull }) {
                    bad++
                    continue
                }
                unknown += r.keys - columns.toSet() - setOf("text_truncated", "blocks_truncated")
                kept += JsonObject(linkedMapOf<String, JsonElement>().apply {
                    for (c in columns) {
                        val v = r[c] ?: continue
                        if (v is JsonNull) continue
                        put(c, v)
                    }
                })
            }
            if (bad > 0) warnings += warning("bad_row", mapOf("entry" to name, "count" to bad.toString()))
            if (unknown.isNotEmpty()) {
                warnings += warning("unknown_columns", mapOf("entry" to name, "columns" to unknown.joinToString(", ")))
            }
            rows[table] = kept
            counts[name] = kept.size
        }

        val files = LinkedHashMap<String, MutableMap<String, String>>()
        for (name in names) {
            if (!name.startsWith(FILES_PREFIX)) continue
            val parts = name.split('/')
            if (parts.size != 3 || parts[1].isEmpty() || parts[2].isEmpty()) continue
            if (checksums != null && checksums[name] == null) continue
            val kind = if (parts[2].startsWith("thumb.")) "thumb" else "original"
            files.getOrPut(parts[1]) { LinkedHashMap() }[kind] = name
        }

        val actual = rows["records"]?.size ?: 0
        val declared = (manifest["record_count"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (declared != null && declared.toInt() != actual) {
            warnings += warning("record_count_mismatch", mapOf("expected" to declared.toString(), "actual" to actual.toString()))
        }
        return ReadResult(true, null, null, manifest, rows, files, warnings, counts)
    }

    // -- Merge / Replace -----------------------------------------------------------------------

    enum class ImportMode(val raw: String) { MERGE("merge"), REPLACE("replace") }

    data class SkippedRecord(val id: String, val reason: String, val existingId: String?)

    data class ImportedRecord(
        val id: String,
        val processingStatus: String,
        val filePath: String?,
        val thumbnailPath: String?,
        val processingError: String?
    )

    data class TableCount(val imported: Int, val skipped: Int)

    data class MergePlan(
        val mode: String,
        val error: String?,
        val deleted: List<String>,
        val imported: List<String>,
        val skipped: List<SkippedRecord>,
        val records: List<ImportedRecord>,
        val tables: Map<String, TableCount>,
        val rows: Map<String, List<JsonObject>>,
        val fileMissing: List<String>,
        val rebuildFts: Boolean
    )

    /**
     * §35 import planning (reference `merge_plan`): what Merge / Replace does, without touching a store.
     * [existing] is the current store snapshot, [incoming] the rows a [readArchiveRows] produced, and
     * [files] its `files/` members.
     */
    fun mergePlan(
        existing: Snapshot,
        incoming: Map<String, List<JsonObject>>,
        files: Map<String, Map<String, String>>,
        mode: ImportMode
    ): MergePlan {
        val existingRecords = existing.records
        val deleted: List<String>
        val existingIds: Set<String>
        val existingChecksums: Map<String, String>
        if (mode == ImportMode.REPLACE) {
            deleted = existingRecords
                .sortedWith(compareBy<JsonObject>({ str(it, "sort_date") }, { num(it, "created_ms") }, { num(it, "seq") }).reversed())
                .map { str(it, "id") }
            existingIds = emptySet()
            existingChecksums = emptyMap()
        } else {
            deleted = emptyList()
            existingIds = existingRecords.map { str(it, "id") }.toSet()
            val checksums = LinkedHashMap<String, String>()
            for (r in existingRecords) {
                val c = str(r, "checksum_sha256")
                if (c.isNotEmpty() && c !in checksums) checksums[c] = str(r, "id")
            }
            existingChecksums = checksums
        }

        val imported = mutableListOf<String>()
        val skipped = mutableListOf<SkippedRecord>()
        val records = mutableListOf<ImportedRecord>()
        val seen = HashSet<String>()
        for (r in incoming["records"].orEmpty()) {
            val rid = str(r, "id")
            if (rid.isEmpty()) continue
            if (!seen.add(rid)) {
                skipped += SkippedRecord(rid, "duplicate_in_archive", null)
                continue
            }
            if (rid in existingIds) {
                skipped += SkippedRecord(rid, "existing_id", rid)
                continue
            }
            val checksum = str(r, "checksum_sha256")
            val match = if (checksum.isEmpty()) null else existingChecksums[checksum]
            if (match != null) {
                skipped += SkippedRecord(rid, "existing_checksum", match)
                continue
            }
            imported += rid
            val entry = files[rid].orEmpty()
            val hasOriginal = entry["original"] != null
            val path = str(r, "file_path").takeIf { it.isNotEmpty() }
            records += ImportedRecord(
                id = rid,
                processingStatus = "ready",
                filePath = if (hasOriginal) path else null,
                thumbnailPath = if (entry["thumb"] != null) str(r, "thumbnail_path").takeIf { it.isNotEmpty() } else null,
                processingError = if (hasOriginal || path == null) null else "file_missing"
            )
        }

        val kept = imported.toSet()
        val live = kept + existingIds
        val tables = LinkedHashMap<String, TableCount>()
        val outRows = LinkedHashMap<String, List<JsonObject>>()
        for (table in CHILD_TABLES) {
            val all = incoming[table].orEmpty()
            val good = all.filter { str(it, "record_id") in kept }
            tables[table] = TableCount(good.size, all.size - good.size)
            outRows[table] = good
        }
        val allLinks = incoming["links"].orEmpty()
        val links = allLinks.filter {
            val a = str(it, "a_id")
            val b = str(it, "b_id")
            a in live && b in live && (a in kept || b in kept)
        }
        tables["links"] = TableCount(links.size, allLinks.size - links.size)
        outRows["links"] = links

        val allEntities = incoming["entities"].orEmpty()
        val used = outRows["record_entities"].orEmpty().map { str(it, "entity_id") }.toSet()
        val entities = allEntities.filter { str(it, "id") in used }
        tables["entities"] = TableCount(entities.size, allEntities.size - entities.size)
        outRows["entities"] = entities

        val allTags = incoming["tags"].orEmpty()
        val tags = mutableListOf<JsonObject>()
        for (t in allTags) {
            val ids = (t["record_ids"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }
                .filter { it in kept }
            if (ids.isEmpty()) continue
            tags += JsonObject(
                linkedMapOf(
                    "id" to (t["id"] ?: JsonPrimitive("")),
                    "name" to (t["name"] ?: JsonPrimitive("")),
                    "record_ids" to JsonArray(ids.map { JsonPrimitive(it) })
                )
            )
        }
        tables["tags"] = TableCount(tags.size, allTags.size - tags.size)
        outRows["tags"] = tags

        val have = if (mode == ImportMode.MERGE) {
            existing.table("analyte_user_aliases").map { str(it, "normalized_name") }.toSet() + existing.userAliases.keys
        } else {
            emptySet()
        }
        val allAliases = incoming["analyte_user_aliases"].orEmpty()
        val aliases = allAliases.filter { str(it, "normalized_name") !in have }
        tables["analyte_user_aliases"] = TableCount(aliases.size, allAliases.size - aliases.size)
        outRows["analyte_user_aliases"] = aliases

        outRows["records"] = incoming["records"].orEmpty().filter { str(it, "id") in kept }
        return MergePlan(
            mode = mode.raw,
            error = null,
            deleted = deleted,
            imported = imported,
            skipped = skipped,
            records = records,
            tables = tables,
            rows = outRows,
            fileMissing = records.filter { it.processingError == "file_missing" }.map { it.id },
            rebuildFts = true
        )
    }

    class UnsupportedArchive(message: String) : Exception(message)

    /** `files/<record id>/<name>`; anything else (absolute paths, `..`, nesting) is rejected. */
    fun fileEntry(name: String): Pair<String, String>? {
        if (!name.startsWith(FILES_PREFIX)) return null
        val parts = name.removePrefix(FILES_PREFIX).split('/')
        if (parts.size != 2) return null
        val (id, file) = parts
        if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '-' }) return null
        if (file.isEmpty() || file.contains("..") || file.contains('\\')) return null
        return id to file
    }
}
