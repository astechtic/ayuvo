package com.ayuvo.health.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The single "Export All Data" zip (`Ayuvo-Export-YYYY-MM-DD.zip`). It carries no format of its
 * own: every entry is the unchanged output of an existing exporter (food diary JSON, Health Data
 * zip, medications JSON, Health Records archive, app backup zip), so each one can still be read
 * back by its own importer. `manifest.json` is written first and lists every file with its format,
 * size, SHA-256 and row counts, plus the sections that were skipped and why. The manifest keys match
 * iOS (`AllDataExport.Manifest`); `sha256`, `description` and `skipped_reasons` are Android extras.
 *
 * Pure JVM: the caller produces the section files, this only assembles them.
 */
object AllDataExportArchive {
    const val APP = "Ayuvo"
    const val FORMAT = "ayuvo-all-data"
    const val FORMAT_VERSION = 1
    const val MANIFEST_NAME = "manifest.json"
    const val MIME_TYPE = "application/zip"

    fun fileName(today: LocalDate = LocalDate.now()): String = "Ayuvo-Export-$today.zip"

    /** Skip reasons written to the manifest. */
    const val REASON_EMPTY = "empty"
    const val REASON_NOT_SET_UP = "not_set_up"
    const val REASON_FAILED = "failed"

    /** One exporter's output, already on disk. [path] is the entry name inside the zip, [format] its existing format id. */
    data class Section(
        val id: String,
        val format: String,
        val path: String,
        val description: String,
        val counts: Map<String, Long>,
        val source: File
    )

    val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private const val BUFFER = 64 * 1024

    /**
     * Writes the zip to [out] (not closed). Nested `.zip` entries are STORED (they are already
     * compressed); everything else is deflated. [onEntry] fires before each section is copied.
     */
    fun write(
        out: OutputStream,
        sections: List<Section>,
        skipped: Map<String, String>,
        createdAt: Instant,
        appVersion: String,
        platform: String = "android",
        onEntry: (index: Int, section: Section) -> Unit = { _, _ -> }
    ): AllDataExportManifest {
        val paths = sections.map { it.path }
        require(paths.size == paths.toSet().size) { "Duplicate entry path" }
        require(MANIFEST_NAME !in paths) { "Reserved entry path" }

        val digests = sections.map { digest(it.source) }
        val manifest = AllDataExportManifest(
            app = APP,
            format = FORMAT,
            format_version = FORMAT_VERSION,
            created_at = createdAt.toString(),
            app_version = appVersion,
            platform = platform,
            files = sections.mapIndexed { i, s ->
                AllDataExportFile(
                    name = s.path,
                    section = s.id,
                    format = s.format,
                    description = s.description,
                    bytes = digests[i].size,
                    sha256 = digests[i].sha256,
                    counts = s.counts.toSortedMap()
                )
            },
            skipped = skipped.keys.toList(),
            skipped_reasons = skipped
        )

        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST_NAME))
        zip.write(json.encodeToString(AllDataExportManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
        zip.write('\n'.code)
        zip.closeEntry()
        sections.forEachIndexed { i, s ->
            onEntry(i, s)
            val entry = ZipEntry(s.path)
            if (s.path.endsWith(".zip", ignoreCase = true)) {
                entry.method = ZipEntry.STORED
                entry.size = digests[i].size
                entry.compressedSize = digests[i].size
                entry.crc = digests[i].crc
            }
            zip.putNextEntry(entry)
            s.source.inputStream().use { it.copyTo(zip, BUFFER) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
        return manifest
    }

    /** Reads `manifest.json` back from an export (tests, and anyone checking a file by hand). */
    fun readManifest(input: InputStream): AllDataExportManifest? {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == MANIFEST_NAME) {
                    return json.decodeFromString(AllDataExportManifest.serializer(), zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
    }

    private class Digest(val size: Long, val crc: Long, val sha256: String)

    private fun digest(file: File): Digest {
        val sha = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha.update(buffer, 0, n)
                crc.update(buffer, 0, n)
                size += n
            }
        }
        return Digest(size, crc.value, sha.digest().joinToString("") { "%02x".format(it) })
    }
}

@Serializable
data class AllDataExportManifest(
    val app: String,
    val format: String,
    val format_version: Int,
    val created_at: String,
    val app_version: String,
    val platform: String,
    val files: List<AllDataExportFile>,
    /** Section ids left out of the zip (same as iOS). */
    val skipped: List<String> = emptyList(),
    /** Why each skipped section was left out: `empty`, `not_set_up` or `failed`. */
    val skipped_reasons: Map<String, String> = emptyMap()
)

@Serializable
data class AllDataExportFile(
    val name: String,
    val section: String,
    val format: String,
    /** Android extra; iOS manifests leave it out. */
    val description: String = "",
    val bytes: Long = 0,
    /** Android extra; iOS manifests leave it out. */
    val sha256: String = "",
    val counts: Map<String, Long> = emptyMap()
)
