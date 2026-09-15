package com.ayuvo.health.export

import com.ayuvo.health.data.health.HealthDataStore
import com.ayuvo.health.data.health.HealthTypeDescriptor
import com.ayuvo.health.models.HealthDataType
import kotlinx.serialization.builtins.ListSerializer
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class HealthExportResult(val sampleCount: Long, val seriesCount: Long, val typeCount: Int, val checksums: Map<String, String>)

/**
 * Streams the mirror into a `ayuvo-health-data` v1 zip (deflate). Rows are written straight
 * from the store cursor, so a multi-year export never materialises in memory. `deleted` rows,
 * `nutrition` and the virtual `dietary_*` types are omitted (the food diary export covers intake).
 */
class HealthDataExporter(
    private val store: HealthDataStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: Clock = Clock.systemUTC()
) {

    suspend fun write(out: OutputStream, appVersion: String, platform: String = "android"): HealthExportResult {
        val z = zone()
        val meta = store.typeMeta().associateBy { it.typeId }
        val summaries = store.typeSummaries()
        val exportedTypeIds = summaries.map { it.typeId }.filter { id ->
            val type = HealthDataType.byId(id)
            type == null || type.exported
        }.toSet()
        val sources = store.sources()
        val sourceNames = sources.associate { it.id to it.name }
        val checksums = LinkedHashMap<String, String>()
        var sampleCount = 0L
        var seriesCount = 0L
        var minStart: Long? = null
        var maxEnd: Long? = null

        val types = summaries.filter { it.typeId in exportedTypeIds }.map { s ->
            val descriptor = HealthTypeDescriptor.resolve(s.typeId, meta)
            val registry = HealthDataType.byId(s.typeId)
            HealthExportTypeInfo(
                type = s.typeId,
                category = registry?.category?.id ?: meta[s.typeId]?.category ?: "other",
                kind = descriptor.kind.id,
                aggregation = descriptor.aggregation.name,
                unit = descriptor.unit,
                display_name = meta[s.typeId]?.displayName,
                native_id = meta[s.typeId]?.nativeId,
                record_count = s.count,
                series_count = s.seriesCount
            )
        }.sortedBy { it.type }
        summaries.filter { it.typeId in exportedTypeIds }.forEach { s ->
            s.firstMs?.let { minStart = minOf(minStart ?: it, it) }
            s.lastMs?.let { maxEnd = maxOf(maxEnd ?: it, it) }
        }
        val manifest = HealthExportManifest(
            format = HealthExportFormat.FORMAT,
            format_version = HealthExportFormat.VERSION,
            platform = platform,
            app_version = appVersion,
            exported_at = Instant.now(clock).toString(),
            zone_id = z.id,
            date_range = if (minStart != null && maxEnd != null) HealthExportDateRange(
                Instant.ofEpochMilli(minStart!!).atZone(z).toLocalDate().toString(),
                Instant.ofEpochMilli(maxEnd!!).atZone(z).toLocalDate().toString()
            ) else null,
            types = types
        )

        ZipOutputStream(out).use { zip ->
            suspend fun entry(name: String, writer: suspend (DigestWriter) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                val digest = DigestWriter(zip)
                writer(digest)
                digest.flush()
                zip.closeEntry()
                checksums[name] = digest.hex()
            }
            entry(HealthExportFormat.ENTRY_MANIFEST) { w ->
                w.write(HealthExportFormat.json.encodeToString(HealthExportManifest.serializer(), manifest))
            }
            entry(HealthExportFormat.ENTRY_SAMPLES) { w ->
                store.forEachExportRow(exportedTypeIds) { row ->
                    w.write(HealthExportFormat.encodeSample(HealthExportFormat.sampleFromRow(row, sourceNames[row.sourceId], z)))
                    w.write("\n")
                    sampleCount++
                }
            }
            entry(HealthExportFormat.ENTRY_SERIES) { w ->
                store.forEachSeriesPoint { point ->
                    if (point.typeId in exportedTypeIds) {
                        w.write(HealthExportFormat.encodeSeries(HealthExportFormat.seriesFromPoint(point)))
                        w.write("\n")
                        seriesCount++
                    }
                }
            }
            entry(HealthExportFormat.ENTRY_SOURCES) { w ->
                w.write(
                    HealthExportFormat.json.encodeToString(
                        ListSerializer(HealthExportSource.serializer()),
                        sources.map(HealthExportFormat::sourceFromRow)
                    )
                )
            }
            zip.putNextEntry(ZipEntry(HealthExportFormat.ENTRY_CHECKSUMS))
            zip.write(HealthExportFormat.json.encodeToString(HealthExportFormat.checksumsSerializer, checksums).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return HealthExportResult(sampleCount, seriesCount, types.size, checksums)
    }

    /** Writes UTF-8 text to the zip while hashing the uncompressed entry bytes. */
    private class DigestWriter(private val zip: ZipOutputStream) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val buffer = StringBuilder()

        fun write(text: String) {
            buffer.append(text)
            if (buffer.length >= 64 * 1024) flush()
        }

        fun flush() {
            if (buffer.isEmpty()) return
            val bytes = buffer.toString().toByteArray(Charsets.UTF_8)
            digest.update(bytes)
            zip.write(bytes)
            buffer.setLength(0)
        }

        fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }
}
