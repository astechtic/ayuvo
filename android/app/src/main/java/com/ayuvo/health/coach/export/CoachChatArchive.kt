package com.ayuvo.health.coach.export

import com.ayuvo.health.coach.data.CoachFileStore
import com.ayuvo.health.coach.data.CoachRepository
import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.long
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The `ayuvo-coach-chats` container (docs/coach.md §11). */
object CoachChatArchiveFormat {
    const val FORMAT = CoachReference.ARCHIVE_FORMAT
    const val FORMAT_VERSION = CoachReference.ARCHIVE_VERSION
    const val APP = "Ayuvo"
    const val PLATFORM = "android"
    const val FILE_NAME = "ayuvo-coach-chats.zip"
    const val MIME_TYPE = "application/zip"

    const val MANIFEST = "manifest.json"
    const val CONVERSATIONS = "conversations.ndjson"
    const val MESSAGES = "messages.ndjson"
    const val ATTACHMENTS = "attachments.ndjson"
    const val FILES_PREFIX = "attachments/"
    const val CHECKSUMS = "checksums.json"

    /** The order a single-pass reader needs. */
    val DATA_ENTRIES = listOf(CONVERSATIONS, MESSAGES, ATTACHMENTS)

    /**
     * Compact JSON with **sorted keys**, so a re-export is byte-identical and an Android archive and
     * an iOS one of the same store are the same bytes (iOS `RJ.jsonText` sorts too).
     */
    fun compact(value: JsonElement): String =
        MedicationJson.json.encodeToString(JsonElement.serializer(), canonical(value))

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    /** `attachments/<id>/<filename>`, with anything that could escape the folder removed. */
    fun fileEntry(attachmentId: String, filename: String): String =
        FILES_PREFIX + attachmentId + "/" + safeName(filename)

    fun safeName(filename: String): String {
        val base = filename.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.filter { it.code >= 0x20 && it != ':' && it != '"' && it != '*' && it != '?' }
        return cleaned.ifEmpty { "attachment" }.take(120)
    }
}

data class CoachChatExportResult(
    val file: File,
    val conversations: Int,
    val messages: Int,
    val attachments: Int,
    val files: Int,
    val bytes: Long
) {
    val isEmpty: Boolean get() = conversations == 0 && messages == 0
}

data class CoachChatImportResult(
    val error: String? = null,
    val counts: Map<String, Int> = emptyMap(),
    val filesRestored: Int = 0,
    val filesMissing: Int = 0
) {
    val ok: Boolean get() = error == null
    val imported: Int
        get() = (counts["conversations_inserted"] ?: 0) + (counts["conversations_updated"] ?: 0) +
            (counts["messages_inserted"] ?: 0) + (counts["messages_updated"] ?: 0)
}

/**
 * Writes the chats the user chose to export (docs/coach.md §11). Tombstones and attachments no
 * exported message references are dropped by `CoachReference.chatArchive`, not here.
 */
class CoachChatArchiveWriter(
    private val repository: CoachRepository,
    private val appVersion: String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val timeZone: () -> String = { java.util.TimeZone.getDefault().id }
) {
    suspend fun export(target: File): CoachChatExportResult {
        val archive = CoachReference.chatArchive(repository.snapshotJson())
        val conversations = archive.arr("conversations").orEmpty()
        val messages = archive.arr("messages").orEmpty()
        val attachments = archive.arr("attachments").orEmpty()
        // Only the blobs of attachments that survived the export are carried.
        val ids = attachments.filterIsInstance<JsonObject>().mapNotNull { it.str("id") }
        val blobs = repository.attachments(ids).mapNotNull { row ->
            val file = repository.files.resolve(row.filePath)?.takeIf { it.isFile } ?: return@mapNotNull null
            CoachChatArchiveFormat.fileEntry(row.id, row.filename) to file
        }

        return withContext(Dispatchers.IO) {
            write(
                target = target,
                archive = archive,
                manifest = manifest(conversations.size, messages.size, attachments.size),
                blobs = blobs.map { (name, file) -> name to file.readBytes() }
            )
            CoachChatExportResult(
                file = target,
                conversations = conversations.size,
                messages = messages.size,
                attachments = attachments.size,
                files = blobs.size,
                bytes = target.length()
            )
        }
    }

    private fun manifest(conversations: Int, messages: Int, attachments: Int): JsonObject =
        MedicationJson.obj(
            "format" to CoachChatArchiveFormat.FORMAT,
            "format_version" to CoachChatArchiveFormat.FORMAT_VERSION,
            "app" to CoachChatArchiveFormat.APP,
            "app_version" to appVersion,
            "platform" to CoachChatArchiveFormat.PLATFORM,
            "exported_at" to nowMs(),
            "zone_id" to timeZone(),
            "counts" to MedicationJson.obj(
                "conversations" to conversations,
                "messages" to messages,
                "attachments" to attachments
            )
        )

    companion object {
        private const val COPY_BUFFER = 64 * 1024

        /**
         * The container itself: manifest, the three ndjson entries, the blobs, then `checksums.json`
         * last because it covers every other entry. Nothing here reads the database, so the format
         * can be tested on its own.
         */
        fun write(target: File, archive: JsonObject, manifest: JsonObject, blobs: List<Pair<String, ByteArray>>) {
            target.parentFile?.mkdirs()
            val checksums = LinkedHashMap<String, String>()
            ZipOutputStream(BufferedOutputStream(target.outputStream())).use { zip ->
                zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)
                entry(zip, checksums, CoachChatArchiveFormat.MANIFEST) { out ->
                    out.write((CoachChatArchiveFormat.compact(manifest) + "\n").toByteArray(Charsets.UTF_8))
                }
                entry(zip, checksums, CoachChatArchiveFormat.CONVERSATIONS) {
                    ndjson(archive.arr("conversations").orEmpty(), it)
                }
                entry(zip, checksums, CoachChatArchiveFormat.MESSAGES) {
                    ndjson(archive.arr("messages").orEmpty(), it)
                }
                entry(zip, checksums, CoachChatArchiveFormat.ATTACHMENTS) {
                    ndjson(archive.arr("attachments").orEmpty(), it)
                }
                for ((name, bytes) in blobs) {
                    entry(zip, checksums, name) { out -> out.write(bytes) }
                }
                zip.putNextEntry(ZipEntry(CoachChatArchiveFormat.CHECKSUMS))
                zip.write(
                    (CoachChatArchiveFormat.compact(
                        JsonObject(checksums.mapValues { JsonPrimitive(it.value) as JsonElement })
                    ) + "\n").toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
            }
        }

        private fun ndjson(rows: List<JsonElement>, out: OutputStream) {
            for (row in rows) {
                out.write((CoachChatArchiveFormat.compact(row) + "\n").toByteArray(Charsets.UTF_8))
            }
        }

        private inline fun entry(
            zip: ZipOutputStream,
            checksums: MutableMap<String, String>,
            name: String,
            body: (OutputStream) -> Unit
        ) {
            zip.putNextEntry(ZipEntry(name))
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashing = object : OutputStream() {
                override fun write(b: Int) {
                    digest.update(b.toByte())
                    zip.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    digest.update(b, off, len)
                    zip.write(b, off, len)
                }
            }
            body(hashing)
            zip.closeEntry()
            checksums[name] = digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Reads an `ayuvo-coach-chats` archive and **merges** it (docs/coach.md §11): nothing local is ever
 * deleted, a local tombstone always wins, and a blob the archive did not carry leaves the row
 * pointing at nothing rather than failing the import.
 */
class CoachChatArchiveReader(private val repository: CoachRepository) {

    suspend fun import(source: File): CoachChatImportResult {
        val read = withContext(Dispatchers.IO) { readEntries(source) }
        val merged = CoachReference.mergeChatArchive(repository.snapshotJson(), read.archive)
        merged.str("error")?.let { return CoachChatImportResult(error = it) }
        val snapshot = merged.objOrNull("snapshot") ?: return CoachChatImportResult(error = "bad_format")
        repository.applySnapshot(snapshot)

        var restored = 0
        for ((id, payload) in read.blobs) {
            val extension = payload.first.substringAfterLast('.', "bin")
            val path = repository.files.writeOriginal(payload.second, id, extension)
            if (path != null) {
                repository.setAttachmentFile(id, path)
                restored++
            }
        }
        val counts = (merged.objOrNull("counts") ?: JsonObject(emptyMap()))
            .mapValues { (_, v) -> (v as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
        val expected = read.archive.arr("attachments").orEmpty().size
        return CoachChatImportResult(
            counts = counts,
            filesRestored = restored,
            filesMissing = (expected - restored).coerceAtLeast(0)
        )
    }

    companion object {
        data class Entries(
            val archive: JsonObject,
            /** attachment id -> (filename, bytes) */
            val blobs: Map<String, Pair<String, ByteArray>>
        )

        fun readEntries(source: File): Entries {
            var manifest: JsonObject? = null
            val conversations = mutableListOf<JsonElement>()
            val messages = mutableListOf<JsonElement>()
            val attachments = mutableListOf<JsonElement>()
            val blobs = LinkedHashMap<String, Pair<String, ByteArray>>()

            ZipInputStream(source.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        entry.isDirectory -> Unit
                        name == CoachChatArchiveFormat.MANIFEST ->
                            manifest = parseObject(zip.readBytes().toString(Charsets.UTF_8))
                        name == CoachChatArchiveFormat.CONVERSATIONS -> conversations += lines(zip.readBytes())
                        name == CoachChatArchiveFormat.MESSAGES -> messages += lines(zip.readBytes())
                        name == CoachChatArchiveFormat.ATTACHMENTS -> attachments += lines(zip.readBytes())
                        name.startsWith(CoachChatArchiveFormat.FILES_PREFIX) -> {
                            val rest = name.removePrefix(CoachChatArchiveFormat.FILES_PREFIX)
                            val id = rest.substringBefore('/')
                            val filename = rest.substringAfter('/', "")
                            if (id.isNotEmpty() && filename.isNotEmpty() && !filename.contains('/')) {
                                blobs[id] = filename to zip.readBytes()
                            }
                        }
                        else -> Unit
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val archive = MedicationJson.obj(
                "format" to (manifest?.str("format") ?: ""),
                "format_version" to (manifest?.long("format_version") ?: 0L),
                "conversations" to JsonArray(conversations),
                "messages" to JsonArray(messages),
                "attachments" to JsonArray(attachments)
            )
            return Entries(archive, blobs)
        }

        private fun lines(bytes: ByteArray): List<JsonElement> =
            bytes.toString(Charsets.UTF_8).lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { parseObject(it) }
                .toList()

        private fun parseObject(text: String): JsonObject? =
            runCatching { MedicationJson.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }
}
