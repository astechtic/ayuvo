package com.ayuvo.health.export

import com.ayuvo.health.data.health.HealthPageCommit
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSeriesPoint
import com.ayuvo.health.data.health.HealthSourceRow
import com.ayuvo.health.data.health.InMemoryHealthDataStore
import com.ayuvo.health.models.HealthDataType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class HealthDataImporterTest {
    private val now = Instant.parse("2026-09-14T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val day = 86_400_000L

    private fun row(id: String, type: HealthDataType, endMs: Long, value: Double, updated: Long = endMs, offsetS: Int = 3600, origin: Int = 0, extra: String? = null, count: Int = 1, v2: Double? = null, v3: Double? = null) = HealthSampleRow(
        id = id, typeId = type.id, startMs = endMs - 600_000, endMs = endMs, startOffsetS = offsetS, endOffsetS = offsetS,
        localDay = Instant.ofEpochMilli(endMs).atOffset(ZoneOffset.ofTotalSeconds(offsetS)).toLocalDate().toString(),
        value = value, value2 = v2, value3 = v3, unit = type.unit, count = count, sourceId = "com.example.watch", device = "Acme Band", deviceType = 2,
        recordingMethod = 2, clientRecordId = null, origin = origin, extraJson = extra, updatedMs = updated
    )

    private suspend fun seed(store: InMemoryHealthDataStore, rows: Int = 5_000) {
        val sample = (0 until rows).map { i ->
            if (i % 50 == 25) row("hr$i", HealthDataType.HEART_RATE, now.toEpochMilli() - i * 3_600_000L, 70.0 + i % 20, count = 3, v2 = 55.0, v3 = 120.0)
            else row("s$i", HealthDataType.STEPS, now.toEpochMilli() - i * 600_000L, (i % 300).toDouble(), extra = if (i % 500 == 0) "{\"note\":\"x\"}" else null)
        }
        val series = sample.filter { it.typeId == "heart_rate" }.flatMap { r -> (0 until 3).map { k -> HealthSeriesPoint(r.id, r.typeId, r.startMs + k * 60_000L, 60.0 + k) } }
        // Never exported: nutrition rows and tombstones.
        val hidden = listOf(
            row("nut1", HealthDataType.NUTRITION_RECORD, now.toEpochMilli() - day, 500.0),
            row("gone", HealthDataType.STEPS, now.toEpochMilli() - 2 * day, 12.0).copy(deleted = true)
        )
        store.commit(HealthPageCommit(rows = sample + hidden, seriesPoints = series, sources = listOf(HealthSourceRow("com.example.watch", "Acme Watch", "Band 3", 2, now.toEpochMilli()))))
    }

    private suspend fun export(store: InMemoryHealthDataStore): Pair<ByteArray, HealthExportResult> {
        val out = ByteArrayOutputStream()
        val result = HealthDataExporter(store, { ZoneOffset.UTC }, clock).write(out, "1.0")
        return out.toByteArray() to result
    }

    @Test
    fun roundTripPreservesRowsSeriesAndSources() = runBlocking {
        val source = InMemoryHealthDataStore()
        seed(source)
        val (zip, result) = export(source)
        assertEquals(5_000L, result.sampleCount)
        assertEquals(300L, result.seriesCount)
        assertEquals(2, result.typeCount)
        assertEquals(listOf("manifest.json", "samples.ndjson", "series.ndjson", "sources.json", "checksums.json"), entryNames(zip))

        val target = InMemoryHealthDataStore()
        val importer = HealthDataImporter(target, { ZoneOffset.UTC })
        val preview = importer.preview(ByteArrayInputStream(zip))
        assertEquals(HealthExportFormat.FORMAT, preview.manifest.format)
        assertEquals("android", preview.manifest.platform)
        assertEquals(5_000L, preview.sampleCount)
        assertEquals(300L, preview.seriesCount)
        assertTrue(preview.unknownTypes.isEmpty())
        assertEquals(0L, preview.unitMismatches)
        assertTrue(preview.hasChecksums)
        assertFalse(preview.checksumWarning)

        val applied = importer.apply(ByteArrayInputStream(zip), HealthImportMode.MERGE)
        assertEquals(5_000, applied.inserted)
        assertEquals(300, applied.seriesWritten)
        assertEquals(source.liveRows("steps").size, target.liveRows("steps").size)
        val original = source.samples.getValue("s0")
        val imported = target.samples.getValue("s0")
        assertEquals(original.copy(origin = HealthSampleRow.ORIGIN_IMPORT), imported)
        assertEquals(3, target.seriesPoints("heart_rate", 0, Long.MAX_VALUE).count { it.sampleId == "hr25" })
        assertEquals("Acme Watch", target.sources().single().name)
        assertFalse(target.samples.containsKey("nut1"))
        assertFalse(target.samples.containsKey("gone"))
        // Rollups were rebuilt for the imported days.
        assertTrue(target.dailyRollups("steps", "2026-01-01", "2026-12-31").isNotEmpty())
        assertEquals("{\"note\":\"x\"}", target.samples.getValue("s0").extraJson)
    }

    @Test
    fun mergeKeepsNewerRowsAndTombstonesAndNeverDeletes() = runBlocking {
        val source = InMemoryHealthDataStore()
        source.commit(HealthPageCommit(rows = listOf(row("a", HealthDataType.WEIGHT, now.toEpochMilli() - day, 80.0, updated = 10), row("b", HealthDataType.WEIGHT, now.toEpochMilli() - 2 * day, 81.0, updated = 10))))
        val (zip, _) = export(source)
        val target = InMemoryHealthDataStore()
        target.commit(HealthPageCommit(rows = listOf(
            row("a", HealthDataType.WEIGHT, now.toEpochMilli() - day, 79.0, updated = 20),   // newer locally → kept
            row("c", HealthDataType.WEIGHT, now.toEpochMilli() - 3 * day, 82.0, updated = 5) // only local → kept
        )))
        target.commit(HealthPageCommit(deletedIds = listOf("c")))
        target.commit(HealthPageCommit(rows = listOf(row("b", HealthDataType.WEIGHT, now.toEpochMilli() - 2 * day, 99.0, updated = 1))))
        target.commit(HealthPageCommit(deletedIds = listOf("b")))

        val result = HealthDataImporter(target, { ZoneOffset.UTC }).apply(ByteArrayInputStream(zip), HealthImportMode.MERGE)
        assertEquals(0, result.inserted)
        assertEquals(79.0, target.samples.getValue("a").value!!, 0.0)
        assertTrue("tombstone must win over the import", target.samples.getValue("b").deleted)
        assertTrue(target.samples.getValue("c").deleted)
    }

    @Test
    fun importedRowsAreImmuneToPlatformDeletionsAndReplaceWipesFirst() = runBlocking {
        val source = InMemoryHealthDataStore()
        source.commit(HealthPageCommit(rows = listOf(row("x", HealthDataType.STEPS, now.toEpochMilli() - day, 100.0))))
        val (zip, _) = export(source)
        val target = InMemoryHealthDataStore()
        target.commit(HealthPageCommit(rows = listOf(row("local", HealthDataType.STEPS, now.toEpochMilli(), 5.0))))
        HealthDataImporter(target, { ZoneOffset.UTC }).apply(ByteArrayInputStream(zip), HealthImportMode.MERGE)
        assertEquals(HealthSampleRow.ORIGIN_IMPORT, target.samples.getValue("x").origin)
        target.commit(HealthPageCommit(deletedIds = listOf("x", "local")))
        assertFalse(target.samples.getValue("x").deleted)
        assertTrue(target.samples.getValue("local").deleted)

        HealthDataImporter(target, { ZoneOffset.UTC }).apply(ByteArrayInputStream(zip), HealthImportMode.REPLACE_ALL)
        assertEquals(setOf("x"), target.samples.keys)
    }

    @Test
    fun unknownTypesLandInOtherAndUnitMismatchesAreSkipped() = runBlocking {
        val lines = listOf(
            sampleLine("k1", "hrv_sdnn", "ms", 42.0),
            sampleLine("k2", "HKQuantityTypeIdentifierMysteryMetric", "count", 1.0),
            sampleLine("k3", "weight", "lb", 170.0) // wrong unit → rejected, counted
        )
        val zip = buildZip(manifestJson(1), lines, stored = false)
        val target = InMemoryHealthDataStore()
        val importer = HealthDataImporter(target, { ZoneOffset.UTC })
        val preview = importer.preview(ByteArrayInputStream(zip))
        assertEquals(listOf("HKQuantityTypeIdentifierMysteryMetric"), preview.unknownTypes)
        assertEquals(1L, preview.unitMismatches)
        assertFalse(preview.hasChecksums)
        val result = importer.apply(ByteArrayInputStream(zip), HealthImportMode.MERGE)
        assertEquals(2, result.inserted)
        assertEquals(1, result.skippedUnitMismatch)
        val meta = target.typeMeta().single()
        assertEquals("HKQuantityTypeIdentifierMysteryMetric", meta.typeId)
        assertEquals("other", meta.category)
        assertEquals("count", meta.unit)
        assertEquals(HealthSampleRow.ORIGIN_IMPORT, target.samples.getValue("k1").origin)
        assertFalse(target.samples.containsKey("k3"))
    }

    @Test
    fun rejectsForeignFormatsNewerMajorsAndOversizedLines() = runBlocking {
        val importer = HealthDataImporter(InMemoryHealthDataStore(), { ZoneOffset.UTC })
        val foreign = buildZip("""{"format":"ayuvo-cloud-backup","format_version":1,"platform":"ios","app_version":"1","exported_at":"x","zone_id":"UTC"}""", emptyList(), stored = false)
        assertTrue(runCatching { importer.preview(ByteArrayInputStream(foreign)) }.exceptionOrNull() is HealthImportException)
        val newer = buildZip(manifestJson(2), emptyList(), stored = false)
        val error = runCatching { importer.preview(ByteArrayInputStream(newer)) }.exceptionOrNull()
        assertTrue(error is HealthImportException && error.message!!.contains("newer"))
        val huge = buildZip(manifestJson(1), listOf(sampleLine("big", "steps", "count", 1.0, title = "x".repeat(70_000))), stored = false)
        assertTrue(runCatching { importer.apply(ByteArrayInputStream(huge), HealthImportMode.MERGE) }.exceptionOrNull() is HealthImportException)
        assertTrue(runCatching { importer.preview(ByteArrayInputStream(ByteArray(0)), sizeBytes = HealthExportFormat.MAX_FILE_BYTES + 1) }.exceptionOrNull() is HealthImportException)
    }

    @Test
    fun storedEntriesAndChecksumMismatchesAreHandled() = runBlocking {
        // iOS writes stored (method 0) entries; a tampered samples entry must raise the checksum warning.
        val lines = listOf(sampleLine("i1", "steps", "count", 12.0))
        val zip = buildZip(manifestJson(1, platform = "ios"), lines, stored = true, checksums = mapOf("samples.ndjson" to "00".repeat(32)))
        val importer = HealthDataImporter(InMemoryHealthDataStore(), { ZoneOffset.UTC })
        val preview = importer.preview(ByteArrayInputStream(zip))
        assertEquals("ios", preview.manifest.platform)
        assertEquals(1L, preview.sampleCount)
        assertTrue(preview.hasChecksums)
        assertTrue(preview.checksumWarning)
        val target = InMemoryHealthDataStore()
        HealthDataImporter(target, { ZoneOffset.UTC }).apply(ByteArrayInputStream(zip), HealthImportMode.MERGE)
        assertEquals(12.0, target.samples.getValue("i1").value!!, 0.0)
        assertEquals("2026-09-13", target.samples.getValue("i1").localDay)
    }

    @Test
    fun exportFormatRoundTripsOffsetsAndRecomputesLocalDay() {
        val original = row("r", HealthDataType.SLEEP, Instant.parse("2026-09-13T05:30:00Z").toEpochMilli(), 3600.0, offsetS = -5 * 3600)
        val sample = HealthExportFormat.sampleFromRow(original, "Watch", ZoneOffset.UTC)
        assertEquals("2026-09-13T00:30:00-05:00", sample.end)
        assertEquals("Watch", sample.source_name)
        val back = HealthExportFormat.rowFromSample(sample, ZoneOffset.UTC)
        assertEquals(original.startMs, back.startMs)
        assertEquals(-5 * 3600, back.endOffsetS)
        // Sleep keys by wake day under its own offset → Sep 13, not the UTC Sep 13 05:30 → same here; a UTC-only reader would still agree.
        assertEquals("2026-09-13", back.localDay)
        assertEquals(HealthSampleRow.ORIGIN_IMPORT, back.origin)
        assertNotNull(HealthExportFormat.encodeSample(sample))
    }

    @Test
    fun writesTheCrossPlatformFixtureWhenRequested() = runBlocking {
        val dir = System.getenv("AYUVO_HEALTH_FIXTURE_DIR") ?: return@runBlocking
        val store = InMemoryHealthDataStore()
        seed(store, rows = 400)
        store.commit(HealthPageCommit(rows = listOf(
            row("bp1", HealthDataType.BLOOD_PRESSURE, now.toEpochMilli() - day, 121.0, v2 = 79.0),
            row("sl", HealthDataType.SLEEP, now.toEpochMilli() - 5 * 3_600_000L, 8 * 3600.0).copy(startMs = now.toEpochMilli() - 13 * 3_600_000L, categoryValue = 0),
            row("sl:0", HealthDataType.SLEEP, now.toEpochMilli() - 5 * 3_600_000L, 8 * 3600.0).copy(startMs = now.toEpochMilli() - 13 * 3_600_000L, categoryValue = 3, extraJson = "{\"session_id\":\"sl\"}")
        )))
        val (zip, _) = export(store)
        val file = File(dir, "ayuvo-health-android-fixture.zip")
        file.parentFile?.mkdirs()
        file.writeBytes(zip)
        assertTrue(file.length() > 0)
    }

    // -- helpers -----------------------------------------------------------------

    private fun entryNames(zip: ByteArray): List<String> = ZipInputStream(ByteArrayInputStream(zip)).use { z ->
        generateSequence { z.nextEntry }.map { it.name }.toList()
    }

    private fun manifestJson(version: Int, platform: String = "android") =
        """{"format":"ayuvo-health-data","format_version":$version,"platform":"$platform","app_version":"1.0","exported_at":"2026-09-14T12:00:00Z","zone_id":"UTC","registry_version":1,"percent_convention":"0-100","types":[]}"""

    private fun sampleLine(id: String, type: String, unit: String, value: Double, title: String? = null) =
        """{"id":"$id","type_id":"$type","start":"2026-09-13T10:00:00+02:00","end":"2026-09-13T10:10:00+02:00","updated":"2026-09-13T10:10:00Z","value":$value,"unit":"$unit","count":1,"source_id":"com.example.app","origin":0${title?.let { ",\"title\":\"$it\"" } ?: ""}}"""

    private fun buildZip(manifest: String, samples: List<String>, stored: Boolean, checksums: Map<String, String>? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            put("manifest.json", manifest)
            put("samples.ndjson", samples.joinToString("\n", postfix = if (samples.isEmpty()) "" else "\n"))
            put("sources.json", "[]")
            checksums?.let { put("checksums.json", HealthExportFormat.json.encodeToString(HealthExportFormat.checksumsSerializer, it)) }
        }
        return out.toByteArray()
    }
}
