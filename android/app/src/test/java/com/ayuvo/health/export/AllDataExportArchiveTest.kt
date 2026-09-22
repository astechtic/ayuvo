package com.ayuvo.health.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AllDataExportArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val createdAt = Instant.parse("2026-09-22T10:15:30Z")

    private fun nestedZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("{\"format\":\"ayuvo-health-data\"}".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun sections(): List<AllDataExportArchive.Section> {
        val diary = tmp.newFile("diary.json").apply { writeText("{\"export\":{},\"days\":[]}") }
        val health = tmp.newFile("health.zip").apply { writeBytes(nestedZip()) }
        return listOf(
            AllDataExportArchive.Section("food_diary", "ayuvo-food-diary", "food-diary/Ayuvo-Food-Diary.json", "Food diary", mapOf("food_entries" to 12L, "days" to 3L), diary),
            AllDataExportArchive.Section("health_data", "ayuvo-health-data", "health-data/Ayuvo-Health-Data.zip", "Health data", mapOf("samples" to 40L), health)
        )
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun fileNameUsesTheDate() {
        assertEquals("Ayuvo-Export-2026-09-22.zip", AllDataExportArchive.fileName(LocalDate.of(2026, 9, 22)))
    }

    @Test
    fun zipHoldsManifestFirstThenEveryExporterOutputUnchanged() {
        val input = sections()
        val out = tmp.newFile("export.zip")
        val visited = mutableListOf<String>()
        out.outputStream().use {
            AllDataExportArchive.write(
                out = it,
                sections = input,
                skipped = mapOf("medications" to AllDataExportArchive.REASON_NOT_SET_UP),
                createdAt = createdAt,
                appVersion = "9.9.9",
                onEntry = { _, s -> visited += s.id }
            )
        }
        assertEquals(listOf("food_diary", "health_data"), visited)

        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(listOf("manifest.json", "food-diary/Ayuvo-Food-Diary.json", "health-data/Ayuvo-Health-Data.zip"), names)
            input.forEach { s ->
                val entry = zip.getEntry(s.path)
                assertArrayEquals(s.source.readBytes(), zip.getInputStream(entry).readBytes())
            }
            // Already-compressed archives are stored, text is deflated.
            assertEquals(ZipEntry.STORED, zip.getEntry("health-data/Ayuvo-Health-Data.zip").method)
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("food-diary/Ayuvo-Food-Diary.json").method)
            // The nested zip is still a readable zip.
            val nested = java.util.zip.ZipInputStream(zip.getInputStream(zip.getEntry("health-data/Ayuvo-Health-Data.zip")))
            assertEquals("manifest.json", nested.nextEntry?.name)
        }
    }

    @Test
    fun manifestListsFilesCountsChecksumsAndSkippedSections() {
        val input = sections()
        val bytes = ByteArrayOutputStream().also {
            AllDataExportArchive.write(
                out = it,
                sections = input,
                skipped = linkedMapOf(
                    "medications" to AllDataExportArchive.REASON_NOT_SET_UP,
                    "health_records" to AllDataExportArchive.REASON_EMPTY
                ),
                createdAt = createdAt,
                appVersion = "9.9.9"
            )
        }.toByteArray()
        val manifest = AllDataExportArchive.readManifest(ByteArrayInputStream(bytes))!!
        assertEquals("Ayuvo", manifest.app)
        assertEquals("ayuvo-all-data", manifest.format)
        assertEquals(1, manifest.format_version)
        assertEquals("2026-09-22T10:15:30Z", manifest.created_at)
        assertEquals("9.9.9", manifest.app_version)
        assertEquals("android", manifest.platform)
        assertEquals(input.map { it.path }, manifest.files.map { it.name })
        assertEquals(listOf("ayuvo-food-diary", "ayuvo-health-data"), manifest.files.map { it.format })
        assertEquals(listOf("food_diary", "health_data"), manifest.files.map { it.section })
        manifest.files.zip(input).forEach { (file, section) ->
            assertEquals(section.source.length(), file.bytes)
            assertEquals(sha(section.source.readBytes()), file.sha256)
            assertEquals(section.counts, file.counts)
        }
        assertEquals(listOf("medications", "health_records"), manifest.skipped)
        assertEquals(mapOf("medications" to "not_set_up", "health_records" to "empty"), manifest.skipped_reasons)
    }

    @Test
    fun emptyExportStillHasAManifest() {
        val bytes = ByteArrayOutputStream().also {
            AllDataExportArchive.write(it, emptyList(), mapOf("food_diary" to "empty"), createdAt, "1.0")
        }.toByteArray()
        val manifest = AllDataExportArchive.readManifest(ByteArrayInputStream(bytes))!!
        assertTrue(manifest.files.isEmpty())
        assertEquals(1, manifest.skipped.size)
    }

    @Test
    fun readManifestIsNullForOtherZips() {
        assertNull(AllDataExportArchive.readManifest(ByteArrayInputStream(ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicatePathsAreRejected() {
        val s = sections().first()
        AllDataExportArchive.write(ByteArrayOutputStream(), listOf(s, s), emptyMap(), createdAt, "1.0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun manifestPathIsReserved() {
        val s = sections().first().copy(path = AllDataExportArchive.MANIFEST_NAME)
        AllDataExportArchive.write(ByteArrayOutputStream(), listOf(s), emptyMap(), createdAt, "1.0")
    }
}
