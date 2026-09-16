package com.ayuvo.health.records.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ayuvo.health.records.data.RecordFileStore
import com.ayuvo.health.records.data.RecordsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** Export/import progress for the Backup screen (§35). */
data class ArchiveProgress(val step: String, val done: Int = 0, val total: Int = 0)

data class ArchiveExportResult(
    val file: File,
    val recordCount: Int,
    val fileCount: Int,
    val totalFileBytes: Long,
    val archiveBytes: Long
)

data class ArchiveImportResult(
    val manifest: JsonObject?,
    val importedRecords: Int,
    val skippedRecords: Int,
    val importedFiles: Int,
    val missingFiles: Int,
    /** §35 reader warnings (checksum mismatches, missing entries, unreadable rows …). */
    val warnings: List<RecordsArchiveFormat.ArchiveWarning>
)

/**
 * Streaming `ayuvo-records` archive writer (docs §35). Entries are written one at a time straight into
 * the zip, so neither the archive nor any original is ever held whole in memory. The row order, the
 * column order and the line encoding all come from [RecordsArchiveFormat].
 */
class RecordsArchiveWriter(
    private val helper: RecordsDatabase,
    private val files: RecordFileStore,
    private val appVersion: String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val timeZone: () -> String = { java.util.TimeZone.getDefault().id }
) {

    suspend fun export(
        target: File,
        includeOriginals: Boolean = true,
        onProgress: (ArchiveProgress) -> Unit = {}
    ): ArchiveExportResult = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        target.parentFile?.mkdirs()
        val checksums = LinkedHashMap<String, String>()
        val order = recordOrder(db)
        val payloads = if (includeOriginals) filePayloads(db, order) else emptyList()
        val recordCount = db.rawQuery("SELECT COUNT(*) FROM records", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val totalBytes = payloads.sumOf { it.file.length() }

        ZipOutputStream(BufferedOutputStream(target.outputStream())).use { zip ->
            zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)
            entry(zip, checksums, RecordsArchiveFormat.MANIFEST) { out ->
                val manifest = JsonObject(
                    linkedMapOf<String, JsonElement>(
                        "format" to JsonPrimitive(RecordsArchiveFormat.FORMAT),
                        "format_version" to JsonPrimitive(RecordsArchiveFormat.FORMAT_VERSION),
                        "schema_version" to JsonPrimitive(RecordsArchiveFormat.SCHEMA_VERSION),
                        "app" to JsonPrimitive(RecordsArchiveFormat.APP),
                        "app_version" to JsonPrimitive(appVersion),
                        "platform" to JsonPrimitive(RecordsArchiveFormat.PLATFORM),
                        "created_ms" to JsonPrimitive(nowMs()),
                        "time_zone" to JsonPrimitive(timeZone()),
                        "record_count" to JsonPrimitive(recordCount),
                        "file_count" to JsonPrimitive(payloads.size),
                        "total_file_bytes" to JsonPrimitive(totalBytes)
                    )
                )
                out.write(RecordsArchiveFormat.manifestText(manifest).toByteArray(Charsets.UTF_8))
            }

            for (name in RecordsArchiveFormat.DATA_ENTRIES) {
                coroutineContext.ensureActive()
                onProgress(ArchiveProgress(name))
                entry(zip, checksums, name) { out -> writeEntry(db, name, order, out) }
            }

            payloads.forEachIndexed { index, payload ->
                coroutineContext.ensureActive()
                onProgress(ArchiveProgress("files", index, payloads.size))
                entry(zip, checksums, payload.name) { out -> payload.file.inputStream().use { it.copyTo(out, COPY_BUFFER) } }
            }

            // checksums.json covers every entry except itself, so it is written last.
            zip.putNextEntry(ZipEntry(RecordsArchiveFormat.CHECKSUMS))
            val json = JsonObject(checksums.mapValues { JsonPrimitive(it.value) as JsonElement })
            zip.write((RecordsArchiveFormat.compact(json) + "\n").toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        ArchiveExportResult(target, recordCount, payloads.size, totalBytes, target.length())
    }

    /** Record ids in §35 export order. */
    private fun recordOrder(db: SQLiteDatabase): List<String> =
        db.rawQuery("SELECT id FROM records ORDER BY sort_date, created_ms, id", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private data class Payload(val name: String, val file: File)

    private fun filePayloads(db: SQLiteDatabase, order: List<String>): List<Payload> {
        val rows = HashMap<String, Pair<String?, String?>>()
        db.rawQuery("SELECT id, file_path, thumbnail_path FROM records", null).use { c ->
            while (c.moveToNext()) {
                rows[c.getString(0)] = (if (c.isNull(1)) null else c.getString(1)) to (if (c.isNull(2)) null else c.getString(2))
            }
        }
        val out = LinkedHashMap<String, Payload>()
        for (id in order) {
            val (path, thumb) = rows[id] ?: continue
            for (relative in listOfNotNull(path, thumb)) {
                val file = files.resolve(relative)?.takeIf { it.isFile } ?: continue
                val name = "${RecordsArchiveFormat.FILES_PREFIX}$id/${relative.substringAfterLast('/')}"
                out.putIfAbsent(name, Payload(name, file))
            }
        }
        return out.values.toList()
    }

    private fun writeEntry(db: SQLiteDatabase, name: String, order: List<String>, out: OutputStream) {
        when (name) {
            "tags.json" -> out.write(RecordsArchiveFormat.entryText(name, tagRows(db, order)).toByteArray(Charsets.UTF_8))
            "analyte_user_aliases.json" -> {
                val rows = query(db, "analyte_user_aliases", RecordsArchiveFormat.ALIAS_COLUMNS, "normalized_name")
                out.write(RecordsArchiveFormat.entryText(name, rows).toByteArray(Charsets.UTF_8))
            }
            else -> {
                val spec = TABLE_SPEC.getValue(name)
                val columns = RecordsArchiveFormat.ENTRY_COLUMNS.getValue(name)
                db.rawQuery("SELECT ${columns.joinToString(", ")} FROM ${spec.first} ORDER BY ${spec.second}", null).use { c ->
                    while (c.moveToNext()) {
                        var row = cursorRow(c, columns)
                        if (name == "pages.ndjson") row = RecordsArchiveFormat.truncatePageRow(row).row
                        out.write((RecordsArchiveFormat.compact(row) + "\n").toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    private fun tagRows(db: SQLiteDatabase, order: List<String>): List<JsonObject> {
        val position = order.withIndex().associate { (i, id) -> id to i }
        val byTag = LinkedHashMap<String, MutableList<String>>()
        db.rawQuery("SELECT tag_id, record_id FROM record_tags", null).use { c ->
            while (c.moveToNext()) byTag.getOrPut(c.getString(0)) { mutableListOf() }.add(c.getString(1))
        }
        val tags = db.rawQuery("SELECT id, name FROM tags", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        return tags
            .sortedWith(compareBy({ com.ayuvo.health.records.processing.RecordText.fold(it.second) }, { it.first }))
            .map { (id, name) ->
                val ids = byTag[id].orEmpty().distinct().sortedWith(compareBy({ position[it] ?: position.size }, { it }))
                JsonObject(
                    linkedMapOf<String, JsonElement>(
                        "id" to JsonPrimitive(id),
                        "name" to JsonPrimitive(name),
                        "record_ids" to JsonArray(ids.map { JsonPrimitive(it) })
                    )
                )
            }
    }

    private fun query(db: SQLiteDatabase, table: String, columns: List<String>, orderBy: String): List<JsonObject> =
        db.rawQuery("SELECT ${columns.joinToString(", ")} FROM $table ORDER BY $orderBy", null).use { c ->
            buildList { while (c.moveToNext()) add(cursorRow(c, columns)) }
        }

    /**
     * One row as JSON, typed by SQLite's stored type; NULL columns are omitted (§35). A
     * [RecordsArchiveFormat.JSON_COLUMNS] column is re-parsed so it exports as nested JSON (§38).
     */
    private fun cursorRow(c: Cursor, columns: List<String>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        columns.forEachIndexed { index, column ->
            when (c.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> Unit
                Cursor.FIELD_TYPE_INTEGER -> out[column] = JsonPrimitive(c.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> out[column] = JsonPrimitive(c.getDouble(index))
                else -> {
                    val text = c.getString(index)
                    out[column] = if (column in RecordsArchiveFormat.JSON_COLUMNS) nestedJson(text) else JsonPrimitive(text)
                }
            }
        }
        return JsonObject(out)
    }

    /** The stored text as a JSON object/array when it parses as one, else the text itself. */
    private fun nestedJson(text: String): JsonElement {
        val parsed = runCatching {
            com.ayuvo.health.records.processing.RecordJson.json.parseToJsonElement(text)
        }.getOrNull()
        return if (parsed is JsonObject || parsed is JsonArray) parsed else JsonPrimitive(text)
    }

    private inline fun entry(
        zip: ZipOutputStream,
        checksums: MutableMap<String, String>,
        name: String,
        write: (OutputStream) -> Unit
    ) {
        zip.putNextEntry(ZipEntry(name))
        val digest = MessageDigest.getInstance("SHA-256")
        write(object : FilterOutputStream(zip) {
            override fun write(b: Int) {
                digest.update(b.toByte())
                out.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                digest.update(b, off, len)
                out.write(b, off, len)
            }

            override fun close() = Unit
        })
        zip.closeEntry()
        checksums[name] = digest.digest().toHex()
    }

    companion object {
        const val COPY_BUFFER = 64 * 1024

        /** entry name → (table, ORDER BY) reproducing the §35 row order. */
        val TABLE_SPEC: Map<String, Pair<String, String>> = mapOf(
            "records.ndjson" to ("records" to "sort_date, created_ms, id"),
            "pages.ndjson" to ("record_pages" to "record_id, page_index"),
            "fields.ndjson" to ("record_fields" to "record_id, created_ms, id"),
            "observations.ndjson" to ("observations" to "record_id, created_ms, id"),
            "highlights.ndjson" to ("record_highlights" to "record_id, section, position, id"),
            "links.ndjson" to ("record_links" to "a_id, b_id"),
            "entities.ndjson" to ("entities" to "kind, normalized_name, id"),
            "record_entities.ndjson" to ("record_entities" to "record_id, entity_id, role")
        )
    }
}

/** Streaming `ayuvo-records` reader (docs §35): Merge or Replace, with reader warnings. */
class RecordsArchiveReader(
    private val helper: RecordsDatabase,
    private val files: RecordFileStore
) {

    suspend fun import(
        source: File,
        mode: RecordsArchiveFormat.ImportMode,
        onProgress: (ArchiveProgress) -> Unit = {}
    ): ArchiveImportResult = withContext(Dispatchers.IO) {
        source.inputStream().use { stream -> import(stream, mode, onProgress) }
    }

    suspend fun import(
        source: InputStream,
        mode: RecordsArchiveFormat.ImportMode,
        onProgress: (ArchiveProgress) -> Unit = {}
    ): ArchiveImportResult = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        var manifest: JsonObject? = null
        val digests = LinkedHashMap<String, String>()
        var declaredChecksums: Map<String, String> = emptyMap()
        var sawChecksums = false
        val warnings = mutableListOf<RecordsArchiveFormat.ArchiveWarning>()
        val skipped = mutableListOf<RecordsArchiveFormat.SkippedRecord>()
        val imported = LinkedHashSet<String>()
        val entityAliases = HashMap<String, String>()
        var importedFiles = 0

        db.beginTransactionNonExclusive()
        try {
            if (mode == RecordsArchiveFormat.ImportMode.REPLACE) wipe(db)
            val existingIds = idSet(db, "SELECT id FROM records")
            val existingChecksums = HashMap<String, String>()
            db.rawQuery("SELECT checksum_sha256, id FROM records WHERE checksum_sha256 IS NOT NULL", null).use { c ->
                while (c.moveToNext()) existingChecksums.putIfAbsent(c.getString(0), c.getString(1))
            }
            val existingAliases = idSet(db, "SELECT normalized_name FROM analyte_user_aliases")

            ZipInputStream(BufferedInputStream(source)).use { zip ->
                var entry: ZipEntry? = zip.getNextEntry()
                while (entry != null) {
                    coroutineContext.ensureActive()
                    val name = entry.name
                    onProgress(ArchiveProgress(name))
                    val digest = MessageDigest.getInstance("SHA-256")
                    val counted = DigestingInputStream(zip, digest)
                    when {
                        name == RecordsArchiveFormat.MANIFEST -> {
                            manifest = parseObject(counted.readBytes().toString(Charsets.UTF_8))
                            validate(manifest)
                        }

                        name == RecordsArchiveFormat.CHECKSUMS -> {
                            sawChecksums = true
                            declaredChecksums = parseObject(counted.readBytes().toString(Charsets.UTF_8))
                                ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" }.orEmpty()
                        }

                        name.startsWith(RecordsArchiveFormat.FILES_PREFIX) -> {
                            val parsed = RecordsArchiveFormat.fileEntry(name)
                            if (parsed != null && parsed.first in imported) {
                                val dir = files.recordDir(parsed.first).apply { mkdirs() }
                                File(dir, parsed.second).outputStream().use { counted.copyTo(it, RecordsArchiveWriter.COPY_BUFFER) }
                                importedFiles++
                            } else {
                                counted.drain()
                            }
                        }

                        name in RecordsArchiveFormat.ENTRY_TABLE -> {
                            require(manifest != null) { "manifest_missing" }
                            readEntry(db, name, counted, mode, existingIds, existingChecksums, existingAliases, imported, skipped, entityAliases, warnings)
                        }

                        else -> {
                            warnings += RecordsArchiveFormat.warning("unknown_entry", mapOf("entry" to name))
                            counted.drain()
                        }
                    }
                    counted.drain()
                    digests[name] = digest.digest().toHex()
                    zip.closeEntry()
                    entry = zip.getNextEntry()
                }
            }
            if (manifest == null) throw RecordsArchiveFormat.UnsupportedArchive("manifest_missing")
            if (!sawChecksums) {
                warnings += RecordsArchiveFormat.warning("checksums_missing")
            } else {
                for ((name, got) in digests) {
                    if (name == RecordsArchiveFormat.CHECKSUMS) continue
                    val want = declaredChecksums[name]
                    if (want == null) {
                        warnings += RecordsArchiveFormat.warning(
                            if (name.startsWith(RecordsArchiveFormat.FILES_PREFIX)) "file_unlisted" else "checksum_unlisted",
                            mapOf("entry" to name)
                        )
                    } else if (want != got) {
                        warnings += RecordsArchiveFormat.warning("checksum_mismatch", mapOf("entry" to name))
                    }
                }
                for (name in declaredChecksums.keys.sorted()) {
                    if (name !in digests) warnings += RecordsArchiveFormat.warning("entry_missing", mapOf("entry" to name))
                }
            }
            val missing = markMissingFiles(db, imported)
            db.setTransactionSuccessful()
            ArchiveImportResult(manifest, imported.size, skipped.size, importedFiles, missing, warnings)
        } finally {
            db.endTransaction()
        }
    }

    private fun validate(manifest: JsonObject?) {
        if (manifest == null) throw RecordsArchiveFormat.UnsupportedArchive("manifest_missing")
        val format = (manifest["format"] as? JsonPrimitive)?.content
        if (format != RecordsArchiveFormat.FORMAT) {
            throw RecordsArchiveFormat.UnsupportedArchive(RecordsArchiveFormat.READ_ERRORS.getValue("bad_format"))
        }
        val version = (manifest["format_version"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        if (version == null || version > RecordsArchiveFormat.FORMAT_VERSION) {
            throw RecordsArchiveFormat.UnsupportedArchive(RecordsArchiveFormat.READ_ERRORS.getValue("unsupported_version"))
        }
    }

    @Suppress("LongParameterList")
    private fun readEntry(
        db: SQLiteDatabase,
        name: String,
        stream: InputStream,
        mode: RecordsArchiveFormat.ImportMode,
        existingIds: Set<String>,
        existingChecksums: Map<String, String>,
        existingAliases: Set<String>,
        imported: MutableSet<String>,
        skipped: MutableList<RecordsArchiveFormat.SkippedRecord>,
        entityAliases: MutableMap<String, String>,
        warnings: MutableList<RecordsArchiveFormat.ArchiveWarning>
    ) {
        val table = RecordsArchiveFormat.ENTRY_TABLE.getValue(name)
        val columns = RecordsArchiveFormat.ENTRY_COLUMNS.getValue(name)
        val required = RecordsArchiveFormat.REQUIRED_COLUMNS.getValue(name)
        var bad = 0
        val handle = { element: JsonObject ->
            if (required.any { element[it] == null || element[it] is JsonNull }) {
                bad++
            } else {
                apply(db, table, columns, element, mode, existingIds, existingChecksums, existingAliases, imported, skipped, entityAliases)
            }
        }
        if (name.endsWith(".ndjson")) {
            stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val o = parseObject(line)
                if (o == null) bad++ else handle(o)
            }
        } else {
            val array = runCatching {
                com.ayuvo.health.records.processing.RecordJson.json.parseToJsonElement(
                    stream.readBytes().toString(Charsets.UTF_8)
                ) as? JsonArray
            }.getOrNull()
            array?.forEach { element ->
                val o = element as? JsonObject
                if (o == null) bad++ else handle(o)
            }
        }
        if (bad > 0) warnings += RecordsArchiveFormat.warning("bad_row", mapOf("entry" to name, "count" to bad.toString()))
    }

    @Suppress("LongParameterList")
    private fun apply(
        db: SQLiteDatabase,
        table: String,
        columns: List<String>,
        row: JsonObject,
        mode: RecordsArchiveFormat.ImportMode,
        existingIds: Set<String>,
        existingChecksums: Map<String, String>,
        existingAliases: Set<String>,
        imported: MutableSet<String>,
        skipped: MutableList<RecordsArchiveFormat.SkippedRecord>,
        entityAliases: MutableMap<String, String>
    ) {
        when (table) {
            "records" -> {
                val id = text(row, "id") ?: return
                if (id in imported) {
                    skipped += RecordsArchiveFormat.SkippedRecord(id, "duplicate_in_archive", null)
                    return
                }
                if (mode == RecordsArchiveFormat.ImportMode.MERGE) {
                    if (id in existingIds) {
                        skipped += RecordsArchiveFormat.SkippedRecord(id, "existing_id", id)
                        return
                    }
                    val checksum = text(row, "checksum_sha256")
                    val match = checksum?.let { existingChecksums[it] }
                    if (match != null) {
                        skipped += RecordsArchiveFormat.SkippedRecord(id, "existing_checksum", match)
                        return
                    }
                }
                val values = contentValues(row, columns).apply {
                    // §35: imported records are `ready`; review and user states are preserved.
                    put("processing_status", "ready")
                    remove("processing_error")
                    if (getAsString("parent_id") != null && getAsString("parent_id") !in imported) remove("parent_id")
                }
                runCatching { db.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE) }
                imported += id
                db.insertWithOnConflict("processing_jobs", null, ContentValues().apply {
                    put("record_id", id)
                    put("stage", "done")
                    put("updated_ms", (row["updated_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }

            "entities" -> {
                val id = text(row, "id") ?: return
                val existing = db.rawQuery(
                    "SELECT id FROM entities WHERE kind = ? AND normalized_name = ?",
                    arrayOf(text(row, "kind").orEmpty(), text(row, "normalized_name").orEmpty())
                ).use { if (it.moveToFirst()) it.getString(0) else null }
                if (existing == null) {
                    runCatching { db.insertWithOnConflict("entities", null, contentValues(row, columns), SQLiteDatabase.CONFLICT_IGNORE) }
                } else if (existing != id) {
                    entityAliases[id] = existing
                }
            }

            "record_entities" -> {
                val recordId = text(row, "record_id") ?: return
                if (recordId !in imported) return
                val entityId = text(row, "entity_id")?.let { entityAliases[it] ?: it } ?: return
                val values = contentValues(row, columns).apply { put("entity_id", entityId) }
                runCatching { db.insertWithOnConflict("record_entities", null, values, SQLiteDatabase.CONFLICT_IGNORE) }
            }

            "links" -> {
                val a = text(row, "a_id") ?: return
                val b = text(row, "b_id") ?: return
                if ((a !in imported && a !in existingIds) || (b !in imported && b !in existingIds)) return
                if (a !in imported && b !in imported) return
                runCatching { db.insertWithOnConflict("record_links", null, contentValues(row, columns), SQLiteDatabase.CONFLICT_REPLACE) }
            }

            "tags" -> {
                val id = text(row, "id") ?: return
                val name = text(row, "name") ?: return
                val ids = (row["record_ids"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.content }
                    .filter { it in imported }
                if (ids.isEmpty()) return
                val tagId = db.rawQuery("SELECT id FROM tags WHERE name = ? COLLATE NOCASE", arrayOf(name))
                    .use { if (it.moveToFirst()) it.getString(0) else null } ?: id.also {
                    runCatching {
                        db.insertWithOnConflict("tags", null, ContentValues().apply {
                            put("id", it)
                            put("name", name)
                        }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
                for (recordId in ids) {
                    runCatching {
                        db.insertWithOnConflict("record_tags", null, ContentValues().apply {
                            put("record_id", recordId)
                            put("tag_id", tagId)
                        }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
            }

            "analyte_user_aliases" -> {
                val key = text(row, "normalized_name") ?: return
                if (mode == RecordsArchiveFormat.ImportMode.MERGE && key in existingAliases) return
                runCatching { db.insertWithOnConflict("analyte_user_aliases", null, contentValues(row, columns), SQLiteDatabase.CONFLICT_REPLACE) }
            }

            else -> {
                val recordId = text(row, "record_id") ?: return
                if (recordId !in imported) return
                val sqlTable = SQL_TABLE.getValue(table)
                var values = contentValues(row, columns)
                // record_fields is referenced by observations/highlights; a missing parent is dropped.
                if (table == "observations" || table == "highlights") {
                    val fieldId = text(row, "field_id")
                    if (fieldId != null && !exists(db, "SELECT 1 FROM record_fields WHERE id = ?", fieldId)) {
                        values = contentValues(row, columns).apply { remove("field_id") }
                    }
                }
                runCatching { db.insertWithOnConflict(sqlTable, null, values, SQLiteDatabase.CONFLICT_REPLACE) }
            }
        }
    }

    /** §35: a record without its `files/` original loses `file_path` and gets `file_missing`. */
    private fun markMissingFiles(db: SQLiteDatabase, imported: Set<String>): Int {
        var missing = 0
        for (id in imported) {
            val path = db.rawQuery("SELECT file_path FROM records WHERE id = ?", arrayOf(id))
                .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
            val present = path != null && files.resolve(path)?.isFile == true
            if (!present) {
                db.execSQL(
                    "UPDATE records SET file_path = NULL, thumbnail_path = NULL, processing_error = ? WHERE id = ?",
                    arrayOf(FILE_MISSING, id)
                )
                if (path != null) missing++
            }
            val thumb = db.rawQuery("SELECT thumbnail_path FROM records WHERE id = ?", arrayOf(id))
                .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
            if (thumb != null && files.resolve(thumb)?.isFile != true) {
                db.execSQL("UPDATE records SET thumbnail_path = NULL WHERE id = ?", arrayOf(id))
            }
        }
        return missing
    }

    private fun wipe(db: SQLiteDatabase) {
        db.delete("records_fts", null, null)
        db.delete("records", null, null)
        db.delete("entities", null, null)
        db.delete("tags", null, null)
        db.delete("analyte_user_aliases", null, null)
        db.delete("processing_jobs", null, null)
        runCatching { files.root.deleteRecursively() }
        runCatching { files.renderCache.deleteRecursively() }
    }

    private fun idSet(db: SQLiteDatabase, sql: String): Set<String> =
        db.rawQuery(sql, null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    private fun exists(db: SQLiteDatabase, sql: String, arg: String): Boolean =
        db.rawQuery(sql, arrayOf(arg)).use { it.moveToFirst() }

    private fun text(row: JsonObject, key: String): String? =
        (row[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotEmpty() }

    private fun contentValues(row: JsonObject, columns: List<String>): ContentValues {
        val values = ContentValues()
        for (column in columns) {
            if (column == "record_ids") continue
            val raw = row[column] ?: continue
            // §38: a nested JSON column comes back as an object/array and is stored as compact text.
            if (raw is JsonObject || raw is JsonArray) {
                values.put(column, RecordsArchiveFormat.compact(raw))
                continue
            }
            val p = raw as? JsonPrimitive ?: continue
            if (p is JsonNull) continue
            if (p.isString) {
                values.put(column, p.content)
            } else {
                val asLong = p.content.toLongOrNull()
                if (asLong != null) values.put(column, asLong) else p.content.toDoubleOrNull()?.let { values.put(column, it) }
            }
        }
        return values
    }

    private fun parseObject(text: String): JsonObject? = runCatching {
        com.ayuvo.health.records.processing.RecordJson.json.parseToJsonElement(text) as? JsonObject
    }.getOrNull()

    companion object {
        const val FILE_MISSING = "file_missing"

        /** Archive table name → SQLite table name. */
        val SQL_TABLE: Map<String, String> = mapOf(
            "pages" to "record_pages", "fields" to "record_fields", "observations" to "observations",
            "highlights" to "record_highlights", "record_entities" to "record_entities"
        )
    }
}

private class DigestingInputStream(private val source: InputStream, private val digest: MessageDigest) : InputStream() {
    override fun read(): Int {
        val b = source.read()
        if (b >= 0) digest.update(b.toByte())
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = source.read(b, off, len)
        if (n > 0) digest.update(b, off, n)
        return n
    }

    override fun close() = Unit

    fun drain() {
        val buffer = ByteArray(RecordsArchiveWriter.COPY_BUFFER)
        while (read(buffer, 0, buffer.size) >= 0) Unit
    }
}

internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
