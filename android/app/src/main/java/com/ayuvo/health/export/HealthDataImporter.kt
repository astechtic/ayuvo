package com.ayuvo.health.export

import com.ayuvo.health.data.health.HealthDataStore
import com.ayuvo.health.data.health.HealthPageCommit
import com.ayuvo.health.data.health.HealthRollupMath
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSeriesPoint
import com.ayuvo.health.data.health.HealthSourceRow
import com.ayuvo.health.data.health.HealthTypeDescriptor
import com.ayuvo.health.data.health.HealthTypeMeta
import com.ayuvo.health.models.HealthDataType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream

enum class HealthImportMode { MERGE, REPLACE_ALL }

class HealthImportException(message: String) : IllegalArgumentException(message)

data class HealthImportPreview(
    val manifest: HealthExportManifest,
    val sampleCount: Long,
    val seriesCount: Long,
    val countsByType: Map<String, Long>,
    val unknownTypes: List<String>,
    val unitMismatches: Long,
    val checksumWarning: Boolean,
    val hasChecksums: Boolean
)

data class HealthImportResult(
    val inserted: Int,
    val updated: Int,
    val skippedUnitMismatch: Int,
    val seriesWritten: Int,
    val unknownTypes: List<String>,
    val touchedTypes: Set<String>
)

/**
 * Reads a `ayuvo-health-data` zip in one pass (method 0 or 8 via ZipInputStream). Fails closed
 * on a foreign format or a newer major version, like DiaryImporter/CloudBackupArchive.
 * Merge: upsert by id (newer `updated_ms` wins, existing tombstones win, never deletes);
 * imported rows get `origin=1` and are immune to platform deletions. Replace: wipe, then insert.
 */
class HealthDataImporter(
    private val store: HealthDataStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    suspend fun preview(input: InputStream, sizeBytes: Long? = null): HealthImportPreview {
        if (sizeBytes != null && sizeBytes > HealthExportFormat.MAX_FILE_BYTES) throw HealthImportException("This file is too large to import.")
        var manifest: HealthExportManifest? = null
        var samples = 0L
        var series = 0L
        val counts = LinkedHashMap<String, Long>()
        val unknown = LinkedHashSet<String>()
        var mismatches = 0L
        val digests = LinkedHashMap<String, String>()
        var checksums: Map<String, String>? = null
        readZip(input) { name, reader, digestOf ->
            when (name) {
                HealthExportFormat.ENTRY_MANIFEST -> manifest = parseManifest(reader.readText())
                HealthExportFormat.ENTRY_SAMPLES -> {
                    requireManifest(manifest)
                    reader.lineSequence().forEach { line ->
                        if (line.isBlank()) return@forEach
                        checkLine(line)
                        val sample = decodeSample(line)
                        samples++
                        counts[sample.type_id] = (counts[sample.type_id] ?: 0L) + 1
                        val type = HealthDataType.byId(sample.type_id)
                        if (type == null) unknown += sample.type_id
                        else if (type.unit != sample.unit) mismatches++
                    }
                }
                HealthExportFormat.ENTRY_SERIES -> reader.lineSequence().forEach { if (it.isNotBlank()) { checkLine(it); series++ } }
                HealthExportFormat.ENTRY_CHECKSUMS -> checksums = runCatching {
                    HealthExportFormat.json.decodeFromString(HealthExportFormat.checksumsSerializer, reader.readText())
                }.getOrNull()
                else -> reader.readText()
            }
            digestOf()?.let { digests[name] = it }
        }
        val m = requireManifest(manifest)
        val expected = checksums
        val warning = expected != null && expected.any { (entry, sha) -> digests[entry] != null && digests[entry] != sha }
        return HealthImportPreview(m, samples, series, counts, unknown.toList(), mismatches, warning, expected != null)
    }

    suspend fun apply(input: InputStream, mode: HealthImportMode, sizeBytes: Long? = null): HealthImportResult {
        if (sizeBytes != null && sizeBytes > HealthExportFormat.MAX_FILE_BYTES) throw HealthImportException("This file is too large to import.")
        val z = zone()
        var manifest: HealthExportManifest? = null
        var inserted = 0
        var updated = 0
        var skipped = 0
        var seriesWritten = 0
        val unknown = LinkedHashSet<String>()
        val touched = LinkedHashSet<String>()
        val dirtyDays = HashMap<String, MutableSet<String>>()
        val batch = ArrayList<HealthSampleRow>(BATCH)
        val seriesBatch = ArrayList<HealthSeriesPoint>(BATCH)
        var replaced = false

        suspend fun flushRows() {
            if (batch.isEmpty()) return
            val result = store.commit(HealthPageCommit(rows = batch.toList()))
            inserted += result.inserted
            updated += result.updated
            batch.clear()
        }
        suspend fun flushSeries() {
            if (seriesBatch.isEmpty()) return
            seriesWritten += store.upsertSeriesPoints(seriesBatch.toList())
            seriesBatch.clear()
        }

        readZip(input) { name, reader, _ ->
            when (name) {
                HealthExportFormat.ENTRY_MANIFEST -> {
                    manifest = parseManifest(reader.readText())
                    if (mode == HealthImportMode.REPLACE_ALL && !replaced) {
                        store.deleteAll()
                        replaced = true
                    }
                }
                HealthExportFormat.ENTRY_SAMPLES -> {
                    val m = requireManifest(manifest)
                    val manifestTypes = m.types.associateBy { it.type }
                    reader.lineSequence().forEach { line ->
                        if (line.isBlank()) return@forEach
                        checkLine(line)
                        val sample = decodeSample(line)
                        val type = HealthDataType.byId(sample.type_id)
                        if (type == null) {
                            if (unknown.add(sample.type_id)) {
                                val info = manifestTypes[sample.type_id]
                                store.upsertTypeMeta(
                                    listOf(
                                        HealthTypeMeta(
                                            typeId = sample.type_id,
                                            category = "other",
                                            kind = info?.kind ?: "discrete",
                                            aggregation = info?.aggregation ?: "AVERAGE",
                                            unit = sample.unit,
                                            displayName = info?.display_name,
                                            platform = m.platform,
                                            nativeId = info?.native_id ?: sample.type_id
                                        )
                                    )
                                )
                            }
                        } else if (type.unit != sample.unit) {
                            skipped++
                            return@forEach
                        } else if (!type.exported) {
                            // nutrition / dietary_* never ride on this format.
                            skipped++
                            return@forEach
                        }
                        val row = HealthExportFormat.rowFromSample(sample, z)
                        batch += row
                        touched += row.typeId
                        dirtyDays.getOrPut(row.typeId) { HashSet() } += row.localDay
                        if (batch.size >= BATCH) flushRows()
                    }
                    flushRows()
                }
                HealthExportFormat.ENTRY_SERIES -> {
                    flushRows()
                    reader.lineSequence().forEach { line ->
                        if (line.isBlank()) return@forEach
                        checkLine(line)
                        val p = HealthExportFormat.decodeSeries(line)
                        seriesBatch += HealthSeriesPoint(sampleId = p.s, typeId = typeOfSample(p.s, batchTypes = touched), tMs = p.t, value = p.v)
                        if (seriesBatch.size >= BATCH) flushSeries()
                    }
                    flushSeries()
                }
                HealthExportFormat.ENTRY_SOURCES -> {
                    val sources = runCatching {
                        HealthExportFormat.json.decodeFromString(ListSerializer(HealthExportSource.serializer()), reader.readText())
                    }.getOrDefault(emptyList())
                    store.upsertSources(sources.map { HealthSourceRow(it.id, it.name, it.device_model, it.device_type, it.last_seen_ms) })
                }
                else -> reader.readText()
            }
        }
        requireManifest(manifest)
        flushRows()
        flushSeries()
        rebuildRollups(dirtyDays, z)
        return HealthImportResult(inserted, updated, skipped, seriesWritten, unknown.toList(), touched)
    }

    /** Series lines carry only the sample id; the type is looked up from the row itself. */
    private suspend fun typeOfSample(sampleId: String, batchTypes: Set<String>): String {
        val row = store.samplesByIds(listOf(sampleId)).firstOrNull()
        return row?.typeId ?: batchTypes.firstOrNull() ?: "other"
    }

    private suspend fun rebuildRollups(dirty: Map<String, Set<String>>, z: ZoneId) {
        val meta = store.typeMeta().associateBy { it.typeId }
        for ((typeId, days) in dirty) {
            if (days.isEmpty()) continue
            val descriptor = HealthTypeDescriptor.resolve(typeId, meta)
            val sorted = days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()
            if (sorted.isEmpty()) continue
            val from = sorted.first().minusDays(2).atStartOfDay(z).toInstant().toEpochMilli()
            val to = sorted.last().plusDays(2).atStartOfDay(z).toInstant().toEpochMilli()
            val rows = store.samplesBetween(typeId, from, to).filter { it.localDay in days }
            store.replaceDailyRollups(typeId, days, HealthRollupMath.rebuildDaily(descriptor, rows, z.id))
        }
    }

    // -- zip plumbing -----------------------------------------------------------

    private inline fun readZip(input: InputStream, onEntry: (name: String, reader: BufferedReader, digestOf: () -> String?) -> Unit) {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val digest = MessageDigest.getInstance("SHA-256")
                val digesting = object : InputStream() {
                    override fun read(): Int = zip.read().also { if (it >= 0) digest.update(it.toByte()) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = zip.read(b, off, len).also { if (it > 0) digest.update(b, off, it) }
                }
                val reader = BufferedReader(InputStreamReader(digesting, Charsets.UTF_8), 64 * 1024)
                var hex: String? = null
                onEntry(entry.name.substringAfterLast('/'), reader) {
                    // Drain so the digest covers the whole entry even when the reader stopped early.
                    val buf = ByteArray(8192)
                    while (digesting.read(buf, 0, buf.size) > 0) Unit
                    hex ?: digest.digest().joinToString("") { "%02x".format(it) }.also { hex = it }
                }
                zip.closeEntry()
            }
        }
    }

    private fun parseManifest(text: String): HealthExportManifest {
        val manifest = try {
            HealthExportFormat.json.decodeFromString(HealthExportManifest.serializer(), text)
        } catch (_: SerializationException) {
            throw HealthImportException("This is not an Ayuvo health data export.")
        } catch (_: IllegalArgumentException) {
            throw HealthImportException("This is not an Ayuvo health data export.")
        }
        if (manifest.format != HealthExportFormat.FORMAT) throw HealthImportException("This is not an Ayuvo health data export.")
        if (manifest.format_version > HealthExportFormat.VERSION) throw HealthImportException("This export needs a newer Ayuvo.")
        return manifest
    }

    private fun requireManifest(manifest: HealthExportManifest?): HealthExportManifest =
        manifest ?: throw HealthImportException("This is not an Ayuvo health data export.")

    private fun checkLine(line: String) {
        if (line.length > HealthExportFormat.MAX_LINE_BYTES) throw HealthImportException("The export contains an oversized record.")
    }

    private fun decodeSample(line: String): HealthExportSample = try {
        HealthExportFormat.decodeSample(line)
    } catch (_: SerializationException) {
        throw HealthImportException("The export contains an unreadable record.")
    } catch (_: IllegalArgumentException) {
        throw HealthImportException("The export contains an unreadable record.")
    }

    private companion object {
        const val BATCH = 500
    }
}
